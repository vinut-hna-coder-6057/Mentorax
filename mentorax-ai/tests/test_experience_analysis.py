import os
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from pypdf import PdfWriter
from io import BytesIO

from app import app
from experience_analysis import ClaimFields, analyze_document


def blank_pdf() -> bytes:
    output = BytesIO()
    writer = PdfWriter()
    writer.add_blank_page(width=200, height=200)
    writer.write(output)
    return output.getvalue()


class ExperienceAnalysisTests(unittest.TestCase):
    def setUp(self):
        self.token = patch.dict(os.environ, {"EXPERIENCE_ANALYSIS_API_TOKEN": "test-service-token"})
        self.token.start()
        self.client = TestClient(app)

    def tearDown(self):
        self.token.stop()

    def _upload(self, content: bytes, *, mime="application/pdf", authorization="Bearer test-service-token",
                claim='{"company":"Northstar","jobTitle":"Engineer","summary":"Built systems"}'):
        return self.client.post(
            "/experience/analyze",
            headers={"Authorization": authorization} if authorization else {},
            data={"claim": claim},
            files={"file": ("evidence.pdf", content, mime)},
        )

    def test_text_evidence_returns_structured_comparison_without_verifying(self):
        fields = ClaimFields(company="Northstar", jobTitle="Engineer", startMonth=None,
                             endMonth=None, summary="Built systems")
        with patch("experience_analysis._extract_pdf", return_value="Northstar hired an Engineer"):
            result = analyze_document(fields, b"%PDF-test")
        self.assertEqual("ANALYZED", result["analysisStatus"])
        self.assertEqual("NONE", result["verificationDecision"])
        self.assertFalse(result["trustedIssuerValidation"])
        self.assertEqual(["MATCH", "MATCH", "INCONCLUSIVE", "INCONCLUSIVE", "INCONCLUSIVE"],
                         [item["outcome"] for item in result["findings"]])

    def test_blank_pdf_is_inconclusive_when_ocr_is_unavailable(self):
        response = self._upload(blank_pdf())
        self.assertEqual(200, response.status_code)
        result = response.json()
        self.assertEqual("INCONCLUSIVE", result["analysisStatus"])
        self.assertEqual("NONE", result["verificationDecision"])
        self.assertFalse(result["trustedIssuerValidation"])

    def test_rejects_invalid_pdf_and_incorrect_mime(self):
        self.assertEqual(415, self._upload(b"not a pdf").status_code)
        self.assertEqual(415, self._upload(blank_pdf(), mime="application/octet-stream").status_code)

    def test_requires_internal_service_token(self):
        self.assertEqual(401, self._upload(blank_pdf(), authorization="Bearer wrong").status_code)

    def test_rejects_malformed_claim_without_echoing_document(self):
        response = self._upload(blank_pdf(), claim='{"company":""}')
        self.assertEqual(422, response.status_code)
        self.assertEqual({"detail": "Invalid experience claim"}, response.json())

    def test_rejects_oversized_document(self):
        response = self._upload(b"%PDF-" + b"x" * (5 * 1024 * 1024))
        self.assertEqual(413, response.status_code)


if __name__ == "__main__":
    unittest.main()
