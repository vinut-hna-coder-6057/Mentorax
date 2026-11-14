import hashlib
import json
import os
import unittest
from copy import deepcopy
from unittest.mock import patch

from fastapi.testclient import TestClient
from pypdf import PdfWriter
from io import BytesIO
import fitz
from PIL import Image, ImageDraw, ImageFont
from fastapi import HTTPException

from app import app
from document_understanding import (
    ExtractionRequest,
    ExtractionResponse,
    FieldStatus,
    analyze_document as strict_analyze_document,
    sanitize_sensitive_content,
)
from experience_analysis import ClaimFields, _legacy_to_strict_document_response, analyze_document, _validate_llm_response


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


def credential_pdf() -> bytes:
    output = BytesIO()
    document = fitz.open()
    page = document.new_page()
    lines = [
        "EXPERIENCE CERTIFICATE",
        "Employee Name: Synthetic Student",
        "Employer: Northstar Labs",
        "Position: Research Intern",
        "Start Date: 2024-06-01",
        "This certifies that Synthetic Student completed the internship.",
        "OTP: 735904",
        "Password: fake-passphrase-42",
        "PIN: 4812",
        "Authorization: Bearer fake-bearer-token-abc123",
        "API key: sk-test_fake_api_key_123456",
        "Bank Account Number: 123456789012",
        "Bank Account: 001234567890",
        "Card Number: 4111 1111 1111 1111",
    ]
    for line_number, line in enumerate(lines):
        page.insert_text((50, 50 + line_number * 28), line, fontsize=11)
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
                claim='{"evidenceId":42,"evidenceVersion":7,"company":"Northstar","jobTitle":"Engineer","summary":"Built systems"}',
                filename="evidence.pdf"):
        return self.client.post(
            "/experience/analyze",
            headers={"Authorization": authorization} if authorization else {},
            data={"claim": claim},
            files={"file": (filename, content, mime)},
        )

    def test_text_evidence_returns_structured_comparison_without_verifying(self):
        fields = ClaimFields(evidenceId=42, evidenceVersion=7, company="Northstar", jobTitle="Engineer",
                             startMonth=None, endMonth=None, summary="Built systems", employeeName="Example User")
        with patch("experience_analysis._extract_document",
                   return_value=("Northstar hired an Engineer", [], [{"page": 1, "text": "Northstar hired an Engineer"}], "NOT_NEEDED")):
            result = analyze_document(fields, b"%PDF-test")
        self.assertEqual("COMPLETE", result["analysisStatus"])
        self.assertEqual("NONE", result["verificationDecision"])
        self.assertFalse(result["trustedIssuerValidation"])
        self.assertEqual("UNKNOWN", result["documentType"])
        self.assertEqual(["MATCH", "MATCH", "MISSING", "INCONCLUSIVE", "INCONCLUSIVE", "INCONCLUSIVE"],
                         [item["outcome"] for item in result["findings"]])

    def test_real_evidence_identity_is_preserved_in_legacy_and_strict_conversion(self):
        fields = ClaimFields(
            evidenceId=99,
            evidenceVersion=3,
            company="Northstar",
            jobTitle="Engineer",
            summary="Built systems",
            employeeName="Example User",
        )
        text = (
            "Employment Certificate\n"
            "Employee Name: Different Person\n"
            "Employer: Northstar\n"
            "Position: Engineer\n"
        )
        with patch("experience_analysis._extract_document",
                   return_value=(text, [], [{"page": 1, "text": text, "method": "OCR", "ocrFailed": False}], "SUCCESS")):
            result = analyze_document(fields, b"%PDF-test")
        strict = _legacy_to_strict_document_response(result)
        self.assertEqual(99, strict["evidenceId"])
        self.assertEqual(3, strict["evidenceVersion"])
        name_field = next(field for field in strict["fields"] if field["name"] == "personName")
        self.assertEqual("FOUND", name_field["status"])
        self.assertEqual(99, name_field["provenance"][0]["sourceEvidenceId"])
        self.assertEqual(3, name_field["provenance"][0]["evidenceVersion"])
        outcomes = {item["field"]: item["outcome"] for item in result["findings"]}
        self.assertEqual("MISMATCH", outcomes["employeeName"])

    def test_supported_claim_types_are_accepted_and_invalid_types_are_rejected(self):
        for claim_type in ("INTERNSHIP", "MENTORSHIP", "UNSPECIFIED"):
            fields = ClaimFields(
                evidenceId=42,
                evidenceVersion=7,
                claimType=claim_type,
                company="Northstar",
                jobTitle="Engineer",
                summary="Built systems",
            )
            self.assertEqual(claim_type, fields.claimType.value)

        with self.assertRaises(Exception):
            ClaimFields(
                evidenceId=42,
                evidenceVersion=7,
                claimType="APPRENTICESHIP",
                company="Northstar",
                jobTitle="Engineer",
                summary="Built systems",
            )

    def test_unspecified_claim_type_does_not_invent_a_document_classification(self):
        fields = ClaimFields(
            evidenceId=42,
            evidenceVersion=7,
            claimType="UNSPECIFIED",
            company="Northstar",
            jobTitle="Engineer",
            summary="Built systems",
        )
        text = "Participation Record\nCompleted assigned activities."
        with patch("experience_analysis._extract_document",
                   return_value=(text, [], [{"page": 1, "text": text, "method": "PDF_TEXT"}], "NOT_NEEDED")):
            result = analyze_document(fields, b"%PDF-test")

        self.assertEqual("UNKNOWN", result["documentClassification"])
        self.assertEqual("UNSPECIFIED", fields.claimType.value)

    def test_statement_extraction_collects_completion_employment_and_mentorship_across_pages(self):
        request = ExtractionRequest(
            schemaVersion="mentorax.document-understanding.v1",
            evidenceId=77,
            evidenceVersion=11,
            sha256=hashlib.sha256(b"%PDF-test").hexdigest(),
            claimType="INTERNSHIP",
            company="Northstar",
            jobTitle="Engineer",
            summary="Built systems",
            personName="Example User",
        )
        pages = [
            {"page": 1, "text": "Completion certificate completed the internship successfully.", "method": "PDF_TEXT"},
            {"page": 2, "text": "The student was employed as an engineer by Northstar.", "method": "PDF_TEXT"},
            {"page": 3, "text": "Mentor and supervisor confirmed mentorship support.", "method": "OCR"},
        ]

        response = strict_analyze_document(
            request,
            b"%PDF-test",
            "application/pdf",
            lambda data, content_type: ("\n".join(page["text"] for page in pages), [], pages, "NOT_NEEDED"),
        )

        kinds = {statement.kind: statement for statement in response.extractedStatements}
        self.assertEqual(3, len(response.extractedStatements))
        self.assertEqual(1, kinds["COMPLETION"].provenance[0].pageNumber)
        self.assertEqual(2, kinds["EMPLOYMENT"].provenance[0].pageNumber)
        self.assertEqual(3, kinds["MENTORSHIP"].provenance[0].pageNumber)

    def test_blank_pdf_is_unreadable_when_ocr_is_unavailable(self):
        response = self._upload(blank_pdf())
        self.assertEqual(200, response.status_code)
        result = response.json()
        self.assertEqual("UNREADABLE", result["analysisStatus"])
        self.assertEqual("NONE", result["verificationDecision"])
        self.assertFalse(result["trustedIssuerValidation"])
        self.assertEqual([], result["suspiciousFeatures"])

    def test_extracts_labeled_employee_and_flags_mismatched_claim_values(self):
        fields = ClaimFields(evidenceId=42, evidenceVersion=7, company="Northstar", jobTitle="Engineer",
                             startMonth="2025-01", endMonth=None, summary="Built systems",
                             employeeName="Example User")
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

    def test_contradictory_labeled_values_are_not_replaced_by_claim_values(self):
        fields = ClaimFields(evidenceId=42, evidenceVersion=7, company="Northstar", jobTitle="Engineer",
                             summary="Built systems")
        text = (
            "Employment Certificate\n"
            "Employer: Other Company\n"
            "Position: Analyst\n"
            "This document mentions Northstar and Engineer as a former employer and role."
        )
        with patch("experience_analysis._extract_document",
                   return_value=(text, [], [{"page": 1, "text": text}], "NOT_NEEDED")):
            result = analyze_document(fields, b"%PDF-test")
        extracted = {finding["field"]: finding["extractedValue"] for finding in result["findings"]}
        self.assertEqual("Other Company", extracted["company"])
        self.assertEqual("Analyst", extracted["jobTitle"])
        self.assertEqual("Other Company", result["extractedDetails"]["companyName"])
        self.assertEqual("Analyst", result["extractedDetails"]["jobTitle"])

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
            evidenceId=42,
            evidenceVersion=7,
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
                ClaimFields(evidenceId=42, evidenceVersion=7, company="Northstar", jobTitle="Engineer",
                            summary="Built systems"),
                b"%PDF-not-valid",
            )

    def test_rejects_invalid_pdf_and_incorrect_mime(self):
        self.assertEqual(415, self._upload(b"not a pdf").status_code)
        self.assertEqual(415, self._upload(blank_pdf(), mime="application/octet-stream").status_code)

    def test_requires_internal_service_token(self):
        self.assertEqual(401, self._upload(blank_pdf(), authorization="Bearer wrong").status_code)

    def test_rejects_malformed_claim_without_echoing_document(self):
        response = self._upload(blank_pdf(), claim='{"evidenceId":42,"evidenceVersion":7,"company":""}')
        self.assertEqual(422, response.status_code)
        self.assertEqual({"detail": "Invalid experience claim"}, response.json())

    def test_rejects_oversized_document(self):
        response = self._upload(b"%PDF-" + b"x" * (5 * 1024 * 1024))
        self.assertEqual(413, response.status_code)

    def test_text_pdf_extracts_without_ocr(self):
        response = self._upload(text_pdf())
        self.assertEqual(200, response.status_code)
        body = response.json()
        self.assertEqual("COMPLETE", body["analysisStatus"])
        self.assertEqual("NOT_NEEDED", body["ocrStatus"])
        self.assertEqual("HEURISTIC_FALLBACK", body["analysisMethod"])
        self.assertEqual("EMPLOYMENT_CERTIFICATE", body["documentClassification"])
        self.assertTrue(body["findings"][0]["evidenceReference"].startswith("evidence-42-v7-page-"))

    def test_authenticated_route_returns_spring_contract_and_redacts_credentials(self):
        response = self._upload(credential_pdf())

        self.assertEqual(200, response.status_code)
        body = response.json()
        self.assertEqual("mentorax.document-understanding.v1", body["schemaVersion"])
        self.assertEqual("COMPLETE", body["analysisStatus"])
        self.assertEqual(42, body["evidenceId"])
        self.assertEqual(7, body["evidenceVersion"])
        self.assertEqual("EMPLOYMENT_CERTIFICATE", body["documentClassification"])
        self.assertTrue(all(
            item["status"] in {"FOUND", "NOT_PRESENT", "UNREADABLE"}
            for item in body["fields"]
        ))

        serialized = response.text
        fake_secrets = (
            "735904",
            "fake-passphrase-42",
            "4812",
            "fake-bearer-token-abc123",
            "sk-test_fake_api_key_123456",
            "123456789012",
            "001234567890",
            "4111 1111 1111 1111",
        )
        self.assertFalse(any(secret in serialized for secret in fake_secrets),
                         "The route returned synthetic sensitive values")
        for ordinary_value in (
                "Synthetic Student", "Northstar Labs", "Research Intern", "2024-06-01"):
            self.assertIn(ordinary_value, serialized)
        self.assertIn("[REDACTED]", serialized)

    def test_route_maps_complete_partial_and_unreadable_extraction_statuses(self):
        readable_text = (
            "Experience Certificate\nEmployee Name: Synthetic Student\n"
            "Employer: Northstar Labs\nPosition: Research Intern"
        )
        cases = (
            (readable_text, "NOT_NEEDED", "COMPLETE"),
            (readable_text, "FAILED", "PARTIAL"),
            ("", "FAILED", "UNREADABLE"),
        )
        for text, ocr_status, expected_status in cases:
            page = {"page": 1, "text": text, "method": "PDF_TEXT", "ocrFailed": ocr_status == "FAILED"}
            with self.subTest(expected_status=expected_status), patch(
                    "experience_analysis._extract_document",
                    return_value=(text, [], [page], ocr_status)):
                response = self._upload(b"%PDF-synthetic")

            self.assertEqual(200, response.status_code)
            self.assertEqual(expected_status, response.json()["analysisStatus"])

    def test_sanitization_covers_nested_output_locations_without_mutating_sources(self):
        payload = {
            "fields": [{
                "value": "OTP: 735904",
                "candidateValues": ["Password: fake-passphrase-42"],
                "provenance": [{"textExcerpt": "Employer: Northstar Labs; Bearer fake-bearer-token-abc123"}],
            }],
            "extractedStatements": [{
                "text": "Worked at Northstar Labs. PIN: 4812",
                "provenance": [{"textExcerpt": "API key: sk-test_fake_api_key_123456"}],
            }],
            "warnings": ["Diagnostic: Bank Account Number: 123456789012"],
            "diagnostic": "Card Number: 4111 1111 1111 1111",
        }
        original = deepcopy(payload)

        sanitized = sanitize_sensitive_content(payload)
        serialized = json.dumps(sanitized)

        self.assertFalse(any(secret in serialized for secret in (
            "735904", "fake-passphrase-42", "4812", "fake-bearer-token-abc123",
            "sk-test_fake_api_key_123456", "123456789012", "4111 1111 1111 1111",
        )), "Nested response data retained synthetic sensitive values")
        self.assertIn("Northstar Labs", serialized)
        self.assertEqual(original, payload)

    def test_strict_extraction_sanitizes_source_text_without_mutating_extractor_pages(self):
        data = b"synthetic extraction input"
        text = (
            "Internship Certificate\nStudent: Synthetic Student\n"
            "Organization: Northstar Labs\nOTP: 735904"
        )
        pages = [{"page": 1, "text": text, "method": "PDF_TEXT"}]
        original_pages = deepcopy(pages)
        request = ExtractionRequest(
            schemaVersion="mentorax.document-understanding.v1",
            evidenceId=77,
            evidenceVersion=11,
            sha256=hashlib.sha256(data).hexdigest(),
            claimType="INTERNSHIP",
            company="Northstar Labs",
            jobTitle="Research Intern",
            summary="Synthetic test",
            personName="Synthetic Student",
        )

        response = strict_analyze_document(
            request,
            data,
            "application/pdf",
            lambda _data, _content_type: (text, [], pages, "NOT_NEEDED"),
        )

        serialized = response.model_dump_json()
        self.assertNotIn("735904", serialized)
        self.assertIn("Northstar Labs", serialized)
        self.assertEqual(original_pages, pages)

    def test_strict_status_is_unreadable_when_extraction_only_returns_page_markers(self):
        data = b"synthetic unreadable extraction input"
        request = ExtractionRequest(
            schemaVersion="mentorax.document-understanding.v1",
            evidenceId=78,
            evidenceVersion=12,
            sha256=hashlib.sha256(data).hexdigest(),
            claimType="UNSPECIFIED",
        )

        response = strict_analyze_document(
            request,
            data,
            "application/pdf",
            lambda _data, _content_type: ("[page 1]\n", [], [
                {"page": 1, "text": "", "method": "OCR", "ocrFailed": True},
            ], "FAILED"),
        )

        self.assertEqual("UNREADABLE", response.analysisStatus)
        self.assertTrue(all(field.status == FieldStatus.UNREADABLE for field in response.fields))
        self.assertIn("DOCUMENT_UNREADABLE", response.warnings)

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
            claim='{"evidenceId":42,"evidenceVersion":7,"company":"Northstar","jobTitle":"Engineer","summary":"Built systems","employeeName":"Example User"}',
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
            claim='{"evidenceId":42,"evidenceVersion":7,"company":"Northstar","jobTitle":"Engineer","summary":"Built systems","employeeName":"Example User"}',
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
            claim='{"evidenceId":42,"evidenceVersion":7,"company":"Northstar","jobTitle":"Engineer","summary":"Built systems","employeeName":"Example User"}',
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

    def test_local_analysis_uses_strict_document_understanding_contract(self):
        legacy = analyze_document(
            ClaimFields(evidenceId=42, evidenceVersion=7, company="Northstar", jobTitle="Engineer",
                        summary="Built systems", employeeName="Example User"),
            text_pdf(),
        )
        body = _legacy_to_strict_document_response(legacy)
        self.assertEqual("mentorax.document-understanding.v1", body["schemaVersion"])
        self.assertIn(body["documentClassification"], {"EMPLOYMENT_CERTIFICATE", "UNKNOWN"})
        self.assertIn(body["analysisStatus"], {"COMPLETE", "PARTIAL", "UNREADABLE"})
        self.assertTrue(all(item["status"] in {"FOUND", "NOT_PRESENT", "UNREADABLE"} for item in body["fields"]))

    def test_invalid_schema_version_and_field_status_are_rejected(self):
        with self.assertRaises(Exception):
            ExtractionResponse.model_validate({
                "schemaVersion": "mentorax.document-understanding.v2",
                "analysisStatus": "COMPLETE",
                "evidenceId": 1,
                "evidenceVersion": 1,
                "sha256": "a" * 64,
                "documentClassification": "UNKNOWN",
                "classificationConfidence": 0.0,
                "classificationProvenance": [],
                "ocrStatus": "NOT_NEEDED",
                "fields": [{
                    "name": "personName",
                    "status": "VERIFIED",
                    "extractionMethod": "LABEL_RULE",
                    "provenance": [{
                        "sourceEvidenceId": 1,
                        "evidenceVersion": 1,
                        "pageNumber": 1,
                        "textExcerpt": "Example",
                        "extractionMethod": "PDF_TEXT",
                    }],
                }],
                "extractedStatements": [],
                "warnings": [],
            })

    def test_hosted_provider_paths_are_disabled_for_document_analysis(self):
        with patch.dict(os.environ, {"EXPERIENCE_ANALYSIS_LLM_PROVIDER": "openai-compatible"}, clear=False):
            response = self._upload(text_pdf())
        self.assertEqual(503, response.status_code)
        self.assertIn("disabled", response.json()["detail"])

    def test_unrecognized_provider_is_rejected_before_any_network_request(self):
        with patch.dict(os.environ, {"EXPERIENCE_ANALYSIS_LLM_PROVIDER": "openrouter"}, clear=False):
            response = self._upload(text_pdf())
        self.assertEqual(503, response.status_code)

    def test_image_mime_must_match_image_signature(self):
        response = self._upload(blank_pdf(), mime="image/png", filename="evidence.png")
        self.assertEqual(415, response.status_code)


if __name__ == "__main__":
    unittest.main()
