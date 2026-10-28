const TERMINAL_STATUSES = new Set([
  "SUCCEEDED",
  "COMPLETED",
  "DLQ",
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

export function isTerminalAgentTaskSnapshot(snapshot = {}) {
  return TERMINAL_STATUSES.has(String(snapshot?.canonicalState || snapshot?.status || '').toUpperCase());
}

function outcome(status) {
  const value = String(status || '').toUpperCase();
  if (['SUCCESS', 'SUCCEEDED', 'COMPLETED'].includes(value)) return 'success';
  if (['PARTIAL', 'PARTIAL_SUCCESS', 'EMPTY', 'NO_PRESENTABLE_RESULT'].includes(value)) return 'partial';
  if (['CANCELLED', 'KILLED', 'TIMEOUT_CANCELLED'].includes(value)) return 'cancelled';
  if (['FAILED', 'DLQ', 'TIME_BUDGET_EXHAUSTED', 'MODEL_BUDGET_EXHAUSTED'].includes(value)) return 'failed';
  if (['WAIT_CONFIRMATION', 'WAITING_CONFIRM', 'WAITING_APPROVAL'].includes(value)) return 'waiting';
  return value;
}

// Events are observations from tools, model rounds and attempts. Only a fresh
// task snapshot can confirm completion of the task being watched.
export function terminalEventForSnapshot(events = [], snapshot = {}) {
  const state = snapshot?.canonicalState || snapshot?.status;
  const expected = outcome(state);
  if (!isTerminalAgentTaskSnapshot(snapshot) && expected !== 'waiting') return null;
  const candidates = events.filter(event => isTerminalAgentEvent(event)
    && (!snapshot.attemptId || !event.attemptId || event.attemptId === snapshot.attemptId)
    && outcome(event.status) === expected);
  const event = terminalEventFromEvents(candidates);
  if (event) return event;
  const status = outcome(snapshot.status) === expected ? snapshot.status : state;
  return { type: expected === 'waiting' ? 'NEEDS_CONFIRMATION' : 'STATUS',
    status, attemptId: snapshot.attemptId,
    payload: { status, answer: snapshot.answerSummary || '', message: snapshot.errorMessage || '' } };
}

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
    .sort((left, right) => Number(left.sequence || 0) - Number(right.sequence || 0)
      || (left.createTime || left.timestamp || 0) - (right.createTime || right.timestamp || 0));
  return terminalEvents
    .filter((event) => ["ANSWER", "RESULT", "ERROR", "NEEDS_CONFIRMATION"].includes(eventType(event)))
    .at(-1)
    || terminalEvents.at(-1)
    || null;
}
