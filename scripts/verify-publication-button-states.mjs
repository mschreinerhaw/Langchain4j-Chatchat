// Exercise the deployed SQL dialog CSS without requiring a workspace login.
import { chromium } from '../chatchat-api/web-app/node_modules/playwright-core/index.mjs';
import fs from 'node:fs';
import assert from 'node:assert/strict';
const out = 'target/sql-publication-button-fix';
const assets = JSON.parse(fs.readFileSync(out + '/live-style-assets.json'));
const browser = await chromium.launch({ executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true, args: ['--no-proxy-server'] });
try {
  const page = await browser.newPage({ viewport: { width: 1000, height: 700 } });
  await page.goto(assets.base + '/index.html');
  await page.addStyleTag({ url: assets.base + '/assets/' + assets.styles[0] });
  await page.evaluate(scope => {
    const panel = document.createElement('form');
    panel.id = 'publication-button-style-check';
    panel.className = 'sql-dialog sql-publication-panel sql-test-parameters-dialog';
    panel.style = 'position:fixed;left:20%;top:20%;z-index:99999;background:white';
    panel.innerHTML = '<header><h2>填写测试参数</h2></header><p>模板发布测试 · 按钮交互样式检查</p><footer class="sql-publication-footer sql-test-parameters-footer"><button type="button">取消</button><button type="submit" class="sql-publication-primary">执行测试</button></footer>';
    [panel, ...panel.querySelectorAll('*')].forEach(n => n.setAttribute(scope, ''));
    panel.addEventListener('submit', e => e.preventDefault());
    document.body.append(panel);
  }, assets.scope);
  const button = page.locator('#publication-button-style-check button.sql-publication-primary');
  const style = () => button.evaluate(e => {
    const s = getComputedStyle(e);
    return { background: s.backgroundColor, color: s.color, opacity: s.opacity, outlineStyle: s.outlineStyle };
  });
  await button.hover();
  const before = await style();
  if (process.env.E2E_DEPLOYED !== '1') await page.addStyleTag({ content: fs.readFileSync(out + '/primary-button.css', 'utf8') });
  const hover = await style();
  assert.equal(hover.color, 'rgb(255, 255, 255)');
  assert.notEqual(hover.background, 'rgb(238, 244, 255)', 'Generic dialog hover must not override the primary background');
  assert.notEqual(hover.background, 'rgb(255, 255, 255)');
  await page.mouse.down();
  const active = await style();
  await page.mouse.up();
  assert.equal(active.background, hover.background);
  await page.mouse.move(0, 0);
  await button.focus();
  await page.keyboard.press('Shift+Tab');
  await page.keyboard.press('Tab');
  const focus = await style();
  assert.equal(focus.outlineStyle, 'solid');
  await button.evaluate(e => { e.disabled = true; e.textContent = '查询中…'; });
  const disabled = await style();
  assert.equal(disabled.color, 'rgb(255, 255, 255)');
  assert.equal(disabled.opacity, '0.65');
  assert.notEqual(disabled.background, 'rgb(255, 255, 255)');
  const name = process.env.E2E_DEPLOYED === '1' ? 'deployed' : 'local';
  await page.screenshot({ path: out + '/' + name + '-loading.png' });
  fs.writeFileSync(out + '/' + name + '-style-states.json', JSON.stringify({ isolatedStyleFixture: true, before, hover, active, focus, disabled }, null, 2));
  console.log(JSON.stringify({ before, hover, active, focus, disabled }));
} finally { await browser.close(); }
