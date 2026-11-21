#!/usr/bin/env python3
"""Submit a real Native task and verify its owner-scoped Silo Parquet receipt."""
import argparse
import getpass
import hashlib
import io
import json
import os
import time
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base_url")
    parser.add_argument("agent_id")
    parser.add_argument("template_id")
    parser.add_argument("question")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--model")
    parser.add_argument("--wait", type=int, default=600)
    args = parser.parse_args()
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    token = None

    def call(path, body=None, binary=False):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        request = urllib.request.Request(args.base_url.rstrip("/") + "/api/v1" + path,
            json.dumps(body).encode() if body is not None else None, headers)
        with client.open(request, timeout=60) as response:
            return response.read() if binary else json.load(response)["data"]

    token = call("/enterprise/auth/login", {"username": args.username,
        "password": os.environ.get("RUNTIME_VERIFY_PASSWORD") or getpass.getpass()})["token"]
    submitted = call("/coding/tasks", {
        "requestId": "native-template-" + uuid.uuid4().hex, "agentId": args.agent_id,
        "publishedTemplateId": args.template_id, "modelName": args.model, "artifactTypes": ["RESULT"],
        "instruction": args.question + "\nUse the task's authorized template tool. After success run "
            "Python inside the workspace to open the returned local file, read its Parquet footer, and hash "
            "its bytes. Write the observed integer rowCount and sha256 to output/result.json and submit it "
            "as the RESULT artifact."
    })
    task, run = submitted["taskId"], submitted["runId"]
    print(json.dumps({"taskId": task, "runId": run}), flush=True)
    deadline = time.monotonic() + args.wait
    while time.monotonic() < deadline:
        delivery = call(f"/coding/tasks/runs/{run}/{task}/delivery")
        if delivery["terminal"]:
            break
        time.sleep(3)
    else:
        raise RuntimeError(f"Task {task} did not settle within the verification window")

    if delivery["status"] != "COMPLETED":
        raise RuntimeError(f"Task {task} ended as {delivery['status']}")
    events = [entry["payload"]["metadata"]["payload"] for entry in delivery["analysisEvents"]]
    receipts = [event for event in events if event.get("nativeEventType") == "template.query.delivered"]
    if not receipts:
        raise RuntimeError("No broker-observed template delivery")
    if not any(event.get("normalizedType") == "COMMAND_COMPLETED" and event.get("exitCode") == 0
               for event in events):
        raise RuntimeError("No successful Native Python command observed")
    result = receipts[-1]
    if result["templateId"] != args.template_id or result["rowCount"] <= 0 or not result["complete"]:
        raise RuntimeError("Expected complete non-empty query results")
    parquet = call("/data-science/user-assets/" + result["assetId"] + "/content", binary=True)
    if not (parquet.startswith(b"PAR1") and parquet.endswith(b"PAR1")
            and hashlib.sha256(parquet).hexdigest() == result["sha256"]):
        raise RuntimeError("Parquet downloaded from Silo differs from the Native receipt")
    import pyarrow.parquet as pq
    if pq.ParquetFile(io.BytesIO(parquet)).metadata.num_rows != result["rowCount"]:
        raise RuntimeError("Parquet footer row count differs from Runtime receipt")
    report = next(file for file in delivery["artifacts"] if file["fileName"] == "result.json")
    data = json.loads(call("/data-science/user-assets/" + report["id"] + "/content", binary=True))
    if data.get("rowCount") != result["rowCount"] or data.get("sha256") != result["sha256"]:
        raise RuntimeError("Native result differs from the Silo receipt")
    print(json.dumps({"taskId": task, "runId": run, "query": result,
        "parquetBytes": len(parquet), "nativeResultAssetId": report["id"],
        "modelUsage": delivery.get("usage")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
