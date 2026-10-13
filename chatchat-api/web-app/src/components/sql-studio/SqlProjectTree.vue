<template>
  <aside class="sql-project-tree sql-ide-explorer" @contextmenu.prevent="openContext({ event: $event, file: null })">
    <header><strong>项目资源</strong><div class="sql-explorer-header-actions"><button type="button" title="新建 SQL 文件" aria-label="新建项目文件" :disabled="busy" @click="newFile"><Plus :size="16" /></button><button type="button" title="刷新工程文件" aria-label="刷新工程文件" :disabled="busy" @click="$emit('refresh')"><RotateCw :size="15" /></button></div></header>
    <label class="sql-explorer-search"><Search :size="15" /><input v-model="query" type="search" aria-label="搜索工程文件" placeholder="搜索文件或文件夹…" /></label>
    <div class="sql-explorer-project"><Folder :size="16" /><span>{{ projectName || '当前工程' }}</span><small>{{ files.length }}</small></div>
    <div class="sql-tree-scroll">
      <SqlFileTreeNode :nodes="tree" :active-id="activeId" :mapping-ids="mappingIds" @open="$emit('open', $event)" @mapping="$emit('mapping', $event)" @context="openContext" />
      <p v-if="!files.length" class="sql-empty-copy">点击 + 或右键新建 SQL 文件，也可以直接描述需求生成脚本。</p>
      <p v-else-if="!filteredFiles.length" class="sql-empty-copy">没有匹配的工程文件。</p>
      <details class="sql-explorer-references"><summary>已关联 SQL 案例 <small>{{ references.length }}</small></summary>
        <div v-for="file in filteredReferences" :key="file.id" class="sql-explorer-reference"><input type="checkbox" :aria-label="'关联 SQL 案例 ' + file.path" :checked="referenceIds.includes(file.id)" @change="$emit('reference', file.id)" /><button :title="file.path" @click="$emit('reference-open', file)">{{ file.path.split('/').at(-1) }}</button><small>v{{ file.version }}</small></div>
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
