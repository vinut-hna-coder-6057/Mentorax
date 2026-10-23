"""Optional, request-stateless sentence embedding ranking with bounded profile caching."""

from collections import OrderedDict
from hashlib import sha256
import os
from threading import Lock
from time import perf_counter
from typing import Callable

import numpy as np


DEFAULT_MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"
DEFAULT_MODEL_REVISION = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"


class SemanticEmbeddingError(RuntimeError):
    pass


class SentenceEmbeddingRanker:
    def __init__(self, model_name: str = DEFAULT_MODEL_NAME,
                 model_revision: str = DEFAULT_MODEL_REVISION,
                 model_loader: Callable[[str], object] | None = None,
                 cache_size: int = 512):
        self.model_name = model_name
        self.model_revision = os.getenv("MENTOR_EMBEDDING_MODEL_REVISION", model_revision)
        self._model_loader = model_loader or self._load_sentence_transformer
        self._model = None
        self._model_error: SemanticEmbeddingError | None = None
        self._model_lock = Lock()
        self._cache_lock = Lock()
        self._profile_cache: OrderedDict[str, tuple[float, ...]] = OrderedDict()
        self._cache_size = cache_size
        self.model_load_seconds: float | None = None

    def _load_sentence_transformer(self, model_name: str):
        from sentence_transformers import SentenceTransformer

        return SentenceTransformer(model_name, revision=self.model_revision, device="cpu")

    def _get_model(self):
        if self._model_error is not None:
            raise self._model_error
        if self._model is None:
            with self._model_lock:
                if self._model_error is not None:
                    raise self._model_error
                if self._model is None:
                    started = perf_counter()
                    try:
                        self._model = self._model_loader(self.model_name)
                    except Exception as error:
                        self._model_error = SemanticEmbeddingError(
                            "Semantic embedding model could not be loaded; restart the service after fixing model availability")
                        raise self._model_error from error
                    self.model_load_seconds = perf_counter() - started
        return self._model

    def _encode(self, texts: list[str]) -> np.ndarray:
        try:
            encoded = self._get_model().encode(
                texts, convert_to_numpy=True, normalize_embeddings=False, show_progress_bar=False)
            vectors = np.asarray(encoded, dtype=np.float64)
        except SemanticEmbeddingError:
            raise
        except Exception as error:
            raise SemanticEmbeddingError("Semantic embedding inference failed") from error
        if vectors.ndim != 2 or vectors.shape[0] != len(texts) or vectors.shape[1] == 0:
            raise SemanticEmbeddingError("Semantic embedding model returned invalid dimensions")
        if not np.isfinite(vectors).all():
            raise SemanticEmbeddingError("Semantic embedding model returned non-finite values")
        return vectors

    def rank(self, goal: str, profile_texts: list[str]) -> list[float]:
        if not goal.strip():
            raise SemanticEmbeddingError("Question text is empty")

        with self._cache_lock:
            cached_vectors: list[tuple[float, ...] | None] = []
            profile_keys = [sha256(text.strip().encode("utf-8")).hexdigest() if text.strip() else None
                            for text in profile_texts]
            missing_texts: dict[str, str] = {}
            for text, cache_key in zip(profile_texts, profile_keys):
                cached = self._profile_cache.get(cache_key) if cache_key else None
                if cached is not None:
                    self._profile_cache.move_to_end(cache_key)
                    cached_vectors.append(cached)
                elif not cache_key:
                    cached_vectors.append(None)
                else:
                    cached_vectors.append(None)
                    missing_texts.setdefault(cache_key, text.strip())

            encoded = self._encode([goal, *missing_texts.values()])
            query_vector = encoded[0]
            missing_vectors = {key: encoded[index + 1] for index, key in enumerate(missing_texts)}
            for key, vector in missing_vectors.items():
                self._profile_cache[key] = tuple(float(value) for value in vector)
                self._profile_cache.move_to_end(key)
            while len(self._profile_cache) > self._cache_size:
                self._profile_cache.popitem(last=False)

            profile_vectors = []
            for cache_key, cached in zip(profile_keys, cached_vectors):
                vector = cached if cached is not None else missing_vectors.get(cache_key)
                profile_vectors.append(None if vector is None else np.asarray(vector, dtype=np.float64))

        scores = []
        query_norm = np.linalg.norm(query_vector)
        if not np.isfinite(query_norm) or query_norm == 0:
            raise SemanticEmbeddingError("Semantic embedding model returned an invalid question vector")
        for vector in profile_vectors:
            if vector is None:
                scores.append(0.0)
                continue
            if vector.shape != query_vector.shape:
                raise SemanticEmbeddingError("Semantic embedding dimensions do not match")
            norm = np.linalg.norm(vector)
            if not np.isfinite(norm) or norm == 0:
                raise SemanticEmbeddingError("Semantic embedding model returned an invalid profile vector")
            cosine = float(np.dot(query_vector, vector) / (query_norm * norm))
            if not np.isfinite(cosine):
                raise SemanticEmbeddingError("Semantic cosine similarity is not finite")
            scores.append(max(0.0, min(1.0, cosine)))
        return scores

    @property
    def cache_entries(self) -> int:
        with self._cache_lock:
            return len(self._profile_cache)
