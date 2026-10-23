import { chromium } from "../chatchat-api/web-app/node_modules/playwright-core/index.mjs";
import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import assert from "node:assert/strict";
const upstream = "http://192.168.195.221:18100";
const base = process.env.CHATCHAT_STAGE_URL || upstream;
const readonly = process.env.CHATCHAT_READONLY === "1";
const out = process.env.CHATCHAT_BROWSER_OUTPUT || "target/tool-draft-edit-live";
mkdirSync(out, { recursive: true });
const browser = await chromium.launch({
  executablePath: "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  headless: true,
  args: ["--no-proxy-server"],
});
const own = new Set(),
  checks = {},
  errors = [];
let api, context, page;
try {
  context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: "zh-CN" });
  const credentials = JSON.parse(
    readFileSync(
      process.env.CHATCHAT_CREDENTIAL_FILE || "target/workspace-stage-auth-private.json",
      "utf8"
    )
  );
  const login = await (
    await context.request.post(upstream + "/api/v1/enterprise/auth/login", { data: credentials })
  ).json();
  assert.ok(login.data?.token);
  await context.addInitScript(
    (s) => localStorage.setItem("chatchat.auth.session", JSON.stringify(s)),
    login.data
  );
  api = async (path, body, method = body ? "POST" : "GET", expected = 200) => {
    const r = await context.request.fetch(upstream + "/api/v1" + path, {
      method,
      headers: { Authorization: "Bearer " + login.data.token, "X-Workspace-Id": "mcp-publication" },
      ...(body ? { data: body } : {}),
    });
    const value = await r.json();
    assert.equal(r.status(), expected, path + ": " + value.message);
    if (expected === 200) assert.equal(value.code, 200, path);
    return value.data;
  };
  const before = await api("/tool-publications");
  page = await context.newPage();
  page.setDefaultTimeout(60000);
  page.on("dialog", (d) => d.accept());
  page.on("pageerror", (e) => errors.push(e.message));
  if (base !== upstream)
    await page.route("**/api/**", async (route) => {
      const u = new URL(route.request().url());
      await route.fulfill({
        response: await route.fetch({ url: upstream + u.pathname + u.search }),
      });
    });
  const ready = () => page.locator('.tool-publications[aria-busy="false"]').waitFor();
  async function selectTool(title) {
    await page.getByLabel("版本状态", { exact: true }).selectOption("PENDING");
    await page.getByPlaceholder("搜索名称或说明").fill(title);
    await page.locator(".permission-master-item").filter({ hasText: title }).click();
    await ready();
  }
  async function action(name, suffix, expected = 200) {
    const response = page.waitForResponse(
      (r) =>
        r
          .url()
          .split("?")[0]
          .endsWith("/" + suffix) && r.request().method() === "POST"
    );
    await page.getByRole("button", { name, exact: true }).click();
    const r = await response;
    assert.equal(r.status(), expected, await r.text());
    await ready();
    return (await r.json()).data;
  }
  const invalid = before.find(
    (r) =>
      r.state === "DRAFT" &&
      !r.publishedAt &&
      !/^[a-z][a-z0-9_]{0,63}$/.test(JSON.parse(r.definitionJson).domainCode)
  );
  await page.goto(base + "/#/w/mcp-publication/toolPublications");
  await page.locator(".publication-detail").waitFor();
  await ready();
  if (invalid) {
    await selectTool(invalid.title);
    await action("校验定义", "validate", 400);
    await page.locator(".publication-editor").waitFor();
    assert.equal(await page.locator("input[pattern]").isEnabled(), true);
    assert.equal(await page.getByRole("button", { name: "保存当前草稿", exact: true }).count(), 1);
    assert.ok((await page.getByRole("alert").innerText()).includes("工具标识"));
    await page.screenshot({ path: out + "/failed-validation-editor.png" });
    await page.getByRole("button", { name: "取消", exact: true }).click();
    const after = (await api("/tool-publications")).find((r) => r.id === invalid.id);
    assert.deepEqual(after, invalid);
    checks.existingInvalidDraftCanReturnToEditorWithoutChanges = true;
  }
  if (!readonly) {
    const seed = before.find((r) => r.state === "RUNNING" && r.kind === "CONTROLLED_TEMPLATE");
    assert.ok(seed);
    const definition = {
      ...JSON.parse(seed.definitionJson),
      domainCode: "错误标识",
      enabled: false,
    };
    delete definition.chineseAlias;
    const stamp = Date.now().toString(36),
      title = "工具版本修复验收 " + stamp,
      domain = "draft_edit_" + stamp;
    let row = await api("/tool-publications", {
      title,
      description: "仅用于发布流程验收，结束后下线。",
      kind: "CONTROLLED_TEMPLATE",
      definition,
      inputSchema: { type: "object", properties: { limit: { type: "integer", minimum: 1 } } },
      outputSchema: { type: "object" },
    });
    own.add(row.logicalId);
    await page.reload();
    await ready();
    await selectTool(title);
    await action("校验定义", "validate", 400);
    await page.locator("input[pattern]").fill(domain);
    const putResponse = page.waitForResponse(
      (r) => r.url().endsWith("/tool-publications/" + row.id) && r.request().method() === "PUT"
    );
    await page.getByRole("button", { name: "保存当前草稿", exact: true }).click();
    const put = await putResponse;
    assert.equal(put.status(), 200);
    let updated = (await put.json()).data;
    await ready();
    assert.equal(updated.id, row.id);
    assert.equal(updated.logicalId, row.logicalId);
    assert.equal(updated.releaseNumber, 1);
    assert.equal(updated.state, "DRAFT");
    checks.correctedDraftKeepsItsToolAndVersion = true;
    await api(
      "/tool-publications/" + row.id,
      {
        title: "stale write",
        description: "",
        definition: { ...definition, domainCode: domain },
        inputSchema: { type: "object" },
        outputSchema: { type: "object" },
        expectedVersion: row.version,
      },
      "PUT",
      409
    );
    checks.staleWriteRejected = true;
    await action("校验定义", "validate");
    await page.getByLabel("测试输入", { exact: true }).fill('{"limit":0}');
    await action("运行查询测试", "test", 400);
    await page.locator(".publication-editor").waitFor();
    await page.getByRole("button", { name: "取消", exact: true }).click();
    await page.getByRole("tab", { name: "测试与记录", exact: true }).click();
    assert.equal(await page.getByLabel("测试输入", { exact: true }).inputValue(), '{"limit":0}');
    checks.failedTestReturnsToSameDraftAndPreservesInput = true;
    await page.getByLabel("测试输入", { exact: true }).fill('{"limit":1}');
    await action("运行查询测试", "test");
    let tested = (await api("/tool-publications")).find((r) => r.id === row.id);
    assert.equal(tested.state, "TESTED");
    assert.ok(tested.testEvidenceJson);
    await page.getByRole("button", { name: "修改当前草稿", exact: true }).click();
    await page.getByLabel("使用说明", { exact: true }).fill("修改后必须重新校验和测试。");
    const edit = page.waitForResponse(
      (r) => r.url().endsWith("/tool-publications/" + row.id) && r.request().method() === "PUT"
    );
    await page.getByRole("button", { name: "保存当前草稿", exact: true }).click();
    assert.equal((await edit).status(), 200);
    await ready();
    updated = (await api("/tool-publications")).find((r) => r.id === row.id);
    assert.equal(updated.state, "DRAFT");
    assert.equal(updated.testEvidenceJson, null);
    assert.equal(await page.getByRole("button", { name: "发布工具", exact: true }).count(), 0);
    checks.editInvalidatesPreviousTestApproval = true;
    await action("校验定义", "validate");
    await page.getByLabel("测试输入", { exact: true }).fill('{"limit":1}');
    await action("运行查询测试", "test");
    await action("发布工具", "publish");
    await page.getByRole("button", { name: "创建新版本", exact: true }).click();
    assert.equal(await page.locator("input[pattern]").isDisabled(), true);
    await page.getByLabel("使用说明", { exact: true }).fill("同一工具的第二版本。");
    const create = page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/tool-publications") && r.request().method() === "POST"
    );
    await page.getByRole("button", { name: "保存新草稿", exact: true }).click();
    const second = (await (await create).json()).data;
    await ready();
    assert.equal(second.logicalId, row.logicalId);
    assert.equal(second.releaseNumber, 2);
    const options = await page
      .getByLabel("当前版本", { exact: true })
      .locator("option")
      .evaluateAll((els) => els.map((e) => e.value));
    assert.deepEqual(options, [second.id, row.id]);
    checks.revisionsBelongOnlyToTheirOwnTool = true;
    await page.screenshot({ path: out + "/tool-owned-versions.png" });
    await action("校验定义", "validate");
    await page.getByLabel("测试输入", { exact: true }).fill('{"limit":1}');
    await action("运行查询测试", "test");
    await action("发布工具", "publish");
    const history = (await api("/tool-publications")).filter((r) => r.logicalId === row.logicalId);
    assert.equal(history.length, 2);
    assert.equal(history.find((r) => r.id === row.id).state, "OFFLINE");
    assert.equal(history.find((r) => r.id === second.id).state, "RUNNING");
    await page.getByLabel("当前版本", { exact: true }).selectOption(row.id);
    await action("回滚到此版本", "rollback");
    const rolled = (await api("/tool-publications")).filter((r) => r.logicalId === row.logicalId);
    assert.equal(rolled.find((r) => r.id === row.id).state, "RUNNING");
    assert.equal(rolled.find((r) => r.id === second.id).state, "OFFLINE");
    checks.publishAndRollbackUseExactOwnedVersions = true;
    await page.screenshot({ path: out + "/published-owned-version.png" });
  }
  assert.deepEqual(errors, []);
  writeFileSync(
    out + "/acceptance.json",
    JSON.stringify({ status: "PASSED", checks, errors }, null, 2)
  );
  console.log(JSON.stringify({ status: "PASSED", checks }));
} catch (e) {
  await page?.screenshot({ path: out + "/failure.png" }).catch(() => {});
  writeFileSync(
    out + "/failure.json",
    JSON.stringify({ error: String(e), checks, errors }, null, 2)
  );
  throw e;
} finally {
  const cleanup = [];
  if (api)
    try {
      for (const r of await api("/tool-publications"))
        if (own.has(r.logicalId) && r.state === "RUNNING")
          await api("/tool-publications/" + r.id + "/offline", {});
    } catch (e) {
      cleanup.push(String(e));
    }
  writeFileSync(
    out + "/cleanup.json",
    JSON.stringify({ syntheticToolsOffline: cleanup.length === 0, errors: cleanup }, null, 2)
  );
  await browser.close();
  if (cleanup.length) throw Error("Acceptance cleanup failed");
}
