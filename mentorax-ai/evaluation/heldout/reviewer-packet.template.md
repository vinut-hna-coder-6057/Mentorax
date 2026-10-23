# Mentorax independent relevance review packet

Template only. The custodian must create the actual blind packets from genuine
questions and eligible profiles. This file contains no questions, profiles,
judgments, reviewer identities, or ranking results.

## Review rules

- Reviewers must be independent of ranker implementation and threshold tuning.
  They should have relevant career-advising or professional-domain experience.
- Review every question/profile pair independently before discussing any pair.
  Do not request, view, infer, or discuss ranker scores, rankings, or outputs.
- Judge only the evidence in the question and the candidate's displayed
  professional fields. Do not infer mentoring availability, willingness, or
  likely response; those are not represented by this dataset.
- Record one grade and a concise evidence-based rationale for each pair. Use
  `UNRESOLVED` when evidence is insufficient or reviewers have not agreed. It
  is not grade zero.

## Relevance grades

- **2 — Directly relevant:** the displayed profile directly supports the
  question's stated career need.
- **1 — Adjacent and potentially useful:** a specific connection is supported
  by the profile, though it is not a direct match.
- **0 — No sufficiently supported relevant connection:** after reviewing the
  available profile evidence, no relevant connection is supported.
- **UNRESOLVED — Insufficient evidence or outstanding disagreement:** do not
  convert this to zero. Keep it unresolved until a documented adjudication
  reaches a supported final grade.

## Blind packet handling

The data custodian should:

1. Build the candidate pool from alumni whose role is `ALUMNI` and status is
   `APPROVED` at the recorded snapshot time. Keep the eligibility query or
   snapshot evidence with the restricted study records; the evaluator schema
   itself has no approval-status field and cannot enforce this filter.
2. Assign neutral aliases unrelated to names, employers, profile IDs, or
   ranker position. Keep the alias-to-profile/ID mapping in a separately
   access-controlled location. Do not include names, email addresses, contact
   data, account metadata, or the private mapping in reviewer packets.
3. Present only the question and professional fields: `jobTitle`, `company`,
   `skills`, `bio`, and `interests`. Do not expose `id`, `key`, expected labels,
   ranked lists, scores, strategy, or ranker logs.
4. Randomize the displayed candidate order independently for each question
   and reviewer. Position is for blind presentation only; it is not a grade.
   Keep the randomization seed/order in the restricted audit record so the
   packet can be reconstructed.
5. Keep query intent labels and recommendation expectations out of the
   relevance packet. The final evaluator dataset needs these fields, but they
   must be prepared independently of ranker output and are not ranking
   judgments. Record any independent query-intent annotation on a
   `QUERY_INTENT` row in the audit ledger, not in a profile-pair vote. The
   current held-out runner does not evaluate Java intent classification.

## Per-reviewer response sheet

Reviewer alias: ____________________  Review protocol/version: ____________________

Repeat the following blank section for each question and each displayed
candidate. Do not write the candidate's real identity or database ID here.

### Question

Blind question alias: ____________________

Question text:

____________________________________________________________________________

### Candidate

Neutral candidate alias: ____________________  Display position: ________________

Job title: ____________________  Company: ____________________

Skills:

____________________________________________________________________________

Bio:

____________________________________________________________________________

Interests: _________________________________________________________________

Grade (`2`, `1`, `0`, or `UNRESOLVED`): ____________________

Concise evidence/rationale (required for grades 1 and 2):

____________________________________________________________________________

Reason unresolved, if applicable:

____________________________________________________________________________

## Adjudication and no-match handling

Keep each reviewer's original vote unchanged in the separate review ledger.
After independent review, list disagreements for discussion. Record the final
adjudicated grade, adjudicator alias, and concise rationale in the ledger.
If evidence remains insufficient, leave the pair unresolved and do not
register the evaluation.

For a question with no positively graded candidates, record a concise
question-level no-match explanation in the ledger. The evaluator's `rationale`
field is reserved for positive profile IDs and must be empty for a no-match
question; putting the no-match explanation there would fail validation.

## Final evaluator dataset contract

Keep the audit ledger separate from `dataset.json`. The current evaluator
requires:

- Root `schemaVersion` equal to `1`, a nonempty `profiles` list, and a nonempty
  `questions` list.
- Every profile to contain `id`, `key`, `jobTitle`, `company`, `skills`, `bio`,
  and `interests`. `skills` must be a list of strings. The ranking request also
  uses integer candidate IDs and the API's profile-field limits.
- Every question to contain `id`, `category`, `question`, `expectedIntent`,
  `expectedRecommendations`, `relevance`, and `rationale`. Question IDs must
  be unique. `question` may be a string or the evaluator's `{ "repeat":
  "...", "count": N }` specification.
- `category` coverage across the dataset must equal this complete set:
  `career_core`, `paraphrase`, `misspelling`, `multi_skill`,
  `alumni_question`, `weak_match`, `misleading_context`, `no_match`,
  `general_question`, `unsupported`, `greeting`, `ambiguous`, and `validation`.
- `expectedIntent` must be one of `CAREER_MENTOR_SEARCH`, `ALUMNI_QUESTION`,
  `GENERAL_KNOWLEDGE`, `GREETING_OR_SMALLTALK`, `UNSUPPORTED_OR_UNCLEAR`, or
  `VALIDATION_ERROR`.
- For every question, `relevance` must map every profile ID (as a string) to
  an explicit integer grade `0`, `1`, or `2`. Missing pairs and `UNRESOLVED`
  are rejected by held-out validation; do not fill them with zero.
- `rationale` must be a map whose keys are exactly the positive (`1` or `2`)
  profile IDs: one rationale per positive and none for zero grades. The
  evaluator checks the key set but does not check rationale content/type.
- `expectedRecommendations` must equal true exactly when the intent is
  `CAREER_MENTOR_SEARCH` or `ALUMNI_QUESTION` and at least one profile has a
  positive grade; otherwise it must equal false.

For each `QUERY_INTENT` ledger row, record the initial independent intent vote
and final adjudicated intent from the allowed values above, with a concise
question-based rationale. Set `expectedRecommendations` mechanically from the
evaluator rule after profile relevance is adjudicated; do not use any ranking
result to set it.

The evaluator checks that the category set is complete, but it does not check
for 48 questions. Confirm the intended 48-query count separately before
registration. It also does not validate reviewer identities, qualifications,
independence, per-reviewer vote coverage, or the adjudication ledger; provenance
and records must be audited by the custodian.
