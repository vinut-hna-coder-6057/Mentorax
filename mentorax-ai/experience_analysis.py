"""Private, bounded evidence extraction and structured analysis for experience claims."""
import hashlib
import hmac
import json
import logging
from io import BytesIO
import multiprocessing
import os
import re
import threading
import time
import unicodedata
from urllib import error, request

from fastapi import APIRouter, File, Form, Header, HTTPException, UploadFile
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from pypdf import PdfReader
from starlette.concurrency import run_in_threadpool

from document_understanding import (
    BoundingBox,
    ClaimType,
    DocumentClassification,
    ExtractionRequest,
    ExtractionResponse,
    ExtractedStatement,
    ExtractionMethod,
    FieldExtraction,
    FieldName,
    FieldStatus,
    MAX_FIELDS,
    MAX_SOURCE_EXCERPT,
    Provenance,
    SourceMethod,
    sanitize_sensitive_content,
    sanitize_sensitive_text,
)
from document_understanding import analyze_document as analyze_document_v1

MAX_EVIDENCE_BYTES = 5 * 1024 * 1024
MAX_EXTRACTED_CHARS = 100_000
MAX_DOCUMENT_PAGES = 20
MAX_RENDERED_IMAGE_PIXELS = 25_000_000
MIN_TEXT_CHARACTERS = 80
OCR_TIMEOUT_SECONDS = 60
DOCUMENT_TIMEOUT_SECONDS = 90
LLM_TIMEOUT_SECONDS = 45
LLM_RETRIES = 2
_slots = threading.BoundedSemaphore(2)
_router = APIRouter()
_logger = logging.getLogger(__name__)
_YEAR_MONTH = re.compile(r"\b(\d{4})[-/.](\d{1,2})\b")
_NUMERIC_MONTH_YEAR = re.compile(r"\b(0?[1-9]|1[0-2])[-/.](\d{4})\b")
_MONTH_NAME_YEAR = re.compile(
    r"(?i)\b(January|Jan|February|Feb|March|Mar|April|Apr|May|June|Jun|July|Jul|"
    r"August|Aug|September|Sept|Sep|October|Oct|November|Nov|December|Dec)\.?\s+"
    r"(?:\d{1,2}(?:st|nd|rd|th)?[,]?\s+)?(\d{4})\b")
_MONTH_NUMBERS = {
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6,
    "jul": 7, "aug": 8, "sep": 9, "oct": 10, "nov": 11, "dec": 12,
}


class ClaimFields(BaseModel):
    model_config = ConfigDict(extra="forbid")
    evidenceId: int = Field(gt=0)
    evidenceVersion: int = Field(gt=0)
    sha256: str | None = Field(default=None, pattern=r"^[a-fA-F0-9]{64}$")
    claimType: ClaimType = ClaimType.UNSPECIFIED
    company: str = Field(min_length=1, max_length=150)
    jobTitle: str = Field(min_length=1, max_length=150)
    startMonth: str | None = Field(default=None, max_length=7)
    endMonth: str | None = Field(default=None, max_length=7)
    summary: str = Field(min_length=1, max_length=2000)
    employeeName: str | None = Field(default=None, max_length=255)


def _normalise(value: str) -> str:
    folded = unicodedata.normalize("NFKD", value).encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", " ", folded).strip()


def _normalise_month(value: str) -> str:
    match = _YEAR_MONTH.search(value)
    if match:
        month = int(match.group(2))
        if 1 <= month <= 12:
            return f"{match.group(1)}-{month:02d}"
    match = _NUMERIC_MONTH_YEAR.search(value)
    if match:
        return f"{match.group(2)}-{int(match.group(1)):02d}"
    match = _MONTH_NAME_YEAR.search(value)
    if match:
        month = _MONTH_NUMBERS[match.group(1)[:3].lower()]
        return f"{match.group(2)}-{month:02d}"
    return _normalise(value)


def _field_finding(field: str, claimed: str | None, extracted: str | None) -> dict:
    if not claimed:
        return {"field": field, "claimValue": None, "extractedValue": extracted,
                "outcome": "INCONCLUSIVE", "explanation": "No value was supplied in the claim."}
    if not extracted:
        return {"field": field, "claimValue": claimed, "extractedValue": None,
                "outcome": "MISSING", "explanation": "No matching text could be extracted from the document."}
    normalize = _normalise_month if field in {"startMonth", "endMonth"} else _normalise
    claim_text, extracted_text = normalize(claimed), normalize(extracted)
    matches = claim_text == extracted_text
    return {"field": field, "claimValue": claimed, "extractedValue": extracted[:255],
            "outcome": "MATCH" if matches else "MISMATCH",
            "explanation": "Text similarity only; issuer authenticity is not verified."}


class OCRExecutionError(RuntimeError):
    pass


def _map_rotated_point(x: float, y: float, width: int, height: int, angle: int) -> tuple[float, float]:
    if angle == 90:
        return width - y, x
    if angle == 180:
        return width - x, height - y
    if angle == 270:
        return y, height - x
    return x, y


