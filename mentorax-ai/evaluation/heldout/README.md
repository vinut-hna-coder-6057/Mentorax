# Held-out mentor-discovery evaluation

Status: **not yet run**. The 48-query blind review referenced in the project history is still pending; this directory contains no query labels or result scores. Do not use the development corpus at `../dataset.json` to tune the held-out labels or the semantic threshold.

## Protocol

1. Create a copy of `dataset.template.json` in a controlled review workspace. Keep the blind question text and expected relevance labels unavailable to implementers until the ranking configuration is frozen.
2. Use synthetic or consented, de-identified profiles only. Include approved-profile eligibility in the fixture and exclude pending/rejected profiles from expected recommendations.
3. Cover exact matches; paraphrases; company- and role-specific questions; skill/domain questions; ambiguous and unrelated questions; no-suitable-alumnus cases; low-lexical-overlap semantic positives; plausible false positives; and relevant profiles positioned beyond the former 500-record boundary.
4. Have reviewers independently label each profile/question pair: `2` directly relevant, `1` adjacent and useful, `0` not relevant. Resolve disagreements before unblinding. Record rationales for every positive and for every no-match case. Never infer labels from model outputs.
5. Freeze a dataset version and SHA-256 digest before running `python evaluation\\run_evaluation.py --dataset evaluation\\heldout\\dataset.json`. Archive the command, code revision, strategy/configuration, model ID and revision, timestamp, and full machine-readable report together.
6. Report Precision@K, Recall@K, MRR/NDCG@K, false-positive rate, no-match accuracy, and counts/denominators. Compare thresholds only on development data; evaluate the frozen threshold once on held-out data. Publish failures and abstentions, not only aggregate scores.

The category registry in the template is a coverage checklist, not labeled evaluation data. Until reviewers provide the held-out corpus, no held-out metric or production-accuracy claim is available.
