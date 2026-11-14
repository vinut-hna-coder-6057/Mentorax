"""Versioned, source-grounded document understanding for experience evidence."""

import hashlib
import hmac
import re
import unicodedata
from enum import Enum
from typing import Any, Callable

from pydantic import BaseModel, ConfigDict, Field, model_validator

MAX_DOCUMENT_PAGES = 20
MAX_SOURCE_EXCERPT = 500
MAX_FIELDS = 40

_SENSITIVE_LABELS = (
    r"one[\s-]*time(?:\s+(?:password|passcode|code))?",
    r"otp(?:\s+code)?",
    r"(?:verification|security|authentication|auth)\s+(?:code|passcode|token)",
    r"(?:password|passphrase|pin)",
    r"(?:api|client)\s*[-_ ]?key",
    r"(?:access|refresh|session|authentication|auth)\s*[-_ ]?token",
    r"(?:client|private)\s*[-_ ]?secret",
    r"secret(?:\s*[-_ ]?key)?",
    r"authorization(?:\s+token)?",
    r"bearer(?:\s+token)?",
    r"(?:bank\s+)?account(?:\s*(?:number|no\.?|id|identifier))?",
    r"(?:credit|debit)?\s*card\s*(?:number|no\.?)",
    r"(?:routing|sort)\s*(?:number|code)",
    r"(?:card\s+)?(?:cvv|cvc|security\s+code)",
    r"credential(?:s)?",
    r"token",
)
_SENSITIVE_LABEL_VALUE = re.compile(
    rf"(?P<label>\b(?:{'|'.join(_SENSITIVE_LABELS)})\b)"
    r"(?:\s*\([^)]*\))?(?:\s*(?:is|was|[:=#])\s*|\s+)"
    r"(?P<value>[^\r\n,;|]{1,256})",
    re.IGNORECASE,
)
_BEARER_CREDENTIAL = re.compile(r"\bBearer\s+[A-Za-z0-9._~+/-]+=*", re.IGNORECASE)
_KNOWN_SECRET_TOKEN = re.compile(
    r"\b(?:"
    r"sk-[A-Za-z0-9_-]{8,}|"
    r"AIza[0-9A-Za-z_-]{20,}|"
    r"gh[pousr]_[A-Za-z0-9_]{20,}|"
    r"AKIA[0-9A-Z]{16}|"
    r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"
    r")\b"
)


def sanitize_sensitive_text(value: str) -> str:
    """Redact labeled credentials and recognizable authentication tokens."""
    sanitized = _SENSITIVE_LABEL_VALUE.sub(
        lambda match: f"{match.group('label')} [REDACTED]",
        value,
    )
    sanitized = _BEARER_CREDENTIAL.sub("Bearer [REDACTED]", sanitized)
    return _KNOWN_SECRET_TOKEN.sub("[REDACTED]", sanitized)


def sanitize_sensitive_content(value: Any) -> Any:
    """Return a sanitized copy of nested response data without mutating its source."""
    if isinstance(value, str):
        return sanitize_sensitive_text(value)
    if isinstance(value, list):
        return [sanitize_sensitive_content(item) for item in value]
    if isinstance(value, dict):
        return {key: sanitize_sensitive_content(item) for key, item in value.items()}
    return value


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ClaimType(str, Enum):
    INTERNSHIP = "INTERNSHIP"
    MENTORSHIP = "MENTORSHIP"
    UNSPECIFIED = "UNSPECIFIED"


class DocumentClassification(str, Enum):
    INTERNSHIP_CERTIFICATE = "INTERNSHIP_CERTIFICATE"
    MENTORSHIP_CERTIFICATE = "MENTORSHIP_CERTIFICATE"
    EXPERIENCE_CERTIFICATE = "EXPERIENCE_CERTIFICATE"
    EMPLOYMENT_CERTIFICATE = "EMPLOYMENT_CERTIFICATE"
    OFFER_LETTER = "OFFER_LETTER"
    SELECTION_LETTER = "SELECTION_LETTER"
    RELIEVING_LETTER = "RELIEVING_LETTER"
    PAYSLIP = "PAYSLIP"
    MENTORSHIP_ACCEPTANCE = "MENTORSHIP_ACCEPTANCE"
    MENTOR_CONFIRMATION = "MENTOR_CONFIRMATION"
    ATTENDANCE_RECORD = "ATTENDANCE_RECORD"
    PROJECT_REPORT = "PROJECT_REPORT"
    EMAIL_EVIDENCE = "EMAIL_EVIDENCE"
    OTHER_SUPPORTING_EVIDENCE = "OTHER_SUPPORTING_EVIDENCE"
    UNKNOWN = "UNKNOWN"