def _ocr_worker(connection) -> None:
    try:
        import numpy as np
        from PIL import Image, ImageOps
        from rapidocr_onnxruntime import RapidOCR

        engine = RapidOCR()
        while True:
            item = connection.recv()
            if item is None:
                return
            page_number, image_bytes, width, height = item
            image = Image.open(BytesIO(image_bytes)).convert("RGB")
            image = ImageOps.autocontrast(image.convert("L")).convert("RGB")
            best_text = ""
            best_words = []
            angles = (90, 0, 180, 270) if height > width else (0, 180, 90, 270)
            for angle in angles:
                candidate = image.rotate(angle, expand=True) if angle else image
                result, _ = engine(np.asarray(candidate))
                words = []
                if result:
                    for entry in result:
                        if len(entry) < 2 or not str(entry[1]).strip():
                            continue
                        points = entry[0]
                        if not points or len(points) < 4:
                            continue
                        mapped = [
                            _map_rotated_point(float(point[0]), float(point[1]), width, height, angle)
                            for point in points
                        ]
                        left = min(point[0] for point in mapped)
                        top = min(point[1] for point in mapped)
                        right = max(point[0] for point in mapped)
                        bottom = max(point[1] for point in mapped)
                        confidence = float(entry[2]) if len(entry) > 2 else None
                        words.append({
                            "text": str(entry[1]).strip(),
                            "bbox": [
                                max(0.0, min(1.0, left / width)),
                                max(0.0, min(1.0, top / height)),
                                max(0.0, min(1.0, right / width)),
                                max(0.0, min(1.0, bottom / height)),
                            ],
                            "confidence": confidence if confidence is not None
                            and 0 <= confidence <= 1 else None,
                        })
                candidate_text = "\n".join(word["text"] for word in words)
                if len(candidate_text) > len(best_text):
                    best_text = candidate_text
                    best_words = words
                confidences = [
                    word["confidence"] for word in words if word["confidence"] is not None
                ]
                if (len(words) >= 3 and len(candidate_text) >= 24 and confidences
                        and sum(confidences) / len(confidences) >= 0.65):
                    best_text = candidate_text
                    best_words = words
                    break
            connection.send((True, {
                "page": page_number,
                "text": best_text,
                "words": best_words,
            }))
    except Exception as exception:
        try:
            connection.send((False, type(exception).__name__))
        except (BrokenPipeError, EOFError, OSError):
            pass
    finally:
        connection.close()


class BoundedOCRWorker:
    def __init__(self) -> None:
        try:
            timeout = int(os.getenv("EXPERIENCE_ANALYSIS_OCR_TIMEOUT_SECONDS", OCR_TIMEOUT_SECONDS))
        except (TypeError, ValueError) as exception:
            raise OCRExecutionError("OCR timeout configuration is invalid") from exception
        if not 1 <= timeout <= 120:
            raise OCRExecutionError("OCR timeout configuration is outside the supported range")
        self.timeout = timeout
        self.context = multiprocessing.get_context("spawn")
        self.parent, child = self.context.Pipe(duplex=True)
        self.process = self.context.Process(target=_ocr_worker, args=(child,), daemon=True)
        try:
            self.process.start()
        except (OSError, RuntimeError) as exception:
            self.parent.close()
            child.close()
            raise OCRExecutionError("OCR worker could not be started") from exception
        child.close()
        self.deadline = time.monotonic() + timeout

    def extract(self, page_number: int, image_bytes: bytes, width: int, height: int) -> dict:
        try:
            self.parent.send((page_number, image_bytes, width, height))
            remaining = self.deadline - time.monotonic()
            if remaining <= 0 or not self.parent.poll(remaining):
                raise OCRExecutionError("OCR worker exceeded its time limit")
            succeeded, result = self.parent.recv()
            if not succeeded:
                error_type = result if isinstance(result, str) and result.isidentifier() else "WorkerError"
                raise OCRExecutionError(f"OCR worker failed ({error_type})")
            if not isinstance(result, dict):
                raise OCRExecutionError("OCR worker could not process the document")
            return result
        except (BrokenPipeError, EOFError, OSError) as exception:
            raise OCRExecutionError("OCR worker stopped unexpectedly") from exception

    def close(self) -> None:
        try:
            if self.process.is_alive():
                try:
                    self.parent.send(None)
                except (BrokenPipeError, EOFError, OSError):
                    pass
                self.process.join(timeout=1)
            if self.process.is_alive():
                self.process.terminate()
                self.process.join(timeout=1)
            if self.process.is_alive():
                self.process.kill()
                self.process.join(timeout=1)
        finally:
            self.parent.close()
            self.process.close()


def _extract_document(data: bytes, content_type: str) -> tuple[str, list[str], list[dict], str]:
    pages: list[dict] = []
    suspicious_features: list[str] = []
    ocr_used = False
    ocr_failed = False
    ocr_worker: BoundedOCRWorker | None = None

    def extract_ocr(page_number: int, image_bytes: bytes, width: int, height: int) -> dict:
        nonlocal ocr_worker
        if ocr_worker is None:
            ocr_worker = BoundedOCRWorker()
        return ocr_worker.extract(page_number, image_bytes, width, height)

    try:
        if content_type == "application/pdf":
            if not data.startswith(b"%PDF-"):
                raise HTTPException(status_code=415, detail="Only valid PDF evidence is supported")
            reader = PdfReader(BytesIO(data), strict=True)
            if reader.is_encrypted:
                raise HTTPException(status_code=422, detail="Encrypted PDFs are not supported")
            if not 1 <= len(reader.pages) <= MAX_DOCUMENT_PAGES:
                raise HTTPException(status_code=422, detail="PDF page count is unsupported")
            suspicious_features = _suspicious_pdf_features(reader)
            import fitz
            document = fitz.open(stream=data, filetype="pdf")
            for index, page in enumerate(document):
                text = page.get_text("text").strip()
                page_method = "PDF_TEXT"
                page_ocr_failed = False
                page_words = []
                if len(text) < MIN_TEXT_CHARACTERS:
                    ocr_used = True
                    page_method = "OCR"
                    try:
                        page_width = page.rect.width
                        page_height = page.rect.height
                        if page_width <= 0 or page_height <= 0:
                            raise ValueError("PDF page dimensions are invalid")
                        scale = min(
                            2,
                            4000 / max(page_width, page_height),
                            (MAX_RENDERED_IMAGE_PIXELS / (page_width * page_height)) ** 0.5,
                        )
                        pixmap = page.get_pixmap(matrix=fitz.Matrix(scale, scale), alpha=False)
                        image_bytes = pixmap.tobytes("png")
                        from PIL import Image
                        image = Image.open(BytesIO(image_bytes))
                        page_result = extract_ocr(
                            index + 1, image_bytes, image.width, image.height)
                        text = str(page_result.get("text", "")).strip()
                        page_words = page_result.get("words", [])
                    except (OCRExecutionError, OSError, ValueError) as exception:
                        _logger.warning("OCR failed for evidence page %s: %s", index + 1, exception)
                        ocr_failed = True
                        page_ocr_failed = True
                pages.append({"page": index + 1, "text": text, "method": page_method,
                              "ocrFailed": page_ocr_failed, "words": page_words})
                if sum(len(item["text"]) for item in pages) > MAX_EXTRACTED_CHARS:
                    break
            document.close()
        elif content_type in {"image/jpeg", "image/png"}:
            from PIL import Image, ImageOps
            expected_signature = b"\xff\xd8\xff" if content_type == "image/jpeg" else b"\x89PNG\r\n\x1a\n"
            if not data.startswith(expected_signature):
                raise HTTPException(status_code=415, detail="Image content does not match its media type")
            image = Image.open(BytesIO(data))
            if image.width * image.height > 25_000_000:
                raise HTTPException(status_code=413, detail="Image dimensions are too large")
            image.verify()
            image = ImageOps.exif_transpose(Image.open(BytesIO(data))).convert("RGB")
            ocr_used = True
            try:
                rendered = BytesIO()
                image.save(rendered, format="PNG")
                page_result = extract_ocr(
                    1, rendered.getvalue(), image.width, image.height)
                extracted = str(page_result.get("text", "")).strip()
                pages.append({"page": 1, "text": extracted, "method": "OCR",
                              "ocrFailed": False, "words": page_result.get("words", [])})
            except (OCRExecutionError, OSError, ValueError) as exception:
                _logger.warning("OCR failed for image evidence: %s", exception)
                ocr_failed = True
                pages.append({"page": 1, "text": "", "method": "OCR",
                              "ocrFailed": True, "words": []})
        else:
            raise HTTPException(status_code=415, detail="Unsupported evidence format")
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=422, detail="Evidence could not be safely extracted") from exc
    finally:
        if ocr_worker is not None:
            ocr_worker.close()

    for page in pages:
        page["text"] = sanitize_sensitive_text(page["text"])
    text = "\n".join(f"[page {item['page']}]\n{item['text']}" for item in pages)
    text = text[:MAX_EXTRACTED_CHARS]
    ocr_status = (
        "FAILED" if ocr_used and (ocr_failed or not any(item["text"] for item in pages))
        else "SUCCESS" if ocr_used else "NOT_NEEDED"
    )
    return text, suspicious_features, pages, ocr_status


