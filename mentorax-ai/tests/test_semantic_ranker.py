import unittest

import numpy as np

from semantic_ranker import SemanticEmbeddingError, SentenceEmbeddingRanker


class ControlledModel:
    def __init__(self, vectors=None):
        self.vectors = vectors or {}
        self.calls = []

    def encode(self, texts, **_kwargs):
        self.calls.append(list(texts))
        return np.asarray([self.vectors.get(text, [1.0, 0.0]) for text in texts])


class SemanticRankerTests(unittest.TestCase):
    def test_semantically_equivalent_wording_ranks_strong_profile_over_unrelated_one(self):
        model = ControlledModel({
            "How do I get ready for software engineering interviews?": [1.0, 0.0],
            "Backend developer experienced in technical hiring and interview preparation": [0.98, 0.2],
            "Cloud infrastructure, Kubernetes, and deployment automation": [0.0, 1.0],
        })
        ranker = SentenceEmbeddingRanker(model_loader=lambda _name: model)

        scores = ranker.rank(
            "How do I get ready for software engineering interviews?",
            ["Backend developer experienced in technical hiring and interview preparation",
             "Cloud infrastructure, Kubernetes, and deployment automation"],
        )

        self.assertGreater(scores[0], scores[1])
        self.assertEqual(1, len(model.calls))

    def test_weak_and_missing_profiles_score_below_strong_profile(self):
        model = ControlledModel({
            "How can I move into machine learning?": [1.0, 0.0],
            "Machine learning engineer": [0.9, 0.1],
            "General software developer": [0.5, 0.5],
        })
        ranker = SentenceEmbeddingRanker(model_loader=lambda _name: model)

        scores = ranker.rank("How can I move into machine learning?",
                             ["Machine learning engineer", "General software developer", ""])

        self.assertGreater(scores[0], scores[1])
        self.assertGreater(scores[1], scores[2])
        self.assertEqual(0.0, scores[2])

    def test_unrelated_profile_with_surface_keyword_overlap_scores_below_career_match(self):
        model = ControlledModel({
            "How should I prepare for cloud engineering interviews?": [1.0, 0.0],
            "Cloud engineer mentoring teams through technical interviews": [0.95, 0.05],
            "Cloud-themed art director who interviews models for photo shoots": [0.1, 0.9],
        })
        ranker = SentenceEmbeddingRanker(model_loader=lambda _name: model)

        scores = ranker.rank(
            "How should I prepare for cloud engineering interviews?",
            ["Cloud engineer mentoring teams through technical interviews",
             "Cloud-themed art director who interviews models for photo shoots"],
        )

        self.assertGreater(scores[0], scores[1])

    def test_changed_profile_text_uses_new_embedding_instead_of_stale_cache(self):
        model = ControlledModel({
            "career goal": [1.0, 0.0],
            "old profile": [1.0, 0.0],
            "updated profile": [0.0, 1.0],
        })
        ranker = SentenceEmbeddingRanker(model_loader=lambda _name: model)

        first = ranker.rank("career goal", ["old profile"])
        second = ranker.rank("career goal", ["updated profile"])

        self.assertGreater(first[0], second[0])
        self.assertEqual(2, len(model.calls))
        self.assertEqual(2, ranker.cache_entries)

    def test_profile_cache_is_bounded(self):
        model = ControlledModel()
        ranker = SentenceEmbeddingRanker(model_loader=lambda _name: model, cache_size=1)

        ranker.rank("career goal", ["profile one"])
        ranker.rank("career goal", ["profile two"])

        self.assertEqual(1, ranker.cache_entries)

    def test_model_load_failure_is_reported_without_echoing_input(self):
        calls = []

        def fail_to_load(_name):
            calls.append(_name)
            raise OSError("private model path")

        ranker = SentenceEmbeddingRanker(model_loader=fail_to_load)
        with self.assertRaisesRegex(SemanticEmbeddingError, "could not be loaded") as error:
            ranker.rank("private student question", ["private profile text"])
        self.assertNotIn("private", str(error.exception))
        with self.assertRaisesRegex(SemanticEmbeddingError, "could not be loaded"):
            ranker.rank("another question", ["another profile"])
        self.assertEqual(1, len(calls), "failed model initialization must not retry downloads per request")

    def test_invalid_nonfinite_or_mismatched_vectors_are_rejected(self):
        nonfinite_model = ControlledModel({"goal": [np.nan, 0.0], "profile": [1.0, 0.0]})
        with self.assertRaisesRegex(SemanticEmbeddingError, "non-finite"):
            SentenceEmbeddingRanker(model_loader=lambda _name: nonfinite_model).rank("goal", ["profile"])

        class ChangingDimensionModel:
            def __init__(self):
                self.calls = 0

            def encode(self, texts, **_kwargs):
                dimensions = 2 if self.calls == 0 else 3
                self.calls += 1
                return np.ones((len(texts), dimensions))

        changing_model = ChangingDimensionModel()
        changing_ranker = SentenceEmbeddingRanker(model_loader=lambda _name: changing_model)
        changing_ranker.rank("goal", ["profile"])
        with self.assertRaisesRegex(SemanticEmbeddingError, "dimensions do not match"):
            changing_ranker.rank("goal", ["profile"])


if __name__ == "__main__":
    unittest.main()
