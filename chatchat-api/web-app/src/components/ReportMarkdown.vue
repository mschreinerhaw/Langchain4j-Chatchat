<template>
  <div class="report-markdown">
    <template v-for="(part, index) in parts" :key="index">
      <div v-if="part.type === 'markdown'" v-html="renderMarkdown(part.content)"></div>
      <details v-else-if="part.type === 'thinking'" class="model-thinking-block">
        <summary
          >思考内容 <small class="thinking-expand-label">点击展开</small
          ><small class="thinking-collapse-label">点击收起</small></summary
        >
        <pre>{{ part.content }}</pre>
      </details>
      <section
        v-else-if="part.type === 'visualization-summary'"
        class="report-visualization-summary"
      >
        <h4>{{ part.block.title }}</h4>
        <p v-if="part.block.conclusion">{{ part.block.conclusion }}</p>
        <p v-if="part.block.reason">{{ part.block.reason }}</p>
        <p class="report-visualization-scope">{{ part.message }}</p>
      </section>
      <section v-else class="report-inline-visualization">
        <p v-if="part.block?.conclusion" class="report-visualization-conclusion">{{
          part.block.conclusion
        }}</p>
        <VisualizationRenderer
          :spec="applyReportChartPreference(part.spec, preferences[part.slot || `report:${index}`])"
          :preference="preferences[part.slot || `report:${index}`] || null"
          @drill-down="$emit('drill-down', $event)"
          @preference-change="
            $emit('preference-change', { slot: part.slot || `report:${index}`, preference: $event })
          "
        />
        <p v-if="part.spec.scope" class="report-visualization-scope">{{ part.spec.scope }}</p>
      </section>
    </template>
  </div>
</template>

<script src="../js/components/ReportMarkdown.js"></script>

<style scoped src="../styles/components/report-markdown.css"></style>
