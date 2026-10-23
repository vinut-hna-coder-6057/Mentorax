import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from app import MAX_REQUEST_BYTES, app
from semantic_ranker import SemanticEmbeddingError


class MentorRankerApiTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(app)
        self.client.__enter__()

    def tearDown(self):
        self.client.__exit__(None, None, None)

    def test_health_endpoint(self):
        response = self.client.get("/health")

        self.assertEqual(200, response.status_code)
        self.assertEqual({"status": "ok"}, response.json())

    def test_relevant_candidate_ranks_first_with_finite_cosine_scores(self):
        response = self.client.post("/rank", json={
            "goal": "Python machine learning engineer",
            "candidates": [
                {"id": 7, "jobTitle": "Gardener", "skills": ["plant care"]},
                {"id": 3, "jobTitle": "ML Engineer", "skills": ["Python", "machine learning"]},
            ],
        })

        self.assertEqual(200, response.status_code)
        result = response.json()
        self.assertEqual([3, 7], result["rankedIds"])
        self.assertEqual("TF-IDF cosine similarity", result["rankingMethod"])
        self.assertEqual({"3", "7"}, set(result["scores"]))
        for score in result["scores"].values():
            self.assertIsInstance(score, float)
            self.assertGreaterEqual(score, 0.0)
            self.assertLessEqual(score, 1.0)

    def test_semantic_strategy_is_selected_and_model_failure_is_explicit(self):
        with patch("app.semantic_ranker.rank", return_value=[0.2, 0.9]) as ranker:
            response = self.client.post("/rank", json={
                "goal": "How can I prepare for software engineering interviews?",
                "strategy": "semantic",
                "candidates": [{"id": 5}, {"id": 2}],
            })

        self.assertEqual(200, response.status_code)
        self.assertEqual([2, 5], response.json()["rankedIds"])
        self.assertEqual("Sentence embedding cosine similarity", response.json()["rankingMethod"])
        ranker.assert_called_once()

        with patch("app.semantic_ranker.rank", side_effect=SemanticEmbeddingError("model unavailable")):
            unavailable = self.client.post("/rank", json={
                "goal": "software engineering interviews", "strategy": "semantic",
                "candidates": [{"id": 1, "skills": ["software engineering"]}],
            })

        self.assertEqual(503, unavailable.status_code)
        self.assertEqual({"detail": "Semantic ranking unavailable"}, unavailable.json())

    def test_hybrid_strategy_combines_rank_percentiles_not_raw_scores(self):
        with patch("app.tfidf_scores", return_value=[0.9, 0.3]), \
                patch("app.semantic_ranker.rank", return_value=[0.2, 0.8]), \
                patch.dict("os.environ", {"MENTOR_HYBRID_TFIDF_WEIGHT": "0.75"}):
            response = self.client.post("/rank", json={
                "goal": "software engineering interviews", "strategy": "hybrid",
                "candidates": [{"id": 8}, {"id": 3}],
            })

        self.assertEqual(200, response.status_code)
        self.assertEqual([8, 3], response.json()["rankedIds"])
        self.assertEqual("Hybrid rank-percentile fusion", response.json()["rankingMethod"])
        self.assertAlmostEqual(0.75, response.json()["scores"]["8"])
        self.assertAlmostEqual(0.25, response.json()["scores"]["3"])

    def test_unknown_strategy_is_rejected_safely(self):
        response = self.client.post("/rank", json={
            "goal": "career advice", "strategy": "unknown", "candidates": [{"id": 1}],
        })

        self.assertEqual(422, response.status_code)
        self.assertEqual({"detail": "Invalid ranking request"}, response.json())

    def test_vectorization_failure_returns_explicit_service_error(self):
        with patch("app.TfidfVectorizer.fit_transform", side_effect=ValueError("empty vocabulary")):
            response = self.client.post("/rank", json={
                "goal": "the and",
                "candidates": [{"id": 1, "skills": ["the"]}],
            })

        self.assertEqual(503, response.status_code)
        self.assertEqual({"detail": "TF-IDF vectorization failed"}, response.json())

    def test_missing_profile_fields_and_unicode_are_safe(self):
        response = self.client.post("/rank", json={
            "goal": "機械学習の仕事",
            "candidates": [
                {"id": 4, "skills": ["機械学習"]},
                {"id": 9},
            ],
        })

        self.assertEqual(200, response.status_code)
        self.assertEqual([4, 9], response.json()["rankedIds"])

    def test_repeated_requests_do_not_reuse_prior_candidate_data(self):
        python_goal = self.client.post("/rank", json={
            "goal": "Python developer",
            "candidates": [{"id": 1, "skills": ["Python"]}, {"id": 2, "skills": ["gardening"]}],
        }).json()
        garden_goal = self.client.post("/rank", json={
            "goal": "gardening",
            "candidates": [{"id": 1, "skills": ["Python"]}, {"id": 2, "skills": ["gardening"]}],
        }).json()

        self.assertEqual([1, 2], python_goal["rankedIds"])
        self.assertEqual([2, 1], garden_goal["rankedIds"])

    def test_blank_goal_empty_candidates_and_duplicate_ids_are_rejected(self):
        blank = self.client.post("/rank", json={"goal": "   ", "candidates": [{"id": 1}]})
        empty = self.client.post("/rank", json={"goal": "design", "candidates": []})
        duplicate = self.client.post("/rank", json={
            "goal": "design", "candidates": [{"id": 1}, {"id": 1}],
        })

        self.assertEqual(422, blank.status_code)
        self.assertEqual(422, empty.status_code)
        self.assertEqual(422, duplicate.status_code)

    def test_missing_fields_invalid_types_and_oversized_fields_are_rejected_safely(self):
        missing_id = self.client.post("/rank", json={"goal": "design", "candidates": [{}]})
        invalid_id = self.client.post("/rank", json={"goal": "design", "candidates": [{"id": "bad"}]})
        oversized_goal = self.client.post("/rank", json={
            "goal": "x" * 501, "candidates": [{"id": 1}],
        })
        oversized_bio = self.client.post("/rank", json={
            "goal": "design", "candidates": [{"id": 1, "bio": "x" * 1001}],
        })
        too_many_candidates = self.client.post("/rank", json={
            "goal": "design", "candidates": [{"id": candidate_id} for candidate_id in range(501)],
        })
        too_many_skills = self.client.post("/rank", json={
            "goal": "design", "candidates": [{"id": 1, "skills": ["skill"] * 51}],
        })
        max_skills = self.client.post("/rank", json={
            "goal": "design", "candidates": [{"id": 2, "skills": ["skill"] * 50}],
        })

        for response in (missing_id, invalid_id, oversized_goal, oversized_bio,
                         too_many_candidates, too_many_skills):
            self.assertEqual(422, response.status_code)
            self.assertEqual({"detail": "Invalid ranking request"}, response.json())
            self.assertNotIn("Traceback", response.text)
            self.assertNotIn("app.py", response.text)
        self.assertEqual(200, max_skills.status_code)
        self.assertEqual([2], max_skills.json()["rankedIds"])

    def test_malformed_json_is_a_safe_validation_error(self):
        response = self.client.post("/rank", content="{", headers={"content-type": "application/json"})

        self.assertEqual(422, response.status_code)
        self.assertEqual({"detail": "Invalid ranking request"}, response.json())
        self.assertNotIn("Traceback", response.text)

    def test_oversized_http_body_is_rejected(self):
        body = b'{"goal":"design","candidates":[{"id":1}],"padding":"' + b"x" * MAX_REQUEST_BYTES + b'"}'
        response = self.client.post("/rank", content=body, headers={"content-type": "application/json"})

        self.assertEqual(413, response.status_code)
        self.assertEqual({"detail": "Request body too large"}, response.json())


if __name__ == "__main__":
    unittest.main()
