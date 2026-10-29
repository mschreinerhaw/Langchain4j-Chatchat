import type { CodeVersion, ModelCall, MonitorEvent, NodeSnapshot, TaskDetail, TaskPage, TaskState } from './monitor-types.ts';

/** Server-side SDK. Keep the operator token on your application server. */
export function createMonitorClient(options: { baseUrl: string; token: string; fetch?: typeof fetch; timeoutMs?: number }) {
  const base = options.baseUrl.replace(/\/$/, '');
  const transport = options.fetch ?? fetch;
  async function get<T>(path: string): Promise<T> {
    const response = await transport(`${base}/v1/monitor${path}`, {
      headers: { authorization: `Bearer ${options.token}` }, signal: AbortSignal.timeout(options.timeoutMs ?? 10000),
      redirect: 'error', cache: 'no-store',
    });
    if (!response.ok) throw Object.assign(new Error(`Node monitor returned HTTP ${response.status}`), { status: response.status });
    return await response.json() as T;
  }
  const id = (value: string) => {
    if (!/^[a-f0-9]{64}$/.test(value)) throw new TypeError('A monitor observation ID is required');
    return value;
  };
  return {
    node: () => get<NodeSnapshot>('/node'),
    tasks: (query: { status?: TaskState; q?: string; offset?: number; limit?: number } = {}) =>
      get<TaskPage>(`/tasks?${new URLSearchParams(Object.entries(query).map(([key, value]) => [key, String(value)]))}`),
    task: (taskId: string) => get<{ schemaVersion: string; task: TaskDetail }>(`/tasks/${id(taskId)}`),
    events: (taskId: string, after = 0, limit = 100) =>
      get<{ schemaVersion: string; items: MonitorEvent[]; nextCursor: number; hasMore: boolean; truncated: boolean; earliestCursor: number | null }>(`/tasks/${id(taskId)}/events?after=${after}&limit=${limit}`),
    models: (taskId: string) => get<{ schemaVersion: string; models: ModelCall[] }>(`/tasks/${id(taskId)}/models`),
    skills: (taskId: string) => get<{ schemaVersion: string; skills: TaskDetail['skills'] }>(`/tasks/${id(taskId)}/skills`),
    codes: (taskId: string) => get<{ schemaVersion: string; codes: CodeVersion[] }>(`/tasks/${id(taskId)}/codes`),
    python: (taskId: string) => get<unknown>(`/tasks/${id(taskId)}/python`),
    code: (taskId: string, codeId: string) => get<CodeVersion>(`/tasks/${id(taskId)}/codes/${id(codeId)}`),
  };
}
export type * from './monitor-types.ts';
