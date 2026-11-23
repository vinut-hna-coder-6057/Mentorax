# Phase 2.6 Final Verification

## Acceptance status

**BLOCKED — Phase 2.6 is not verified complete.** The actual Spring–Python–MySQL backend workflows passed, including internship and mentorship claims, lifecycle updates, authorization checks, extraction persistence, and sensitive-content scans. Database-backed retry, lease recovery, retry exhaustion, and an evidence-version race have since passed against fresh disposable MySQL, but those controlled HTTP-responder tests do not replace the complete live Python workflow for every case. The required Flutter release-web journey remains blocked because there is no trusted staging HTTPS endpoint.

Phase 2.7 and 2.8 implementation and readiness findings are documented separately; neither phase is complete.

### Remaining acceptance gaps

| Criterion | Status | Evidence / limitation |
|---|---|---|
| Flutter release login-to-assessment/history journey | **BLOCKED** | No local HTTPS API listener with a trusted certificate was available. The documented URL is production, so it was not used for synthetic acceptance data. |
| Correction evidence through the live Spring API and processing pipeline | **NOT VERIFIED LIVE** | A new focused MockMvc regression verifies link/supersede behavior and failure safety; no separate live correction upload was made. |
| Dedicated `CONFLICTS_WITH` upload through the live API | **NOT VERIFIED LIVE** | Complementary evidence did produce a live `INCONSISTENT` assessment, and controller/decision tests cover explicit conflict behavior. |
| Forced analysis failure and retry through the live Spring scheduler | **PASS — database-backed controlled responder** | Fresh-MySQL lifecycle tests verify recoverable failure, retry/backoff, successful extraction, idempotent replay, retry exhaustion, and stale-lease recovery. The controlled responder is not Python, so Python-induced live failure remains unverified. |
| Deliberately raced stale job against a newer evidence version | **PASS — database-backed controlled fixture** | Fresh-MySQL lifecycle test verifies a version-1 worker cannot affect a requeued version-2 job, including equal retry-attempt numbers and unique lease tokens. |
| Historical production extraction-data review | **NOT RUN** | No production database was accessed or rewritten. A controlled review may be needed for records created before sanitization. |

## Maven diagnosis and startup correction

- The earlier direct wrapper launch reached Flyway and Hibernate, then failed while creating `MentorDiscoveryService`. The exact cause was the self-referential property `AI_MENTOR_API_TOKEN=${AI_MENTOR_API_TOKEN:}` in `application.properties`, which caused Spring's `Circular placeholder reference 'AI_MENTOR_API_TOKEN:'` error when the environment variable was absent.
- This was a runtime configuration error, not a source compilation, dependency-resolution, or working-directory failure. Standalone `mvn` is not installed; the repository wrapper works. The environment used JDK 21.0.11, Maven Wrapper 3.3.4 / Maven 3.9.15, and Spring Boot 3.3.5.
- Removed the redundant self-reference. `ApplicationPropertiesResolutionTest` now loads the actual main properties file with system environment/property sources absent and verifies the optional token resolves to an empty value.
- After correction, `.\mvnw.cmd spring-boot:run` compiled successfully and started Spring. On the fresh MySQL 8.0.46 database, Flyway validated 16 migrations through V22, Hibernate initialized, and `/actuator/health` returned `UP` with `db=UP`.
- Outbound email dispatch was explicitly disabled with `APP_EMAIL_OUTBOX_ENABLED=false`. Synthetic signup still used the real encrypted outbox OTP and `/verify-email`; no external email was sent.

## Actual backend workflow

All records and documents below were synthetic and stored only in the disposable database.

- Created and verified synthetic student accounts through Spring `/signup`, `/verify-email`, and `/login`; created an `INTERNSHIP` claim through `POST /experience-claims`.
- Uploaded a synthetic certificate through Spring `POST /experience-claims/{id}/evidence`. Spring assigned evidence ID 1, version 1, stored it encrypted, and queued processing.
- The real scheduler leased and processed the job. Spring called the authenticated local Python `/experience/analyze` endpoint. The strict v1 response passed Spring validation and was persisted; the decision engine produced `VERIFIED_FROM_EVIDENCE` / `ANALYZED`.
- Direct authenticated HTTP verification of the same Python route returned HTTP 200, schema `mentorax.document-understanding.v1`, `analysisStatus=COMPLETE`, classification `EMPLOYMENT_CERTIFICATE`, and six fields: person, organization, role, start date, and end date `FOUND`; issue date `NOT_PRESENT`.
- The persisted first extraction was associated with evidence ID 1/version 1 and contained 6 fields, 0 candidates, 1 statement, 7 provenance records, and 0 warnings. A Java-generated canonical response hash was persisted. Python does not provide that response-hash field, so no Python-to-database hash equality was asserted.
- Re-uploading the identical PDF returned HTTP 200 without creating a second evidence-history record or version. The original and downloaded owner-authorized evidence had identical SHA-256 hashes.
- A separate `MENTORSHIP` claim also completed processing and persisted an extraction. Its assessment was `ADDITIONAL_EVIDENCE_REQUIRED`, not an internship result.
- Final disposable database state: 2 claims, 4 extraction runs (three distinct internship evidence versions and one mentorship evidence version), and 0 queued processing jobs. Distinct document and response hashes were present for the three internship runs; earlier runs remained after later versions were processed.

## Sensitive-data defense in depth