class FieldName(str, Enum):
    PERSON_NAME = "personName"
    ORGANISATION = "organisation"
    PROGRAMME_NAME = "programmeName"
    ISSUER = "issuer"
    ROLE = "role"
    MENTOR_SUPERVISOR = "mentorSupervisor"
    EXPERIENCE_TYPE = "experienceType"
    START_DATE = "startDate"
    END_DATE = "endDate"
    DURATION = "duration"
    ISSUE_DATE = "issueDate"
    CERTIFICATE_REFERENCE_ID = "certificateReferenceId"
    VERIFICATION_URL = "verificationUrl"
    COMPLETION_STATEMENT = "completionStatement"
    EMPLOYMENT_STATEMENT = "employmentStatement"
    MENTORSHIP_STATEMENT = "mentorshipStatement"
    SUPPORTING_TEXT = "supportingText"


class FieldStatus(str, Enum):
    FOUND = "FOUND"
    NOT_PRESENT = "NOT_PRESENT"
    UNREADABLE = "UNREADABLE"
    AMBIGUOUS = "AMBIGUOUS"
    NOT_PROCESSED = "NOT_PROCESSED"


class ExtractionMethod(str, Enum):
    LABEL_RULE = "LABEL_RULE"
    DATE_RULE = "DATE_RULE"
    STATEMENT_RULE = "STATEMENT_RULE"
    CLASSIFIER_RULE = "CLASSIFIER_RULE"
    NOT_RUN = "NOT_RUN"


class SourceMethod(str, Enum):
    PDF_TEXT = "PDF_TEXT"
    OCR = "OCR"


class BoundingBox(StrictModel):
    x: float = Field(ge=0, le=1)
    y: float = Field(ge=0, le=1)
    width: float = Field(ge=0, le=1)
    height: float = Field(ge=0, le=1)

    @model_validator(mode="after")
    def stays_on_page(self):
        if self.x + self.width > 1.000001 or self.y + self.height > 1.000001:
            raise ValueError("bounding box must fit within normalized page coordinates")
        return self


class Provenance(StrictModel):
    sourceEvidenceId: int = Field(gt=0)
    evidenceVersion: int = Field(gt=0)
    pageNumber: int = Field(ge=1, le=MAX_DOCUMENT_PAGES)
    textExcerpt: str = Field(min_length=1, max_length=MAX_SOURCE_EXCERPT)
    boundingBox: BoundingBox | None
    extractionMethod: SourceMethod
    sourceConfidence: float | None = Field(default=None, ge=0, le=1)


class FieldExtraction(StrictModel):
    name: FieldName
    status: FieldStatus
    value: str | None = Field(default=None, max_length=500)
    candidateValues: list[str] = Field(default_factory=list, max_length=5)
    confidence: float | None = Field(default=None, ge=0, le=1)
    modelConfidence: float | None = Field(default=None, ge=0, le=1)
    extractionMethod: ExtractionMethod
    provenance: list[Provenance] = Field(default_factory=list, max_length=5)

    @model_validator(mode="after")
    def validate_status_value(self):
        if self.status == FieldStatus.FOUND:
            if not self.value or not self.provenance or self.candidateValues:
                raise ValueError("FOUND fields require a value and source provenance")
        elif self.status == FieldStatus.AMBIGUOUS:
            if self.value is not None or len(set(self.candidateValues)) < 2 or not self.provenance:
                raise ValueError("AMBIGUOUS fields require distinct candidates and provenance")
        elif self.value is not None or self.candidateValues:
            raise ValueError("non-found fields cannot contain a selected value")
        if self.status in {FieldStatus.NOT_PRESENT, FieldStatus.NOT_PROCESSED} and self.provenance:
            raise ValueError("absent or unprocessed fields cannot claim source provenance")
        return self


class ExtractionRequest(StrictModel):
    schemaVersion: str = Field(pattern=r"^mentorax\.document-understanding\.v1$")
    evidenceId: int = Field(gt=0)
    evidenceVersion: int = Field(gt=0)
    sha256: str = Field(pattern=r"^[a-fA-F0-9]{64}$")
    claimType: ClaimType
    company: str | None = Field(default=None, max_length=150)
    jobTitle: str | None = Field(default=None, max_length=150)
    startMonth: str | None = Field(default=None, max_length=7)
    endMonth: str | None = Field(default=None, max_length=7)
    summary: str | None = Field(default=None, max_length=2000)
    personName: str | None = Field(default=None, max_length=255)


