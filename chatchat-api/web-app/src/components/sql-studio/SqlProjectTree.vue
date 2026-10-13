<template>
  <aside class="sql-project-tree">
    <header><div><small>PROJECT FILES</small><strong>工程文件</strong></div><button type="button" title="刷新文件，不丢弃编辑草稿" :disabled="busy" @click="$emit('refresh')">↻</button></header>
    <div class="sql-upload-actions">
      <label :class="{ disabled: busy }">＋ Mapping<input type="file" multiple :disabled="busy" accept=".json,.csv,.md,.txt,.xlsx,.xls,.pdf,.doc,.docx" @change="upload($event, 'MAPPING')" /></label>
      <label :class="{ disabled: busy }">＋ SQL 案例<input type="file" multiple :disabled="busy" accept=".sql" @change="upload($event, 'REFERENCE')" /></label>
    </div>
    <form class="sql-new-file" @submit.prevent="create"><input v-model="path" aria-label="新 SQL 文件路径" placeholder="sql/new_file.sql" :disabled="busy" /><button :disabled="busy || !path.trim()" title="创建 SQL 文件">＋</button></form>
    <div class="sql-tree-scroll"><SqlFileTreeNode :nodes="tree" :active-id="activeId" :mapping-ids="mappingIds" @open="$emit('open', $event)" @mapping="$emit('mapping', $event)" />
      <p v-if="!files.length" class="sql-empty-copy">上传 Mapping 或创建 SQL 文件，开始组织工程。</p>
    </div>
    <footer>{{ files.length }} 个文件 · {{ mappingIds.length }} 个 Mapping 已关联</footer>
  </aside>
</template>
<script setup>
import { computed, ref } from 'vue';
import SqlFileTreeNode from './SqlFileTreeNode.vue';
import { sqlFileTree } from '../../js/utils/sqlStudioState';
const props = defineProps({ files: Array, activeId: String, mappingIds: Array, busy: Boolean });
const emit = defineEmits(['open', 'mapping', 'upload', 'create', 'refresh']);
const path = ref(''); const tree = computed(() => sqlFileTree(props.files));
function create() { emit('create', path.value.trim()); path.value = ''; }
function upload(event, kind) { emit('upload', { files: [...event.target.files], kind }); event.target.value = ''; }
</script>
