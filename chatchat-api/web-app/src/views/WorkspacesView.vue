<template>
  <section class="workspace-selection" aria-label="工作空间">
    <p class="workspace-breadcrumb">平台管理 / 工作空间</p>
    <h1>工作空间</h1>
    <p>选择已授权的工作空间，或设置登录后默认进入的空间。</p>
    <p v-if="loading" role="status">正在加载工作空间…</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <div class="workspace-cards">
      <article v-for="workspace in workspaces" :key="workspace.workspaceId" class="workspace-card">
        <h2>{{ workspace.name }}</h2>
        <p>{{ workspace.description }}</p>
        <p v-if="workspace.workspaceId === defaultWorkspaceId" class="workspace-default">默认登录空间</p>
        <button type="button" :disabled="loading" @click="$emit('switch-workspace', workspace.workspaceId)">进入空间</button>
        <button type="button" :disabled="loading || workspace.workspaceId === defaultWorkspaceId"
          @click="$emit('default-workspace', workspace.workspaceId)">
          {{ workspace.workspaceId === defaultWorkspaceId ? '已设为默认' : '设为我的默认空间' }}
        </button>
      </article>
    </div>
    <button v-if="!workspaces.length && !loading" type="button" @click="$emit('reload-workspaces')">重新加载</button>
  </section>
</template>

<script>
export default {
  name: "WorkspacesView",
  props: {
    workspaces: { type: Array, default: () => [] },
    defaultWorkspaceId: { type: String, default: "" },
    loading: Boolean,
    notice: { type: String, default: "" }
  },
  emits: ["switch-workspace", "default-workspace", "reload-workspaces"]
};
</script>

<style scoped src="../styles/app-workspace.css"></style>