class ClassificationEvidence(StrictModel):
    type: DocumentClassification
    confidence: float = Field(ge=0, le=1)
    provenance: list[Provenance] = Field(default_factory=list, max_length=3)


class ExtractedStatement(StrictModel):
    kind: str = Field(pattern=r"^(COMPLETION|EMPLOYMENT|MENTORSHIP|SUPPORTING)$")
    text: str = Field(min_length=1, max_length=500)
    provenance: list[Provenance] = Field(min_length=1, max_length=3)


class ExtractionResponse(StrictModel):
    schemaVersion: str = Field(pattern=r"^mentorax\.document-understanding\.v1$")
    analysisStatus: str = Field(
        pattern=r"^(COMPLETE|PARTIAL|UNREADABLE)$",
        description=(
            "COMPLETE means readable pages were processed; absent details use NOT_PRESENT. "
            "PARTIAL means readable content exists but OCR/page extraction was incomplete. "
            "UNREADABLE means no meaningful page text was extracted."
        ),
    )
    evidenceId: int = Field(gt=0)
    evidenceVersion: int = Field(gt=0)
    sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    documentClassification: DocumentClassification
    classificationConfidence: float = Field(ge=0, le=1)
    classificationProvenance: list[Provenance] = Field(default_factory=list, max_length=3)
    ocrStatus: str = Field(pattern=r"^(NOT_NEEDED|SUCCESS|PARTIAL|FAILED)$")
    fields: list[FieldExtraction] = Field(max_length=MAX_FIELDS)
    extractedStatements: list[ExtractedStatement] = Field(max_length=20)
    warnings: list[str] = Field(max_length=20)

    @model_validator(mode="after")
    def validate_contract(self):
        unique_fields = [
            item.name for item in self.fields
            if item.name not in {
                FieldName.COMPLETION_STATEMENT,
                FieldName.EMPLOYMENT_STATEMENT,
                FieldName.MENTORSHIP_STATEMENT,
                FieldName.SUPPORTING_TEXT,
            }
        ]
        if len(unique_fields) != len(set(unique_fields)):
            raise ValueError("single-valued fields must be unique")
        if self.documentClassification == DocumentClassification.UNKNOWN:
            if self.classificationProvenance:
                raise ValueError("UNKNOWN classification cannot assert supporting provenance")
        elif not self.classificationProvenance:
            raise ValueError("classified documents require source provenance")
        if self.analysisStatus == "UNREADABLE" and any(
                item.status == FieldStatus.FOUND for item in self.fields):
            raise ValueError("unreadable documents cannot report FOUND fields")
        return self


_CLASS_RULES: tuple[tuple[DocumentClassification, tuple[str, ...]], ...] = (
    (DocumentClassification.MENTORSHIP_CERTIFICATE,
     ("mentorship certificate", "certificate of mentorship", "mentoring completion certificate")),
    (DocumentClassification.INTERNSHIP_CERTIFICATE,
     ("internship certificate", "certificate of internship", "internship completion certificate")),
    (DocumentClassification.EXPERIENCE_CERTIFICATE,
     ("experience certificate", "certificate of experience")),
    (DocumentClassification.EMPLOYMENT_CERTIFICATE,
     ("employment certificate", "certificate of employment")),
    (DocumentClassification.OFFER_LETTER, ("offer letter", "letter of offer")),
    (DocumentClassification.SELECTION_LETTER, ("selection letter", "selected for the")),
    (DocumentClassification.RELIEVING_LETTER, ("relieving letter", "letter of relieving")),
    (DocumentClassification.PAYSLIP, ("payslip", "pay slip", "pay stub", "salary statement")),
    (DocumentClassification.MENTORSHIP_ACCEPTANCE,
     ("mentorship acceptance", "accepted as a mentee", "selected as a mentee")),
    (DocumentClassification.MENTOR_CONFIRMATION,
     ("mentor confirmation", "confirm that i mentored", "mentored by")),
    (DocumentClassification.ATTENDANCE_RECORD,
     ("attendance record", "attendance certificate", "attendance report")),
    (DocumentClassification.PROJECT_REPORT, ("project report", "project documentation")),
    (DocumentClassification.EMAIL_EVIDENCE, ("subject:", "from:", "to:")),
)

