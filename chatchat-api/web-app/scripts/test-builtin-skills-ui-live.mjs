/** Real UI save/unbind/rebind and marketplace display, using only an isolated acceptance Agent. */
import { chromium } from "playwright-core";
import fs from "node:fs";
import path from "node:path";
import assert from "node:assert/strict";
const {
  E2E_ORIGIN: origin,
  E2E_AUTH_FILE: authFile,
  E2E_OUTPUT: out,
  E2E_AGENT_ID: id,
  E2E_AGENT_NAME: name,
} = process.env;
assert.ok(origin && authFile && out && id && name);
const auth = JSON.parse(fs.readFileSync(authFile));
fs.mkdirSync(out, { recursive: true });
const save = (n, v) => fs.writeFileSync(path.join(out, n + ".json"), JSON.stringify(v, null, 2));
async function api(route) {
  const r = await fetch(origin + "/api/v1" + route, {
    headers: { Authorization: "Bearer " + auth.token },
  });
  const j = await r.json();
  assert.ok(r.ok && j.code === 200, route);
  return j.data;
}
const before = await api("/agents/workshop/" + id);
assert.equal(before.boundDomainSkillIds.length, 2);
assert.ok(!Object.hasOwn(before.workflowConfig, "disabledBuiltinSkillIds"));
save("agent-after-upgrade", before);
const browser = await chromium.launch({
  executablePath:
    process.env.CHROME_PATH || "C:/Program Files/Google/Chrome/Application/chrome.exe",
  headless: true,
});
try {
  const context = await browser.newContext({ viewport: { width: 1680, height: 1100 } });
  await context.addInitScript(
    (a) => localStorage.setItem("chatchat.auth.session", JSON.stringify(a)),
    auth
  );
  const page = await context.newPage();
  await page.goto(origin + "/index.html#/w/zhice/agents");
  await page.getByPlaceholder("名称、场景、标签或工具").fill(name);
  const card = page
    .locator(".agent-card")
    .filter({ has: page.getByRole("heading", { name, exact: true }) });
  const open = async () => {
    await card.getByRole("button", { name: "设置", exact: true }).click();
    await page.locator(".agent-dialog").waitFor();
  };
  await open();
  let dialog = page.locator(".agent-dialog");
  assert.ok(!(await dialog.innerText()).includes("禁用内置"));
  assert.ok(!(await dialog.innerText()).includes("python-data-analysis"));
  assert.ok(!(await dialog.innerText()).includes("python-code-development"));
  await dialog.getByRole("button", { name: /调整 Skills/ }).scrollIntoViewIfNeeded();
  await page.screenshot({ path: path.join(out, "agent-settings.png") });
  await dialog.getByRole("button", { name: /调整 Skills/ }).click();
  let picker = page
    .locator(".agent-resource-dialog")
    .filter({ has: page.getByRole("heading", { name: "选择 Skills", exact: true }) });
  const selected = picker.locator("label.agent-document-check.active").first(),
    removedName = await selected.getAttribute("title");
  await selected.locator("input").uncheck();
  await picker.getByRole("button", { name: /完成（已选 1 个）/ }).click();
  const put1 = page.waitForResponse(
    (r) => r.request().method() === "PUT" && r.url().endsWith("/agents/workshop/" + id)
  );
  await dialog.getByRole("button", { name: "保存", exact: true }).click();
  assert.equal((await put1).status(), 200);
  await dialog.waitFor({ state: "hidden" });
  const unbound = await api("/agents/workshop/" + id);
  assert.equal(unbound.boundDomainSkillIds.length, 1);
  save("business-skill-unbound", unbound);
  await open();
  dialog = page.locator(".agent-dialog");
  await dialog.getByRole("button", { name: /调整 Skills/ }).click();
  picker = page
    .locator(".agent-resource-dialog")
    .filter({ has: page.getByRole("heading", { name: "选择 Skills", exact: true }) });
  assert.ok(!(await picker.innerText()).includes("python-data-analysis"));
  await picker.getByTitle(removedName, { exact: true }).locator("input").check();
  await picker.getByRole("button", { name: /完成（已选 2 个）/ }).click();
  const put2 = page.waitForResponse(
    (r) => r.request().method() === "PUT" && r.url().endsWith("/agents/workshop/" + id)
  );
  await dialog.getByRole("button", { name: "保存", exact: true }).click();
  assert.equal((await put2).status(), 200);
  await dialog.waitFor({ state: "hidden" });
  const rebound = await api("/agents/workshop/" + id);
  assert.deepEqual([...rebound.boundDomainSkillIds].sort(), [...before.boundDomainSkillIds].sort());
  assert.ok(!Object.hasOwn(rebound.workflowConfig, "disabledBuiltinSkillIds"));
  save("business-skill-rebound", rebound);
  await page.goto(origin + "/index.html#/w/zhice/dataScienceSkills");
  await page.locator(".domain-skills-page").waitFor({ timeout: 30000 });
  const section = page.locator('section[aria-label="系统内置技能"]');
  await section.getByText("Python 数据分析", { exact: true }).waitFor();
  const titles = await section.locator(".domain-skill-item-title strong").allTextContents();
  assert.equal(titles.length, 6);
  assert.ok(titles.includes("Python 程序开发"));
  assert.ok(titles.includes("Azure 数据探索与查询"));
  assert.ok(!(await section.innerText()).includes("upstream-"));
  await page.screenshot({ path: path.join(out, "marketplace-chinese.png") });
  await section
    .locator("article")
    .filter({ has: page.getByText("Python 数据分析", { exact: true }) })
    .getByRole("button", { name: "查看详情" })
    .click();
  let detail = page.getByRole("dialog");
  await detail.getByText("builtin:python-data-analysis", { exact: true }).waitFor();
  await page.screenshot({ path: path.join(out, "builtin-detail.png") });
  await detail.getByRole("button", { name: "关闭", exact: true }).first().click();
  await section
    .locator("article")
    .filter({ has: page.getByText("Azure 数据探索与查询", { exact: true }) })
    .getByRole("button", { name: "查看详情" })
    .click();
  detail = page.getByRole("dialog");
  await detail
    .getByText("3f5b461b2314e01c400564051e92469114c5b68f", { exact: true })
    .first()
    .waitFor();
  await detail.getByText("MIT", { exact: true }).waitFor();
  await page.screenshot({ path: path.join(out, "upstream-detail.png") });
  save("ui-verdict", {
    status: "PASS",
    builtinOptionsAbsent: true,
    selectedBusinessCount: 2,
    businessUnbindRebind: true,
    chineseTitles: titles,
    originalIdsAndProvenanceInDetails: true,
    agentId: id,
  });
  console.log(JSON.stringify({ status: "PASS", agentId: id, chineseTitles: titles }));
} finally {
  await browser.close();
}
