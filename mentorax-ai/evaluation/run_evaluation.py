"""Run Python TF-IDF component and Java production fallback evaluations."""
import argparse
import ctypes
import json
import os
import subprocess
import sys
import time
from pathlib import Path

from evaluator import materialize_question, ranking_metrics, summarize, validate_dataset, validate_ranking_result

ROOT = Path(__file__).resolve().parents[2]
AI_ROOT = ROOT / "mentorax-ai"


def load_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def process_rss_bytes():
    try:
        if os.name == "nt":
            class ProcessMemoryCounters(ctypes.Structure):
                _fields_ = [("cb", ctypes.c_ulong), ("PageFaultCount", ctypes.c_ulong),
                            ("PeakWorkingSetSize", ctypes.c_size_t), ("WorkingSetSize", ctypes.c_size_t),
                            ("QuotaPeakPagedPoolUsage", ctypes.c_size_t), ("QuotaPagedPoolUsage", ctypes.c_size_t),
                            ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t), ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                            ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t)]

            counters = ProcessMemoryCounters()
            counters.cb = ctypes.sizeof(counters)
            handle = ctypes.windll.kernel32.GetCurrentProcess()
            if ctypes.windll.psapi.GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb):
                return int(counters.WorkingSetSize)
        elif Path("/proc/self/statm").exists():
            resident_pages = int(Path("/proc/self/statm").read_text().split()[1])
            return resident_pages * os.sysconf("SC_PAGE_SIZE")
    except (AttributeError, OSError, ValueError, IndexError):
        return None
    return None


def python_evaluation(dataset, strategy):
    sys.path.insert(0, str(AI_ROOT))
    from app import Candidate, RankingRequest, rank, semantic_ranker

    searchable = [case for case in dataset["questions"] if case["expectedRecommendations"]]
    profiles = dataset["profiles"]
    candidates = [Candidate(id=p["id"], jobTitle=p["jobTitle"], company=p["company"],
                            skills=p["skills"], bio=p["bio"], interests=p["interests"])
                  for p in profiles]
    ranked = {}
    errors = []
    durations_ms = []
    rss_before = process_rss_bytes()
    started = time.perf_counter()
    for case in searchable:
        try:
            question_started = time.perf_counter()
            result = rank(RankingRequest(goal=materialize_question(case["question"]),
                                         candidates=candidates, strategy=strategy))
            validate_ranking_result(result, [profile["id"] for profile in profiles])
            ranked[case["id"]] = result["rankedIds"]
            durations_ms.append((time.perf_counter() - question_started) * 1000)
        except Exception as error:
            from fastapi import HTTPException

            if isinstance(error, HTTPException) and error.status_code == 503 and strategy in {"semantic", "hybrid"}:
                return {"status": "NOT_EVALUATED", "strategy": strategy,
                        "reason": error.detail, "errors": [{"caseId": case["id"], "errorType": type(error).__name__}],
                        "metrics": None, "careerQualityMetrics": None, "rankedIdsByCase": {}}
            errors.append({"caseId": case["id"], "errorType": type(error).__name__})
            break
    repeated = {}
    if not errors:
        for case in searchable:
            result = rank(RankingRequest(goal=materialize_question(case["question"]),
                                         candidates=candidates, strategy=strategy))
            validate_ranking_result(result, [profile["id"] for profile in profiles])
            repeated[case["id"]] = result["rankedIds"]
    deterministic = not errors and ranked == repeated
    summary = summarize(searchable, ranked) if ranked else None
    quality_cases = [case for case in searchable if any(int(value) > 0 for value in case["relevance"].values())]
    quality_summary = summarize(quality_cases, ranked) if ranked else None
    elapsed = (time.perf_counter() - started) * 1000
    rss_after = process_rss_bytes()
    return {"status": "evaluated" if not errors else "failed", "strategy": strategy,
            "implementation": "mentorax-ai/app.py rank()",
            "scope": f"Isolated Python {strategy} ranker over all synthetic profiles; no Java evidence gate or intent routing",
            "caseCount": len(searchable), "deterministicRepeatedRun": deterministic,
            "errors": errors, "metrics": summary, "careerQualityMetrics": quality_summary,
            "runtime": {"totalRankingMilliseconds": round(elapsed, 3),
                        "meanQuestionLatencyMilliseconds": round(sum(durations_ms) / len(durations_ms), 3) if durations_ms else None,
                        "firstQuestionLatencyMilliseconds": round(durations_ms[0], 3) if durations_ms else None,
                        "modelInitializationSeconds": semantic_ranker.model_load_seconds,
                        "profileEmbeddingCacheEntries": semantic_ranker.cache_entries,
                        "processRssBytesBefore": rss_before, "processRssBytesAfter": rss_after,
                        "processRssDeltaBytes": rss_after - rss_before if rss_before is not None and rss_after is not None else None},
            "rankedIdsByCase": ranked}


