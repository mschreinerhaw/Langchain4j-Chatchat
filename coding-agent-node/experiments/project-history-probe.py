"""Post-stop, read-only Silo version checks; never feeds results into the Agent."""
import argparse
import datetime
import hashlib
import importlib.util
import json
from pathlib import Path
import urllib.request

spec = importlib.util.spec_from_file_location("acceptance", Path(__file__).with_name("runtime-generic-acceptance.py"))
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)


def download(identifier, token):
    request = urllib.request.Request("http://127.0.0.1:18280/api/v1/interactions/user-assets/" + identifier + "/content",
        headers={"Authorization": "Bearer " + token})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read(1048577)


def probe(directory, case, token):
    intent = json.loads((directory / "intent.json").read_text())
    task = intent["taskId"]
    base = "/api/v1/coding/projects/" + task
    before = h.data(h.call("GET", base, token))
    assert before["status"] in h.TERMINAL and before["executionControl"]["executorStopped"]
    history = h.data(h.call("GET", base + "/history", token))
    assets = h.data(h.call("GET", "/api/v1/interactions/user-assets?taskId=" + task + "&kind=CODE&personalOnly=false&limit=100", token))
    assets = [item for item in assets if item["sourceRef"].startswith("native-code:")]
    assert len(assets) == len(history)
    root = directory / "history-probe"
    root.mkdir(exist_ok=False)
    started = int(datetime.datetime.fromisoformat(before["startedAt"].replace("Z", "+00:00")).timestamp() * 1000)
    versions = []
    for asset in reversed(assets):
        content = download(asset["id"], token)
        assert hashlib.sha256(content).hexdigest() == asset["sha256"] and len(content) == asset["size"]
        tree = json.loads(content)
        manifest = tree["manifest"]
        assert asset["taskId"] == task and asset["runId"] == task
        assert asset["sourceRef"] == "native-code:" + manifest["sha256"]
        assert any(item["manifest"] == manifest for item in history)
        body = {k: v for k, v in manifest.items() if k != "sha256"}
        assert h.sha(json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()) == manifest["sha256"]
        version = manifest["version"]
        output = root / ("v" + str(version))
        output.mkdir()
        independent = h.independent(case, tree, output, "history-" + directory.name + "-v" + str(version))
        reread = download(asset["id"], token)
        versions.append({"version": version, "manifestHash": manifest["sha256"], "assetSha256": asset["sha256"],
            "archivedAtEpochMs": asset["createdAt"], "elapsedToArchiveMs": max(0, asset["createdAt"] - started),
            "assetReadAgainIdentical": content == reread, "independent": independent})
    versions.sort(key=lambda item: item["version"])
    valid = [item for item in versions if item["independent"]["passed"]]
    after = h.data(h.call("GET", base, token))
    result = {"scope": "POST_STOP_HISTORICAL_SOURCE_REPLAY_NO_AGENT_FEEDBACK", "taskId": task, "case": case["id"],
        "sourceCommit": intent["sourceCommit"], "originalTaskStatus": before["status"], "versions": versions,
        "firstValidArchivedVersion": valid[0]["version"] if valid else None,
        "firstValidCodeTimeMs": valid[0]["elapsedToArchiveMs"] if valid else None,
        "timingDefinition": "First independently passing archived source version creation time minus original task startedAt; not a live success observation",
        "editVersionCount": len(versions) - 1, "repairRounds": None,
        "repairRoundLimitation": "Edit versions are not automatically equivalent to error-feedback repair rounds",
        "originalUsageUnchanged": before["usage"] == after["usage"],
        "originalTaskUnchanged": before == after}
    h.write(directory / "history-probe-public.json", result)
    print(json.dumps({k: result[k] for k in ("taskId", "case", "originalTaskStatus", "firstValidCodeTimeMs", "editVersionCount", "originalTaskUnchanged")}), flush=True)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", type=Path, required=True)
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--cohort", required=True)
    parser.add_argument("--repeat", nargs="+", type=int, required=True)
    options = parser.parse_args()
    cases = {case["id"]: case for case in json.loads(options.fixtures.read_text())["cases"]}
    token = json.loads(options.token_file.read_text())["token"]
    results = []
    for repeat in options.repeat:
        for directory in sorted(options.runs.glob(options.cohort + "-*-r" + str(repeat))):
            case = cases[json.loads((directory / "intent.json").read_text())["case"]]
            results.append(probe(directory, case, token))
    h.write(options.runs / (options.cohort + "-history-probe-summary.json"), {"results": results})
