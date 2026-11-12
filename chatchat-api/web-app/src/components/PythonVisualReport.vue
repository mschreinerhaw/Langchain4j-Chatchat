<template><HtmlReportFrame v-if="report?.htmlReport" :report="report.htmlReport" /><ReportRenderer v-else :report="report" :fallback-markdown="fallbackMarkdown" /></template>
<script src="../js/components/LegacyPythonVisualReport.js"></script>