def run_java(dataset_path, output_path):
    wrapper = ROOT / "alumni-connect" / "mvnw.cmd"
    command = (f'"{wrapper}" -q "-Dtest=MentorDiscoveryEvaluationTest" '
               f'"-Dmentorax.evaluation.dataset={dataset_path}" '
               f'"-Dmentorax.evaluation.output={output_path}" test')
    completed = subprocess.run(command, cwd=ROOT / "alumni-connect", shell=True,
                               capture_output=True, text=True, check=False)
    if completed.returncode:
        raise RuntimeError("Java evaluation test failed (details in Maven output):\n" + completed.stdout[-4000:] + completed.stderr[-4000:])
    return load_json(output_path)


def java_evaluation(dataset, java_raw, cases_key="cases", mode="disabled"):
    cases_by_id = {case["id"]: case for case in dataset["questions"]}
    mode_cases = java_raw[cases_key]
    rows = {row["id"]: row for row in mode_cases}
    if set(rows) != set(cases_by_id):
        raise ValueError("Java evaluator output case IDs do not match the dataset")
    intents_correct = sum(rows[c["id"]]["predictedIntent"] == c["expectedIntent"] for c in dataset["questions"])
    forbidden = [c for c in dataset["questions"] if not c["expectedRecommendations"]]
    false_recommendations = sum(bool(rows[c["id"]]["recommendedIds"]) for c in forbidden)
    no_match_cases = [c for c in dataset["questions"] if c["category"] == "no_match"]
    no_match_correct = sum(not rows[c["id"]]["recommendedIds"] for c in no_match_cases)
    career_cases = [c for c in dataset["questions"] if c["expectedRecommendations"]]
    rankings = {case_id: row["recommendedIds"] for case_id, row in rows.items()}
    repeated = [row.get("deterministic", False) for row in mode_cases]
    all_deterministic = all(repeated)
    quality_cases = [case for case in career_cases if any(int(value) > 0 for value in case["relevance"].values())]
    routing_by_category = {}
    for category in sorted({case["category"] for case in dataset["questions"]}):
        category_cases = [case for case in dataset["questions"] if case["category"] == category]
        category_correct = sum(rows[case["id"]]["predictedIntent"] == case["expectedIntent"]
                               for case in category_cases)
        category_forbidden = [case for case in category_cases if not case["expectedRecommendations"]]
        category_false = sum(bool(rows[case["id"]]["recommendedIds"]) for case in category_forbidden)
        category_no_match = [case for case in category_cases if case["category"] == "no_match"]
        category_no_match_correct = sum(not rows[case["id"]]["recommendedIds"] for case in category_no_match)
        routing_by_category[category] = {
            "intentAccuracy": {"numerator": category_correct, "denominator": len(category_cases),
                               "value": category_correct / len(category_cases)},
            "falseMentorRecommendations": {"numerator": category_false, "denominator": len(category_forbidden),
                                           "rate": category_false / len(category_forbidden) if category_forbidden else None},
            "noMatchAccuracy": {"numerator": category_no_match_correct, "denominator": len(category_no_match),
                                "value": category_no_match_correct / len(category_no_match) if category_no_match else None},
        }
    return {"status": "evaluated", "implementation": java_raw["implementation"],
            "mode": mode,
            "scope": "Real Java classifier and MentorDiscoveryService with synthetic approved alumni fixtures; "
                     + ("Python ranking disabled" if mode == "disabled" else "Python transport failure injected into RestTemplate"),
            "metrics": summarize(dataset["questions"], rankings),
            "careerQualityMetrics": summarize(quality_cases, rankings),
            "intentAccuracy": {"numerator": intents_correct, "denominator": len(dataset["questions"]),
                               "value": intents_correct / len(dataset["questions"])},
            "falseMentorRecommendations": {"numerator": false_recommendations, "denominator": len(forbidden),
                                           "rate": false_recommendations / len(forbidden) if forbidden else None},
            "noMatchAccuracy": {"numerator": no_match_correct, "denominator": len(no_match_cases),
                                "value": no_match_correct / len(no_match_cases) if no_match_cases else None},
            "routingByCategory": routing_by_category,
            "deterministicRepeatedRun": all_deterministic,
            "cases": [{**case, "predictedIntent": rows[case["id"]]["predictedIntent"],
                       "recommendedIds": rows[case["id"]]["recommendedIds"],
                       "rankingMethod": rows[case["id"]]["rankingMethod"],
                       "fallbackUsed": rows[case["id"]]["fallbackUsed"],
                       "recommendedRelevance": {str(profile_id): case["relevance"].get(str(profile_id), 0)
                                                for profile_id in rows[case["id"]]["recommendedIds"]},
                       "rankingMetrics": ranking_metrics(rows[case["id"]]["recommendedIds"], case["relevance"])}
                      for case in dataset["questions"]]}


