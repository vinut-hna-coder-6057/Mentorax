import json
import tempfile
import unittest
from pathlib import Path

from inbox_assistant_evaluation import SCHEMA, verify_and_evaluate


class InboxAssistantEvaluationTests(unittest.TestCase):
    def write_dataset(self, directory: str, dataset: dict) -> Path:
        path = Path(directory) / "synthetic-test-only.json"
        path.write_text(json.dumps(dataset), encoding="utf-8")
        return path

    def test_missing_provenance_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_dataset(directory, {"schemaVersion": SCHEMA, "examples": []})
            with self.assertRaisesRegex(ValueError, "provenance"):
                verify_and_evaluate(path)

    def test_unresolved_votes_are_counted_and_not_scored_as_negative(self):
        dataset = {
            "schemaVersion": SCHEMA,
            "provenance": {
                "datasetName": "synthetic unit-test fixture",
                "sourceDescription": "not real message data",
                "labelingProtocolVersion": "test-only",
                "reviewedAtUtc": "test-only",
                "modelOutputHidden": True,
                "annotators": [
                    {"id": "reviewer-a", "role": "test fixture", "independent": True},
                    {"id": "reviewer-b", "role": "test fixture", "independent": True},
                ],
            },
            "examples": [{
                "id": "synthetic-unresolved",
                "content": "Could you advise on my career path?",
                "judgments": [
                    {"reviewerId": "reviewer-a", "category": "CAREER_GUIDANCE", "priority": "normal", "rationale": "test fixture"},
                    {"reviewerId": "reviewer-b", "category": "UNRESOLVED", "priority": "UNRESOLVED", "rationale": "test fixture"},
                ],
            }],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_dataset(directory, dataset)
            with self.assertRaisesRegex(ValueError, "No resolved judgments"):
                verify_and_evaluate(path)

    def test_rejects_labels_collected_with_model_outputs_visible(self):
        dataset = {"schemaVersion": SCHEMA, "provenance": {
            "datasetName": "test", "sourceDescription": "test", "labelingProtocolVersion": "test",
            "reviewedAtUtc": "test", "modelOutputHidden": False,
            "annotators": [{"id": "a", "role": "test", "independent": True},
                           {"id": "b", "role": "test", "independent": True}],
        }, "examples": []}
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_dataset(directory, dataset)
            with self.assertRaisesRegex(ValueError, "without model outputs"):
                verify_and_evaluate(path)


if __name__ == "__main__":
    unittest.main()
