"""Private, bounded PDF text analysis for experience claims; never determines verification."""
import hmac
from io import BytesIO
import os
import re
import threading
import unicodedata

from fastapi import APIRouter, File, Form, Header, HTTPException, UploadFile
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from pypdf import PdfReader

MAX_PDF_BYTES = 5 * 1024 * 1024
MAX_EXTRACTED_CHARS = 100_000
_slots = threading.BoundedSemaphore(2)
_router = APIRouter()


class ClaimFields(BaseModel):
    model_config = ConfigDict(extra="forbid")
    company: str = Field(min_length=1, max_length=150)
    jobTitle: str = Field(min_length=1, max_length=150)
    startMonth: str | None = Field(default=None, max_length=7)
    endMonth: str | None = Field(default=None, max_length=7)
    summary: str = Field(min_length=1, max_length=2000)


def _normalise(value: str) -> str:
    folded = unicodedata.normalize("NFKD", value).encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", " ", folded).strip()


def _field_finding(field: str, claimed: str | None, extracted: str | None) -> dict:
    if not claimed:
        return {"field": field, "claimValue": None, "extractedValue": extracted,
                "outcome": "INCONCLUSIVE", "explanation": "No value was supplied in the claim."}
    if not extracted:
        return {"field": field, "claimValue": claimed, "extractedValue": None,
                "outcome": "MISSING", "explanation": "No matching text could be extracted from the document."}
    claim_text, extracted_text = _normalise(claimed), _normalise(extracted)
    matches = claim_text in extracted_text or extracted_text in claim_text
    return {"field": field, "claimValue": claimed, "extractedValue": extracted[:255],
            "outcome": "MATCH" if matches else "MISMATCH",
            "explanation": "Text similarity only; issuer authenticity is not verified."}


def _extract_pdf(data: bytes) -> str:
    if not data.startswith(b"%PDF-"):
        raise HTTPException(status_code=415, detail="Only valid PDF evidence is supported")
    try:
        reader = PdfReader(BytesIO(data), strict=True)
        if reader.is_encrypted:
            raise HTTPException(status_code=422, detail="Encrypted PDFs are not supported")
        extracted = "\n".join(page.extract_text() or "" for page in reader.pages[:20])
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=422, detail="PDF could not be safely read") from exc
    if len(extracted) > MAX_EXTRACTED_CHARS:
        extracted = extracted[:MAX_EXTRACTED_CHARS]
    return extracted


def analyze_document(claim: ClaimFields, data: bytes) -> dict:
    text = _extract_pdf(data)
    if not text.strip():
        findings = [
            {"field": "documentText", "claimValue": None, "extractedValue": None,
             "outcome": "INCONCLUSIVE",
             "explanation": "No selectable text was found. OCR is not configured; manual review is required."}
        ]
        state = "INCONCLUSIVE"
    else:
        company = _find_value(text, claim.company)
        title = _find_value(text, claim.jobTitle)
        start_month = _find_date(text, claim.startMonth)
        end_month = _find_date(text, claim.endMonth)
        findings = [
            _field_finding("company", claim.company, company),
            _field_finding("jobTitle", claim.jobTitle, title),
            _field_finding("startMonth", claim.startMonth, start_month),
            _field_finding("endMonth", claim.endMonth, end_month),
            {"field": "issuerAuthenticity", "claimValue": None, "extractedValue": None,
             "outcome": "INCONCLUSIVE",
             "explanation": "No trusted employer or issuer validation is configured."},
        ]
        state = "ANALYZED"
    return {
        "schemaVersion": "mentorax-experience-analysis-v1",
        "analysisStatus": state,
        "verificationDecision": "NONE",
        "trustedIssuerValidation": False,
        "findings": findings,
    }


def _find_value(text: str, value: str) -> str | None:
    pattern = re.compile(re.escape(value), re.IGNORECASE)
    match = pattern.search(text)
    if match:
        return match.group(0)
    return None


def _find_date(text: str, value: str | None) -> str | None:
    if not value:
        return None
    year, month = value.split("-")
    candidates = (f"{year}-{month}", f"{month}/{year}", f"{month}-{year}",
                  f"{year}/{month}", f"{month}/{year[2:]}")
    return next((candidate for candidate in candidates
                 if re.search(re.escape(candidate), text)), None)


@_router.post("/experience/analyze")
async def analyze_experience_document(
    claim: str = Form(...),
    file: UploadFile = File(...),
    authorization: str | None = Header(default=None),
):
    expected = os.getenv("EXPERIENCE_ANALYSIS_API_TOKEN", "")
    supplied = authorization.removeprefix("Bearer ") if authorization else ""
    if not expected:
        raise HTTPException(status_code=503, detail="Experience analysis unavailable")
    if not hmac.compare_digest(supplied.encode(), expected.encode()):
        raise HTTPException(status_code=401, detail="Authentication required")
    if file.content_type != "application/pdf":
        raise HTTPException(status_code=415, detail="Only PDF evidence is supported")
    try:
        claim_fields = ClaimFields.model_validate_json(claim)
    except ValidationError as exc:
        raise HTTPException(status_code=422, detail="Invalid experience claim") from exc
    data = await file.read(MAX_PDF_BYTES + 1)
    if len(data) > MAX_PDF_BYTES:
        raise HTTPException(status_code=413, detail="Evidence file is too large")
    if not data:
        raise HTTPException(status_code=422, detail="Evidence file is empty")
    if not _slots.acquire(blocking=False):
        raise HTTPException(status_code=429, detail="Experience analysis busy")
    try:
        return analyze_document(claim_fields, data)
    finally:
        _slots.release()


router = _router
