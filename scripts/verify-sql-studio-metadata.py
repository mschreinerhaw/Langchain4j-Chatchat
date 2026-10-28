#!/usr/bin/env python3
"""Opt-in real-model/registered-database acceptance. Never executes or applies SQL.

Use an isolated application with an existing authorized SQL project and source binding.
The token file is an existing login response (or its data object), never a CLI secret.
Outputs contain authorized schema evidence: keep the output directory private.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request


QUESTIONS = [
    ("A", "这个数据库下有哪些表可以分析调度运行情况？"),
    ("B", "这些表中哪些可以分析调度失败率和执行耗时？"),
    ("C", "根据这些表生成最近七天调度失败率的统计 SQL。"),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--token-file", required=True, type=Path)
    parser.add_argument("--project-id", required=True)
    parser.add_argument("--binding-context-id", required=True)
    parser.add_argument("--model")
    parser.add_argument("--dialect", default="mysql")
    parser.add_argument("--max-wait-seconds", type=int, default=1200)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.max_wait_seconds <= 0:
        parser.error("max-wait-seconds must be positive")
    os.umask(0o077)
    args.output.mkdir(parents=True, exist_ok=True, mode=0o700)
    session = json.loads(args.token_file.read_text(encoding="utf-8"))
    token = session.get("data", session)["token"]

    def api(path, body=None, method=None):
        request = urllib.request.Request(
            args.base_url.rstrip("/") + "/api/v1/" + path,
            None if body is None else json.dumps(body, ensure_ascii=False).encode(),
            {"Content-Type": "application/json", "Authorization": "Bearer " + token}, method=method)
        with urllib.request.urlopen(request, timeout=90) as response:
            value = json.load(response)
        if value.get("code") not in (0, 200):
            raise RuntimeError("Application rejected acceptance request: " + str(value.get("code")))
        return value["data"]

    def save(name, value):
        (args.output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")

    base = "data-science/sql/projects/" + args.project_id
    discovery, results = None, []
    for label, question in QUESTIONS:
        stored = args.output / (label + "-prepared.json")
        if stored.exists():
            prepared = json.loads(stored.read_text(encoding="utf-8"))
        else:
            prepared = api(base + "/generations", {
                "action": "explore", "prompt": question, "dialect": args.dialect,
                "modelName": args.model, "metadataDiscoveryId": discovery,
                "metadataBindingContextId": args.binding_context_id})
            save(label + "-prepared.json", prepared)
        task_request = prepared["taskRequest"]
        assert task_request["toolInput"].get("codingWorkspaceProfile") is None
        task = api("agent/tasks", task_request)
        task_id = task["taskId"]
        api(base + "/generations/" + prepared["generationId"] + "/task", {"taskId": task_id}, "PUT")
        suffix = "?tenantId=" + urllib.parse.quote(task_request["tenantId"])
        started = time.monotonic()
        last_status = None
        while True:
            # A Runtime retry is another taskId in the same execution, not a mutation
            # of the predecessor. Studio follows the authenticated attempt lineage.
            current = api(base + "/generations/" + prepared["generationId"])
            task = current["task"]
            task_id = task["taskId"]
            canonical = task.get("canonicalState")
            status = canonical if canonical not in (None, "COMPLETED") else task["status"]
            if status != last_status:
                print(label, task_id, status, flush=True)
                last_status = status
            if status in {"SUCCEEDED", "SUCCESS", "FAILED", "CANCELLED", "REJECTED", "PARTIAL", "PARTIAL_SUCCESS"}:
                break
            if time.monotonic() - started > args.max_wait_seconds:
                api("agent/tasks/" + task_id + "/cancel" + suffix, {}, "POST")
                raise TimeoutError("Acceptance wait expired; cancellation requested for " + task_id)
            time.sleep(2)
        result = api(base + "/generations/" + prepared["generationId"])
        save(label + "-result.json", result)
        save(label + "-events.json", result["events"])
        assert status in {"SUCCEEDED", "SUCCESS", "PARTIAL", "PARTIAL_SUCCESS"}, "Backend task did not succeed: " + task_id
        assert not result.get("outputError"), "Generation result failed metadata validation"
        evidence = result["metadataExploration"]
        assert evidence["sqlExecuted"] is False
        if label == "A":
            assert evidence["candidates"], "No evidenced candidate tables were returned"
        if label != "A":
            assert evidence["discoveryId"] == discovery, "Discovery identity changed across rounds"
            assert evidence["stats"]["reusedEvidenceCount"] > 0, "Prior metadata evidence was not reused"
        discovery = evidence["discoveryId"]
        if result["files"]:
            assert evidence["fieldValidation"] == "CHECKED", "SQL references were not validated"
        results.append({"scenario": label, "taskId": task_id, "generationId": prepared["generationId"],
                        "discoveryId": discovery, "status": status, "stats": evidence["stats"],
                        "candidateTables": [table["tableName"] for table in evidence["candidates"]],
                        "sqlProposalCount": len(result["files"]), "sqlExecuted": False,
                        "semanticValidation": evidence.get("semanticValidation"),
                        "blockedSqlProposalCount": evidence.get("blockedSqlProposalCount", 0),
                        "answerSha256": hashlib.sha256(result["rawAnswer"].encode()).hexdigest()})
        save("summary.json", results)
    print("A/B/C completed; inspect C notes for confirmed semantics or explicit missing evidence. SQL was never executed.")


if __name__ == "__main__":
    main()
