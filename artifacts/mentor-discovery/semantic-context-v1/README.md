# Mentorax semantic profile-context experiment freeze

This directory freezes the existing `semantic` retrieval condition against the
synthetic development corpus. It does not change the production ranking method.
“Context” means the existing candidate-side concatenation of skills, role,
company, bio, and interests; no chat history or query rewrite is used.

`freeze.json` binds a content-derived ranker ID to the resolved model commit,
runtime scoring configuration, source hashes, candidate manifest, copied
development relevance/rationale maps, context snapshots, provenance, and the
development report. Its status is `development_freeze_heldout_pending` because
the repository contains only the held-out category template, not the reviewed
48-query labels or their provenance. A development freeze can be verified, but
it cannot authorize held-out execution yet.

The checked-in development labels are the existing synthetic grades; the source
does not record reviewer identities or a label-generator version and has no
unresolved-grade representation. Sparse label maps are preserved exactly. The
existing evaluation specification interprets omitted grades as irrelevant, so
the freeze reports this limitation and does not fill in or rewrite those maps.
For held-out data, registration requires two independent reviewers, resolved
disagreements, zero unresolved judgments, and an explicit grade for every
question/profile pair. Unresolved or insufficient-evidence judgments must be
adjudicated before registration and may not silently become grade zero.

## Reproduce the development comparison

From `mentorax-ai` in PowerShell, using the already installed Python and model
packages (no downloads are needed when the pinned model is cached):

```powershell
$env:HF_HUB_OFFLINE = '1'
python evaluation\run_evaluation.py --skip-java --output ..\artifacts\mentor-discovery\semantic-context-v1\development-report.json
python evaluation\freeze_experiment.py create --report ..\artifacts\mentor-discovery\semantic-context-v1\development-report.json
python evaluation\freeze_experiment.py verify
```

The report pairs Python TF-IDF and Python semantic rankings on the same
development career questions with positive labels. Both conditions use the
same candidate-side profile text, so this compares scoring/ranking strategy and
does not isolate the benefit of adding profile fields. It reports mean
Precision@3, Recall@5, MRR@5, NDCG@5, and paired per-query deltas. This is a
small synthetic development comparison; it bypasses Java intent routing and the
approved-profile evidence gate. See the report and manifest for counts and
exclusions.

## Register and run held-out once

Only after independent review is complete, place the reviewed dataset at
`mentorax-ai/evaluation/heldout/dataset.json` and its provenance at
`mentorax-ai/evaluation/heldout/label-provenance.json`. Keep them access
controlled until the ranker freeze has been verified. Provenance must contain
`status: "independently_reviewed"`, a `reviewers` array with at least two
reviewers, the exact dataset SHA-256 in `datasetSha256`,
`disagreementsResolved: true`, and `unresolvedJudgmentCount: 0`.

Then run these commands from `mentorax-ai`; registration and verification fail
closed if any frozen code, configuration, development input, provenance, or
held-out hash differs:

```powershell
python evaluation\freeze_experiment.py verify
python evaluation\freeze_experiment.py register-heldout --dataset evaluation\heldout\dataset.json --provenance evaluation\heldout\label-provenance.json
python evaluation\freeze_experiment.py verify --require-heldout
python evaluation\run_frozen_evaluation.py --freeze ..\artifacts\mentor-discovery\semantic-context-v1\freeze.json --dataset evaluation\heldout\dataset.json --output ..\artifacts\mentor-discovery\semantic-context-v1\heldout-report.json
```

The last command is the frozen final evaluation command. Do not tune thresholds
or ranking configuration from held-out results; any subsequent method change
requires a new experiment freeze and a fresh independent held-out set.
