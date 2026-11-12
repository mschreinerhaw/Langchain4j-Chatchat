<template>
  <section class="html-report-viewer" aria-label="交互式分析报告">
    <p v-if="loading" role="status">正在加载分析报告…</p>
    <p v-else-if="error" role="alert">{{ error }}</p>
    <iframe v-else ref="frame" :srcdoc="document" title="交互式分析报告" sandbox="allow-scripts" referrerpolicy="no-referrer" credentialless allow="camera 'none'; microphone 'none'; geolocation 'none'; clipboard-read 'none'; clipboard-write 'none'" />
  </section>
</template>
<script src="../js/components/HtmlReportFrame.js"></script>
<style scoped src="../styles/components/html-report-frame.css"></style>
