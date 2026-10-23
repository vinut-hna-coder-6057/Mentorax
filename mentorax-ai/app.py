"""Optional local ranker. Keep this service on a private network."""
import logging
import os
from typing import List

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator
from semantic_ranker import SemanticEmbeddingError, SentenceEmbeddingRanker
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.metrics.pairwise import cosine_similarity

MAX_REQUEST_BYTES = 8 * 1024 * 1024

app = FastAPI(title="Mentorax local ranking service", docs_url=None, redoc_url=None)
log = logging.getLogger(__name__)
semantic_ranker = SentenceEmbeddingRanker(
    model_name=os.getenv("MENTOR_EMBEDDING_MODEL", "sentence-transformers/all-MiniLM-L6-v2"))


class Candidate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: int
    name: str = Field(default="", max_length=255)
    jobTitle: str | None = Field(default=None, max_length=255)
    company: str | None = Field(default=None, max_length=255)
    bio: str | None = Field(default=None, max_length=1000)
    skills: List[str] = Field(default_factory=list, max_length=50)
    interests: str | None = Field(default=None, max_length=500)

    @field_validator("skills")
    @classmethod
    def skills_are_bounded(cls, skills: List[str]) -> List[str]:
        if any(len(skill) > 255 for skill in skills):
            raise ValueError("skill entries must be at most 255 characters")
        return skills


class RankingRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    goal: str = Field(min_length=1, max_length=500)
    candidates: List[Candidate] = Field(min_length=1, max_length=500)
    strategy: str | None = Field(default=None, pattern="^(tfidf|semantic|hybrid)$")

    @field_validator("goal")
    @classmethod
    def goal_is_not_blank(cls, goal: str) -> str:
        if not goal.strip():
            raise ValueError("goal must contain non-whitespace characters")
        return goal.strip()

    @field_validator("candidates")
    @classmethod
    def candidate_ids_are_unique(cls, candidates: List[Candidate]) -> List[Candidate]:
        ids = [candidate.id for candidate in candidates]
        if len(ids) != len(set(ids)):
            raise ValueError("candidate IDs must be unique")
        return candidates


@app.middleware("http")
async def reject_oversized_request(request: Request, call_next):
    content_length = request.headers.get("content-length")
    if content_length is not None:
        try:
            if int(content_length) > MAX_REQUEST_BYTES:
                return JSONResponse(status_code=413, content={"detail": "Request body too large"})
        except ValueError:
            return JSONResponse(status_code=400, content={"detail": "Invalid Content-Length"})
    return await call_next(request)


@app.exception_handler(RequestValidationError)
async def safe_validation_error(_request: Request, _exception: RequestValidationError):
    # Do not echo profile text or oversized input values in validation responses.
    return JSONResponse(status_code=422, content={"detail": "Invalid ranking request"})


@app.get("/health")
def health():
    return {"status": "ok"}


def profile_text(candidate: Candidate) -> str:
    return " ".join(filter(None, [
        " ".join(candidate.skills), candidate.jobTitle, candidate.company,
        candidate.bio, candidate.interests,
    ])).strip()


def tfidf_scores(request: RankingRequest) -> list[float]:
    documents = [request.goal] + [profile_text(candidate) for candidate in request.candidates]
    try:
        matrix = TfidfVectorizer(ngram_range=(1, 2), stop_words="english").fit_transform(documents)
    except ValueError:
        raise HTTPException(status_code=503, detail="TF-IDF vectorization failed") from None
    return [float(score) for score in cosine_similarity(matrix[0:1], matrix[1:]).flatten()]


def percentile_scores(scores: list[float]) -> list[float]:
    if len(scores) == 1:
        return [1.0]
    ordered = sorted(range(len(scores)), key=lambda index: (-scores[index], index))
    result = [0.0] * len(scores)
    position = 0
    while position < len(ordered):
        end = position + 1
        while end < len(ordered) and scores[ordered[end]] == scores[ordered[position]]:
            end += 1
        percentile = 1.0 - ((position + end - 1) / 2) / (len(scores) - 1)
        for ranked_position in range(position, end):
            result[ordered[ranked_position]] = percentile
        position = end
    return result


@app.post("/rank")
def rank(request: RankingRequest, strategy: str | None = None):
    """Rank one request; profile embeddings are bounded, process-local cache entries."""
    selected_strategy = strategy or request.strategy or os.getenv("MENTOR_RANKING_STRATEGY", "tfidf").lower()
    if selected_strategy not in {"tfidf", "semantic", "hybrid"}:
        raise HTTPException(status_code=422, detail="Invalid ranking strategy")

    try:
        if selected_strategy == "tfidf":
            scores = tfidf_scores(request)
            method = "TF-IDF cosine similarity"
        elif selected_strategy == "semantic":
            scores = semantic_ranker.rank(request.goal, [profile_text(candidate) for candidate in request.candidates])
            method = "Sentence embedding cosine similarity"
        else:
            lexical = tfidf_scores(request)
            semantic = semantic_ranker.rank(request.goal, [profile_text(candidate) for candidate in request.candidates])
            try:
                lexical_weight = float(os.getenv("MENTOR_HYBRID_TFIDF_WEIGHT", "0.5"))
            except ValueError:
                raise HTTPException(status_code=503, detail="Hybrid ranking configuration is invalid") from None
            if not 0.0 <= lexical_weight <= 1.0:
                raise HTTPException(status_code=503, detail="Hybrid ranking configuration is invalid")
            lexical_percentiles = percentile_scores(lexical)
            semantic_percentiles = percentile_scores(semantic)
            scores = [lexical_weight * left + (1.0 - lexical_weight) * right
                      for left, right in zip(lexical_percentiles, semantic_percentiles)]
            method = "Hybrid rank-percentile fusion"
    except SemanticEmbeddingError as error:
        log.warning("Semantic ranking unavailable (%s)", type(error).__name__)
        raise HTTPException(status_code=503, detail="Semantic ranking unavailable") from None

    ranked = sorted(zip(request.candidates, scores), key=lambda pair: (-float(pair[1]), pair[0].id))
    return {
        "rankedIds": [candidate.id for candidate, _ in ranked],
        "scores": {str(candidate.id): float(score) for candidate, score in ranked},
        "rankingMethod": method,
    }
