import { expect, test } from '@playwright/test';

test('admin can delete an upstream after removing its model mappings', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('管理员密码').fill('fixture-admin-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('menuitem', { name: '上游模型服务' }).click();

  const revision = await (await page.request.get('/admin/config')).json();
  const suffix = Date.now();
  const service = { id: 'delete-ui-service-' + suffix, name: 'Deletion fixture ' + suffix, origin: 'https://deletion.example.invalid' };
  const model = { alias: 'delete-ui-model-' + suffix, upstream: 'fixture-model', service: service.id, enabled: false };
  const saved = await page.request.put('/admin/config', { data: { version: revision.version,
    config: { ...revision.config, services: [...revision.config.services, service], models: [...revision.config.models, model] } } });
  expect(saved.ok()).toBeTruthy();
  const version = (await saved.json()).version;

  await page.reload();
  const services = page.getByRole('menuitem', { name: '上游模型服务' });
  await services.click();
  const serviceRow = page.getByRole('row').filter({ hasText: service.name });
  const deleteService = serviceRow.getByRole('button', { name: '删除', exact: true });
  const dialog = page.getByRole('dialog');
  await deleteService.click();
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText(model.alias);
  await dialog.getByRole('button', { name: '知道了' }).click();
  await expect(serviceRow).toBeVisible();
  expect((await (await page.request.get('/admin/config')).json()).version).toBe(version);

  await page.getByRole('menuitem', { name: '模型映射' }).click();
  const modelRow = page.getByRole('row').filter({ hasText: model.alias });
  await modelRow.getByRole('button', { name: '删除', exact: true }).click();
  const confirm = dialog.getByRole('button', { name: '确认删除' });
  await confirm.click();
  await expect(modelRow).toHaveCount(0);

  await services.click();
  await deleteService.click();
  await dialog.getByRole('button', { name: /取\s*消/ }).click();
  await expect(serviceRow).toBeVisible();
  await deleteService.click();
  await confirm.click();
  await expect(serviceRow).toHaveCount(0);
  await page.reload();
  await services.click();
  await expect(serviceRow).toHaveCount(0);
});

test('integration uses public IDs in copyable curl and runs published listener tests', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('管理员密码').fill('fixture-admin-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('menuitem', { name: '接入配置' }).click();
  const revision = await (await page.request.get('/admin/config')).json();
  const alias = 'Qwen/Qwen3.8-27B';
  const service = { id: 'integration-ui', name: 'Integration fixture', origin: process.env.TEST_FIXTURE ?? 'http://mock:19090',
    apiKey: 'fixture-upstream-credential' };
  const saved = await page.request.put('/admin/config', { data: { version: revision.version,
    config: { services: [service], models: [{ alias, displayName: '浏览器演示', upstream: '/models/Ui', service: service.id }] } } });
  expect(saved.ok()).toBeTruthy();
  await page.reload();
  await page.getByRole('menuitem', { name: '接入配置' }).click();
  await page.getByRole('tab', { name: 'curl', exact: true }).click();
  const example = page.locator('.integration-example pre');
  await expect(example).toContainText('"model": "' + alias + '"');
  await expect(example).not.toContainText('request.json');
  await page.getByText('Windows PowerShell', { exact: true }).click();
  await page.getByText('流式请求', { exact: true }).click();
  await expect(example).toContainText('curl.exe -i -N');
  await expect(example).toContainText('"stream": true');
  await page.getByRole('button', { name: '运行测试', exact: true }).click();
  await expect(page.getByText('API Key 与模型列表', { exact: true })).toBeVisible();
  await expect(page.getByText('通过', { exact: true })).toHaveCount(2);
});

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
  await expect(page.getByText(address + '/v1/chat/completions', { exact: true })).toBeVisible();
  await expect(page.getByText(address + '/v1/models', { exact: true })).toBeVisible();
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
  const upstream = '/models/Browser';
  await page.getByLabel('上游模型 ID', { exact: true }).fill(upstream);
  await page.getByRole('combobox', { name: '支持模型', exact: true }).fill(alias);
  await page.locator('.ant-select-item-option-content').filter({ hasText: /^qwen3\.6-35b-a3b$/ }).click();
  await page.getByRole('button', { name: '验证并发布' }).click();
  await expect(page.getByRole('cell', { name: upstream + ' → ' + alias, exact: true })).toBeVisible();
  await page.getByRole('menuitem', { name: '请求实验室' }).click();
  await page.getByRole('button', { name: '发送请求' }).click();
  await expect(page.getByText(/HTTP 200/)).toBeVisible();
  for (const stage of ['标准客户端请求', '发送给上游的请求', '上游原始响应', '网关标准响应']) {
    await expect(page.getByText(stage, { exact: true })).toBeVisible();
  }
  await expect(page.getByText('响应字段差异', { exact: true })).toBeVisible();
  if (process.env.TEST_SCREENSHOT) await page.screenshot({ path: process.env.TEST_SCREENSHOT, fullPage: true });
  await page.getByRole('menuitem', { name: '服务概览' }).click();
  await Promise.all([
    page.waitForResponse(response => response.url().endsWith('/admin/overview?range=24h') && response.ok()),
    page.getByText('最近 24 小时', { exact: true }).click(),
  ]);
  await expect(page.getByRole('radio', { name: '最近 24 小时' })).toBeChecked();
  const requestChart = page.getByRole('group', { name: '请求量趋势，单位次' });
  await expect(requestChart.getByRole('button', { name: /成功 [1-9]/ }).first()).toBeVisible();
  await expect(page.getByRole('group', { name: '响应耗时趋势，单位ms' })).toBeVisible();
  await page.getByRole('button', { name: '查看请求日志' }).click();
  await expect(page.getByRole('heading', { name: '请求日志' })).toBeVisible();
  await expect(page.getByRole('menuitem', { name: '请求日志' })).toBeVisible();
  await expect(page.getByRole('columnheader', { name: '首 Token ms', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '退出' }).click();
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeVisible();
  expect(errors).toEqual([]);
});
