import { chromium } from "../chatchat-api/web-app/node_modules/playwright-core/index.mjs";
import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import assert from "node:assert/strict";
const base = process.env.CHATCHAT_STAGE_URL || "http://192.168.195.221:18100";
const out = "docs/system-search-tools-20261024";
mkdirSync(out, { recursive: true });
const browser = await chromium.launch({
  executablePath: "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  headless: true,
  args: ["--no-proxy-server"],
});
const checks = {},
  errors = [];
let api, ownAgent, beforeAgents;
try {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    locale: "zh-CN",
  });
  const login = await (
    await context.request.post(base + "/api/v1/enterprise/auth/login", {
      data: JSON.parse(readFileSync("target/workspace-stage-auth-private.json", "utf8")),
    })
  ).json();
  assert.ok(login.data?.token);
  await context.addInitScript(
    (session) => localStorage.setItem("chatchat.auth.session", JSON.stringify(session)),
    login.data
  );
  api = async (path, body, method = body ? "POST" : "GET") => {
    const response = await context.request.fetch(base + "/api/v1" + path, {
      method,
      headers: { Authorization: "Bearer " + login.data.token, "X-Workspace-Id": "zhice" },
      ...(body ? { data: body } : {}),
    });
    const result = await response.json();
    assert.equal(result.code, 200, path + ": " + JSON.stringify(result));
    return result.data;
  };
  const names = ["mcp_chatchat_mcp_server_web_search", "mcp_chatchat_mcp_server_document_search"];
  let directory = await api("/mcp/tool-cards?pageSize=100&sourceType=mcp");
  for (
    let attempt = 0;
    attempt < 12 &&
    !names.every((name) => directory.tools.some((tool) => tool.localToolName === name));
    attempt++
  ) {
    await new Promise((resolve) => setTimeout(resolve, 2500));
    directory = await api("/mcp/tool-cards?pageSize=100&sourceType=mcp");
  }
  const system = directory.tools.filter((tool) => tool.systemTool);
  assert.deepEqual(system.map((tool) => tool.localToolName).sort(), [...names].sort());
  assert.ok(system.every((tool) => tool.agentCompatible && tool.functionalCategory === "系统工具"));
  assert.ok(directory.tools.some((tool) => !tool.systemTool));
  checks.exactlyTwoSystemSearchToolsAndBusinessToolsPreserved = true;
  const workshop = await api("/agents/workshop?page=1&pageSize=100");
  beforeAgents = workshop.agents.map((agent) => agent.id).sort();
  assert.ok(
    names.every((name) =>
      workshop.registeredMcpTools.some(
        (tool) => tool.localToolName === name && tool.applicability.systemTool
      )
    )
  );
  checks.agentOptionsIncludeBothSystemTools = true;
  const id = "system_search_acceptance_" + Date.now().toString(36);
  const created = await api("/agents/workshop", {
    id,
    name: "系统搜索验收 " + id,
    description: "临时验收，仅检查系统搜索工具绑定。",
    defaultMode: "agent_chat",
    modelName: workshop.defaultModelName || workshop.models[0].value,
    systemPrompt: "使用所选工具回答问题。",
    boundMcpToolNames: names,
    boundMcpServiceIds: [],
    preferredToolPrefixes: [],
    boundDocumentIds: [],
    boundDomainSkillIds: [],
    toolConfigs: names.map((toolName) => ({ toolName, enabled: true })),
    marketStatus: "draft",
    defaultAgent: false,
  });
  ownAgent = created.id;
  writeFileSync(
    out + "/fixture.json",
    JSON.stringify({ agentId: ownAgent, status: "CREATED" }, null, 2)
  );
  assert.equal(ownAgent, id);
  const saved = await api("/agents/workshop/" + ownAgent);
  assert.ok(
    names.every(
      (name) => saved.boundMcpToolNames.includes(name) && saved.resolvedToolNames.includes(name)
    )
  );
  checks.agentCanSaveAndResolveBothTools = true;
  const page = await context.newPage();
  page.setDefaultTimeout(45000);
  page.on("pageerror", (error) => errors.push(error.message));
  let templateRequests = 0;
  page.on("request", (request) => {
    if (request.url().includes("/mcp/published-tool-templates?")) templateRequests++;
  });
  await page.goto(base + "/#/w/zhice/mcp");
  await page.getByRole("combobox", { name: "业务分类", exact: true }).selectOption("系统工具");
  await page.waitForFunction(() => document.querySelectorAll(".mcp-tool-card").length === 2);
  for (const title of ["联网搜索", "文档检索"]) {
    const card = page
      .locator(".mcp-tool-card")
      .filter({ has: page.getByRole("heading", { name: title, exact: true }) });
    await card.getByText("系统内置", { exact: true }).waitFor();
    assert.ok((await card.textContent()).includes("Agent 可用"));
    await card.getByRole("button", { name: "查看用途", exact: true }).click();
    const drawer = page.getByRole("dialog", { name: "工具详情与授权模板" });
    await drawer
      .getByText("系统基础工具，可在 Agent 的工具配置中选择使用。", { exact: true })
      .waitFor();
    assert.equal(await drawer.locator(".mcp-authorized-templates").count(), 0);
    assert.equal(await drawer.getByRole("region", { name: "调用信息" }).count(), 0);
    await page.screenshot({
      path: out + "/" + (title === "联网搜索" ? "web-search" : "document-search") + ".png",
    });
    await drawer.getByRole("button", { name: "关闭", exact: true }).click();
  }
  assert.equal(templateRequests, 0);
  checks.systemCardsAndPurposeAvoidTemplateRequests = true;
  await page.goto(base + "/#/w/zhice/agents");
  await page.getByRole("button", { name: "新增Agent", exact: true }).click();
  await page
    .getByText("运行模式", { exact: true })
    .locator("..")
    .locator("select")
    .selectOption("agent_chat");
  await page.getByRole("button", { name: "选择工具", exact: true }).click();
  const picker = page.getByRole("dialog", { name: "选择工具", exact: true });
  await picker.waitFor();
  await picker
    .getByText("联网搜索", { exact: true })
    .locator("..")
    .locator("..")
    .getByRole("checkbox")
    .check();
  await picker
    .getByText("文档检索", { exact: true })
    .locator("..")
    .locator("..")
    .getByRole("checkbox")
    .check();
  await page.screenshot({ path: out + "/agent-tool-picker.png" });
  checks.userCanSelectBothInAgentPicker = true;
  assert.deepEqual(errors, []);
  checks.noBrowserErrors = true;
} finally {
  try {
    if (ownAgent) {
      assert.ok(ownAgent.startsWith("system_search_acceptance_"));
      await api("/agents/workshop/" + ownAgent, undefined, "DELETE");
      writeFileSync(
        out + "/fixture.json",
        JSON.stringify({ agentId: ownAgent, status: "DELETED" }, null, 2)
      );
    }
    if (api && beforeAgents) {
      const after = await api("/agents/workshop?page=1&pageSize=100");
      assert.deepEqual(after.agents.map((agent) => agent.id).sort(), beforeAgents);
      checks.temporaryAgentCleanedAndExistingAgentsPreserved = true;
    }
  } finally {
    writeFileSync(out + "/acceptance.json", JSON.stringify({ checks, errors }, null, 2));
    await browser.close();
  }
}
