# Optional Mentorax ranking service

This service runs only when Spring receives an authenticated `POST /ai/mentor-discovery` request and `AI_MENTOR_ENABLED=true`. It performs request-scoped TF-IDF/cosine ranking over a bounded set of approved alumni fields. It stores no data and exposes no model, profile, or admin endpoints. Keep it on a private network reachable only by the Spring backend.

Python 3.10+ is required. Windows PowerShell setup from the repository root:

```powershell
cd mentorax-ai
py -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
uvicorn app:app --host 127.0.0.1 --port 8090
```

For a separately hosted service, bind to the platform's private interface and restrict ingress to the backend. Set `AI_MENTOR_ENABLED=true` and `AI_SERVICE_URL` on Spring. The app continues with transparent keyword-overlap ranking if the service is disabled or unavailable. No LLM, model download, external API, database, or startup inference is used.

## Tests

From this directory, install the test client dependency and run the API and ranking tests:

```powershell
python -m pip install -r requirements-test.txt
python -m unittest discover -s tests -v
```

The suite uses FastAPI's in-process test client and controlled synthetic profiles; it does not need a running service or external database.
