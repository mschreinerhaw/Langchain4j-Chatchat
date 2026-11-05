import { describe, expect, it } from "vitest";
import { buildExplorationGraph, groupExplorationGraph } from "./explorationGraph.js";
const task = { taskId: "task", attemptId: "run", question: "Explore data", status: "RUNNING" };
describe("Runtime exploration fact projection", () => {
  it("shows a pending request immediately and updates it in place on receipt", () => {
    const base = { eventKind: "HARNESS_TURN", contextFingerprint: "stable", turn: 1, eventState: "RUNNING" };
    const started = { metadata: { ...base, reads: [{ status: "STARTED", request: { operation: "READ_RECORDS", datasetReference: "source" } }] } };
    const before = buildExplorationGraph(task, { observations: [started] });
    expect(before.nodes.find(node => node.id === "request:stable:0").status).toBe("STARTED");
    const after = buildExplorationGraph(task, { observations: [started, { metadata: { ...base,
      reads: [{ status: "ACCEPTED", request: { operation: "READ_RECORDS", datasetReference: "source" }, receipt: { requestId: "runtime-generated" } }] } }] });
    expect(after.nodes.filter(node => node.id === "request:stable:0")).toHaveLength(1);
    expect(after.nodes.find(node => node.id === "request:stable:0").status).toBe("ACCEPTED");
  });
  it("does not treat a model inference lifecycle record naming a tool as a remote invocation", () => {
    const graph = buildExplorationGraph(task, { observations: [
      { type: "lifecycle", source: "agent_lifecycle", metadata: { eventKind: "MODEL_INFERENCE", toolName: "read", eventState: "COMPLETED" } },
      { type: "tool", source: "read", metadata: { toolName: "read", evidenceId: "fact", interpretationPlanStepId: 1, success: true } },
      { type: "evidence", source: "evidence", metadata: { evidenceId: "fact", evidenceObject: { tool: "read", stepId: 1 } } }
    ] });
    expect(graph.nodes.filter(node => node.kind === "tool")).toHaveLength(1);
    expect(graph.edges).toContainEqual(expect.objectContaining({ source: "tool:fact", target: "evidence:fact", kind: "returns" }));
  });
  it("merges turn start and completion using the persistent context identity", () => {
    const graph = buildExplorationGraph(task, { observations: [
      { metadata: { eventKind: "HARNESS_TURN", contextFingerprint: "stable", turn: 1, eventState: "STARTED" } },
      { metadata: { eventKind: "HARNESS_TURN", contextFingerprint: "stable", turn: 1, eventState: "COMPLETED", modelDecision: { action: "CONTINUE" } } }
    ] });
    expect(graph.nodes.filter(node => node.kind === "intent")).toHaveLength(1);
    expect(graph.nodes.find(node => node.id === "turn:stable").status).toBe("COMPLETED");
    expect(graph.nodes.find(node => node.kind === "decision").label).toBe("继续探索");
  });
  it("links actual tool requests to their returned evidence without exposing payload previews", () => {
    const graph = buildExplorationGraph(task, { observations: [{ metadata: { eventKind: "HARNESS_TURN", turn: 1, reads: [
      { request: { operation: "CALL_TOOL", toolName: "read", requestId: "intent" }, receipt: { status: "SUCCESS", datasets: [{ datasetReference: "source", contentSha256: "hash" }] } }
    ] } }] });
    expect(graph.nodes.find(node => node.kind === "tool").status).toBe("SUCCESS");
    expect(graph.edges).toContainEqual(expect.objectContaining({ target: "evidence:source", kind: "returns" }));
  });
  it("preserves unknown execution outcomes instead of marking them successful", () => {
    const graph = buildExplorationGraph(task, { observations: [{ metadata: { eventKind: "HARNESS_TURN", turn: 1, reads: [
      { request: { toolName: "read" }, receipt: { status: "EXECUTION_OUTCOME_UNKNOWN" } }
    ] } }] });
    expect(graph.nodes.find(node => node.kind === "tool").status).toBe("EXECUTION_OUTCOME_UNKNOWN");
  });
  it("keeps completion distinct from a publication request", () => {
    for (const action of ["COMPLETE", "PARTIAL_COMPLETE", "PUBLISH"]) {
      const graph = buildExplorationGraph(task, { observations: [{ metadata: { eventKind: "HARNESS_TURN", turn: 1, modelDecision: { action } } }] });
      expect(graph.nodes.find(node => node.kind === "decision").detail.action).toBe(action);
      expect(graph.nodes.some(node => node.kind === "publication")).toBe(false);
    }
  });
  it("only renders hypotheses and findings explicitly declared by the model", () => {
    const graph = buildExplorationGraph(task, { observations: [{ metadata: { eventKind: "HARNESS_TURN", turn: 1,
      workspace: { state: { hypotheses: ["Try another time window"], findings: [{ summary: "Observed zero queue" }] } } } }] });
    expect(graph.nodes.filter(node => ["finding", "hypothesis"].includes(node.kind))).toHaveLength(2);
    expect(buildExplorationGraph(task).nodes).toHaveLength(1);
  });
  it("retains stable node identities as later turns arrive", () => {
    const first = { metadata: { eventKind: "HARNESS_TURN", contextFingerprint: "first", turn: 1 } };
    const before = buildExplorationGraph(task, { observations: [first] });
    const after = buildExplorationGraph(task, { observations: [first, { metadata: { eventKind: "HARNESS_TURN", contextFingerprint: "next", turn: 2 } }] });
    expect(after.nodes.map(node => node.id)).toEqual(expect.arrayContaining(before.nodes.map(node => node.id)));
    expect(after.edges.every(edge => after.nodes.some(node => node.id === edge.source) && after.nodes.some(node => node.id === edge.target))).toBe(true);
  });
  it("renders the model's declared Final independently of COMPLETE and never infers Draft", () => {
    const observation = { metadata: { eventKind: "HARNESS_TURN", turn: 1, modelDecision: { action: "COMPLETE" },
      modelOutput: { author: "MODEL", type: "FINAL", target: "CURRENT_SESSION" } } };
    const graph = buildExplorationGraph(task, { observations: [observation] });
    expect(graph.nodes.find(node => node.kind === "output").label).toBe("模型最终回答");
    delete observation.metadata.modelOutput;
    expect(buildExplorationGraph(task, { observations: [observation] }).nodes.some(node => node.kind === "output")).toBe(false);
  });

});

