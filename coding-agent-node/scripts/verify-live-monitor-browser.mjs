/** Real deployed monitor. Credentials remain in process memory, never fixtures or output. */
import { chromium } from '../../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';

const base = process.env.CODING_NODE_MONITOR_URL ?? 'http://192.168.195.221:18100';
const runId = process.env.CODING_NODE_VERIFY_RUN;
if (!runId || !process.env.CODING_NODE_SSH_PASSWORD) throw new Error('Set verification run and SSH credentials through environment');
const token = execFileSync('C:/Program Files/PuTTY/plink.exe', ['-ssh', '-batch', '-P', '5221', 'root@192.168.195.221',
  '-pw', process.env.CODING_NODE_SSH_PASSWORD, 'cat /opt/runtime-os-docker-stage/config/coding-node-monitor.token'], { encoding: 'utf8' }).trim();
const browser = await chromium.launch({ executablePath: process.env.CHATCHAT_BROWSER_PATH ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: true, args: ['--no-proxy-server'] });
const context = await browser.newContext({ viewport: { width: 1520, height: 1080 }, locale: 'zh-CN' });
const page = await context.newPage(), errors = [];
page.on('pageerror', error => errors.push(error.message));
async function api(path) {
  const response = await context.request.get(base + '/v1/monitor' + path);
  assert.equal(response.status(), 200, path); return response.json();
}
try {
  await page.goto(base + '/monitor/', { waitUntil: 'domcontentloaded' });
  await page.locator('#token').fill(token); await page.locator('#login-form button').click();
  await page.locator('#console').waitFor({ state: 'visible' });
  let task;
  const deadline = Date.now() + 1500000;
  while (Date.now() < deadline) {
    const tasks = await api('/tasks?q=' + encodeURIComponent(runId));
    if (tasks.items[0]) {
      task = (await api('/tasks/' + tasks.items[0].id)).task;
      if (task.status !== 'RUNNING' && task.codes.length && task.models.some(call => call.usage.inputTokens !== null)) break;
    }
    await new Promise(resolve => setTimeout(resolve, 3000));
  }
  assert.ok(task?.codes.length, 'Real code must be generated'); assert.equal(task.status, 'COMPLETED');
  await page.locator('#search').fill(runId);
  await page.locator(`[data-task-id="${task.id}"]`).click();
  await page.locator('[data-tab="codes"]').click();
  const code = await api('/tasks/' + task.id + '/codes/' + task.codes.at(-1).id);
  for (let i = 0; i < 100; i++) {
    if ((await page.locator('.code-block').innerText()) === code.source) break;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.equal(await page.locator('.code-block').innerText(), code.source);
  assert.equal(createHash('sha256').update(code.source).digest('hex'), code.sha256);
  await mkdir('../target/coding-node-monitor', { recursive: true });
  await page.screenshot({ path: '../target/coding-node-monitor/live-code.png', fullPage: true });
  await page.locator('[data-tab="models"]').click(); await page.locator('.model-card').first().waitFor();
  const modelText = await page.locator('#detail-content').innerText();
  assert.ok(task.models.some(call => call.providerModel && modelText.includes(call.providerModel)));
  await page.screenshot({ path: '../target/coding-node-monitor/live-models.png', fullPage: true });
  await page.locator('[data-tab="skills"]').click();
  if (task.skills.status === 'APPLIED') { assert.ok(task.skills.loadedAt); assert.ok(task.skills.applications > 0); }
  await page.locator('[data-tab="events"]').click(); await page.locator('.timeline li').first().waitFor();
  const node = await api('/node');
  assert.equal(node.platform, 'linux'); assert.equal(node.history.persistence, 'HEALTHY');
  assert.deepEqual(errors, []);
  const result = { status: 'PASSED', scope: 'Real deployed monitor and Runtime-submitted task; no mocked network or model',
    runId, taskId: task.taskId, monitorId: task.id, taskStatus: task.status,
    codeVersions: task.codes.length, codeSha256: code.sha256, codeSourceVerifiedInBrowser: true,
    models: task.models.map(call => ({ ordinal: call.ordinal, status: call.status, providerModel: call.providerModel,
      inputTokens: call.usage.inputTokens, outputTokens: call.usage.outputTokens })),
    skills: task.skills, events: task.eventCount, persistence: node.history.persistence, build: node.build, errors };
  await writeFile('../target/coding-node-monitor/live-browser.json', JSON.stringify(result, null, 2));
  console.log(JSON.stringify({ status: result.status, taskId: result.taskId, codeVersions: result.codeVersions,
    models: result.models.length, codeSourceVerifiedInBrowser: true, persistence: node.history.persistence }));
} finally { await browser.close(); }