def _document_extraction_worker(connection, data: bytes, content_type: str) -> None:
    try:
        connection.send((True, _extract_document(data, content_type), None))
    except HTTPException as exception:
        connection.send((False, exception.status_code, str(exception.detail)))
    except Exception as exception:
        _logger.warning("Document extraction worker failed (%s)", type(exception).__name__)
        connection.send((False, 422, "Evidence could not be safely extracted"))
    finally:
        connection.close()


class BoundedDocumentExtractor:
    def __init__(self, data: bytes, content_type: str) -> None:
        try:
            timeout = int(os.getenv(
                "EXPERIENCE_ANALYSIS_DOCUMENT_TIMEOUT_SECONDS", DOCUMENT_TIMEOUT_SECONDS))
        except (TypeError, ValueError) as exception:
            raise HTTPException(status_code=503, detail="Evidence analysis is unavailable") from exception
        if not 1 <= timeout <= DOCUMENT_TIMEOUT_SECONDS:
            raise HTTPException(status_code=503, detail="Evidence analysis is unavailable")
        self.timeout = timeout
        self.context = multiprocessing.get_context("spawn")
        self.parent, child = self.context.Pipe(duplex=False)
        self.process = self.context.Process(
            target=_document_extraction_worker, args=(child, data, content_type))
        try:
            self.process.start()
        except (OSError, RuntimeError) as exception:
            self.parent.close()
            child.close()
            raise HTTPException(status_code=503, detail="Evidence analysis is unavailable") from exception
        child.close()
        self.deadline = time.monotonic() + timeout

    def extract(self) -> tuple[str, list[str], list[dict], str]:
        try:
            remaining = self.deadline - time.monotonic()
            if remaining <= 0 or not self.parent.poll(remaining):
                raise HTTPException(status_code=503, detail="Evidence analysis exceeded its time limit")
            response = self.parent.recv()
        except (EOFError, OSError) as exception:
            raise HTTPException(status_code=503, detail="Evidence analysis worker stopped unexpectedly") from exception
        if not isinstance(response, tuple) or len(response) != 3:
            raise HTTPException(status_code=503, detail="Evidence analysis worker returned an invalid result")
        succeeded, result, detail = response
        if not succeeded:
            status_code = result if isinstance(result, int) and 400 <= result <= 599 else 503
            safe_detail = detail if isinstance(detail, str) else "Evidence could not be safely extracted"
            raise HTTPException(status_code=status_code, detail=safe_detail)
        if (not isinstance(result, tuple) or len(result) != 4
                or not isinstance(result[0], str) or not isinstance(result[1], list)
                or not isinstance(result[2], list) or not isinstance(result[3], str)):
            raise HTTPException(status_code=503, detail="Evidence analysis worker returned an invalid result")
        return result

    def close(self) -> None:
        try:
            if self.process.is_alive():
                self.process.join(timeout=1)
            if self.process.is_alive():
                self.process.terminate()
                self.process.join(timeout=1)
            if self.process.is_alive():
                self.process.kill()
                self.process.join(timeout=1)
        finally:
            self.parent.close()
            self.process.close()


def _bounded_extract_document(data: bytes, content_type: str) -> tuple[str, list[str], list[dict], str]:
    worker = BoundedDocumentExtractor(data, content_type)
    try:
        return worker.extract()
    finally:
        worker.close()


def _provider_settings() -> dict:
    provider = os.getenv("EXPERIENCE_ANALYSIS_LLM_PROVIDER", "").strip().lower()
    if provider and provider not in {"local", "none", "disabled", "off"}:
        raise HTTPException(
            status_code=503,
            detail="Hosted AI provider paths are disabled for experience analysis",
        )
    model = os.getenv("EXPERIENCE_ANALYSIS_LLM_MODEL", "").strip()
    base_url = os.getenv("EXPERIENCE_ANALYSIS_LLM_BASE_URL", "").strip()
    api_key = os.getenv("EXPERIENCE_ANALYSIS_LLM_API_KEY", "").strip()
    return {
        "provider": provider or "local",
        "model": model or None,
        "base_url": base_url or None,
        "api_key": api_key or None,
    }


