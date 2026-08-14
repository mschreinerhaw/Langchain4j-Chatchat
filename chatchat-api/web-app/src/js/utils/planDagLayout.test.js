import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { layoutPlanDag, PLAN_FLOW_NODE_HEIGHT } from "./planDagLayout.js";

describe("interactive plan DAG layout contract", () => {
  const nodes = [
    { id: "search", label: "检索" },
    { id: "query", label: "查询" },
    { id: "review", label: "审核" },
    { id: "answer", label: "回答" }
  ];
  const edges = [
    { source: "search", target: "query", label: "next" },
    { source: "search", target: "review", label: "review" },
    { source: "query", target: "answer", label: "result" },
    { source: "review", target: "answer", label: "approved" }
  ];

  it("uses a left-to-right directed layout and retains every valid edge", () => {
    const layout = layoutPlanDag(nodes, edges);
    const byId = Object.fromEntries(layout.nodes.map((node) => [node.id, node]));

    expect(layout.nodes).toHaveLength(4);
    expect(layout.edges).toHaveLength(4);
    expect(byId.query.position.x).toBeGreaterThan(byId.search.position.x);
    expect(byId.answer.position.x).toBeGreaterThan(byId.query.position.x);
    expect(Math.abs(byId.query.position.y - byId.review.position.y)).toBeGreaterThanOrEqual(PLAN_FLOW_NODE_HEIGHT);
    expect(layout.edges[0]).toMatchObject({ type: "smoothstep", source: "search", target: "query", label: "next" });
  });

  it("ignores dangling edges without losing disconnected nodes", () => {
    const layout = layoutPlanDag([...nodes, { id: "orphan" }], [...edges, { source: "missing", target: "answer" }]);
    expect(layout.nodes.map((node) => node.id)).toContain("orphan");
    expect(layout.edges).toHaveLength(4);
  });

  it("keeps the open-source graph interaction controls and drag contract enabled", () => {
    const component = readFileSync(new URL("../../components/PlanDagGraph.vue", import.meta.url), "utf8");
    expect(component).toContain(':nodes-draggable="true"');
    expect(component).toContain("<MiniMap pannable zoomable");
    expect(component).toContain("<Controls position=\"bottom-left\"");
    expect(component).toContain("拖动节点整理布局");
    expect(component).toContain("resetLayout");
    expect(component).toContain("positionCache");
    expect(component).toContain("watch(graphKey");
    expect(component).toContain(":deep(.vue-flow__controls-zoomout svg)");
    expect(component).toContain("transform: translateY(-1px)");
    expect(component).toContain("--node-accent: #7b8fa4");
    expect(component).toContain("border-left: 4px solid var(--node-accent)");
    expect(component).toContain('success: "#4f9d69"');
    expect(component).toContain("selectedNodeId");
    expect(component).toContain("setCenter(");
    expect(component).toContain("defineExpose({ fitGraph, resetLayout, downloadSvg, selectNode })");
  });
});
