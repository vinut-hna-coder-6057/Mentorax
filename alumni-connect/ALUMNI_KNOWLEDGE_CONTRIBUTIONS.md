# Alumni Knowledge Companion

Spring Boot owns contribution eligibility, retrieval, answer composition, conversations, privacy-safe question aggregation, freshness reminders, and preparation roadmaps. Flutter calls Spring Boot only. The existing Career Assistant and Mentor Discovery continue to operate on their original endpoints and policies.

## Authorization and lifecycle

All routes require the existing JWT authentication. Owner routes also require the `ALUMNI` role and the service rechecks current `ALUMNI` role plus `APPROVED` account status. The owner comes from the authenticated principal; request bodies have no owner field. Student listing requires `STUDENT` role and approved status. A non-owner receives 404 for an individual contribution, avoiding private-ID disclosure. Responses are DTOs and never include user profile fields.

New contributions start in `DRAFT`, version 1, with both consent scopes false. Publishing requires explicit `studentVisibilityConsent: true` and `aiRetrievalConsent: true`. Consent can later be changed independently using PATCH; every change updates the consent timestamp and appends an audit event. Withdrawal switches the aggregate to `WITHDRAWN`, which immediately removes it from student and AI eligibility queries while preserving version and audit history.

An edit creates a new immutable version with both consent scopes false. If a previous version was published, the old published version remains visible until the owner publishes the new version. Owner responses contain both `version` (the current editable snapshot) and `publishedVersion` (nullable pointer to the snapshot currently shown to students). Consent revocation and withdrawal override that historical visibility immediately. The student's API represents the published version number and only returns records whose owner remains an approved alumnus and whose published version has student visibility consent.

## Endpoints

| Method and path | Purpose |
| --- | --- |
| `POST /api/alumni/knowledge` | Create draft from category, title, narrative, optional company/role/year and typed entries. Returns 201. |
| `GET /api/alumni/knowledge/mine?page=0&size=20` | List own current versions; size is capped at 50. |
| `GET /api/alumni/knowledge/{id}` | Read own current version. |
| `GET /api/alumni/knowledge/{id}/versions` | Read own immutable version history, newest first. |
| `PUT /api/alumni/knowledge/{id}` | Create a new draft version. |
| `POST /api/alumni/knowledge/{id}/publish` | Publish current version with both explicit consent booleans. |
| `POST /api/alumni/knowledge/{id}/withdraw` | Withdraw and immediately suppress student/AI eligibility. |
| `PATCH /api/alumni/knowledge/{id}/consent` | Set `studentVisibilityConsent` and `aiRetrievalConsent` independently. |
| `GET /api/knowledge/contributions?page=0&size=20&category=PROJECT_EXPERIENCE` | Student-visible, published contributions with optional category filter; size capped at 50. |

Categories: `INTERVIEW_EXPERIENCE`, `PREPARATION_STRATEGY`, `PROJECT_EXPERIENCE`, `CAREER_JOURNEY`, `PROFESSIONAL_ADVICE`, `MENTORSHIP_GUIDANCE`. Mentorship guidance represents alumni's mentoring experience and practical guidance; it does not add appointment booking or private messaging.

The Flutter alumni form guides contributions by category and collects title, optional company/organization, role or career goal, experience year, narrative, and typed structured answers. Its prompts cover interview rounds/topics/questions and preparation; study strategy, skills, resources, and lessons; project problem, approach, challenges, and resolution; role transitions and learned skills; professional recommendations; and mentorship context and guidance. Structured answers use the existing `TECHNOLOGY`, `INTERVIEW_ROUND`, `INTERVIEW_TOPIC`, `INTERVIEW_QUESTION`, `RESOURCE`, `CHALLENGE`, `LESSON`, and `ROLE_TRANSITION` entry types. Editing starts from the complete current snapshot and saves a new version with the existing entries and metadata unless the alumnus changes them.

New drafts are private. Consent is selected for the contribution version in the publication confirmation; saving a draft does not publish it. Student visibility and AI retrieval are independent scopes. Grants affect only the current version. Revoking a scope also immediately suppresses it on the active published version. A grant on a newer draft can never reactivate a previously revoked scope on an older version; publishing the new version is a separate explicit action.

Entry types: `TECHNOLOGY`, `INTERVIEW_ROUND`, `INTERVIEW_TOPIC`, `INTERVIEW_QUESTION`, `RESOURCE`, `CHALLENGE`, `LESSON`, `ROLE_TRANSITION`. Entries have bounded text, optional hierarchy positions, and optional HTTPS resource URL. Per-type list limits are enforced (20 technologies, 8 rounds, 40 topics, 40 questions, 10 resources/challenges/lessons/transitions; 300 total maximum).

Example request:

```json
{
  "category": "PROJECT_EXPERIENCE",
  "title": "Building a data ingestion service",
  "narrative": "Describe the experience and practical lessons.",
  "companyName": "Optional",
  "jobRole": "Optional",
  "experienceYear": 2024,
  "entries": [
    {"type": "TECHNOLOGY", "position": 0, "value": "Java"},
    {"type": "CHALLENGE", "position": 0, "value": "Handling retries", "detail": "Used bounded backoff."}
  ]
}
```