_FIELD_RULES: tuple[tuple[FieldName, str, ExtractionMethod], ...] = (
    (FieldName.PERSON_NAME, r"(?:student|intern|mentee|participant|employee|full\s*name|name)",
     ExtractionMethod.LABEL_RULE),
    (FieldName.ORGANISATION,
     r"(?:organisation|organization|institution|company|employer|host\s*organisation|host\s*organization)",
     ExtractionMethod.LABEL_RULE),
    (FieldName.PROGRAMME_NAME, r"(?:programme|program|course|initiative)\s*name",
     ExtractionMethod.LABEL_RULE),
    (FieldName.ISSUER, r"(?:issued\s*by|issuer|issuing\s*authority)",
     ExtractionMethod.LABEL_RULE),
    (FieldName.ROLE, r"(?:role|designation|position|job\s*title)",
     ExtractionMethod.LABEL_RULE),
    (FieldName.MENTOR_SUPERVISOR,
     r"(?:mentor|supervisor|guide|faculty\s*advisor|faculty\s*adviser)",
     ExtractionMethod.LABEL_RULE),
    (FieldName.EXPERIENCE_TYPE, r"(?:experience|programme|program)\s*type",
     ExtractionMethod.LABEL_RULE),
    (FieldName.START_DATE,
     r"(?:start\s*date|started\s*on|commencement\s*date|from\s*date|joining\s*date)",
     ExtractionMethod.DATE_RULE),
    (FieldName.END_DATE,
     r"(?:end\s*date|ended\s*on|completion\s*date|to\s*date|through)",
     ExtractionMethod.DATE_RULE),
    (FieldName.DURATION, r"(?:duration|period)",
     ExtractionMethod.LABEL_RULE),
    (FieldName.ISSUE_DATE, r"(?:issue\s*date|date\s*of\s*issue|issued\s*on)",
     ExtractionMethod.DATE_RULE),
    (FieldName.CERTIFICATE_REFERENCE_ID,
     r"(?:certificate\s*(?:number|no\.?|id)|reference\s*(?:number|no\.?|id)|document\s*id)",
     ExtractionMethod.LABEL_RULE),
    (FieldName.VERIFICATION_URL, r"(?:verification\s*url|verify\s*at|verification\s*link)",
     ExtractionMethod.LABEL_RULE),
)

_DATE_PATTERN = re.compile(
    r"(?<!\d)(?:\d{1,2}[/-]\d{1,2}[/-](?:19|20)?\d{2}|"
    r"(?:19|20)\d{2}[/-]\d{1,2}[/-]\d{1,2}|"
    r"\d{1,2}\s+[A-Za-z]{3,9}\s+(?:19|20)\d{2}|"
    r"[A-Za-z]{3,9}\s+\d{1,2},?\s+(?:19|20)\d{2})(?!\d)"
)
_URL_PATTERN = re.compile(r"https?://[^\s<>\"']{1,300}", re.IGNORECASE)


def _tokens(value: str) -> list[str]:
    normalized = unicodedata.normalize("NFKC", value).casefold()
    return re.findall(r"[\w@.-]+", normalized)


def _provenance(value: str, page: dict, request: ExtractionRequest) -> Provenance | None:
    text = page.get("text", "")
    match = re.search(re.escape(value), text, re.IGNORECASE)
    if match is None:
        return None
    start, end = match.span()
    excerpt_start = max(0, start - 100)
    excerpt_end = min(len(text), max(end + 100, excerpt_start + 1))
    excerpt = text[excerpt_start:excerpt_end].strip()[:MAX_SOURCE_EXCERPT]
    target_tokens = _tokens(value)
    source_words = page.get("words", [])
    box = None
    source_confidence = None
    source_method = page.get("method", "PDF_TEXT")
    if target_tokens and source_words:
        word_tokens = [_tokens(word.get("text", "")) for word in source_words]
        flattened = [(token, index) for index, parts in enumerate(word_tokens) for token in parts]
        target = [token for token in target_tokens if token]
        for offset in range(0, max(0, len(flattened) - len(target) + 1)):
            if [entry[0] for entry in flattened[offset:offset + len(target)]] == target:
                indexes = {entry[1] for entry in flattened[offset:offset + len(target)]}
                boxes = [source_words[index].get("bbox") for index in indexes]
                boxes = [item for item in boxes if item]
                if boxes:
                    left = min(item[0] for item in boxes)
                    top = min(item[1] for item in boxes)
                    right = max(item[2] for item in boxes)
                    bottom = max(item[3] for item in boxes)
                    box = {
                        "x": max(0.0, min(1.0, left)),
                        "y": max(0.0, min(1.0, top)),
                        "width": max(0.0, min(1.0, right - left)),
                        "height": max(0.0, min(1.0, bottom - top)),
                    }
                    confidences = [
                        source_words[index].get("confidence")
                        for index in indexes if source_words[index].get("confidence") is not None
                    ]
                    source_confidence = min(confidences) if confidences else None
                break
    return Provenance(
        sourceEvidenceId=request.evidenceId,
        evidenceVersion=request.evidenceVersion,
        pageNumber=page["page"],
        textExcerpt=excerpt or value[:MAX_SOURCE_EXCERPT],
        boundingBox=box,
        extractionMethod=source_method,
        sourceConfidence=source_confidence,
    )


