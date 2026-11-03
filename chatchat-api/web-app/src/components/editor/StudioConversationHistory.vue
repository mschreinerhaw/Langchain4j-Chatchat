<template>
  <Teleport to="body"><dialog ref="dialog" class="studio-history-dialog" :aria-label="label" @cancel.prevent="close" @click.self="close">
    <header><h3>历史会话</h3><button type="button" aria-label="关闭历史会话" :disabled="deleting" @click="close"><X :size="18" /></button></header>
    <div class="studio-history-body sql-assistant-history" @click="menuId = null">
      <p v-if="loading" role="status">正在加载历史会话…</p>
      <p v-if="error || failure" role="alert">{{ error || failure }}</p>
      <p v-if="!loading && !entries.length && !error">暂无历史会话</p>
      <div v-for="entry in entries" :key="entry.id" class="studio-history-row" :aria-current="entry.id === currentId ? 'true' : undefined">
        <button type="button" class="studio-history-select" :disabled="busy || deleting" @click="$emit('select', entry.id)"><strong>{{ entry.title }}</strong><small>{{ entry.subtitle || '查看会话与结果' }}</small></button>
        <div class="studio-history-actions" @click.stop>
          <button type="button" :aria-label="'会话操作：' + entry.title" aria-haspopup="menu" :aria-expanded="menuId === entry.id" :disabled="busy || deleting" @click="menuId = menuId === entry.id ? null : entry.id"><Ellipsis :size="18" /></button>
          <div v-if="menuId === entry.id" role="menu"><button type="button" role="menuitem" @click="askDelete(entry)"><Trash2 :size="14" />删除会话</button></div>
        </div>
      </div>
      <button v-if="hasMore" type="button" :disabled="loading || deleting" @click="$emit('more')">加载更多</button>
    </div>
  </dialog></Teleport>
  <AppConfirmDialog v-if="selected" title="删除会话" :message="`确定删除“${selected.title}”吗？删除后将无法从历史列表恢复，已保存的工程文件不受影响。`" confirm-label="删除" tone="danger" :busy="deleting" @confirm="confirmDelete" @cancel="selected = null" />
</template>
<script src="../../js/components/editor/StudioConversationHistory.js"></script>
<style src="../../styles/components/studio-conversation-history.css"></style>
