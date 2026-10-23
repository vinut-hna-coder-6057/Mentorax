# Optional Mentorax ranking service

This service runs only after Spring has classified an authenticated student question as career mentorship and `AI_MENTOR_ENABLED=true`. Spring filters approved alumni using an independent local profile-evidence gate before sending eligible professional fields to Python. Python supports TF-IDF, semantic cosine, and hybrid rank-percentile ordering, but only among those eligible candidates. Similarity and rank-fusion scores are ranking signals, not calibrated probabilities. Keep the service on a private network reachable only by Spring; do not expose it to Flutter.

Python 3.10+ is required. Windows PowerShell setup from the repository root:

```powershell
cd mentorax-ai
py -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
uvicorn app:app --host 127.0.0.1 --port 8090
```

The default remains `tfidf`; the base `requirements.txt` does not install a model runtime. To enable the optional CPU embedding path, install `python -m pip install -r requirements-semantic.txt`. Use Python 3.10–3.12 for the pinned semantic dependencies (NumPy 1.26.4 and PyTorch 2.5.1 do not support every newer Python release). The semantic extra pins Sentence Transformers 3.3.1, Transformers 4.46.3, and PyTorch 2.5.1. The model loads lazily once per Python process, on the first `semantic` or `hybrid` request. A failed initialization is memoized for that process so later requests do not repeat download attempts; after fixing model/package availability, restart the service. Missing packages, unavailable weights, or invalid model output return a safe HTTP 503; Spring then uses its existing Java profile-evidence-gated keyword fallback.

The initial model is `sentence-transformers/all-MiniLM-L6-v2`, pinned to revision `1110a243fdf4706b3f48f1d95db1a4f5529b4d41`. It has 22.7M float parameters and returns 384-dimensional vectors. Its principal `model.safetensors` file is about 90.9 MB; the Hugging Face repository contains several alternative runtime exports and is about 977 MB in total, so reserve up to that amount for a full snapshot/cache. The PyTorch runtime and Python process need additional RAM; reserve at least 2 GB for the ranker process and measure actual RSS on the deployment host before enabling it. First use needs network access to the model repository; alternatively pre-populate the Hugging Face cache and set `HF_HUB_OFFLINE=1`. Model downloads are never attempted for TF-IDF requests. The process-local LRU caches at most 512 profile vectors by a SHA-256 digest of the exact professional text; profile edits produce a new key, and cache entries are not persisted. Restart the service to release old entries or load a changed model revision.

Choose a strategy without source edits using Spring's `AI_MENTOR_RANKING_STRATEGY=tfidf|semantic|hybrid` (requires `AI_MENTOR_ENABLED=true`), or `MENTOR_RANKING_STRATEGY` for direct Python requests that omit `strategy`. `MENTOR_EMBEDDING_MODEL` and `MENTOR_EMBEDDING_MODEL_REVISION` select the local model; the revision should remain pinned. Hybrid mode converts both score lists to within-request rank percentiles before combining them, avoiding the addition of incomparable raw scores. `MENTOR_HYBRID_TFIDF_WEIGHT` sets the TF-IDF percentile weight from 0 to 1; the conservative default is 0.5. There is not yet an independent validation corpus large enough to calibrate this weight, so it is not tuned against the fixed evaluation test set and should be treated as an unvalidated default.

For a separately hosted service, bind to the platform's private interface and restrict ingress to the backend. Set `AI_MENTOR_ENABLED=true` and `AI_SERVICE_URL` on Spring. `AI_MENTOR_MIN_RELEVANCE` sets the minimum normalized profile-token overlap (0 to 1); zero still excludes profiles with no matching evidence. The app continues with transparent Java keyword-overlap ranking if the service is disabled or unavailable. No LLM, paid API, vector database, or startup inference is used.

## Intent routing and general questions

Spring locally classifies each question before ranking. Career mentorship and explicit alumni-question requests may enter discovery; greetings and general-knowledge questions never call `/rank`. General factual answering is not configured, so those requests receive a safe unavailable message and no invented answer. The response includes the intent, message/answer fields, clarification state, existing recommendation fields, and an empty `sources` list. `/ai/career-assistant` is the current route; `/ai/mentor-discovery` remains as a compatible alias.

The deterministic classifier is intentionally modest. It normalizes several common student typos and handles common phrasing, but it may ask for clarification on unfamiliar wording. TF-IDF remains the default lightweight strategy; semantic mode adds CPU and memory costs and may not improve Mentorax's synthetic evaluation. Keep it disabled unless its real evaluation results and deployment budget justify enabling it.

## Tests

From this directory, install the test client dependency and run the API and ranking tests:

```powershell
python -m pip install -r requirements-test.txt
python -m unittest discover -s tests -v
```

The suite uses FastAPI's in-process test client and controlled synthetic profiles; it does not need a running service or external database. Controlled-vector semantic tests verify cosine math, validation, and cache behavior, not real semantic quality. The fixed-dataset evaluator runs TF-IDF, semantic, and hybrid over the same profiles/questions when the model is installed; otherwise semantic/hybrid are explicitly reported `NOT_EVALUATED`. Its Python metrics are isolated ranker results, not end-to-end Java-gated recommendations. It also reports measured model initialization, request latency, and process RSS when those can be read in the environment.
