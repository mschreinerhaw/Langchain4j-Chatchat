<template>
  <section class="sql-editor-pane">
    <nav class="sql-editor-tabs" aria-label="工程编辑标签">
      <div v-for="file in openFiles" :key="file.id" :class="{ active: activeId === file.id && !preview }">
        <button type="button" @click="$emit('open', file.id)">{{ file.path.split('/').at(-1) }}<i v-if="file.draft !== file.content">●</i></button>
        <button type="button" :aria-label="'关闭标签 ' + file.path" @click="$emit('close', file.id)">×</button>
      </div>
      <div v-if="preview" class="active sql-preview-tab"><span>生成预览 · {{ preview.path }}</span><button type="button" aria-label="关闭生成预览" @click="$emit('preview-close')">×</button></div>
    </nav>
    <header class="sql-file-toolbar"><span>{{ preview?.path || current?.path || '选择工程文件' }}</span>
      <small v-if="preview">只读预览 · 应用后才会写入工程</small><small v-else-if="current">v{{ current.version }}{{ current.conflict ? ' · 版本已冲突' : current.editable ? '' : ' · 文档文本预览' }}</small>
      <EditorPreferences :workspace-context="workspaceContext" language="sql" @change="applyPreferences" />
      <button v-if="current?.editable && current.kind === 'SQL' && !preview" type="button" @click="formatDocument">格式化</button>
      <button v-if="current && !preview" type="button" @click="$emit('versions', current)">版本</button>
      <button v-if="current && !preview" type="button" @click="$emit('download', current)">下载</button>
    </header>
    <div ref="host" class="sql-monaco-host" :class="{ 'sql-editor-hidden': !current && !preview }"></div>
    <div v-if="!current && !preview" class="sql-editor-empty"><strong>SQL 工程编辑器</strong><p>点击左侧文件查看内容，或预览 AI 生成的 SQL。</p></div>
    <footer><span>{{ preview ? '生成结果预览' : current?.kind === 'MAPPING' ? 'Mapping 文档' : 'SQL' }}</span><span>Ctrl/Cmd + S 保存 · 不执行数据库语句</span></footer>
  </section>
</template>
<script src="../../js/components/sql-studio/SqlEditorPane.js"></script>
