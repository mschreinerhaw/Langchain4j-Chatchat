import { expect, test } from '@playwright/test';

test('admin can publish a mapping, compare four stages, inspect logs and log out', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/');
  await page.getByLabel('管理员密码').fill('fixture-admin-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '服务概览' })).toBeVisible();
  await page.getByRole('menuitem', { name: '模型映射' }).click();
  await page.getByRole('button', { name: '新增映射' }).click();
  const alias = 'ui-' + Date.now();
  await page.getByLabel('对外模型 ID').fill(alias);
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
