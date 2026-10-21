<template>
  <section class="python-visual-report" aria-label="图文分析报告" :data-status="deliveryStatus">
    <p v-if="!report" role="status">正在整理图文报告…</p>
    <template v-else>
      <header
        ><small>图文报告 · {{ statusLabel }}</small
        ><h2>{{ report.title || "分析报告" }}</h2
        ><p v-if="report.summary">{{ report.summary }}</p></header
      >
      <p v-if="deliveryStatus !== 'SUCCESS'" class="report-notice" role="status"
        >{{ statusLabel }}。已取得的分析内容会继续保留。</p
      >
      <dl v-if="report.metrics?.length" class="python-report-metrics"
        ><div v-for="metric in report.metrics" :key="metric.id"
          ><dt>{{ metric.label }}</dt
          ><dd
            >{{ metric.value }} <small>{{ metric.unit }}</small></dd
          ></div
        ></dl
      >
      <article v-for="(section, index) in report.sections || []" :key="index"
        ><h3>{{ section.title }}</h3
        ><p class="report-narrative">{{ section.narrative }}</p
        ><PythonReportChart
          v-for="chart in chartsFor(section.chartIds)"
          :key="chart.id"
          :chart="chart"
          @status="chartStatus" /><ReportTable
          v-for="table in tablesFor(section.tableIds)"
          :key="table.id"
          :table="table"
      /></article>
      <PythonReportChart
        v-for="chart in looseCharts"
        :key="chart.id"
        :chart="chart"
        @status="chartStatus"
      />
      <ReportTable v-for="table in looseTables" :key="table.id" :table="table" />
      <details v-if="report.sections?.length && markdown" class="report-full-text"
        ><summary>完整分析正文</summary
        ><ReportMarkdown :content="markdown" :render-markdown="renderMarkdown"
      /></details>
      <ReportMarkdown v-else-if="markdown" :content="markdown" :render-markdown="renderMarkdown" />
      <section v-if="report.limitations?.length" class="report-notice" aria-label="分析限制"
        ><h3>解读提示</h3
        ><ul
          ><li v-for="(limitation, index) in report.limitations" :key="index">{{
            limitation
          }}</li></ul
        ></section
      >
    </template>
  </section>
</template>
<script src="../js/components/PythonVisualReport.js"></script>
<style scoped src="../styles/components/python-visual-report.css"></style>