def _field_result(
    name: FieldName,
    candidates: list[tuple[str, dict]],
    method: ExtractionMethod,
    request: ExtractionRequest,
    *,
    unreadable: bool,
) -> FieldExtraction:
    unique: dict[str, tuple[str, dict]] = {}
    for value, page in candidates:
        value = value.strip().rstrip(".,;")
        if value:
            unique.setdefault(value.casefold(), (value[:500], page))
    found = list(unique.values())
    if len(found) > 1:
        provenance = [
            source for value, page in found[:5]
            if (source := _provenance(value, page, request)) is not None
        ]
        if len(provenance) < 2:
            return FieldExtraction(name=name, status=FieldStatus.UNREADABLE,
                                   extractionMethod=method, provenance=[])
        return FieldExtraction(name=name, status=FieldStatus.AMBIGUOUS,
                               candidateValues=[item[0] for item in found[:5]],
                               confidence=None, extractionMethod=method,
                               provenance=provenance)
    if found:
        value, page = found[0]
        source = _provenance(value, page, request)
        if source is not None:
            return FieldExtraction(name=name, status=FieldStatus.FOUND, value=value,
                                   confidence=0.8, extractionMethod=method,
                                   provenance=[source])
        return FieldExtraction(name=name, status=FieldStatus.UNREADABLE,
                               extractionMethod=method)
    return FieldExtraction(
        name=name,
        status=FieldStatus.UNREADABLE if unreadable else FieldStatus.NOT_PRESENT,
        extractionMethod=ExtractionMethod.NOT_RUN if unreadable else method,
    )


def _labeled_values(text: str, label_pattern: str) -> list[tuple[str, int, int]]:
    pattern = re.compile(
        rf"(?im)^\s*{label_pattern}\s*[:\-]\s*(?P<value>[^\r\n]{{1,500}})",
    )
    return [
        (match.group("value").strip(), match.start("value"), match.end("value"))
        for match in pattern.finditer(text)
    ]


def _source_page(pages: list[dict], page_number: int) -> dict | None:
    return next((page for page in pages if page["page"] == page_number), None)


def _classification(text: str, pages: list[dict], request: ExtractionRequest):
    folded = unicodedata.normalize("NFKC", text).casefold()
    for classification, terms in _CLASS_RULES:
        for term in terms:
            match = re.search(re.escape(term), folded)
            if match:
                original = text[match.start():match.end()]
                page = next((item for item in pages if original.casefold() in item["text"].casefold()), None)
                source = _provenance(original, page, request) if page else None
                if source is not None:
                    return classification, 0.9, [source]
    if len(text.strip()) >= 80:
        first_page = next((page for page in pages if page.get("text")), None)
        source = _provenance(first_page["text"][:100], first_page, request) if first_page else None
        return (DocumentClassification.OTHER_SUPPORTING_EVIDENCE, 0.35,
                [source] if source else [])
    return DocumentClassification.UNKNOWN, 0.0, []


