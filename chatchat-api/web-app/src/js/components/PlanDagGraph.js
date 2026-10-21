import { defineComponent, nextTick, ref, watch } from "vue";
import { Handle, Position, VueFlow, useVueFlow } from "@vue-flow/core";
import { Background } from "@vue-flow/background";
import { Controls } from "@vue-flow/controls";
import { MiniMap } from "@vue-flow/minimap";
import {
  layoutPlanDag,
  PLAN_FLOW_NODE_HEIGHT,
  PLAN_FLOW_NODE_WIDTH,
} from "../utils/planDagLayout.js";
import "@vue-flow/core/dist/style.css";
import "@vue-flow/core/dist/theme-default.css";
import "@vue-flow/controls/dist/style.css";
import "@vue-flow/minimap/dist/style.css";

export default defineComponent({
  name: "PlanDagGraph",
  components: { VueFlow, Handle, Background, MiniMap, Controls },
  props: {
    title: { type: String, default: "交互式计划图" },
    exploration: { type: Boolean, default: false },
    nodes: { type: Array, default: () => [] },
    edges: { type: Array, default: () => [] },
    selectedNodeId: { type: String, default: "" },
    layoutKey: { type: String, default: "" },
    downloadName: { type: String, default: "plan-dag" },
  },
  emits: ["node-select", "export-json"],
  setup(componentProps, { expose: exposeComponent, emit: emitEvent }) {
    const props = componentProps;
    const emit = emitEvent;
    const flowNodes = ref([]);
    const flowEdges = ref([]);
    const { fitView, setCenter } = useVueFlow();
    const positionCache = new Map();
    let activeGraphKey = "";
    let activeLayoutKey = "";
    function kindStyle(kind) {
      const color =
        {
          goal: "#0f766e",
          intent: "#2563eb",
          tool: "#7c3aed",
          evidence: "#b45309",
          observation: "#0369a1",
          hypothesis: "#9333ea",
          finding: "#be185d",
          decision: "#047857",
          event: "#64748b",
        }[kind] || "#64748b";
      return { "--node-accent": color, "--node-border": color, borderLeftColor: color };
    }

    function graphKey() {
      const nodeIds = props.nodes.map((node, index) =>
        String(node?.id || `step-${node?.stepId || index + 1}`)
      );
      const edgeIds = props.edges.map(
        (edge) =>
          `${edge?.source || edge?.from || edge?.fromStepId}->${edge?.target || edge?.to || edge?.toStepId}`
      );
      return `${props.layoutKey}|${nodeIds.join(",")}|${edgeIds.join(",")}`;
    }

    function rememberCurrentPositions() {
      if (!activeGraphKey || !flowNodes.value.length) return;
      positionCache.set(
        activeGraphKey,
        Object.fromEntries(flowNodes.value.map((node) => [node.id, { ...node.position }]))
      );
      while (positionCache.size > 12) positionCache.delete(positionCache.keys().next().value);
    }

    function rebuild(nextGraphKey = graphKey(), discardCurrent = false) {
      const keepViewport =
        props.exploration &&
        activeLayoutKey === props.layoutKey &&
        flowNodes.value.length > 0 &&
        !discardCurrent;
      const livePositions = keepViewport
        ? Object.fromEntries(flowNodes.value.map((node) => [node.id, { ...node.position }]))
        : null;
      if (!discardCurrent) rememberCurrentPositions();
      else positionCache.delete(nextGraphKey);
      const layout = layoutPlanDag(props.nodes, props.edges);
      const savedPositions = livePositions || positionCache.get(nextGraphKey);
      if (savedPositions) {
        layout.nodes.forEach((node) => {
          if (savedPositions[node.id]) node.position = { ...savedPositions[node.id] };
        });
        const placed = layout.nodes.filter((node) => savedPositions[node.id]);
        for (const node of layout.nodes.filter((node) => !savedPositions[node.id])) {
          while (
            placed.some(
              (other) =>
                Math.abs(other.position.x - node.position.x) < PLAN_FLOW_NODE_WIDTH + 20 &&
                Math.abs(other.position.y - node.position.y) < PLAN_FLOW_NODE_HEIGHT + 20
            )
          ) {
            node.position.y += PLAN_FLOW_NODE_HEIGHT + 54;
          }
          placed.push(node);
        }
      }
      flowNodes.value = layout.nodes;
      flowEdges.value = layout.edges;
      activeGraphKey = nextGraphKey;
      activeLayoutKey = props.layoutKey;
      if (!keepViewport) nextTick(() => focusReadableView());
    }

    function fitGraph() {
      return fitView({ padding: 0.16, duration: 260, minZoom: 0.2, maxZoom: 1.15 });
    }

    function focusReadableView() {
      if (props.exploration && flowNodes.value.length) {
        const focus =
          flowNodes.value.find((node) => node.data.kind === "intent") || flowNodes.value[0];
        return setCenter(
          focus.position.x + PLAN_FLOW_NODE_WIDTH / 2,
          focus.position.y + PLAN_FLOW_NODE_HEIGHT / 2,
          { zoom: 0.85, duration: 0 }
        );
      }
      return fitView({ padding: 0.12, duration: 260, minZoom: 0.68, maxZoom: 1.05 });
    }

    function resetLayout() {
      rebuild(graphKey(), true);
    }

    function syncNodeData() {
      const dataById = Object.fromEntries(
        props.nodes.map((node, index) => [
          String(node?.id || `step-${node?.stepId || index + 1}`),
          node,
        ])
      );
      flowNodes.value.forEach((node) => {
        if (dataById[node.id]) node.data = { ...dataById[node.id], id: node.id };
      });
    }

    function statusKey(data = {}) {
      return String(
        data.statusText ||
          data.status ||
          (data.success === true ? "success" : data.success === false ? "failed" : "planned")
      ).toLowerCase();
    }

    function nodeTone(data) {
      const status = statusKey(data);
      if (/fail|error|kill|cancel/.test(status)) return "failed";
      if (/success|complete|done/.test(status)) return "success";
      if (/run|execut|started/.test(status)) return "running";
      if (/wait|confirm|pending/.test(status)) return "waiting";
      if (/final/.test(String(data.actionType || "").toLowerCase())) return "final";
      return "planned";
    }

    function statusLabel(data) {
      if (props.exploration)
        return (
          {
            OBSERVED: "已记录",
            PLANNED: "模型计划",
            STARTED: "运行中",
            COMPLETED: "已执行",
            SUCCESS: "成功",
            FAILED: "失败",
            EXECUTION_OUTCOME_UNKNOWN: "结果未知",
            RESULT_PERSISTENCE_PENDING: "待落盘",
          }[String(data.statusText || data.status).toUpperCase()] ||
          String(data.statusText || data.status || "已记录")
        );
      return (
        data.statusLabel ||
        { success: "成功", failed: "失败", running: "运行中", waiting: "等待", planned: "计划" }[
          nodeTone(data)
        ] ||
        "计划"
      );
    }

    function miniMapColor(node) {
      return node?.data ? "#2f6fa9" : "#8aacc9";
    }

    function statusColor(data) {
      return (
        {
          success: "#278653",
          failed: "#c2414b",
          running: "#1976c9",
          waiting: "#b7791f",
          final: "#6554a5",
        }[nodeTone(data)] || "#507da5"
      );
    }

    function formatDuration(value) {
      const ms = Number(value || 0);
      if (ms < 1000) return `${ms}ms`;
      return `${(ms / 1000).toFixed(ms < 10000 ? 1 : 0)}s`;
    }

    function handleNodeClick({ node }) {
      emit("node-select", node.id);
    }

    function selectNode(nodeId, { center = true } = {}) {
      const selectedId = String(nodeId || "");
      const target = flowNodes.value.find((node) => node.id === selectedId);
      flowNodes.value = flowNodes.value.map((node) => ({
        ...node,
        selected: Boolean(selectedId) && node.id === selectedId,
      }));
      if (!target || !center) return Promise.resolve(false);
      return setCenter(
        target.position.x + PLAN_FLOW_NODE_WIDTH / 2,
        target.position.y + PLAN_FLOW_NODE_HEIGHT / 2,
        { zoom: 1, duration: 320 }
      );
    }

    function escapeXml(value) {
      return String(value ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;");
    }

    function downloadSvg() {
      if (!flowNodes.value.length) return;
      const minX = Math.min(...flowNodes.value.map((node) => node.position.x)) - 40;
      const minY = Math.min(...flowNodes.value.map((node) => node.position.y)) - 40;
      const maxX =
        Math.max(...flowNodes.value.map((node) => node.position.x + PLAN_FLOW_NODE_WIDTH)) + 40;
      const maxY =
        Math.max(...flowNodes.value.map((node) => node.position.y + PLAN_FLOW_NODE_HEIGHT)) + 40;
      const byId = Object.fromEntries(flowNodes.value.map((node) => [node.id, node]));
      const edgeSvg = flowEdges.value
        .map((edge) => {
          const source = byId[edge.source];
          const target = byId[edge.target];
          if (!source || !target) return "";
          const sx = source.position.x + PLAN_FLOW_NODE_WIDTH;
          const sy = source.position.y + PLAN_FLOW_NODE_HEIGHT / 2;
          const tx = target.position.x;
          const ty = target.position.y + PLAN_FLOW_NODE_HEIGHT / 2;
          const curve = Math.max(55, Math.abs(tx - sx) / 2);
          return `<path d="M ${sx} ${sy} C ${sx + curve} ${sy}, ${tx - curve} ${ty}, ${tx} ${ty}" marker-end="url(#arrow)"/>`;
        })
        .join("");
      const nodeSvg = flowNodes.value
        .map((node) => {
          const data = node.data || {};
          return `<g transform="translate(${node.position.x},${node.position.y})"><rect width="${PLAN_FLOW_NODE_WIDTH}" height="${PLAN_FLOW_NODE_HEIGHT}" rx="8" fill="#edf6fd" stroke="#2f6fa9" stroke-width="2"/><rect width="5" height="${PLAN_FLOW_NODE_HEIGHT}" rx="3" fill="#1f5f99"/><circle cx="18" cy="21" r="5" fill="${statusColor(data)}"/><text x="32" y="25" class="status">${escapeXml(statusLabel(data))}</text><text x="18" y="56" class="title">${escapeXml(data.fullLabelText || data.labelText || data.label || node.id)}</text><text x="18" y="84" class="meta">${escapeXml(data.toolName || data.actionText || "运行节点")}</text><text x="18" y="108" class="step">#${escapeXml(data.stepId || node.id)}</text></g>`;
        })
        .join("");
      const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${minX} ${minY} ${maxX - minX} ${maxY - minY}" width="${maxX - minX}" height="${maxY - minY}"><defs><marker id="arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto"><path d="M0,0 L0,6 L8,3 z" fill="#64748b"/></marker></defs><style>path{fill:none;stroke:#64748b;stroke-width:2}.status{font:700 12px sans-serif;fill:#475467}.title{font:700 14px sans-serif;fill:#101828}.meta{font:12px sans-serif;fill:#475467}.step{font:11px monospace;fill:#667085}</style>${edgeSvg}${nodeSvg}</svg>`;
      const url = URL.createObjectURL(new Blob([svg], { type: "image/svg+xml" }));
      const link = document.createElement("a");
      link.href = url;
      link.download = `${props.downloadName}.svg`;
      link.click();
      URL.revokeObjectURL(url);
    }

    watch(graphKey, (key) => rebuild(key), { immediate: true });
    watch(() => props.nodes, syncNodeData, { deep: true });
    watch(
      () => props.selectedNodeId,
      (nodeId) => nextTick(() => selectNode(nodeId, { center: false })),
      { immediate: true }
    );
    exposeComponent({ fitGraph, resetLayout, downloadSvg, selectNode });
    return {
      Position,
      emit,
      flowNodes,
      flowEdges,
      kindStyle,
      fitGraph,
      resetLayout,
      nodeTone,
      statusLabel,
      miniMapColor,
      formatDuration,
      handleNodeClick,
      downloadSvg,
    };
  },
});
