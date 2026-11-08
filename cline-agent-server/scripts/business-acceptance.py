"""Real business requests and observation only; never a model, tool, or Python executor.

Run on the isolated acceptance host. Private requests/receipts stay under --root;
only an explicitly reviewed summary should be exported to the repository.
"""
import argparse
import datetime
import json
import pathlib
import urllib.error
import urllib.parse
import urllib.request
import uuid


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    path.chmod(0o600)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=pathlib.Path, required=True)
    parser.add_argument("--case", choices=["A", "B", "C", "D"], required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--action", choices=["submit", "observe"], default="observe")
    args = parser.parse_args()
    root = args.root.resolve()
    evidence = root / "evidence" / "acceptance-20261108" / args.case
    evidence.mkdir(parents=True, exist_ok=True)
    private = root / "private"
    auth = json.loads((private / "auth.json").read_text())
    token = auth["token"]

    def request(path, body=None):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        req = urllib.request.Request("http://127.0.0.1:18340" + path, data,
            {"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=1800) as response:
                result = json.load(response)
        except urllib.error.HTTPError as error:
            result = {"httpStatus": error.code, "body": error.read().decode(errors="replace")}
        if result.get("code", 200) != 200 or "httpStatus" in result:
            raise RuntimeError(json.dumps(result, ensure_ascii=False))
        return result.get("data", result)

    state_path = evidence / "dispatch.json"
    if args.action == "submit":
        if state_path.exists():
            raise RuntimeError("Dispatch already recorded; observe it instead of replaying a business request")
        before = [p.parent.name for p in (root / "state").glob("*/run.json")]
        identifier = "native-acceptance-" + args.case + "-" + uuid.uuid4().hex[:20]
        previous = root / "evidence" / "business"
        if args.case in ["A", "B"]:
            body = json.loads((previous / (args.case + "-r2-input.json")).read_text())
            body.update(sessionId=identifier, idempotencyKey=identifier)
            path = "/api/v1/agent/tasks"
            original = dict(body)
        else:
            body = json.loads((previous / ("C-r5-input.json" if args.case == "C" else "D-input.json")).read_text())
            body["requestId"] = identifier
            original = dict(body)
            path = "/api/v1/data-science/user-assets/analyses"
            if args.case == "D":
                created = request("/api/v1/data-science/explorations", {"title": "Native Cline 图文业务验收 " + identifier})
                write(evidence / "workspace-created.json", created)
                prepared = request("/api/v1/data-science/explorations/" + created["id"] + "/nodes",
                    {"version": created["version"], "parentId": "", "fileIds": body["fileIds"],
                     "question": body["instruction"], "modelName": body["modelName"]})
                write(evidence / "workspace-prepared.json", prepared)
                node = prepared["nodes"][-1]
                path = "/api/v1/data-science/explorations/" + created["id"] + "/nodes/" + node["id"] + "/execute"
                body = {}
        state = {"case": args.case, "codeCommit": args.commit, "requestPath": path,
                 "originalRequest": original, "workersBeforeDispatch": before,
                 "dispatchedAt": datetime.datetime.now(datetime.timezone.utc).isoformat()}
        write(state_path, state)  # Persist before network IO; uncertainty cannot admit a second task.
        try:
            response = request(path, body)
            write(evidence / "response.json", response)
        except Exception as error:
            write(evidence / "request-failure.json", {"error": str(error)})
            raise
        print(json.dumps({"case": args.case, "requestRecorded": True, "responseKeys": list(response)}, ensure_ascii=False))
        return

    state = json.loads(state_path.read_text())
    if args.case in ["A", "B"] and (evidence / "response.json").exists():
        submitted = json.loads((evidence / "response.json").read_text())
        if submitted.get("taskId"):
            tenant = auth["user"]["tenantId"]
            response = request("/api/v1/agent/tasks/" + submitted["taskId"] + "?tenantId=" + urllib.parse.quote(tenant))
            write(evidence / "parent-latest.json", response)
            print(json.dumps({"case": args.case, "parentTaskId": submitted["taskId"], "status": response.get("status"), "error": response.get("errorMessage")}))
    if args.case == "D":
        prepared = json.loads((evidence / "workspace-prepared.json").read_text())
        response = request("/api/v1/data-science/explorations/" + prepared["id"] + "/nodes/" + prepared["nodes"][-1]["id"] + "/status")
        write(evidence / "workspace-latest.json", response)
    candidates = []
    for file in (root / "state").glob("*/run.json"):
        if file.parent.name in state["workersBeforeDispatch"]:
            continue
        run = json.loads(file.read_text())
        candidates.append({"workerId": file.parent.name, "taskId": run.get("taskId"),
            "nativeSessionId": run.get("nativeSessionId"), "nativeAgentSessionId": run.get("nativeAgentSessionId"),
            "scope": run.get("scope"), "status": run.get("status"), "businessStatus": run.get("businessStatus"),
            "activeOperations": run.get("activeOperations"), "lastOperation": run.get("lastOperation"),
            "error": run.get("error"), "codeLifecycle": run.get("codeLifecycle"),
            "nativeCompletion": run.get("nativeCompletion"), "workerStopped": run.get("workerStopped")})
    # Candidates require scope/request correlation during review; chronological proximity is not identity proof.
    write(evidence / "candidate-runs.json", candidates)
    print(json.dumps({"case": args.case, "candidateRuns": candidates}, ensure_ascii=False))


if __name__ == "__main__":
    main()
