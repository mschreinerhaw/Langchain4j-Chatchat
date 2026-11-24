import { expect, test } from '@playwright/test';

test.skip(!process.env.TEST_TLS_SPKI, 'Requires the isolated TLS fixture');

test('certificate management, CA export, HTTPS replay and SDK onboarding', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('管理员密码').fill('fixture-admin-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('menuitem', { name: 'HTTPS 证书' }).click();
  await expect(page.getByText('当前服务器证书')).toBeVisible();
  await expect(page.getByText('https://localhost:8443/v1', { exact: true })).toBeVisible();
  const ca = page.waitForEvent('download');
  await page.getByRole('link', { name: '下载 CA' }).click();
  expect((await ca).suggestedFilename()).toBe('gateway-ca.crt');
  if (process.env.TEST_TLS_SCREENSHOT) await page.screenshot({ path: process.env.TEST_TLS_SCREENSHOT, fullPage: true });
  await page.getByRole('menuitem', { name: '请求实验室' }).click();
  await page.getByLabel('导入脱敏请求').setInputFiles({ name: 'langchain4j-sample.json', mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify({ profile: 'langchain4j', headers: { Authorization: 'redacted', 'User-Agent': 'LangChain4j fixture' },
      body: { model: 'sdk-model', messages: [{ role: 'user', content: 'Replay SDK capture' }] } })) });
  await expect(page.getByLabel('请求 JSON')).toHaveValue(/Replay SDK capture/);
  await expect(page.getByLabel('附加请求头 JSON')).not.toHaveValue(/Authorization/);
  await page.getByRole('button', { name: '发送请求' }).click();
  await expect(page.getByText('HTTPS 连接及认证')).toBeVisible();
  await expect(page.getByText('客户端协议兼容性')).toBeVisible();
  await expect(page.locator('.ant-card').filter({ hasText: '发送给上游的请求' })).toContainText('java-fixture-profile');
  await expect(page.getByText(/HTTP 200/)).toBeVisible();
  if (process.env.TEST_REPLAY_SCREENSHOT) await page.screenshot({ path: process.env.TEST_REPLAY_SCREENSHOT, fullPage: true });
  await page.getByRole('menuitem', { name: '接入配置' }).click();
  await page.getByRole('tab', { name: 'LangChain4j / JVM' }).click();
  await expect(page.getByText(/dev.langchain4j:langchain4j-open-ai:1.20.0/)).toBeVisible();
});
