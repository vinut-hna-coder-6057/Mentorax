import os
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from pypdf import PdfWriter
from io import BytesIO
import fitz
from PIL import Image, ImageDraw, ImageFont
from fastapi import HTTPException

from app import app
from experience_analysis import ClaimFields, analyze_document, _validate_llm_response


def blank_pdf() -> bytes:
    output = BytesIO()
    writer = PdfWriter()
    writer.add_blank_page(width=200, height=200)
    writer.write(output)
    return output.getvalue()


def employment_image() -> bytes:
    image = Image.new("RGB", (1400, 500), "white")
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype("C:\\Windows\\Fonts\\arial.ttf", 48)
    draw.text((60, 35), "EXPERIENCE CERTIFICATE", fill="black", font=font)
    draw.text((60, 145), "Employee Name: Example User", fill="black", font=font)
    draw.text((60, 245), "Employer: Northstar", fill="black", font=font)
    draw.text((60, 345), "Position: Engineer", fill="black", font=font)
    output = BytesIO()
    image.save(output, format="PNG")
    return output.getvalue()


def scanned_pdf() -> bytes:
    output = BytesIO()
    document = fitz.open()
    page = document.new_page(width=700, height=250)
    page.insert_image(page.rect, stream=employment_image())
    document.save(output)
    document.close()
    return output.getvalue()


def multipage_scanned_pdf() -> bytes:
    output = BytesIO()
    document = fitz.open()
    first_page = document.new_page(width=700, height=250)
    first_page.insert_text(
        (40, 50),
        "Supporting employment documents are reviewed for consistency across all pages.",
    )
    second_page = document.new_page(width=700, height=250)
    second_page.insert_image(second_page.rect, stream=employment_image())
    document.save(output)
    document.close()
    return output.getvalue()


def text_pdf() -> bytes:
    output = BytesIO()
    document = fitz.open()
    page = document.new_page()
    page.insert_text((60, 60), "EXPERIENCE CERTIFICATE", fontsize=18)
    page.insert_text((60, 100), "Employee Name: Example User", fontsize=14)
    page.insert_text((60, 130), "Employer: Northstar", fontsize=14)
    page.insert_text((60, 160), "Position: Engineer", fontsize=14)
    document.save(output)
    document.close()
    return output.getvalue()


def valid_llm_payload() -> dict:
    return {
        "analysisVersion": "2.0",
        "documentClassification": "EXPERIENCE_CERTIFICATE",
        "extractedDetails": {
            "employeeName": "Example User", "companyName": "Northstar",
            "jobTitle": "Engineer", "employmentType": None, "startDate": None,
            "endDate": None, "issueDate": None, "issuer": "Northstar HR",
            "verificationIdentifiers": None, "employmentStatements": None,
        },
        "fieldAssessments": [{
            "field": "company", "outcome": "MATCH", "explanation": "Employer is stated.",
            "evidenceReference": "document-1-page-1", "pageNumber": 1,
            "textExcerpt": "Employer: Northstar",
        }],
        "crossDocumentFindings": [],
        "missingInformation": [],
        "riskIndicators": [],
        "evidenceQuality": "HIGH",
        "recommendedAction": "REQUEST_INDEPENDENT_CORROBORATION",
        "explanation": "The text supports the claim; issuer identity is not verified.",
    }


