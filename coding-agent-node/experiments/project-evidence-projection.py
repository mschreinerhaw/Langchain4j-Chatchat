"""Project existing isolated receipts without exporting prompts, credentials or source."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import urllib.request

spec = importlib.util.spec_from_file_location("acceptance", Path(__file__).with_name("runtime-generic-acceptance.py"))
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)
DATABASE = "runtime_native_acceptance_api_20261107"
MODEL_FIELDS = ("selectedModel", "providerModel", "protocol", "durationMs", "startedAt", "finishedAt",
    "maxOutputTokens", "authorizedTools", "disclosedTools", "toolSchemaSha256", "requestBytes", "messageCount", "responseMode")


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def rows(task):
    assert re.fullmatch(r"native-[a-f0-9]{48}", task)
    metadata = json.loads(subprocess.check_output(["docker", "inspect", "postgres"]))[0]
    username = dict(value.split("=", 1) for value in metadata["Config"]["Env"] if "=" in value)["POSTGRES_USER"]
    query = ("select json_build_object('kind',e.entry_kind,'document',e.record_json::json) "
        "from runtime_evidence_entry e join runtime_evidence_partition p on p.id=e.partition_id where p.run_id='"
        + task + "' and e.entry_kind in ('EVIDENCE','EXEC_CHECKPOINT','EXEC_PAYLOAD') order by e.occurred_at")
    output = subprocess.check_output(["docker", "exec", "postgres", "psql", "-X", "-U", username, "-d", DATABASE, "-At", "-c", query], text=True)
    return [json.loads(line) for line in output.splitlines()]


def journal(records, task):
    for entry in records:
        item = entry["document"]
        if entry["kind"] != "EXEC_CHECKPOINT" or item.get("key") != "coding.tasks.v1":
            continue
        value = item.get("value")
        if value is None:
            parts = [row["document"] for row in records if row["kind"] == "EXEC_PAYLOAD"
                and row["document"].get("contentSha256") == item.get("contentSha256")]
            value = "".join(part["value"] for part in sorted(parts, key=lambda part: part["index"]))
        return json.loads(value)["tasks"][task]
    raise RuntimeError("No authoritative task journal found")


def project(directory, token):
    intent = json.loads((directory / "intent.json").read_text())
    task = intent["taskId"]
    records = rows(task)
    state = journal(records, task)
    models, tools = [], []
    for entry in records:
        if entry["kind"] != "EVIDENCE":
            continue
        record = entry["document"]
        value = record.get("payload", {})
        if record.get("evidenceType") == "CODING_MODEL_RESPONSE_RECEIPT":
            response = value["response"]
            info = response.get("modelInfo", {})
            models.append({"operationId": value["operationId"], "evidenceSha256": record["contentSha256"],
                "finishReason": response.get("finishReason"), "modelInfo": {key: info[key] for key in MODEL_FIELDS if key in info},
                "usage": response.get("usage"), "toolCalls": [{"id": tool.get("id"), "name": tool.get("name"),
                    "inputSha256": digest(tool.get("input"))} for tool in response.get("toolCalls", [])]})
        if value.get("executorScope") == "SDK_NATIVE_CODE_IO":
            tools.append({key: value.get(key) for key in ("operationId", "tool", "executorStopped", "resultSource", "resultHash")})
    assets = []
    for offset in range(0, 1000, 100):
        page = h.data(h.call("GET", "/api/v1/interactions/user-assets?taskId=" + task
            + "&kind=ARTIFACT&personalOnly=false&limit=100&offset=" + str(offset), token))
        assets.extend(page)
        if len(page) < 100:
            break
    indexed = {asset["sourceRef"]: asset for asset in assets}
    observations = []
    for tool in tools:
        asset = indexed[tool["resultSource"]]
        assert asset["taskId"] == task and asset["runId"] == task and asset["sha256"] == tool["resultHash"]
        request = urllib.request.Request("http://127.0.0.1:18280/api/v1/interactions/user-assets/" + asset["id"] + "/content",
            headers={"Authorization": "Bearer " + token})
        with urllib.request.urlopen(request, timeout=30) as response:
            content = response.read(1048577)
        assert h.sha(content) == tool["resultHash"] and len(content) == asset["size"]
        original = json.loads(content)
        tool["archivedResultReadAndHashMatched"] = True
        tool["isError"] = original.get("isError")
        output_entries = original.get("output", [])
        tool["nativeEntryFailures"] = sum(isinstance(item, dict) and item.get("success") is False
            for item in output_entries) if isinstance(output_entries, list) else 0
        tool["admittedResultFailed"] = original.get("isError") is True or tool["nativeEntryFailures"] > 0
        if tool["tool"] != "run_commands":
            continue
        outputs = original.get("output", [])
        for output in outputs if isinstance(outputs, list) else [outputs]:
            if not isinstance(output, dict):
                continue
            observation = {"toolOperationId": tool["operationId"], "success": output.get("success"), "outputSha256": digest(output)}
            if output.get("error"):
                observation["errorSha256"] = h.sha(str(output["error"]).encode())
            try:
                wire = output.get("result") or output.get("error") or ""
                if isinstance(wire, dict):
                    result = wire
                else:
                    # SDK CommandExitError wraps the real receipt in its error string.
                    # Parse that original JSON only; never manufacture a failed result.
                    begin = wire.find("{")
                    if begin < 0:
                        raise ValueError("No embedded command receipt")
                    result, _ = json.JSONDecoder().raw_decode(wire[begin:])
                if not isinstance(result, dict):
                    raise ValueError("Command receipt is not an object")
                for key in ("operationId", "manifestHash", "commandHash", "sourceHash", "exitCode", "executorStopped",
                        "resultAvailable", "stopProofRef", "resultArtifactSha256", "executorHelperSha256", "stdoutBytes", "stderrBytes",
                        "stdoutTailOnly", "stderrTailOnly", "truncated", "verificationPolicy", "declaredCommandMatched", "declaredCommandSha256"):
                    if key in result:
                        observation[key] = result[key]
                for stream in ("stdout", "stderr"):
                    observation[stream + "VisibleSha256"] = h.sha(result.get(stream, "").encode())
                text = result.get("stdout", "") + "\n" + result.get("stderr", "")
                observation["pythonDiagnosticCodes"] = sorted(set(re.findall(
                    r"(?:^|\n)\s*(SyntaxError|KeyError|NameError|TypeError|ImportError|ModuleNotFoundError|AssertionError|UnboundLocalError):", text)))
                observation["streamHashScope"] = "MODEL_VISIBLE_TEXT_POSSIBLY_TAIL; full artifact has a separate Hash"
            except (ValueError, TypeError):
                pass
            observations.append(observation)
    executions = []
    for operation, value in state["operations"].items():
        if value.get("kind") != "CODE":
            continue
        prepared = state["privateState"].get("native-command-input:" + operation, {})
        executions.append({"operationId": operation,
            **{key: value.get(key) for key in ("kind", "intentSha256", "startIntent", "startingFencingToken", "startSequence", "stopProofRef", "actual", "reservation", "meteringComplete")},
            "snapshotBinding": {key: prepared.get(key) for key in ("toolCallId", "modelOperationId", "manifestHash", "commandHash", "sourceHash", "fileHash", "commandOrdinal", "timeoutMs", "executorHelperSha256")}})
    result = {"scope": "WHITELISTED_REAL_RECEIPTS_AND_SILO_HASH_RECHECK", "taskId": task, "sourceCommit": intent["sourceCommit"],
        "modelReceipts": models, "toolStops": tools, "commandObservations": observations, "codeOperations": executions,
        "sdkCheckpoint": {key: state.get("sdkCheckpoint", {}).get(key) for key in
            ("sdkSessionId", "nativeSdk", "compactionCount", "checkpointOrdinal", "modelOrdinal", "contractHash")},
        "frozenBudget": state["contract"]["budget"],
        "frozenModel": state["contract"].get("runtimeContext", {}).get("model", {}),
        "privacy": "No private prompts, model free text, input arguments, raw source, stream text or credentials exported"}
    h.write(directory / "receipts-projection-public.json", result)
    print(json.dumps({"taskId": task, "models": len(models), "toolsReRead": len(tools), "codeOperations": len(executions)}), flush=True)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", type=Path, required=True)
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--cohort", required=True)
    parser.add_argument("--repeat", nargs="+", type=int, required=True)
    options = parser.parse_args()
    token = json.loads(options.token_file.read_text())["token"]
    results = [project(directory, token) for repeat in options.repeat
        for directory in sorted(options.runs.glob(options.cohort + "-*-r" + str(repeat)))]
    h.write(options.runs / (options.cohort + "-receipts-projection-summary.json"), {"results": results})
