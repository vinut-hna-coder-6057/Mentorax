# Knowledge Companion Milestones 1–8

## Initial state and execution boundary

- Initial branch: `phase-2-6-full-review`.
- Initial HEAD: `f06efe97d97de68a65661293c1b08ecdb51e7409`. The worktree was clean. `origin/main` was fetched and pointed to merge commit `183c2f0a6357a968659f73eaa4f6e5907192c685`, which contains this implementation.
- The existing `mentorax-ai/evaluation/dataset.json` provenance/history was already committed in the starting tree and was left byte-for-byte unchanged.
- No commit, push, merge, or deployment was performed.
- No ZIP files were accessed.
- Spring is the sole authority for authentication, eligibility, consent, retrieval, persistence, and final response policy. Flutter uses Spring APIs only. No vector store was added because the current corpus is served by bounded lexical retrieval and an eligibility recheck.

## Milestone 1 — Alumni contribution experience

Flutter provides a guided contribution editor for all six categories, including `MENTORSHIP_GUIDANCE`, using the existing contribution request and typed entry schema. It collects company, role/career goal, experience year, narrative, structured entries, and optional HTTPS resource links. Editing starts from the complete current version and saves an immutable new draft. Consent is version-scoped and publication requires an explicit confirmation with independent student-visibility and AI-retrieval scopes. Mentorship guidance describes alumni's mentoring experiences; it does not add booking or private messaging.

## Milestone 2 — Trusted retrieval: COMPLETE

Added a Spring lexical retrieval service and authenticated route. Queries are normalized, candidates/results are bounded, ranking is deterministic and reports matched fields, and results cite contribution/version IDs. Only current published versions with both consent scopes and an approved alumni owner are eligible. Eligibility is rechecked before output; no stale cache/index exists. Excerpts and titles redact common private contact/secret patterns and common prompt-injection directives. The integration test covers eligible retrieval, draft exclusion, consent revocation, withdrawal, owner eligibility, empty results, result references, and private access.

Primary files: `KnowledgeRetrievalService`, `KnowledgeRetrievalController`, `KnowledgeRetrievalResponse`, and `KnowledgeContributionService`.

## Milestone 3 — Grounded answers: COMPLETE WITH PROVIDER LIMITATION

Added `GroundedAnswerProvider`, a deterministic implementation, and an optional Ollama adapter restricted to loopback hosts. The deterministic provider is the default. The configured Ollama model name defaults to `gemma3:1b`, but a model is not automatically available in production. Answers cite current evidence and identify alumni-reported content, insufficient evidence, and the implemented same-company/role conflict heuristic. Prompt-injection text is filtered from excerpts. Mocked Ollama tests validate request handling and citation syntax only; citation presence does not establish that every generated statement is entailed by the cited evidence. The real local-model test is opt-in (`-Dmentorax.test.ollama=true`) and was not run in this validation record. No remote LLM provider is configured.

Primary files: `GroundedKnowledgeAnswerService`, `GroundedAnswerProvider`, `DeterministicGroundedAnswerProvider`, answer request/response DTOs, and the companion answer controller route.

## Milestone 4 — Private conversations: COMPLETE

Added student-owned conversation/message storage, owner-scoped endpoints, bounded history/context and source-reference persistence. A history read rechecks referenced contribution versions and hides stale assistant answer/source data after revocation. Conversations are not used as retrieval input and are not accessible to alumni. Flutter has history, new conversation, follow-up, loading/error handling, citations, and deletion.

Primary files: `KnowledgeConversationService`, `KnowledgeConversationController`, conversation/message DTOs, repository methods, and `knowledge_companion_screens.dart`. Integration tests cover creation, ownership isolation, deletion, and suppression of history after source withdrawal.

## Milestone 5 — Human escalation: COMPLETE

Insufficient evidence and detected conflicts offer recommendations from the existing `MentorDiscoveryService`. No second ranker was introduced and no connection or message is sent automatically. Flutter links to the existing mentor profile flow so students explicitly choose further contact. Existing Mentor Discovery and Career Assistant routes and contracts were not replaced.

Primary files: `GroundedKnowledgeAnswerService`, existing mentor discovery APIs, and the Knowledge Companion screen.