def acceptance_report(dataset, python_results, java_result, java_unavailable_result, acceptance):
    checks = []
    if java_result is None:
        checks.append({"criterion": "Java hard correctness suite", "status": "NOT_EVALUATED"})
        checks.append({"criterion": "Python versus Java comparison", "status": "NOT_EVALUATED"})
        modes = []
    else:
        hard = acceptance["hard_requirements"]
        modes = [("Java fallback (Python disabled)", java_result)]
        checks.extend([
            {"criterion": "Java intent accuracy", "status": "PASS" if java_result["intentAccuracy"]["value"] >= hard["java_intent_accuracy"] else "FAIL",
             "actual": java_result["intentAccuracy"], "required": hard["java_intent_accuracy"]},
            {"criterion": "Zero false mentor recommendations (Python disabled)", "status": "PASS" if java_result["falseMentorRecommendations"]["numerator"] == 0 else "FAIL",
             "actual": java_result["falseMentorRecommendations"]},
            {"criterion": "No-match accuracy (Python disabled)", "status": "PASS" if java_result["noMatchAccuracy"]["value"] >= hard["no_match_accuracy"] else "FAIL",
             "actual": java_result["noMatchAccuracy"], "required": hard["no_match_accuracy"]},
            {"criterion": "Java repeated-run determinism", "status": "PASS" if java_result["deterministicRepeatedRun"] else "FAIL"},
        ])
        if java_unavailable_result is not None:
            modes.append(("Java fallback (Python unavailable)", java_unavailable_result))
            checks.extend([
                {"criterion": "Zero false mentor recommendations (Python unavailable)",
                 "status": "PASS" if java_unavailable_result["falseMentorRecommendations"]["numerator"] == 0 else "FAIL",
                 "actual": java_unavailable_result["falseMentorRecommendations"]},
                {"criterion": "No-match accuracy (Python unavailable)",
                 "status": "PASS" if java_unavailable_result["noMatchAccuracy"]["value"] >= hard["no_match_accuracy"] else "FAIL",
                 "actual": java_unavailable_result["noMatchAccuracy"], "required": hard["no_match_accuracy"]},
                {"criterion": "Python-unavailable repeated-run determinism",
                 "status": "PASS" if java_unavailable_result["deterministicRepeatedRun"] else "FAIL"},
            ])
    for strategy, result in python_results.items():
        label = {"tfidf": "Python TF-IDF", "semantic": "Python semantic", "hybrid": "Python hybrid"}[strategy]
        if result["status"] == "NOT_EVALUATED":
            checks.append({"criterion": f"{label} evaluation", "status": "NOT_EVALUATED", "reason": result["reason"]})
            continue
        checks.append({"criterion": f"{label} repeated-run determinism",
                       "status": "PASS" if result["deterministicRepeatedRun"] else "FAIL"})
        if result["status"] != "evaluated":
            checks.append({"criterion": f"{label} component completed", "status": "FAIL", "errors": result["errors"]})
    targets = acceptance["quality_targets"]
    for name, target in targets.items():
        metric_name = {"precision_at_3": "precisionAt3", "recall_at_5": "recallAt5",
                       "ndcg_at_5": "ndcgAt5"}[name]
        for label, mode_result in modes:
            metric = mode_result["careerQualityMetrics"]["overall"].get(metric_name)
            actual = metric["value"] if metric else None
            checks.append({"criterion": f"Quality target {name} ({label})",
                           "status": "WARN" if actual is None or actual < target else "PASS",
                           "actual": actual, "target": target})
        for strategy, result in python_results.items():
            label = {"tfidf": "Python TF-IDF", "semantic": "Python semantic",
                     "hybrid": "Python hybrid"}[strategy]
            if result["status"] != "evaluated":
                checks.append({"criterion": f"Quality target {name} ({label})",
                               "status": "NOT_EVALUATED", "target": target})
                continue
            metric = result["careerQualityMetrics"]["overall"].get(metric_name)
            actual = metric["value"] if metric else None
            checks.append({"criterion": f"Quality target {name} ({label})",
                           "status": "WARN" if actual is None or actual < target else "PASS",
                           "actual": actual, "target": target})
    return checks


