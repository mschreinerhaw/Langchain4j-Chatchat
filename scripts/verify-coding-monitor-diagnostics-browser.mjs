// Requires real accepted tasks and an authenticated private session; never mocks requests.
import { chromium } from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import assert from 'node:assert/strict';

const base = process.env.CHATCHAT_MONITOR_BASE_URL ?? 'http://127.0.0.1:28880';
const output = process.env.CHATCHAT_MONITOR_OUTPUT;
if (!output || !process.env.CHATCHAT_STAGE_SESSION_FILE || !process.env.CHATCHAT_MONITOR_CASES_FILE)
  throw new Error('Set private session, real cases manifest and output directory');
const supplied = JSON.parse(await readFile(process.env.CHATCHAT_STAGE_SESSION_FILE, 'utf8'));
const session = supplied.data ?? supplied;
const cases = JSON.parse(await readFile(process.env.CHATCHAT_MONITOR_CASES_FILE, 'utf8'));
await mkdir(output, { recursive: true });
const browser = await chromium.launch({ executablePath: process.env.CHATCHAT_BROWSER_PATH
  ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true, args: ['--no-proxy-server'] });
const proof = { scope: 'Real deployed Vue page, Cline SDK, MCP and Python receipts; no request interception', cases: [], errors: [] };
try {
  const context = await browser.newContext({ viewport: { width: 1500, height: 1500 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' });
  await context.addInitScript(value => localStorage.setItem('chatchat.auth.session', JSON.stringify(value)), session);
  const page = await context.newPage();
  page.on('pageerror', e => proof.errors.push(e.message));
  async function api(path) {
    const response = await context.request.get(base + '/api/v1' + path, { headers: { Authorization: 'Bearer ' + session.token } });
    assert.equal(response.status(), 200);
    const value = await response.json(); assert.equal(value.code, 200); return value.data;
  }
  async function open(test) {
    await page.goto(base + '/#/w/zhice/tasks');
    await page.evaluate(id => sessionStorage.setItem('chatchat.runtime.selectedTaskId', id), test.parentTaskId);
    await page.reload();
    await page.locator('.coding-monitor > summary').waitFor({ timeout: 60000 });
    await page.locator('.coding-monitor > summary').click();
    await page.waitForFunction(expected => document.querySelectorAll('.coding-monitor .python-execution').length === expected,
      test.expectedExecutions, { timeout: 60000 });
  }
  async function overview() {
    return page.locator('.coding-monitor .python-overview > div').evaluateAll(nodes => Object.fromEntries(
      nodes.map(node => [node.querySelector('dt').textContent, node.querySelector('dd').textContent])));
  }
  for (const test of cases) {
    const path = '/agent/runtime/runs/' + encodeURIComponent(test.runId) + '/coding';
    const tasks = (await api(path)).tasks; assert.equal(tasks.length, 1);
    const task = tasks[0], executions = task.executions;
    assert.equal(executions.length, test.expectedExecutions);
    assert.deepEqual(executions.map(e => e.error?.diagnosticCode ?? null), test.expectedDiagnostics);
    await open(test);
    const monitor = page.locator('.coding-monitor'), facts = await overview();
    assert.equal(facts['Python 执行次数'], String(executions.length));
    assert.equal(facts['进程成功次数'], String(executions.filter(e => e.status === 'SUCCEEDED').length));
    assert.equal(facts['进程失败 / 超时次数'], String(executions.filter(e => ['FAILED','TIMED_OUT'].includes(e.status)).length));
    assert.equal(facts['产物校验失败次数'], String(executions.filter(e => e.status === 'SUCCEEDED' && !e.resultAvailable).length));
    assert.equal(facts['自动修复次数'], String(test.expectedRepairs));
    assert.equal(facts['修复后仍失败次数'], String(test.expectedFailedRepairs));
    assert.equal(await monitor.locator('.python-repair').count(), test.expectedRepairs);
    await monitor.locator('.python-overview').screenshot({ path: output + '/' + test.name + '-overview.png' });
    const timingChecks = [];
    for (const [index, execution] of executions.entries()) {
      const row = monitor.locator('[data-execution-id="' + execution.executionId + '"]');
      await row.evaluate(node => node.open = true);
      const text = await row.innerText();
      const timing = await api(path + '/' + task.taskId + '/' + execution.executionId + '/timing');
      assert.equal(timing.codeHash, execution.codeHash); assert.equal(timing.source, 'MCP_ARCHIVED_RECEIPT');
      assert(timing.startedAt && timing.finishedAt);
      await row.locator('[data-testid="execution-started-at"] time').waitFor();
      assert.equal(await row.locator('[data-testid="execution-started-at"] time').getAttribute('datetime'), timing.startedAt);
      assert.equal(await row.locator('[data-testid="execution-finished-at"] time').getAttribute('datetime'), timing.finishedAt);
      timingChecks.push({ executionId: execution.executionId, ...timing });
      assert(text.includes(execution.codeHash)); assert(text.includes(execution.script.fileName));
      if (execution.error) {
        assert(text.includes(execution.error.diagnosticCode));
        if (execution.error.exceptionType) assert(text.includes(execution.error.exceptionType));
        for (const frame of execution.error.stack) assert(text.includes(frame.file + ':' + frame.line));
        assert(text.includes('原始日志不可用'));
        await row.screenshot({ path: output + '/' + test.name + '-execution-' + (index + 1) + '.png' });
      }
    }
    const diffChecks = [];
    for (let index = 0; index < test.expectedRepairs; index++) {
      const relation = monitor.locator('.python-repair').nth(index);
      await relation.getByRole('button', { name: '查看修复 Diff' }).click();
      const diff = monitor.locator('.python-code-diff'); await diff.waitFor();
      assert(await diff.locator('.diff-added, .diff-deleted').count() > 0);
      const target = await relation.getAttribute('data-repair-execution');
      const after = executions.find(e => e.executionId === target);
      const before = executions[executions.indexOf(after) - 1];
      const first = await api(path + '/' + task.taskId + '/' + before.executionId + '/code');
      const second = await api(path + '/' + task.taskId + '/' + after.executionId + '/code');
      assert.equal(first.sha256, before.codeHash); assert.equal(second.sha256, after.codeHash);
      assert.notEqual(first.content, second.content);
      const rendered = await diff.innerText();
      assert(rendered.includes(first.content.split('\n').find(line => line.trim()).trim()));
      assert(rendered.includes(second.content.split('\n').find(line => line.trim()).trim()));
      diffChecks.push({ fromExecutionId: before.executionId, toExecutionId: after.executionId,
        beforeVersion: before.script.scriptVersion, afterVersion: after.script.scriptVersion,
        beforeSha256: first.sha256, afterSha256: second.sha256, redacted: first.redacted || second.redacted });
      await diff.screenshot({ path: output + '/' + test.name + '-repair-' + (index + 1) + '-diff.png' });
    }
    const timeline = await monitor.locator('.python-timeline li strong').allTextContents();
    assert(timeline.some(value => value.includes('执行失败')));
    assert.equal(timeline.filter(value => value.includes('Java 派发意图')).length, executions.length);
    await monitor.locator('.python-timeline').screenshot({ path: output + '/' + test.name + '-timeline.png' });
    // Reopen the actual task and compare API/DOM facts; never dispatch a new model or Python operation.
    await open(test); assert.deepEqual(await overview(), facts);
    const restored = (await api(path)).tasks[0];
    assert.deepEqual(restored.executions, executions);
    assert.deepEqual(restored.outcome.usage, task.outcome.usage);
    proof.cases.push({ name: test.name, runId: test.runId, parentTaskId: test.parentTaskId,
      codingTaskId: task.taskId, facts, diffChecks, timingChecks, timeline, restoredExactly: true, newModelOrPythonCallsOnRestore: false });
  }
  assert.deepEqual(proof.errors, []); proof.passed = true;
} finally {
  await writeFile(output + '/browser-proof.json', JSON.stringify(proof, null, 2) + '\n');
  await browser.close();
}
