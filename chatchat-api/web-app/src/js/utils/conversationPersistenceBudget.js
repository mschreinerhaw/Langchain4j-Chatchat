const MAX_MESSAGE_CONTENT_BYTES = 128 * 1024;
const MAX_STRUCTURED_FIELD_BYTES = 64 * 1024;
const MAX_ANALYSIS_TREE_BYTES = 128 * 1024;
const MAX_CONVERSATION_MESSAGES_BYTES = 1200 * 1024;
const MAX_NESTED_STRING_BYTES = 16 * 1024;
const MAX_COLLECTION_ENTRIES = 100;
const MAX_DEPTH = 6;
const UTF8_ENCODER = new TextEncoder();

export function utf8Size(value) {
  return UTF8_ENCODER.encode(String(value ?? "")).byteLength;
}

function truncateString(value, maxBytes, label = "content") {
  const text = String(value ?? "");
  const originalBytes = utf8Size(text);
  if (originalBytes <= maxBytes) {
    return text;
  }
  const marker = `\n...[${label} truncated; originalBytes=${originalBytes}]`;
  const contentBudget = Math.max(0, maxBytes - utf8Size(marker));
  let low = 0;
  let high = text.length;
  while (low < high) {
    const middle = Math.ceil((low + high) / 2);
    if (utf8Size(text.slice(0, middle)) <= contentBudget) {
      low = middle;
    } else {
      high = middle - 1;
    }
  }
  return text.slice(0, low) + marker;
}

function compactNested(value, depth = 0, seen = new WeakSet()) {
  if (typeof value === "string") {
    return truncateString(value, MAX_NESTED_STRING_BYTES, "nested value");
  }
  if (value == null || typeof value !== "object") {
    return value;
  }
  if (depth >= MAX_DEPTH) {
    return "[nested value omitted: depth limit]";
  }
  if (seen.has(value)) {
    return "[circular value omitted]";
  }
  seen.add(value);
  if (Array.isArray(value)) {
    const result = value.slice(0, MAX_COLLECTION_ENTRIES)
      .map((item) => compactNested(item, depth + 1, seen));
    if (value.length > MAX_COLLECTION_ENTRIES) {
      result.push({ truncated: true, omittedEntries: value.length - MAX_COLLECTION_ENTRIES });
    }
    seen.delete(value);
    return result;
  }
  const result = {};
  const entries = Object.entries(value);
  for (const [key, item] of entries.slice(0, MAX_COLLECTION_ENTRIES)) {
    result[key] = compactNested(item, depth + 1, seen);
  }
  if (entries.length > MAX_COLLECTION_ENTRIES) {
    result.persistenceTruncated = true;
    result.omittedEntries = entries.length - MAX_COLLECTION_ENTRIES;
  }
  seen.delete(value);
  return result;
}

export function compactPersistenceValue(value, maxBytes = MAX_STRUCTURED_FIELD_BYTES) {
  const compacted = compactNested(value);
  let serialized;
  try {
    serialized = JSON.stringify(compacted);
  } catch (_error) {
    return { persistenceTruncated: true, reason: "SERIALIZATION_FAILED" };
  }
  const serializedBytes = utf8Size(serialized);
  if (serializedBytes <= maxBytes) {
    return compacted;
  }
  return {
    persistenceTruncated: true,
    originalBytes: serializedBytes,
    preview: truncateString(serialized, Math.max(1024, maxBytes - 128), "structured field")
  };
}

export function compactMessageForPersistence(message = {}) {
  return {
    id: message.id,
    role: message.role,
    content: truncateString(message.content, MAX_MESSAGE_CONTENT_BYTES, "message content"),
    timestamp: message.timestamp,
    sources: compactPersistenceValue(message.sources || []),
    traces: compactPersistenceValue(message.traces || []),
    steps: compactPersistenceValue(message.steps || []),
    visualizationSpec: message.visualizationSpec
      ? compactPersistenceValue(message.visualizationSpec, MAX_ANALYSIS_TREE_BYTES)
      : null,
    uiResponse: message.uiResponse ? compactPersistenceValue(message.uiResponse) : null,
    evidencePremises: compactPersistenceValue(message.evidencePremises || []),
    agentName: truncateString(message.agentName, 1024, "agent name"),
    modelName: truncateString(message.modelName, 1024, "model name"),
    analysisNodeId: truncateString(message.analysisNodeId, 1024, "analysis node id"),
    analysisParentNodeId: truncateString(message.analysisParentNodeId, 1024, "analysis parent node id"),
    analysisSourceMessageId: truncateString(message.analysisSourceMessageId, 1024, "analysis source message id"),
    analysisSelection: message.analysisSelection ? compactPersistenceValue(message.analysisSelection, 32 * 1024) : null,
    streaming: !!message.streaming,
    status: truncateString(message.status || (message.streaming ? "streaming" : "completed"), 128, "status"),
    taskId: truncateString(message.taskId, 1024, "task id"),
    feedbackTime: truncateString(message.feedbackTime, 128, "feedback time"),
    feedbackAction: truncateString(message.feedbackAction, 128, "feedback action"),
    feedbackUseful: message.feedbackUseful,
    feedbackAdopted: message.feedbackAdopted,
    feedbackResolved: message.feedbackResolved,
    feedbackComment: truncateString(message.feedbackComment, 4000, "feedback comment"),
    feedbackReasonCategory: truncateString(message.feedbackReasonCategory, 64, "feedback reason category")
  };
}

export function boundMessagesForPersistence(messages = []) {
  const projected = (Array.isArray(messages) ? messages : []).map(compactMessageForPersistence);
  const retainedNewestFirst = [];
  let usedChars = 2;
  for (let index = projected.length - 1; index >= 0; index -= 1) {
    const message = projected[index];
    const messageBytes = utf8Size(JSON.stringify(message)) + 1;
    if (usedChars + messageBytes > MAX_CONVERSATION_MESSAGES_BYTES) {
      break;
    }
    retainedNewestFirst.push(message);
    usedChars += messageBytes;
  }
  const retained = retainedNewestFirst.reverse();
  if (retained.length === 0 && projected.length > 0) {
    const latest = projected.at(-1);
    return [{
      ...latest,
      sources: [],
      traces: [],
      steps: [],
      visualizationSpec: null,
      uiResponse: null,
      evidencePremises: [],
      analysisSelection: null,
      persistenceHistoryTruncated: true,
      omittedOlderMessages: projected.length - 1,
      persistenceRichFieldsOmitted: true
    }];
  }
  if (retained.length < projected.length && retained.length > 0) {
    retained[0] = {
      ...retained[0],
      persistenceHistoryTruncated: true,
      omittedOlderMessages: projected.length - retained.length
    };
  }
  return retained;
}

export function compactAnalysisTreeForPersistence(analysisTree) {
  return compactPersistenceValue(analysisTree || {}, MAX_ANALYSIS_TREE_BYTES);
}

export function boundQuestionForPersistence(question) {
  return truncateString(question, MAX_MESSAGE_CONTENT_BYTES, "question");
}
