<template>
  <section class="interactive-plan-dag" :aria-label="title">
    <div class="plan-flow-toolbar">
      <div>
        <strong>{{ title }}</strong>
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
      :fit-view-on-init="!exploration"
      :zoom-on-double-click="false"
      @node-click="handleNodeClick"
    >
      <template #node-plan="{ data, selected }">
        <article
          class="plan-flow-node"
          :class="[nodeTone(data), { selected }]"
          :style="exploration ? kindStyle(data.kind) : {}"
        >
          <Handle type="target" :position="Position.Left" />
          <header>
            <span class="plan-flow-status-dot"></span>
            <small>{{ statusLabel(data) }}</small>
            <code>{{ exploration ? data.actionText : `#${data.stepId || data.id}` }}</code>
          </header>
          <strong :title="data.fullLabelText || data.labelText || data.label">{{
            data.fullLabelText || data.labelText || data.label || data.id
          }}</strong>
          <p :title="data.toolName || data.actionText">{{
            data.toolName || data.actionText || "运行节点"
          }}</p>
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

<script src="../js/components/PlanDagGraph.js"></script>

<style scoped src="../styles/components/plan-dag-graph.css"></style>
