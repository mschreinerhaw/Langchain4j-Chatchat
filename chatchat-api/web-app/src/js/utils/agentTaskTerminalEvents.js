const TERMINAL_STATUSES = new Set([
  "SUCCESS",
  "PARTIAL",
  "PARTIAL_SUCCESS",
  "EMPTY",
  "NO_PRESENTABLE_RESULT",
  "TIME_BUDGET_EXHAUSTED",
  "MODEL_BUDGET_EXHAUSTED",
  "FAILED",
  "CANCELLED",
  "REJECTED",
  "TIMEOUT_CANCELLED",
  "KILLED"
]);

const RESULT_EVENT_TYPES = new Set([
  "ANSWER",
  "RESULT",
  "ERROR",
  "COMPLETE",
  "NEEDS_CONFIRMATION",
  "RUNTIME_FAILED",
  "RUNTIME_CANCELLED"
]);

function eventType(event = {}) {
  return String(event?.type || "").toUpperCase();
}

function eventStatus(event = {}) {
  return String(event?.status || "").toUpperCase();
}

/**
 * A failed tool call is an execution-step result, not an Agent task result.
 * Only result/control event types may terminate polling.
 */
export function isTerminalAgentEvent(event = {}) {
  const type = eventType(event);
  const status = eventStatus(event);
  if (RESULT_EVENT_TYPES.has(type)) {
    return true;
  }
  if (type === "RUNTIME_CONFIRMATION") {
    return status === "WAIT_CONFIRMATION";
  }
  if (type === "STATUS" || !type) {
    return TERMINAL_STATUSES.has(status) || status === "WAIT_CONFIRMATION";
  }
  return false;
}

export function terminalEventFromEvents(events = []) {
  const terminalEvents = [...(Array.isArray(events) ? events : [])]
    .filter(isTerminalAgentEvent)
    .sort((left, right) => (left.createTime || left.timestamp || 0) - (right.createTime || right.timestamp || 0));
  return terminalEvents
    .filter((event) => ["ANSWER", "RESULT", "ERROR", "NEEDS_CONFIRMATION"].includes(eventType(event)))
    .at(-1)
    || terminalEvents.at(-1)
    || null;
}
