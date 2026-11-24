import { expect, test } from '@playwright/test';

test('admin can save a new upstream address with a path prefix', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('管理员密码').fill('fixture-admin-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('menuitem', { name: '上游模型服务' }).click();
  await page.getByRole('button', { name: '新增服务' }).click();
  const suffix = Date.now();
  const address = 'https://upstream-' + suffix + '.example.invalid/compatible-mode';
  await page.getByLabel('服务 ID', { exact: true }).fill('browser-' + suffix);
  await page.getByLabel('服务名称', { exact: true }).fill('Browser upstream ' + suffix);
  const input = page.getByRole('textbox', { name: '上游地址' });
  await input.fill('ftp://invalid.example');
  const publish = page.getByRole('button', { name: '验证并发布' });
  await publish.click();
  await expect(page.getByText('请输入有效的 HTTP(S) 地址', { exact: true })).toBeVisible();
  await input.fill(address);
  await publish.click();
  const cell = page.getByRole('cell', { name: address + ' openai', exact: true });
  await expect(cell).toBeVisible();
  await page.reload();
  await page.getByRole('menuitem', { name: '上游模型服务' }).click();
  await expect(cell).toBeVisible();
});

test('admin can publish a mapping, compare four stages, inspect logs and log out', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/');
  await page.getByLabel('管理员密码').fill('fixture-admin-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '服务概览' })).toBeVisible();
  await page.getByRole('menuitem', { name: '模型映射' }).click();
  await page.getByRole('button', { name: '新增映射' }).click();
  const alias = 'qwen3.6-35b-a3b';
  await page.getByRole('combobox', { name: 'Cline 对外模型' }).fill(alias);
  await page.locator('.ant-select-item-option-content').filter({ hasText: /^qwen3\.6-35b-a3b$/ }).click();
  await page.getByLabel('上游真实模型 ID').fill('/models/Browser');
  await page.getByRole('button', { name: '验证并发布' }).click();
  await expect(page.getByText(alias, { exact: true })).toBeVisible();
  await page.getByRole('menuitem', { name: '请求实验室' }).click();
  await page.getByRole('button', { name: '发送请求' }).click();
  await expect(page.getByText(/HTTP 200/)).toBeVisible();
  for (const stage of ['标准客户端请求', '发送给上游的请求', '上游原始响应', '网关标准响应']) {
    await expect(page.getByText(stage, { exact: true })).toBeVisible();
  }
  await expect(page.getByText('响应字段差异', { exact: true })).toBeVisible();
  if (process.env.TEST_SCREENSHOT) await page.screenshot({ path: process.env.TEST_SCREENSHOT, fullPage: true });
  await page.getByRole('menuitem', { name: '请求监控' }).click();
  await expect(page.getByRole('columnheader', { name: '首 Token ms', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '退出' }).click();
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeVisible();
  expect(errors).toEqual([]);
});