The synthetic certificate planted OTP and verification codes, a PIN, password and passphrase, bearer credential, API key, bank-account identifiers, and a card number.

- The complete serialized Python response contained 0 planted-value matches. Synthetic name, organization, role, dates, and certificate reference remained available.
- Spring claim and evidence-history JSON contained 0 planted-value matches. The persisted-content scan covered claim findings/explanations, evidence analysis findings, fields, candidates, statements, provenance excerpts, and warnings; it found 0 matching rows.
- Captured Python access logs contained only request paths and HTTP status codes. No planted values appeared in the captured Spring console output.
- Spring successfully decrypted and downloaded the authorized original evidence, and its bytes matched the uploaded PDF. **That explicit binary download necessarily contains the planted values because it is required to reproduce the original file.** The clean-response result applies to extraction JSON, assessment/history DTOs, and structured persistence—not the original document bytes.
- No historical production database was inspected or rewritten. If earlier runs may contain unsanitized extraction values, a separate controlled historical-data review/remediation is still required.

## Lifecycle and security checks

- Complementary upload: version 2 was added while version 1 remained `ACTIVE`.
- Failed replacement upload: invalid PDF returned HTTP 415; the related valid evidence remained `ACTIVE`, and no new version was added.
- Successful replacement: version 3 linked to evidence ID 3; only the replaced version 2 became `SUPERSEDED`. Version 1 remained active. Three extraction runs were retained.
- Cross-claim related-evidence reference: HTTP 400; history was unchanged.
- Duplicate upload: HTTP 200; no extra evidence record/version.
- Ownership: a second synthetic student received HTTP 403 for the claim and evidence history; an invalid bearer token received HTTP 401.
- Withdrawal: DELETE returned HTTP 204; evidence history retained the record as `WITHDRAWN`, and its extraction remained persisted.
- Complementary evidence temporarily produced an `INCONSISTENT` assessment; after replacement processing the claim returned to `VERIFIED_FROM_EVIDENCE`. No lifecycle semantics were changed to obtain these results.
- Focused Java tests cover controller relationships, decision evidence, extraction persistence, scheduler processing, retry scheduling, and stale-version protection. A new MockMvc correction test confirms the predecessor link and superseding transition occur only after a successful upload. The correction path, a dedicated `CONFLICTS_WITH` upload, forced retry failure, and a deliberately raced stale-job scenario were not separately driven through the live server.

## Flutter