def _validate_llm_response(payload: object) -> dict | None:
    if not isinstance(payload, dict) or payload.get("analysisVersion") != "2.0":
        return None
    classes = {"EMPLOYMENT_LETTER", "EMPLOYMENT_CERTIFICATE", "EXPERIENCE_CERTIFICATE",
               "PAYSLIP", "RELIEVING_LETTER", "OFFER_LETTER", "UNKNOWN"}
    if payload.get("documentClassification") not in classes:
        return None
    details = payload.get("extractedDetails")
    detail_fields = {
        "employeeName", "companyName", "jobTitle", "employmentType",
        "startDate", "endDate", "issueDate", "issuer", "verificationIdentifiers",
        "employmentStatements",
    }
    if not isinstance(details, dict) or set(details) - detail_fields:
        return None
    if not detail_fields.issubset(details):
        return None
    if any(value is not None and (not isinstance(value, str) or len(value) > 500)
           for value in details.values()):
        return None
    field_assessments = payload.get("fieldAssessments")
    if not isinstance(field_assessments, list) or len(field_assessments) > 20:
        return None
    outcomes = {"MATCH", "MISMATCH", "MISSING", "INCONCLUSIVE"}
    for finding in field_assessments:
        if not isinstance(finding, dict):
            return None
        allowed_finding_fields = {
            "field", "outcome", "explanation", "evidenceReference", "pageNumber", "textExcerpt",
        }
        if set(finding) - allowed_finding_fields:
            return None
        if not allowed_finding_fields.issubset(finding):
            return None
        if not isinstance(finding.get("field"), str) or len(finding["field"]) > 64:
            return None
        if finding.get("outcome") not in outcomes:
            return None
        if not isinstance(finding.get("explanation"), str) or len(finding["explanation"]) > 500:
            return None
        for field in ("evidenceReference", "textExcerpt"):
            if field in finding and (not isinstance(finding[field], str) or len(finding[field]) > 500):
                return None
        if finding["pageNumber"] is not None and (
                not isinstance(finding["pageNumber"], int) or not 1 <= finding["pageNumber"] <= MAX_DOCUMENT_PAGES):
            return None
        if finding["evidenceReference"] is not None and (
                not isinstance(finding["evidenceReference"], str) or len(finding["evidenceReference"]) > 100):
            return None
        if finding["textExcerpt"] is not None and (
                not isinstance(finding["textExcerpt"], str) or len(finding["textExcerpt"]) > 500):
            return None
    cross_findings = payload.get("crossDocumentFindings")
    missing = payload.get("missingInformation")
    risks = payload.get("riskIndicators")
    if not isinstance(cross_findings, list) or len(cross_findings) > 20:
        return None
    if len(field_assessments) + len(cross_findings) > 20:
        return None
    if not isinstance(missing, list) or len(missing) > 20:
        return None
    if not isinstance(risks, list) or len(risks) > 5:
        return None
    if any(not isinstance(value, str) or len(value) > 255 for value in missing + risks):
        return None
    for item in cross_findings:
        if not isinstance(item, dict) or set(item) != {
                "field", "outcome", "evidenceReferences", "explanation"}:
            return None
        if not isinstance(item["field"], str) or len(item["field"]) > 64:
            return None
        if item["outcome"] not in {"MATCH", "MISMATCH", "INCONCLUSIVE"}:
            return None
        if not isinstance(item["explanation"], str) or len(item["explanation"]) > 500:
            return None
        if not isinstance(item["evidenceReferences"], list) or len(item["evidenceReferences"]) > 20:
            return None
        if any(not isinstance(reference, str) or len(reference) > 100
               for reference in item["evidenceReferences"]):
            return None
    if payload.get("evidenceQuality") not in {"HIGH", "MEDIUM", "LOW"}:
        return None
    if payload.get("recommendedAction") not in {
            "REQUEST_ADDITIONAL_EVIDENCE", "REQUEST_INDEPENDENT_CORROBORATION", "NO_ACTION"}:
        return None
    if not isinstance(payload.get("explanation"), str) or len(payload["explanation"]) > 500:
        return None
    result = dict(payload)
    result["schemaVersion"] = "mentorax-experience-analysis-v2"
    result["analysisStatus"] = "INCONCLUSIVE" if missing or any(
        finding["outcome"] in {"MISSING", "INCONCLUSIVE"} for finding in field_assessments
    ) else "ANALYZED"
    result["verificationDecision"] = "NONE"
    result["trustedIssuerValidation"] = False
    result["documentType"] = result["documentClassification"]
    result["suspiciousFeatures"] = list(risks)
    result["findings"] = list(field_assessments) + list(cross_findings)
    return result


def _llm_analyze_document(claim: ClaimFields, text: str, suspicious_features: list[str],
                          ocr_status: str) -> dict | None:
    config = _provider_settings()
    if config["provider"] == "local":
        raise HTTPException(status_code=503, detail="Hosted AI provider paths are disabled")
    raise HTTPException(status_code=503, detail="Hosted AI provider paths are disabled")


def _page_for_value(pages: list[dict], value: str | None) -> tuple[int | None, str | None]:
    if not value:
        return None, None
    for page in pages:
        if re.search(re.escape(value), page["text"], re.IGNORECASE):
            return page["page"], page["text"][:500]
    return None, None


def _evidence_reference(evidence_id: int, evidence_version: int, page_number: int | None = None) -> str:
    base = f"evidence-{evidence_id}-v{evidence_version}"
    return f"{base}-page-{page_number}" if page_number is not None else base


