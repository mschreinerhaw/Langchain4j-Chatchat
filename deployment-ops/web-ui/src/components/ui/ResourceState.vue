<template>
  <div v-if="error" role="alert" class="notice danger">
    <div>
      <strong>数据读取失败</strong>
      <p>{{ error }}</p>
    </div>
    <button @click="$emit('retry')">重试</button>
  </div>
  <p v-else-if="loading" class="loading" role="status">
    <span class="spinner" />
    正在读取实时数据…
  </p>
  <div v-else-if="empty" class="empty-state">
    <strong>{{ title }}</strong>
    <p>{{ description }}</p>
    <slot />
  </div>
</template>
<script setup>
  defineProps({
    loading: Boolean,
    error: String,
    empty: Boolean,
    title: { type: String, default: '暂无数据' },
    description: String,
  });
  defineEmits(['retry']);
</script>
