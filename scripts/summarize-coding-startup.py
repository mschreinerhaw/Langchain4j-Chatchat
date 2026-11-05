"""Summarize actual receipts; unavailable measurements remain null. Never publish prompt text."""
import argparse
import datetime
import json
import math
from pathlib import Path

def milliseconds(value):
    if isinstance(value, (float, int)):
        return value
    if isinstance(value, str):
        return datetime.datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000
    return None

def summarize(path):
    record = json.loads(path.read_text(encoding="utf-8-sig"))
    events = record.get("events", [])
    start = milliseconds(events[0]["occurredAt"]) if events else None
    calls = record.get("modelCalls", [])
    timings = [e["payload"] for e in events if e.get("payload", {}).get("type") == "MODEL_TIMING"]
    session = next((e["payload"] for e in events if e.get("payload", {}).get("type") == "SESSION_TIMING"), {})
    model_started = next((milliseconds(e["occurredAt"]) for e in events if e.get("payload", {}).get("type") == "MODEL_STARTED"), None)
    finished = next((e for e in events if e.get("payload", {}).get("type") == "TOOL_FINISHED"
                     and e["payload"].get("status") == "SUCCEEDED"), None)
    first_cline_tool = next((e for e in events if e.get("payload", {}).get("type") == "TOOL_FINISHED"
                             and e["payload"].get("status") == "SUCCEEDED"
                             and model_started is not None and milliseconds(e["occurredAt"]) > model_started
                             and e["payload"].get("stage") != "CONTEXT_READY"
                             and e["payload"].get("tool") != "tool_describe"), None)
    plan = next((e for e in events if e.get("payload", {}).get("type") == "PLAN_READY"), None)
    usages = [c.get("usage") for c in calls]
    known = bool(calls) and all(isinstance(u, dict) and isinstance(u.get("inputTokens"), int)
                                and isinstance(u.get("outputTokens"), int) for u in usages)
    model = [c.get("modelInfo", {}) for c in calls]
    return {"case": path.stem, "workloadSha256": record["workloadSha256"], "model": record["model"],
            "status": record["status"], "wallMs": record["wallMs"], "callsWithReceipts": len(calls),
            "actualInputTokens": sum(u["inputTokens"] for u in usages) if known else None,
            "actualOutputTokens": sum(u["outputTokens"] for u in usages) if known else None,
            "firstActualInputTokens": usages[0]["inputTokens"] if known else None,
            "providerFirstTokenMs": model[0].get("firstTokenMs") if model else None,
            "initialModelResponseMs": round(milliseconds(model[0]["finishedAt"]) - start) if model and start else None,
            "generatedInitialPlanMs": round(milliseconds(plan["occurredAt"]) - start) if plan and start else None,
            "firstValidPlanMs": round(max(milliseconds(plan["occurredAt"]),milliseconds(first_cline_tool["occurredAt"]))-start) if plan and first_cline_tool and start else None,
            "validPlanAvailability": "CLINE_PLAN_AND_SUCCESSFUL_AUTHORIZED_TOOL" if plan and first_cline_tool else "NO_SEPARATE_CLINE_PLAN_RECEIPT",
            "firstSuccessfulToolMs": round(milliseconds(finished["occurredAt"]) - start) if finished and start else None,
            "firstSuccessfulTool": finished["payload"].get("tool") if finished else None,
            "firstClineToolMs": round(milliseconds(first_cline_tool["occurredAt"])-start) if first_cline_tool and start else None,
            "rawEventCount": len(events), "sessionTiming": session,
            "requestCategories": [{k: v for k, v in t.items() if k.startswith("context") and k.endswith("Bytes")}
                                  for t in timings],
            "avoidableRepeatedContextBytes": sum(t.get("avoidableRepeatedContextBytes", 0) for t in timings),
            "modelDurationMs": [m.get("durationMs") for m in model]}

def percentiles(rows):
    result = {}
    for key in ["wallMs", "actualInputTokens", "actualOutputTokens", "callsWithReceipts",
                "firstActualInputTokens", "initialModelResponseMs", "firstSuccessfulToolMs", "firstClineToolMs", "generatedInitialPlanMs", "firstValidPlanMs", "providerFirstTokenMs"]:
        values = sorted(row[key] for row in rows if row[key] is not None)
        result[key] = {"n": len(values), **{f"p{q}": values[math.ceil(len(values)*q/100)-1] if values else None
                                           for q in (50, 95, 99)}}
    return result

parser = argparse.ArgumentParser()
parser.add_argument("--variant", action="append", required=True, help="label=directory")
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
variants = {}
for value in args.variant:
    label, directory = value.split("=", 1)
    rows = [summarize(path) for path in sorted(Path(directory).glob("*.json"))]
    variants[label] = {"tasks": rows, "percentiles": percentiles(rows),
                       "successes": sum(r["status"] == "COMPLETED" for r in rows), "attempted": len(rows)}
baseline = next(iter(variants.values()))["tasks"]
for variant in variants.values():
    pairs = {r["case"]: r for r in variant["tasks"]}
    for row in baseline:
        candidate = pairs.get(row["case"])
        if candidate and (candidate["workloadSha256"], candidate["model"]) != (row["workloadSha256"], row["model"]):
            raise ValueError("Workload/model mismatch; cannot label this a paired comparison")
report = {"schemaVersion": "coding.startup.comparison.v1", "productionModified": False,
          "scope": "ISOLATED_REAL_PROVIDER_JAVA_NODE_H2_SOURCE_CANDIDATES_NO_CODE_EXECUTION",
          "quantileMethod": "NEAREST_RANK_DESCRIPTIVE_ONLY_HETEROGENEOUS_N4_NOT_PRODUCTION_SLO",
          "acceptance": "PENDING_REPRESENTATIVE_COHORT_AND_TAIL_LATENCY", "variants": variants}
args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False)+"\n", encoding="utf-8")
print(json.dumps({label: {"attempted": data["attempted"], "successes": data["successes"],
                         "knownInputTokens": sum(r["actualInputTokens"] or 0 for r in data["tasks"])}
                  for label, data in variants.items()}))
