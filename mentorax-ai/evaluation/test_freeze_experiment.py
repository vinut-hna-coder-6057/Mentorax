import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import freeze_experiment as freeze


def sample_report(path):
    cases = [
        {"id": "q1", "expectedIntent": "CAREER_MENTOR_SEARCH", "relevance": {"1": 2},
         "pythonRankedIds": [1, 2], "pythonSemanticRankedIds": [1, 2]},
        {"id": "q2", "expectedIntent": "ALUMNI_QUESTION", "relevance": {"2": 1},
         "pythonRankedIds": [2, 1], "pythonSemanticRankedIds": [1, 2]},
        {"id": "q3", "expectedIntent": "CAREER_MENTOR_SEARCH", "relevance": {},
         "pythonRankedIds": [1, 2], "pythonSemanticRankedIds": [2, 1]},
        {"id": "q4", "expectedIntent": "GENERAL_KNOWLEDGE", "relevance": {},
         "pythonRankedIds": [], "pythonSemanticRankedIds": []},
    ]
    dataset_hash = freeze.sha256_file(freeze.AI_ROOT / "evaluation" / "dataset.json")
    path.write_text(json.dumps({"dataset": {"sha256": dataset_hash},
                                "pythonTfidf": {"status": "evaluated"},
                                "pythonSemantic": {"status": "evaluated"},
                                "perQuestion": cases}), encoding="utf-8")


class ExperimentFreezeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=freeze.ROOT, prefix=".freeze-test-")
        self.directory = Path(self.temp.name)
        self.report = self.directory / "development-report.json"
        sample_report(self.report)
        self.manifest = self.directory / "freeze.json"

    def tearDown(self):
        self.temp.cleanup()

    def create(self):
        return freeze.create_manifest(self.manifest, self.report,
                                      created_at="2026-01-02T03:04:05+00:00")

    def test_manifest_generation_is_stable_and_hashes_verify(self):
        first = self.create()
        first_bytes = self.manifest.read_bytes()
        second = self.create()
        self.assertEqual(first["rankerId"], second["rankerId"])
        self.assertEqual(first_bytes, self.manifest.read_bytes())
        verified = freeze.verify_manifest(self.manifest)
        self.assertEqual(first["rankerId"], verified["rankerId"])
        self.assertEqual("development_freeze_heldout_pending", verified["status"])

    def test_changed_frozen_input_is_rejected(self):
        self.create()
        candidate_file = self.directory / "candidate-manifest.json"
        candidate_file.write_text(candidate_file.read_text(encoding="utf-8") + " ", encoding="utf-8")
        with self.assertRaisesRegex(freeze.FreezeError, "changed or missing: candidateManifest"):
            freeze.verify_manifest(self.manifest)

    def test_changed_runtime_configuration_is_rejected(self):
        self.create()
        current = freeze.ranker_configuration

        def changed_configuration():
            changed = current()
            changed["python"]["modelId"] = "different/model"
            return changed

        with patch.object(freeze, "ranker_configuration", changed_configuration):
            with self.assertRaisesRegex(freeze.FreezeError, "configuration does not match"):
                freeze.verify_manifest(self.manifest)

    def test_changed_runtime_package_versions_are_rejected(self):
        self.create()
        with patch.object(freeze, "_runtime_versions", return_value={
                "python": "3.14.3", "packages": {"numpy": "different"}}):
            with self.assertRaisesRegex(freeze.FreezeError, "Python/package versions"):
                freeze.verify_manifest(self.manifest)

    def test_changed_source_hash_is_rejected(self):
        self.create()
        original_hash = freeze.sha256_file
        changed_path = (freeze.ROOT / freeze.SOURCE_PATHS[0]).resolve()

        def changed_hash(path):
            if Path(path).resolve() == changed_path:
                return "0" * 64
            return original_hash(path)

        with patch.object(freeze, "sha256_file", side_effect=changed_hash):
            with self.assertRaisesRegex(freeze.FreezeError, "Frozen source changed"):
                freeze.verify_manifest(self.manifest)

    def test_missing_and_invalid_manifests_fail_closed(self):
        with self.assertRaisesRegex(freeze.FreezeError, "missing"):
            freeze.verify_manifest(self.directory / "missing.json")
        self.manifest.write_text("{}", encoding="utf-8")
        with self.assertRaisesRegex(freeze.FreezeError, "invalid schema"):
            freeze.verify_manifest(self.manifest)

    def test_proxy_labels_preserve_sparse_source_values_and_provenance_limits(self):
        data, _candidates, labels, _snapshots, provenance = freeze._materialized_assets(
            freeze.AI_ROOT / "evaluation" / "dataset.json",
            freeze.AI_ROOT / "evaluation" / "label-provenance.json")
        self.assertEqual([case["relevance"] for case in data["questions"]],
                         [case["relevance"] for case in labels["questions"]])
        self.assertFalse(provenance["unresolvedJudgmentsRepresented"])
        self.assertIsNone(provenance["labelGeneratorVersion"])
        self.assertNotIn("unresolved", labels["questions"][0])
        corrupted = dict(provenance, annotatorIdentityRecorded=True)
        with self.assertRaisesRegex(freeze.FreezeError, "annotatorIdentityRecorded"):
            freeze._validate_development_provenance(corrupted, provenance["sourceDatasetSha256"])

    def test_heldout_evaluation_is_blocked_until_labels_and_review_provenance_are_frozen(self):
        self.create()
        with self.assertRaisesRegex(freeze.FreezeError, "Held-out evaluation is blocked"):
            freeze.verify_manifest(self.manifest, require_heldout=True)

    def test_heldout_unresolved_or_sparse_labels_cannot_become_irrelevant(self):
        development = json.loads((freeze.AI_ROOT / "evaluation" / "dataset.json").read_text(encoding="utf-8"))
        provenance = {"status": "independently_reviewed", "reviewers": ["reviewer-a", "reviewer-b"],
                      "datasetSha256": freeze.sha256_file(freeze.AI_ROOT / "evaluation" / "dataset.json"),
                      "disagreementsResolved": True, "unresolvedJudgmentCount": 0}
        with self.assertRaisesRegex(freeze.FreezeError, "explicit resolved grades"):
            freeze.validate_heldout_labels(development, provenance, provenance["datasetSha256"])
        provenance["unresolvedJudgmentCount"] = 1
        with self.assertRaisesRegex(freeze.FreezeError, "remain unresolved"):
            freeze.validate_heldout_labels(development, provenance, provenance["datasetSha256"])


if __name__ == "__main__":
    unittest.main()
