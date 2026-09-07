<template>
  <div class="report-markdown">
    <template v-for="(part, index) in parts" :key="index">
      <div v-if="part.type === 'markdown'" v-html="renderMarkdown(part.content)"></div>
      <section v-else class="report-inline-visualization">
        <VisualizationRenderer :spec="part.spec" @drill-down="$emit('drill-down', $event)" />
        <p v-if="part.spec.scope" class="report-visualization-scope">{{ part.spec.scope }}</p>
      </section>
    </template>
  </div>
</template>

<script setup>
import { computed } from "vue";
import VisualizationRenderer from "./VisualizationRenderer.vue";
import { splitReportVisualizations } from "../js/utils/reportVisualization.js";
const props = defineProps({ content: { type: String, default: "" }, renderMarkdown: { type: Function, required: true } });
defineEmits(["drill-down"]);
const parts = computed(() => splitReportVisualizations(props.content));
</script>

<style scoped>
.report-inline-visualization { margin: 1rem 0; min-width: 0; }
.report-visualization-scope { color: var(--text-secondary, #667085); font-size: 0.85rem; }
</style>
