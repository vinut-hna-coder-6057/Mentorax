# Feature Two: Alumni inbox assistant

## Local setup

The assistant reuses the existing `messages` table and existing Spring authentication. Run the AI service using the opt-in entry point:

```powershell
$env:INBOX_ASSISTANT_API_TOKEN = '<same high-entropy secret configured in Spring>'
python -m uvicorn inbox_app:app --host 127.0.0.1 --port 8090
```

For Spring, set `AI_INBOX_ASSISTANT_ENABLED=true`, `INBOX_ASSISTANT_API_TOKEN` to the same secret, and `AI_SERVICE_URL` to the Python service URL. The default is disabled. Spring returns a safe unavailable response if disabled, missing a token, or Python is unavailable. Keep the Python process on a private interface reachable only by Spring; do not expose its internal route publicly.

The existing `uvicorn app:app` entry point and all Feature One endpoints remain unchanged. Deploy the inbox assistant with `uvicorn inbox_app:app`; it imports the existing FastAPI app and adds only `/inbox/assist`.

## Data and behavior

Spring accepts `POST /messages/{messageId}/assistant` for authenticated alumni only. It loads the stored message and checks the authenticated alumni is its recipient before sending the minimum fields `messageId`, message `content` (up to 2,000 characters), and `timestamp` to Python. The Python endpoint requires a bearer token, rejects extra schema fields, and bounds concurrent inference to two requests. Spring validates category identifiers, response metadata, summary/source containment, and evidence/source containment before exposing the response. No assistant output is persisted.

Categories use a TF-IDF similarity baseline against fixed category descriptions. The qualitative confidence tier is not a calibrated probability. No transformer is configured and no model was trained on Mentorax data. Priority is a suggestion; exact urgency phrases and explicit deadline evidence are returned, and no inbox state is changed automatically. Summary output is an extractive first sentence. The reply is a deterministic, generic editable template—not model-generated—and is never sent automatically. The UI offers review, edit, and copy.

Message processing is request-scoped and not retained by Python. The underlying message remains subject to existing database retention behavior; no new retention policy or audit store is introduced. Existing Java HTTP connect/read bounds apply. No database migration or additional dependency is required.

Quality metrics are pending genuine reviewed inbox labels. Synthetic unit test fixtures are not production evidence. See `evaluation/inbox-assistant/README.md` for the independent label schema and evaluation protocol. A local pretrained transformer comparison is deferred: no suitable model is already configured or bundled, and this task does not download model files or install packages.
