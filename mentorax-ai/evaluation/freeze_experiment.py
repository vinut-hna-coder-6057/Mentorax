"""Create and verify an immutable, development-only Mentorax ranker freeze."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
AI_ROOT = ROOT / "mentorax-ai"
ARTIFACT_ROOT = ROOT / "artifacts" / "mentor-discovery" / "semantic-context-v1"
MANIFEST_PATH = ARTIFACT_ROOT / "freeze.json"
DEFAULT_REPORT = ARTIFACT_ROOT / "development-report.json"
HELDOUT_DATASET = AI_ROOT / "evaluation" / "heldout" / "dataset.json"
HELDOUT_PROVENANCE = AI_ROOT / "evaluation" / "heldout" / "label-provenance.json"

SOURCE_PATHS = (
    "mentorax-ai/app.py",
    "mentorax-ai/semantic_ranker.py",
    "mentorax-ai/evaluation/evaluator.py",
    "mentorax-ai/evaluation/run_evaluation.py",
    "mentorax-ai/evaluation/run_frozen_evaluation.py",
    "mentorax-ai/evaluation/freeze_experiment.py",
    "mentorax-ai/requirements.txt",
    "mentorax-ai/requirements-semantic.txt",
    "mentorax-ai/requirements-test.txt",
    "alumni-connect/src/main/java/com/alumni/alumni_connect/service/MentorDiscoveryService.java",
    "alumni-connect/src/main/java/com/alumni/alumni_connect/service/QuestionIntentClassifier.java",
    "alumni-connect/src/main/java/com/alumni/alumni_connect/repository/UserRepository.java",
    "alumni-connect/src/main/java/com/alumni/alumni_connect/repository/UserSkillRepository.java",
    "alumni-connect/src/main/resources/application.properties",
)


class FreezeError(ValueError):
    """A required frozen experiment input is absent, changed, or untrusted."""


def _canonical_bytes(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def _sha256_bytes(value):
    return hashlib.sha256(value).hexdigest()


def sha256_file(path):
    return _sha256_bytes(Path(path).read_bytes())


def _relative(path):
    return Path(path).resolve().relative_to(ROOT).as_posix()


def _load_json(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise FreezeError(f"Cannot read required JSON input: {_relative(path)}") from error


def ranker_configuration():
    """Concrete scoring, enrichment, gating, and runtime settings for this experiment."""
    sys.path.insert(0, str(AI_ROOT))
    from semantic_ranker import DEFAULT_MODEL_NAME, DEFAULT_MODEL_REVISION

    model_revision = os.getenv("MENTOR_EMBEDDING_MODEL_REVISION", DEFAULT_MODEL_REVISION)
    if not re.fullmatch(r"[0-9a-fA-F]{40}", model_revision):
        raise FreezeError("A full 40-character model commit revision is required for a reproducible freeze")

    return {
        "experimentStrategy": "semantic",
        "pythonConfiguredDefaultStrategy": os.getenv("MENTOR_RANKING_STRATEGY", "tfidf").strip().lower(),
        "python": {
            "modelId": os.getenv("MENTOR_EMBEDDING_MODEL", DEFAULT_MODEL_NAME),
            "resolvedRevision": model_revision,
            "device": "cpu",
            "encode": {"normalize_embeddings": False, "convert_to_numpy": True,
                       "show_progress_bar": False},
            "textNormalization": {
                "query": "Pydantic strips surrounding whitespace; otherwise original casing/punctuation",
                "profile": "skills joined with spaces, then jobTitle, company, bio, interests; empty values omitted; final strip",
                "embeddingInput": "exact profile string after strip; cache key SHA-256 of stripped UTF-8 text",
                "noLowercasingOrContextualQueryRewrite": True,
            },
            "similarity": "float64 cosine(query, profile), finite/nonzero vectors required, clamped to [0,1]",
            "ordering": "descending score, then ascending candidate integer ID",
            "profileCacheMaxEntries": 512,
        },
        "contextEnrichment": {
            "side": "candidate profile only",
            "fieldsInOrder": ["skills", "jobTitle", "company", "bio", "interests"],
            "queryContextSnapshot": "none; query text is passed through after surrounding trim",
        },
        "javaProductionGate": {
            "approvedCandidateFilter": "role=ALUMNI AND status=APPROVED, keyset ordered by id",
            "pythonSemanticCandidatePrefilter": "none; all candidates in each approved page are sent to Python",
            "recommendationAcceptance": "semantic score >= AI_MENTOR_MIN_SEMANTIC_RELEVANCE AND substantive profile field; OR positive lexical evidence >= AI_MENTOR_MIN_RELEVANCE",
            "configuration": {
                "AI_MENTOR_ENABLED": os.getenv("AI_MENTOR_ENABLED", "false").strip().lower(),
                "AI_MENTOR_MIN_RELEVANCE": float(os.getenv("AI_MENTOR_MIN_RELEVANCE", "0.0")),
                "AI_MENTOR_MIN_SEMANTIC_RELEVANCE": float(os.getenv("AI_MENTOR_MIN_SEMANTIC_RELEVANCE", "0.72")),
                "AI_MENTOR_RANKING_STRATEGY": os.getenv("AI_MENTOR_RANKING_STRATEGY", "tfidf").strip().lower(),
            },
            "pageSize": 500,
            "maxPagesPerPythonBatch": 2,
            "topK": 10,
            "evidenceScore": "min(1.0, (unique direct query-term overlap + 0.2 * unique bio-term overlap) / unique normalized query-term count); direct fields are skills, jobTitle, interests",
            "normalization": "NFKC, lowercase, Unicode letters/numbers retained, punctuation mapped to spaces; stop-term removal, typo/synonym expansions, and canonical suffix rules are pinned by MentorDiscoveryService.java hash",
            "substantiveFields": ["jobTitle", "skills", "interests", "bio"],
            "inputLimits": {"goalCharacters": 500, "candidatePageSize": 500,
                            "skillsPerCandidate": 50, "batchPages": 5,
                            "pythonRequestBodyBytes": 8388608, "javaBatchBodyBytes": 7340032},
            "fallback": "Java evidence ordering on Python unavailable/invalid; cross-page Python semantic ordering only when all pages succeed",
            "returnedRelevanceDisplay": "Java lexical profile evidence score; not raw semantic cosine",
        },
        "hybrid": {
            "method": "average of within-request rank percentiles",
            "tfidfWeight": float(os.getenv("MENTOR_HYBRID_TFIDF_WEIGHT", "0.5")),
            "tieBreak": "descending final score, then ascending candidate integer ID",
        },
        "tfidfBaseline": {
            "vectorizer": "scikit-learn TfidfVectorizer(ngram_range=(1,2), stop_words='english')",
            "similarity": "cosine_similarity(query row, profile rows)",
            "ordering": "descending score, then ascending candidate integer ID",
        },
        "evaluationScope": {
            "comparedConditions": ["Python TF-IDF baseline", "Python semantic profile-context ranker"],
            "inputUniverse": "all synthetic profiles in development corpus; Java approved-alumni/evidence gates and intent routing excluded from Python component metrics",
            "pairedQuerySet": "development questions expected to request recommendations and containing at least one positive relevance grade",
        },
    }


def _package_versions():
    result = {}
    for package in ("numpy", "scikit-learn", "sentence-transformers", "transformers", "torch", "fastapi", "pydantic"):
        try:
            result[package] = importlib.metadata.version(package)
        except importlib.metadata.PackageNotFoundError:
            result[package] = None
    return result


def _runtime_versions():
    return {"python": sys.version.split()[0], "packages": _package_versions()}


def _materialized_assets(dataset_path, provenance_path):
    data = _load_json(dataset_path)
    sys.path.insert(0, str(AI_ROOT))
    sys.path.insert(0, str(AI_ROOT / "evaluation"))
    from app import Candidate, profile_text
    from evaluator import validate_dataset

    validate_dataset(data)
    source_digest = sha256_file(dataset_path)
    candidates = {
        "schemaVersion": 1,
        "sourceDatasetSha256": source_digest,
        "profiles": data["profiles"],
    }
    labels = {
        "schemaVersion": 1,
        "sourceDatasetSha256": source_digest,
        "questions": [{key: case[key] for key in (
            "id", "category", "expectedIntent", "expectedRecommendations", "relevance", "rationale")}
            for case in data["questions"]],
    }
    snapshots = {
        "schemaVersion": 1,
        "sourceDatasetSha256": source_digest,
        "contextDefinition": "mentorax-ai/app.py::profile_text",
        "profiles": [{"candidateId": profile["id"],
                      "profileText": profile_text(Candidate(
                          id=profile["id"], jobTitle=profile["jobTitle"], company=profile["company"],
                          skills=profile["skills"], bio=profile["bio"], interests=profile["interests"]))}
                     for profile in data["profiles"]],
    }
    provenance = _load_json(provenance_path)
    _validate_development_provenance(provenance, source_digest)
    return data, candidates, labels, snapshots, provenance


def _validate_development_provenance(provenance, dataset_digest):
    required = {
        "schemaVersion": 1,
        "sourceDatasetSha256": dataset_digest,
        "sourceDatasetPath": "mentorax-ai/evaluation/dataset.json",
        "annotationStatus": "existing_synthetic_grades_provenance_incomplete",
        "labelGeneratorVersion": None,
        "annotatorIdentityRecorded": False,
        "unresolvedJudgmentsRepresented": False,
        "omittedRelevanceSemantics": "The existing evaluation specification treats omitted grades as irrelevant; the freeze preserves sparse maps without filling or relabeling them.",
    }
    for key, expected in required.items():
        if provenance.get(key) != expected:
            raise FreezeError(f"Development label provenance mismatch: {key}")


def validate_heldout_labels(dataset, provenance, dataset_digest):
    """Require reviewed, explicit pairwise judgments; unresolved pairs cannot become zeros."""
    if provenance.get("status") != "independently_reviewed":
        raise FreezeError("Held-out label provenance is not independently reviewed")
    reviewers = provenance.get("reviewers")
    if not isinstance(reviewers, list) or len(reviewers) < 2:
        raise FreezeError("Held-out provenance must record at least two independent reviewers")
    if provenance.get("datasetSha256") != dataset_digest:
        raise FreezeError("Held-out provenance does not bind to the frozen dataset")
    if provenance.get("disagreementsResolved") is not True:
        raise FreezeError("Held-out reviewer disagreements are not recorded as resolved")
    if provenance.get("unresolvedJudgmentCount") != 0:
        raise FreezeError("Held-out evaluation is blocked while judgments remain unresolved")
    sys.path.insert(0, str(AI_ROOT / "evaluation"))
    from evaluator import validate_dataset
    validate_dataset(dataset)
    profile_ids = {str(profile["id"]) for profile in dataset["profiles"]}
    for question in dataset["questions"]:
        labels = question["relevance"]
        if set(labels) != profile_ids or any(
                not isinstance(grade, int) or isinstance(grade, bool) or grade not in (0, 1, 2)
                for grade in labels.values()):
            raise FreezeError(
                f"Held-out question {question['id']} must have explicit resolved grades for every profile")


def _write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def _summary_metrics(report_path, dataset_digest):
    report = _load_json(report_path)
    if report.get("dataset", {}).get("sha256") != dataset_digest:
        raise FreezeError("Development report dataset hash does not match the frozen dataset")
    if report.get("pythonTfidf", {}).get("status") != "evaluated":
        raise FreezeError("Development TF-IDF baseline report is not evaluated")
    if report.get("pythonSemantic", {}).get("status") != "evaluated":
        raise FreezeError("Development semantic report is not evaluated")
    pairs = []
    for case in report.get("perQuestion", []):
        if not case.get("expectedIntent") in {"CAREER_MENTOR_SEARCH", "ALUMNI_QUESTION"}:
            continue
        if not any(int(grade) > 0 for grade in case.get("relevance", {}).values()):
            continue
        pairs.append(case)
    if not pairs:
        raise FreezeError("No paired, positively labeled development queries are available")
    metrics = ("precisionAt3", "recallAt5", "mrrAt5", "ndcgAt5")
    conditions = {}
    paired_deltas = {}
    for name, ranking_key in (("baseline", "pythonRankedIds"), ("contextAware", "pythonSemanticRankedIds")):
        # Use same metric implementation and exact same query IDs as the paired comparison.
        sys.path.insert(0, str(AI_ROOT / "evaluation"))
        from evaluator import ranking_metrics
        per_query = {case["id"]: ranking_metrics(case[ranking_key], case["relevance"]) for case in pairs}
        conditions[name] = {
            metric: sum(row[metric] for row in per_query.values()) / len(per_query)
            for metric in metrics
        }
        conditions[name]["caseCount"] = len(pairs)
    for metric in metrics:
        deltas = []
        for case in pairs:
            from evaluator import ranking_metrics
            base = ranking_metrics(case["pythonRankedIds"], case["relevance"])[metric]
            context = ranking_metrics(case["pythonSemanticRankedIds"], case["relevance"])[metric]
            deltas.append(context - base)
        paired_deltas[metric] = {"mean": sum(deltas) / len(deltas), "caseCount": len(deltas)}
    return {
        "reportPath": _relative(report_path),
        "reportSha256": sha256_file(report_path),
        "metricDefinitions": {"precisionAt3": "mean Precision@3", "recallAt5": "mean Recall@5",
                              "mrrAt5": "mean reciprocal rank truncated at 5",
                              "ndcgAt5": "mean graded NDCG@5"},
        "pairedQueryCount": len(pairs),
        "baseline": conditions["baseline"],
        "contextAware": conditions["contextAware"],
        "pairedDeltas": paired_deltas,
        "excluded": {
            "non_recommendation_intent_or_expectation": sum(
                case.get("expectedIntent") not in {"CAREER_MENTOR_SEARCH", "ALUMNI_QUESTION"}
                for case in report.get("perQuestion", [])),
            "no_positive_relevance_grade": sum(
                case.get("expectedIntent") in {"CAREER_MENTOR_SEARCH", "ALUMNI_QUESTION"}
                and not any(int(grade) > 0 for grade in case.get("relevance", {}).values())
                for case in report.get("perQuestion", [])),
            "unresolved_labels": "not represented by the current development schema; not inferred or converted",
        },
    }


def create_manifest(manifest_path=MANIFEST_PATH, report_path=DEFAULT_REPORT,
                    created_at=None, dataset_path=None, provenance_path=None):
    manifest_path = Path(manifest_path)
    artifact_root = manifest_path.parent
    dataset_path = Path(dataset_path or AI_ROOT / "evaluation" / "dataset.json")
    provenance_path = Path(provenance_path or AI_ROOT / "evaluation" / "label-provenance.json")
    data, candidates, labels, snapshots, provenance = _materialized_assets(dataset_path, provenance_path)
    _write_json(artifact_root / "candidate-manifest.json", candidates)
    _write_json(artifact_root / "development-proxy-labels.json", labels)
    _write_json(artifact_root / "development-context-snapshots.json", snapshots)

    asset_paths = {
        "developmentDataset": dataset_path,
        "developmentProxyLabels": artifact_root / "development-proxy-labels.json",
        "candidateManifest": artifact_root / "candidate-manifest.json",
        "developmentContextSnapshots": artifact_root / "development-context-snapshots.json",
        "developmentLabelProvenance": provenance_path,
        "heldoutTemplate": AI_ROOT / "evaluation" / "heldout" / "dataset.template.json",
    }
    assets = {key: {"path": _relative(path), "sha256": sha256_file(path)}
              for key, path in asset_paths.items()}
    sources = {path: sha256_file(ROOT / path) for path in SOURCE_PATHS}
    config = ranker_configuration()
    runtime_versions = _runtime_versions()
    ranker_material = {"configuration": config, "sourceSha256": sources,
                       "assetSha256": {key: value["sha256"] for key, value in assets.items()},
                       "runtimeVersions": runtime_versions}
    ranker_id = "mentorax-semantic-profile-context-v1-" + _sha256_bytes(_canonical_bytes(ranker_material))[:20]
    labels_by_question = [case.get("relevance", {}) for case in data["questions"]]
    explicit_grade_count = sum(len(item) for item in labels_by_question)
    total_pairs = len(data["questions"]) * len(data["profiles"])
    report = _summary_metrics(report_path, assets["developmentDataset"]["sha256"])
    freeze = {
        "experimentSchemaVersion": 1,
        "rankerId": ranker_id,
        "createdAtUtc": created_at or datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "status": "development_freeze_heldout_pending",
        "configuration": config,
        "sourceSha256": sources,
        "assets": assets,
        "model": {"id": config["python"]["modelId"],
                  "configuredRevision": config["python"]["resolvedRevision"],
                  "resolvedRevision": config["python"]["resolvedRevision"],
                  "revisionSource": "pinned model revision in semantic_ranker.py / environment override frozen in configuration"},
        "versions": {"labelGeneratorVersion": provenance.get("labelGeneratorVersion"),
                     "labelProvenanceStatus": provenance["annotationStatus"],
                     **runtime_versions},
        "developmentReport": report,
        "labelCoverage": {
            "profiles": len(data["profiles"]), "questions": len(data["questions"]),
            "possibleQuestionProfilePairs": total_pairs,
            "explicitlyPresentGrades": explicit_grade_count,
            "sparseOmittedPairs": total_pairs - explicit_grade_count,
            "sparseOmissionMeaning": "Existing evaluation spec treats omitted relevance as irrelevant; no new labels were written into source data.",
            "unresolvedJudgmentsRepresented": provenance["unresolvedJudgmentsRepresented"],
            "provenanceLimitations": ["annotator identity not recorded", "label-generator version not recorded",
                                      "no unresolved category in source schema"],
        },
        "heldout": {
            "status": "not_provided_blind_review_pending",
            "datasetPath": _relative(HELDOUT_DATASET),
            "proxyLabelSha256": None,
            "labelProvenancePath": _relative(HELDOUT_PROVENANCE),
            "labelProvenanceSha256": None,
            "templateSha256": assets["heldoutTemplate"]["sha256"],
            "evaluationAllowed": False,
        },
    }
    _write_json(manifest_path, freeze)
    return freeze


def verify_manifest(manifest_path=MANIFEST_PATH, require_heldout=False):
    manifest_path = Path(manifest_path)
    if not manifest_path.is_file():
        raise FreezeError("Freeze manifest is missing")
    manifest = _load_json(manifest_path)
    if manifest.get("experimentSchemaVersion") != 1 or not isinstance(manifest.get("configuration"), dict):
        raise FreezeError("Freeze manifest has an invalid schema")
    if not str(manifest.get("rankerId", "")).startswith("mentorax-semantic-profile-context-v1-"):
        raise FreezeError("Freeze manifest ranker ID is invalid")
    if manifest.get("configuration") != ranker_configuration():
        raise FreezeError("Current ranker configuration does not match the freeze")
    if {key: manifest.get("versions", {}).get(key) for key in ("python", "packages")} != _runtime_versions():
        raise FreezeError("Current Python/package versions do not match the freeze")
    if set(manifest.get("sourceSha256", {})) != set(SOURCE_PATHS):
        raise FreezeError("Freeze manifest is missing required source hashes")
    for path, expected in manifest.get("sourceSha256", {}).items():
        source = ROOT / path
        if not source.is_file() or sha256_file(source) != expected:
            raise FreezeError(f"Frozen source changed or missing: {path}")
    assets = manifest.get("assets")
    required_assets = {"developmentDataset", "developmentProxyLabels", "candidateManifest",
                       "developmentContextSnapshots", "developmentLabelProvenance", "heldoutTemplate"}
    if not isinstance(assets, dict) or not required_assets.issubset(assets):
        raise FreezeError("Freeze manifest is missing required frozen input hashes")
    for key, entry in assets.items():
        path = ROOT / entry.get("path", "")
        if not path.is_file() or sha256_file(path) != entry.get("sha256"):
            raise FreezeError(f"Frozen input changed or missing: {key}")
    provenance = _load_json(ROOT / assets["developmentLabelProvenance"]["path"])
    _validate_development_provenance(provenance, assets["developmentDataset"]["sha256"])
    _, expected_candidates, expected_labels, expected_snapshots, _ = _materialized_assets(
        ROOT / assets["developmentDataset"]["path"],
        ROOT / assets["developmentLabelProvenance"]["path"])
    for key, expected_value in (("candidateManifest", expected_candidates),
                                ("developmentProxyLabels", expected_labels),
                                ("developmentContextSnapshots", expected_snapshots)):
        actual = _load_json(ROOT / assets[key]["path"])
        if _sha256_bytes(_canonical_bytes(actual)) != _sha256_bytes(_canonical_bytes(expected_value)):
            raise FreezeError(f"Frozen derived input does not match its source: {key}")
    report_ref = manifest.get("developmentReport", {})
    report_path = ROOT / report_ref.get("reportPath", "")
    if not report_path.is_file() or sha256_file(report_path) != report_ref.get("reportSha256"):
        raise FreezeError("Frozen development report is missing or changed")
    if _summary_metrics(report_path, assets["developmentDataset"]["sha256"]) != report_ref:
        raise FreezeError("Frozen development metrics do not match the development report")
    # Recompute ranker ID so a manually retained ID cannot conceal changed data.
    material = {"configuration": manifest["configuration"], "sourceSha256": manifest["sourceSha256"],
                "assetSha256": {key: value["sha256"] for key, value in assets.items()},
                "runtimeVersions": {key: manifest["versions"][key] for key in ("python", "packages")}}
    expected_id = "mentorax-semantic-profile-context-v1-" + _sha256_bytes(_canonical_bytes(material))[:20]
    if manifest["rankerId"] != expected_id:
        raise FreezeError("Ranker ID does not match frozen configuration, sources, and inputs")
    if require_heldout:
        heldout = manifest.get("heldout", {})
        if heldout.get("status") != "reviewed_labels_frozen" or heldout.get("evaluationAllowed") is not True:
            raise FreezeError("Held-out evaluation is blocked: reviewed labels and provenance are not frozen")
        for key, path_key in (("proxyLabelSha256", "datasetPath"), ("labelProvenanceSha256", "labelProvenancePath")):
            path = ROOT / heldout.get(path_key, "")
            if not path.is_file() or not heldout.get(key) or sha256_file(path) != heldout[key]:
                raise FreezeError(f"Held-out frozen input missing or changed: {path_key}")
        heldout_provenance = _load_json(ROOT / heldout["labelProvenancePath"])
        heldout_dataset = _load_json(ROOT / heldout["datasetPath"])
        validate_heldout_labels(heldout_dataset, heldout_provenance,
                                heldout["proxyLabelSha256"])
    return manifest


def main():
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    create = subparsers.add_parser("create")
    create.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    create.add_argument("--manifest", type=Path, default=MANIFEST_PATH)
    verify = subparsers.add_parser("verify")
    verify.add_argument("--manifest", type=Path, default=MANIFEST_PATH)
    verify.add_argument("--require-heldout", action="store_true")
    register = subparsers.add_parser("register-heldout")
    register.add_argument("--manifest", type=Path, default=MANIFEST_PATH)
    register.add_argument("--dataset", type=Path, default=HELDOUT_DATASET)
    register.add_argument("--provenance", type=Path, default=HELDOUT_PROVENANCE)
    args = parser.parse_args()
    if args.command == "create":
        manifest = create_manifest(args.manifest, args.report)
        print(f"Created ranker freeze: {manifest['rankerId']}")
        print(f"Status: {manifest['status']}")
    elif args.command == "verify":
        manifest = verify_manifest(args.manifest, args.require_heldout)
        print(f"Verified ranker freeze: {manifest['rankerId']}")
        if not args.require_heldout:
            print("Held-out evaluation remains blocked until independently reviewed labels and provenance are frozen.")
    else:
        manifest = verify_manifest(args.manifest)
        dataset = _load_json(args.dataset)
        dataset_digest = sha256_file(args.dataset)
        provenance = _load_json(args.provenance)
        validate_heldout_labels(dataset, provenance, dataset_digest)
        manifest["heldout"] = {
            "status": "reviewed_labels_frozen",
            "datasetPath": _relative(args.dataset),
            "proxyLabelSha256": dataset_digest,
            "labelProvenancePath": _relative(args.provenance),
            "labelProvenanceSha256": sha256_file(args.provenance),
            "templateSha256": manifest["assets"]["heldoutTemplate"]["sha256"],
            "evaluationAllowed": True,
        }
        _write_json(args.manifest, manifest)
        verify_manifest(args.manifest, require_heldout=True)
        print(f"Registered reviewed held-out labels for ranker: {manifest['rankerId']}")


if __name__ == "__main__":
    main()
