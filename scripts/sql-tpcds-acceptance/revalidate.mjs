/** Revalidate existing, published fixtures without changing shared datasource policy or releases. */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
const [command] = process.argv.slice(2);
assert.ok(['capture', 'ask', 'poll', 'python-proof'].includes(command), 'Use capture, ask, poll or python-proof');
const { TPCDS_ORIGIN: origin, TPCDS_AUTH_FILE: authFile, TPCDS_STATE: stateFile, TPCDS_OUTPUT: output } = process.env;
assert.ok(origin && authFile && stateFile && output, 'Set TPCDS_ORIGIN, TPCDS_AUTH_FILE, TPCDS_STATE and TPCDS_OUTPUT');
const auth = JSON.parse(fs.readFileSync(authFile));
const state = JSON.parse(fs.readFileSync(stateFile));
fs.mkdirSync(output, { recursive: true });
const save = (name, data) => fs.writeFileSync(path.join(output, name + '.json'), JSON.stringify(data, null, 2));
const read = name => JSON.parse(fs.readFileSync(path.join(output, name + '.json')));
async function api(route, method = 'GET', body) {
  const r = await fetch(origin + '/api/v1' + route, { method,
    headers: { Authorization: 'Bearer ' + auth.token, 'Content-Type': 'application/json', 'X-Workspace-Id': 'data-science' },
    body: body === undefined ? undefined : JSON.stringify(body) });
  const j = await r.json();
  if (!r.ok || j.code !== 200) throw Object.assign(new Error(JSON.stringify({ route, status: r.status, code: j.code, message: j.message })), { code: j.code });
  return j.data;
}
if (command === 'capture') {
  const base = '/data-science/sql/projects/' + state.project.id;
  const tools = await api('/mcp/runtime/tools?serviceId=chatchat-mcp-server');
  const snapshot = { capturedAt: new Date().toISOString(), projectId: state.project.id, templates: {} };
  for (const name of ['yearly', 'monthly']) {
    const file = await api(base + '/files/' + state[name].file.id);
    const definitions = await api(base + '/files/' + file.id + '/template-publications');
    assert.ok(Array.isArray(definitions), 'Expected SQL definition list');
    const active = definitions.filter(d => d.active && d.publication?.state === 'RUNNING');
    assert.equal(active.length, 1, 'Exactly one active published revision required');
    const definition = active[0];
    const taskId = file.sourceTaskId;
    assert.ok(taskId?.startsWith('sql-coding-'), 'Model-generated source provenance required');
    const generation = await api(base + '/generations/' + taskId.slice('sql-coding-'.length));
    const descriptor = (Array.isArray(tools) ? tools : tools.tools).find(t => t.remoteToolName === definition.publication.toolName);
    assert.ok(descriptor, 'Published tool missing from actual MCP discovery');
    snapshot.templates[name] = { file, definition, generation, descriptor };
  }
  snapshot.agent = await api('/agents/workshop/' + (process.env.TPCDS_AGENT_ID || state.agent.id));
  save('source-chain', snapshot);
  console.log(JSON.stringify({ capturedAt: snapshot.capturedAt, releases: Object.values(snapshot.templates).map(t => t.definition.publication.id) }));
}
if (command === 'ask') {
  assert.ok(!fs.existsSync(path.join(output, 'agent-task.json')), 'Task already submitted; use poll');
  const task = await api('/agent/tasks', 'POST', {
    agentId: process.env.TPCDS_AGENT_ID || state.agent.id, mode: 'agent_chat', historyWindow: 0,
    idempotencyKey: 'sql-chain-revalidation-' + crypto.randomUUID(),
    query: process.env.TPCDS_QUESTION || '请分析248测试数据库tpcds中商品主键1在1999-01-01至1999-12-31的门店销售情况，月度最低购买数量0、品类不限，将年度汇总与逐月明细进行勾稽对账。使用实际查询结果说明差额和限制，金额单位按数据来源声明，不要推断。'
  });
  save('agent-task', task);
  console.log(JSON.stringify({ taskId: task.taskId, state: task.canonicalState }));
}
if (command === 'poll') {
  const { taskId } = read('agent-task');
  const task = await api('/agent/tasks/' + taskId + '?tenantId=' + auth.user.tenantId);
  save('agent-result', task);
  const events = await api('/agent/tasks/' + taskId + '/events?tenantId=' + auth.user.tenantId + '&limit=1000');
  save('agent-events', events);
  const runs = [...new Set(events.flatMap(e => { try { return [JSON.parse(e.payload).runId].filter(Boolean); } catch { return []; } }))];
  for (const run of runs) {
    try {
      const observations = await api('/agent/runtime/runs/' + run + '/observations?limit=1000');
      save('observations-' + run, observations);
      save('coding-' + run, await api('/agent/runtime/runs/' + run + '/coding'));
    } catch (error) {
      // Planning can emit an attempt ID before its Runtime run is registered.
      if (error.code !== 404 || ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(task.canonicalState)) throw error;
    }
  }
  save('run-index', { taskId, runs, capturedAt: new Date().toISOString() });
  console.log(JSON.stringify({ taskId, state: task.canonicalState, runs }));
}
if (command === 'python-proof') {
  const parent = read('agent-result');
  assert.equal(parent.canonicalState, 'SUCCEEDED');
  const monitor = read('coding-' + parent.attemptId);
  assert.equal(monitor.source, 'DURABLE_CODING_JOURNAL');
  const verified = [];
  for (const task of monitor.tasks) {
    assert.equal(task.outcome.status, 'COMPLETED');
    assert.equal(task.outcome.executionControl.executorStopped, true);
    const execution = task.executions.at(-1);
    assert.equal(execution.status, 'SUCCEEDED');
    assert.equal(execution.exitCode, 0);
    assert.equal(execution.stage, 'VALIDATED');
    assert.equal(execution.resultAvailable, true);
    const base = '/agent/runtime/runs/' + parent.attemptId + '/coding/' + task.taskId + '/' + execution.executionId;
    const code = await api(base + '/code');
    assert.equal(code.redacted, false);
    assert.equal(code.truncated, false);
    const bytes = Buffer.from(code.content, 'utf8');
    const digest = crypto.createHash('sha256').update(bytes).digest('hex');
    assert.equal(digest, code.sha256);
    assert.equal(digest, execution.codeHash);
    const timing = await api(base + '/timing');
    assert.equal(timing.source, 'MCP_ARCHIVED_RECEIPT');
    assert.equal(timing.codeHash, digest);
    assert.equal(timing.operationId, execution.executionId);
    fs.writeFileSync(path.join(output, execution.executionId + '.py'), bytes);
    save('stdout-' + execution.executionId, await api(base + '/stdout'));
    verified.push({ taskId: task.taskId, executionId: execution.executionId, codeSha256: digest, sourceBytes: bytes.length,
      stage: execution.stage, exitCode: execution.exitCode, executorStopped: true,
      validationStatus: execution.validationStatus, businessValidationPassed: execution.businessValidationPassed, timing });
  }
  assert.ok(verified.length > 0, 'No actual Python execution');
  const proof = { status: 'PYTHON_EXECUTION_VERIFIED', parentTaskId: parent.taskId, executions: verified,
    limitation: 'Execution and byte integrity only; preserve business INCONCLUSIVE and verify computed results separately.' };
  save('python-execution-proof', proof);
  console.log(JSON.stringify({ status: proof.status, executions: verified.length }));
}
