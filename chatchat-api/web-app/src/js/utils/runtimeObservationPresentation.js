function upper(value) {
  return String(value || "").trim().toUpperCase();
}

function objectValue(value) {
  return value && typeof value === "object" && !Array.isArray(value) ? value : {};
}

/**
 * Maps structured Runtime observation metadata to timeline semantics. The UI
 * must never infer failures or repairs by matching human-readable log text.
 */
export function runtimeObservationPresentation(runtimePayload = {}) {
  const metadata = objectValue(runtimePayload.metadata);
  const eventKind = upper(metadata.eventKind);
  const eventState = upper(metadata.eventState);
  const type = upper(metadata.type);
  const stage = upper(metadata.stage);
  if (eventKind === "MODEL_INFERENCE") {
    const phase = upper(metadata.modelPhase);
    const title = phase === "TOOL_RESULT_REVIEW" ? "模型审查工具结果" : "模型分析中";
    return {
      title,
      toolName: "model_inference",
      status: eventState === "FAILED" ? "error" : (eventState === "COMPLETED" ? "done" : "active")
    };
  }
  if (type.startsWith("BUSINESS_ANALYSIS_") || type === "BUSINESS_ANALYSIS_PROGRESS") {
    if (stage === "DATA_PREPARATION_STARTED") {
      return { title: "准备业务数据", toolName: "业务分析", status: "active" };
    }
    if (["RESULT_AGGREGATING", "RESULT_AGGREGATION_RETRYING"].includes(stage)) {
      return { title: "汇总分析结果", toolName: "业务分析", status: "active" };
    }
    if (["DATA_PROCESSING_COMPLETED", "BUSINESS_RESULT_READY"].includes(stage)) {
      return { title: "业务分析完成", toolName: "业务分析", status: "done" };
    }
    if (["PARTIAL_DATA_UNAVAILABLE", "DATA_PROCESSING_FAILED"].includes(stage)) {
      return { title: "部分业务数据未完成", toolName: "业务分析", status: "warning" };
    }
    if (stage === "DATA_PROCESSING_CANCELLED") {
      return { title: "业务分析已取消", toolName: "业务分析", status: "cancelled" };
    }
    return { title: "处理业务数据", toolName: "业务分析", status: "active" };
  }
  if (eventKind === "DAG_REPAIR") {
    if (eventState === "STARTED") {
      return { title: "DAG 自动修复", toolName: "dag_repair", status: "repairing" };
    }
    if (eventState === "APPLIED") {
      return { title: "DAG 自动修复", toolName: "dag_repair", status: "repaired" };
    }
    return { title: "DAG 修复未通过", toolName: "dag_repair", status: "warning" };
  }
  if (eventKind === "DAG_VALIDATION") {
    return eventState === "FAILED"
      ? { title: "检测到 DAG 漂移", toolName: "dag_validation", status: "warning" }
      : { title: "DAG 审核通过", toolName: "dag_validation", status: "done" };
  }
  // Compatibility for lifecycle events persisted by older Runtime builds. They
  // already carry typed validation flags, but did not yet publish eventKind.
  // Do not inspect contentPreview here: status must stay protocol-driven.
  if (type === "LIFECYCLE" && ("valid" in metadata || "executable" in metadata)) {
    const failed = metadata.valid === false || metadata.executable === false;
    return failed
      ? { title: "执行计划校验失败", toolName: "dag_validation", status: "warning" }
      : { title: "执行计划校验通过", toolName: "dag_validation", status: "done" };
  }
  if (type === "LIFECYCLE") {
    const lifecyclePhase = upper(metadata.lifecyclePhase);
    const phases = {
      PROBLEM_IDENTIFICATION: { title: "理解问题", status: "done" },
      TOOL_DISCOVERY: { title: "发现可用能力", status: "done" },
      PLAN_GENERATION: { title: "生成执行计划", status: "done" },
      PLAN_EVALUATION: { title: "校验执行计划", status: "done" },
      STEP_EXECUTION: { title: "执行计划", status: "active" },
      FINAL_SYNTHESIS: { title: "生成回答", status: "active" },
      DRIVER_CHALLENGE_REPAIR: { title: "补充分析", status: "active" }
    };
    return {
      ...(phases[lifecyclePhase] || { title: "后端处理中", status: "active" }),
      toolName: "agent_lifecycle"
    };
  }
  if (metadata.success === false || type === "TOOL_FAILURE") {
    return { title: "工具执行失败", status: "error" };
  }
  return null;
}

export function runtimeObservationIdentity(runtimePayload = {}) {
  const metadata = objectValue(runtimePayload.metadata);
  if (upper(metadata.eventKind) === "MODEL_INFERENCE") {
    return `model-inference:${metadata.modelPhase || "unknown"}:${metadata.stepId || "run"}`;
  }
  if (upper(metadata.type) === "BUSINESS_ANALYSIS_PROGRESS" && metadata.progressId) {
    return `business-analysis:${metadata.progressId}`;
  }
  if (upper(metadata.eventKind) === "DAG_VALIDATION"
      || (upper(metadata.type) === "LIFECYCLE" && ("valid" in metadata || "executable" in metadata))) {
    return `dag-validation:${metadata.stage || "unknown"}`;
  }
  if (upper(metadata.type) === "LIFECYCLE" && metadata.lifecyclePhase) {
    return `lifecycle:${metadata.lifecyclePhase}:${metadata.stage || "run"}`;
  }
  if (upper(metadata.eventKind) !== "DAG_REPAIR") return "";
  const repairEvent = objectValue(metadata.repairEvent);
  const attempt = metadata.repairAttempt ?? repairEvent.repairAttempt;
  return attempt === undefined || attempt === null || String(attempt).trim() === ""
    ? ""
    : `dag-repair:${attempt}`;
}
