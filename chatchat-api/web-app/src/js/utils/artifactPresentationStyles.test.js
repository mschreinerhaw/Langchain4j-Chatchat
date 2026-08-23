import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const chatStyles = readFileSync(
  new URL("../../styles/pages/chat-assistant.css", import.meta.url),
  "utf8"
);
const artifactRenderer = readFileSync(
  new URL("../../components/EnterpriseUiArtifactRenderer.vue", import.meta.url),
  "utf8"
);
const artifactRegistry = readFileSync(
  new URL("../ui-artifact/registry.js", import.meta.url),
  "utf8"
);
const visualizationRenderer = readFileSync(
  new URL("../../components/VisualizationRenderer.vue", import.meta.url),
  "utf8"
);
const retrievalRulesView = readFileSync(
  new URL("../../views/RetrievalRulesView.vue", import.meta.url),
  "utf8"
);
const agentRuntimeStyles = readFileSync(
  new URL("../../styles/pages/agent-runtime.css", import.meta.url),
  "utf8"
);
const tasksView = readFileSync(
  new URL("../../views/TasksView.vue", import.meta.url),
  "utf8"
);
const skillHubStyles = readFileSync(
  new URL("../../styles/pages/skill-hub.css", import.meta.url),
  "utf8"
);
const agentWorkshopView = readFileSync(
  new URL("../../views/AgentWorkshopView.vue", import.meta.url),
  "utf8"
);
const mcpCenterView = readFileSync(
  new URL("../../views/McpCenterView.vue", import.meta.url),
  "utf8"
);
const agentWorkshopLogic = readFileSync(
  new URL("../views/AgentWorkshopView.js", import.meta.url),
  "utf8"
);

