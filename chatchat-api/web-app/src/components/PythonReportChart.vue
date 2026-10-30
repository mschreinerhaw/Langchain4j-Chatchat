<template>
  <figure
    ref="container"
    class="python-report-chart"
    :class="{'chart-compact':compact}"
    :data-chart-id="chart.id"
    :aria-busy="loading"
  >
    <figcaption v-if="!compact"
      ><strong>{{ chart.title }}</strong></figcaption
    >
    <p v-if="error" role="alert">{{ error }} <button v-if="!chart.renderError" type="button" @click="load">重试</button></p>
    <div v-if="error && fallbackTable" class="chart-data-fallback"><table aria-label="图表替代数据表"><thead><tr><th v-for="column in fallbackTable.columns" :key="column">{{ column }}</th></tr></thead><tbody><tr v-for="(row,index) in fallbackTable.rows" :key="index"><td v-for="(cell,column) in row" :key="column">{{ cell ?? '—' }}</td></tr></tbody></table><p v-if="fallbackTable.truncated">仅展示前 200 行图表数据。</p></div>
    <p v-else-if="loading" role="status">正在加载图表…</p>
    <p v-else-if="empty" role="status">此图表暂无可绘制数据。</p>
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
    <p v-if="chart.caption && !compact" class="chart-caption">{{ chart.caption }}</p>
  </figure>
</template>
<script src="../js/components/PythonReportChart.js"></script>
<style scoped src="../styles/components/python-report-chart.css"></style>
