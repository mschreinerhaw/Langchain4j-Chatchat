<template>
  <figure
    ref="container"
    class="python-report-chart"
    :data-chart-id="chart.id"
    :aria-busy="loading"
  >
    <figcaption
      ><strong>{{ chart.title }}</strong></figcaption
    >
    <p v-if="error" role="alert">{{ error }} <button type="button" @click="load">重试</button></p>
    <p v-else-if="loading" role="status">正在加载图表…</p>
    <button v-else-if="!imageUrl && !frameDocument" type="button" @click="load">加载图表</button>
    <img
      v-if="imageUrl"
      :src="imageUrl"
      :alt="chart.title"
      loading="lazy"
      @error="error = '静态图表渲染失败，文字分析仍保留'"
    />
    <iframe
      v-if="frameDocument"
      ref="frame"
      :srcdoc="frameDocument"
      :title="chart.title"
      sandbox="allow-scripts"
      referrerpolicy="no-referrer"
      @load="sendFigure"
    ></iframe>
    <p v-if="chart.caption" class="chart-caption">{{ chart.caption }}</p>
  </figure>
</template>
<script src="../js/components/PythonReportChart.js"></script>
<style scoped src="../styles/components/python-report-chart.css"></style>
