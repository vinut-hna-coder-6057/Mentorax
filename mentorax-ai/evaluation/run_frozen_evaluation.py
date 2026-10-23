"""Run the frozen semantic ranker once against independently frozen held-out labels."""
import argparse
import json
from pathlib import Path
import sys

from freeze_experiment import (
    FreezeError, HELDOUT_DATASET, MANIFEST_PATH, ROOT, _load_json, _relative,
    sha256_file, verify_manifest,
)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--freeze", type=Path, default=MANIFEST_PATH)
    parser.add_argument("--dataset", type=Path, default=HELDOUT_DATASET)
    parser.add_argument("--output", type=Path,
                        default=ROOT / "artifacts" / "mentor-discovery" / "semantic-context-v1" / "heldout-report.json")
    args = parser.parse_args()
    manifest = verify_manifest(args.freeze, require_heldout=True)
    expected_dataset = (ROOT / manifest["heldout"]["datasetPath"]).resolve()
    dataset_path = args.dataset.resolve()
    if dataset_path != expected_dataset:
        raise FreezeError("Held-out dataset path does not match the frozen manifest")
    if sha256_file(dataset_path) != manifest["heldout"]["proxyLabelSha256"]:
        raise FreezeError("Held-out dataset hash does not match the frozen manifest")

    sys.path.insert(0, str(ROOT / "mentorax-ai" / "evaluation"))
    from evaluator import summarize, validate_dataset
    from run_evaluation import python_evaluation

    dataset = _load_json(dataset_path)
    validate_dataset(dataset)
    result = python_evaluation(dataset, "semantic")
    if result.get("status") != "evaluated":
        raise FreezeError("Frozen semantic evaluation did not complete")
    report = {
        "experimentSchemaVersion": manifest["experimentSchemaVersion"],
        "rankerId": manifest["rankerId"],
        "condition": "semantic profile-context ranker",
        "datasetPath": _relative(dataset_path),
        "datasetSha256": sha256_file(dataset_path),
        "labelProvenanceSha256": manifest["heldout"]["labelProvenanceSha256"],
        "status": "evaluated",
        "coverage": {
            "profileCount": len(dataset["profiles"]),
            "questionCount": len(dataset["questions"]),
            "positiveLabelQueries": sum(any(int(grade) > 0 for grade in q["relevance"].values())
                                          for q in dataset["questions"]),
            "unresolvedJudgments": 0,
        },
        "metrics": summarize(dataset["questions"], result["rankedIdsByCase"]),
        "runtime": result["runtime"],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Held-out report: {args.output}")
    print(f"Verified ranker ID: {manifest['rankerId']}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except FreezeError as error:
        raise SystemExit(f"FROZEN EVALUATION BLOCKED: {error}") from None
