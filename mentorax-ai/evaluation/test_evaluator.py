import json
import math
import unittest
from pathlib import Path

from evaluator import ranking_metrics, summarize, validate_dataset, validate_ranking_result
from app import Candidate, RankingRequest, rank_candidates


class EvaluationMetricTests(unittest.TestCase):
    def test_manually_verified_ranking_metrics(self):
        result = ranking_metrics(["4", "2", "1"], {"2": 2, "4": 1, "1": 0}, candidate_count=3)
        self.assertAlmostEqual(2 / 3, result["precisionAt3"])
        self.assertEqual(1.0, result["recallAt3"])
        self.assertEqual(1.0, result["hitRateAt3"])
        self.assertEqual(1.0, result["mrrAt3"])
        expected_ndcg = (1 + 3 / math.log2(3)) / (3 + 1 / math.log2(3))
        self.assertAlmostEqual(expected_ndcg, result["ndcgAt3"])

    def test_empty_candidate_and_no_relevant_edge_cases_are_explicit(self):
        empty = ranking_metrics([], {"1": 2}, candidate_count=1)
        self.assertEqual(0.0, empty["precisionAt3"])
        self.assertEqual(0.0, empty["recallAt3"])
        self.assertEqual(0.0, empty["mrrAt3"])
        self.assertEqual(0.0, empty["candidateCoverage"])
        no_relevant = ranking_metrics(["1"], {}, candidate_count=1)
        self.assertIsNone(no_relevant["recallAt3"])
        self.assertIsNone(no_relevant["mrrAt3"])
        self.assertIsNone(no_relevant["ndcgAt3"])

    def test_partial_ranking_precision_uses_candidate_pool_and_reports_coverage(self):
        partial = ranking_metrics(["1"], {"1": 2, "2": 1, "3": 0, "4": 0}, candidate_count=4)
        self.assertAlmostEqual(1 / 3, partial["precisionAt3"])
        self.assertAlmostEqual(1 / 4, partial["precisionAt5"])
        self.assertAlmostEqual(1 / 4, partial["candidateCoverage"])

        summary = summarize(
            [{"id": "partial", "category": "test", "relevance": {"1": 2}}],
            {"partial": ["1"]}, candidate_count=4)
        coverage = summary["overall"]["candidateCoverage"]
        self.assertEqual({"value": 0.25, "numerator": 1, "denominator": 4,
                          "excludedUndefined": 0}, coverage)

    def test_precision_denominator_is_bounded_by_smaller_candidate_pool(self):
        result = ranking_metrics([1], {"1": 2, "2": 0}, candidate_count=2)
        self.assertEqual(0.5, result["precisionAt3"])

    def test_invalid_candidate_pool_size_is_rejected(self):
        for count in (-1, 0, 1.5, True):
            with self.subTest(count=count), self.assertRaises(ValueError):
                ranking_metrics([1], {"1": 2}, candidate_count=count)

    def test_duplicate_ranked_ids_and_invalid_labels_are_rejected(self):
        with self.assertRaises(ValueError):
            ranking_metrics([1, 1], {"1": 2}, candidate_count=2)
        for labels in ({"1": 3}, {"1": float("nan")}, {"1": -1}):
            with self.subTest(labels=labels), self.assertRaises(ValueError):
                ranking_metrics([1], labels, candidate_count=1)

    def test_fixed_dataset_schema_and_labels_validate(self):
        path = Path(__file__).with_name("dataset.json")
        dataset = json.loads(path.read_text(encoding="utf-8"))
        self.assertTrue(validate_dataset(dataset))
        # Labels are fixed annotations, structurally separate from runtime predictions.
        self.assertIn("relevance", dataset["questions"][0])
        self.assertNotIn("predictedIntent", dataset["questions"][0])
        self.assertNotIn("recommendedIds", dataset["questions"][0])

    def test_invalid_profile_fixture_and_labels_fail_schema_validation(self):
        dataset = json.loads(Path(__file__).with_name("dataset.json").read_text(encoding="utf-8"))
        missing_profile_field = json.loads(json.dumps(dataset))
        del missing_profile_field["profiles"][0]["company"]
        with self.assertRaises(ValueError):
            validate_dataset(missing_profile_field)
        duplicate_profile = json.loads(json.dumps(dataset))
        duplicate_profile["profiles"][1]["id"] = duplicate_profile["profiles"][0]["id"]
        with self.assertRaises(ValueError):
            validate_dataset(duplicate_profile)

    def test_malformed_ranking_contract_is_rejected(self):
        valid = {"rankedIds": [1, 2], "scores": {"1": 0.7, "2": 0.2}}
        self.assertTrue(validate_ranking_result(valid, [1, 2]))
        missing_candidate = {"rankedIds": [1], "scores": {"1": 0.7}}
        empty_result = {"rankedIds": [], "scores": {}}
        self.assertTrue(validate_ranking_result(missing_candidate, [1, 2]))
        self.assertTrue(validate_ranking_result(empty_result, [1, 2]))
        invalid_results = [
            {"rankedIds": [1, 1], "scores": {"1": 0.7, "2": 0.2}},
            {"rankedIds": [3], "scores": {"3": 0.7}},
            {"rankedIds": [1, 2], "scores": {"1": 0.7}},
            {"rankedIds": [1, 2], "scores": {"1": float("nan"), "2": 0.2}},
            {"rankedIds": [1, 2], "scores": {"1": 1.1, "2": 0.2}},
        ]
        for result in invalid_results:
            with self.subTest(result=result), self.assertRaises(ValueError):
                validate_ranking_result(result, [1, 2])
        with self.assertRaises(ValueError):
            validate_ranking_result(valid, [1, 1])

    def test_equal_tfidf_scores_have_deterministic_id_tie_break(self):
        result = rank_candidates(RankingRequest(goal="shared term", candidates=[
            Candidate(id=9, bio="shared term"), Candidate(id=2, bio="shared term")]))
        self.assertEqual([2, 9], result["rankedIds"])


if __name__ == "__main__":
    unittest.main()