describe("exploration event grouping", () => {
  const timeline = { observations: [
    { id: "one", type: "tool", source: "coding_agent", content: "first", metadata: { status: "FAILED" } },
    { id: "two", type: "tool", source: "coding_agent", content: "second", metadata: { status: "SUCCESS" } },
    { id: "three", type: "tool", source: "another_source", metadata: { status: "SUCCESS" } },
  ] };
  it("renders one node per type, counts original nodes, and retains every detail without mutating facts", () => {
    const raw = buildExplorationGraph(task, timeline);
    const snapshot = JSON.stringify(raw);
    const graph = groupExplorationGraph(raw);
    expect(graph.nodes).toHaveLength(2);
    expect(graph.originalNodeCount).toBe(4);
    const group = graph.nodes.find(node => node.kind === "observation");
    expect(group.count).toBe(3);
    expect(group.members.map(node => node.id)).toEqual(["observation:one", "observation:two", "observation:three"]);
    expect(group.detail.events[0].detail.summary).toBe("first");
    expect(group.status).toBe("MIXED");
    expect(group.statusCounts).toEqual({ FAILED: 1, SUCCESS: 2 });
    expect(JSON.stringify(raw)).toBe(snapshot);
    expect(graph.edges).toHaveLength(1);
    expect(graph.edges[0]).toMatchObject({ count: 3, label: "实际观察 ×3" });
  });
  it("keeps group identities stable as new events arrive and updates pending facts instead of counting them twice", () => {
    const before = groupExplorationGraph(buildExplorationGraph(task, { observations: timeline.observations.slice(0, 1) }));
    const after = groupExplorationGraph(buildExplorationGraph(task, timeline));
    expect(after.nodes.map(node => node.id)).toEqual(before.nodes.map(node => node.id));
    const updated = groupExplorationGraph(buildExplorationGraph(task, { observations: [timeline.observations[0],
      { ...timeline.observations[0], metadata: { status: "SUCCESS" } }] }));
    expect(updated.nodes.find(node => node.kind === "observation")).toMatchObject({ count: 1, status: "SUCCESS" });
  });
  it("does not merge different event types or discard unknown states", () => {
    const graph = groupExplorationGraph(buildExplorationGraph(task, {}, [
      { eventId: "one", type: "STATUS", status: "SUCCESS" },
      { eventId: "two", type: "STATUS", status: "EXECUTION_OUTCOME_UNKNOWN" },
      { eventId: "three", type: "QUESTION", status: "OBSERVED" },
    ]));
    expect(graph.nodes.filter(node => node.kind === "event")).toHaveLength(2);
    const status = graph.nodes.find(node => node.eventType === "STATUS");
    expect(status).toMatchObject({ count: 2, status: "MIXED", statusCounts: { SUCCESS: 1, EXECUTION_OUTCOME_UNKNOWN: 1 } });
  });
  it("removes internal links and retains distinct external relationship types", () => {
    const graph = groupExplorationGraph({ nodes: [
      { id: "a", kind: "tool", status: "STARTED" },
      { id: "b", kind: "tool", status: "SUCCESS" },
      { id: "c", kind: "evidence", status: "SUCCESS" },
    ], edges: [
      { source: "a", target: "b", kind: "after" },
      { source: "a", target: "c", kind: "returns", label: "返回" },
      { source: "b", target: "c", kind: "returns", label: "返回" },
      { source: "c", target: "b", kind: "reads" },
      { source: "missing", target: "a", kind: "after" },
    ] });
    expect(graph.edges).toHaveLength(2);
    expect(graph.edges.find(edge => edge.kind === "returns").count).toBe(2);
    expect(graph.edges.every(edge => edge.source !== edge.target)).toBe(true);
    expect(groupExplorationGraph({ nodes: [], edges: [] })).toMatchObject({ nodes: [], edges: [], originalNodeCount: 0 });
  });
});
