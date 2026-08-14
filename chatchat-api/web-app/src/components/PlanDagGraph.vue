<template>
  <section class="interactive-plan-dag" aria-label="可交互解读计划图">
    <div class="plan-flow-toolbar">
      <div>
        <strong>交互式计划图</strong>
        <span>拖动节点整理布局 · 滚轮缩放 · 拖动空白处平移</span>
      </div>
      <div class="plan-flow-actions">
        <button type="button" @click="resetLayout">自动布局</button>
        <button type="button" @click="fitGraph">适配视图</button>
        <button type="button" @click="downloadSvg">导出 SVG</button>
        <button type="button" @click="$emit('export-json')">导出 JSON</button>
      </div>
    </div>

    <VueFlow
      v-model:nodes="flowNodes"
      v-model:edges="flowEdges"
      class="plan-flow"
      :min-zoom="0.2"
      :max-zoom="2.5"
      :default-viewport="{ zoom: 0.85 }"
      :nodes-draggable="true"
      :nodes-connectable="false"
      :edges-updatable="false"
      :fit-view-on-init="true"
      :zoom-on-double-click="false"
      @node-click="handleNodeClick"
    >
      <template #node-plan="{ data, selected }">
        <article class="plan-flow-node" :class="[nodeTone(data), { selected }]">
          <Handle type="target" :position="Position.Left" />
          <header>
            <span class="plan-flow-status-dot"></span>
            <small>{{ statusLabel(data) }}</small>
            <code>#{{ data.stepId || data.id }}</code>
          </header>
          <strong :title="data.fullLabelText || data.labelText || data.label">{{ data.fullLabelText || data.labelText || data.label || data.id }}</strong>
          <p :title="data.toolName || data.actionText">{{ data.toolName || data.actionText || "运行节点" }}</p>
          <footer v-if="data.durationMs || data.kind">
            <span>{{ data.kind || "plan" }}</span>
            <span v-if="data.durationMs">{{ formatDuration(data.durationMs) }}</span>
          </footer>
          <Handle type="source" :position="Position.Right" />
        </article>
      </template>
      <Background pattern-color="#d9e3f0" :gap="22" :size="1.2" />
      <MiniMap pannable zoomable :node-color="miniMapColor" />
      <Controls position="bottom-left" />
    </VueFlow>
  </section>
</template>

<script setup>
import { nextTick, ref, watch } from "vue";
import { Handle, Position, VueFlow, useVueFlow } from "@vue-flow/core";
import { Background } from "@vue-flow/background";
import { Controls } from "@vue-flow/controls";
import { MiniMap } from "@vue-flow/minimap";
import { layoutPlanDag, PLAN_FLOW_NODE_HEIGHT, PLAN_FLOW_NODE_WIDTH } from "../js/utils/planDagLayout.js";
import "@vue-flow/core/dist/style.css";
import "@vue-flow/core/dist/theme-default.css";
import "@vue-flow/controls/dist/style.css";
import "@vue-flow/minimap/dist/style.css";

const props = defineProps({
  nodes: { type: Array, default: () => [] },
  edges: { type: Array, default: () => [] },
  selectedNodeId: { type: String, default: "" },
  layoutKey: { type: String, default: "" },
  downloadName: { type: String, default: "plan-dag" }
});
const emit = defineEmits(["node-select", "export-json"]);
const flowNodes = ref([]);
const flowEdges = ref([]);
const { fitView, setCenter } = useVueFlow();
const positionCache = new Map();
let activeGraphKey = "";

function graphKey() {
  const nodeIds = props.nodes.map((node, index) => String(node?.id || `step-${node?.stepId || index + 1}`));
  const edgeIds = props.edges.map((edge) => `${edge?.source || edge?.from || edge?.fromStepId}->${edge?.target || edge?.to || edge?.toStepId}`);
  return `${props.layoutKey}|${nodeIds.join(",")}|${edgeIds.join(",")}`;
}

function rememberCurrentPositions() {
  if (!activeGraphKey || !flowNodes.value.length) return;
  positionCache.set(activeGraphKey, Object.fromEntries(flowNodes.value.map((node) => [node.id, { ...node.position }])));
}

function rebuild(nextGraphKey = graphKey(), discardCurrent = false) {
  if (!discardCurrent) rememberCurrentPositions();
  else positionCache.delete(nextGraphKey);
  const layout = layoutPlanDag(props.nodes, props.edges);
  const savedPositions = positionCache.get(nextGraphKey);
  if (savedPositions) {
    layout.nodes.forEach((node) => {
      if (savedPositions[node.id]) node.position = { ...savedPositions[node.id] };
    });
  }
  flowNodes.value = layout.nodes;
  flowEdges.value = layout.edges;
  activeGraphKey = nextGraphKey;
  nextTick(() => focusReadableView());
}

function fitGraph() {
  return fitView({ padding: 0.16, duration: 260, minZoom: 0.2, maxZoom: 1.15 });
}

