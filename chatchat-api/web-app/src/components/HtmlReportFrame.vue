<template>
  <section ref="viewer" class="html-report-viewer" aria-label="交互式分析报告">
    <header v-if="document && !error" aria-label="报告操作"><button type="button" @click="fullscreen">全屏查看</button><button type="button" @click="download">下载离线报告</button></header>
    <p v-if="loading" role="status">正在加载分析报告…</p>
    <p v-else-if="error" role="alert">{{ error }}</p>
    <iframe v-else ref="frame" :srcdoc="document" :style="{height: frameHeight + 'px'}" title="交互式分析报告" sandbox="allow-scripts" referrerpolicy="no-referrer" credentialless allow="camera 'none'; microphone 'none'; geolocation 'none'; clipboard-read 'none'; clipboard-write 'none'" />
  </section>
</template>
<script src="../js/components/HtmlReportFrame.js"></script>
<style scoped src="../styles/components/html-report-frame.css"></style>
