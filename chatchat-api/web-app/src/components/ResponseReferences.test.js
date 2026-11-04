// @vitest-environment jsdom
import { afterEach, expect, it } from 'vitest';
import { createApp, h, nextTick } from 'vue';
import ResponseReferences from './ResponseReferences.vue';

let app, host;
afterEach(() => { app?.unmount(); host?.remove(); });
async function mount(props = {}) {
  host = document.createElement('div'); document.body.append(host);
  app = createApp({ render: () => h(ResponseReferences, { actionsAvailable: true, ...props }, { actions: () => h('button', '有用') }) });
  app.mount(host); await nextTick();
}
it('keeps sources and actions together without a duplicate reference list', async () => {
  const id = 'a'.repeat(64);
  await mount({ sources: [{ title: '公司公告 / 发布 2026-11-02T09:10:09Z / 采集 2026-11-03T04:05:56Z', snippet: `[原文](runtime-evidence:knowledge:${id})` }] });
  expect(host.querySelector('footer').textContent).toContain('有用');
  expect(host.querySelector('.answer-source-panel')).toBeNull();
  host.querySelector('.source-panel-toggle').click(); await nextTick();
  expect(host.textContent).not.toContain('引用文档');
  expect(host.textContent).not.toContain('runtime-evidence:');
  const link = host.querySelector('.answer-source-link');
  expect(link.textContent).toBe('公司公告');
  expect(link.getAttribute('href')).toBe(`#knowledge-evidence-${id}`);
  expect(link.hasAttribute('target')).toBe(false);
  expect(host.querySelector('.answer-source-meta').textContent).toContain('2026-11-02');
  host.querySelector('[aria-label="关闭来源侧栏"]').click(); await nextTick();
  expect(host.querySelector('.answer-source-panel')).toBeNull();
});
it('retains actions with no sources and prevents unsafe source navigation', async () => {
  await mount();
  expect(host.querySelector('footer').textContent).toBe('有用');
  expect(host.querySelector('.source-panel-toggle')).toBeNull();
});
it('shows external links and neutralizes unsupported schemes', async () => {
  await mount({ sources: [
    { title: '市场日报', url: 'https://example.com/report', publisher: '财经日报' },
    { title: '无效来源', deepLink: 'javascript:alert(1)' }
  ] });
  host.querySelector('.source-panel-toggle').click(); await nextTick();
  const links = host.querySelectorAll('.answer-source-link');
  expect(links).toHaveLength(1);
  expect(links[0].getAttribute('rel')).toContain('noopener');
  expect(host.querySelector('.answer-source-meta').textContent).toContain('财经日报');
  expect(host.textContent).toContain('无效来源');
});
