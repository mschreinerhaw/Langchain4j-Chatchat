<template>
  <div class="async-view-state" role="status" aria-live="polite">
    <span class="async-view-state__spinner" aria-hidden="true"></span>
    <p>{{ error ? "页面加载失败，请刷新后重试" : "正在加载页面…" }}</p>
  </div>
</template>

<script src="../js/components/AsyncViewState.js"></script>

<style scoped src="../styles/components/async-view-state.css"></style>