function focusReadableView() {
  return fitView({ padding: 0.12, duration: 260, minZoom: 0.68, maxZoom: 1.05 });
}

function resetLayout() {
  rebuild(graphKey(), true);
}

function syncNodeData() {
  const dataById = Object.fromEntries(props.nodes.map((node, index) => [String(node?.id || `step-${node?.stepId || index + 1}`), node]));
  flowNodes.value.forEach((node) => {
    if (dataById[node.id]) node.data = { ...dataById[node.id], id: node.id };
  });
}

function statusKey(data = {}) {
  return String(data.statusText || data.status || (data.success === true ? "success" : data.success === false ? "failed" : "planned")).toLowerCase();
}

function nodeTone(data) {
  const status = statusKey(data);
  if (/fail|error|kill|cancel/.test(status)) return "failed";
  if (/success|complete|done/.test(status)) return "success";
  if (/run|execut/.test(status)) return "running";
  if (/wait|confirm|pending/.test(status)) return "waiting";
  if (/final/.test(String(data.actionType || "").toLowerCase())) return "final";
  return "planned";
}

function statusLabel(data) {
  return data.statusLabel || ({ success: "成功", failed: "失败", running: "运行中", waiting: "等待", planned: "计划" }[nodeTone(data)] || "计划");
}

function miniMapColor(node) {
  return { success: "#4f9d69", failed: "#c85b5b", running: "#3f78a8", waiting: "#c58a2b", final: "#7568a6" }[nodeTone(node.data)] || "#7b8fa4";
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
    selected: Boolean(selectedId) && node.id === selectedId
  }));
  if (!target || !center) return Promise.resolve(false);
  return setCenter(
    target.position.x + PLAN_FLOW_NODE_WIDTH / 2,
    target.position.y + PLAN_FLOW_NODE_HEIGHT / 2,
    { zoom: 1, duration: 320 }
  );
}

