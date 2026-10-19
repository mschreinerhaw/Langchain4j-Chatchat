import { chromium } from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';

const base = process.env.CHATCHAT_STAGE_URL || 'http://192.168.195.221:18100';
const session = JSON.parse(readFileSync(process.env.CHATCHAT_STAGE_SESSION_FILE, 'utf8'));
const accepted = JSON.parse(readFileSync(process.env.CHATCHAT_RESTORATION_ACCEPTANCE_FILE, 'utf8'));
const browser = await chromium.launch({ executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true, args: ['--no-proxy-server'] });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, timezoneId: 'Asia/Shanghai', locale: 'zh-CN' });
await context.addInitScript(session => localStorage.setItem('chatchat.auth.session', JSON.stringify(session)), session);
const page = await context.newPage();
const errors = [];
page.on('pageerror', error => errors.push(error.name));
const results = [];
async function open(id) {
  await page.locator('.recent-card').first().waitFor({ timeout: 30000 });
  const search = page.getByPlaceholder('搜索历史会话');
  await search.fill(id);
  for (let attempt = 0; attempt < 40 && await page.locator('.recent-card').count() === 0; attempt++) {
    await search.fill('');
    const expand = page.getByRole('button', { name: '查看全部对话', exact: true });
    if (await expand.count()) await expand.click();
    const more = page.getByRole('button', { name: '加载更多历史', exact: true });
    if (!await more.count()) throw new Error('Session missing from persisted history: '+JSON.stringify({ id, head: await page.locator('.recent-head').innerText(), buttons: await page.locator('.recent-block .more-link').allTextContents() }));
    const loaded = page.waitForResponse(r => r.url().includes('/summaries?') && r.status() === 200);
    await more.click(); await loaded;
    await more.waitFor({ state: 'visible', timeout: 10000 }).catch(() => {});
    await search.fill(id);
  }
  const loaded = page.waitForResponse(r => new URL(r.url()).pathname === `/api/v1/conversations/${id}` && r.status() === 200);
  await page.locator('.recent-card').first().click();
  // A second click may reuse an already hydrated session; callers reload the page first.
  const response = await loaded;
  const detail = (await response.json()).data;
  await page.locator('.history-detail-spinner').waitFor({ state: 'hidden' });
  return detail;
}
async function verifyRenderedState(detail) {
  const latestTaskId=[...detail.messages].reverse().find(m=>m.role==='assistant' && m.taskId)?.taskId || '';
  await page.waitForFunction(({id,count,task})=>{
    const root=document.querySelector('#app')?._vnode?.component?.proxy;
    const view=root?.$refs?.activeViewComponent;
    return root?.selectedConversation?.id===id && root.selectedConversation.messages?.length===count
      && view?.historyId===id && (!task || view.messages.some(message=>message.taskId===task));
  },{id:detail.id,count:detail.messages.length,task:latestTaskId},{timeout:30000});
  return !!latestTaskId;
}
try {
  const targets = accepted.visibleHistory.filter(row => row.messages >= 2 && !row.missingMessageCount).slice(0, 3);
  if (targets.length < 2) throw new Error('Both recent and old platform conversations are required');
  for (const target of targets) {
    await page.goto(`${base}/#/w/zhice/chat`, { waitUntil: 'domcontentloaded' });
    await page.getByPlaceholder('搜索历史会话').waitFor();
    const detail = await open(target.sessionId);
    const latestTaskVerified=await verifyRenderedState(detail);
    console.log(JSON.stringify({stage:'OPENED', sessionId:target.sessionId, apiMessages:detail.messages.length,
      renderedMessages:await page.locator('.chat-message').count(), renderedAssistants:await page.locator('.chat-message.assistant').count(),
      historyErrors:await page.locator('.history-detail-error').count(), loading:await page.locator('.history-detail-spinner').count(), url:page.url()}));
    await page.locator('.chat-message.assistant').first().waitFor({ timeout: 30000 });
    if (detail.missingMessageCount !== 0 || detail.messages.length < 2) throw new Error('Incomplete restoration');
    const text = await page.locator('.message-panel').innerText();
    if (!text.trim() || await page.locator('.history-detail-error').count()) throw new Error('History failed to render');
    const firstHash = createHash('sha256').update(JSON.stringify(detail.messages)).digest('hex');
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByPlaceholder('搜索历史会话').waitFor();
    const reopened = await open(target.sessionId);
    await verifyRenderedState(reopened);
    await page.locator('.chat-message.assistant').first().waitFor({ timeout: 30000 }).catch(async error => {
      const state=await page.evaluate(()=>{
        const root=document.querySelector('#app')?._vnode?.component?.proxy;
        const view=root?.$refs?.activeViewComponent;
        return {selectedId:root?.selectedConversation?.id,selectedMessages:root?.selectedConversation?.messages?.length,
          activeId:root?.activeHistoryId,workspace:root?.currentWorkspace?.workspaceId,workspaceLoading:root?.workspaceLoading,
          activeView:root?.activeView,viewHistoryId:view?.historyId,viewMessages:view?.messages?.length,
          propMessages:view?.selectedConversation?.messages?.length,viewStatus:view?.conversationStatus,
          immediateWatcher:view?.$options?.watch?.selectedConversation?.immediate};
      });
      console.log(JSON.stringify({stage:'RESTORATION_STATE',...state}));throw error;
    });
    if (createHash('sha256').update(JSON.stringify(reopened.messages)).digest('hex') !== firstHash) throw new Error('Restored state changed after reload');
    results.push({ sessionId: target.sessionId, messages: detail.messages.length, reopenedAfterReload: true,
      richResponses: detail.messages.filter(m => m.uiResponse && Object.keys(m.uiResponse).length > 0).length,
      assistantRendered: true, latestTaskVerified, streamingPlaceholders: await page.locator('.chat-message.streaming').count() });
    if (['completed','cancelled','failed'].includes(target.status) && results.at(-1).streamingPlaceholders !== 0)
      throw new Error('A terminal conversation still appears to be streaming');
  }
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.getByPlaceholder('搜索历史会话').waitFor();
  const incomplete = await open(accepted.visibleIncompleteHistory.sessionId);
  await page.getByText('部分历史消息尚未恢复', { exact: true }).waitFor();
  if (!incomplete.missingMessageCount) throw new Error('Missing history was hidden');
  const report = { realDeployedPage: !base.includes('127.0.0.1'), localBuiltFrontendAgainstRealStageApi: base.includes('127.0.0.1'), requestMocking: false, browserCacheSeeded: false, results,
    incompleteHistoryVisible: true, missingMessages: incomplete.missingMessageCount, pageErrors: errors };
  writeFileSync(process.env.CHATCHAT_RESTORATION_BROWSER_OUTPUT, JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report));
  if (errors.length) throw new Error('Browser errors');
} finally { await browser.close(); }
