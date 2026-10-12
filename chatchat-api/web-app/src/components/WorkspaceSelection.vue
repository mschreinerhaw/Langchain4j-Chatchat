<template>
  <section class="workspace-selection">
    <h1>选择工作空间</h1>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="!spaces.length">当前没有可访问的工作空间。</p>
    <button v-for="space in spaces" :key="space.manifest.workspaceId" type="button" :disabled="busy" @click="$emit('switch',space.manifest.workspaceId)">
      <strong>{{ space.manifest.name }}</strong><span>{{ space.manifest.description }}</span>
    </button>
    <button type="button" @click="$emit('retry')">刷新可访问空间</button>
  </section>
</template>
<script setup>
defineProps({ spaces: { type: Array, default: () => [] }, busy: Boolean, error: String });
defineEmits(['switch','retry']);
</script>
<style scoped>
.workspace-selection{padding:32px;max-width:900px;margin:auto}button{display:flex;flex-direction:column;gap:10px;width:100%;padding:20px;margin:12px 0;border:1px solid #cbd5e1;border-radius:10px;background:var(--surface-color,#fff);color:inherit;text-align:left;cursor:pointer}span{font-size:14px}p[role=alert]{color:#b91c1c}
</style>
