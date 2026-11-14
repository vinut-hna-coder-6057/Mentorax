"""Run the synthetic experience-document corpus against the real local API route."""
from __future__ import annotations

import hashlib
import json
import os
import sys
from collections import defaultdict
from io import BytesIO
from pathlib import Path

import fitz
from fastapi.testclient import TestClient
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from app import app  # noqa: E402

CORPUS = Path(__file__).with_name("synthetic_corpus.json")
TOKEN = "synthetic-evaluation-only-token"
FIELD_NAMES = {
    "personName": "personName",
    "organisation": "organisation",
    "role": "role",
    "startDate": "startDate",
    "endDate": "endDate",
    "issueDate": "issueDate",
    "issuer": "issuer",
    "mentorSupervisor": "mentorSupervisor",
    "certificateReferenceId": "certificateReferenceId",
}


def make_pdf(text: str, scanned: bool) -> bytes:
    doc = fitz.open()
    page = doc.new_page(width=612, height=792)
    if scanned:
        image = Image.new("RGB", (850, 1100), "white")
        draw = ImageDraw.Draw(image)
        try:
            font = ImageFont.truetype("C:\\Windows\\Fonts\\arial.ttf", 21)
        except OSError:
            font = ImageFont.load_default(size=21)
        y = 45
        for line in text.splitlines():
            draw.text((35, y), line[:90], fill="black", font=font)
            y += 50
        stream = BytesIO()
        image.save(stream, format="JPEG", quality=82, optimize=True)
        page.insert_image(page.rect, stream=stream.getvalue())
    else:
        y = 60
        for line in text.splitlines():
            if y > 750:
                page = doc.new_page(width=612, height=792)
                y = 60
            page.insert_text((45, y), line[:120], fontsize=11)
            y += 22
    out = BytesIO()
    doc.save(out)
    doc.close()
    return out.getvalue()


def response_field_map(payload: dict) -> dict:
    return {item["name"]: item for item in payload.get("fields", [])}


def run_case(client: TestClient, case: dict) -> dict:
    content = make_pdf(case["text"], case["format"] == "scanned_pdf")
    claim = {
        "evidenceId": 8000 + len(case["id"]),
        "evidenceVersion": 1,
        "sha256": hashlib.sha256(content).hexdigest(),
        "claimType": case.get("claimType", "UNSPECIFIED"),
        "company": case.get("company", "Northstar Synthetic Labs"),
        "jobTitle": case.get("jobTitle", "Synthetic role"),
        "startMonth": case.get("startMonth"),
        "endMonth": case.get("endMonth"),
        "summary": "Synthetic document quality evaluation fixture.",
        "employeeName": case.get("employeeName"),
    }
    response = client.post(
        "/experience/analyze",
        headers={"Authorization": f"Bearer {TOKEN}"},
        data={"claim": json.dumps(claim)},
        files={"file": (f"{case['id']}.pdf", content, "application/pdf")},
    )
    if response.status_code != 200:
        return {"id": case["id"], "category": case["category"], "httpStatus": response.status_code,
                "error": response.json().get("detail")}
    payload = response.json()
    actual_fields = response_field_map(payload)
    checks = {
        "classification": payload.get("documentClassification") == case["expectedClassification"],
        "verification_is_none": payload.get("verificationDecision") == "NONE",
        "issuer_not_authenticated": payload.get("trustedIssuerValidation") is False,
    }
    field_checks = {}
    for key, expected in case.get("expectedFields", {}).items():
        name = FIELD_NAMES[key]
        actual = actual_fields.get(name, {})
        field_checks[key] = actual.get("status") == expected["status"] and actual.get("value") == expected["value"]
    finding_map = {item["field"]: item["outcome"] for item in payload.get("findings", [])}
    comparison_checks = {
        field: finding_map.get(field) == outcome
        for field, outcome in case.get("expectedComparisons", {}).items()
    }
    serialized = response.text
    sensitive_checks = {
        value: value not in serialized for value in case.get("plantedSensitiveValues", [])
    }
    checks["fields"] = all(field_checks.values())
    checks["comparisons"] = all(comparison_checks.values())
    checks["sensitive_values_redacted"] = all(sensitive_checks.values())
    return {
        "id": case["id"], "category": case["category"],
        "expectedClassification": case["expectedClassification"],
        "actualClassification": payload.get("documentClassification"),
        "ocrStatus": payload.get("ocrStatus"),
        "analysisStatus": payload.get("analysisStatus"),
        "checks": checks, "fieldChecks": field_checks,
        "comparisonChecks": comparison_checks, "sensitiveChecks": sensitive_checks,
        "findings": finding_map,
    }