def analyze_document(claim: ClaimFields, data: bytes, content_type: str = "application/pdf") -> dict:
    _provider_settings()
    text, suspicious_features, pages, ocr_status = _extract_document(data, content_type)
    text = sanitize_sensitive_text(text)
    pages = [
        {
            **page,
            "text": sanitize_sensitive_text(page.get("text", "")),
        }
        for page in pages
    ]
    normalized_text = re.sub(r"\s+", " ", text or "").strip().lower()
    evidence_sha256 = hashlib.sha256(data).hexdigest()
    readable = any(page.get("text", "").strip() for page in pages)
    partial = readable and (ocr_status == "FAILED" or any(page.get("ocrFailed") for page in pages))
    analysis_status = "UNREADABLE" if not readable else "PARTIAL" if partial else "COMPLETE"
    document_type = _document_type(text) if readable else "UNKNOWN"

    def to_provenance(page_number: int | None, excerpt: str | None = None) -> list[Provenance]:
        if page_number is None:
            return []
        source_page = next((page for page in pages if page.get("page") == page_number), None)
        if source_page is None:
            return []
        excerpt_text = (excerpt or source_page.get("text", "") or "").strip()
        if not excerpt_text:
            return []
        method = source_page.get("method") or ("PDF_TEXT" if content_type == "application/pdf" else "OCR")
        return [Provenance(
            sourceEvidenceId=claim.evidenceId,
            evidenceVersion=claim.evidenceVersion,
            pageNumber=page_number,
            textExcerpt=excerpt_text[:MAX_SOURCE_EXCERPT],
            boundingBox=None,
            extractionMethod=SourceMethod(method) if method in {"PDF_TEXT", "OCR"} else SourceMethod.PDF_TEXT,
            sourceConfidence=0.8 if readable else None,
        )]

    def field_value(field_name: str) -> str | None:
        if field_name == "employeeName":
            return _extract_labeled_value(text, claim.employeeName, r"(?:employee\s*name|employee|full\s*name|name)")
        if field_name == "company":
            return _extract_labeled_value(text, claim.company, r"(?:company|employer|organization|organisation)")
        if field_name == "jobTitle":
            return _extract_labeled_value(text, claim.jobTitle, r"(?:job\s*title|position|designation|role)")
        if field_name == "startMonth":
            return _find_date(text, claim.startMonth)
        if field_name == "endMonth":
            return _find_date(text, claim.endMonth, end=True)
        if field_name == "issueDate":
            return _extract_labeled_value(text, None, r"(?:issue\s*date|date\s*of\s*issue|issued\s*on)")
        return None

    field_specs = [
        ("employeeName", FieldName.PERSON_NAME, field_value("employeeName")),
        ("company", FieldName.ORGANISATION, field_value("company")),
        ("jobTitle", FieldName.ROLE, field_value("jobTitle")),
        ("startMonth", FieldName.START_DATE, field_value("startMonth")),
        ("endMonth", FieldName.END_DATE, field_value("endMonth")),
        ("issueDate", FieldName.ISSUE_DATE, field_value("issueDate")),
    ]

    fields: list[FieldExtraction] = []
    for field_name, field_enum, extracted in field_specs:
        if extracted is None and not readable:
            status = FieldStatus.UNREADABLE
        elif extracted is None:
            status = FieldStatus.NOT_PRESENT
        else:
            status = FieldStatus.FOUND
        page_number = None
        if extracted:
            page_number = next((page["page"] for page in pages if re.search(re.escape(extracted), page["text"], re.IGNORECASE)), None)
        fields.append(FieldExtraction(
            name=field_enum,
            status=status,
            value=extracted[:500] if extracted else None,
            confidence=0.8 if status == FieldStatus.FOUND else None,
            extractionMethod=ExtractionMethod.LABEL_RULE if field_name in {"employeeName", "company", "jobTitle"} else ExtractionMethod.DATE_RULE,
            provenance=to_provenance(page_number, extracted[:MAX_SOURCE_EXCERPT] if extracted else None),
        ))

    classification_provenance = []
    first_page = pages[0] if pages else None
    if first_page and readable and first_page.get("text", "").strip():
        classification_provenance = to_provenance(first_page["page"], first_page["text"][:MAX_SOURCE_EXCERPT])

    statement_rules = (
        (FieldName.COMPLETION_STATEMENT, re.compile(r"\b(?:completed|completion|successfully finished|awarded)\b", re.I)),
        (FieldName.EMPLOYMENT_STATEMENT, re.compile(r"\b(?:employed|employment|worked|appointed|relieved)\b", re.I)),
        (FieldName.MENTORSHIP_STATEMENT, re.compile(r"\b(?:mentor|mentorship|mentored|mentee|supervisor)\b", re.I)),
    )
    extracted_statements: list[ExtractedStatement] = []
    for field_enum, regex in statement_rules:
        for page in pages:
            for line in page["text"].splitlines():
                candidate = line.strip()
                if candidate and regex.search(candidate):
                    source = to_provenance(page["page"], candidate[:MAX_SOURCE_EXCERPT])
                    if source:
                        extracted_statements.append(ExtractedStatement(
                            kind={FieldName.COMPLETION_STATEMENT: "COMPLETION", FieldName.EMPLOYMENT_STATEMENT: "EMPLOYMENT", FieldName.MENTORSHIP_STATEMENT: "MENTORSHIP"}[field_enum],
                            text=candidate[:500],
                            provenance=source,
                        ))
                    break
    classification_enum = DocumentClassification(document_type) if document_type in {member.value for member in DocumentClassification} else DocumentClassification.UNKNOWN
    response = ExtractionResponse(
        schemaVersion="mentorax.document-understanding.v1",
        analysisStatus=analysis_status,
        evidenceId=claim.evidenceId,
        evidenceVersion=claim.evidenceVersion,
        sha256=evidence_sha256,
        documentClassification=classification_enum,
        classificationConfidence=0.9 if classification_enum != DocumentClassification.UNKNOWN else 0.0,
        classificationProvenance=classification_provenance if classification_enum != DocumentClassification.UNKNOWN else [],
        ocrStatus=ocr_status,
        fields=fields[:MAX_FIELDS],
        extractedStatements=extracted_statements[:20],
        warnings=[warning for warning in [
            "PDF_ACTIVE_OR_EMBEDDED_CONTENT_PRESENT" if suspicious_features else "",
            "OCR_FAILED" if ocr_status == "FAILED" else "",
            "OCR_USED" if ocr_status == "SUCCESS" else "",
            "PARTIAL_EXTRACTION" if partial else "",
            "DOCUMENT_UNREADABLE" if not readable else "",
        ] if warning],
    )
    payload = response.model_dump(mode="json", exclude_none=True)
    legacy_fields = {
        FieldName.PERSON_NAME: "employeeName",
        FieldName.ORGANISATION: "company",
        FieldName.ROLE: "jobTitle",
        FieldName.START_DATE: "startMonth",
        FieldName.END_DATE: "endMonth",
        FieldName.ISSUE_DATE: "issueDate",
        FieldName.ISSUER: "issuer",
        FieldName.DURATION: "duration",
    }
    claim_values = {
        "employeeName": claim.employeeName,
        "company": claim.company,
        "jobTitle": claim.jobTitle,
        "startMonth": claim.startMonth,
        "endMonth": claim.endMonth,
    }
    field_assessments = []
    extracted_details = {
        "employeeName": None,
        "companyName": None,
        "jobTitle": None,
        "employmentType": None,
        "startDate": None,
        "endDate": None,
        "issueDate": None,
        "issuer": None,
        "verificationIdentifiers": None,
        "employmentStatements": None,
    }
    item_lookup = {item.name: item for item in response.fields}
    for field_name in [FieldName.ORGANISATION, FieldName.ROLE, FieldName.PERSON_NAME, FieldName.START_DATE, FieldName.END_DATE, FieldName.ISSUE_DATE]:
        item = item_lookup.get(field_name)
        if item is None:
            continue
        legacy_name = legacy_fields.get(item.name, item.name.value)
        page_number = item.provenance[0].pageNumber if item.provenance else None
        evidence_reference = _evidence_reference(claim.evidenceId, claim.evidenceVersion, page_number)
        text_excerpt = item.provenance[0].textExcerpt if item.provenance else None
        finding = _field_finding(legacy_name, claim_values.get(legacy_name), item.value if item.status == FieldStatus.FOUND else None)
        assessment = {
            "field": legacy_name,
            "outcome": finding["outcome"],
            "explanation": finding["explanation"],
            "evidenceReference": evidence_reference,
            "pageNumber": page_number,
            "textExcerpt": text_excerpt,
            "extractedValue": item.value if item.status == FieldStatus.FOUND and item.value is not None else None,
        }
        if item.status == FieldStatus.FOUND and item.value is not None:
            if legacy_name == "employeeName":
                extracted_details["employeeName"] = item.value
            elif legacy_name == "company":
                extracted_details["companyName"] = item.value
            elif legacy_name == "jobTitle":
                extracted_details["jobTitle"] = item.value
            elif legacy_name == "startMonth":
                extracted_details["startDate"] = item.value
            elif legacy_name == "endMonth":
                extracted_details["endDate"] = item.value
            elif legacy_name == "issueDate":
                extracted_details["issueDate"] = item.value
        field_assessments.append(assessment)

    employment_type = _extract_labeled_value(text, None, r"(?:employment\s*type)")
    if employment_type:
        page_number = next((page["page"] for page in pages if re.search(re.escape(employment_type), page["text"], re.IGNORECASE)), None)
        field_assessments.append({
            "field": "employmentType",
            "outcome": "MATCH" if claim_values.get("jobTitle") and employment_type.lower() in claim_values["jobTitle"].lower() else "MATCH",
            "explanation": "The employment type is stated in the evidence.",
            "evidenceReference": _evidence_reference(claim.evidenceId, claim.evidenceVersion, page_number),
            "pageNumber": page_number,
            "textExcerpt": employment_type,
            "extractedValue": employment_type,
        })
        extracted_details["employmentType"] = employment_type
    issue_date = _extract_labeled_value(text, None, r"(?:issue\s*date|date\s*of\s*issue|issued\s*on)")
    if issue_date:
        page_number = next((page["page"] for page in pages if re.search(re.escape(issue_date), page["text"], re.IGNORECASE)), None)
        field_assessments.append({
            "field": "issueDate",
            "outcome": "MATCH",
            "explanation": "The issue date was extracted from the evidence.",
            "evidenceReference": _evidence_reference(claim.evidenceId, claim.evidenceVersion, page_number),
            "pageNumber": page_number,
            "textExcerpt": issue_date,
            "extractedValue": issue_date,
        })
        extracted_details["issueDate"] = issue_date
    employment_statements = _extract_employment_statements(text)
    if employment_statements:
        page_number = next((page["page"] for page in pages if re.search(re.escape(employment_statements[:50]), page["text"], re.IGNORECASE)), None)
        field_assessments.append({
            "field": "employmentStatements",
            "outcome": "MATCH",
            "explanation": "The supporting employment statement was extracted from the evidence.",
            "evidenceReference": _evidence_reference(claim.evidenceId, claim.evidenceVersion, page_number),
            "pageNumber": page_number,
            "textExcerpt": employment_statements[:500],
            "extractedValue": employment_statements[:500],
        })
        extracted_details["employmentStatements"] = employment_statements[:500]
    payload["analysisMethod"] = "OCR_PLUS_HEURISTIC" if ocr_status in {"SUCCESS", "FAILED"} else "HEURISTIC_FALLBACK"
    payload["extractedTextFingerprint"] = hashlib.sha256(
        normalized_text.encode("utf-8")
    ).hexdigest()
    payload["documentType"] = response.documentClassification.value
    payload["trustedIssuerValidation"] = False
    payload["verificationDecision"] = "NONE"
    payload["analysisStatus"] = analysis_status
    payload["fieldAssessments"] = field_assessments
    payload["findings"] = field_assessments
    payload["extractedDetails"] = extracted_details
    payload["suspiciousFeatures"] = suspicious_features
    payload["riskIndicators"] = suspicious_features
    payload["crossDocumentFindings"] = []
    payload["missingInformation"] = []
    payload["evidenceQuality"] = "HIGH" if readable else "LOW"
    payload["recommendedAction"] = "REQUEST_ADDITIONAL_EVIDENCE" if not readable else "REQUEST_INDEPENDENT_CORROBORATION"
    payload["explanation"] = "Document content was extracted locally and checked for basic consistency; no external issuer validation is performed."
    return sanitize_sensitive_content(payload)


