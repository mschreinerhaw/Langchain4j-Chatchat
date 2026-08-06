import assert from "node:assert/strict";
import test from "node:test";

import { isTerminalAgentEvent, terminalEventFromEvents } from "./agentTaskTerminalEvents.js";

test("failed tool result does not terminate an Agent task with later partial result", () => {
  const failedTool = {
    type: "TOOL_RESULT",
    status: "FAILED",
    sequence: 71,
    createTime: 100
  };
  const partialResult = {
    type: "RESULT",
    status: "PARTIAL_SUCCESS",
    sequence: 81,
    createTime: 200,
    payload: { answer: "available evidence" }
  };

  assert.equal(isTerminalAgentEvent(failedTool), false);
  assert.equal(isTerminalAgentEvent(partialResult), true);
  assert.equal(terminalEventFromEvents([failedTool, partialResult]), partialResult);
});

test("fatal task events and explicit terminal status events still terminate polling", () => {
  assert.equal(isTerminalAgentEvent({ type: "ERROR", status: "FAILED" }), true);
  assert.equal(isTerminalAgentEvent({ type: "RUNTIME_FAILED", status: "FAILED" }), true);
  assert.equal(isTerminalAgentEvent({ type: "STATUS", status: "CANCELLED" }), true);
  assert.equal(isTerminalAgentEvent({ type: "STATUS", status: "REJECTED" }), true);
  assert.equal(isTerminalAgentEvent({ type: "STATUS", status: "TIMEOUT_CANCELLED" }), true);
  assert.equal(isTerminalAgentEvent({ type: "STATUS", status: "KILLED" }), true);
  assert.equal(isTerminalAgentEvent({ type: "RUNTIME_CONFIRMATION", status: "WAIT_CONFIRMATION" }), true);
});
