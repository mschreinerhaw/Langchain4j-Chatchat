"""Execute a prepared, explicitly authorized synthetic code-reuse regression.

Run on the deployment host: python3 verify-cline-sdk-reuse-live.py RELEASE_DIR baseline|candidate
The private directory must contain auth-private.json and VARIANT-prepared-private.json.
Uses the real application model bridge, Cline worker and MCP Python Sandbox.
Never applies proposed code to Studio files or changes model/Agent configuration.
"""
import hashlib
import json
from pathlib import Path
import sys
import time
import urllib.request


def main():
    root = Path(sys.argv[1]).resolve()
    variant = sys.argv[2]
    if variant not in {"baseline", "candidate"}:
        raise ValueError("Expected baseline or candidate")
    prepared = json.loads((root / (variant + "-prepared-private.json")).read_text())
    token = json.loads((root / "auth-private.json").read_text())["token"]
    prefix = "/data-science/python/code-reuse/" + prepared["requestId"]

    def api(path, body=None):
        request = urllib.request.Request("http://127.0.0.1:18180/api/v1" + path,
            data=None if body is None else json.dumps(body).encode(),
            headers={"Content-Type": "application/json", "Authorization": "Bearer " + token,
                     "X-Workspace-Id": "data-science"})
        with urllib.request.urlopen(request, timeout=1800) as response:
            value = json.load(response)
        if value.get("code") != 200:
            raise RuntimeError("Application rejected " + path + ": " + str(value.get("message")))
        return value["data"]

    started = time.monotonic()
    receipt = api(prefix + "/execute", {"snapshotSha256": prepared["snapshotSha256"], "authorizeExecution": True})
    (root / (variant + "-receipt-private.json")).write_text(json.dumps(receipt))
    state = api(prefix)
    (root / (variant + "-state-private.json")).write_text(json.dumps(state))
    outcome = state.get("outcome", {})
    versions = state.get("codeVersions", [])
    report = {"variant": variant, "requestId": prepared["requestId"], "taskId": state.get("taskId"),
        "status": outcome.get("status", state.get("status")), "durationMs": round((time.monotonic() - started) * 1000),
        "resultAvailable": state.get("resultAvailable", False), "usage": outcome.get("usage"),
        "resultDatasetRef": outcome.get("resultDatasetRef"),
        "validationRecordRef": outcome.get("validationRecordRef"), "evidenceBundleRef": outcome.get("evidenceBundleRef"),
        "sources": [{k: s[k] for k in ("path", "sha256")} for s in prepared["sources"]],
        "executions": [{"executionId": e.get("executionId"), "status": e.get("status"), "codeHash": e.get("codeHash"),
            "exceptionType": (e.get("error") or {}).get("exceptionType"),
            "diagnosticCode": (e.get("error") or {}).get("diagnosticCode"), "repair": e.get("repair"),
            "durationMs": e.get("durationMs")} for e in state.get("executions", [])],
        "versions": [{"sha256": v.get("sha256"), "files": [{"path": f["path"],
            "sha256": hashlib.sha256(f["content"].encode()).hexdigest()} for f in v.get("project", {}).get("files", [])]}
            for v in versions],
        "modelEvents": [{"type": e.get("type"), "operationId": e.get("operationId"), "payload": e.get("payload")}
            for e in state.get("events", []) if "MODEL" in e.get("type", "")],
        "syntheticOnly": True, "businessDataModified": False}
    (root / (variant + "-verification.json")).write_text(json.dumps(report, indent=2))
    print(json.dumps({k: report[k] for k in ["variant", "requestId", "status", "durationMs", "resultAvailable"]}), flush=True)


if __name__ == "__main__":
    main()
