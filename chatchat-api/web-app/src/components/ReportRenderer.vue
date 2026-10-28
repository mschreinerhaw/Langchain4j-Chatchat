<template>
  <section
    class="python-visual-report report-renderer"
    aria-label="图文分析报告"
    :data-status="deliveryStatus"
    :data-layout-strategy="layout.preferences.strategy"
    :data-density="layout.preferences.density"
  >
    <p v-if="!report" role="status">正在整理图文报告…</p>
    <template v-else>
      <header
        ><small>图文报告 · {{ statusLabel }}</small
        ><h2>{{ report.title || "分析报告" }}</h2></header
      >
      <p v-if="deliveryStatus !== 'SUCCESS'" class="report-notice" role="status"
        >{{ statusLabel }}。已取得的分析内容会继续保留。</p
      >
      <p
        v-if="layout.warning || report.layoutValidationStatus === 'FAILED'"
        class="report-notice"
        role="status"
        >{{ layout.warning || "排版描述未通过校验，已保留原分析并使用兼容布局。" }}</p
      >
      <nav v-if="contents.length >= 4" class="report-contents" aria-label="报告章节"
        ><a
          v-for="block in contents"
          :key="block.id"
          :href="`#${reportId}-${block.id}`"
          @click.prevent="scrollToBlock(block)"
          >{{ block.value.title }}</a
        ></nav
      >
      <div class="report-layout-grid">
        <article
          v-for="block in layout.blocks"
          :key="block.id"
          :id="`${reportId}-${block.id}`"
          class="report-layout-block"
          :class="[
            `report-block-${block.type}`,
            `report-span-${block.span}`,
            { 'report-important': block.emphasis === 'important' },
          ]"
          :data-block-type="block.type"
          :data-report-ref="block.ref"
        >
          <div
            v-if="block.type === 'summary'"
            class="report-summary"
            v-html="renderReportNarrative(report.summary)"
          ></div>
          <dl v-else-if="block.type === 'metric'" class="python-report-metrics"
            ><div
              ><dt>{{ block.value.label }}</dt
              ><dd
                >{{ block.value.value }} <small>{{ block.value.unit }}</small></dd
              ></div
            ></dl
          >
          <template v-else-if="block.type === 'section'"
            ><template v-if="showSectionText(block.value)"
              ><h3>{{ block.value.title }}</h3
              ><div
                class="report-narrative"
                v-html="renderReportNarrative(block.value.narrative)"
              ></div></template
          ></template>
          <PythonReportChart
            v-else-if="block.type === 'chart'"
            :chart="block.value"
            @status="chartStatus"
          />
          <ReportTable v-else-if="block.type === 'table'" :table="block.value" />
          <section
            v-else-if="block.type === 'limitations'"
            class="report-notice"
            aria-label="分析限制"
            ><h3>解读提示</h3
            ><ul
              ><li v-for="(limitation, index) in report.limitations" :key="index"
                ><div v-html="renderReportNarrative(limitation)"></div></li></ul
          ></section>
          <details v-else-if="block.type === 'sources'" class="report-provenance"
            ><summary>数据来源</summary
            ><ul
              ><li v-for="source in report.dataSources" :key="source">{{ source }}</li></ul
            ></details
          >
          <details v-if="block.value?.dataRefs?.length" class="report-provenance"
            ><summary>数据依据</summary
            ><ul
              ><li v-for="source in block.value.dataRefs" :key="source">{{ source }}</li></ul
            ></details
          >
        </article>
      </div>
    </template>
  </section>
</template>
<script src="../js/components/PythonVisualReport.js"></script>
<style scoped src="../styles/components/python-visual-report.css"></style>
