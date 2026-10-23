import unittest
from concurrent.futures import ThreadPoolExecutor
from threading import Event, Lock
from unittest.mock import patch

from fastapi.testclient import TestClient
from fastapi import HTTPException

from inbox_assistant import InboxAssistRequest, analyze, assist_inbox_message
from inbox_app import app


class InboxAssistantTests(unittest.TestCase):
    def test_optional_entrypoint_keeps_existing_health_and_ranking_routes(self):
        client = TestClient(app)
        self.assertEqual(200, client.get("/health").status_code)
        ranking = client.post("/rank", json={"goal": "career advice", "strategy": "tfidf",
            "candidates": [{"id": 1, "bio": "career guidance and interviewing"}]})
        self.assertEqual(200, ranking.status_code)
        self.assertEqual([1], ranking.json()["rankedIds"])

    def test_supported_categories_and_uncertain_other(self):
        cases = {
            "Could you give career advice about choosing a career path?": "CAREER_GUIDANCE",
            "Can you help me prepare for my technical interview?": "INTERVIEW_PREPARATION",
            "Could you refer me for this job application?": "REFERRAL_JOB_APPLICATION",
            "Please advise on debugging my software project code": "PROJECT_TECHNICAL_ADVICE",
            "Would you consider being my mentor?": "MENTORSHIP_REQUEST",
            "The weather is pleasant today.": "OTHER",
        }
        for content, expected in cases.items():
            with self.subTest(content=content):
                result = analyze(InboxAssistRequest(messageId=1, content=content))
                self.assertEqual(expected, result["category"])
        self.assertTrue(analyze(InboxAssistRequest(messageId=1, content="The weather is pleasant today."))["uncertain"])

    def test_explicit_deadline_is_distinguished_from_urgency_heuristic(self):
        explicit = analyze(InboxAssistRequest(messageId=1, content="Could you review my application by tomorrow?"))
        heuristic = analyze(InboxAssistRequest(messageId=2, content="This is urgent, can you advise me?"))
        ordinary = analyze(InboxAssistRequest(messageId=3, content="Can you share career advice?"))
        self.assertEqual("explicit_deadline", explicit["urgencyKind"])
        self.assertEqual("tomorrow", explicit["deadlineEvidence"].split()[-1])
        self.assertEqual("heuristic", heuristic["urgencyKind"])
        self.assertEqual("normal", ordinary["prioritySuggestion"])

    def test_summary_is_extractive_and_draft_is_safe_template(self):
        content = "I am applying for a role. I need help with the application."
        result = analyze(InboxAssistRequest(messageId=1, content=content))
        self.assertIn(result["summary"], content)
        self.assertEqual("deterministic_template", result["draftKind"])
        self.assertNotIn("referral", result["draft"].lower())
        self.assertFalse(result["modelMetadata"]["trainedForMentorax"])

    def test_request_validation_empty_oversized_and_extra_fields(self):
        with self.assertRaises(ValueError):
            InboxAssistRequest(messageId=1, content=" ")
        with self.assertRaises(ValueError):
            InboxAssistRequest(messageId=1, content="x" * 2001)
        with self.assertRaises(ValueError):
            InboxAssistRequest(messageId=0, content="hello")

    def test_endpoint_requires_internal_token_and_returns_safe_result(self):
        with patch.dict("os.environ", {"INBOX_ASSISTANT_API_TOKEN": "test-token"}):
            client = TestClient(app)
            body = {"messageId": 3, "content": "Can you help me prepare for an interview?"}
            self.assertEqual(503, client.post("/inbox/assist", json=body).status_code)
            response = client.post("/inbox/assist", json=body,
                                   headers={"Authorization": "Bearer test-token"})
            self.assertEqual(200, response.status_code)
            self.assertEqual("INTERVIEW_PREPARATION", response.json()["category"])
            invalid = client.post("/inbox/assist", json={**body, "extra": "x"},
                                  headers={"Authorization": "Bearer test-token"})
            self.assertEqual(422, invalid.status_code)
            self.assertEqual({"detail": "Invalid inbox assistant request"}, invalid.json())

    def test_busy_inference_is_bounded(self):
        with patch("inbox_assistant._slots.acquire", return_value=False), \
                patch.dict("os.environ", {"INBOX_ASSISTANT_API_TOKEN": "test-token"}):
            response = TestClient(app).post(
                "/inbox/assist", json={"messageId": 1, "content": "career advice"},
                headers={"Authorization": "Bearer test-token"})
        self.assertEqual(429, response.status_code)

    def test_simultaneous_inference_never_exceeds_configured_concurrency(self):
        entered = Event()
        release = Event()
        guard = Lock()
        active = 0
        peak = 0

        def controlled_analysis(_request):
            nonlocal active, peak
            with guard:
                active += 1
                peak = max(peak, active)
                if active == 2:
                    entered.set()
            release.wait(timeout=3)
            with guard:
                active -= 1
            return {"available": True}

        def call(index):
            return assist_inbox_message(
                InboxAssistRequest(messageId=index, content="career guidance"),
                authorization="Bearer test-token")

        with patch("inbox_assistant.analyze", side_effect=controlled_analysis), \
                patch.dict("os.environ", {"INBOX_ASSISTANT_API_TOKEN": "test-token"}), \
                ThreadPoolExecutor(max_workers=3) as executor:
            first = executor.submit(call, 1)
            second = executor.submit(call, 2)
            self.assertTrue(entered.wait(timeout=3), "two requests should enter inference concurrently")
            third = executor.submit(call, 3)
            with self.assertRaises(HTTPException) as error:
                third.result(timeout=3)
            self.assertEqual(429, error.exception.status_code)
            self.assertEqual(2, peak)
            release.set()
            self.assertTrue(first.result(timeout=3)["available"])
            self.assertTrue(second.result(timeout=3)["available"])


if __name__ == "__main__":
    unittest.main()
