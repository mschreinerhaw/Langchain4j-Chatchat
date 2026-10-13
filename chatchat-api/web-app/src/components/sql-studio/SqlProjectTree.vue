<template>
  <aside class="sql-project-tree sql-ide-explorer" @contextmenu.prevent="openContext({ event: $event, file: null })">
    <header><div><small>EXPLORER</small><strong>工程文件</strong></div><button type="button" title="刷新工程文件" :disabled="busy" @click="$emit('refresh')">↻</button></header>
    <div class="sql-explorer-project">{{ projectName || '当前工程' }}<small>{{ files.length }} 个文件</small></div>
    <div class="sql-explorer-actions">
      <button type="button" :disabled="busy" @click="$emit('choose-library', 'MAPPING')">从文档库选 Mapping</button>
      <button type="button" :disabled="busy" @click="$emit('choose-library', 'REFERENCE')">从文档库选 SQL 案例</button>
    </div>
    <div class="sql-tree-scroll">
      <SqlFileTreeNode :nodes="tree" :active-id="activeId" :mapping-ids="mappingIds" @open="$emit('open', $event)" @mapping="$emit('mapping', $event)" @context="openContext" />
      <p v-if="!files.length" class="sql-empty-copy">右键新建 SQL 文件，或从文档库关联 Mapping 与案例。</p>
      <details class="sql-explorer-references"><summary>已关联 SQL 案例 <small>{{ references.length }}</small></summary>
        <div v-for="file in references" :key="file.id" class="sql-explorer-reference"><input type="checkbox" :aria-label="'关联 SQL 案例 ' + file.path" :checked="referenceIds.includes(file.id)" @change="$emit('reference', file.id)" /><button :title="file.path" @click="$emit('reference-open', file)">{{ file.path.split('/').at(-1) }}</button><small>v{{ file.version }}</small></div>
      </details>
    </div>
    <footer>{{ files.length }} 个文件 · {{ mappingIds.length }} 个 Mapping 已关联</footer>
    <div v-if="contextOpen" class="sql-explorer-context" :style="{ top: contextY + 'px', left: contextX + 'px' }" role="menu" @contextmenu.prevent>
      <button role="menuitem" @click="beginCreate">新建 SQL 文件…</button>
      <form v-if="creating" @submit.prevent="commitCreate"><input v-model="path" aria-label="新文件路径" autofocus placeholder="sql/new_file.sql" /><button :disabled="!path.trim()">创建并保存</button></form>
      <template v-if="contextFile">
        <button role="menuitem" @click="$emit('open', contextFile.id); closeContext()">打开</button>
        <button role="menuitem" @click="copyPath">复制路径</button>
        <button role="menuitem" @click="$emit('versions', contextFile); closeContext()">查看版本</button>
        <button role="menuitem" @click="$emit('download', contextFile); closeContext()">下载</button>
        <button role="menuitem" @click="beginRename">重命名…</button>
        <form v-if="renaming" @submit.prevent="commitRename"><input v-model="renameName" aria-label="新文件名" autofocus /><button :disabled="!renameName.trim()">确定</button></form>
        <button role="menuitem" class="sql-danger-action" @click="deleteCurrent">删除文件…</button>
      </template>
    </div>
  </aside>
</template>
<script src="../../js/components/sql-studio/SqlProjectTree.js"></script>
