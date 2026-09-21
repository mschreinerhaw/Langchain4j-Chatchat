<template>
  <div class="report-markdown">
    <template v-for="(part, index) in parts" :key="index">
      <div v-if="part.type === 'markdown'" v-html="renderMarkdown(part.content)"></div>
      <details v-else-if="part.type === 'thinking'" class="model-thinking-block">
        <summary>思考内容 <small class="thinking-expand-label">点击展开</small><small class="thinking-collapse-label">点击收起</small></summary>
        <pre>{{ part.content }}</pre>
      </details>
      <section v-else class="report-inline-visualization">
        <VisualizationRenderer
          :spec="part.spec"
          :preference="preferences[`report:${index}`] || null"
          @drill-down="$emit('drill-down', $event)"
          @preference-change="$emit('preference-change', { slot: `report:${index}`, preference: $event })"
        />
        <p v-if="part.spec.scope" class="report-visualization-scope">{{ part.spec.scope }}</p>
      </section>
    </template>
  </div>
</template>

<script setup>
import { computed } from "vue";
import VisualizationRenderer from "./VisualizationRenderer.vue";
import { splitReportVisualizations } from "../js/utils/reportVisualization.js";
import { splitThinkBlocks } from "../js/utils/thinkBlocks.js";
const props = defineProps({
  content: { type: String, default: "" },
  renderMarkdown: { type: Function, required: true },
  collapseThinking: { type: Boolean, default: false },
  preferences: { type: Object, default: () => ({}) }
});
defineEmits(["drill-down", "preference-change"]);
const parts = computed(() => (props.collapseThinking
  ? splitThinkBlocks(props.content)
  : [{ type: "text", content: props.content }]
).flatMap((block) => block.type === "thinking" ? [block] : splitReportVisualizations(block.content)));
</script>

<style scoped>
.report-inline-visualization { margin: 1rem 0; min-width: 0; }
.report-visualization-scope { color: var(--text-secondary, #667085); font-size: 0.85rem; }
.model-thinking-block { margin: 8px 0 12px; padding: 7px 10px; border-radius: 8px; background: #f3f5f8; color: #7a8493; font-size: 11px; line-height: 1.5; }
.model-thinking-block summary { cursor: pointer; user-select: none; }
.model-thinking-block summary small { margin-left: 5px; color: #9aa3ae; font-size: 10px; }
.model-thinking-block .thinking-collapse-label, .model-thinking-block[open] .thinking-expand-label { display: none; }
.model-thinking-block[open] .thinking-collapse-label { display: inline; }
.model-thinking-block pre { margin: 8px 0 2px; color: inherit; font: inherit; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
