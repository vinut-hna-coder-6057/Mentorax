# Phase 2.7 — Document Intelligence and Evidence Verification

## Status: PARTIALLY COMPLETE

Spring Boot remains the final decision authority. The Python service extracts source-grounded fields and does not authenticate issuers or make final verification decisions.

## Implemented and verified in this continuation

- The authenticated `/experience/analyze` endpoint now calls the canonical `document_understanding.analyze_document` pipeline directly. The previous legacy extraction-to-strict conversion is no longer on the production route.
- Spring sends the stored evidence SHA-256 with the authenticated request. Python compares it in constant time with the original uploaded bytes and returns that original-byte hash. The normalized extracted-text fingerprint remains a separate `extractedTextFingerprint`.
- Spring independently rejects a strict response whose hash does not match the evidence request hash; invalid results remain unavailable and are not persisted as successful extractions.
- OCR runs in a bounded worker process with a shared per-document OCR deadline; timeout handling terminates and, if needed, kills the worker. Synchronous PDF/OCR work is moved off the FastAPI event loop. Existing size, page, image-dimension and concurrency limits remain in force.
- The canonical classifier recognizes supported employment, internship, mentorship, payslip, offer, project and related document labels without adding new persisted document-type values.
- Extracted identity labels include employee names. Ambiguous values remain candidates in the strict extraction contract. Provenance includes source evidence/version, page, excerpt and OCR bounding boxes where available.
- The deprecated direct OCR helper without process termination was removed.
- Python documentation now states that LLM provider settings are inactive and non-local provider selection returns HTTP 503. No external provider is called.

## Tests run

| Command | Result |
|---|---|
| `python -m compileall -q .` (Python 3.11.9) | PASS |
| `python -m unittest discover -s tests -v` | PASS — 76 tests, 0 failures; exit 0 in the latest run |
| `python -m unittest discover -s evaluation -v` | PASS — 19 tests, 0 failures; exit 0 |
| Focused classifier, original-hash, sensitive-data, OCR rotation, provenance-box and timeout tests | PASS — included in the unit suite |
| `.\mvnw.cmd '-Dtest=ExperienceEvidenceAnalysisStructuredResponseTest,ExperienceClaimProcessingServiceTest' test` | PASS — 20 tests, 0 failures/errors, 1 skipped |
| `.\mvnw.cmd '-Dphase26.processing.mysql=true' '-Dtest=FreshMySqlMigrationTest,ExperienceClaimProcessingMySqlLifecycleTest' test` on a fresh isolated MySQL 8.0.46 container | PASS — 2 migration/recovery tests and 4 lifecycle tests; Flyway applied through V23 and Hibernate validation completed |

The fresh database lifecycle test uses the existing controlled authenticated HTTP responder, not the actual Python process. The Python API contract and Spring response validator were tested independently; this is not claimed as a new live Spring-to-Python end-to-end run.

## Remaining gaps

- No authorized local or hosted provider implementation exists. LLM-assisted extraction remains disabled; this is explicit and fail-closed.
- The current synthetic tests are regression tests, not the full labeled document evaluation corpus requested for per-category accuracy, false-verification and anomaly thresholds.
- Classification and extraction remain deterministic rules, not a fully validated taxonomy for every requested document subtype. Confidence values are not calibrated probabilities.
- OCR execution has a hard process deadline. A separate hard wall-clock deadline for all non-OCR PDF parsing and an explicit memory limit for parser subprocesses are not implemented.
- Cross-document comparisons and evidence relationship behavior remain limited to the currently implemented Spring decision policy and tests; not every requested semantic combination was exhaustively evaluated.
- Trusted HTTPS Flutter E2E and historical production-data review remain external blockers as recorded in [the Phase 2.6 report](./PHASE_2_6_FINAL_VERIFICATION.md).

Phase 2.7 is not complete.

## Latest verification — 2026-11-14