def invalid_bytes(fmt: str) -> bytes:
    if fmt == "empty_pdf":
        return b""
    if fmt == "corrupt_pdf":
        return b"%PDF-1.7\nnot a valid document"
    return b"unsupported text"


def run_invalid(client: TestClient, case: dict) -> dict:
    content = invalid_bytes(case["format"])
    claim = {
        "evidenceId": 9900, "evidenceVersion": 1,
        "sha256": hashlib.sha256(content).hexdigest(),
        "claimType": "UNSPECIFIED", "company": "Northstar Synthetic Labs",
        "jobTitle": "Synthetic role", "summary": "Invalid fixture",
    }
    response = client.post(
        "/experience/analyze", headers={"Authorization": f"Bearer {TOKEN}"},
        data={"claim": json.dumps(claim)},
        files={"file": (f"{case['id']}.txt", content, case["contentType"])},
    )
    return {"id": case["id"], "expectedHttpStatus": case["expectedHttpStatus"],
            "actualHttpStatus": response.status_code,
            "passed": response.status_code == case["expectedHttpStatus"]}


def main() -> int:
    corpus = json.loads(CORPUS.read_text(encoding="utf-8"))
    os.environ["EXPERIENCE_ANALYSIS_API_TOKEN"] = TOKEN
    with TestClient(app) as client:
        documents = [run_case(client, case) for case in corpus["documents"]]
        invalid = [run_invalid(client, case) for case in corpus["invalidDocuments"]]
    grouped = defaultdict(lambda: {"fixtures": 0, "checks": 0, "passed": 0})
    for result in documents:
        group = grouped[result["category"]]
        group["fixtures"] += 1
        for section in (result.get("checks", {}), result.get("fieldChecks", {}),
                        result.get("comparisonChecks", {}), result.get("sensitiveChecks", {})):
            for passed in section.values():
                group["checks"] += 1
                group["passed"] += int(bool(passed))
    summary = {
        "corpus": corpus["name"], "syntheticOnly": corpus["syntheticOnly"],
        "realWorldAccuracyClaim": False, "documentCount": len(documents),
        "documentChecks": sum(len(item.get("checks", {})) + len(item.get("fieldChecks", {}))
                               + len(item.get("comparisonChecks", {}))
                               + len(item.get("sensitiveChecks", {})) for item in documents),
        "passingDocumentChecks": sum(sum(bool(v) for section in (item.get("checks", {}),
            item.get("fieldChecks", {}), item.get("comparisonChecks", {}),
            item.get("sensitiveChecks", {})) for v in section.values()) for item in documents),
        "categoryResults": dict(grouped), "documents": documents,
        "invalidDocumentResults": invalid,
        "allInvalidCasesPassed": all(item["passed"] for item in invalid),
        "allCoreChecksPassed": all(all(item.get("checks", {}).values()) and
            all(item.get("fieldChecks", {}).values()) and all(item.get("comparisonChecks", {}).values())
            and all(item.get("sensitiveChecks", {}).values()) for item in documents),
    }
    out = Path(__file__).with_name("latest_results.json")
    out.write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in summary.items() if k not in {"documents"}}, indent=2))
    print(f"Per-document details: {out}")
    return 0 if summary["allCoreChecksPassed"] and summary["allInvalidCasesPassed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
