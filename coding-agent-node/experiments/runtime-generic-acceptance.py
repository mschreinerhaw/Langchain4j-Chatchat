"""Real project-API acceptance. Reads an existing private token; never repairs model code.

Run on the isolated acceptance host after generating general-coding-cases.py JSON.
The independent checks execute only after the original task stops. Their results
are not sent back to the Agent and do not extend its budget.
"""
import argparse
import ast
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request

os.umask(0o077)
IMAGE = "sha256:a3a279fc391bda1bb5aefb62acd8ee56200215ce2905cfb83d189398c33c5828"
TERMINAL = {"COMPLETED", "PARTIAL", "FAILED", "CANCELLED", "BUDGET_EXCEEDED"}


def sha(value):
    return hashlib.sha256(value).hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def call(method, path, token, value=None):
    request = urllib.request.Request("http://127.0.0.1:18280" + path,
        data=None if value is None else json.dumps(value).encode(), method=method,
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return {"http": response.status, "body": json.load(response)}
    except urllib.error.HTTPError as error:
        return {"http": error.code, "body": json.loads(error.read())}


def data(reply):
    if reply["http"] != 200 or reply["body"].get("code") != 200:
        raise RuntimeError("Acceptance API rejected request: " + str(reply["http"]))
    return reply["body"]["data"]


def function_segments(source):
    return {node.name: ast.get_source_segment(source, node) for node in ast.parse(source).body
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))}


def independent(case, tree, directory, label):
    files = tree["files"]
    for descriptor in tree["manifest"]["files"]:
        source = files[descriptor["path"]].encode()
        assert sha(source) == descriptor["sha256"] and len(source) == descriptor["bytes"]
    readonly = all(files.get(p) == case["files"][p] for p in case["readOnlyPaths"])
    required = case["id"] != "A" or all(p in files for p in (
        "textkit/__init__.py", "textkit/normalize.py", "textkit/cli.py", "tests/test_normalize.py"))
    preserved = True
    if case["preserveFunctions"]:
        before = function_segments(case["files"]["large_module.py"])
        after = function_segments(files.get("large_module.py", ""))
        preserved = all(before[name] == after.get(name) for name in case["preserveFunctions"])
    project = directory / "independent-project"
    project.mkdir(mode=0o755)
    for name, source in files.items():
        target = project / name
        assert target.resolve().is_relative_to(project.resolve())
        target.parent.mkdir(exist_ok=True, parents=True)
        target.write_text(source, encoding="utf-8")
        target.chmod(0o644)
        for parent in target.parents:
            if parent == project.parent:
                break
            parent.chmod(0o755)
    count = 6 if case["id"] == "A" else 1 if case["id"].startswith("E-") else 3
    program = ("import sys,unittest,decimal\nsys.dont_write_bytecode=True\n"
        "sys.path.insert(0,'/workspace/code')\n" + case["independentHoldout"]
        + "\nsuite=unittest.defaultTestLoader.discover('/workspace/code/tests')\n"
        + "result=unittest.TextTestRunner(verbosity=2).run(suite)\n"
        + f"assert result.testsRun >= {count} and result.wasSuccessful()\n")
    if case["id"] == "A":
        program += ("import subprocess\ncheck=subprocess.run([sys.executable,'-m','textkit.cli',' Hello, WORLD! 42 '],"
            "cwd='/workspace/code',capture_output=True,text=True)\n"
            "assert check.returncode==0 and check.stdout.strip()=='hello-world-42'\n")
    name = "native-holdout-" + label.lower()
    arguments = ["docker", "run", "--name", name, "--read-only", "--network", "none", "--cap-drop", "ALL",
        "--security-opt", "no-new-privileges:true", "--memory", "512m", "--cpus", "1", "--user", "10001:10001",
        "--pids-limit", "256", "--tmpfs", "/tmp:rw,nosuid,noexec,size=64m", "-v", str(project) + ":/workspace/code:ro",
        "-w", "/workspace/code", "--entrypoint", "python", IMAGE, "-I", "-c", program]
    timed_out = False
    try:
        result = subprocess.run(arguments, capture_output=True, timeout=45)
    except subprocess.TimeoutExpired as error:
        timed_out = True
        # Killing the Docker client does not establish that its container stopped.
        subprocess.run(["docker", "kill", name], capture_output=True, check=False)
        result = subprocess.CompletedProcess(arguments, 124, error.stdout or b"", error.stderr or b"")
    (directory / "independent-stdout.log").write_bytes(result.stdout)
    (directory / "independent-stderr.log").write_bytes(result.stderr)
    state = json.loads(subprocess.check_output(["docker", "inspect", name]))[0]
    return {"exitCode": result.returncode, "timedOut": timed_out, "executorStopped": not state["State"]["Running"],
        "containerId": state["Id"], "imageId": IMAGE, "networkMode": state["HostConfig"]["NetworkMode"],
        "oomKilled": state["State"]["OOMKilled"], "readonlyTestsUnchanged": readonly, "requiredFilesPresent": required, "unrelatedFunctionsPreserved": preserved,
        "preservedFunctionCount": len(case["preserveFunctions"]), "stdoutSha256": sha(result.stdout), "stderrSha256": sha(result.stderr),
        "passed": not timed_out and result.returncode == 0 and readonly and required and preserved and not state["State"]["Running"]}


