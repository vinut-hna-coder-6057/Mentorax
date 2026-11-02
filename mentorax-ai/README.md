# Optional Mentorax ranking service

This service runs only after Spring has classified an authenticated student question as career mentorship and `AI_MENTOR_ENABLED=true`. Spring reads approved alumni in stable keyset batches of at most 500, backed by the `(role, status, id)` MySQL index. For semantic and hybrid strategies, it sends each approved page's professional fields to Python before applying the Java evidence and abstention gate; TF-IDF remains gated before ranking. Names and contact details stay in Spring. Java validates each returned ranked ID and score against the submitted candidate universe. Semantic cosine scores are comparable across pages and can be merged globally. TF-IDF cosine and hybrid rank percentiles are page-relative; when a search spans multiple pages, Java uses its global evidence ordering and marks the result as fallback because those batch scores cannot be compared safely. Similarity and rank-fusion scores are not calibrated probabilities. Keep the service on a private network reachable only by Spring; do not expose it to Flutter.

Set `MENTOR_RANKING_API_TOKEN` on this service and the same value as
`AI_MENTOR_API_TOKEN` in Spring to authenticate `/rank` and `/rank/batch`.
When the Python token is configured, requests without the matching bearer token
are rejected. Keep the token in environment or secret-manager configuration.

## Experience evidence analysis

The same private FastAPI service also extracts experience evidence. PDF text is extracted first; pages with insufficient text and supported JPG/JPEG/PNG uploads are processed by local RapidOCR. OCR does not require an external credential. The backend evaluates all versions attached to a claim together, compares supported employee/employer/role/date/employment details, and reports identical normalized extracted text with its document versions. The backend applies its evidence policy and persists the decision; neither the Python service nor an LLM confirms employment with an employer.

Use Python 3.10–3.12 for the pinned project dependencies. From the repository root in Windows PowerShell:

```powershell
cd mentorax-ai
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
$env:EXPERIENCE_ANALYSIS_API_TOKEN = "<same-long-random-secret-configured-on-Spring>"
uvicorn app:app --host 127.0.0.1 --port 8090
```

Configure the backend with the same `EXPERIENCE_ANALYSIS_API_TOKEN` and `app.ai.service-url=http://127.0.0.1:8090`. Configure a separately generated `EXPERIENCE_EVIDENCE_ENCRYPTION_KEY` for Spring evidence storage. Keep both secrets out of source control and use a private network for the Python service.

Generate the encryption key once, store it in your secret manager, and reuse it across backend restarts so previously uploaded evidence remains decryptable:

```powershell
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
[Convert]::ToBase64String($bytes)
```

In a second PowerShell window, set the same internal token and the persistent encryption key before starting Spring:

```powershell
cd alumni-connect
$env:EXPERIENCE_ANALYSIS_API_TOKEN = "<same-long-random-secret>"
$env:EXPERIENCE_EVIDENCE_ENCRYPTION_KEY = "<stored-base64-key>"
.\mvnw.cmd spring-boot:run
```

Without an LLM provider, analysis is explicitly reported as `HEURISTIC_FALLBACK` or `OCR_PLUS_HEURISTIC`; no employer confirmation is inferred. To enable an OpenAI-compatible provider, set `EXPERIENCE_ANALYSIS_LLM_PROVIDER=openai-compatible`, `EXPERIENCE_ANALYSIS_LLM_MODEL=<model>`, `EXPERIENCE_ANALYSIS_LLM_BASE_URL=<private-or-provider-v1-url>`, and `EXPERIENCE_ANALYSIS_LLM_API_KEY=<secret>`. The provider may be `openai`, `openrouter`, or `ollama` as well; Ollama defaults to `http://127.0.0.1:11434/v1` and does not require an API key. Provider requests use a 45-second per-attempt timeout and retry once. Invalid responses and provider errors fail closed and never trigger a verification decision. Spring's evidence-analysis HTTP read timeout is longer than both provider attempts.

After both services are running, upload or add evidence to a claim. The enabled backend scheduler processes queued claims automatically, persists analysis status/method and findings, then notifies the claimant. Use **Retry analysis** when a terminal processing failure is shown. To run focused verification tests:

```powershell
python -m unittest discover -s tests -p "test_experience_analysis.py" -v
cd ..\alumni-connect
.\mvnw.cmd "-Dtest=ExperienceClaimControllerTest,ExperienceVerificationDecisionEngineTest,ExperienceClaimProcessingServiceTest,ExperienceClaimProcessingSchedulerTest" test
```

The automated provider request/timeout tests use mocks. A real LLM request requires valid provider configuration and credentials; the provider is not called by tests unless explicitly configured.

## Optional alumni ranking service

Python 3.10+ is required. Windows PowerShell setup from the repository root:

```powershell
cd mentorax-ai
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
uvicorn app:app --host 127.0.0.1 --port 8090
```

The default remains `tfidf`; the base `requirements.txt` does not install a model runtime. To enable the optional CPU embedding path, install `python -m pip install -r requirements-semantic.txt`. Use Python 3.10–3.12 for the pinned semantic dependencies (NumPy 1.26.4 and PyTorch 2.5.1 do not support every newer Python release). The semantic extra pins Sentence Transformers 3.3.1, Transformers 4.46.3, and PyTorch 2.5.1. When Python is configured with `MENTOR_RANKING_STRATEGY=semantic` or `hybrid`, initialization starts on a background thread during application startup. Otherwise, the first semantic request or `GET /ready?strategy=semantic` starts the same single-flight warmup. `/health` reports process liveness; `/ready` reports readiness for Python's configured strategy, and accepts `?strategy=semantic` or `?strategy=hybrid` to check and start readiness for that strategy. Semantic ranking returns safe HTTP 503 while the model is loading or unavailable, so Spring uses its existing Java profile-evidence-gated fallback instead of waiting for model loading inside its five-second timeout. Readiness becomes true only after model loading and a finite, nonzero embedding probe. Initialization failures are memoized for that process; after fixing model/package availability, restart the service.

