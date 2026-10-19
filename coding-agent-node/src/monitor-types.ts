/** Public, read-only component contract. No SDK checkpoints or provider credentials. */
export type TaskState = 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'INTERRUPTED';
export interface MonitorEvent {
  cursor: number; timestamp: string; type: string; stage?: string; status?: string;
  operationId?: string; modelCall?: number; tool?: string; [key: string]: unknown;
}
export interface ModelCall {
  ordinal: number; status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN';
  startedAt: string; finishedAt: string | null; durationMs: number | null;
  selectedModel?: string; providerModel?: string; protocol?: string;
  usage: { inputTokens: number | null; outputTokens: number | null; totalCost: number | null };
}
export interface CodeVersion {
  id: string; operationId: string; fileName: string; sha256: string; sourceBytes: number;
  createdAt: string; sourceRetained: boolean; source?: string;
}
export interface SkillInfo { skillId: string; name?: string; version?: string }
export interface TaskSummary {
  id: string; taskId: string; runId: string; tenantId: string; userId: string; fencingToken: number;
  status: TaskState; stage: string; objective: string; createdAt: string; updatedAt: string;
  finishedAt: string | null; deadlineAt: string | null; elapsedMs: number;
  recoveryMode: string; reconciliationRequired: boolean; error: string | null;
  eventCount: number; retainedEvents: number; codeCount: number; modelCalls: number;
  inputTokens: number; outputTokens: number; usageKnownCalls: number;
}
export interface TaskDetail extends TaskSummary {
  configuredModel: { selectedModel?: string; providerModel?: string; protocol?: string };
  skills: { status: string; fingerprint: string | null; configured: SkillInfo[];
    stages: string[]; loadedAt: string | null; applications: number; lastAppliedAt: string | null };
  budget: { maxModelCalls: number | null; deadlineAt: string | null };
  events: MonitorEvent[]; models: ModelCall[]; codes: CodeVersion[];
  eventsTruncated: boolean; codesTruncated: boolean;
}
export interface NodeSnapshot {
  schemaVersion: 'coding_agent.monitor.v1'; instanceId: string; observedAt: string;
  status: 'READY' | 'DRAINING'; sdk: string; build: string; nodeVersion: string;
  platform: string; arch: string; hostname: string; uptimeSeconds: number; startedAt: string;
  memory: { rssBytes: number; heapUsedBytes: number; heapTotalBytes: number };
  cpu: { userMicros: number; systemMicros: number; cores: number; loadAverage: number[] };
  sessions: { active: number; capacity: number; retained: number };
  tasks: Record<TaskState, number>; callbackTimeoutMs: number;
  history: { persistence: 'MEMORY' | 'HEALTHY' | 'DEGRADED'; error: string | null;
    maxTasks: number; maxEventsPerTask: number; maxSourceBytes: number; retainedSourceBytes: number };
  authority: 'NODE_OBSERVATION_ONLY';
}
export interface TaskPage { schemaVersion: 'coding_agent.monitor.v1'; observedAt: string;
  total: number; offset: number; limit: number; items: TaskSummary[] }