def run_case(case, options, token, fixture_sha):
    label = f"{options.cohort}-{case['id']}-r{options.repeat}"
    directory = options.output / label
    directory.mkdir(parents=True, exist_ok=False)
    request = {k: case[k] for k in ("instruction", "files", "readOnlyPaths", "acceptanceCommand")}
    request.update(modelName="qwen3.8-flash", editStrategy=options.edit_strategy, skills=[])
    write(directory / "request.json", request)
    intent = {"case": case["id"], "cohort": options.cohort, "repeat": options.repeat,
        "sourceCommit": options.source_commit, "fixtureSha256": fixture_sha,
        "harnessSha256": sha(Path(__file__).read_bytes()),
        "initialFileHashes": case["initialFileHashes"], "requestSha256": sha(json.dumps(request, ensure_ascii=False,
            sort_keys=True, separators=(",", ":")).encode()), "startedAtEpochMs": int(time.time() * 1000)}
    write(directory / "intent.json", intent)
    submitted = call("PUT", "/api/v1/coding/projects/generic-" + label, token, request)
    write(directory / "submit.json", submitted)
    task = data(submitted)["taskId"]
    intent["taskId"] = task
    write(directory / "intent.json", intent)
    stop = time.monotonic() + 360
    while True:
        reply = call("GET", "/api/v1/coding/projects/" + task, token)
        write(directory / "last-status.json", reply)
        status = data(reply)
        if status["status"] in TERMINAL or time.monotonic() >= stop:
            break
        time.sleep(2)
    for suffix in ("files", "history", "events"):
        write(directory / (suffix + ".json"), call("GET", "/api/v1/coding/projects/" + task + "/" + suffix, token))
    report = {**intent, "status": status["status"], "runtimeValidationStatus": status["validationStatus"],
        "usage": status["usage"], "executionControl": status["executionControl"], "completed": False,
        "endedAtEpochMs": int(time.time() * 1000)}
    if status["status"] in TERMINAL:
        tree = data(json.loads((directory / "files.json").read_text()))
        report["manifestHash"] = tree["manifest"]["sha256"]
        report["finalFileHashes"] = {p: sha(s.encode()) for p, s in tree["files"].items()}
        try:
            report["independent"] = independent(case, tree, directory, label)
            after = data(call("GET", "/api/v1/coding/projects/" + task, token))
            reread = data(call("GET", "/api/v1/coding/projects/" + task + "/files", token))
            report["taskUsageUnchangedByHoldout"] = after["usage"] == status["usage"]
            report["archivedFilesReadAgain"] = reread == tree
            report["completed"] = (status["status"] == "COMPLETED" and report["independent"]["passed"]
                and report["taskUsageUnchangedByHoldout"] and report["archivedFilesReadAgain"]
                and status["executionControl"]["executorStopped"] and not status["executionControl"]["reconciliationRequired"])
        except Exception as error:
            report["independentErrorType"] = type(error).__name__
    else:
        report["harnessObservationTimeout"] = True
    write(directory / "result-public.json", report)
    print(json.dumps({k: report.get(k) for k in ("case", "taskId", "status", "completed", "usage")}), flush=True)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixtures", required=True, type=Path)
    parser.add_argument("--token-file", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--cohort", required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--edit-strategy", choices=("auto", "editor", "patch"), default="auto")
    parser.add_argument("--cases", nargs="*")
    args = parser.parse_args()
    fixture = json.loads(args.fixtures.read_text())
    token = json.loads(args.token_file.read_text())["token"]
    selected = [case for case in fixture["cases"] if not args.cases or case["id"] in args.cases]
    reports = [run_case(case, args, token, fixture["fixtureSha256"]) for case in selected]
    write(args.output / f"{args.cohort}-r{args.repeat}-summary.json", {"scope": "ACTUAL_RUNTIME_AND_INDEPENDENT_HOLDOUT",
        "cases": len(reports), "completed": sum(r["completed"] for r in reports), "results": reports})
