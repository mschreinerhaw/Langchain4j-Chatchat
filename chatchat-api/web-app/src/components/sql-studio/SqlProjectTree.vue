<template>
  <aside class="sql-project-tree sql-ide-explorer" :class="{ 'is-drop-target': dragging }" @dragenter.prevent="dragging = true" @dragover.prevent @dragleave="dragLeave" @drop.prevent="dropFiles">
    <header><div><small>EXPLORER</small><strong>工程文件</strong></div><button type="button" title="刷新文件，不丢弃编辑草稿" :disabled="busy" @click="$emit('refresh')">↻</button></header>
    <div class="sql-explorer-project">{{ projectName || '当前工程' }}<small>{{ files.length }} 个文件</small></div>
    <div class="sql-explorer-actions">
      <label :class="{ disabled: busy }" title="上传 Mapping 文档">＋ Mapping<input type="file" multiple :disabled="busy" accept=".json,.csv,.md,.txt,.xlsx,.xls,.pdf,.doc,.docx" @change="upload($event, 'MAPPING')" /></label>
      <label :class="{ disabled: busy }" title="上传历史 SQL 案例">＋ SQL 案例<input type="file" multiple :disabled="busy" accept=".sql" @change="upload($event, 'REFERENCE')" /></label>
    </div>
    <form class="sql-new-file" @submit.prevent="create"><input v-model="path" aria-label="新 SQL 文件路径" placeholder="sql/new_file.sql" :disabled="busy" /><button :disabled="busy || !path.trim()" title="创建 SQL 文件">＋</button></form>
    <div class="sql-tree-scroll">
      <SqlFileTreeNode :nodes="tree" :active-id="activeId" :mapping-ids="mappingIds" @open="$emit('open', $event)" @mapping="$emit('mapping', $event)" @context="openContext" />
      <p v-if="!files.length" class="sql-empty-copy">拖入 Mapping 或 SQL 案例，或创建 SQL 文件。</p>
      <details class="sql-explorer-references" open><summary>历史 SQL 案例 <small>{{ references.length }}</small></summary>
        <div v-for="file in references" :key="file.id" class="sql-explorer-reference"><input type="checkbox" :aria-label="'关联 SQL 案例 ' + file.path" :checked="referenceIds.includes(file.id)" @change="$emit('reference', file.id)" /><button :title="file.path" @click="$emit('reference-open', file)">{{ file.path.split('/').at(-1) }}</button><small>v{{ file.version }}</small></div>
        <p v-if="!references.length" class="sql-empty-copy">上传案例后可在这里关联。</p>
      </details>
    </div>
    <footer>{{ files.length }} 个文件 · {{ mappingIds.length }} 个 Mapping 已关联</footer>
    <div v-if="dragging" class="sql-explorer-drop-hint">释放文件以加入工程</div>
    <div v-if="contextFile" class="sql-explorer-context" :style="{ top: contextY + 'px', left: contextX + 'px' }" role="menu">
      <button role="menuitem" @click="$emit('open', contextFile.id); closeContext()">打开</button>
      <button role="menuitem" @click="copyPath">复制路径</button>
      <button role="menuitem" @click="$emit('versions', contextFile); closeContext()">查看版本</button>
      <button role="menuitem" @click="$emit('download', contextFile); closeContext()">下载</button>
      <button role="menuitem" @click="beginRename">重命名</button>
      <form v-if="renaming" @submit.prevent="commitRename"><input v-model="renameName" aria-label="新文件名" autofocus /><button :disabled="!renameName.trim()">确定</button></form>
    </div>
  </aside>
</template>
<script src="../../js/components/sql-studio/SqlProjectTree.js"></script>