- The full Python unit/API suite passed with 76 tests; the evaluation suite passed with 19 tests. Pylance reported no syntax errors in `experience_analysis.py`.
- Four OCR regressions passed for scanned PDF, PNG, rotated JPEG, and multipage scanned PDF. To avoid unnecessary OCR passes, the worker now tries the likely orientation first and stops rotating after a sufficiently confident extraction. The default worker deadline is 60 seconds and remains capped at 120 seconds; OCR still runs in a killable bounded process.
- The Spring comparison changes now include issuer and duration findings with provenance. The decision engine considers only active evidence and makes an explicitly declared active `CONFLICTS_WITH` relationship inconsistent; historical/non-active relationships do not create a current conflict.
- The expanded database-backed lifecycle suite also passes a version-1 to version-2 requeue race case. It uses a controlled authenticated HTTP responder, not the Python OCR/extraction process, so it is not evidence that these lifecycle paths were re-run through live Python.
- Additive V23 stores the fresh lease token needed to prevent stale workers from matching a newer job after attempt-count reset. Fresh MySQL migration and recovery both passed through V23; V20–V22 remain unchanged.

Remaining gaps still include a labeled/calibrated document evaluation corpus, validated coverage for all requested document types and semantic combinations, an OS-enforced parser memory limit, and any authorized local extraction provider. Phase 2.7 remains PARTIALLY COMPLETE; no hosted LLM or external document processor was introduced.

## Final completion attempt — 2026-11-14

**Status: PARTIALLY COMPLETE.** The authenticated strict extraction route now runs PDF/image parsing in a separate, killable process with a 90-second total wall-clock deadline. Timeout terminates and, if necessary, kills the worker; worker failures return safe errors without document content. Limits are 5 MiB per upload, 20 PDF pages, 25 million rendered pixels per page, and 100,000 extracted text characters. The Python service README documents these bounds.

Validation in this attempt:

- `python -m unittest discover -s tests -v`: **PASS**, 77 tests, 0 failures; Python 3.14.3.
- `python -m unittest discover -s evaluation -v`: **PASS**, 19 tests, 0 failures; Python 3.14.3.
- Pylance syntax checks for the modified extractor and test file: **PASS**.
- Authenticated local Python HTTP request with a synthetic credential-planted certificate: **PASS**, HTTP 200, strict v1 response, ordinary certificate fields/reference retained, 0 planted-secret matches.
- Fresh MySQL migration and lifecycle verification: **PASS**, migrations through V23; five lifecycle tests including token-collision, retry, stale worker, and extraction persistence. The responder is controlled HTTP, not Python.
- Full Java suite: **PASS**, 351 tests, 0 failures/errors, 3 skipped.

The whole-document wall-clock bound is now in place, superseding the earlier statement that parser time was unbounded. An operating-system-enforced memory ceiling is still **NOT VERIFIED/NOT IMPLEMENTED**: input, dimensions, rendered pixels and output are bounded, but a malformed PDF may still cause high parser-process memory use before its deadline. Deploy behind a process/container memory limit before accepting untrusted public traffic. The local Python version (3.14.3) is newer than the documented Python 3.10–3.12 range; compatibility of this latest change on the supported matrix and the remote Python 3.12.8 CI job remain unverified.

The document evaluation suite validates existing ranking/evaluation tooling; it does not supply calibrated document-classification or extraction-quality metrics. No hosted LLM, external document provider, issuer authentication, or Spring decision authority change was introduced. Trusted-HTTPS Flutter E2E and historical production-data review remain external blockers as recorded in [Phase 2.6](./PHASE_2_6_FINAL_VERIFICATION.md). Phase 2.7 remains PARTIALLY COMPLETE.

## Independent verification pass — 2026-11-14

- Python 3.14.3: unit/API **77 passed**, evaluation **19 passed**, `compileall` **PASS**. The suites include original-file SHA-256 validation, strict v1 responses, nested sensitive-value sanitization, parser-worker timeout termination, OCR and extraction behavior.
- Java: complete suite against disposable MySQL 8.0.46 **351 tests, 0 failures, 0 errors, 3 skipped**. Fresh and recovery migrations both passed through V23; all 5 database-backed lifecycle tests passed. These lifecycle tests use Spring's controlled authenticated HTTP responder, not the live Python process.
- No current full Spring → Python → MySQL extraction journey was run. Strict Python API and Spring response validation remain independently verified, not combined. Python 3.10–3.12 compatibility remains unverified because those runtimes were unavailable; only Python 3.14.3 was installed.

