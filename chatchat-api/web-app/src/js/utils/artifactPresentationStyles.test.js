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
});
