import assert from "node:assert/strict";
import test from "node:test";
import { runtimeObservationIdentity, runtimeObservationPresentation } from "./runtimeObservationPresentation.js";

test("renders a structured DAG repair lifecycle without parsing log text", () => {
  assert.deepEqual(runtimeObservationPresentation({
    contentPreview: "arbitrary localized text",
    metadata: { eventKind: "DAG_REPAIR", eventState: "STARTED" }
  }), { title: "DAG 自动修复", toolName: "dag_repair", status: "repairing" });
  assert.deepEqual(runtimeObservationPresentation({
    metadata: { eventKind: "DAG_REPAIR", eventState: "APPLIED" }
  }), { title: "DAG 自动修复", toolName: "dag_repair", status: "repaired" });
  assert.deepEqual(runtimeObservationPresentation({
    metadata: { eventKind: "DAG_REPAIR", eventState: "REJECTED" }
  }), { title: "DAG 修复未通过", toolName: "dag_repair", status: "warning" });
  assert.equal(runtimeObservationIdentity({
    metadata: { eventKind: "DAG_REPAIR", repairAttempt: 2 }
  }), "dag-repair:2");
});

test("distinguishes a recoverable validation warning from a failed tool observation", () => {
  assert.equal(runtimeObservationPresentation({
    metadata: { eventKind: "DAG_VALIDATION", eventState: "FAILED" }
  }).status, "warning");
  assert.equal(runtimeObservationPresentation({
    metadata: { type: "tool_failure", success: false }
  }).status, "error");
});

test("renders analysis progress with business language instead of driver and worker terms", () => {
  assert.deepEqual(runtimeObservationPresentation({
    metadata: { type: "business_analysis_progress", stage: "DATA_PREPARATION_STARTED" }
  }), { title: "准备业务数据", toolName: "业务分析", status: "active" });
  assert.deepEqual(runtimeObservationPresentation({
    metadata: { type: "business_analysis_result_ready", stage: "BUSINESS_RESULT_READY" }
  }), { title: "业务分析完成", toolName: "业务分析", status: "done" });
  assert.deepEqual(runtimeObservationPresentation({
    metadata: { type: "business_analysis_partial_failure", stage: "PARTIAL_DATA_UNAVAILABLE" }
  }), { title: "部分业务数据未完成", toolName: "业务分析", status: "warning" });
});
