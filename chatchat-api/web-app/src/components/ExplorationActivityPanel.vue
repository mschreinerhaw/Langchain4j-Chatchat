<template>
  <section v-if="running" class="exploration-activity-panel" aria-label="实时分析活动" role="status" aria-live="polite">
    <LoaderCircle :size="16" class="analysis-event-spinner" aria-hidden="true" />
    <span class="analysis-activity-title">分析活动</span>
    <span class="analysis-activity-message">{{ businessAction }}</span>
    <time>{{ workbench.elapsedLabel }}</time>
  </section>
</template>
<script src="../js/components/ExplorationActivityPanel.js"></script>
<style scoped src="../styles/components/exploration-activity-panel.css"></style>