- `flutter test`: 132 passed. `flutter analyze`: no issues.
- The prior release build used `API_BASE_URL=https://127.0.0.1:8443` and rendered the login screen, but this is not evidence of a backend journey.
- At the time of this historical run, the release URL documented in `alumni_connect_mobile/README.md` was the Railway endpoint. It is retired; current builds use the centrally managed Spring Render URL documented in that README.
- `alumni_connect_mobile/lib/app/env.dart` requires an absolute HTTPS URL in profile/release builds. It does not bypass or pin certificates. The default Dio client installs no custom certificate adapter or bad-certificate callback; HTTPS validation is left to the platform transport (the browser's certificate validation for Flutter web). The Android main manifest denies cleartext traffic; the debug manifest alone permits it for local development.
- Spring's main `application.properties` configures the local service port but no `server.ssl.*` keystore, so the local Spring API is HTTP by default.
- A read-only check of CurrentUser and LocalMachine certificate stores found no localhost certificate, and no listener was present on ports 443, 8443, 5000, 8080, or 8090. The only Docker services present were unrelated Redis/Mongo containers, which were left untouched. No local HTTPS API endpoint with a trusted certificate was available.
- The shared browser tab had previously rendered the login route with an HTTPS-configured release build, but that is not evidence of a backend journey. I did not use the production API, install a new trust anchor, bypass certificate validation, or downgrade TLS.
- Login against Spring, claim creation/editing, upload, processing status, assessment, history, withdrawal, and retry states through Flutter therefore remain **BLOCKED / NOT VERIFIED**.

## Regression gates

| Command or gate | Result |
|---|---|
| `Push-Location .\mentorax-ai; python -m unittest discover -s tests -v; $result=$LASTEXITCODE; Pop-Location; exit $result` | 71 tests passed; exit 0 |
| `Push-Location .\mentorax-ai; python -m unittest discover -s evaluation -v; $result=$LASTEXITCODE; Pop-Location; exit $result` | 19 tests passed; exit 0 |
| `Push-Location .\alumni-connect; .\mvnw.cmd '-Dtest=ExperienceClaimProcessingRepositoryTransactionTest' test; $result=$LASTEXITCODE; Pop-Location; exit $result` | 1 passed; exit 0 |
| `Push-Location .\alumni-connect; .\mvnw.cmd '-Dtest=ExperienceClaimControllerTest' test; $result=$LASTEXITCODE; Pop-Location; exit $result` | 22 tests passed, 0 failures/errors/skips; exit 0 (includes correction regression) |
| `Push-Location .\alumni-connect; .\mvnw.cmd '-Dtest=*,!FreshMySqlMigrationTest' test; $result=$LASTEXITCODE; Pop-Location; exit $result` | 334 tests, 0 failures, 0 errors, 3 skipped; exit 0 (run before the focused correction test was added) |
| `FreshMySqlMigrationTest` against fresh disposable schemas | Previously verified: 2 passed, 0 failures/errors/skips. The actual Spring run also migrated a separate fresh MySQL database through V22. |
| `Push-Location .\alumni_connect_mobile; flutter test; ...; flutter analyze; ...` | 132 tests passed; analysis reported no issues; both exit 0 |
| `git diff --check` | Exit 0 after the final report update |
| Spring `.\mvnw.cmd spring-boot:run` | Startup and health verified; the command intentionally remained running during the HTTP workflow, so it had no exit code at that point. |

The first full Java-suite rerun exposed a time-precision edge in the lease regression fixture: a timestamp inserted and queried at the same instant could be rounded by the database and miss the due-job predicate. The fixture now schedules the row one second in the past. The focused test and the final full safe suite both passed; production lease logic was not changed by this correction.

## Files and preservation

Changes made during this continuation:

- `alumni-connect/src/main/resources/application.properties` — removed the self-referential optional token property.
- `alumni-connect/src/test/java/com/alumni/alumni_connect/config/ApplicationPropertiesResolutionTest.java` — added the missing-environment regression test.
- `alumni-connect/src/test/java/com/alumni/alumni_connect/ExperienceClaimControllerTest.java` — added correction-link and superseding-on-success regression coverage.
- `alumni-connect/src/test/java/com/alumni/alumni_connect/repository/ExperienceClaimProcessingRepositoryTransactionTest.java` — made the due-job test timestamp deterministic.
- `PHASE_2_6_FINAL_VERIFICATION.md` — updated this acceptance report.

Earlier Phase 2.6 remediation files—including the Python sanitizer and regression test, and the transactional lease-acquisition fix and test—were preserved. No Flutter source or migration was changed in this continuation. V20, V21, and V22 were left untouched. Existing worktree modifications and untracked files were preserved; no staging, commit, push, deployment, reset, or cleanup of pre-existing work was performed.

## Database-backed processing lifecycle verification — latest continuation

**Status: PASS for the lifecycle cases below; Phase 2.6 remains BLOCKED overall.** This section supersedes earlier statements that retry and stale-lease recovery had only unit-test evidence.

- Added attempt-generation ownership to the production processing path. Lease acquisition returns the incremented attempt count; processing and unexpected-failure handling lock the job row and require the matching attempt plus a live lease. The scheduler passes the acquired attempt to both paths.
- Added opt-in MySQL integration tests. They refuse a non-loopback target, any port other than 3308, a schema other than `mentorax_phase26_lifecycle_test`, and any schema that is not empty before Flyway starts.
- A new disposable MySQL 8.0.46 container was used. The Spring test context applied all 16 migrations through V22, Flyway validation succeeded, and Hibernate schema validation completed.
- The test-only loopback HTTP responder was authenticated using the configured internal bearer token. For retry testing it returned HTTP 503, then a strict v1 response. The responder exercises Spring's real HTTP client, validation, processing service, extraction persistence, decision update, and MySQL transactions; it does not execute Python code. The actual Python route's HTTP verification remains the separate evidence recorded above.

Database-backed cases:

| Test | Result |
|---|---|
| `retriesRecoverablePythonFailureAndKeepsSuccessfulExtractionIdempotent` | PASS — first attempt recorded `attempt_count=1`, released its lease, scheduled the existing 5-second backoff, and created no extraction. After making the job due, attempt 2 succeeded and persisted one extraction. Replaying the same strict response did not add another extraction or alter the completed decision. |
| `recoversExpiredLeaseAndRejectsAnOldWorkersProcessingAndFailureWrites` | PASS — a second acquisition was refused while the lease was live. After setting the lease and due time to the past, acquisition returned attempt 2. Attempt 1 could neither call the analysis endpoint nor alter the claim/job through processing or failure recording. Attempt 2 completed and persisted its extraction. |
| `exhaustsRetriesWithoutWritingSuccessfulExtraction` | PASS — an initial attempt count of 4 was acquired as attempt 5; HTTP 503 exhausted the existing limit, marked the claim `PROCESSING_FAILED`, wrote the failure audit row, deleted the job, and persisted no extraction. |

After all three tests, direct SQL inspection showed 3 synthetic claims, 3 evidence rows, 2 successful extraction runs (one each for retry success and stale-lease recovery), 0 remaining processing jobs, and 1 `PROCESSING_FAILED` audit event. Each extraction remained associated with evidence version 1 and had one distinct canonical response hash. The full Flyway history contained successful versions 1–3 and 10–22.

Regression results in this continuation:

| Command | Result |
|---|---|
| `.\mvnw.cmd '-Dphase26.processing.mysql=true' '-Dtest=ExperienceClaimProcessingMySqlLifecycleTest' test` against the fresh disposable loopback database | PASS — 3 tests, 0 failures, 0 errors, 0 skipped; exit 0. |
| `.\mvnw.cmd '-Dtest=ExperienceClaimProcessingRepositoryTransactionTest,ExperienceClaimProcessingSchedulerTest,ExperienceClaimProcessingServiceTest' test` | PASS — 14 tests, 0 failures, 0 errors, 0 skipped; exit 0. |
| `.\mvnw.cmd '-Dtest=*,!FreshMySqlMigrationTest' test` | PASS — 339 tests, 0 failures, 0 errors, 6 skipped; exit 0. The opt-in lifecycle tests were separately run above with MySQL enabled. |
| `git diff --check` | PASS — exit 0 before this report append; rerun after final edits. |

Files changed in this lifecycle continuation:

- `alumni-connect/src/main/java/com/alumni/alumni_connect/repository/ExperienceClaimProcessingRepository.java`
- `alumni-connect/src/main/java/com/alumni/alumni_connect/service/ExperienceClaimProcessingService.java`
- `alumni-connect/src/main/java/com/alumni/alumni_connect/service/ExperienceClaimProcessingScheduler.java`
- `alumni-connect/src/test/java/com/alumni/alumni_connect/repository/ExperienceClaimProcessingRepositoryTransactionTest.java`
- `alumni-connect/src/test/java/com/alumni/alumni_connect/service/ExperienceClaimProcessingSchedulerTest.java`
- `alumni-connect/src/test/java/com/alumni/alumni_connect/service/ExperienceClaimProcessingServiceTest.java`
- `alumni-connect/src/test/java/com/alumni/alumni_connect/service/ExperienceClaimProcessingMySqlLifecycleTest.java`
- `PHASE_2_6_FINAL_VERIFICATION.md`

No migration was changed in this continuation. Existing V20–V22 files remain pre-existing untracked worktree files and were preserved. The disposable container and its task-created data volume were removed after verification; port 3308 is closed. The safe HTTPS Flutter journey remains BLOCKED because no trusted staging HTTPS API is available; the production URL was not used. Historical production extraction review remains NOT RUN. Phase 2.6 must therefore remain BLOCKED, and Phase 2.7 was not started.

## Flutter CI analyzer failure — 2026-11-14

**Status: PARTIALLY COMPLETE.** The reported Flutter CI failure was reproduced from the read-only GitHub Actions run `43553741601` (Flutter Tests job `132321787086`). The backend job, including fresh MySQL migration tests, passed. Flutter dependency installation passed; analysis failed, so Flutter tests and the Android APK job were skipped.

- Root cause: CI selected the floating `stable` channel. The failed analyzer output contained 31 `deprecated_member_use` informational diagnostics for `package:flutter/material.dart`, which caused `flutter analyze` to exit non-zero. The source imports were not mass-rewritten.
- Correction: `.github/workflows/ci.yml` now pins both Flutter setup steps to `3.47.9`; the Flutter test job asserts that exact version in its logs. The Android APK job uses the same pinned version.
- The local Flutter SDK is 3.47.9 (Dart 3.13.7), satisfying `pubspec.yaml` constraints. No dependency manifest or lockfile change was made.
- Local validation, all using Flutter 3.47.9:

| Command | Result |
|---|---|
| `flutter --version` | PASS — Flutter 3.47.9, Dart 3.13.7; exit 0 |
| `flutter pub get` | PASS — dependencies resolved; exit 0 |
| `flutter analyze` | PASS — no issues found; exit 0 |
| `flutter test --reporter json` | PASS — 152 passed, 0 failed, 0 skipped; exit 0 |
| `flutter build apk --debug` | PASS — debug APK built; exit 0. Gradle/AGP/Kotlin future-support warnings were emitted. |
| `git diff --check` | PASS — exit 0 |

The GitHub workflow has not been rerun with this correction because this task does not push or dispatch changes. The CI fix is locally verified but awaits a subsequent authorized workflow run. The existing Phase 2.6 external/integration gaps remain: no trusted isolated HTTPS staging endpoint is available for Flutter backend E2E, historical production extraction review has not been authorized, and the remaining live integration cases listed above have not all been exercised through the complete Spring/Python/MySQL path. Phase 2.6 is therefore still BLOCKED. At the time this report section was first written, Phases 2.7 and 2.8 had not started; their later work and acceptance are recorded separately below.

## Phase 2.6–2.8 local continuation — 2026-11-14

This continuation supersedes the earlier statement that the Python response did not contain the original-document hash. The production FastAPI route now uses the canonical strict document-understanding pipeline and returns `sha256` of the original uploaded bytes; the normalized text digest is separate. Spring sends the stored evidence hash and independently rejects any different response hash. The endpoint rejects missing or mismatched request hashes.

The OCR route now uses the existing killable bounded worker process and executes analysis off the asynchronous event loop. Added regression coverage for worker termination on timeout, OCR provenance bounding boxes, supported classification labels, original hash equality/mismatch, and required hash metadata. Removed the old unbounded OCR helper from the extraction module. No migrations changed.

Additional locally verified results:

| Gate | Result |
|---|---|
| `python -m pytest tests -q` on Python 3.11 | PASS — 75 tests, 0 failures, 17 subtests; one third-party deprecation warning; exit 0 |
| `python -m pytest evaluation -q` on Python 3.11 | PASS — 19 tests, 0 failures, 8 subtests; exit 0 |
| Java safe suite after request-hash validation | PASS — 345 tests, 0 failures, 0 errors, 6 skipped; exit 0 |
| AES-256-GCM focused tests | PASS — 3 tests, 0 failures; exit 0 |
| Fresh disposable MySQL 8.0.46 lifecycle suite after request-hash change | PASS — 3 tests, 0 failures/errors/skips; Flyway through V22 and Hibernate validation passed; task-created container removed |
| Flutter 3.47.9 analysis/tests/debug APK | PASS — no analyzer issues, 152 tests passed, APK built |
| `git diff --check` | PASS |

CI now pins both Flutter setup jobs to 3.47.9, includes an explicit version assertion, adds Python syntax/unit/evaluation jobs, and runs the opt-in MySQL processing lifecycle suite against a separately provisioned fresh database. The revised GitHub workflow was not remotely executed because no push or workflow dispatch was authorized. Phase 2.6 remains BLOCKED by trusted isolated HTTPS Flutter staging, historical production-data review authorization, and live integration cases not yet driven through the actual full Spring/Python/MySQL stack. The detailed local findings and phase-specific limitations are in [Phase 2.7](./PHASE_2_7_ACCEPTANCE_REPORT.md) and [Phase 2.8](./PHASE_2_8_READINESS_REPORT.md); neither later phase is marked complete.

Files changed in this continuation:

- `.github/workflows/ci.yml`
- `PHASE_2_6_FINAL_VERIFICATION.md`
- `PHASE_2_7_ACCEPTANCE_REPORT.md`
- `PHASE_2_8_READINESS_REPORT.md`
- `alumni-connect/src/main/java/com/alumni/alumni_connect/dto/ExperienceEvidenceAnalysisRequest.java`
- `alumni-connect/src/main/java/com/alumni/alumni_connect/service/ExperienceClaimProcessingService.java`
- `alumni-connect/src/main/java/com/alumni/alumni_connect/service/ExperienceEvidenceAnalysisService.java`
- `alumni-connect/src/test/java/com/alumni/alumni_connect/service/ExperienceEvidenceAnalysisStructuredResponseTest.java`
- `alumni-connect/src/test/java/com/alumni/alumni_connect/service/ExperienceEvidenceCryptoTest.java`
- `alumni-connect/src/main/resources/DATABASE_MIGRATION.md`
- `mentorax-ai/README.md`
- `mentorax-ai/document_understanding.py`
- `mentorax-ai/experience_analysis.py`
- `mentorax-ai/tests/test_experience_analysis.py`

V20–V22 remain unchanged. No ZIP files were inspected or modified. No production service, database, data or secrets were accessed. No credentials were committed. No commit, push or deployment was performed. Port 3306 was occupied by pre-existing local infrastructure, so the fresh migration/recovery pair was not rerun there; that service was left untouched.

## Latest lease/conflict and OCR continuation — 2026-11-14

This section supersedes earlier lifecycle test counts and migration-through-V22 statements where newer verification below applies.

- Added the additive V23 migration for nullable `experience_claim_processing.lease_token`. V20, V21, and V22 were not changed. Lease acquisition writes a fresh token; process and failure writes require matching claim, evidence version, attempt, token, and a live lease. Requeue clears the token. This closes the old-worker/new-evidence collision even when the new job resets its attempt count.
- Active explicitly declared `CONFLICTS_WITH` evidence now prevents a verified decision; conflicts with non-active historical evidence remain excluded. Cross-document comparisons include issuer and duration. Python findings expose those source-grounded values and provenance.
- OCR now prioritizes likely page orientation and stops trying additional rotations when extracted text meets the confidence threshold. The bounded default is 60 seconds (the existing configuration ceiling remains 120 seconds); no unbounded OCR fallback was added.
- Fresh disposable MySQL 8.0.46 validation applied V23 and passed Flyway and Hibernate validation. Fresh migration and legacy-recovery tests passed 2/2. Migration histories were fresh `1,2,3,10–23` and normalized/recovery `1,2,3,4,10–23`; all entries succeeded.
- Four database-backed lifecycle tests passed against an empty, task-created schema: recoverable failure/retry/idempotency; expired-lease recovery and rejection of the old worker's processing/failure writes; retry exhaustion; and a version-1/version-2 requeue race. The controlled authenticated HTTP responder exercised Spring's real client, validation, transaction, persistence, and decision path, but did not execute Python.
- Direct SQL after the lifecycle run showed 4 synthetic claims, 5 evidence records, 3 successful extraction runs, 0 remaining jobs, and 1 processing-failure audit event. The extraction assertions include evidence ID/version, fields, candidate values, statement, provenance, warning, and canonical response hash.

Latest validation:

| Command | Result |
|---|---|
| `python -m unittest discover -s tests -v` | PASS — 76 tests, 0 failures; exit 0 |
| `python -m unittest discover -s evaluation -v` | PASS — 19 tests, 0 failures; exit 0 |
| Pylance syntax check for `mentorax-ai/experience_analysis.py` | PASS — no syntax errors |
| `git diff --check` | PASS — exit 0 after report updates |
| `.\mvnw.cmd '-Dphase26.processing.mysql=true' '-Dtest=FreshMySqlMigrationTest,ExperienceClaimProcessingMySqlLifecycleTest' test` against a fresh loopback MySQL container on port 3308 | PASS — 6 tests, 0 failures/errors/skips; exit 0 |
| `.\mvnw.cmd '-Dtest=RoutedMigrationTest,SecurityApiIntegrationTest#emailVerificationOutboxEntriesUseTheEncryptedDeliveryPipeline' test` | PASS — 5 tests, 0 failures/errors/skips; exit 0 |
| `.\mvnw.cmd '-Dtest=*,!FreshMySqlMigrationTest' test` | PASS — 348 tests, 0 failures/errors, 7 skipped; exit 0 |
| OCR-focused regressions (scanned PDF, PNG, rotated JPEG, multipage PDF) | PASS — 4 tests; included in the 76-test Python unit suite |

The first MySQL lifecycle run exposed a version-1 hard-coded hash assertion in the new version-2 race fixture; the helper now uses the fixture's evidence version and the complete six-test migration/lifecycle command passed on rerun. The first full Java run also exposed stale migration-count assertions after V23 and an intermittent outbox test failure; migration expectations were updated to V23, the isolated outbox test passed, and the successful complete rerun passed. The Python suite initially exposed OCR timeouts under 45 seconds; orientation-aware OCR plus a 60-second default resolved the scanned-image regressions, and the complete 76-test suite passed. The MySQL disposable container and its task-created schemas were removed after verification. No existing Docker service or occupied port 3306 was touched.

Phase 2.6 remains BLOCKED: trusted isolated HTTPS Flutter-to-Spring E2E and authorized historical production extraction review are outstanding. A correction upload and dedicated `CONFLICTS_WITH` upload have not been exercised through the complete live Spring/Python/MySQL stack. The live Python endpoint's credential redaction and ordinary certificate extraction were independently verified earlier; the controlled MySQL lifecycle responder does not add Python-runtime evidence. No phase is marked complete.

Files changed in this latest continuation:

- `alumni-connect/src/main/java/com/alumni/alumni_connect/entity/ExperienceClaimProcessing.java`
- `alumni-connect/src/main/java/com/alumni/alumni_connect/repository/ExperienceClaimProcessingRepository.java`
- `alumni-connect/src/main/java/com/alumni/alumni_connect/service/ExperienceClaimProcessingService.java`
- `alumni-connect/src/main/java/com/alumni/alumni_connect/service/ExperienceClaimProcessingScheduler.java`
- `alumni-connect/src/main/java/com/alumni/alumni_connect/controller/ExperienceClaimController.java`
- `alumni-connect/src/main/java/com/alumni/alumni_connect/service/ExperienceVerificationDecisionEngine.java`
- `alumni-connect/src/main/resources/db/shared/V23__add_experience_processing_lease_token.sql`
- `alumni-connect/src/main/resources/db/shared/README.md`
- `alumni-connect/src/main/resources/DATABASE_MIGRATION.md`
- `alumni-connect/src/test/java/com/alumni/alumni_connect/FreshMySqlMigrationTest.java`
- `alumni-connect/src/test/java/com/alumni/alumni_connect/RoutedMigrationTest.java`
- `alumni-connect/src/test/java/com/alumni/alumni_connect/service/ExperienceClaimProcessingMySqlLifecycleTest.java`
- Related processing, decision-engine, controller, and repository regression tests under `alumni-connect/src/test/java`
- `mentorax-ai/experience_analysis.py`
- `mentorax-ai/tests/test_experience_analysis.py`
- `PHASE_2_6_FINAL_VERIFICATION.md`, `PHASE_2_7_ACCEPTANCE_REPORT.md`, and `PHASE_2_8_READINESS_REPORT.md`

V20–V22 were not modified. The disposable MySQL container and volume were removed. The existing user worktree changes were preserved; no staging, commit, push, deployment, reset, or cleanup of pre-existing resources occurred.

## Final completion attempt — 2026-11-14

**Phase 2.6 status: BLOCKED — not verified complete.** The current code-controlled database, processing-lease, Python extraction and Flutter regression gates passed. The updated Spring application was not launched in this attempt, so the latest parser-worker change was not verified through the complete Spring → Python → MySQL path. No production service or database was accessed.

| Gate | Result |
|---|---|
| Full Java suite, `.\mvnw.cmd '-Dphase26.processing.mysql=true' test`, with task-created MySQL 8.0.46 | **PASS** — 351 tests, 0 failures, 0 errors, 3 skipped; exit 0. Includes 2 fresh/recovery migration tests and 5 database-backed processing lifecycle tests. |
| Fresh/recovery MySQL migrations | **PASS** — Flyway and Hibernate validation completed through V23. Fresh history has 17 successful migrations; normalized/recovery history has 18. V20–V22 were not modified. |
| MySQL lifecycle persistence | **PASS** — 5 synthetic claims, 6 evidence rows, 4 successful extraction runs, 0 queued jobs after tests. The tests assert evidence/version identity, fields, candidates, statements, provenance, warnings, and response hashes. |
| Retry, lease, and stale-worker regressions | **PASS** — retry/backoff and exhaustion, expired-lease recovery, duplicate/idempotent processing, evidence-version race, and a new same-version/same-attempt token-collision case all passed. In the collision case, the old token could neither call analysis nor write processing/failure results; the new token completed and persisted extraction. These lifecycle tests use a controlled authenticated HTTP responder, not Python. |
| Python unit/API suite, `python -m unittest discover -s tests -v` | **PASS** — 77 tests, 0 failures; exit 0 under local Python 3.14.3. |
| Python evaluation, `python -m unittest discover -s evaluation -v` | **PASS** — 19 tests, 0 failures; exit 0 under local Python 3.14.3. The evaluation is not a labeled document-extraction accuracy corpus. |
| Pylance syntax checks for `experience_analysis.py` and its tests | **PASS** — no syntax errors. |
| Live authenticated Python HTTP extraction | **PASS** — `/health` returned 200; `/experience/analyze` returned 200 and strict v1 for synthetic evidence ID 90217/version 4. A certificate planted with fake OTP/PIN/password/bearer/API-key/bank/card values had 0 matching values in the response; ordinary name, organization, role, dates, issuer, duration, and `NS-EXP-2025-0418` reference remained. |
| Flutter 3.47.9 tests and analysis | **PASS** — 131 tests passed; analyzer reported no issues. These are local widget/unit tests, not a Flutter-to-backend journey. |
| Spring application launch in this attempt | **BLOCKED** — `debugJavaApplication` returned “Maven build failed with exit code 1”; `getDebugSessionInfo` found no active session. The debugger did not expose the Maven error or terminal log. The repository wrapper’s complete test build passed, so this generic launch result does not identify a source compilation defect; its root cause remains unverified. No terminal/Maven launch retry was attempted after the debugger failure. |
| Current-code Spring → Python → MySQL end-to-end and Flutter backend journey | **BLOCKED** — Spring could not be started through the supported debugger workflow. The earlier live Spring/Python/MySQL workflow recorded above remains prior evidence, but does not include this newly bounded parser worker. |
| Trusted-HTTPS Flutter release E2E | **BLOCKED** — no isolated staging API/credentials and trusted HTTPS host were available. Production was not used, certificate validation was not bypassed, and HTTP was not substituted for release HTTPS. |
| Historical production extraction review | **NOT RUN** — no production database access was authorized or attempted. |

The direct Python request and MySQL lifecycle tests are independent checks; they are not represented as one end-to-end workflow. Earlier reports of live Spring workflows remain recorded separately and are not generalized to the updated parser implementation.

The newly added document parser worker has a 90-second total deadline and is terminated/killed on timeout. Upload size, page count, render pixels, and output text are bounded as documented in [the Python service README](./mentorax-ai/README.md). There is no operating-system-enforced memory ceiling for PDF parser internals.

Additional files changed in this attempt:

- `alumni-connect/src/test/java/com/alumni/alumni_connect/service/ExperienceClaimProcessingMySqlLifecycleTest.java` — same-version/same-attempt lease-token collision regression.
- `mentorax-ai/experience_analysis.py` and `mentorax-ai/tests/test_experience_analysis.py` — bounded parser worker and timeout regression.
- `mentorax-ai/README.md` — parser limits and memory-ceiling limitation.
- This report, [Phase 2.7](./PHASE_2_7_ACCEPTANCE_REPORT.md), and [Phase 2.8](./PHASE_2_8_READINESS_REPORT.md) — current verification evidence.

Only the additive V23 lease-token migration is present from the earlier continuation; V20–V22 remain untouched. Existing user worktree changes were preserved. No commit, push, deployment, production access, or ZIP-file operation occurred. Phase 2.6 remains BLOCKED.

## Independent verification pass — 2026-11-14

The repository was rechecked on branch `phase-2-6-full-review`. Existing tracked and untracked work was preserved. No production service or data was accessed.

- Full Java suite: `alumni-connect/.\mvnw.cmd '-Dphase26.processing.mysql=true' test` — **PASS**, 351 tests, 0 failures, 0 errors, 3 skipped. The run used explicit loopback credentials with three newly created schemas on a task-owned MySQL 8.0.46 container. Fresh installation and recovery migrations applied through V23 and passed Hibernate validation; all 5 database-backed processing lifecycle tests passed. The three skips are pre-existing opt-in tests.
- Python: `python -m unittest discover -s tests -v` — **PASS**, 77 tests; `python -m unittest discover -s evaluation -v` — **PASS**, 19 tests; `python -m compileall -q .` — **PASS**. The only installed interpreter available was Python 3.14.3, outside the documented 3.10–3.12 range.
- Flutter 3.47.9: `flutter pub get` — **PASS**; `dart format lib test` — **PASS**, 52 files formatted; `flutter analyze` — **PASS**, no issues; `flutter test --reporter expanded` — **PASS**, 131 tests; debug and release APK builds — **PASS**. Both builds used `API_BASE_URL=https://127.0.0.1:8443`, so they do not validate a reachable or trusted staging backend.
- A fresh Python+Spring+MySQL request journey was not run in this pass. Python contract/redaction tests and Spring database lifecycle tests were run separately; they are not a combined live end-to-end result. Prior live-flow evidence above is historical and is not extended to the current parser/request-hash changes.
- Cleanup removed only the task-owned MySQL container. Port 3308 was closed afterward; pre-existing Redis/Mongo containers were not touched. `git diff --check` passed after report updates. No commit, push, deploy, ZIP access, or production access occurred.

Phase 2.6 remains **BLOCKED** pending trusted isolated HTTPS Flutter-to-Spring acceptance and an authorized historical production-data review. Correction/conflict upload journeys through the current live stack also remain unverified.

## Final current-stack verification — 2026-11-14

A reproducible date comparison defect was found by exercising the latest implementation through live local services. The first run showed that Python marked `June 2025` versus claim `2025-06` as `MISMATCH`, which made Spring return `INCONSISTENT`. Spring's date normalization was fixed, then a second integration run revealed that Python's persisted findings still said `MISMATCH` even though Spring's assessment was corrected. Python and Spring now both normalize named month/year dates consistently; regression tests cover each implementation.

- Current full Java suite, including the new regression: **352 tests, 0 failures, 0 errors, 3 skipped; exit 0**. Fresh and recovery MySQL migrations passed through V23, Hibernate schema validation passed, and all 5 MySQL lifecycle tests passed.
- Python 3.14.3: unit/API **78 passed**, evaluation **19 passed**, explicit-path `compileall` **PASS**.
- Current live local synthetic API flow: Python health and Spring `/actuator/health` (including MySQL) returned `UP`. A test account was registered through the API; email-verification state was set only in the disposable synthetic schema because no mail delivery service was configured. Real Spring login/JWT, claim creation, PDF upload, authenticated Python analysis, strict response validation, extraction persistence, assessment, processing history, and owner-authorized download then ran. Result: `ANALYZED` and `VERIFIED_FROM_EVIDENCE`, no date `MISMATCH` findings, one extraction for the active evidence version, three history events, and the downloaded evidence SHA-256 matched the uploaded PDF.
- Flutter 3.47.9 validation remains: analyze **PASS**, 131 tests **PASS**, debug APK **PASS**, release APK **PASS**. Flutter-to-Spring trusted HTTPS staging was not available, so that client journey remains unverified.
- The task-owned MySQL container and synthetic DB were removed; Spring and Python processes were stopped; ports 3308, 8081, and 8090 were confirmed closed. Existing Redis/Mongo containers were untouched. No production access, ZIP operation, commit, push, or deployment occurred.

Phase 2.6 remains **BLOCKED** on external/staging acceptance: trusted HTTPS Flutter-to-Spring E2E and authorized historical production extraction review. The later correction/conflict/replacement/withdrawal API acceptance below closes those local workflow gaps.

## Final Flutter and document validation addendum — 2026-11-14

**Current outcome: COMPLETE WITH LIMITATIONS.** The experience-claims Flutter UI, API repository and models were inspected against the Spring contract. No contract mismatch was found. The unbounded processing poll and edit state that did not immediately repaint were fixed; widget regressions cover bounded refresh/retry and edit rendering. Flutter analyze passed, the full widget suite passed **134 tests**, and debug/release APK builds passed. No emulator/device or trusted HTTPS staging service was available, so no live Flutter-to-Spring journey was run. Prior real Spring/Python/MySQL evidence lifecycle results recorded above are separate API evidence, not Flutter integration proof.

The synthetic corpus exercised the real authenticated Python analysis route: **12 synthetic documents, 188/188 expected checks; 3/3 malformed/unsupported requests returned expected errors**. OCR succeeded on the scanned fixture. Ambiguous dates remained inconclusive; explicit mismatch remained a mismatch without a fraud claim; compatible evidence matched; all seven planted sensitive values were absent from the response. Every extraction returned `verificationDecision=NONE` and `trustedIssuerValidation=false`. Full per-category results and exact commands are in [the consolidated report](./PHASE_2_6_2_8_FINAL_IMPLEMENTATION_REPORT.md). No production accuracy claim is made. No Java source changed, no database was used in this pass, no ZIP was touched, and no commit/push/deployment occurred.

Phase 2.6 remains **BLOCKED** on trusted isolated Flutter-to-Spring HTTPS acceptance and authorized historical production-data review. Emulator/device execution, Python 3.10/3.12 compatibility, representative labeled documents, and confidence calibration also remain outstanding.

## Final evidence lifecycle acceptance — 2026-11-14

The earlier note that correction, conflict, replacement, and withdrawal had not been exercised is superseded by the current local API acceptance run documented in [the consolidated report](./PHASE_2_6_2_8_FINAL_IMPLEMENTATION_REPORT.md). Synthetic claims and generated PDFs were processed by the current Spring API, authenticated Python service (Python 3.11.9), and a task-owned MySQL 8.0.46 container. A verification flag was set directly only for the synthetic signup accounts in the disposable schema because no email delivery service was configured; login/JWT, claim, evidence, history, download, and withdrawal requests used the real APIs.

| Workflow | Current API result | Persistence/history result |
|---|---|---|
| Original | `VERIFIED_FROM_EVIDENCE`; owner download SHA-256 matched; unrelated download returned 403 | Extraction persisted; 3 ordered events |
| Correction | Old May version superseded; linked June correction active and verified; missing related ID returned 400; foreign correction returned 404 | Both extractions retained; 5 ordered events |
| Conflict | Both versions active; `INCONSISTENT`, explicit clarification and no-fraud explanation | Both extractions retained; 5 ordered events |
| Compatible documents | Complementary active evidence remained `VERIFIED_FROM_EVIDENCE` | Both extractions retained; 5 ordered events |
| Replacement | Old version superseded; linked replacement active and verified | Both extractions retained; 5 ordered events |
| Withdrawal | Owner returned 204; unrelated and repeated withdrawals returned 404; claim reset to `AWAITING_EVIDENCE` | Evidence/extraction retained as `WITHDRAWN`; history download remained owner-authorized; 4 events including withdrawal |

Read-only MySQL checks confirmed evidence links, active/superseded/withdrawn states, each version’s persisted extraction, and matching claim assessments. Existing V23 stale-worker protection was exercised by the five database lifecycle tests, including `newerEvidenceLeaseCannotBeReusedByAWorkerFromThePreviousVersion` and lease-token collision coverage.

The complete Java command `mvnw.cmd -Dmaven.repo.local=C:\Users\vinut\.m2\repository -Dphase26.processing.mysql=true test` passed **352 tests, 0 failures, 0 errors, 3 skipped** after the three clean test schemas were created with MySQL’s default collation. The first attempt used an explicit non-default collation and failed one recovery migration test; that setup was corrected and the complete suite passed. No application-source or Flutter files were changed in this acceptance pass. The Flutter API/model contracts were inspected and match the endpoint fields and relationship identifiers. Trusted HTTPS Flutter staging, Python 3.10/3.12 matrix coverage, and historical production-data review remain outstanding.
