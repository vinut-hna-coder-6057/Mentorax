"""Fail-closed evaluation for independently reviewed inbox-assistant examples."""
import argparse
import json
from collections import defaultdict
from pathlib import Path

from inbox_assistant import InboxAssistRequest, analyze

SCHEMA = "mentorax-inbox-assistant-evaluation-v1"
CATEGORIES = {"CAREER_GUIDANCE", "INTERVIEW_PREPARATION", "REFERRAL_JOB_APPLICATION",
              "PROJECT_TECHNICAL_ADVICE", "MENTORSHIP_REQUEST", "OTHER"}


def verify_and_evaluate(path: Path) -> dict:
    try:
        dataset = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError("Dataset is missing or invalid JSON") from exc
    if not isinstance(dataset, dict):
        raise ValueError("Dataset root must be an object")
    provenance = dataset.get("provenance")
    required_provenance = ("datasetName", "sourceDescription", "labelingProtocolVersion",
                           "reviewedAtUtc", "annotators")
    if dataset.get("schemaVersion") != SCHEMA or not isinstance(provenance, dict):
        raise ValueError("Dataset schema or provenance is missing")
    if any(not provenance.get(key) for key in required_provenance):
        raise ValueError("Required review provenance is incomplete")
    if provenance.get("modelOutputHidden") is not True:
        raise ValueError("Independent labels must be collected without model outputs")
    annotators = provenance.get("annotators")
    if not isinstance(annotators, list) or len(annotators) < 2 or any(
            not isinstance(a, dict) or not a.get("id") or not a.get("role")
            or a.get("independent") is not True for a in annotators):
        raise ValueError("At least two independent, provenance-identified reviewers are required")
    annotator_ids = {annotator["id"] for annotator in annotators}
    examples = dataset.get("examples")
    if not isinstance(examples, list) or not examples:
        raise ValueError("Dataset must contain reviewed examples")

    resolved = []
    unresolved = 0
    for example in examples:
        if not isinstance(example, dict) or not example.get("id") or not example.get("content"):
            raise ValueError("Each example needs an ID and message content")
        votes = example.get("judgments")
        if not isinstance(votes, list) or len(votes) < 2:
            raise ValueError("Each example requires at least two explicit reviewer judgments")
        if len({vote.get("reviewerId") for vote in votes if isinstance(vote, dict)}) != len(votes):
            raise ValueError("Each example requires distinct reviewers")
        for vote in votes:
            if not isinstance(vote, dict):
                raise ValueError("Judgments must be objects")
            if vote.get("reviewerId") not in {a["id"] for a in annotators}:
                raise ValueError("Judgment reviewer is missing from provenance")
            if vote.get("category") not in CATEGORIES | {"UNRESOLVED"}:
                raise ValueError("Invalid category judgment")
            if vote.get("priority") not in {"urgent", "normal", "UNRESOLVED"}:
                raise ValueError("Invalid priority judgment")
            if not vote.get("rationale"):
                raise ValueError("Each judgment requires a concise rationale")
        adjudication = example.get("adjudication")
        categories = {vote["category"] for vote in votes}
        priorities = {vote["priority"] for vote in votes}
        if adjudication is not None:
            if (not isinstance(adjudication, dict)
                    or adjudication.get("reviewerId") not in annotator_ids
                    or not adjudication.get("rationale")):
                raise ValueError("Adjudication requires reviewer provenance and rationale")
            category = adjudication.get("category")
            priority = adjudication.get("priority")
            if category not in CATEGORIES | {"UNRESOLVED"} or priority not in {"urgent", "normal", "UNRESOLVED"}:
                raise ValueError("Adjudication contains invalid labels")
        elif len(categories) == 1 and len(priorities) == 1:
            category = next(iter(categories))
            priority = next(iter(priorities))
        else:
            category, priority = "UNRESOLVED", "UNRESOLVED"
        if category == "UNRESOLVED" or priority == "UNRESOLVED":
            unresolved += 1
            continue
        resolved.append((example, category, priority))
    if not resolved:
        raise ValueError("No resolved judgments are available for metrics")

    confusion = defaultdict(lambda: defaultdict(int))
    category_counts = defaultdict(lambda: {"tp": 0, "fp": 0, "fn": 0})
    priority = {"urgent": {"tp": 0, "fp": 0, "fn": 0}, "normal": {"tp": 0, "fp": 0, "fn": 0}}
    abstentions = 0
    for index, (example, actual_category, actual_priority) in enumerate(resolved, start=1):
        prediction = analyze(InboxAssistRequest(messageId=index, content=example["content"]))
        predicted_category = prediction["category"]
        if prediction["uncertain"]:
            abstentions += 1
        confusion[actual_category][predicted_category] += 1
        for label in CATEGORIES:
            counts = category_counts[label]
            counts["tp"] += int(actual_category == label and predicted_category == label)
            counts["fp"] += int(actual_category != label and predicted_category == label)
            counts["fn"] += int(actual_category == label and predicted_category != label)
        predicted_priority = prediction["prioritySuggestion"]
        for label in priority:
            counts = priority[label]
            counts["tp"] += int(actual_priority == label and predicted_priority == label)
            counts["fp"] += int(actual_priority != label and predicted_priority == label)
            counts["fn"] += int(actual_priority == label and predicted_priority != label)

    def scores(counts):
        p = counts["tp"] / (counts["tp"] + counts["fp"]) if counts["tp"] + counts["fp"] else 0.0
        r = counts["tp"] / (counts["tp"] + counts["fn"]) if counts["tp"] + counts["fn"] else 0.0
        return {"precision": p, "recall": r, "f1": 2 * p * r / (p + r) if p + r else 0.0}

    per_category = {label: scores(category_counts[label]) for label in sorted(CATEGORIES)}
    return {
        "evaluationStatus": "MEASURED_ON_REVIEWED_DATA",
        "datasetSha256": __import__("hashlib").sha256(path.read_bytes()).hexdigest(),
        "strategy": "TF-IDF category-description similarity plus deterministic urgency rules",
        "transformer": "NOT_EVALUATED: no local pretrained classifier configured",
        "resolvedCount": len(resolved), "unresolvedCount": unresolved,
        "categoryMetrics": per_category,
        "macroF1": sum(row["f1"] for row in per_category.values()) / len(per_category),
        "confusionMatrix": {actual: dict(predicted) for actual, predicted in confusion.items()},
        "abstentionRate": abstentions / len(resolved),
        "priorityMetrics": {label: scores(counts) for label, counts in priority.items()},
        "priorityFalsePositives": priority["urgent"]["fp"],
        "priorityFalseNegatives": priority["urgent"]["fn"],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", type=Path)
    args = parser.parse_args()
    try:
        result = verify_and_evaluate(args.dataset)
    except ValueError as error:
        parser.exit(2, f"INBOX ASSISTANT EVALUATION BLOCKED: {error}\n")
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
