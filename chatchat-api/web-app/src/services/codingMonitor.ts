import { apiRequest } from './api';

export interface CodingEvent {
  eventId: string; taskId: string; sequence: number; occurredAt: string; type: string;
  operationId?: string; attempt?: number; status: string; summary: string;
  payload?: Record<string, any>; evidenceRefs?: string[];
}
export interface CodingTask {
  modelReceipts?: CodingModelCallTelemetry[];
  taskId: string; attemptId: string; objective: string; canCancel: boolean; canRetry: boolean; canResume?: boolean;
  configuration: { model?: Record<string, any>; skills?: Record<string, any>; configuredSkillIds?: string[] };
  allowedTools: string[];
  outcome: { status: string; createdAt: string; updatedAt: string; endedAt?: string;
    cancellationRequested: boolean; reasonCode?: string; summary?: string; lastEventSequence: number; validationStatus?: string;
    executionControl?: { reconciliationRequired?: boolean; stopReason?: string } };
}
export const stageLabels: Record<string, string> = {
  QUEUED: '排队', CONTEXT_READY: '上下文已就绪', CODING: 'Cline 生成 / 修正代码',
  CODE_READY: '代码已返回', VALIDATING: 'Java 校验代码', EXECUTING: 'Sandbox 执行',
  RESULT_VALIDATING: '验证和登记结果', COMPLETED: '完成', FAILED: '失败',
  CANCELLED: '已取消', TIMED_OUT: '已超时', CANCELLING: '正在取消', RECONCILING: '等待异常恢复核对',
  WAITING_CONTEXT: '等待 Java 补充上下文', BUDGET_EXCEEDED: '预算耗尽', PARTIAL: '部分完成',
};
export function mergeCodingEvents(previous: CodingEvent[], incoming: CodingEvent[]): CodingEvent[] {
  const events = new Map(previous.map(event => [event.eventId, event]));
  for (const event of incoming) events.set(event.eventId, event);
  return [...events.values()].sort((a, b) => a.sequence - b.sequence || a.eventId.localeCompare(b.eventId));
}
export function codingStage(task: CodingTask, events: CodingEvent[]): string {
  const outcome = task.outcome;
  if (outcome.status === 'COMPLETED' && outcome.validationStatus === 'FAILED') return 'FAILED';
  if (outcome.reasonCode === 'DEADLINE_EXCEEDED' || outcome.executionControl?.stopReason === 'DEADLINE_EXCEEDED') return 'TIMED_OUT';
  if (!['CREATED', 'RUNNING'].includes(outcome.status)) return outcome.status;
  if (outcome.cancellationRequested) return 'CANCELLING';
  if (outcome.executionControl?.reconciliationRequired) return 'RECONCILING';
  for (const event of [...events].reverse()) {
    const payload = event.payload ?? {};
    if (payload.type === 'TOOL_STARTED' && payload.tool === 'data_request') return 'WAITING_CONTEXT';
    if (payload.type === 'TOOL_FINISHED' && payload.tool === 'data_request') return 'CONTEXT_READY';
    if (['CODE_READY', 'CONTEXT_READY', 'CODING', 'VALIDATING', 'EXECUTING', 'RESULT_VALIDATING'].includes(payload.stage)) return payload.stage;
    if (payload.type?.startsWith('MODEL_')) return 'CODING';
    if (event.type === 'CODE_COMMITTED' || event.type === 'VALIDATION_RECORDED') return 'RESULT_VALIDATING';
    if (event.type === 'EXECUTION_FINISHED' && event.operationId?.startsWith('cline-')) return 'RESULT_VALIDATING';
    if (event.type === 'EXECUTION_STARTED' && event.operationId?.startsWith('cline-')) return 'EXECUTING';
    if (payload.type === 'TOOL_STARTED' && payload.tool === 'python_execute') return 'VALIDATING';
  }
  return outcome.status === 'CREATED' ? 'QUEUED' : 'CONTEXT_READY';
}
export function codeVersions(events: CodingEvent[]) {
  const versions = new Map<string, Record<string, any>>();
  for (const event of events) {
    const p = event.payload ?? {};
    const operation = p.proposedOperationId ?? event.operationId;
    if (!operation?.startsWith('cline-')) continue;
    if (p.type === 'CODE_READY') versions.set(operation, {
      operationId: operation, hash: p.codeSha256, fileName: p.fileName, generatedAt: event.occurredAt, state: 'CODE_READY',
    });
    const version = versions.get(operation);
    if (version && ['VALIDATING', 'EXECUTING', 'RESULT_VALIDATING'].includes(p.stage)) version.state = p.stage;
    if (version && event.type === 'EXECUTION_STARTED') version.state = 'EXECUTING';
    if (version && event.type === 'CODE_COMMITTED') version.state = 'EXECUTED_ARCHIVED';
    if (version && p.type === 'TOOL_FINISHED') {
      version.validationStatus = p.validationStatus;
      version.outputStatus = p.outputStatus;
      version.state = p.validationStatus === 'FAILED' || ['INVALID_RESULT_SCHEMA', 'RESULT_VALIDATION_FAILED', 'SOURCE_COPY_REJECTED', 'REQUIRED_OUTPUT_MISSING'].includes(p.outputStatus)
        ? 'RESULT_VALIDATION_FAILED' : p.status === 'SUCCEEDED' ? 'EXECUTED' : 'FAILED';
    }
  }
  return [...versions.values()];
}
export interface CodingModelCallTelemetry {
  operationId: string; attemptId?: string; modelCall: number; state: string;
  selectedModel?: string; providerModel?: string; protocol?: string; startedAt?: string; finishedAt?: string;
  durationMs?: number; usage: { inputTokens?: number; outputTokens?: number }; source: 'JAVA_MODEL_RECEIPT';
}
export function modelCalls(events: CodingEvent[], receipts: CodingModelCallTelemetry[] = []) {
  const calls = new Map<string, Record<string, any>>();
  for (const event of events) {
    const p = event.payload ?? {};
    if (!p.type?.startsWith('MODEL_')) continue;
    const key = `${p.attemptId ?? ''}:${p.modelCall}`;
    const call = calls.get(key) ?? { id: key, ordinal: p.modelCall, state: 'RUNNING' };
    if (p.type === 'MODEL_STARTED') call.startedAt = event.occurredAt;
    if (p.type === 'MODEL_RESOLVED') Object.assign(call, p);
    if (p.type === 'MODEL_USAGE') Object.assign(call, { finishedAt: event.occurredAt, usage: p.usage, state: 'SUCCEEDED' });
    calls.set(key, call);
  }
  for (const receipt of receipts) {
    const key = `${receipt.attemptId ?? ''}:${receipt.modelCall}`;
    const call = calls.get(key) ?? { id: key, ordinal: receipt.modelCall };
    const usage = { ...(call.usage ?? {}), ...receipt.usage };
    Object.assign(call, Object.fromEntries(Object.entries(receipt).filter(([, value]) => value != null)), { usage });
    calls.set(key, call);
  }
  return [...calls.values()].sort((left, right) => left.ordinal - right.ordinal);
}
const root = (run: string) => `/agent/runtime/runs/${encodeURIComponent(run)}/coding`;
export interface CodingInputVersion {
  reference: string; fileId: string; fileName: string; sha256: string; rowCount: number; size: number; format: string;
  chunks?: { fileId: string; fileName: string; sha256: string; rowCount: number; size: number }[];
  schemaJson?: string; qualitySummary?: Record<string, unknown>;
  inputManifest?: { schemaVersion: string; schemaFileId: string; columns: Record<string, unknown>[] };
}
export const fetchCodingInputs = (run: string, task: string, operation?: string): Promise<{ binding: string; snapshots: CodingInputVersion[] }> =>
  apiRequest(`${root(run)}/${encodeURIComponent(task)}/inputs${operation ? `?operationId=${encodeURIComponent(operation)}` : ''}`);
export const fetchCodingTasks = (run: string): Promise<{ tasks: CodingTask[] }> => apiRequest(root(run));
export const fetchCodingEvents = (run: string, task: string, cursor = 0): Promise<{ events: CodingEvent[]; nextSequence: number; hasMore: boolean }> =>
  apiRequest(`${root(run)}/${encodeURIComponent(task)}/events?afterSequence=${cursor}&limit=128`);
export const fetchCodingCode = (run: string, task: string, operation: string): Promise<Record<string, any>> =>
  apiRequest(`${root(run)}/${encodeURIComponent(task)}/${encodeURIComponent(operation)}/code`);
export const cancelCodingTask = (run: string, task: string) =>
  apiRequest(`${root(run)}/${encodeURIComponent(task)}/cancel`, { method: 'POST' });
export const resumeCodingTask = (run: string, task: string) =>
  apiRequest(`${root(run)}/${encodeURIComponent(task)}/resume`, { method: 'POST' });