def _extract_labeled_value(text: str, expected: str | None, label_pattern: str) -> str | None:
    match = re.search(
        rf"(?im)^\s*{label_pattern}\s*[:\-]\s*([^\r\n]{{1,255}})",
        text,
    )
    if match:
        return match.group(1).strip()
    if expected and re.search(rf"\b{re.escape(expected)}\b", text, re.IGNORECASE):
        return expected
    return None


def _extract_employment_statements(text: str) -> str | None:
    statements = [
        line.strip() for line in text.splitlines()
        if re.search(r"\b(?:employed|worked|served|joined|terminated|resigned|appointed)\b", line, re.I)
    ]
    return "\n".join(statements)[:500] or None


def _find_date(text: str, value: str | None, *, end: bool = False) -> str | None:
    if not value:
        return None
    year, month = value.split("-")
    candidates = (f"{year}-{month}", f"{month}/{year}", f"{month}-{year}",
                  f"{year}/{month}", f"{month}/{year[2:]}")
    match = next((candidate for candidate in candidates
                  if re.search(re.escape(candidate), text)), None)
    if match:
        return value

    dates = re.findall(
        r"(?<!\d)(?:(?:19|20)\d{2}[-/](?:0?[1-9]|1[0-2])|"
        r"(?:0?[1-9]|1[0-2])[-/](?:(?:19|20)\d{2}|\d{2}))(?!\d)",
        text,
    )
    normalized = [_normalize_month(candidate) for candidate in dates]
    normalized = [candidate for candidate in normalized if candidate]
    return (normalized[-1] if end else normalized[0]) if normalized else None


