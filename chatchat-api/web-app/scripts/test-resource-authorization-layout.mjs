import { access } from 'node:fs/promises';
import { chromium } from 'playwright-core';
import { createServer } from 'vite';
import assert from 'node:assert/strict';

const mocks = `
const pause = () => new Promise(resolve => setTimeout(resolve, 300));
export async function fetchRoleAuthorization() { await pause(); return { agentIds: [], allAgentAccess: true }; }
export async function fetchResourceGrants() { await pause(); return [{ principalType: 'ROLE', principalId: 'admin', resourceId: '*', effect: 'ALLOW', enabled: true }]; }
export async function fetchDomainSkills() { await pause(); return { skills: Array.from({ length: 80 }, (_, i) => ({ id: 'skill-' + i, name: '领域技能 ' + i })), totalPages: 1 }; }
export async function fetchMcpRegisteredTools() { return [{ localToolName: 'query', chineseAlias: '查询' }]; }
export async function fetchResearchLibrary() { return { documents: [], totalPages: 1 }; }
export async function createResourceGrant() { throw new Error('Read-only fixture'); }
`;
const entry = `
import { createApp } from 'vue';
import Panel from '/src/views/ResourceAuthorizationPanel.vue';
document.body.innerHTML = '<main style="height:620px;max-width:1200px;margin:auto"><div id="app" style="height:100%"></div></main>';
createApp(Panel, { tenantId: 'tenant', roles: [{ id: 'admin', roleName: '管理员' }],
  agents: Array.from({ length: 40 }, (_, i) => ({ id: 'agent-' + i, name: 'Agent ' + i })) }).mount('#app');
`;
const server = await createServer({ logLevel: 'error', server: { host: '127.0.0.1', port: 0 }, plugins: [{
  name: 'resource-layout-fixture', enforce: 'pre',
  resolveId(source, importer) {
    if (source === '/resource-layout-entry.js') return '\0resource-layout-entry';
    if (source === '../services/api' && importer?.includes('ResourceAuthorizationPanel.vue')) return '\0resource-layout-api';
  },
  load(id) { if (id === '\0resource-layout-entry') return entry; if (id === '\0resource-layout-api') return mocks; }
}] });
let browser;
try {
  let executablePath;
  for (const candidate of [process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,
    'C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', '/usr/bin/chromium'].filter(Boolean)) {
    try { await access(candidate); executablePath = candidate; break; } catch { /* next browser */ }
  }
  await server.listen();
  browser = await chromium.launch({ executablePath, headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 950 } });
  await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/`);
  await page.evaluate(async () => { await import('/resource-layout-entry.js'); });
  await page.waitForFunction(() => document.querySelectorAll('.resource-auth-resource').length === 80
    && document.querySelectorAll('.resource-auth-list[aria-busy="false"]').length === 2);
  await page.evaluate(() => {
    document.querySelector('.resource-auth').scrollTop = 140;
    for (const list of document.querySelectorAll('.resource-auth-list')) list.scrollTop = 200;
  });
  const snapshot = () => page.evaluate(() => [document.querySelector('.resource-auth'), ...document.querySelectorAll('.resource-auth-list')]
    .map(node => ({ top: node.getBoundingClientRect().top, height: node.getBoundingClientRect().height, scroll: node.scrollTop })));
  const before = await snapshot();
  await page.evaluate(() => document.querySelectorAll('.resource-auth-agent')[5].click());
  await page.waitForSelector('.resource-auth-list[aria-busy="true"]');
  assert.deepEqual(await snapshot(), before, 'Layout/scroll changed during selection loading');
  await page.waitForFunction(() => document.querySelectorAll('.resource-auth-list[aria-busy="false"]').length === 2);
  assert.deepEqual(await snapshot(), before, 'Layout/scroll changed after selection');
  await page.evaluate(() => document.querySelector('.resource-auth-tools button').click());
  await page.waitForSelector('.resource-auth-list[aria-busy="true"]');
  assert.deepEqual(await snapshot(), before, 'Layout/scroll changed during Agent refresh');
  await page.waitForFunction(() => document.querySelectorAll('.resource-auth-list[aria-busy="false"]').length === 2);
  assert.deepEqual(await snapshot(), before, 'Layout/scroll changed after Agent refresh');
  for (const index of [1, 2, 0]) {
    await page.evaluate(index => document.querySelectorAll('.resource-auth-kinds button')[index].click(), index);
    await page.waitForFunction(() => document.querySelectorAll('.resource-auth-list[aria-busy="false"]').length === 2);
    const after = await snapshot();
    assert.deepEqual(after.slice(0, 2), before.slice(0, 2), 'Tab switch moved panel or Agent list');
    assert.equal(after[2].height, before[2].height, 'Tab switch changed resource viewport height');
    assert.equal(after[2].top, before[2].top, 'Tab switch moved resource viewport');
  }
  console.log('PASS: selection, refresh and tabs preserve layout and outer/Agent scroll positions');
} finally { await browser?.close(); await server.close(); }
