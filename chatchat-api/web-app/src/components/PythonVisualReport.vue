<template>
  <HtmlReportFrame v-if="report?.htmlReport" :report="report.htmlReport" :embedded="embedded">
    <template #fallback>
      <ReportRenderer :report="report" :fallback-markdown="fallbackMarkdown" />
    </template>
  </HtmlReportFrame>
  <ReportRenderer v-else :report="report" :fallback-markdown="fallbackMarkdown" />
</template>
<script src="../js/components/LegacyPythonVisualReport.js"></script>