def _normalize_month(value: str) -> str | None:
    parts = re.split(r"[-/]", value)
    if len(parts) != 2:
        return None
    if len(parts[0]) == 4:
        year, month = parts
    else:
        month, year = parts
        if len(year) == 2:
            year = f"20{year}"
    if not (month.isdigit() and year.isdigit() and 1 <= int(month) <= 12):
        return None
    return f"{int(year):04d}-{int(month):02d}"


def _document_type(text: str) -> str:
    normalized = _normalise(text)
    if any(term in normalized for term in ("payslip", "pay stub", "salary statement")):
        return "PAYSLIP"
    if any(term in normalized for term in (
        "employment certificate", "certificate of employment", "experience certificate",
    )):
        return "EMPLOYMENT_CERTIFICATE"
    if any(term in normalized for term in (
        "employment letter", "letter of employment", "offer of employment",
        "confirmation of employment",
    )):
        return "EMPLOYMENT_LETTER"
    return "UNKNOWN"


def _suspicious_pdf_features(reader: PdfReader) -> list[str]:
    root = reader.trailer.get("/Root")
    if root is None:
        return []
    root_object = root.get_object()
    features = []
    if root_object.get("/OpenAction") is not None or root_object.get("/AA") is not None:
        features.append("DOCUMENT_ACTIONS")
    names = root_object.get("/Names")
    if names is not None and names.get_object().get("/EmbeddedFiles") is not None:
        features.append("EMBEDDED_FILES")
    return features


@_router.post("/experience/analyze")
async def analyze_experience_document(
    claim: str = Form(...),
    file: UploadFile = File(...),
    authorization: str | None = Header(default=None),
):
    _provider_settings()
    expected = os.getenv("EXPERIENCE_ANALYSIS_API_TOKEN", "")
    supplied = authorization.removeprefix("Bearer ") if authorization else ""
    if not expected:
        raise HTTPException(status_code=503, detail="Experience analysis unavailable")
    if not hmac.compare_digest(supplied.encode(), expected.encode()):
        raise HTTPException(status_code=401, detail="Authentication required")
    if file.content_type not in {"application/pdf", "image/jpeg", "image/png"}:
        raise HTTPException(status_code=415, detail="Unsupported evidence format")
    try:
        claim_fields = ClaimFields.model_validate_json(claim)
    except ValidationError as exc:
        raise HTTPException(status_code=422, detail="Invalid experience claim") from exc
    claim_hash = claim_fields.sha256
    if claim_hash is None:
        raise HTTPException(status_code=422, detail="Evidence hash is required")
    data = await file.read(MAX_EVIDENCE_BYTES + 1)
    if len(data) > MAX_EVIDENCE_BYTES:
        raise HTTPException(status_code=413, detail="Evidence file is too large")
    if not data:
        raise HTTPException(status_code=422, detail="Evidence file is empty")
    actual_hash = hashlib.sha256(data).hexdigest()
    if not hmac.compare_digest(actual_hash, claim_hash.lower()):
        raise HTTPException(status_code=422, detail="Evidence integrity validation failed")
    if not _slots.acquire(blocking=False):
        raise HTTPException(status_code=429, detail="Experience analysis busy")
    try:
        extraction_request = ExtractionRequest(
            schemaVersion="mentorax.document-understanding.v1",
            evidenceId=claim_fields.evidenceId,
            evidenceVersion=claim_fields.evidenceVersion,
            sha256=claim_hash,
            claimType=claim_fields.claimType,
            company=claim_fields.company,
            jobTitle=claim_fields.jobTitle,
            startMonth=claim_fields.startMonth,
            endMonth=claim_fields.endMonth,
            summary=claim_fields.summary,
            personName=claim_fields.employeeName,
        )
        extracted_document = {}

        def extract_with_capture(document_bytes: bytes, content_type: str):
            result = _bounded_extract_document(document_bytes, content_type)
            extracted_document["result"] = result
            return result

        response = await run_in_threadpool(
            analyze_document_v1,
            extraction_request, data, file.content_type, extract_with_capture)
        payload = response.model_dump(mode="json", exclude_none=True)
        text, suspicious, _, ocr_status = extracted_document["result"]
        payload["analysisMethod"] = (
            "OCR_PLUS_HEURISTIC" if ocr_status in {"SUCCESS", "FAILED"}
            else "HEURISTIC_FALLBACK")
        payload["extractedTextFingerprint"] = hashlib.sha256(
            re.sub(r"\s+", " ", sanitize_sensitive_text(text)).strip().lower().encode("utf-8")
        ).hexdigest()
        payload["documentType"] = response.documentClassification.value
        payload["trustedIssuerValidation"] = False
        payload["verificationDecision"] = "NONE"
        payload["suspiciousFeatures"] = suspicious
        payload["riskIndicators"] = suspicious
        findings = []
        legacy_names = {
            FieldName.PERSON_NAME: "employeeName",
            FieldName.ORGANISATION: "company",
            FieldName.ROLE: "jobTitle",
            FieldName.START_DATE: "startMonth",
            FieldName.END_DATE: "endMonth",
            FieldName.ISSUE_DATE: "issueDate",
            FieldName.ISSUER: "issuer",
            FieldName.DURATION: "duration",
        }
        claim_values = {
            "employeeName": claim_fields.employeeName,
            "company": claim_fields.company,
            "jobTitle": claim_fields.jobTitle,
            "startMonth": claim_fields.startMonth,
            "endMonth": claim_fields.endMonth,
            "issueDate": None,
            "issuer": None,
            "duration": None,
        }
        for field in response.fields:
            if field.name not in legacy_names:
                continue
            provenance = field.provenance[0] if field.provenance else None
            legacy_name = legacy_names[field.name]
            extracted_value = field.value if field.status == FieldStatus.FOUND else None
            if field.status == FieldStatus.NOT_PRESENT:
                finding = {
                    "field": legacy_name,
                    "claimValue": claim_values.get(legacy_name),
                    "extractedValue": None,
                    "outcome": "MISSING",
                    "explanation": "No matching text could be extracted from the document.",
                }
            elif field.status == FieldStatus.FOUND:
                finding = _field_finding(
                    legacy_name, claim_values.get(legacy_name), extracted_value)
            else:
                finding = {
                    "field": legacy_name,
                    "claimValue": claim_values.get(legacy_name),
                    "extractedValue": None,
                    "outcome": "INCONCLUSIVE",
                    "explanation": "The source document contains ambiguous or unreadable content.",
                }
            findings.append({
                **finding,
                "evidenceReference": _evidence_reference(
                    claim_fields.evidenceId, claim_fields.evidenceVersion,
                    provenance.pageNumber if provenance else None),
                "pageNumber": provenance.pageNumber if provenance else None,
                "textExcerpt": provenance.textExcerpt if provenance else None,
                "extractedValue": extracted_value,
            })
        for statement in response.extractedStatements:
            source = statement.provenance[0]
            findings.append({
                "field": "employmentStatements",
                "outcome": "INCONCLUSIVE",
                "explanation": "A supporting statement was found; it is not issuer authentication.",
                "evidenceReference": _evidence_reference(
                    claim_fields.evidenceId, claim_fields.evidenceVersion, source.pageNumber),
                "pageNumber": source.pageNumber,
                "textExcerpt": source.textExcerpt,
                "extractedValue": statement.text,
            })
        payload["findings"] = findings
        payload["fieldAssessments"] = findings
        return sanitize_sensitive_content(payload)
    finally:
        _slots.release()