def analyze_document(
    request: ExtractionRequest,
    data: bytes,
    content_type: str,
    extract_document: Callable,
) -> ExtractionResponse:
    actual_hash = hashlib.sha256(data).hexdigest()
    if not hmac.compare_digest(actual_hash, request.sha256.lower()):
        raise ValueError("evidence hash does not match request metadata")
    text, suspicious, pages, ocr_status = extract_document(data, content_type)
    text = sanitize_sensitive_text(text)
    pages = [
        {
            **page,
            "text": sanitize_sensitive_text(page.get("text", "")),
        }
        for page in pages
    ]
    readable = any(page.get("text", "").strip() for page in pages)
    page_ocr_failed = ocr_status == "FAILED"
    partial = readable and (page_ocr_failed or any(page.get("ocrFailed") for page in pages))
    status = "UNREADABLE" if not readable else "PARTIAL" if partial else "COMPLETE"
    document_type, classification_confidence, classification_sources = _classification(
        text, pages, request)

    fields: list[FieldExtraction] = []
    for name, labels, method in _FIELD_RULES:
        candidates: list[tuple[str, dict]] = []
        for page in pages:
            for value, _, _ in _labeled_values(page["text"], labels):
                if name in {FieldName.START_DATE, FieldName.END_DATE, FieldName.ISSUE_DATE}:
                    date_match = _DATE_PATTERN.search(value)
                    if date_match:
                        value = date_match.group(0)
                elif name == FieldName.VERIFICATION_URL:
                    url_match = _URL_PATTERN.search(value)
                    if url_match:
                        value = url_match.group(0)
                candidates.append((value, page))
        fields.append(_field_result(name, candidates, method, request,
                                    unreadable=partial or not readable))

    experience_type_values = []
    for page in pages:
        for match in re.finditer(r"\b(?:internship|mentorship)\b", page["text"], re.IGNORECASE):
            experience_type_values.append((match.group(0), page))
    experience_type_field = _field_result(
        FieldName.EXPERIENCE_TYPE,
        experience_type_values,
        ExtractionMethod.LABEL_RULE,
        request,
        unreadable=partial or not readable,
    )
    for index, field in enumerate(fields):
        if field.name == FieldName.EXPERIENCE_TYPE:
            fields[index] = experience_type_field
            break
    else:
        fields.append(experience_type_field)

    statements: list[ExtractedStatement] = []
    statement_rules = (
        ("COMPLETION", re.compile(r"\b(?:completed|completion|successfully finished|awarded)\b", re.I)),
        ("EMPLOYMENT", re.compile(r"\b(?:employed|employment|worked|appointed|relieved)\b", re.I)),
        ("MENTORSHIP", re.compile(r"\b(?:mentor|mentorship|mentored|mentee|supervisor)\b", re.I)),
    )
    for page in pages:
        for line in page["text"].splitlines():
            candidate = line.strip()
            if not candidate:
                continue
            for kind, pattern in statement_rules:
                match = pattern.search(candidate)
                if match:
                    source = _provenance(match.group(0), page, request)
                    if source is not None:
                        statements.append(ExtractedStatement(
                            kind=kind, text=candidate[:500], provenance=[source]))
                    break
            if len(statements) >= 20:
                break
        if len(statements) >= 20:
            break
    for statement in statements:
        field_name = {
            "COMPLETION": FieldName.COMPLETION_STATEMENT,
            "EMPLOYMENT": FieldName.EMPLOYMENT_STATEMENT,
            "MENTORSHIP": FieldName.MENTORSHIP_STATEMENT,
        }[statement.kind]
        fields.append(FieldExtraction(
            name=field_name, status=FieldStatus.FOUND, value=statement.text,
            confidence=0.7, extractionMethod=ExtractionMethod.STATEMENT_RULE,
            provenance=statement.provenance))
    if not statements and readable:
        fields.append(_field_result(
            FieldName.SUPPORTING_TEXT,
            [(page["text"][:300], page) for page in pages if page["text"].strip()],
            ExtractionMethod.STATEMENT_RULE, request, unreadable=partial))

    warnings = []
    if suspicious:
        warnings.append("PDF_ACTIVE_OR_EMBEDDED_CONTENT_PRESENT")
    if ocr_status == "FAILED":
        warnings.append("OCR_FAILED")
    elif ocr_status == "SUCCESS":
        warnings.append("OCR_USED")
    if partial:
        warnings.append("PARTIAL_EXTRACTION")
    if not readable:
        warnings.append("DOCUMENT_UNREADABLE")

    response = ExtractionResponse(
        schemaVersion="mentorax.document-understanding.v1",
        analysisStatus=status,
        evidenceId=request.evidenceId,
        evidenceVersion=request.evidenceVersion,
        sha256=actual_hash,
        documentClassification=document_type,
        classificationConfidence=classification_confidence,
        classificationProvenance=classification_sources,
        ocrStatus=("FAILED" if not readable and ocr_status != "NOT_NEEDED"
                   else "PARTIAL" if partial else ocr_status),
        fields=fields[:MAX_FIELDS],
        extractedStatements=statements,
        warnings=warnings,
    )
    return ExtractionResponse.model_validate(
        sanitize_sensitive_content(response.model_dump(mode="json"))
    )
