"""Inject an existing broken source into one real acceptance task, never its repair.

The official Cline must reproduce the exception, edit the source and run again.
This opt-in operator harness uses the isolated host's existing private requests.
It never supplies analytical code, a successful receipt or an expected result.
"""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import threading
import time
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=pathlib.Path, required=True)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    evidence = root / "evidence/acceptance-20261108/C-r2"
    evidence.mkdir(parents=True, exist_ok=True)

    def write(name, value):
        file = evidence / name
        file.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
        file.chmod(0o600)

    if (evidence / "dispatch.json").exists():
        raise RuntimeError("A dispatch is already recorded; never replay this request")
    before = [f.parent.name for f in (root / "state").glob("*/run.json")]
    request = json.loads((root / "evidence/business/C-r5-input.json").read_text())
    identifier = "native-repair-" + uuid.uuid4().hex[:20]
    request["requestId"] = identifier
    fixture = '"""Existing program with an injected runtime fault for acceptance."""\nraise RuntimeError("NATIVE_ACCEPTANCE_EXISTING_PROGRAM_FAILURE")\n'
    request["instruction"] = (
        "本轮同时验证真实错误修复。工作区已有 acceptance_reproduce.py。"
        "先原样执行该程序以复现异常，读取实际执行反馈，再自主修复源代码并重新执行。"
        "故障复现不算完成交付；修复后仍须完成下列原始业务需求，不得编造数据或跳过必需结果文件。\n"
        + request["instruction"]
    )
    state = {"case": "C", "attempt": 2, "codeCommit": args.commit,
             "requestPath": "/api/v1/data-science/user-assets/analyses",
             "originalRequest": request, "workersBeforeDispatch": before,
             "dispatchedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
             "fault": {"type": "EXISTING_SOURCE_RUNTIME_EXCEPTION", "source": fixture,
                       "sha256": hashlib.sha256(fixture.encode()).hexdigest()}}
    write("dispatch.json", state)

    def inject():
        try:
            for _ in range(2400):
                for file in (root / "state").glob("*/run.json"):
                    if file.parent.name in before:
                        continue
                    run = json.loads(file.read_text())
                    if run.get("scope", {}).get("requestId") != identifier or not run.get("containerId"):
                        continue
                    if run.get("nativeSessionId"):
                        raise RuntimeError("Native session already exists; refusing a late source injection")
                    workspace = root / "workers" / run["workerId"] / "workspace"
                    target = workspace / "acceptance_reproduce.py"
                    owner = workspace.stat()
                    with target.open("x", encoding="utf-8") as output:
                        output.write(fixture)
                    os.chown(target, owner.st_uid, owner.st_gid)
                    target.chmod(0o600)
                    write("fixture-injected.json", {"workerId": run["workerId"], "taskId": run["taskId"],
                          "beforeNativeSessionCreation": True, "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
                          "injectedAt": datetime.datetime.now(datetime.timezone.utc).isoformat()})
                    return
                time.sleep(0.05)
            raise RuntimeError("No bound worker became available for fault injection")
        except Exception as error:
            write("fixture-failure.json", {"error": str(error)})

    watcher = threading.Thread(target=inject, daemon=True)
    watcher.start()
    auth = json.loads((root / "private/auth.json").read_text())
    http = urllib.request.Request("http://127.0.0.1:18340" + state["requestPath"],
        json.dumps(request, ensure_ascii=False).encode(),
        {"Authorization": "Bearer " + auth["token"], "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(http, timeout=1800) as response:
            write("response.json", json.load(response))
    except Exception as error:
        write("request-failure.json", {"error": str(error)})
        raise


if __name__ == "__main__":
    main()
