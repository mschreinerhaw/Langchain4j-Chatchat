<template>
  <div class="workspace-switcher">
    <label>工作空间
      <select :value="activeId" :disabled="busy" @change="$emit('switch', $event.target.value)">
        <option v-for="space in spaces" :key="space.manifest.workspaceId" :value="space.manifest.workspaceId">{{ space.manifest.name }}</option>
      </select>
    </label>
    <button type="button" @click="$emit('select')">选择空间</button>
    <button type="button" :disabled="busy" @click="$emit('default')">设为默认</button>
  </div>
</template>
<script setup>
defineProps({ spaces: { type: Array, default: () => [] }, activeId: String, busy: Boolean });
defineEmits(['switch', 'select', 'default']);
</script>
<style scoped>
.workspace-switcher{display:flex;flex-wrap:wrap;align-items:center;gap:8px;padding:10px 16px;border-bottom:1px solid var(--border-color,#e2e8f0);background:var(--surface-color,#fff)}
label{display:flex;align-items:center;gap:8px}select,button{border:1px solid #cbd5e1;border-radius:6px;padding:6px 10px;background:transparent;color:inherit}button{cursor:pointer}
</style>
