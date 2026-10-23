import { chromium } from "../chatchat-api/web-app/node_modules/playwright-core/index.mjs";
import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import assert from "node:assert/strict";
const upstream = "http://192.168.195.221:18100";
const base = process.env.CHATCHAT_STAGE_URL || upstream;
const readonly = process.env.CHATCHAT_READONLY === "1";
const out = process.env.CHATCHAT_BROWSER_OUTPUT || "target/offline-tool-delete-live";
mkdirSync(out, { recursive: true });
const browser = await chromium.launch({
  executablePath: "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  headless: true,
  args: ["--no-proxy-server"],
});
const own = new Set(),
  checks = {},
  errors = [];
let api, page;
try {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    locale: "zh-CN",
  });
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
    const v = await r.json();
    assert.equal(r.status(), expected, path + ": " + v.message);
    if (expected === 200) assert.equal(v.code, 200, path);
    return v.data;
  };
  const before = await api("/tool-publications");
  const family = async (logical) =>
    (await api("/tool-publications")).filter((r) => r.logicalId === logical);
  const confirmation = (rows) => ({
    expectedVersions: Object.fromEntries(rows.map((r) => [r.id, r.version])),
  });
  page = await context.newPage();
  page.setDefaultTimeout(60000);
  page.on("pageerror", (e) => errors.push(e.message));
  if (base !== upstream)
    await page.route("**/api/**", async (route) => {
      const u = new URL(route.request().url());
      await route.fulfill({
        response: await route.fetch({ url: upstream + u.pathname + u.search }),
      });
    });
  const ready = () => page.locator('.tool-publications[aria-busy="false"]').waitFor();
  async function showOffline(title) {
    await page.goto(base + "/#/w/mcp-publication/toolPublications");
    await page.locator(".publication-detail").waitFor();
    await ready();
    await page.getByLabel("版本状态", { exact: true }).selectOption("OFFLINE");
    await page.getByPlaceholder("搜索名称或说明").fill(title);
    await page.locator(".permission-master-item").filter({ hasText: title }).click();
    await ready();
  }
  async function action(name, suffix) {
    const response = page.waitForResponse(
      (r) =>
        r
          .url()
          .split("?")[0]
          .endsWith("/" + suffix) && r.request().method() === "POST"
    );
    page.once("dialog", (d) => d.accept());
    await page.getByRole("button", { name, exact: true }).click();
    const r = await response;
    assert.equal(r.status(), 200, await r.text());
    await ready();
  }
  if (readonly) {
    const stopped = before.find(
      (row) =>
        row.state === "OFFLINE" &&
        !before.some((r) => r.logicalId === row.logicalId && r.state === "RUNNING")
    );
    assert.ok(stopped);
    await showOffline(stopped.title);
    const button = page.getByRole("button", { name: "删除工具", exact: true });
    assert.equal(await button.isEnabled(), true);
    let prompt = "";
    page.once("dialog", async (d) => {
      prompt = d.message();
      await d.dismiss();
    });
    await button.click();
    await ready();
    assert.ok(prompt.includes("全部") && prompt.includes("不能恢复"));
    assert.deepEqual(await api("/tool-publications"), before);
    await page.screenshot({ path: out + "/delete-entry.png" });
    checks.cancelDeletionPreservesExistingTools = true;
  } else {
    const seed = before.find((r) => r.state === "RUNNING" && r.kind === "CONTROLLED_TEMPLATE");
    assert.ok(seed);
    const stamp = Date.now().toString(36),
      title = "下线删除验收 " + stamp,
      domain = "offline_delete_" + stamp;
    const definition = { ...JSON.parse(seed.definitionJson), domainCode: domain, enabled: false };
    delete definition.chineseAlias;
    const payload = {
      title,
      description: "临时删除闭环验收工具",
      kind: "CONTROLLED_TEMPLATE",
      definition,
      inputSchema: { type: "object", properties: { limit: { type: "integer", minimum: 1 } } },
      outputSchema: { type: "object" },
    };
    async function publish(row) {
      await api("/tool-publications/" + row.id + "/validate", {});
      await api("/tool-publications/" + row.id + "/test", { input: { limit: 1 } });
      return api("/tool-publications/" + row.id + "/publish", {});
    }
    const first = await api("/tool-publications", payload);
    own.add(first.logicalId);
    await publish(first);
    const second = await api("/tool-publications", { ...payload, logicalId: first.logicalId });
    await publish(second);
    await showOffline(title);
    await page.getByLabel("当前版本", { exact: true }).selectOption(first.id);
    assert.equal(
      await page.getByRole("button", { name: "删除工具", exact: true }).isDisabled(),
      true
    );
    await api(
      "/tool-publications/" + first.id,
      confirmation(await family(first.logicalId)),
      "DELETE",
      409
    );
    checks.runningSiblingBlocksDeleteInUiAndApi = true;
    await page.screenshot({ path: out + "/running-version-blocks-delete.png" });
    await page.getByLabel("当前版本", { exact: true }).selectOption(second.id);
    await action("下线工具", "offline");
    assert.equal(
      await page.getByRole("button", { name: "删除工具", exact: true }).isEnabled(),
      true
    );
    const pending = await api("/tool-publications", { ...payload, logicalId: first.logicalId });
    assert.equal(pending.releaseNumber, 3);
    page.once("dialog", (d) => d.accept());
    let response = page.waitForResponse(
      (r) =>
        r.url().endsWith("/tool-publications/" + second.id) && r.request().method() === "DELETE"
    );
    await page.getByRole("button", { name: "删除工具", exact: true }).click();
    assert.equal((await response).status(), 409);
    await ready();
    assert.ok((await page.getByRole("alert").innerText()).includes("版本已变化"));
    checks.concurrentNewVersionPreventsStaleDeletion = true;
    await page
      .locator(".permission-page-heading")
      .getByRole("button", { name: "刷新", exact: true })
      .click();
    await ready();
    assert.equal(await page.getByLabel("当前版本", { exact: true }).locator("option").count(), 3);
    let prompt = "";
    let deleteRequests = 0;
    page.on("request", (r) => {
      if (r.method() === "DELETE") deleteRequests++;
    });
    page.once("dialog", async (d) => {
      prompt = d.message();
      await d.dismiss();
    });
    await page.getByRole("button", { name: "删除工具", exact: true }).click();
    assert.equal(deleteRequests, 0);
    assert.ok(prompt.includes("全部 3 个版本"));
    assert.equal((await family(first.logicalId)).length, 3);
    checks.cancelConfirmationDoesNotDelete = true;
    await page.screenshot({ path: out + "/offline-delete-entry.png" });
    response = page.waitForResponse(
      (r) =>
        r.url().endsWith("/tool-publications/" + second.id) && r.request().method() === "DELETE"
    );
    page.once("dialog", (d) => d.accept());
    await page.getByRole("button", { name: "删除工具", exact: true }).click();
    const deleted = await response;
    assert.equal(deleted.status(), 200, await deleted.text());
    assert.equal((await deleted.json()).data.deletedVersions, 3);
    await ready();
    assert.equal((await family(first.logicalId)).length, 0);
    checks.confirmedDeletionRemovesAllOwnedVersionsIncludingDrafts = true;
    await api("/tool-publications/" + first.id + "/rollback", {}, "POST", 404);
    await api("/tool-publications/" + second.id, confirmation([]), "DELETE", 404);
    await api("/tool-publications", { ...payload, logicalId: first.logicalId }, "POST", 404);
    checks.deletedVersionIdsCannotBeReplayed = true;
    const runtime = await api("/mcp/runtime/tools?toolNames=" + domain + "_template_query");
    assert.equal(
      runtime.some((t) => t.remoteToolName === domain + "_template_query"),
      false
    );
    checks.deletedToolIsNotAvailableToRuntime = true;
    const recreated = await api("/tool-publications", payload);
    own.add(recreated.logicalId);
    assert.notEqual(recreated.logicalId, first.logicalId);
    assert.equal(recreated.releaseNumber, 1);
    await publish(recreated);
    await api("/tool-publications/" + recreated.id + "/offline", {});
    await api(
      "/tool-publications/" + recreated.id,
      confirmation(await family(recreated.logicalId)),
      "DELETE"
    );
    checks.stoppedBindingRemovedAndNameCanBeReused = true;
    const after = await api("/tool-publications");
    assert.deepEqual(after, before);
    checks.otherToolDefinitionsAndVersionStatesUnchanged = true;
    writeFileSync(
      out + "/fixture-ids.json",
      JSON.stringify(
        { logicalIds: [...own], first: first.id, second: second.id, pending: pending.id },
        null,
        2
      )
    );
    await page.screenshot({ path: out + "/deleted-tool-absent.png" });
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
  if (api && !readonly)
    try {
      let rows = await api("/tool-publications");
      for (const r of rows)
        if (own.has(r.logicalId) && r.state === "RUNNING")
          await api("/tool-publications/" + r.id + "/offline", {});
      rows = await api("/tool-publications");
      for (const logical of own) {
        const history = rows.filter((r) => r.logicalId === logical);
        const offline = history.find((r) => r.state === "OFFLINE");
        if (offline)
          await api(
            "/tool-publications/" + offline.id,
            { expectedVersions: Object.fromEntries(history.map((r) => [r.id, r.version])) },
            "DELETE"
          );
      }
      const leftovers = (await api("/tool-publications")).filter((r) => own.has(r.logicalId));
      if (leftovers.length) cleanup.push("Remaining temporary versions: " + leftovers.length);
    } catch (e) {
      cleanup.push(String(e));
    }
  writeFileSync(
    out + "/cleanup.json",
    JSON.stringify({ temporaryToolsRemoved: cleanup.length === 0, errors: cleanup }, null, 2)
  );
  await browser.close();
  if (cleanup.length) throw Error("Temporary tool cleanup failed");
}
