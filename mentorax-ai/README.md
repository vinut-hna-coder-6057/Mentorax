# Optional Mentorax ranking service

This service runs only after Spring has classified an authenticated student question as career mentorship and `AI_MENTOR_ENABLED=true`. Spring filters approved alumni using an independent local profile-evidence gate before sending eligible professional fields to Python. Python orders only those eligible candidates with request-scoped TF-IDF/cosine similarity. The cosine score is used for ordering, not as a calibrated probability. The service stores no data and exposes no model, profile, or admin endpoints. Keep it on a private network reachable only by the Spring backend.

Python 3.10+ is required. Windows PowerShell setup from the repository root:

```powershell
cd mentorax-ai
py -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
uvicorn app:app --host 127.0.0.1 --port 8090
```

For a separately hosted service, bind to the platform's private interface and restrict ingress to the backend. Set `AI_MENTOR_ENABLED=true` and `AI_SERVICE_URL` on Spring. `AI_MENTOR_MIN_RELEVANCE` sets the minimum normalized profile-token overlap (0 to 1); zero still excludes profiles with no matching evidence. This initial rule is deterministic and configurable, but is not scientifically calibrated. Evaluate it against representative, reviewed mentor-query/profile pairs before increasing it in production. The app continues with transparent Java keyword-overlap ranking if the service is disabled or unavailable. No LLM, embedding model, model download, external API, database, or startup inference is used.

## Intent routing and general questions

Spring locally classifies each question before ranking. Career mentorship and explicit alumni-question requests may enter discovery; greetings and general-knowledge questions never call `/rank`. General factual answering is not configured, so those requests receive a safe unavailable message and no invented answer. The response includes the intent, message/answer fields, clarification state, existing recommendation fields, and an empty `sources` list. `/ai/career-assistant` is the current route; `/ai/mentor-discovery` remains as a compatible alias.

The deterministic classifier is intentionally modest. It normalizes several common student typos and handles common phrasing, but it is not a language model and may ask for clarification on unfamiliar wording. TF-IDF also does not provide robust semantic similarity. A sentence-embedding model is not bundled because the repository has no model hosting, download, memory, or deployment plan; add one only after evaluating a specific model and deployment budget against the current baseline.

## Tests

From this directory, install the test client dependency and run the API and ranking tests:

```powershell
python -m pip install -r requirements-test.txt
python -m unittest discover -s tests -v
```

The suite uses FastAPI's in-process test client and controlled synthetic profiles; it does not need a running service or external database.
