<template>
  <div class="async-view-state" role="status" aria-live="polite">
    <span class="async-view-state__spinner" aria-hidden="true"></span>
    <p>{{ error ? "页面加载失败，请刷新后重试" : "正在加载页面…" }}</p>
  </div>
</template>

<script setup>
defineProps({
  error: {
    type: Error,
    default: null
  }
});
</script>

<style scoped>
.async-view-state {
  min-height: 280px;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 12px;
  color: #64748b;
  font-size: 14px;
}

.async-view-state__spinner {
  width: 26px;
  height: 26px;
  border: 3px solid #dbeafe;
  border-top-color: #3478f6;
  border-radius: 50%;
  animation: async-view-spin 0.8s linear infinite;
}

@keyframes async-view-spin {
  to { transform: rotate(360deg); }
}
</style>