Phase 2.7 remains **PARTIALLY COMPLETE**. Remaining items are the documented extraction-quality corpus and calibration, comprehensive subtype/semantic evaluation, supported-version matrix, parser memory ceiling, and an authorized provider decision/credentials if LLM-assisted analysis is required.

## Final current-stack verification — 2026-11-14

- Fixed date-name normalization in the Python finding builder and Spring decision engine. `January 2025`/`Dec 2025` now match `2025-01`/`2025-12`; mismatched months remain mismatches. Added regression tests in both services.
- Python unit/API suite: **78 passed**; evaluation: **19 passed**; explicit Python 3.14.3 `compileall`: **PASS**.
- Java suite including month-name regression: **352 tests, 0 failures, 0 errors, 3 skipped**. Fresh/recovery migrations through V23, Hibernate validation, and all five database-backed lifecycle cases passed.
- A current Spring → authenticated Python → MySQL workflow processed a synthetic PDF and persisted extraction plus findings. Month-name findings and final Spring assessment agreed: no date mismatches and `VERIFIED_FROM_EVIDENCE`. Owner download matched the uploaded bytes. This is a local HTTP integration check; it is not Flutter staging or trusted-HTTPS validation.

Phase 2.7 remains **PARTIALLY COMPLETE** because the representative labeled accuracy/calibration corpus, supported Python runtime matrix, parser memory ceiling, and any credentialed provider decision/acceptance remain outstanding.

## Final evidence lifecycle acceptance addendum — 2026-11-14

The local Python 3.11.9 extraction service was exercised in the current Spring → Python → MySQL API chain for original, corrected, conflicting, compatible, replacement, and withdrawn synthetic PDFs. Spring persisted extraction results for each version; its assessment used only active evidence. Correction and replacement retained superseded extractions, conflict retained both active extractions and returned an explainable `INCONSISTENT` decision explicitly stating it is not a fraud finding, compatible evidence remained verified, and withdrawal retained the historical record while returning the claim to `AWAITING_EVIDENCE`. Full details, API results, direct database checks, and Java suite command/results are in [the consolidated report](./PHASE_2_6_2_8_FINAL_IMPLEMENTATION_REPORT.md).

This pass did not change Python code or rerun Python unit/evaluation suites. Python 3.11.9 was exercised for the live workflow; Python 3.10 and 3.12 matrix coverage, representative labeled accuracy/calibration, parser memory ceiling, and credentialed provider validation remain outstanding. Trusted HTTPS Flutter staging was not tested.

## Final Flutter and document validation addendum — 2026-11-14

Flutter integration review found no request/response contract mismatch. Two UI defects were fixed: processing refresh was unbounded, and editing a claim changed local state without triggering an immediate repaint. Focused widget regressions were added. `flutter analyze` passed, all **134 Flutter tests** passed, and debug and release APK builds passed. Device/emulator and trusted HTTPS staging were unavailable, so the current Flutter client was not installed or exercised against Spring.

The new synthetic document corpus evaluated the real protected Python route with **12 fixtures and 188/188 labeled checks passing**, plus **3/3** invalid-format checks. Scanned OCR succeeded; mismatches and ambiguous fields remained non-verifying; planted sensitive values were redacted; issuer trust remained false. Python 3.11.9 unit/API (78) and existing evaluation (19) tests passed. The full per-category table and constraints are in [the consolidated report](./PHASE_2_6_2_8_FINAL_IMPLEMENTATION_REPORT.md). These results establish deterministic synthetic behavior only, not real-world accuracy or Flutter-to-backend integration. Phase 2.7 acceptance is locally supported with these limits; staging and production acceptance remain open.