describe("dynamic report presentation contract", () => {
  it("keeps wide enhanced tables scrollable without crushing headers", () => {
    expect(chatStyles).toMatch(/\.query-result-table-card\s*\{[^}]*overflow:\s*auto/s);
    expect(chatStyles).toMatch(/\.query-result-table-card\s*\{[^}]*display:\s*block/s);
    expect(chatStyles).toMatch(/\.query-result-table-card table\s*\{[^}]*width:\s*max-content[^}]*min-width:\s*100%[^}]*table-layout:\s*auto/s);
    expect(chatStyles).toMatch(/\.query-result-table-card th\s*\{[^}]*white-space:\s*nowrap/s);
    expect(chatStyles).toMatch(/\.query-result-table-toolbar\s*\{[^}]*position:\s*sticky[^}]*width:\s*100%/s);
  });

  it("keeps evidence-chain controls visually subordinate to report content", () => {
    expect(artifactRenderer).toMatch(/\.artifact-notice[\s\S]*color:\s*#667085[\s\S]*font-size:\s*0\.84rem/);
    expect(artifactRenderer).toMatch(/\.artifact-evidence-content[\s\S]*font-size:\s*0\.82rem/);
    expect(artifactRegistry).toContain('h("details"');
    expect(artifactRegistry).toContain("collapseRecordCoverageEvidenceHtml(collapseToolExecutionEvidenceHtml(rendered))");
  });

  it("does not render retained evidence resources on answer pages", () => {
    expect(artifactRegistry).toContain("EvidenceList: () => null");
  });

  it("retains the table-chart event bridge into the existing analysis modal", () => {
    expect(artifactRenderer).toContain('defineEmits(["drill-down", "table-chart"])');
    expect(artifactRenderer).toContain('emit("table-chart"');
  });

  it("shows an explicit financial trend legend and professional report hierarchy", () => {
    expect(visualizationRenderer).toContain("visualization-trend-legend");
    expect(visualizationRenderer).toContain("上涨 / 正收益");
    expect(visualizationRenderer).toContain("下跌 / 负收益");
    expect(visualizationRenderer).toContain("持平 / 起点 / 零值");
    expect(chatStyles).toMatch(/\.visualization-trend-legend \.up i\s*\{\s*background:\s*var\(--trend-up-color, #e5484d\)/);
    expect(chatStyles).toMatch(/\.visualization-trend-legend \.down i\s*\{\s*background:\s*var\(--trend-down-color, #16a36a\)/);
    expect(artifactRenderer).toMatch(/\.artifact-markdown h2[\s\S]*border-left:\s*4px solid #2563eb/);
  });

  it("keeps keyword rule actions aligned with Agent scheduler buttons", () => {
    expect(retrievalRulesView).toContain('class="primary-button" title="创建规则"');
    expect(retrievalRulesView.match(/class="light-button"/g)).toHaveLength(2);
    expect(agentRuntimeStyles).toMatch(/\.keyword-rule-actions button\s*\{[^}]*min-height:\s*34px[^}]*border-radius:\s*7px[^}]*font-size:\s*13px/s);
    expect(agentRuntimeStyles).toMatch(/\.keyword-rule-actions \.light-button\s*\{[^}]*background:\s*#eef4ff/s);
  });

  it("keeps runtime refresh and load actions aligned with Agent scheduler buttons", () => {
    expect(tasksView.match(/class="light-button"/g)).toHaveLength(3);
    expect(skillHubStyles).toMatch(/\.runtime-view \.light-button\s*\{[^}]*min-height:\s*34px[^}]*border-radius:\s*7px[^}]*background:\s*#eef4ff[^}]*font-size:\s*13px/s);
    expect(skillHubStyles).toMatch(/\.runtime-view \.light-button:hover:not\(:disabled\)\s*\{[^}]*background:\s*#dceaff/s);
  });

  it("keeps task status badges inside their own runtime-table column", () => {
    expect(skillHubStyles).toMatch(/\.task-table button\s*\{[^}]*grid-template-columns:[^;}]*minmax\(112px, 140px\)/s);
    expect(skillHubStyles).toMatch(/\.task-table button > strong\s*\{[^}]*max-width:\s*100%[^}]*overflow:\s*hidden[^}]*text-overflow:\s*ellipsis/s);
  });

  it("keeps Agent management header actions aligned with Agent scheduler buttons", () => {
    expect(agentWorkshopView).toContain('class="primary-button" @click="openCreateDialog"');
    expect(skillHubStyles).toMatch(/\.agent-workshop-view \.agent-light-actions \.primary-button,[\s\S]*?\.agent-workshop-view \.agent-light-actions \.light-button\s*\{[^}]*min-height:\s*34px[^}]*border-radius:\s*7px[^}]*font-size:\s*13px/s);
    expect(skillHubStyles).toMatch(/\.agent-workshop-view \.agent-light-actions \.light-button\s*\{[^}]*background:\s*#eef4ff/s);
  });

  it("keeps Agent card actions aligned with Agent scheduler row actions", () => {
    expect(skillHubStyles).toMatch(/\.agent-workshop-view \.agent-card-actions button\s*\{[^}]*height:\s*34px[^}]*border-radius:\s*7px[^}]*font-size:\s*13px/s);
    expect(skillHubStyles).toMatch(/\.agent-workshop-view \.agent-card-actions \.secondary-button\s*\{[^}]*background:\s*#eef4ff/s);
    expect(skillHubStyles).toMatch(/\.agent-workshop-view \.agent-card-actions \.danger-button\s*\{[^}]*border:\s*1px solid #ffd1d8[^}]*background:\s*#fff3f4/s);
  });

  it("uses a product delete dialog instead of the browser confirmation", () => {
    expect(agentWorkshopView).toContain('id="agent-delete-title"');
    expect(agentWorkshopView).toContain('@click="confirmDeleteAgent"');
    expect(agentWorkshopLogic).not.toContain("window.confirm");
    expect(agentWorkshopLogic).toContain("deleteConfirmError");
    expect(skillHubStyles).toMatch(/\.agent-workshop-view \.agent-delete-submit\s*\{[^}]*background:\s*#dc2626/s);
  });

  it("keeps MCP management actions aligned with Agent scheduler buttons", () => {
    expect(mcpCenterView).toContain('class="light-button" :disabled="loading || syncing"');
    expect(mcpCenterView).toContain('class="primary-button" :disabled="syncing || loading"');
    expect(skillHubStyles).toMatch(/\.mcp-center-view \.mcp-actions button\s*\{[^}]*min-height:\s*34px[^}]*border-radius:\s*7px[^}]*font-size:\s*13px/s);
    expect(skillHubStyles).toMatch(/\.mcp-center-view \.mcp-actions \.light-button\s*\{[^}]*background:\s*#eef4ff/s);
  });
});
