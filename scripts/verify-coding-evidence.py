"""Check published-Agent data-analysis evidence; task success alone never passes.

Usage: python scripts/verify-coding-evidence.py --evidence target/private-run
Business numerical correctness requires a separate independent verification.
"""
import argparse
import hashlib
import json
from pathlib import Path


def objects(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from objects(child)
    elif isinstance(value, list):
        for child in value:
            yield from objects(child)


def evaluate(root, allow_partial=False, min_rows=1):
    def read(name):
        return json.loads((root / name).read_text(encoding="utf-8-sig"))

    state = read("run-state.json")
    observations = read("runtime-observations.json")
    artifacts = read("coding-artifacts.json")
    definitions = read("agent-definition-comparison.json")
    receipts = {}
    for item in objects(observations):
        if item.get("operation") == "RUN_CODING" and "resultAvailable" in item:
            receipts[item["taskId"]] = item
    admitted = [r for r in receipts.values()
                if r.get("status") == "COMPLETED" and r.get("resultAvailable") is True
                and r.get("validationStatus") in ("PASSED", "INCONCLUSIVE")
                and r.get("rowCount", 0) >= min_rows and len(set(r.get("evidenceRefs", []))) >= 6
                and str(r.get("datasetReference", "")).startswith("runtime-evidence:")
                and not r.get("reconciliationRequired")]
    turns = [o.get("metadata", {}) for o in observations
             if o.get("metadata", {}).get("eventKind") == "HARNESS_TURN"
             and o.get("metadata", {}).get("eventState") == "COMPLETED"]
    action = turns[-1].get("modelDecision", {}).get("action") if turns else None
    coding_events = {v.get("eventId") or (v.get("taskId"), v.get("sequence"))
                     for v in objects(observations) if v.get("eventKind") == "CODING_TRACE"}
    failures = []
    if not definitions.get("matches"):
        failures.append("PUBLISHED_DEFINITION_MISMATCH")
    if not state.get("terminal") or state.get("canonicalState") != "SUCCEEDED":
        failures.append("TASK_NOT_SUCCEEDED")
    if not coding_events:
        failures.append("NO_CODING_TRACE")
    if not admitted:
        failures.append("NO_ADMITTED_NONEMPTY_CODING_RESULT_WITH_EVIDENCE")
    if action not in (("COMPLETE", "PARTIAL_COMPLETE") if allow_partial else ("COMPLETE",)):
        failures.append("ANALYSIS_NOT_COMPLETE_WITHIN_REQUIRED_SCOPE")
    inspected = {}
    for artifact in artifacts:
        if artifact.get("unavailable"):
            failures.append("ARTIFACT_UNAVAILABLE")
            continue
        path = (root / artifact["file"]).resolve()
        if not path.is_relative_to(root.resolve()):
            raise ValueError("Artifact escapes the evidence directory")
        # The API exports UTF-8 text. Accept exact bytes or undo the one CRLF
        # translation applied by the older Windows write_text client. Original
        # source CRLF and lone CR characters must not be silently discarded.
        exported = path.read_bytes()
        digests = {hashlib.sha256(exported).hexdigest(),
                   hashlib.sha256(exported.replace(b"\r\n", b"\n")).hexdigest()}
        if artifact["sha256"] not in digests:
            failures.append("ARTIFACT_TEXT_HASH_MISMATCH")
        inspected.setdefault(artifact["taskId"], set()).add(artifact["kind"])
    for receipt in admitted:
        if not {"code", "stdout", "stderr"}.issubset(inspected.get(receipt["taskId"], set())):
            failures.append("ADMITTED_TASK_ARTIFACT_INSPECTION_MISSING")
    return {"status": "FAILED" if failures else "PASSED", "taskId": state["taskId"],
            "runId": state.get("runId"), "analysisAction": action,
            "partialAnalysisAllowed": allow_partial, "uniqueCodingEvents": len(coding_events),
            "admittedResults": [{k: r[k] for k in ("taskId", "datasetReference", "rowCount", "evidenceRefs")}
                                for r in admitted],
            "failures": sorted(set(failures)),
            "limitation": "This gate checks execution/evidence contracts, not business numerical correctness or release readiness."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--allow-partial", action="store_true")
    parser.add_argument("--min-rows", type=int, default=1)
    args = parser.parse_args()
    if args.min_rows < 0:
        parser.error("--min-rows must be nonnegative")
    report = evaluate(args.evidence, args.allow_partial, args.min_rows)
    (args.evidence / "coding-evidence-gate.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
    raise SystemExit(0 if report["status"] == "PASSED" else 1)