## Milestone 6 — Unanswered-topic aggregation: COMPLETE

Only insufficiently answered questions are normalized and grouped. Persistence is unique per student/topic; repeated questions by one student count once. Raw question text and identities are not exposed to alumni. The alumni opportunity endpoint has a minimum five-distinct-student threshold (configured thresholds cannot lower it). Integration coverage checks deduplication, reordered equivalents, and visibility at four versus five students.

Primary files: `UnansweredQuestionService`, `KnowledgeOpportunityResponse`, contribution controller endpoint, and V25 aggregation table.

## Milestone 7 — Freshness: COMPLETE

Alumni can opt out or select 90/180-day reminders and confirm current published content. Due reminders are claimed conditionally to avoid duplicates and use the existing in-app notification table. Consent/withdrawal eligibility is respected; aging alone does not alter content. Email delivery is not promised. Integration coverage checks confirmation, reminder behavior, and no reminder after withdrawal.

Primary files: `KnowledgeFreshnessService`, `KnowledgeFreshnessScheduler`, freshness controller/DTOs, and V25 contribution columns/index.

## Milestone 8 — Preparation roadmaps: COMPLETE

Students can create, list, edit progress/details, and delete private roadmaps. Generated items cite eligible alumni reports as a separate evidence class from general guidance. Roadmaps store source/version references, and current eligibility is rechecked before returning source metadata. Flutter provides roadmap creation and progress/editing controls. Integration coverage checks ownership, update, and source suppression after withdrawal.

Primary files: `KnowledgeRoadmapService`, `KnowledgeRoadmapController`, roadmap DTOs, `knowledge_companion_screens.dart`, and V25 roadmap/source tables.

## Database and interfaces

- V24 is the existing contribution schema; it was not edited. Its category column is `VARCHAR(32)`, so adding `MENTORSHIP_GUIDANCE` required no database migration.
- V25 additively adds freshness fields and conversation, source-reference, question-interest, and roadmap tables; it was not edited.
- H2 routed migrations and real MySQL fresh/recovery migration tests use the existing migration routes; MySQL validation ran against dedicated disposable schemas.
- Student APIs: contribution listing, retrieval, answer, conversation CRUD/messages, roadmap CRUD/items.
- Alumni APIs: contribution ownership/lifecycle/consent, freshness settings/confirmation, and aggregated opportunities.
- Exact paths and examples are in `ALUMNI_KNOWLEDGE_CONTRIBUTIONS.md` and the corresponding controllers.

## Validation record

- Initial state: branch `phase-2-6-full-review`, HEAD `f06efe97d97de68a65661293c1b08ecdb51e7409`; `origin/main` was `183c2f0a6357a968659f73eaa4f6e5907192c685`.
- Backend compile: `mvnw -DskipTests compile` passed. The full Maven suite passed: 371 tests, 0 failures, 0 errors, 9 skipped. Fresh/recovery MySQL migration tests ran against disposable MySQL 8.0.46 schemas; the separate opt-in processing lifecycle suite passed all 5 tests. Focused knowledge contribution, grounded answer, and provider tests passed, including the v1 consent revocation/v2 republication regression.
- Flutter: `flutter analyze` reported no issues; all 139 tests passed. A debug APK built successfully for `https://mentorax-production.up.railway.app` using Java 21. Flutter's temporary JDK override was restored after the build.
- Python 3.12.8: source-only `compileall` passed; all 78 service unit tests and all 24 evaluation tests passed. OCR-heavy unit tests ran after installing the required native libraries in the disposable container. The tracked dataset and generated `latest_results.json` were byte-preserved around evaluation runs. Synthetic document evaluation was not run.
- MySQL tests used only the two task-owned disposable containers and dedicated schemas. No production database or API write was performed.
- No migration file was changed. `mentorax-ai/evaluation/dataset.json` remains byte-for-byte unchanged. No ZIP files were accessed. No commit, push, merge, or deployment was performed.
- The deterministic answer provider remains the default. The real Ollama local-model test is opt-in and was not run; mocked adapter tests do not establish real model behavior or statement entailment. No claim is made that GitHub Actions passed.