def print_summary(report):
    print(f"Mentorax synthetic evaluation: {report['dataset']['questionCount']} cases, {report['dataset']['profileCount']} profiles")
    for mode in ("javaFallback", "javaPythonUnavailableFallback", "pythonTfidf", "pythonSemantic", "pythonHybrid"):
        item = report[mode]
        if item is None:
            print(f"{mode}: NOT EVALUATED")
            continue
        if item["status"] == "NOT_EVALUATED":
            print(f"{mode}: NOT EVALUATED ({item['reason']})")
            continue
        metrics = item["careerQualityMetrics"]["overall"]
        precision = metrics["precisionAt3"]["value"]
        recall = metrics["recallAt5"]["value"]
        ndcg = metrics["ndcgAt5"]["value"]
        print(f"{mode}: status={item['status']} P@3={precision} R@5={recall} NDCG@5={ndcg}")
    for check in report["acceptance"]:
        print(f"{check['status']}: {check['criterion']}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, default=Path(__file__).with_name("dataset.json"))
    parser.add_argument("--acceptance", type=Path, default=Path(__file__).with_name("acceptance.json"))
    parser.add_argument("--output", type=Path, default=ROOT / "alumni-connect" / "target" / "mentorax-evaluation.json")
    parser.add_argument("--skip-java", action="store_true", help="Do not run Java fallback; report it as not evaluated")
    args = parser.parse_args()
    dataset = load_json(args.dataset)
    validate_dataset(dataset)
    acceptance = load_json(args.acceptance)
    python_results = {strategy: python_evaluation(dataset, strategy)
                      for strategy in ("tfidf", "semantic", "hybrid")}
    python_result = python_results["tfidf"]
    java_result = None
    java_unavailable_result = None
    java_raw = None
    if not args.skip_java:
        java_path = args.output.parent / "mentorax-evaluation-java.json"
        java_raw = run_java(args.dataset.resolve(), java_path.resolve())
        java_result = java_evaluation(dataset, java_raw)
        java_unavailable_result = java_evaluation(dataset, java_raw, "pythonUnavailableCases", "python_unavailable")
    java_cases = {case["id"]: case for case in java_result["cases"]} if java_result else {}
    unavailable_cases = {case["id"]: case for case in java_unavailable_result["cases"]} if java_unavailable_result else {}
    per_question = []
    for case in dataset["questions"]:
        python_ids = python_result["rankedIdsByCase"].get(case["id"], [])
        semantic_ids = python_results["semantic"]["rankedIdsByCase"].get(case["id"], [])
        hybrid_ids = python_results["hybrid"]["rankedIdsByCase"].get(case["id"], [])
        java_case = java_cases.get(case["id"])
        unavailable_case = unavailable_cases.get(case["id"])
        per_question.append({"id": case["id"], "category": case["category"],
                             "question": materialize_question(case["question"]),
                             "expectedIntent": case["expectedIntent"],
                             "predictedIntent": java_case["predictedIntent"] if java_case else None,
                             "relevance": case["relevance"], "rationale": case["rationale"],
                             "pythonRankedIds": python_ids,
                             "pythonSemanticRankedIds": semantic_ids,
                             "pythonHybridRankedIds": hybrid_ids,
                             "pythonRankingMetrics": ranking_metrics(python_ids, case["relevance"]) if python_ids else None,
                             "javaRecommendedIds": java_case["recommendedIds"] if java_case else None,
                             "javaUnavailableRecommendedIds": unavailable_case["recommendedIds"] if unavailable_case else None,
                             "javaRecommendedRelevance": java_case["recommendedRelevance"] if java_case else None,
                             "javaRankingMetrics": java_case["rankingMetrics"] if java_case else None,
                             "rankingMethod": java_case["rankingMethod"] if java_case else None,
                             "fallbackUsed": java_case["fallbackUsed"] if java_case else None})
    report = {"schemaVersion": 1,
              "dataset": {"name": dataset.get("description"), "profileCount": len(dataset["profiles"]),
                          "questionCount": len(dataset["questions"]),
                          "categoryCounts": {category: sum(q["category"] == category for q in dataset["questions"])
                                             for category in sorted({q["category"] for q in dataset["questions"]})}},
              "pythonTfidf": python_result, "pythonSemantic": python_results["semantic"],
              "pythonHybrid": python_results["hybrid"], "javaFallback": java_result,
              "perQuestion": per_question,
              "javaPythonUnavailableFallback": java_unavailable_result,
              "livePythonOutageIntegration": {"status": "NOT_EVALUATED", "reason": "The suite injects a transport failure into the Java client; it does not start a live Python service or induce a network outage."},
              "acceptance": acceptance_report(dataset, python_results, java_result,
                                               java_unavailable_result, acceptance)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print_summary(report)
    print(f"JSON report: {args.output}")
    return 1 if any(check["status"] == "FAIL" for check in report["acceptance"]) else 0


if __name__ == "__main__":
    raise SystemExit(main())
