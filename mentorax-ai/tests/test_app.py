import unittest
import asyncio
import json
import os
from threading import Event
from unittest.mock import patch

from fastapi import HTTPException
from fastapi.testclient import TestClient

from app import MAX_REQUEST_BYTES, app
from semantic_ranker import SemanticEmbeddingError, SentenceEmbeddingRanker


class MentorRankerApiTests(unittest.TestCase):
    def setUp(self):
        self.strategy_env = patch.dict(os.environ, {"MENTOR_RANKING_STRATEGY": "tfidf"})
        self.strategy_env.start()
        self.client = TestClient(app)
        self.client.__enter__()

    def tearDown(self):
        self.client.__exit__(None, None, None)
        self.strategy_env.stop()

    def test_health_endpoint(self):
        response = self.client.get("/health")

        self.assertEqual(200, response.status_code)
        self.assertEqual({"status": "ok"}, response.json())
        ready = self.client.get("/ready")
        self.assertEqual(200, ready.status_code)
        self.assertEqual({"status": "ready", "strategy": "tfidf",
                          "semanticModel": "not_required"}, ready.json())

    def test_background_warmup_readiness_and_ranking_during_load(self):
        load_entered = Event()
        release_load = Event()

        class DeterministicModel:
            def encode(self, texts, **_kwargs):
                import numpy as np
                return np.asarray([[1.0, 0.0] for _ in texts])

        def load_model(_name):
            load_entered.set()
            if not release_load.wait(timeout=5):
                raise TimeoutError("test did not release model initialization")
            return DeterministicModel()

        ranker = SentenceEmbeddingRanker(model_loader=load_model)
        with patch("app.semantic_ranker", ranker), TestClient(app) as client:
            warming = client.get("/ready?strategy=semantic")
            self.assertTrue(load_entered.wait(timeout=3), "semantic readiness starts model warmup")
            self.assertEqual(503, warming.status_code)
            self.assertEqual({"status": "not_ready", "strategy": "semantic",
                              "semanticModel": "warming"}, warming.json())
            self.assertEqual(200, client.get("/health").status_code,
                             "liveness must remain responsive during model loading")

            semantic = client.post("/rank", json={
                "goal": "software engineering", "strategy": "semantic",
                "candidates": [{"id": 1, "skills": ["software engineering"]}],
            })
            self.assertEqual(503, semantic.status_code)
            self.assertEqual({"detail": "Semantic ranking unavailable"}, semantic.json())
            batch = client.post("/rank/batch", json={"requests": [
                {"goal": "software engineering", "strategy": "semantic",
                 "candidates": [{"id": 2, "skills": ["software engineering"]}]},
                {"goal": "gardening", "strategy": "tfidf",
                 "candidates": [{"id": 3, "skills": ["gardening"]}]},
            ]})
            self.assertEqual({"available": False}, batch.json()["results"][0])
            self.assertEqual([3], batch.json()["results"][1]["result"]["rankedIds"])

            initialization = ranker.start_model_initialization()
            release_load.set()
            initialization.result(timeout=3)
            ready = client.get("/ready?strategy=semantic")
            self.assertEqual(200, ready.status_code)
            self.assertEqual("ready", ready.json()["semanticModel"])
            ranked = client.post("/rank", json={
                "goal": "software engineering", "strategy": "semantic",
                "candidates": [{"id": 1, "skills": ["software engineering"]}],
            })
            self.assertEqual(200, ranked.status_code)
            self.assertEqual([1], ranked.json()["rankedIds"])

    def test_readiness_reports_cached_model_failure_without_internal_details(self):
        load_calls = []

        def fail_to_load(_name):
            load_calls.append(True)
            raise OSError("secret local model path")

        ranker = SentenceEmbeddingRanker(model_loader=fail_to_load)
        with patch("app.semantic_ranker", ranker), TestClient(app) as client:
            initial = client.get("/ready?strategy=semantic")
            self.assertEqual(503, initial.status_code)
            ranker.start_model_initialization().result(timeout=3)
            failed = client.get("/ready?strategy=semantic")
            self.assertEqual(503, failed.status_code)
            self.assertEqual({"status": "not_ready", "strategy": "semantic",
                              "semanticModel": "unavailable"}, failed.json())
            self.assertNotIn("secret local model path", failed.text)
            self.assertEqual(200, client.get("/health").status_code)
            self.assertEqual(200, client.post("/rank", json={
                "goal": "gardening", "strategy": "tfidf",
                "candidates": [{"id": 8, "skills": ["gardening"]}],
            }).status_code)
            self.assertEqual(1, len(load_calls))

    def test_semantic_config_starts_warmup_in_background_during_application_startup(self):
        load_entered = Event()
        release_load = Event()

        class DeterministicModel:
            def encode(self, texts, **_kwargs):
                import numpy as np
                return np.asarray([[1.0, 0.0] for _ in texts])

        def load_model(_name):
            load_entered.set()
            if not release_load.wait(timeout=5):
                raise TimeoutError("test did not release model initialization")
            return DeterministicModel()

        ranker = SentenceEmbeddingRanker(model_loader=load_model)
        with patch.dict(os.environ, {"MENTOR_RANKING_STRATEGY": "semantic"}), \
                patch("app.semantic_ranker", ranker), TestClient(app) as client:
            self.assertTrue(load_entered.wait(timeout=3), "application startup should launch background warmup")
            self.assertEqual(200, client.get("/health").status_code)
            warming = client.get("/ready")
            self.assertEqual(503, warming.status_code)
            self.assertEqual("warming", warming.json()["semanticModel"])
            initialization = ranker.start_model_initialization()
            release_load.set()
            initialization.result(timeout=3)
            self.assertEqual(200, client.get("/ready").status_code)

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
        with patch("app.ensure_semantic_model_ready"), \
                patch("app.semantic_ranker.rank", return_value=[0.2, 0.9]) as ranker:
            response = self.client.post("/rank", json={
                "goal": "How can I prepare for software engineering interviews?",
                "strategy": "semantic",
                "candidates": [{"id": 5}, {"id": 2}],
            })

        self.assertEqual(200, response.status_code)
        self.assertEqual([2, 5], response.json()["rankedIds"])
        self.assertEqual("Sentence embedding cosine similarity", response.json()["rankingMethod"])
        self.assertEqual({"5": 0.2, "2": 0.9}, response.json()["semanticScores"])
        ranker.assert_called_once()

        with patch("app.ensure_semantic_model_ready"), \
                patch("app.semantic_ranker.rank", side_effect=SemanticEmbeddingError("model unavailable")):
            unavailable = self.client.post("/rank", json={
                "goal": "software engineering interviews", "strategy": "semantic",
                "candidates": [{"id": 1, "skills": ["software engineering"]}],
            })

        self.assertEqual(503, unavailable.status_code)
        self.assertEqual({"detail": "Semantic ranking unavailable"}, unavailable.json())

    def test_hybrid_strategy_combines_rank_percentiles_not_raw_scores(self):
        with patch("app.tfidf_scores", return_value=[0.9, 0.3]), \
                patch("app.ensure_semantic_model_ready"), \
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
        self.assertEqual({"8": 0.2, "3": 0.8}, response.json()["semanticScores"])

    def test_bounded_batch_ranks_independent_pages_and_isolates_page_failure(self):
        requests = [
            {"goal": "Python developer", "strategy": "tfidf", "candidates": [
                {"id": 1, "skills": ["Python"]}, {"id": 2, "skills": ["gardening"]},
            ]},
            {"goal": "gardening", "strategy": "tfidf", "candidates": [
                {"id": 3, "skills": ["Python"]}, {"id": 4, "skills": ["gardening"]},
            ]},
        ]
        response = self.client.post("/rank/batch", json={"requests": requests})

        self.assertEqual(200, response.status_code)
        results = response.json()["results"]
        self.assertEqual([1, 2], results[0]["result"]["rankedIds"])
        self.assertEqual([4, 3], results[1]["result"]["rankedIds"])
        self.assertNotIn("semanticScores", results[0]["result"])

        with patch("app.rank", side_effect=[{"rankedIds": [1]}, HTTPException(
                status_code=503, detail="safe failure")]):
            isolated = self.client.post("/rank/batch", json={"requests": requests})
        self.assertEqual({"result": {"rankedIds": [1]}}, isolated.json()["results"][0])
        self.assertEqual({"available": False}, isolated.json()["results"][1])

        with patch("app.rank", side_effect=[{"rankedIds": [1]}, RuntimeError("internal detail")]):
            isolated = self.client.post("/rank/batch", json={"requests": requests})
        self.assertEqual({"result": {"rankedIds": [1]}}, isolated.json()["results"][0])
        self.assertEqual({"available": False}, isolated.json()["results"][1])
        self.assertNotIn("internal detail", isolated.text)

    def test_batch_rejects_empty_oversized_count_and_malformed_requests(self):
        one_request = {"goal": "design", "candidates": [{"id": 1}]}
        empty = self.client.post("/rank/batch", json={"requests": []})
        too_many = self.client.post("/rank/batch", json={"requests": [one_request] * 6})
        malformed = self.client.post("/rank/batch", content="{",
                                     headers={"content-type": "application/json"})
        oversized_body = b'{"requests":[]}' + b" " * MAX_REQUEST_BYTES
        oversized = self.client.post("/rank/batch", content=oversized_body,
                                     headers={"content-type": "application/json"})

        for response in (empty, too_many, malformed):
            self.assertEqual(422, response.status_code)
            self.assertEqual({"detail": "Invalid ranking request"}, response.json())
        self.assertEqual(413, oversized.status_code)
        self.assertEqual({"detail": "Request body too large"}, oversized.json())

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

    def test_oversized_streamed_body_without_content_length_is_rejected(self):
        body = json.dumps({
            "goal": "design", "candidates": [{"id": 1}],
            "padding": "x" * MAX_REQUEST_BYTES,
        }).encode()
        messages = [{"type": "http.request", "body": body, "more_body": False}]
        sent = []

        async def receive():
            return messages.pop(0) if messages else {
                "type": "http.request", "body": b"", "more_body": False,
            }

        async def send(message):
            sent.append(message)

        scope = {
            "type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1",
            "method": "POST", "scheme": "http", "path": "/rank", "raw_path": b"/rank",
            "query_string": b"", "root_path": "", "headers": [(b"content-type", b"application/json")],
            "client": ("127.0.0.1", 1234), "server": ("127.0.0.1", 80),
        }
        asyncio.run(self.client.app(scope, receive, send))

        response_start = next(message for message in sent
                              if message["type"] == "http.response.start")
        response_body = next(message["body"] for message in sent
                             if message["type"] == "http.response.body")
        self.assertEqual(413, response_start["status"])
        self.assertEqual({"detail": "Request body too large"}, json.loads(response_body))


if __name__ == "__main__":
    unittest.main()
