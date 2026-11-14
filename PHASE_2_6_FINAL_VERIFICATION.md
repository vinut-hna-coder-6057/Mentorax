# Phase 2.6 Final Verification

## Acceptance status

**BLOCKED — Phase 2.6 is not verified complete.** The actual Spring–Python–MySQL backend workflows passed, including internship and mentorship claims, lifecycle updates, authorization checks, extraction persistence, and sensitive-content scans. The required Flutter release-web journey remains blocked because there is no running local web server or trusted HTTPS endpoint to connect to Spring without weakening TLS. Several less common processing cases were covered by Java tests but not forced through the live application.

Phase 2.7 was not started.

### Remaining acceptance gaps

| Criterion | Status | Evidence / limitation |
|---|---|---|
| Flutter release login-to-assessment/history journey | **BLOCKED** | No local HTTPS API listener with a trusted certificate was available. The documented URL is production, so it was not used for synthetic acceptance data. |
| Correction evidence through the live Spring API and processing pipeline | **NOT VERIFIED LIVE** | A new focused MockMvc regression verifies link/supersede behavior and failure safety; no separate live correction upload was made. |
| Dedicated `CONFLICTS_WITH` upload through the live API | **NOT VERIFIED LIVE** | Complementary evidence did produce a live `INCONSISTENT` assessment, and controller/decision tests cover explicit conflict behavior. |
| Forced analysis failure and retry through the live scheduler | **NOT VERIFIED LIVE** | The Java service regression verifies retry scheduling without a decision; no live Python failure was induced. |
| Deliberately raced stale job against a newer evidence version | **NOT VERIFIED LIVE** | The Java service regression verifies stale-version protection; no live concurrent race was induced. |
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
- The supported release URL documented in `alumni_connect_mobile/README.md` is `https://mentorax-production.up.railway.app`; it is a production endpoint and was not used for synthetic acceptance data.
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
