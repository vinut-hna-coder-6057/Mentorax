# Knowledge Companion Milestones 2–8

## Initial state and execution boundary

- Initial branch: `phase-2-6-full-review`.
- Initial HEAD: `5b7d227 Fix evaluation dataset provenance across platforms`.
- The worktree already contained the uncommitted Milestone 1 implementation (V24, contribution services/tests/docs) and an existing modification to `mentorax-ai/evaluation/dataset.json`. Both were preserved; no commit, push, merge, or deployment was performed.
- No ZIP files were accessed.
- Spring is the sole authority for authentication, eligibility, consent, retrieval, persistence, and final response policy. Flutter uses Spring APIs only. No vector store was added because the current corpus is served by bounded lexical retrieval and an eligibility recheck.

## Milestone 2 — Trusted retrieval: COMPLETE

Added a Spring lexical retrieval service and authenticated route. Queries are normalized, candidates/results are bounded, ranking is deterministic and reports matched fields, and results cite contribution/version IDs. Only current published versions with both consent scopes and an approved alumni owner are eligible. Eligibility is rechecked before output; no stale cache/index exists. Excerpts and titles redact common private contact/secret patterns and common prompt-injection directives. The integration test covers eligible retrieval, draft exclusion, consent revocation, withdrawal, owner eligibility, empty results, result references, and private access.

Primary files: `KnowledgeRetrievalService`, `KnowledgeRetrievalController`, `KnowledgeRetrievalResponse`, and `KnowledgeContributionService`.

## Milestone 3 — Grounded answers: COMPLETE WITH PROVIDER LIMITATION

Added `GroundedAnswerProvider` and a deterministic implementation. The Spring property `app.knowledge.answer-provider` selects a registered implementation; an unavailable selection falls back to deterministic output and is labeled `DETERMINISTIC_PROVIDER_UNAVAILABLE`. Answers cite current evidence and explicitly identify alumni-reported content, insufficient evidence, and the implemented same-company/role conflict heuristic. Prompt-injection text is filtered from excerpts. The repository has no configured external or local LLM answer provider, so no provider request, timeout, malformed-provider, or live LLM behavior was exercised. No answer is represented as LLM-generated.

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

- V24 is the existing Milestone 1 contribution schema; it was not edited.
- V25 additively adds freshness fields and conversation, source-reference, question-interest, and roadmap tables.
- H2 fresh, normalized, and legacy routed migration checks pass. Real MySQL fresh/recovery migration tests require dedicated test URLs and credentials and were NOT RUN.
- Student APIs: contribution listing, retrieval, answer, conversation CRUD/messages, roadmap CRUD/items.
- Alumni APIs: contribution ownership/lifecycle/consent, freshness settings/confirmation, and aggregated opportunities.
- Exact paths and examples are in `ALUMNI_KNOWLEDGE_CONTRIBUTIONS.md` and the corresponding controllers.

## Validation record

Recorded for this execution on Windows (Java 21.0.11):

- Focused H2 migration/integration run after provider and conflict-path changes: 12 tests, 0 failures/errors/skips; BUILD SUCCESS.
- Complete Maven suite excluding the credential-gated `FreshMySqlMigrationTest`: 358 tests, 0 failures/errors, 8 skipped; all non-MySQL suite reports passed.
- Unfiltered complete Maven suite: 359 tests, 0 assertion failures, 2 errors because `MENTORAX_MYSQL_MIGRATION_TEST` and `MENTORAX_MYSQL_RECOVERY_TEST` URL/username/password are not configured; 8 skipped.
- Flutter widget/unit suite: 136 tests passed.
- Flutter analyze: `No issues found`.
- Python unit/evaluation and synthetic-document evaluation: NOT RUN. The only available `python.exe` and `py.exe` resolve to WindowsApps aliases and could not be launched in this environment; there is no repository `.venv`.
- GitHub CI and Linux-specific database execution: NOT RUN. No claim is made that GitHub Actions passed.
- Real external answer provider: NOT CONFIGURED and NOT TESTED.

These results establish local H2 and Flutter behavior, not production readiness. The MySQL dialect/recovery path and provider-based generation still need validation in an appropriately configured environment.
