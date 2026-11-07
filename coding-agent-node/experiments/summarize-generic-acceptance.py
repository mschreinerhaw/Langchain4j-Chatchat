"""Post-stop measurement only. No model calls, task mutation or source rewriting."""
import argparse
import json
from pathlib import Path


def read(path):
    return json.loads(path.read_text()) if path.exists() else None


def measure(directory):
    result = read(directory / "result-public.json")
    history = read(directory / "history-probe-public.json")
    projection = read(directory / "receipts-projection-public.json")
    if result is None or projection is None:
        raise ValueError("Original terminal result and actual receipt projection required")
    passed = {v["manifestHash"]: v["independent"]["passed"] for v in history["versions"]} if history else {}
    observations = {v["operationId"]: v for v in projection["commandObservations"] if v.get("operationId")}
    pending = None
    cycles = []
    for operation in sorted(projection["codeOperations"], key=lambda v: v.get("startSequence") or 0):
        observed = observations.get(operation["operationId"])
        if not observed or not observed.get("executorStopped"):
            continue
        manifest = observed.get("manifestHash")
        diagnostics = observed.get("pythonDiagnosticCodes", [])
        if diagnostics and passed.get(manifest) is False:
            pending = {"failedManifestHash": manifest, "failureOperationId": operation["operationId"],
                "diagnosticCodes": diagnostics, "pipelineMaskedFailure": observed.get("exitCode") == 0}
        elif pending and manifest != pending["failedManifestHash"] and passed.get(manifest) is True and observed.get("exitCode") == 0 and not diagnostics:
            cycles.append({**pending, "repairedManifestHash": manifest, "verificationOperationId": operation["operationId"]})
            pending = None
    tools = projection["toolStops"]
    usage = result["usage"]
    return {"case": result["case"], "taskId": result["taskId"], "sourceCommit": result["sourceCommit"],
        "status": result["status"], "completed": result["completed"],
        "finalSourcePassed": result.get("independent", {}).get("passed", False),
        "modelCalls": usage["consumed"]["modelCalls"], "knownModelTokens": usage["consumed"]["modelTokens"],
        "actualCodeExecutions": usage["consumed"]["codeExecutions"], "usage": usage,
        "actualModelReceiptCount": len(projection["modelReceipts"]),
        "modelRequestedToolCalls": sum(len(v["toolCalls"]) for v in projection["modelReceipts"]),
        "admittedToolReceipts": len(tools), "admittedFailedToolReceipts": sum(v["admittedResultFailed"] for v in tools),
        "admittedToolFailureRate": sum(v["admittedResultFailed"] for v in tools) / len(tools) if tools else None,
        "firstValidCodeTimeMs": history["firstValidCodeTimeMs"] if history else None,
        "editVersionCount": history["editVersionCount"] if history else None,
        "observedErrorFeedbackRepairRounds": len(cycles) if history else None, "observedRepairCycles": cycles,
        "measurementLimits": "Repair cycles require actual diagnostics, independently invalid old source, a changed independently passing version and later stopped zero execution. Read-only fixes without executing bad source are not error-feedback cycles. First-valid time uses original archive creation time, not post-stop test duration. Model calls mean Runtime admitted calls; provider-internal retries are not counted as new Runtime calls."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", type=Path, required=True)
    parser.add_argument("--cohort", required=True)
    parser.add_argument("--repeat", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    options = parser.parse_args()
    results = [measure(d) for d in sorted(options.runs.glob(options.cohort + "-*-r" + str(options.repeat)))]
    if not results:
        raise ValueError("No original tasks found")
    admitted = sum(v["admittedToolReceipts"] for v in results)
    failed = sum(v["admittedFailedToolReceipts"] for v in results)
    output = {"scope": "POST_STOP_ACTUAL_RECEIPT_AND_INDEPENDENT_ARCHIVE_MEASUREMENT", "cohort": options.cohort,
        "repeat": options.repeat, "cases": len(results), "completed": sum(v["completed"] for v in results),
        "finalSourcesPassed": sum(v["finalSourcePassed"] for v in results),
        "modelCalls": sum(v["modelCalls"] for v in results), "knownModelTokens": sum(v["knownModelTokens"] for v in results),
        "admittedToolReceipts": admitted, "admittedFailedToolReceipts": failed,
        "admittedToolFailureRate": failed / admitted if admitted else None, "results": results}
    options.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({k: v for k, v in output.items() if k != "results"}))
