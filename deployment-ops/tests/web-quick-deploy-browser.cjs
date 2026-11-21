'use strict';

const assert = require('node:assert/strict');
const { readFile, mkdir, writeFile } = require('node:fs/promises');
const { join } = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

async function main() {
  const base = process.env.OPS_QA_URL || 'http://127.0.0.1:19240';
  const out = process.env.OPS_TEST_ARTIFACTS;
  const environment = process.env.OPS_TEST_ENV || 'web-deploy-qa';
  const password = process.env.OPS_ADMIN_PASSWORD;
  const media = process.env.OPS_TEST_MEDIA;
  const checksum = process.env.OPS_TEST_CHECKSUM;
  const digest = (await readFile(checksum, 'utf8')).trim().split(/\s+/, 1)[0];
  const browser = await chromium.launch({
    executablePath: process.env.CHROME_PATH,
    headless: true,
    args: ['--no-proxy-server'],
  });
  await mkdir(out, { recursive: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1024 } });
  page.setDefaultTimeout(30000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));

  async function api(path) {
    const response = await page.request.get(base + '/api' + path);
    assert.equal(response.status(), 200, path);
    return response.json();
  }
  async function screenshot(name) {
    await page.screenshot({ path: join(out, name + '.png'), fullPage: true });
  }

  try {
    await page.goto(base + '/#/login');
    if ((await api('/setup')).required) {
      await page.getByLabel('安装令牌', { exact: true }).fill(process.env.OPS_SETUP_TOKEN);
      await page.getByLabel('密码', { exact: true }).fill(password);
      await page.getByLabel('确认密码', { exact: true }).fill(password);
      await page.getByRole('button', { name: '创建管理员并登录' }).click();
    } else {
      await page.getByLabel('账号', { exact: true }).fill('admin');
      await page.getByLabel('密码', { exact: true }).fill(password);
      await page.getByRole('button', { name: '登录', exact: true }).click();
    }
    const health = await api('/platform/health').catch(async () => {
      await page.waitForURL('**/#/overview');
      return api('/platform/health');
    });
    assert.equal(health.maintenanceMode, 'standalone');
    assert.equal(health.executorImplementation, 'JDK');
    await page.getByRole('link', { name: '部署应用', exact: true }).click();
    await page.getByText('主机已连接，持久化数据位于 /mnt/disk01', { exact: true }).waitFor();
    assert.equal(await page.locator('select').first().inputValue(), '1');
    await page.getByLabel('环境名称', { exact: false }).fill(environment);
    const catalog = await api('/nodes/1/packages');
    if (!catalog.some((item) => item.digest === digest)) {
      const importer = page.locator('details').filter({ has: page.locator('input[accept=".zip"]') });
      if ((await importer.getAttribute('open')) === null) await importer.locator('summary').click();
      await page.locator('input[accept=".zip"]').setInputFiles(media);
      await page.getByLabel('读取 SHA-256 文件', { exact: true }).setInputFiles(checksum);
      await page.waitForFunction((expected) =>
        document.getElementById('package-upload-sha256')?.value === expected, digest);
      assert.equal(await page.getByLabel('发行方 SHA-256', { exact: true }).inputValue(), digest);
      await page.getByRole('button', { name: '上传并验证应用包' }).click();
      await page.getByText('发行包已验证，可以配置参数并运行预检。', { exact: true })
        .waitFor({ timeout: 180000 });
    } else await page.getByLabel('发行版本', { exact: true }).selectOption(digest);
    assert.equal(await page.getByLabel('发行版本', { exact: true }).inputValue(), digest);
    await page.locator('#parameter-greeting').fill('browser-deploy-verified');
    await screenshot('01-configuration');
    const preflight = page.waitForResponse((response) =>
      response.url().endsWith('/installations/preflight') && response.request().method() === 'POST'
    );
    await page.getByRole('button', { name: '运行部署预检' }).click();
    const checked = await preflight;
    assert.equal(checked.status(), 200);
    const review = await checked.json();
    assert.equal(review.ok, true, JSON.stringify(review.checks));
    assert.ok(!review.upgrade);
    await screenshot('02-preflight');
    const submitted = page.waitForResponse((response) =>
      response.url().endsWith('/installations') && response.request().method() === 'POST'
    );
    await page.getByRole('button', { name: '开始部署' }).click();
    const accepted = await submitted;
    assert.equal(accepted.status(), 200);
    let job = await accepted.json();
    for (const until = Date.now() + 90000; Date.now() < until;) {
      job = await api('/nodes/1/installations/' + job.id);
      if (!['queued', 'running', 'starting', 'rolling_back'].includes(job.status)) break;
      await new Promise((resolve) => setTimeout(resolve, 1000));
    }
    assert.equal(job.status, 'succeeded', JSON.stringify(job.steps));
    await page.getByRole('link', { name: '查看任务详情' }).waitFor();
    await page.waitForFunction(() => document.querySelector('main')?.innerText.includes('成功'));
    await screenshot('03-result');
    assert.deepEqual(errors, []);
    const proof = { passed: true, version: health.version, environment, packageDigest: digest,
      jobId: job.id, status: job.status, steps: job.steps, browserErrors: errors };
    await writeFile(join(out, 'verification.json'), JSON.stringify(proof, null, 2));
    console.log(JSON.stringify(proof));
  } finally {
    await browser.close();
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
