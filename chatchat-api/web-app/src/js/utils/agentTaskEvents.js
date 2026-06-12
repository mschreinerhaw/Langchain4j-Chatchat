const AGENT_TASK_CANCELLED_EVENT = "chatchat:agent-task-cancelled";
const AGENT_TASK_CANCELLED_STORAGE_KEY = "chatchat.agentTask.cancelled";

function normalizeCancelledTask(task = {}) {
  return {
    taskId: task.taskId || "",
    tenantId: task.tenantId || "",
    userId: task.userId || "",
    agentId: task.agentId || "",
    sessionId: task.sessionId || task.conversationId || "",
    question: task.question || "",
    status: "CANCELLED",
    message: task.message || task.errorMessage || "Agent task cancelled",
    timestamp: Date.now()
  };
}

export function notifyAgentTaskCancelled(task = {}) {
  if (typeof window === "undefined") {
    return;
  }
  const detail = normalizeCancelledTask(task);
  window.dispatchEvent(new CustomEvent(AGENT_TASK_CANCELLED_EVENT, { detail }));
  try {
    window.localStorage.setItem(AGENT_TASK_CANCELLED_STORAGE_KEY, JSON.stringify(detail));
  } catch (error) {
    // Cross-tab notification is best effort; same-tab events already fired.
  }
}

export function onAgentTaskCancelled(handler) {
  if (typeof window === "undefined" || typeof handler !== "function") {
    return () => {};
  }

  const handleEvent = (event) => {
    handler(event?.detail || {});
  };
  const handleStorage = (event) => {
    if (event.key !== AGENT_TASK_CANCELLED_STORAGE_KEY || !event.newValue) {
      return;
    }
    try {
      handler(JSON.parse(event.newValue));
    } catch (error) {
      handler({});
    }
  };

  window.addEventListener(AGENT_TASK_CANCELLED_EVENT, handleEvent);
  window.addEventListener("storage", handleStorage);
  return () => {
    window.removeEventListener(AGENT_TASK_CANCELLED_EVENT, handleEvent);
    window.removeEventListener("storage", handleStorage);
  };
}
