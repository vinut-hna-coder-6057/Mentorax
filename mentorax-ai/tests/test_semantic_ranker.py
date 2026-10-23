import unittest
from concurrent.futures import ThreadPoolExecutor
from threading import Event, Lock

import numpy as np

from semantic_ranker import READINESS_PROBE, SemanticEmbeddingError, SentenceEmbeddingRanker


class ControlledModel:
    def __init__(self, vectors=None):
        self.vectors = vectors or {}
        self.calls = []

    def encode(self, texts, **_kwargs):
        self.calls.append(list(texts))
        return np.asarray([self.vectors.get(text, [1.0, 0.0]) for text in texts])


class SemanticRankerTests(unittest.TestCase):
    def test_background_initialization_is_single_flight_and_probes_model(self):
        load_entered = Event()
        release_load = Event()
        model = ControlledModel()
        load_calls = []

        def load_model(_name):
            load_calls.append(_name)
            load_entered.set()
            if not release_load.wait(timeout=3):
                raise TimeoutError("test did not release model loading")
            return model

        ranker = SentenceEmbeddingRanker(model_loader=load_model)
        first_future = ranker.start_model_initialization()
        self.assertTrue(load_entered.wait(timeout=3), "background initialization should start")
        with ThreadPoolExecutor(max_workers=8) as pool:
            initialization_futures = [first_future] + list(pool.map(
                lambda _index: ranker.start_model_initialization(), range(8)))
        self.assertTrue(all(future is first_future for future in initialization_futures))
        self.assertEqual("loading", ranker.model_state)
        self.assertFalse(first_future.done())

        release_load.set()
        first_future.result(timeout=3)

        self.assertEqual("ready", ranker.model_state)
        self.assertEqual(1, len(load_calls))
        self.assertEqual([[READINESS_PROBE]], model.calls)
        self.assertEqual([1.0], ranker.rank("goal", ["profile"]))
        self.assertEqual("ready", ranker.model_state)

    def test_background_initialization_failure_is_sticky_and_private(self):
        load_calls = []

        def fail_to_load(_name):
            load_calls.append(True)
            raise OSError("private model path")

        ranker = SentenceEmbeddingRanker(model_loader=fail_to_load)
        future = ranker.start_model_initialization()
        future.result(timeout=3)

        self.assertEqual("failed", ranker.model_state)
        self.assertIs(future, ranker.start_model_initialization())
        with self.assertRaisesRegex(SemanticEmbeddingError, "could not be loaded") as error:
            ranker.rank("private goal", ["private profile"])
        self.assertNotIn("private model path", str(error.exception))
        self.assertEqual(1, len(load_calls))

    def test_model_is_not_ready_until_readiness_embedding_is_valid(self):
        model = ControlledModel({READINESS_PROBE: [0.0, 0.0]})
        ranker = SentenceEmbeddingRanker(model_loader=lambda _name: model)

        ranker.start_model_initialization().result(timeout=3)

        self.assertEqual("failed", ranker.model_state)
        self.assertIsNone(ranker.model_warmup_seconds)
        with self.assertRaisesRegex(SemanticEmbeddingError, "failed readiness validation"):
            ranker.rank("goal", ["profile"])

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
        self.assertEqual(2, len(model.calls))  # one readiness probe plus one ranking call

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
        self.assertEqual(3, len(model.calls))  # probe plus two ranking calls
        self.assertEqual(2, ranker.cache_entries)

    def test_profile_cache_is_bounded(self):
        model = ControlledModel()
        ranker = SentenceEmbeddingRanker(model_loader=lambda _name: model, cache_size=1)

        ranker.rank("career goal", ["profile one"])
        ranker.rank("career goal", ["profile two"])

        self.assertEqual(1, ranker.cache_entries)

    def test_concurrent_cache_misses_encode_without_holding_cache_lock(self):
        calls_lock = Lock()
        both_encodes_entered = Event()
        release_encodes = Event()
        load_calls = []

        class ConcurrentModel:
            def __init__(self):
                self.calls = []

            def encode(self, texts, **_kwargs):
                if texts == [READINESS_PROBE]:
                    return np.asarray([[1.0, 0.0]])
                with calls_lock:
                    self.calls.append(list(texts))
                    if len(self.calls) == 2:
                        both_encodes_entered.set()
                # Keep the first two concurrent requests inside inference
                # until the test confirms both entered. If inference holds
                # the shared cache lock, the second request cannot signal.
                # The later sequential cache verification is not gated.
                if not release_encodes.wait(timeout=10):
                    raise TimeoutError("test did not release concurrent encodes")
                vectors = {
                    "goal one": [1.0, 0.0],
                    "goal two": [0.0, 1.0],
                    "shared profile": [1.0, 0.0],
                }
                return np.asarray([vectors[text] for text in texts])

        model = ConcurrentModel()

        def load_model(_name):
            with calls_lock:
                load_calls.append(_name)
            return model

        ranker = SentenceEmbeddingRanker(model_loader=load_model, cache_size=1)
        with ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(ranker.rank, "goal one", ["shared profile"])
            second = pool.submit(ranker.rank, "goal two", ["shared profile"])
            try:
                self.assertTrue(both_encodes_entered.wait(timeout=10),
                                "both concurrent requests must reach inference")
            finally:
                release_encodes.set()
            first_scores = first.result(timeout=15)
            second_scores = second.result(timeout=15)

        self.assertEqual(1, len(load_calls), "model initialization must happen once")
        self.assertEqual(2, len(model.calls), "overlapping misses may both encode")
        self.assertEqual(1, sum("shared profile" in call for call in model.calls),
                         "overlapping requests should share the profile embedding")
        self.assertAlmostEqual(1.0, first_scores[0])
        self.assertAlmostEqual(0.0, second_scores[0])
        self.assertEqual(1, ranker.cache_entries)

        # The committed vector remains correct after the concurrent race.
        cached_score = ranker.rank("goal one", ["shared profile"])
        self.assertAlmostEqual(1.0, cached_score[0])
        self.assertEqual(["goal one"], model.calls[-1])

    def test_simultaneous_distinct_cache_misses_keep_each_result_correct(self):
        both_encodes_entered = Event()
        release_encodes = Event()
        encode_lock = Lock()

        class ConcurrentModel:
            def __init__(self):
                self.calls = 0

            def encode(self, texts, **_kwargs):
                if texts == [READINESS_PROBE]:
                    return np.asarray([[1.0, 0.0]])
                with encode_lock:
                    self.calls += 1
                    if self.calls == 2:
                        both_encodes_entered.set()
                if not release_encodes.wait(timeout=10):
                    raise TimeoutError("test did not release concurrent encodes")
                vectors = {
                    "goal x": [1.0, 0.0],
                    "goal y": [0.0, 1.0],
                    "profile x": [1.0, 0.0],
                    "profile y": [0.0, 1.0],
                }
                return np.asarray([vectors[text] for text in texts])

        model = ConcurrentModel()
        ranker = SentenceEmbeddingRanker(
            model_loader=lambda _name: model, cache_size=2)
        with ThreadPoolExecutor(max_workers=2) as pool:
            x = pool.submit(ranker.rank, "goal x", ["profile x"])
            y = pool.submit(ranker.rank, "goal y", ["profile y"])
            try:
                self.assertTrue(both_encodes_entered.wait(timeout=10),
                                "distinct concurrent misses must both reach inference")
            finally:
                release_encodes.set()
            self.assertAlmostEqual(1.0, x.result(timeout=15)[0])
            self.assertAlmostEqual(1.0, y.result(timeout=15)[0])
        self.assertEqual(2, ranker.cache_entries)

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
                if texts == [READINESS_PROBE]:
                    return np.ones((1, 2))
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
