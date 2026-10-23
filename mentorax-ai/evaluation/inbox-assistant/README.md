# Inbox assistant evaluation

No genuine labeled inbox dataset is currently registered. Do not treat synthetic examples or unit-test strings as quality evidence. Feature One's held-out queries and labels are not inputs to this evaluation.

The frozen implementation uses TF-IDF similarity against a fixed category-description set and explicit deterministic urgency rules. It is not a Mentorax-trained model; the similarity score is not a calibrated probability. No pretrained transformer classifier is configured or downloaded, so transformer comparison is `NOT_EVALUATED` until an offline model candidate and its license, size, latency, and resource needs are reviewed.

## Dataset format

The evaluator expects a JSON object with schema version `mentorax-inbox-assistant-evaluation-v1`, `provenance`, and `examples`. Provenance requires a dataset name and source description, labeling protocol version, UTC review time, at least two independent reviewers with non-empty IDs and roles, and `modelOutputHidden: true`.

Each example has a stable ID, original message content, and at least two `judgments`. Every judgment records `reviewerId`, category (`CAREER_GUIDANCE`, `INTERVIEW_PREPARATION`, `REFERRAL_JOB_APPLICATION`, `PROJECT_TECHNICAL_ADVICE`, `MENTORSHIP_REQUEST`, `OTHER`, or `UNRESOLVED`), priority (`urgent`, `normal`, or `UNRESOLVED`), and rationale. Disagreement remains unresolved unless an explicit `adjudication` records `reviewerId`, final category, final priority, and rationale. Unresolved examples are counted and excluded from metrics; they are never converted to `OTHER` or `normal`.

The dataset should use messages collected with appropriate consent and redaction. Keep reviewer identities and source message identifiers in a separately access-controlled provenance record when needed; use pseudonymous reviewer IDs in the evaluation file. Do not include ranking outputs in reviewer materials. Have reviewers label the message independently before discussing disagreements.

## Run

From `mentorax-ai`, after a genuine reviewed dataset has been prepared outside source control:

```powershell
python -m evaluation.inbox_assistant_evaluation C:/secure-path/inbox-assistant-reviewed.json
```

The script fails closed for missing provenance, fewer than two independent judgments, invalid labels, or absent resolved judgments. It reports per-category precision/recall/F1, macro-F1, confusion matrix, abstention rate, priority precision/recall, and urgent false-positive/false-negative counts. Register the dataset hash and reviewer protocol alongside the output. No empirical quality result is available until genuine labels exist.
