"""Deterministic, local inbox assistance; no message text is logged or persisted."""
import hmac
import os
import re
import threading
from datetime import datetime

from fastapi import APIRouter, Header, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from pydantic import BaseModel, ConfigDict, Field, field_validator
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.metrics.pairwise import cosine_similarity


SCHEMA_VERSION = "mentorax-inbox-assistant-v1"
MAX_CONCURRENT_INFERENCES = 2
MIN_CATEGORY_SIMILARITY = 0.08
_slots = threading.BoundedSemaphore(MAX_CONCURRENT_INFERENCES)

_CATEGORY_TEXT = {
    "CAREER_GUIDANCE": "career guidance career path career advice job role industry growth skills career transition",
    "INTERVIEW_PREPARATION": "interview preparation mock interview behavioral questions technical interview coding interview resume screening",
    "REFERRAL_JOB_APPLICATION": "referral job application apply hiring opening job opportunity resume referral position",
    "PROJECT_TECHNICAL_ADVICE": "project technical advice debugging code architecture technology engineering design project help",
    "MENTORSHIP_REQUEST": "mentorship mentor guidance ongoing mentoring connect mentor relationship",
}
_CATEGORY_LABELS = {
    "CAREER_GUIDANCE": "Career guidance",
    "INTERVIEW_PREPARATION": "Interview preparation",
    "REFERRAL_JOB_APPLICATION": "Referral or job application",
    "PROJECT_TECHNICAL_ADVICE": "Project or technical advice",
    "MENTORSHIP_REQUEST": "Mentorship request",
    "OTHER": "Other or unclear",
}
_DEADLINE = re.compile(
    r"\b(?:by|before|due|deadline(?:\s+is)?|no\s+later\s+than)\s+"
    r"(?:today|tomorrow|tonight|next\s+(?:week|month)|\d{1,2}[/-]\d{1,2}(?:[/-]\d{2,4})?|"
    r"(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|"
    r"jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|"
    r"dec(?:ember)?)\s+\d{1,2})\b", re.IGNORECASE)
_URGENCY = re.compile(r"\b(?:urgent(?:ly)?|asap|as soon as possible|time[- ]sensitive)\b", re.I)
_SAFE_DRAFT = "Hi, thanks for reaching out. Could you share any additional context that would help me understand your request?"


class InboxAssistRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    messageId: int = Field(gt=0)
    content: str = Field(min_length=1, max_length=2000)
    timestamp: datetime | None = None

    @field_validator("content")
    @classmethod
    def content_not_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("content must not be blank")
        return value.strip()


def _classify(text: str) -> tuple[str, str, list[str]]:
    categories = list(_CATEGORY_TEXT)
    docs = [_CATEGORY_TEXT[key] for key in categories] + [text]
    try:
        matrix = TfidfVectorizer(ngram_range=(1, 2), stop_words="english").fit_transform(docs)
        scores = cosine_similarity(matrix[-1], matrix[:-1]).ravel()
    except ValueError:
        return "OTHER", "low", []
    index = max(range(len(categories)), key=lambda i: (float(scores[i]), -i))
    score = float(scores[index])
    if score < MIN_CATEGORY_SIMILARITY:
        return "OTHER", "low", []
    # Scores are lexical similarity signals, not calibrated probabilities.
    evidence = []
    for term in _CATEGORY_TEXT[categories[index]].split():
        if len(term) >= 4 and re.search(rf"\b{re.escape(term)}\w*\b", text, re.I):
            match = re.search(rf"\b{re.escape(term)}\w*\b", text, re.I)
            if match and match.group(0) not in evidence:
                evidence.append(match.group(0))
    return categories[index], ("moderate" if score >= 0.2 else "low"), evidence[:5]


def analyze(request: InboxAssistRequest) -> dict:
    text = request.content
    category, confidence, category_evidence = _classify(text)
    deadline = _DEADLINE.search(text)
    urgency = _URGENCY.search(text)
    if deadline:
        priority, urgency_kind, urgency_evidence = "urgent", "explicit_deadline", deadline.group(0)
    elif urgency:
        priority, urgency_kind, urgency_evidence = "urgent", "heuristic", urgency.group(0)
    else:
        priority, urgency_kind, urgency_evidence = "normal", "none", None
    first_sentence = re.split(r"(?<=[.!?])\s+", text, maxsplit=1)[0].strip()
    summary = first_sentence[:300]
    return {
        "schemaVersion": SCHEMA_VERSION,
        "available": True,
        "category": category,
        "categoryLabel": _CATEGORY_LABELS[category],
        "confidence": confidence,
        "uncertain": category == "OTHER" or confidence == "low",
        "categoryEvidence": category_evidence,
        "prioritySuggestion": priority,
        "urgencyKind": urgency_kind,
        "urgencyEvidence": urgency_evidence,
        "deadlineEvidence": deadline.group(0) if deadline else None,
        "summary": summary,
        "missingInformation": "Review the original message for details that may need clarification.",
        "draft": _SAFE_DRAFT,
        "draftKind": "deterministic_template",
        "modelMetadata": {
            "strategy": "TF-IDF category-description similarity plus deterministic urgency rules",
            "trainedForMentorax": False,
            "scoresAreCalibratedProbabilities": False,
            "transformer": "not_configured",
        },
    }


class InboxAssistantRoute(APIRoute):
    """Give this endpoint an accurate safe validation error without changing /rank."""
    def get_route_handler(self):
        original_handler = super().get_route_handler()

        async def handler(request: Request):
            try:
                return await original_handler(request)
            except RequestValidationError:
                return JSONResponse(status_code=422, content={"detail": "Invalid inbox assistant request"})

        return handler


router = APIRouter(route_class=InboxAssistantRoute)


@router.post("/inbox/assist")
def assist_inbox_message(
    request: InboxAssistRequest,
    authorization: str | None = Header(default=None),
):
    expected = os.getenv("INBOX_ASSISTANT_API_TOKEN", "")
    supplied = authorization.removeprefix("Bearer ") if authorization else ""
    if not expected or not hmac.compare_digest(supplied.encode("utf-8"), expected.encode("utf-8")):
        raise HTTPException(status_code=503, detail="Inbox assistant unavailable")
    if not _slots.acquire(blocking=False):
        raise HTTPException(status_code=429, detail="Inbox assistant busy")
    try:
        return analyze(request)
    finally:
        _slots.release()
