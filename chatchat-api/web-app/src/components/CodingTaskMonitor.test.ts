// @vitest-environment jsdom
import { createApp, h, nextTick } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import CodingTaskMonitor from './CodingTaskMonitor.vue';
import { fetchCodingTasks, fetchCodingEvents, fetchCodingCode, cancelCodingTask } from '../services/codingMonitor';
vi.mock('../services/codingMonitor', async importOriginal => ({
  ...await importOriginal<typeof import('../services/codingMonitor')>(),
  fetchCodingTasks: vi.fn(), fetchCodingEvents: vi.fn(), fetchCodingCode: vi.fn(), cancelCodingTask: vi.fn(),
}));
let app: ReturnType<typeof createApp>, host: HTMLElement;
afterEach(() => { app?.unmount(); host?.remove(); vi.clearAllMocks(); });
const task = (status = 'RUNNING') => ({ taskId: 'coding-1', attemptId: 'coding-1:fence-1', objective: 'Calculate median',
  canCancel: status === 'RUNNING', canRetry: false, allowedTools: ['python_execute'],
  configuration: { model: { selectedModel: 'configured-model' }, configuredSkillIds: ['skill-a'],
    skills: { status: 'APPLIED', skills: [{ skillId: 'skill-a' }] } },
  outcome: { status, createdAt: '2026-10-19T01:00:00Z', updatedAt: '2026-10-19T01:00:02Z',
    endedAt: status === 'RUNNING' ? undefined : '2026-10-19T01:00:02Z', cancellationRequested: false, lastEventSequence: 2 } });
async function mount() {
  host = document.createElement('div'); document.body.append(host);
  app = createApp({ render: () => h(CodingTaskMonitor, { runId: 'run' }) }); app.mount(host); await nextTick();
  const details = host.querySelector('details')!; details.open = true; details.dispatchEvent(new Event('toggle'));
  await vi.waitFor(() => expect(host.textContent).toContain('Calculate median'));
}
describe('Coding task monitor page', () => {
  it('shows persisted actual model, code and server stage without claiming completion', async () => {
    vi.mocked(fetchCodingTasks).mockResolvedValue({ tasks: [task()] });
    vi.mocked(fetchCodingEvents).mockResolvedValue({ events: [
      { eventId: 'e1', taskId: 'coding-1', sequence: 1, occurredAt: '2026-10-19T01:00:01Z', type: 'RECOVERY_RECONCILED', status: 'RUNNING', summary: 'model',
        payload: { type: 'MODEL_RESOLVED', modelCall: 1, attemptId: 'a', providerModel: 'actual-model', protocol: 'openai' } },
      { eventId: 'e2', taskId: 'coding-1', sequence: 2, occurredAt: '2026-10-19T01:00:02Z', type: 'RECOVERY_RECONCILED', status: 'RUNNING', summary: 'code',
        payload: { type: 'CODE_READY', stage: 'CODE_READY', proposedOperationId: 'cline-abc', codeSha256: 'hash' } },
    ], nextSequence: 2, hasMore: false });
    vi.mocked(fetchCodingCode).mockResolvedValue({ content: 'print(42)', redacted: false });
    await mount(); await vi.waitFor(() => expect(host.querySelector('pre')?.textContent).toBe('print(42)'));
    expect(host.textContent).toContain('actual-model'); expect(host.textContent).toContain('代码已返回');
    expect(host.textContent).toContain('RUNNING'); expect(host.textContent).toContain('skill-a');
    const cancel = [...host.querySelectorAll('button')].find(b => b.textContent === '取消任务')!;
    cancel.click(); await vi.waitFor(() => expect(cancelCodingTask).toHaveBeenCalledWith('run', 'coding-1'));
  });
  it('hides cancellation for terminal tasks and does not invent legacy configuration or progress', async () => {
    const completed = task('COMPLETED'); completed.configuration = {} as any;
    vi.mocked(fetchCodingTasks).mockResolvedValue({ tasks: [completed] });
    vi.mocked(fetchCodingEvents).mockResolvedValue({ events: [], nextSequence: 0, hasMore: false });
    await mount();
    expect(host.textContent).toContain('COMPLETED'); expect(host.textContent).toContain('历史任务未记录');
    expect([...host.querySelectorAll('button')].some(b => b.textContent === '取消任务')).toBe(false);
    expect(fetchCodingCode).not.toHaveBeenCalled();
  });
});
