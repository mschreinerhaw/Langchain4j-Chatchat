// Independent projection of Runtime facts. ARTEX inspired typed exploration links;
// no ARTEX implementation is copied and no analytical completion is inferred here.
const kinds = { goal: "分析目标", intent: "模型探索", tool: "工具调用", evidence: "数据证据", observation: "结果观察", hypothesis: "模型假设", finding: "模型发现", decision: "模型决定", event: "运行事件" };
const decisions = { CONTINUE: "继续探索", WAIT: "请求等待", COMPLETE: "完成（未请求发布）", PARTIAL_COMPLETE: "有限范围完成（未请求发布）", PUBLISH: "请求发布" };
const object = value => {
  if (value && typeof value === "object") return value;
  try { return JSON.parse(value || "{}"); } catch { return {}; }
};
export function buildExplorationGraph(task, timeline = {}, events = []) {
  if (!task?.taskId) return { nodes: [], edges: [] };
  const nodes = new Map(), edges = new Map();
  const root = `goal:${task.taskId}`;
  const add = (id, kind, label, detail = {}, status = "OBSERVED") => {
    nodes.set(id, { ...nodes.get(id), id, kind, label, fullLabelText: label, actionText: kinds[kind] || kind,
      status, statusText: status, statusLabel: kinds[kind] || kind, detail, detailText: detail.summary || detail.reason || "",
      toolName: detail.toolName || "", ...((detail.durationMs != null) ? { durationMs: detail.durationMs } : {}) });
    return id;
  };
  const link = (source, target, kind, label) => {
    if (source === target) return;
    const id = `${source}->${target}:${kind}`;
    edges.set(id, { id, source, target, kind, label });
  };
  add(root, "goal", task.question || "任务目标", { taskId: task.taskId, attemptId: task.attemptId }, task.status || "OBSERVED");
  let previous = root;
  const steps = [...(timeline.steps || [])].sort((a,b) => (a.stepNumber || a.step || 0) - (b.stepNumber || b.step || 0));
  for (const step of steps) {
    const id = add(`step:${step.stepNumber || step.step || step.id}`, "intent", step.toolName || step.actionType || step.action || "模型计划步骤", step,
      step.status || (step.success === true ? "SUCCESS" : step.success === false ? "FAILED" : "PLANNED"));
    link(previous, id, "observed_after", "执行顺序"); previous = id;
  }
  const turns = new Map();
  for (const [index, observation] of (timeline.observations || []).entries()) {
    const meta = observation.metadata || {};
    if (meta.eventKind !== "HARNESS_TURN") {
      if (observation.type === "evidence" && meta.evidenceId && meta.evidenceObject) {
        const fact = meta.evidenceObject;
        const id = add(`evidence:${meta.evidenceId}`, "evidence", meta.evidenceId,
          { evidenceId: meta.evidenceId, stepId: fact.stepId, toolName: fact.tool, iteration: fact.iteration });
        link(`tool:${meta.evidenceId}`, id, "returns", "返回证据");
        continue;
      }
      if (observation.source && observation.type === "tool") {
        const kind = meta.toolName ? "tool" : "observation";
        const id = add(meta.evidenceId ? `tool:${meta.evidenceId}` : `observation:${observation.observationId || observation.id || index}`, kind,
          meta.toolName || observation.source, { ...meta, summary: observation.content },
          meta.executionStatus || meta.status || (meta.success === true ? "SUCCESS" : meta.success === false ? "FAILED" : "OBSERVED"));
        link(meta.interpretationPlanStepId ? `step:${meta.interpretationPlanStepId}` : root, id, "records", "实际观察");
      }
      continue;
    }
    const identity = meta.contextFingerprint || `${observation.source || 'harness'}:${meta.turn}`;
    const prior = turns.get(identity) || {};
    turns.set(identity, { ...prior, ...meta, reads: meta.reads || prior.reads || [], detail: { ...prior.detail, ...meta } });
  }
  for (const [identity, turn] of turns) {
    const turnId = add(`turn:${identity}`, "intent", `第 ${turn.turn} 轮自主探索`, turn.detail, turn.eventState || "OBSERVED");
    link(previous, turnId, "observed_after", "继续探索"); previous = turnId;
    for (const [index, audit] of (turn.reads || []).entries()) {
      const receipt = { ...(audit.request || {}), ...audit, ...(audit.receipt || {}) };
      const origins = { COMMITTED_RESULT: "恢复已提交回执", PENDING_RESULT: "重试保存已有结果", BATCH_RECOVERY: "恢复批量执行", NEW_EXECUTION: "新执行请求" };
      if (receipt.receiptOrigin?.origin) receipt.summary = origins[receipt.receiptOrigin.origin] || receipt.receiptOrigin.origin;
      const requestId = `request:${identity}:${receipt.requestId || index}`;
      const request = add(requestId, receipt.toolName ? "tool" : "observation", receipt.toolName || receipt.operation || "证据读取", receipt, receipt.status || "OBSERVED");
      link(turnId, request, "requests", "请求 / 读取");
      if (receipt.datasetReference) {
        const source = add(`evidence:${receipt.datasetReference}`, "evidence", receipt.datasetReference, { datasetReference: receipt.datasetReference });
        link(source, request, "reads", "读取已有证据");
      }
      for (const dataset of receipt.datasets || []) {
        const id = add(`evidence:${dataset.datasetReference}`, "evidence", dataset.datasetReference, dataset);
        link(request, id, "returns", "返回证据");
      }
    }
    for (const kind of ["hypothesis", "finding"]) {
      const values = turn.workspace?.state?.[kind === "hypothesis" ? "hypotheses" : "findings"] || [];
      if (!Array.isArray(values)) continue;
      values.forEach((value,index) => {
        const detail = typeof value === "string" ? { summary: value } : object(value);
        const id = add(`${kind}:${identity}:${index}`, kind, detail.title || detail.summary || detail.name || kinds[kind], detail);
        link(turnId, id, "declares", "模型声明");
      });
    }
    if (turn.modelDecision?.action) {
      const action = turn.modelDecision.action;
      const id = add(`decision:${identity}`, "decision", decisions[action] || action, turn.modelDecision);
      link(turnId, id, "decides", "模型决定"); previous = id;
    }
  }
  // Task lifecycle events remain separately identified; adjacency is never claimed to be causation.
  for (const event of [...events].sort((a,b) => Number(a.sequence || 0) - Number(b.sequence || 0))) {
    if (!event.eventId) continue;
    const payload = object(event.payload);
    const id = add(`event:${event.eventId}`, "event", event.type || "运行事件", { ...payload, eventId: event.eventId, sequence: event.sequence }, event.status || "OBSERVED");
    link(root, id, "records", "运行记录");
  }
  return { schemaVersion: "runtime_exploration_view.v1", taskId: task.taskId, attemptId: task.attemptId,
    nodes: [...nodes.values()], edges: [...edges.values()].filter(edge => nodes.has(edge.source) && nodes.has(edge.target)) };
}