class ExperienceAnalysisTests(unittest.TestCase):
    def setUp(self):
        self.token = patch.dict(os.environ, {"EXPERIENCE_ANALYSIS_API_TOKEN": "test-service-token"})
        self.token.start()
        self.client = TestClient(app)

    def tearDown(self):
        self.token.stop()

    def _upload(self, content: bytes, *, mime="application/pdf", authorization="Bearer test-service-token",
                claim='{"company":"Northstar","jobTitle":"Engineer","summary":"Built systems"}',
                filename="evidence.pdf"):
        return self.client.post(
            "/experience/analyze",
            headers={"Authorization": authorization} if authorization else {},
            data={"claim": claim},
            files={"file": (filename, content, mime)},
        )

    def test_text_evidence_returns_structured_comparison_without_verifying(self):
        fields = ClaimFields(company="Northstar", jobTitle="Engineer", startMonth=None,
                             endMonth=None, summary="Built systems", employeeName="Example User")
        with patch("experience_analysis._extract_document",
                   return_value=("Northstar hired an Engineer", [], [{"page": 1, "text": "Northstar hired an Engineer"}], "NOT_NEEDED")):
            result = analyze_document(fields, b"%PDF-test")
        self.assertEqual("ANALYZED", result["analysisStatus"])
        self.assertEqual("NONE", result["verificationDecision"])
        self.assertFalse(result["trustedIssuerValidation"])
        self.assertEqual("UNKNOWN", result["documentType"])
        self.assertEqual(["MATCH", "MATCH", "MISSING", "INCONCLUSIVE", "INCONCLUSIVE", "INCONCLUSIVE"],
                         [item["outcome"] for item in result["findings"]])

    def test_blank_pdf_is_inconclusive_when_ocr_is_unavailable(self):
        response = self._upload(blank_pdf())
        self.assertEqual(200, response.status_code)
        result = response.json()
        self.assertEqual("INCONCLUSIVE", result["analysisStatus"])
        self.assertEqual("NONE", result["verificationDecision"])
        self.assertFalse(result["trustedIssuerValidation"])
        self.assertEqual([], result["suspiciousFeatures"])

    def test_extracts_labeled_employee_and_flags_mismatched_claim_values(self):
        fields = ClaimFields(company="Northstar", jobTitle="Engineer", startMonth="2025-01",
                             endMonth=None, summary="Built systems", employeeName="Example User")
        text = (
            "Employment Certificate\n"
            "Employee Name: Different Person\n"
            "Employer: Other Company\n"
            "Position: Analyst\n"
            "2024-02 to 2025-03"
        )
        with patch("experience_analysis._extract_document",
                   return_value=(text, [], [{"page": 1, "text": text}], "NOT_NEEDED")):
            result = analyze_document(fields, b"%PDF-test")
        outcomes = {finding["field"]: finding["outcome"] for finding in result["findings"]}
        self.assertEqual("EMPLOYMENT_CERTIFICATE", result["documentType"])
        self.assertEqual("MISMATCH", outcomes["employeeName"])
        self.assertEqual("MISMATCH", outcomes["company"])
        self.assertEqual("MISMATCH", outcomes["jobTitle"])
        self.assertEqual("MISMATCH", outcomes["startMonth"])

    def test_extracts_employment_type_issue_date_and_supporting_statement(self):
        text = (
            "Employment Certificate\n"
            "Employee Name: Example User\n"
            "Employer: Northstar\n"
            "Position: Engineer\n"
            "Employment Type: Full-time\n"
            "Issue Date: 2025-02-01\n"
            "This is to certify that Example User worked with Northstar."
        )
        fields = ClaimFields(
            company="Northstar",
            jobTitle="Engineer",
            summary="Built systems",
            employeeName="Example User",
        )
        with patch("experience_analysis._extract_document",
                   return_value=(text, [], [{"page": 1, "text": text}], "NOT_NEEDED")):
            result = analyze_document(fields, b"%PDF-test")

        by_field = {finding["field"]: finding for finding in result["findings"]}
        self.assertEqual("Full-time", by_field["employmentType"]["extractedValue"])
        self.assertEqual("2025-02-01", by_field["issueDate"]["extractedValue"])
        self.assertIn("worked with Northstar", by_field["employmentStatements"]["extractedValue"])
        self.assertEqual(1, by_field["issueDate"]["pageNumber"])

    def test_malformed_pdf_is_rejected(self):
        with self.assertRaises(Exception):
            analyze_document(
                ClaimFields(company="Northstar", jobTitle="Engineer", summary="Built systems"),
                b"%PDF-not-valid",
            )

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

    def test_text_pdf_extracts_without_ocr(self):
        response = self._upload(text_pdf())
        self.assertEqual(200, response.status_code)
        body = response.json()
        self.assertEqual("NOT_NEEDED", body["ocrStatus"])
        self.assertEqual("HEURISTIC_FALLBACK", body["analysisMethod"])
        self.assertEqual("EMPLOYMENT_CERTIFICATE", body["documentClassification"])
        self.assertTrue(body["findings"][0]["evidenceReference"].startswith("document-1-page-"))

    def test_normalized_extracted_text_fingerprint_identifies_duplicate_content(self):
        response_one = self._upload(text_pdf())
        response_two = self._upload(text_pdf())

        self.assertEqual(200, response_one.status_code)
        self.assertEqual(200, response_two.status_code)
        fingerprint_one = response_one.json()["extractedTextFingerprint"]
        fingerprint_two = response_two.json()["extractedTextFingerprint"]
        self.assertRegex(fingerprint_one, r"^[a-f0-9]{64}$")
        self.assertEqual(fingerprint_one, fingerprint_two)

    def test_scanned_pdf_executes_ocr_and_preserves_page_reference(self):
        response = self._upload(scanned_pdf())
        self.assertEqual(200, response.status_code)
        body = response.json()
        self.assertEqual("SUCCESS", body["ocrStatus"])
        self.assertEqual("OCR_PLUS_HEURISTIC", body["analysisMethod"])
        self.assertIn("MATCH", {finding["outcome"] for finding in body["findings"]})
        self.assertTrue(any(finding.get("pageNumber") == 1 for finding in body["findings"]))

    def test_png_image_evidence_executes_ocr(self):
        response = self._upload(
            employment_image(),
            mime="image/png",
            filename="evidence.png",
            claim='{"company":"Northstar","jobTitle":"Engineer","summary":"Built systems","employeeName":"Example User"}',
        )
        self.assertEqual(200, response.status_code)
        body = response.json()
        self.assertEqual("SUCCESS", body["ocrStatus"])
        self.assertEqual("OCR_PLUS_HEURISTIC", body["analysisMethod"])
        self.assertEqual("MATCH", next(item["outcome"] for item in body["findings"]
                                      if item["field"] == "employeeName"))

    def test_rotated_jpeg_executes_ocr_after_orientation_recovery(self):
        image = Image.open(BytesIO(employment_image())).rotate(90, expand=True)
        output = BytesIO()
        image.save(output, format="JPEG", quality=95)
        response = self._upload(
            output.getvalue(),
            mime="image/jpeg",
            filename="evidence.jpg",
            claim='{"company":"Northstar","jobTitle":"Engineer","summary":"Built systems","employeeName":"Example User"}',
        )

        self.assertEqual(200, response.status_code)
        body = response.json()
        self.assertEqual("SUCCESS", body["ocrStatus"])
        self.assertTrue(any(finding.get("pageNumber") == 1 for finding in body["findings"]))
        self.assertEqual("MATCH", next(item["outcome"] for item in body["findings"]
                                      if item["field"] == "employeeName"))

    def test_multipage_scanned_pdf_preserves_the_ocr_page_reference(self):
        response = self._upload(
            multipage_scanned_pdf(),
            claim='{"company":"Northstar","jobTitle":"Engineer","summary":"Built systems","employeeName":"Example User"}',
        )

        self.assertEqual(200, response.status_code)
        body = response.json()
        self.assertEqual("SUCCESS", body["ocrStatus"])
        self.assertTrue(any(finding.get("pageNumber") == 2 for finding in body["findings"]))

    def test_invalid_llm_response_is_rejected(self):
        self.assertIsNone(_validate_llm_response({"analysisVersion": "2.0"}))
        invalid = valid_llm_payload()
        invalid["fieldAssessments"][0]["outcome"] = "VERIFIED"
        self.assertIsNone(_validate_llm_response(invalid))

    def test_provider_request_uses_configured_model_and_schema(self):
        from experience_analysis import _llm_analyze_document
        response_body = {"choices": [{"message": {"content": __import__("json").dumps(valid_llm_payload())}}]}
        class FakeResponse:
            def __enter__(self):
                return self
            def __exit__(self, *_args):
                return False
            def read(self, _limit):
                return __import__("json").dumps(response_body).encode()

        with patch.dict(os.environ, {
            "EXPERIENCE_ANALYSIS_LLM_PROVIDER": "openai-compatible",
            "EXPERIENCE_ANALYSIS_LLM_MODEL": "test-model",
            "EXPERIENCE_ANALYSIS_LLM_BASE_URL": "https://provider.invalid/v1",
            "EXPERIENCE_ANALYSIS_LLM_API_KEY": "test-key",
        }), patch("experience_analysis.request.urlopen", return_value=FakeResponse()) as urlopen:
            result = _llm_analyze_document(
                ClaimFields(company="Northstar", jobTitle="Engineer", summary="Built systems"),
                "Employer: Northstar", [], "NOT_NEEDED")
        self.assertEqual("LLM_ANALYSIS", result["analysisMethod"])
        sent = __import__("json").loads(urlopen.call_args.args[0].data)
        self.assertEqual("test-model", sent["model"])
        self.assertEqual("json_schema", sent["response_format"]["type"])

    def test_openrouter_provider_uses_its_default_endpoint(self):
        from experience_analysis import _llm_analyze_document
        with patch.dict(os.environ, {
            "EXPERIENCE_ANALYSIS_LLM_PROVIDER": "openrouter",
            "EXPERIENCE_ANALYSIS_LLM_API_KEY": "test-key",
        }, clear=False), patch("experience_analysis.request.urlopen", side_effect=TimeoutError("timeout")) as urlopen:
            with self.assertRaises(HTTPException):
                _llm_analyze_document(
                    ClaimFields(company="Northstar", jobTitle="Engineer", summary="Built systems"),
                    "Employer: Northstar", [], "NOT_NEEDED")
        self.assertIn(
            "openrouter.ai/api/v1/chat/completions",
            urlopen.call_args.args[0].full_url,
        )

    def test_provider_timeout_retries_and_fails_closed(self):
        from experience_analysis import _llm_analyze_document
        with patch.dict(os.environ, {
            "EXPERIENCE_ANALYSIS_LLM_PROVIDER": "openai-compatible",
            "EXPERIENCE_ANALYSIS_LLM_BASE_URL": "https://provider.invalid/v1",
            "EXPERIENCE_ANALYSIS_LLM_API_KEY": "test-key",
        }), patch("experience_analysis.request.urlopen", side_effect=TimeoutError("timeout")) as urlopen:
            with self.assertRaises(HTTPException) as raised:
                _llm_analyze_document(
                    ClaimFields(company="Northstar", jobTitle="Engineer", summary="Built systems"),
                    "Employer: Northstar", [], "NOT_NEEDED")
        self.assertEqual(503, raised.exception.status_code)
        self.assertEqual(2, urlopen.call_count)

    def test_configured_provider_failure_does_not_fall_back_to_heuristic(self):
        with patch.dict(os.environ, {
            "EXPERIENCE_ANALYSIS_LLM_PROVIDER": "unrecognized-provider",
        }):
            response = self._upload(text_pdf())
        self.assertEqual(503, response.status_code)

    def test_image_mime_must_match_image_signature(self):
        response = self._upload(blank_pdf(), mime="image/png", filename="evidence.png")
        self.assertEqual(415, response.status_code)


if __name__ == "__main__":
    unittest.main()
