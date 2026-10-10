import { describe, expect, it } from "vitest";
import { buildExplorationGraph } from "./explorationGraph.js";
const task = { taskId: "task", attemptId: "run", question: "Explore data", status: "RUNNING" };
describe("Runtime exploration fact projection", () => {
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
});