def _legacy_to_strict_document_response(legacy: dict) -> dict:
    evidence_id = legacy.get("evidenceId")
    evidence_version = legacy.get("evidenceVersion")
    if evidence_id is None or evidence_version is None:
        raise ValueError("Legacy document analysis response is missing evidence identity")
    legacy_fields = {
        "employeeName": FieldName.PERSON_NAME,
        "company": FieldName.ORGANISATION,
        "jobTitle": FieldName.ROLE,
        "startMonth": FieldName.START_DATE,
        "endMonth": FieldName.END_DATE,
        "issueDate": FieldName.ISSUE_DATE,
    }
    strict_fields = []
    for finding in legacy.get("findings", []):
        field_name = legacy_fields.get(finding.get("field"), None)
        if field_name is None:
            continue
        outcome = finding.get("outcome")
        if outcome == "MISSING":
            status = FieldStatus.NOT_PRESENT
            extracted_value = None
        elif outcome in {"MATCH", "MISMATCH"}:
            status = FieldStatus.FOUND
            extracted_value = finding.get("extractedValue") or finding.get("textExcerpt") or finding.get("claimValue")
        else:
            status = FieldStatus.UNREADABLE
            extracted_value = None
        strict_fields.append({
            "name": field_name.value,
            "status": status.value,
            "value": extracted_value[:500] if isinstance(extracted_value, str) else None,
            "confidence": 0.8 if status == FieldStatus.FOUND else None,
            "extractionMethod": ExtractionMethod.LABEL_RULE.value if field_name in {FieldName.PERSON_NAME, FieldName.ORGANISATION, FieldName.ROLE} else ExtractionMethod.DATE_RULE.value,
            "provenance": [{
                "sourceEvidenceId": evidence_id,
                "evidenceVersion": evidence_version,
                "pageNumber": finding.get("pageNumber") or 1,
                "textExcerpt": (finding.get("textExcerpt") or extracted_value or "Document excerpt")[:MAX_SOURCE_EXCERPT],
                "boundingBox": None,
                "extractionMethod": SourceMethod.PDF_TEXT.value if legacy.get("documentType") else SourceMethod.OCR.value,
                "sourceConfidence": 0.8 if status == FieldStatus.FOUND else None,
            }] if finding.get("pageNumber") or finding.get("textExcerpt") or extracted_value else [],
        })
    document_classification = legacy.get("documentType", "UNKNOWN")
    status = legacy.get("analysisStatus")
    if status in {"COMPLETE", "PARTIAL", "UNREADABLE"}:
        strict_status = status
    elif status == "INCONCLUSIVE":
        strict_status = "UNREADABLE" if not legacy.get("findings") else "PARTIAL"
    elif status == "ANALYZED":
        strict_status = "COMPLETE"
    else:
        raise ValueError("Legacy document analysis response has an unsupported status")
    return {
        "schemaVersion": "mentorax.document-understanding.v1",
        "analysisStatus": strict_status,
        "evidenceId": evidence_id,
        "evidenceVersion": evidence_version,
        "sha256": legacy.get("extractedTextFingerprint") or legacy.get("sha256") or "0" * 64,
        "documentClassification": document_classification,
        "classificationConfidence": 0.9 if document_classification != "UNKNOWN" else 0.0,
        "classificationProvenance": [],
        "ocrStatus": legacy.get("ocrStatus", "NOT_NEEDED"),
        "fields": strict_fields,
        "extractedStatements": [],
        "warnings": [],
    }


router = _router
