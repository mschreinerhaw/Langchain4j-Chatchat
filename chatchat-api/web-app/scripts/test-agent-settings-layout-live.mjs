// Real page and authorized read-only API data; form toggles are cancelled, never saved.
import { chromium } from 'playwright-core';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
const origin = process.env.AGENT_LAYOUT_ORIGIN;
const authFile = process.env.AGENT_LAYOUT_AUTH_FILE;
assert.ok(origin && authFile, 'Set AGENT_LAYOUT_ORIGIN and AGENT_LAYOUT_AUTH_FILE');
const auth = JSON.parse(fs.readFileSync(authFile));
const out = path.resolve(process.env.AGENT_LAYOUT_OUTPUT || '../../target/agent-settings-layout');
fs.mkdirSync(out, { recursive: true });
const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
const results = [];
try {
  for (const viewport of [{ width: 1240, height: 824 }, { width: 990, height: 660 }, { width: 390, height: 844 }]) {
    const context = await browser.newContext({ viewport });
    await context.addInitScript(value => localStorage.setItem('chatchat.auth.session', JSON.stringify(value)), auth);
    // Optional local production build with requests forwarded to the real server, not mocked.
    if (process.env.AGENT_LAYOUT_API_ORIGIN) await context.route('**/api/**', async route => {
      const original = new URL(route.request().url());
      const response = await route.fetch({ url: process.env.AGENT_LAYOUT_API_ORIGIN + original.pathname + original.search });
      await route.fulfill({ response });
    });
    const page = await context.newPage();
    await page.goto(origin + '/index.html#/w/zhice/agents');
    const name = process.env.AGENT_LAYOUT_NAME || '金融文档分析助手';
    await page.getByPlaceholder('名称、场景、标签或工具').fill(name);
    const card = page.locator('.agent-card').filter({ has: page.getByRole('heading', { name, exact: true }) });
    await card.getByRole('button', { name: '设置', exact: true }).click();
    const dialog = page.locator('.agent-dialog');
    await dialog.waitFor();
    const skills = dialog.locator('.agent-resource-selector').filter({ has: page.locator('strong', { hasText: /^Skills$/ }) });
    await skills.scrollIntoViewIfNeeded();
    const geometry = await dialog.evaluate(form => {
      const rect = el => { const r = el.getBoundingClientRect(); return { top: r.top, bottom: r.bottom, left: r.left, right: r.right, width: r.width, height: r.height }; };
      return { form: rect(form), overflow: form.scrollWidth > form.clientWidth,
        sections: [...form.querySelectorAll('.dialog-body > section')].map(section => ({ ...rect(section),
          content: [...section.querySelectorAll('.agent-resource-selector-copy')].map(rect) })),
        checks: [...form.querySelectorAll('.agent-builtin-skill-option')].map(label => ({ label: rect(label), input: rect(label.querySelector('input')), text: rect(label.querySelector('span')) })),
        footer: rect(form.querySelector('footer')) };
    });
    assert.equal(geometry.overflow, false, 'Dialog overflows horizontally');
    assert.equal(geometry.checks.length, 2);
    for (const check of geometry.checks) {
      assert.equal(check.input.width, 16); assert.equal(check.input.height, 16);
      assert.ok(check.input.right < check.text.left, 'Checkbox and text overlap');
      assert.ok(check.text.bottom <= check.label.bottom + 1, 'Wrapped label escapes its row');
    }
    for (let i = 0; i < geometry.sections.length; i++) {
      const section = geometry.sections[i];
      if (i) assert.ok(geometry.sections[i - 1].bottom <= section.top + 1, 'Resource sections overlap');
      for (const content of section.content) {
        assert.ok(content.top >= section.top - 1 && content.bottom <= section.bottom + 1, 'Resource copy escapes its row');
      }
    }
    assert.ok(geometry.footer.top >= 0 && geometry.footer.bottom <= viewport.height, 'Save/cancel footer is clipped');
    const checkbox = skills.locator('input[value="builtin:python-data-analysis"]');
    const initial = await checkbox.isChecked();
    await checkbox.setChecked(!initial); assert.equal(await checkbox.isChecked(), !initial);
    await checkbox.setChecked(initial);
    await page.screenshot({ path: path.join(out, `agent-settings-${viewport.width}.png`) });
    await skills.getByRole('button', { name: /选择 Skills|调整 Skills/ }).click();
    await page.getByRole('dialog', { name: '选择 Skills', exact: true }).waitFor();
    await page.getByRole('button', { name: '关闭 Skill 选择', exact: true }).click();
    await dialog.getByRole('button', { name: '取消', exact: true }).click();
    results.push({ viewport, status: 'PASSED', geometry });
    await context.close();
  }
  fs.writeFileSync(path.join(out, 'layout-checks.json'), JSON.stringify({ origin, apiOrigin: process.env.AGENT_LAYOUT_API_ORIGIN || origin, capturedAt: new Date().toISOString(), writesSaved: false, results }, null, 2));
  console.log(JSON.stringify({ status: 'PASSED', viewports: results.length, writesSaved: false }));
} finally {
  await browser.close();
}