The initial model is `sentence-transformers/all-MiniLM-L6-v2`, pinned to revision `1110a243fdf4706b3f48f1d95db1a4f5529b4d41`. It has 22.7M float parameters and returns 384-dimensional vectors. Its principal `model.safetensors` file is about 90.9 MB; the Hugging Face repository contains several alternative runtime exports and is about 977 MB in total, so reserve up to that amount for a full snapshot/cache. The PyTorch runtime and Python process need additional RAM; reserve at least 2 GB for the ranker process and measure actual RSS on the deployment host before enabling it. First use needs network access to the model repository; alternatively pre-populate the Hugging Face cache and set `HF_HUB_OFFLINE=1`. Model downloads are never attempted for TF-IDF requests. The process-local LRU caches at most 512 profile vectors by a SHA-256 digest of exact professional text. Same-key concurrent requests share one in-flight embedding; unrelated profile encodes happen outside the cache lock. Profile edits produce a new key, and cache entries are not persisted. Restart the service to release old entries or load a changed model revision. `/rank/batch` accepts up to five independent ranking pages in one HTTP request, preserves each page's existing ranking universe and score semantics, and isolates individual page failures; `/rank` remains supported. Spring groups at most two keyset pages and keeps each serialized request under 7 MiB, below the service's 8 MiB body limit.

Choose a strategy without source edits using Spring's `AI_MENTOR_RANKING_STRATEGY=tfidf|semantic|hybrid` (requires `AI_MENTOR_ENABLED=true`), or `MENTOR_RANKING_STRATEGY` for direct Python requests that omit `strategy`. `MENTOR_EMBEDDING_MODEL` and `MENTOR_EMBEDDING_MODEL_REVISION` select the local model; the revision should remain pinned. Hybrid mode converts both score lists to within-request rank percentiles before combining them, avoiding the addition of incomparable raw scores. `MENTOR_HYBRID_TFIDF_WEIGHT` sets the TF-IDF percentile weight from 0 to 1; the conservative default is 0.5. There is not yet an independent validation corpus large enough to calibrate this weight, so it is not tuned against the fixed evaluation test set and should be treated as an unvalidated default.

For a separately hosted service, bind to the platform's private interface and restrict ingress to the backend. Set `AI_MENTOR_ENABLED=true` and `AI_SERVICE_URL` on Spring. Set Python's `MENTOR_RANKING_STRATEGY` to the same semantic/hybrid strategy used by Spring to begin background warmup at service startup. If only Spring is configured for semantic ranking, the first request starts warmup and receives a safe 503 until `/ready?strategy=semantic` reports ready. `AI_MENTOR_MIN_RELEVANCE` sets the minimum normalized profile-token overlap (0 to 1); zero still excludes profiles with no matching evidence. Semantic/hybrid mode also requires a raw cosine score at or above `AI_MENTOR_MIN_SEMANTIC_RELEVANCE` (default 0.72) and substantive role, skill, interest, or bio fields. This is a conservative uncalibrated gate, not a probability; calibrate it only on separately labeled validation data. The app continues with transparent Java keyword-overlap ranking if the service is disabled or unavailable. No LLM, paid API, or vector database is used.

## Intent routing and general questions

Spring locally classifies each question before ranking. Career mentorship and explicit alumni-question requests may enter discovery; greetings and general-knowledge questions never call `/rank`. General factual answering is not configured, so those requests receive a safe unavailable message and no invented answer. The response includes the intent, message/answer fields, clarification state, existing recommendation fields, and an empty `sources` list. `/ai/career-assistant` is the current route; `/ai/mentor-discovery` remains as a compatible alias.

The deterministic classifier is intentionally modest. It normalizes several common student typos and handles career switches, placement preparation, resume review, and common role wording, but it may ask for clarification on unfamiliar questions. There is no existing Ollama runtime or independently validated local LLM in this repository, so no LLM dependency is added. TF-IDF remains the default lightweight strategy; semantic mode adds CPU and memory costs and may not improve Mentorax's synthetic evaluation. Keep it disabled unless its real evaluation results and deployment budget justify enabling it. Java's evidence gate still controls whether a retrieved profile can be recommended; semantic retrieval does not create profile evidence.

## Tests

From this directory, install the test client dependency and run the API and ranking tests:

```powershell
python -m pip install -r requirements-test.txt
python -m unittest discover -s tests -v
```

The suite uses FastAPI's in-process test client and controlled synthetic profiles; it does not need a running service or external database. Controlled-vector semantic tests verify cosine math, validation, and cache behavior, not real semantic quality. The fixed-dataset evaluator runs TF-IDF, semantic, and hybrid over the same profiles/questions when the model is installed; otherwise semantic/hybrid are explicitly reported `NOT_EVALUATED`. Its Python metrics are isolated ranker results, not end-to-end Java-gated recommendations. It also reports measured model initialization, request latency, and process RSS when those can be read in the environment.