function escapeXml(value) {
  return String(value ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function downloadSvg() {
  if (!flowNodes.value.length) return;
  const minX = Math.min(...flowNodes.value.map((node) => node.position.x)) - 40;
  const minY = Math.min(...flowNodes.value.map((node) => node.position.y)) - 40;
  const maxX = Math.max(...flowNodes.value.map((node) => node.position.x + PLAN_FLOW_NODE_WIDTH)) + 40;
  const maxY = Math.max(...flowNodes.value.map((node) => node.position.y + PLAN_FLOW_NODE_HEIGHT)) + 40;
  const byId = Object.fromEntries(flowNodes.value.map((node) => [node.id, node]));
  const edgeSvg = flowEdges.value.map((edge) => {
    const source = byId[edge.source];
    const target = byId[edge.target];
    if (!source || !target) return "";
    const sx = source.position.x + PLAN_FLOW_NODE_WIDTH;
    const sy = source.position.y + PLAN_FLOW_NODE_HEIGHT / 2;
    const tx = target.position.x;
    const ty = target.position.y + PLAN_FLOW_NODE_HEIGHT / 2;
    const curve = Math.max(55, Math.abs(tx - sx) / 2);
    return `<path d="M ${sx} ${sy} C ${sx + curve} ${sy}, ${tx - curve} ${ty}, ${tx} ${ty}" marker-end="url(#arrow)"/>`;
  }).join("");
  const nodeSvg = flowNodes.value.map((node) => {
    const data = node.data || {};
    const color = miniMapColor(node);
    return `<g transform="translate(${node.position.x},${node.position.y})"><rect width="${PLAN_FLOW_NODE_WIDTH}" height="${PLAN_FLOW_NODE_HEIGHT}" rx="12" fill="#fff" stroke="${color}" stroke-width="2"/><circle cx="18" cy="21" r="5" fill="${color}"/><text x="32" y="25" class="status">${escapeXml(statusLabel(data))}</text><text x="18" y="56" class="title">${escapeXml(data.fullLabelText || data.labelText || data.label || node.id)}</text><text x="18" y="84" class="meta">${escapeXml(data.toolName || data.actionText || "运行节点")}</text><text x="18" y="108" class="step">#${escapeXml(data.stepId || node.id)}</text></g>`;
  }).join("");
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
watch(() => props.selectedNodeId, (nodeId) => nextTick(() => selectNode(nodeId, { center: false })), { immediate: true });
defineExpose({ fitGraph, resetLayout, downloadSvg, selectNode });
</script>

<style scoped>
.interactive-plan-dag { min-width: 0; border: 1px solid #dbe5f2; border-radius: 14px; overflow: hidden; background: #f8fbff; }
.plan-flow-toolbar { min-height: 58px; display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 10px 14px; border-bottom: 1px solid #dbe5f2; background: rgba(255,255,255,.96); }
.plan-flow-toolbar > div:first-child { min-width: 0; display: grid; gap: 3px; }
.plan-flow-toolbar strong { color: #172033; font-size: 13px; }
.plan-flow-toolbar span { color: #667085; font-size: 11px; }
.plan-flow-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 7px; }
.plan-flow-actions button { height: 32px; border: 1px solid #cfdced; border-radius: 8px; padding: 0 11px; background: #fff; color: #344054; font-size: 12px; font-weight: 700; }
.plan-flow-actions button:hover { border-color: #2f7cf6; color: #175cd3; background: #f5f9ff; }
.plan-flow { width: 100%; height: 650px; background: #f5f7fa; }
.plan-flow-node { --node-accent: #7b8fa4; --node-border: #bdc9d5; --node-bg: #f7f9fb; --node-chip-bg: #e8edf2; width: 300px; min-height: 126px; box-sizing: border-box; display: grid; gap: 8px; border: 1px solid var(--node-border); border-left: 4px solid var(--node-accent); border-radius: 7px; padding: 11px 14px 11px 13px; background: var(--node-bg); box-shadow: 0 3px 10px rgba(44,62,80,.1); cursor: grab; transition: border-color .15s, box-shadow .15s, transform .15s; }
.plan-flow-node:active { cursor: grabbing; }
.plan-flow-node.selected { border-color: var(--node-accent); box-shadow: 0 0 0 3px rgba(63,120,168,.2), 0 7px 18px rgba(44,62,80,.14); transform: translateY(-1px); }
.plan-flow-node.success { --node-accent: #4f9d69; --node-border: #a9cbb5; --node-bg: #f2f8f4; --node-chip-bg: #dceee2; }
.plan-flow-node.failed { --node-accent: #c85b5b; --node-border: #dfb0b0; --node-bg: #fcf3f3; --node-chip-bg: #f4dddd; }
.plan-flow-node.running { --node-accent: #3f78a8; --node-border: #a9bfd3; --node-bg: #f0f5fa; --node-chip-bg: #dbe8f3; }
.plan-flow-node.waiting { --node-accent: #c58a2b; --node-border: #dac39d; --node-bg: #faf6ed; --node-chip-bg: #f2e6cf; }
.plan-flow-node.final { --node-accent: #7568a6; --node-border: #bdb6d5; --node-bg: #f5f3f9; --node-chip-bg: #e6e1f0; }
.plan-flow-node header, .plan-flow-node footer { min-width: 0; display: flex; align-items: center; gap: 7px; }
.plan-flow-node header small { border-radius: 4px; padding: 2px 6px; background: var(--node-chip-bg); color: #3f4c59; font-size: 10px; font-weight: 800; letter-spacing: .02em; }
.plan-flow-node header code { margin-left: auto; max-width: 120px; overflow: hidden; color: #6b7d8f; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.plan-flow-status-dot { width: 8px; height: 8px; flex: 0 0 auto; border-radius: 50%; background: var(--node-accent); box-shadow: 0 0 0 3px color-mix(in srgb, var(--node-accent) 16%, transparent); }
.plan-flow-node > strong { min-width: 0; display: -webkit-box; overflow: hidden; color: #25313d; font-size: 14px; line-height: 1.35; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow-wrap: anywhere; }
.plan-flow-node > p { min-width: 0; margin: 0; overflow: hidden; color: #536475; font-size: 11px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.plan-flow-node footer { border-top: 1px solid color-mix(in srgb, var(--node-border) 72%, transparent); padding-top: 7px; color: #6b7d8f; font-size: 10px; text-transform: uppercase; }
:deep(.vue-flow__edge-path) { stroke: #8093a7; stroke-width: 1.8; }
:deep(.vue-flow__edge.selected .vue-flow__edge-path) { stroke: #3f78a8; stroke-width: 2.8; }
:deep(.vue-flow__edge-text) { fill: #3f4c59; font-size: 11px; font-weight: 800; }
:deep(.vue-flow__edge-textbg) { fill: rgba(248,250,252,.96); stroke: #c8d2dc; stroke-width: 1; }
:deep(.vue-flow__handle) { width: 8px; height: 8px; border: 2px solid var(--node-bg); background: var(--node-accent); }
:deep(.vue-flow__minimap) { border: 1px solid #d8e1ee; border-radius: 10px; overflow: hidden; background: rgba(255,255,255,.92); }
:deep(.vue-flow__controls) { border: 1px solid #d8e1ee; border-radius: 9px; overflow: hidden; box-shadow: 0 5px 15px rgba(15,23,42,.08); }
:deep(.vue-flow__controls-button) { line-height: 0; }
:deep(.vue-flow__controls-button svg) { display: block; flex: 0 0 auto; }
:deep(.vue-flow__controls-zoomout svg) { width: 12px; height: 12px; transform: translateY(-1px); }
@media (max-width: 720px) { .plan-flow-toolbar { align-items: flex-start; flex-direction: column; }.plan-flow-actions { justify-content: flex-start; }.plan-flow { height: 560px; } }
</style>
