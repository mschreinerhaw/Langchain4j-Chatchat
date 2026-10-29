// @vitest-environment jsdom
import { createApp, h, nextTick } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import CodingTaskMonitor from './CodingTaskMonitor.vue';
import { fetchCodingTasks, fetchCodingEvents, fetchCodingCode, fetchCodingInputs, cancelCodingTask } from '../services/codingMonitor';
vi.mock('../services/codingMonitor', async importOriginal => ({
  ...await importOriginal<typeof import('../services/codingMonitor')>(),
  fetchCodingTasks: vi.fn(), fetchCodingEvents: vi.fn(), fetchCodingCode: vi.fn(), cancelCodingTask: vi.fn(),
  fetchCodingInputs: vi.fn(async () => ({ binding: 'TASK_LATEST', snapshots: [] })),
  fetchCodingTiming: vi.fn(async (_run: string, _task: string, operationId: string) => ({ operationId, codeHash: operationId==='cline-1'?'1'.repeat(64):'2'.repeat(64),
    source:'MCP_ARCHIVED_RECEIPT',startedAt:'2026-10-19T01:00:01Z',finishedAt:'2026-10-19T01:00:02Z' })),
}));
let app: ReturnType<typeof createApp>, host: HTMLElement;
beforeEach(() => vi.mocked(fetchCodingInputs).mockResolvedValue({ binding: 'TASK_LATEST', snapshots: [] }));
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
  it('restores per-execution diagnosis and repair Diff from archived feedback when Node events are absent', async () => {
    const feedback = (n: number, failed: boolean): any => ({ schemaVersion:'runtime_os.execution_feedback.v1',
      executionId:`cline-${n}`,attemptId:`coding-1:execution-${n}`,status:failed?'FAILED':'SUCCEEDED',
      stage:failed?'EXECUTION_FAILED':'VALIDATED',codeHash:String(n).repeat(64),exitCode:failed?1:0,durationMs:17,
      script:{fileName:`analysis_v${n}.py`,scriptVersion:n,scriptPath:`/workspace/scripts/analysis_v${n}.py`},
      resultAvailable:!failed,validationStatus:failed?'NOT_RUN':'INCONCLUSIVE',businessValidationPassed:false,
      error:failed?{exceptionType:'KeyError',diagnosticCode:'FIELD_ACCESS',category:'SCHEMA_ACCESS',message:'Safe schema guidance',stack:[{file:'analysis_v1.py',line:2}]}:null,
      repair:{allowed:failed,attemptsUsed:n-1,maxAttempts:3,action:failed?'REPAIR_IN_CURRENT_CONTEXT':'COMPLETE',reason:failed?'TECHNICAL_REPAIR':'RESULT_VERIFIED'}});
    vi.mocked(fetchCodingTasks).mockResolvedValue({tasks:[{...task('COMPLETED'),executions:[feedback(1,true),feedback(2,false)]}]});
    vi.mocked(fetchCodingEvents).mockResolvedValue({events:[],nextSequence:0,hasMore:false});
    vi.mocked(fetchCodingCode).mockImplementation(async (_run,_task,op)=>({content:op==='cline-1'?"value = row['missing']":"value = row['amount']",sha256:op==='cline-1'?'1'.repeat(64):'2'.repeat(64)}));
    await mount();
    expect(host.querySelectorAll('.python-execution')).toHaveLength(2);
    expect(host.textContent).toContain('KeyError');expect(host.textContent).toContain('FIELD_ACCESS');
    expect(host.textContent).toContain('analysis_v1.py:2');expect(host.textContent).toContain('原始日志不可用');
    expect(host.textContent).toContain('未独立验证通过');expect(host.querySelectorAll('.python-repair')).toHaveLength(1);
    (host.querySelector('.python-repair button') as HTMLButtonElement).click();
    await vi.waitFor(()=>expect(host.querySelector('.python-code-diff')).not.toBeNull());
    expect(fetchCodingCode).toHaveBeenCalledWith('run','coding-1','cline-1');
    expect(fetchCodingCode).toHaveBeenCalledWith('run','coding-1','cline-2');
    expect(host.querySelector('.python-code-diff')?.textContent).toContain('missing');
    expect(host.querySelector('.python-code-diff')?.textContent).toContain('amount');
  });
  it('labels exit zero with invalid output as validation failure, retaining the real error code', async () => {
    vi.mocked(fetchCodingTasks).mockResolvedValue({tasks:[{...task('FAILED'),executions:[{schemaVersion:'runtime_os.execution_feedback.v1',
      executionId:'cline-output',attemptId:'coding-1:execution-1',status:'SUCCEEDED',stage:'RESULT_VALIDATION_FAILED',exitCode:0,
      resultAvailable:false,validationStatus:'FAILED',codeHash:'a'.repeat(64),error:{exceptionType:null,diagnosticCode:'OUTPUT_CONTRACT',category:'OUTPUT_CONTRACT',message:'Invalid result schema',stack:[]},
      repair:{attemptsUsed:0,maxAttempts:3,allowed:false,action:'STOP'}} as any]}]});
    vi.mocked(fetchCodingEvents).mockResolvedValue({events:[],nextSequence:0,hasMore:false});
    vi.mocked(fetchCodingCode).mockResolvedValue({content:'pass'});
    await mount();expect(host.textContent).toContain('OUTPUT_CONTRACT');
    expect(host.querySelector('.python-execution > summary')?.textContent).toContain('结果文件验收失败');
    const facts=Object.fromEntries([...host.querySelectorAll('.python-overview > div')].map(e=>[e.querySelector('dt')?.textContent,e.querySelector('dd')?.textContent]));
    expect(facts['进程成功次数']).toBe('1');expect(facts['产物校验失败次数']).toBe('1');expect(facts['自动修复次数']).toBe('0');
  });
  it('shows persisted actual model, code and server stage without claiming completion', async () => {
    vi.mocked(fetchCodingTasks).mockResolvedValue({ tasks: [task()] });
    vi.mocked(fetchCodingEvents).mockResolvedValue({ events: [
      { eventId: 'e1', taskId: 'coding-1', sequence: 1, occurredAt: '2026-10-19T01:00:01Z', type: 'RECOVERY_RECONCILED', status: 'RUNNING', summary: 'model',
        payload: { type: 'MODEL_RESOLVED', modelCall: 1, attemptId: 'a', providerModel: 'actual-model', protocol: 'openai' } },
      { eventId: 'e2', taskId: 'coding-1', sequence: 2, occurredAt: '2026-10-19T01:00:02Z', type: 'RECOVERY_RECONCILED', status: 'RUNNING', summary: 'code',
        payload: { type: 'CODE_READY', stage: 'CODE_READY', proposedOperationId: 'cline-abc', codeSha256: 'hash' } },
    ], nextSequence: 2, hasMore: false });
    vi.mocked(fetchCodingCode).mockResolvedValue({ content: 'print(42)', redacted: false });
    vi.mocked(fetchCodingInputs).mockResolvedValue({ binding: 'OPERATION_FROZEN', snapshots: [{ reference: 'input-revision-1',
      fileId: 'manifest', fileName: 'manifest.json', sha256: 'manifest-hash', rowCount: 2, size: 100, format: 'PARQUET' }] });
    await mount(); await vi.waitFor(() => expect(host.querySelector('pre code')?.textContent).toBe('print(42)'));
    expect(host.textContent).toContain('actual-model'); expect(host.textContent).toContain('代码已返回');
    await vi.waitFor(() => expect(host.textContent).toContain('input-revision-1'));
    expect(host.textContent).toContain('PARQUET'); expect(host.textContent).toContain('当前代码操作已冻结的输入版本');
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
