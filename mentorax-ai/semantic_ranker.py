"""Optional, request-stateless sentence embedding ranking with bounded profile caching."""

from collections import OrderedDict
from concurrent.futures import Future
from hashlib import sha256
import os
from threading import Lock, Thread
from time import perf_counter
from typing import Callable

import numpy as np


DEFAULT_MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"
DEFAULT_MODEL_REVISION = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"
READINESS_PROBE = "Mentorax semantic ranking readiness probe"


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
        self._state_lock = Lock()
        self._cache_lock = Lock()
        self._profile_cache: OrderedDict[str, tuple[float, ...]] = OrderedDict()
        self._profile_inflight: dict[str, Future[tuple[float, ...]]] = {}
        self._cache_size = cache_size
        self._model_state = "not_loaded"
        self._initialization_future: Future[None] | None = None
        self.model_load_seconds: float | None = None
        self.model_warmup_seconds: float | None = None

    def _load_sentence_transformer(self, model_name: str):
        from sentence_transformers import SentenceTransformer

        return SentenceTransformer(model_name, revision=self.model_revision, device="cpu")

    @property
    def model_state(self) -> str:
        """Return lifecycle state without waiting for model load or inference."""
        with self._state_lock:
            return self._model_state

    def start_model_initialization(self) -> Future[None]:
        """Start one background load-and-probe operation and return its completion future."""
        with self._state_lock:
            if self._initialization_future is not None:
                return self._initialization_future

            future: Future[None] = Future()
            self._initialization_future = future
            if self._model is not None:
                self._model_state = "ready"
                future.set_result(None)
                return future
            if self._model_error is not None:
                self._model_state = "failed"
                future.set_result(None)
                return future
            self._model_state = "loading"

            def initialize():
                try:
                    self._get_model()
                except SemanticEmbeddingError:
                    # Failure state is recorded by _get_model; keep details private.
                    pass
                finally:
                    future.set_result(None)

            try:
                Thread(target=initialize, name="mentorax-model-warmup", daemon=True).start()
            except Exception:
                self._model_error = SemanticEmbeddingError(
                    "Semantic embedding model could not be loaded; restart the service after fixing model availability")
                self._model_state = "failed"
                future.set_result(None)
            return future

    def _set_model_state(self, state: str) -> None:
        with self._state_lock:
            self._model_state = state

    def _get_model(self):
        if self._model_error is not None:
            raise self._model_error
        if self._model is None:
            with self._model_lock:
                if self._model_error is not None:
                    raise self._model_error
                if self._model is None:
                    self._set_model_state("loading")
                    load_started = perf_counter()
                    try:
                        model = self._model_loader(self.model_name)
                    except Exception as error:
                        raise self._record_model_failure("Semantic embedding model could not be loaded") from error
                    self.model_load_seconds = perf_counter() - load_started

                    warmup_started = perf_counter()
                    try:
                        probe = self._encode_with_model(model, [READINESS_PROBE])[0]
                        norm = np.linalg.norm(probe)
                        if not np.isfinite(norm) or norm == 0:
                            raise SemanticEmbeddingError("Semantic readiness probe returned an invalid vector")
                    except Exception as error:
                        raise self._record_model_failure(
                            "Semantic embedding model failed readiness validation") from error
                    self.model_warmup_seconds = perf_counter() - warmup_started
                    self._model = model
                    self._set_model_state("ready")
        return self._model

    def _record_model_failure(self, message: str) -> SemanticEmbeddingError:
        self._model_error = SemanticEmbeddingError(
            f"{message}; restart the service after fixing model availability")
        self._set_model_state("failed")
        return self._model_error

    @staticmethod
    def _encode_with_model(model, texts: list[str]) -> np.ndarray:
        try:
            encoded = model.encode(
                texts, convert_to_numpy=True, normalize_embeddings=False, show_progress_bar=False)
            vectors = np.asarray(encoded, dtype=np.float64)
        except Exception as error:
            raise SemanticEmbeddingError("Semantic embedding inference failed") from error
        if vectors.ndim != 2 or vectors.shape[0] != len(texts) or vectors.shape[1] == 0:
            raise SemanticEmbeddingError("Semantic embedding model returned invalid dimensions")
        if not np.isfinite(vectors).all():
            raise SemanticEmbeddingError("Semantic embedding model returned non-finite values")
        return vectors

    def _encode(self, texts: list[str]) -> np.ndarray:
        return self._encode_with_model(self._get_model(), texts)

    def rank(self, goal: str, profile_texts: list[str]) -> list[float]:
        if not goal.strip():
            raise SemanticEmbeddingError("Question text is empty")

        profile_keys = [sha256(text.strip().encode("utf-8")).hexdigest() if text.strip() else None
                        for text in profile_texts]
        # Reserve missing profiles under the cache lock, then encode outside it.
        # A future per cache key lets concurrent requests share the same profile
        # inference without serializing unrelated requests.
        with self._cache_lock:
            cached_vectors: dict[str, tuple[float, ...]] = {}
            waiting_vectors: dict[str, Future[tuple[float, ...]]] = {}
            owned_texts: dict[str, str] = {}
            owned_futures: dict[str, Future[tuple[float, ...]]] = {}
            for text, cache_key in zip(profile_texts, profile_keys):
                if cache_key is None or cache_key in cached_vectors or cache_key in waiting_vectors or cache_key in owned_texts:
                    continue
                cached = self._profile_cache.get(cache_key)
                if cached is not None:
                    self._profile_cache.move_to_end(cache_key)
                    cached_vectors[cache_key] = cached
                elif cache_key in self._profile_inflight:
                    waiting_vectors[cache_key] = self._profile_inflight[cache_key]
                else:
                    future: Future[tuple[float, ...]] = Future()
                    self._profile_inflight[cache_key] = future
                    owned_futures[cache_key] = future
                    owned_texts[cache_key] = text.strip()

        try:
            encoded = self._encode([goal, *owned_texts.values()])
            query_vector = encoded[0]
            owned_vectors = {key: tuple(float(value) for value in encoded[index + 1])
                             for index, key in enumerate(owned_texts)}
        except Exception as error:
            with self._cache_lock:
                for key, future in owned_futures.items():
                    if self._profile_inflight.get(key) is future:
                        del self._profile_inflight[key]
                        future.set_exception(error)
            raise

        # Publish complete immutable vectors and wake same-key waiters. A
        # waiter keeps the Future result even if LRU eviction follows.
        with self._cache_lock:
            for key, vector in owned_vectors.items():
                canonical = self._profile_cache.setdefault(key, vector)
                self._profile_cache.move_to_end(key)
                owned_vectors[key] = canonical
                future = self._profile_inflight.pop(key)
                future.set_result(canonical)
            while len(self._profile_cache) > self._cache_size:
                self._profile_cache.popitem(last=False)

        profile_vectors_by_key = dict(cached_vectors)
        profile_vectors_by_key.update(owned_vectors)
        for key, future in waiting_vectors.items():
            profile_vectors_by_key[key] = future.result()

        profile_vectors = []
        for cache_key in profile_keys:
            vector = profile_vectors_by_key.get(cache_key)
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
