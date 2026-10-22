# Mentorax mentor-discovery evaluation

This suite evaluates the current lexical rankers against version-controlled synthetic inputs. It does not alter production ranking or route real user data.

## Dataset

`dataset.json` contains 12 fictional alumni profiles and 34 labeled questions across career-core, paraphrase, misspelling, multi-skill, explicit alumni question, weak-match, misleading-context, no-match, general, unsupported, greeting, ambiguous, and validation categories. IDs are stable synthetic keys. Per-question `relevance` is the fixed ground truth: `2` highly relevant, `1` partially relevant, omitted or `0` irrelevant. Each positive label has an independent human-readable rationale. The evaluator never derives labels from predictions. `profileRationales` documents the synthetic profile set.

Add a profile with a unique numeric `id`, synthetic professional fields, and a rationale. Add a question with a unique `id`, category, expected intent, recommendation expectation, an explicit label map, and a rationale for every positive label. Run schema validation/evaluation before accepting corpus changes. Do not add production profile or question data.

## What is evaluated

- **Python TF-IDF component:** calls the actual `mentorax-ai/app.py` `rank()` implementation on all synthetic profiles for cases labeled to request mentor discovery. This isolates ranking, deliberately bypassing Java's local profile-evidence gate and intent router; it cannot measure full deployed recommendations.
- **Java fallback:** a dedicated JUnit harness runs the production `CareerAssistantService`, `QuestionIntentClassifier`, and `MentorDiscoveryService` with Python disabled, mocked repositories containing synthetic approved profiles, and real production local evidence/ranking logic. It does not use a production database or claim the authorization integration test was rerun.
- **Python unavailable fallback:** a second Java pass injects a synthetic `ResourceAccessException` from the optional ranker client and verifies deterministic local fallback. A live Python HTTP outage integration is still marked `NOT_EVALUATED`; this is not a running-service test.

## Metrics and edge cases

Binary relevant means grade > 0; NDCG uses gain `2^grade - 1`. Precision@K divides by the number actually returned up to K; an empty list is undefined and excluded from the average. Recall, MRR, and NDCG are undefined when a case has no positive labels and those cases are excluded from those aggregates, with denominators and exclusion counts reported. A positive-label case with no hits has recall/MRR/NDCG of zero (NDCG is zero where ideal gain exists). HitRate is zero when no relevant item is returned. Irrelevant recommendation rate divides irrelevant returned items by all returned items. Intent, false-mentor, and no-match rates include explicit numerator and denominator.

Reports include K=3/5/10 and per-category summaries. Java intent accuracy and false-mentor/no-match checks are evaluated over all labeled questions. Ranking quality targets are transparent initial guardrails in `acceptance.json`, not statistically calibrated production claims. Hard correctness failures exit nonzero; quality misses are warnings and do not get hidden. The Python component has no routing, so routing metrics are N/A there.

The dataset intentionally includes difficult cases. Current production lexical behavior may miss paraphrases or surface irrelevant raw TF-IDF matches; the report is diagnostic, not a claim that recommendations are production-accurate. An evaluation run can expose hard failures; do not edit labels or thresholds merely to turn it green.

## Reproduce on Windows PowerShell

From repository root:

```powershell
cd C:\Users\vinut\Downloads\admind\Mentorax\mentorax-ai
python -m pip install -r requirements-test.txt
python -m unittest discover -s evaluation -p "test_*.py" -v
python -m unittest discover -s tests -v
python evaluation\run_evaluation.py
```

The full evaluation invokes `alumni-connect\mvnw.cmd` and runs only `MentorDiscoveryEvaluationTest`; it requires a local JDK 21 and Maven-wrapper dependencies, but no running database or service. To explicitly omit Java, use:

```powershell
python evaluation\run_evaluation.py --skip-java
```

The machine-readable combined report and Java intermediate output go under `alumni-connect\target\` (ignored build output). The runner prints the report path. The Java test runs each case twice and asserts prediction stability; Python rankings are also repeated and compared. Compare `careerQualityMetrics` against the same fixed dataset and acceptance config before/after a ranking change. This small synthetic corpus does not estimate real-world performance.