## Retrieval and answers

`POST /api/knowledge/companion/answer` accepts a bounded student question and returns a deterministic relevance-ranked answer with exact contribution/version references. Retrieval reads only published active versions with both student-visibility and AI-retrieval consent, and an approved ALUMNI owner. Results are bounded, normalized, lexically scored, excerpt-filtered for common personal/contact data and prompt-injection instructions, and rechecked against current eligibility immediately before returning. No vector index or cache is used, so there is no stale index to authorize around.

`app.knowledge.answer-provider` defaults to `deterministic`, which labels alumni submissions as reports, cites their source versions, and clearly reports insufficient evidence. The optional `ollama` provider is loopback-only (`127.0.0.1`, default model name `gemma3:1b`); production does not automatically have a local Ollama service or model. Provider errors and unavailable selections fall back to deterministic output with an explicit answer mode. The real local-model test is opt-in with `-Dmentorax.test.ollama=true`. Mocked adapter tests check request handling and citation syntax, but citation presence does not prove every generated statement is entailed by its source. No remote LLM provider is configured. The conflict detector is a conservative heuristic for a small set of same-company/role opposite terms, not a general contradiction detector.

## Conversations and human escalation

| Method and path | Purpose |
| --- | --- |
| `POST /api/knowledge/companion/conversations` | Create a private student conversation. |
| `GET /api/knowledge/companion/conversations?page=0&size=20` | List only the current student's conversations. |
| `GET /api/knowledge/companion/conversations/{id}/messages?page=0&size=50` | Read bounded history; source text/metadata is suppressed if its version is no longer eligible. |
| `POST /api/knowledge/companion/conversations/{id}/messages` | Ask a bounded follow-up; stores source references rather than copied contribution bodies. |
| `DELETE /api/knowledge/companion/conversations/{id}` | Delete the owner's conversation. |

Insufficient evidence and detected conflicts offer recommendations from the existing Mentor Discovery service. The student must navigate to a mentor and separately initiate any connection request. Conversation questions are never sent to recommended alumni.

## Unanswered topics, freshness, and roadmaps

| Method and path | Purpose |
| --- | --- |
| `GET /api/alumni/knowledge/opportunities` | Show aggregated unanswered topics only after at least five distinct students. |
| `PATCH /api/alumni/knowledge/{id}/freshness` | Set 90-day, 180-day, or opted-out reminder interval. |
| `POST /api/alumni/knowledge/{id}/freshness/confirm` | Confirm the published content is still current. |
| `POST /api/knowledge/roadmaps` | Create a student-owned preparation roadmap from eligible knowledge. |
| `GET /api/knowledge/roadmaps` | List only the authenticated student's roadmaps. |
| `PUT /api/knowledge/roadmaps/{id}/items/{itemId}` | Edit a roadmap step or progress status. |
| `DELETE /api/knowledge/roadmaps/{id}` | Delete the owner's roadmap. |

Topic aggregation stores a normalized topic and hash with a unique student/topic row, not the raw question or student identity in the alumni-facing response. Repeated questions by one student count once. Freshness reminders use the existing in-app notification table; the scheduler is hourly by default and claims each due reminder with a conditional update to avoid duplicates. Roadmaps preserve contribution/version references and suppress source titles when eligibility later changes. General suggestions are labeled separately from alumni-supported steps.

## Storage and migration boundary

Flyway V24 adds the contribution and audit tables described above. V25 adds freshness controls and private conversation, source-reference, question-interest, and roadmap tables. Both are additive shared migrations. Routed migration tests exercise fresh, normalized, and legacy history on H2. Real-MySQL migration tests require the dedicated disposable schemas documented in `src/main/resources/DATABASE_MIGRATION.md` and were not run in this local environment.

Conversation rows belong to their authenticated student; source association rows contain contribution/version references instead of copied content. Roadmap rows also store references. Deleting conversations and roadmaps is explicit. Existing notification infrastructure is reused; email delivery is not part of this feature.

## Flutter

The app provides `/student/knowledge-companion`, `/student/knowledge-roadmaps`, and `/alumni/knowledge-contributions`. These screens cover conversation history and citations, explicit human escalation, editable/progress-tracked roadmaps, contribution creation/edit/publication/withdrawal/consent, freshness settings, and thresholded opportunity summaries. The existing Career Assistant and mentor routes remain available.

## Local validation

Run backend tests from `alumni-connect` with `./mvnw test` (Windows: `mvnw.cmd test`), Python tests from `mentorax-ai` with `python -m unittest discover -s tests -v`, and Flutter checks from `alumni_connect_mobile` with `flutter analyze` and `flutter test`. MySQL migration checks need separately provisioned disposable schemas and credentials. The repository does not configure a real grounded-answer LLM provider.
