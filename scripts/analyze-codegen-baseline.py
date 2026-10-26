"""Reproduce historical codegen metrics without prompts, credentials or model calls."""
import json
from pathlib import Path


def summarize(path):
    data = json.loads(path.read_text(encoding="utf-8"))
    elapsed = data["observedDurationMs"]
    calls = data["modelTelemetry"]
    return {
        "evidence": path.name,
        "sourceCommit": data["sourceCommit"],
        "scope": "HISTORICAL_SINGLE_SAMPLE_NOT_CURRENT_PROVIDER_BASELINE",
        "language": data["language"],
        "taskDurationMs": elapsed,
        "modelCalls": data["modelCalls"],
        "modelDurationSumMs": sum(c["durationMs"] for c in calls),
        "unattributedWallMs": elapsed - sum(c["durationMs"] for c in calls),
        "calls": [{"ordinal": i, "durationMs": c["durationMs"],
                   "taskWallPercent": round(c["durationMs"] / elapsed * 100, 2),
                   "usage": c["usage"], "providerQueueMs": None,
                   "providerFirstTokenMs": None, "promptAvailable": False}
                  for i, c in enumerate(calls, 1)],
    }


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1] / "docs/studio-cline-history-20261021-evidence"
    print(json.dumps([summarize(root / f"{language}-verified-acceptance.json")
                      for language in ("sql", "python")], ensure_ascii=False, indent=2))
