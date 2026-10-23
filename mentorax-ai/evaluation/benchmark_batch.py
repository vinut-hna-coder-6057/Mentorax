"""In-process HTTP benchmark for bounded ranking-page request batching.

This measures Python request handling only. It excludes database, network, and model
startup costs, and must not be presented as a deployment latency benchmark.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
import statistics
import sys
import time
import tracemalloc
from pathlib import Path

from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import RankingRequest, app, rank, semantic_ranker


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int((len(ordered) - 1) * fraction + 0.999))]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--strategy", choices=("tfidf", "semantic", "hybrid"), default="tfidf")
    parser.add_argument("--iterations", type=int, default=20)
    parser.add_argument("--concurrency", type=int, default=2)
    parser.add_argument("--concurrent-requests", type=int, default=4)
    args = parser.parse_args()
    if args.iterations < 1:
        parser.error("--iterations must be positive")
    if args.concurrency < 1 or args.concurrent_requests < 1:
        parser.error("--concurrency and --concurrent-requests must be positive")

    os.environ["MENTOR_RANKING_STRATEGY"] = args.strategy

    page_size = 500
    pages = []
    for page in range(2):
        pages.append({
            "goal": "software engineering interview preparation",
            "strategy": args.strategy,
            "candidates": [
                {"id": page * page_size + index + 1,
                 "jobTitle": "Software Engineer" if index % 3 == 0 else "Operations Specialist",
                 "skills": ["Algorithms", "Python"] if index % 3 == 0 else ["Inventory", "Scheduling"],
                 "bio": "Builds software and prepares for coding interviews." if index % 3 == 0
                 else "Coordinates supply and operations."}
                for index in range(page_size)
            ],
        })

    sequential_ms = []
    batch_ms = []
    peak_bytes = 0
    first_semantic_request_ms = None
    first_semantic_request_status = None
    first_successful_request_ms = None
    model_ready_elapsed_ms = None
    initialization_started = time.perf_counter()
    with TestClient(app) as client:
        if args.strategy in {"semantic", "hybrid"}:
            started = time.perf_counter()
            first_request = client.post("/rank", json=pages[0])
            first_semantic_request_ms = (time.perf_counter() - started) * 1000
            first_semantic_request_status = first_request.status_code
            if first_request.status_code == 503:
                semantic_ranker.start_model_initialization().result(timeout=300)
            elif first_request.status_code != 200:
                first_request.raise_for_status()
            if semantic_ranker.model_state != "ready":
                raise RuntimeError("Semantic model failed initialization; see readiness state")
            model_ready_elapsed_ms = (time.perf_counter() - initialization_started) * 1000
            started = time.perf_counter()
            warmed = client.post("/rank", json=pages[0])
            warmed.raise_for_status()
            first_successful_request_ms = (time.perf_counter() - started) * 1000
        for iteration in range(args.iterations):
            if iteration % 2 == 0:
                started = time.perf_counter()
                sequential = [client.post("/rank", json=page).json() for page in pages]
                sequential_ms.append((time.perf_counter() - started) * 1000)
                started = time.perf_counter()
                batched_response = client.post("/rank/batch", json={"requests": pages})
                batch_ms.append((time.perf_counter() - started) * 1000)
            else:
                started = time.perf_counter()
                batched_response = client.post("/rank/batch", json={"requests": pages})
                batch_ms.append((time.perf_counter() - started) * 1000)
                started = time.perf_counter()
                sequential = [client.post("/rank", json=page).json() for page in pages]
                sequential_ms.append((time.perf_counter() - started) * 1000)
            batched_response.raise_for_status()
            batched = [entry["result"] for entry in batched_response.json()["results"]]
            if batched != sequential:
                raise AssertionError("batched and sequential page rankings differ")

        tracemalloc.start()
        memory_response = client.post("/rank/batch", json={"requests": pages})
        memory_response.raise_for_status()
        _, peak_bytes = tracemalloc.get_traced_memory()
        tracemalloc.stop()

    concurrent_payloads = []
    for request_index in range(args.concurrent_requests):
        request_payload = json.loads(json.dumps(pages[request_index % len(pages)]))
        for candidate in request_payload["candidates"]:
            candidate["bio"] = (candidate.get("bio") or "") + f" request-{request_index}"
        concurrent_payloads.append(RankingRequest.model_validate(request_payload))

    def rank_concurrently(request):
        started = time.perf_counter()
        try:
            rank(request)
            return {"latency_ms": (time.perf_counter() - started) * 1000, "success": True}
        except Exception:
            return {"latency_ms": (time.perf_counter() - started) * 1000, "success": False}

    with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        concurrent_results = list(executor.map(rank_concurrently, concurrent_payloads))
    concurrent_latencies = [result["latency_ms"] for result in concurrent_results]

    print(json.dumps({
        "scope": "in-process FastAPI TestClient; no database or network; cold timing is a new Python process with locally available model files",
        "database_pages": len(pages),
        "profiles_per_page": page_size,
        "total_profiles": len(pages) * page_size,
        "strategy": args.strategy,
        "iterations": args.iterations,
        "concurrency": args.concurrency,
        "python_http_requests_per_iteration": {"sequential": 2, "batched": 1},
        "latency_ms": {
            "first_cold_semantic_request": {
                "status": first_semantic_request_status,
                "elapsed": round(first_semantic_request_ms, 3)
                if first_semantic_request_ms is not None else None,
            },
            "first_successful_warm_semantic_request": round(first_successful_request_ms, 3)
            if first_successful_request_ms is not None else None,
            "fresh_ranker_start_to_model_ready": round(model_ready_elapsed_ms, 3)
            if model_ready_elapsed_ms is not None else None,
            "model_load": round(semantic_ranker.model_load_seconds * 1000, 3)
            if semantic_ranker.model_load_seconds is not None else None,
            "readiness_probe": round(semantic_ranker.model_warmup_seconds * 1000, 3)
            if semantic_ranker.model_warmup_seconds is not None else None,
            "sequential_p50": round(statistics.median(sequential_ms), 3),
            "sequential_p95": round(percentile(sequential_ms, 0.95), 3),
            "batched_p50": round(statistics.median(batch_ms), 3),
            "batched_p95": round(percentile(batch_ms, 0.95), 3),
            "concurrent_direct_rank_p50": round(statistics.median(concurrent_latencies), 3),
            "concurrent_direct_rank_p95": round(percentile(concurrent_latencies, 0.95), 3),
        },
        "concurrent_direct_rank": {
            "requests": len(concurrent_results),
            "concurrency": args.concurrency,
            "successful": sum(result["success"] for result in concurrent_results),
            "failed": sum(not result["success"] for result in concurrent_results),
            "note": "direct in-process rank handler calls; excludes HTTP serialization and socket overhead",
        },
        "latency_sample_note": "p95 is exploratory with small sample counts; increase iterations and concurrent requests for stable tail estimates",
        "batched_python_tracemalloc_peak_bytes": peak_bytes,
        "ranking_parity": "exact per-page response equality",
    }, indent=2))


if __name__ == "__main__":
    main()
