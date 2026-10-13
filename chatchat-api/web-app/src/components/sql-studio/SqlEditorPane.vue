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
      <button v-if="current && !preview" type="button" @click="$emit('versions', current)">版本</button>
      <button v-if="current && !preview" type="button" @click="$emit('download', current)">下载</button>
    </header>
    <div ref="host" class="sql-monaco-host" :class="{ 'sql-editor-hidden': !current && !preview }"></div>
    <div v-if="!current && !preview" class="sql-editor-empty"><strong>SQL 工程编辑器</strong><p>点击左侧文件查看内容，或预览 AI 生成的 SQL。</p></div>
    <footer><span>{{ preview ? '生成结果预览' : current?.kind === 'MAPPING' ? 'Mapping 文档' : 'SQL' }}</span><span>Ctrl/Cmd + S 保存 · 不执行数据库语句</span></footer>
  </section>
</template>
<script setup>
import { computed, ref, watch, onMounted, onBeforeUnmount, onActivated, nextTick } from 'vue';
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import 'monaco-editor/esm/vs/editor/editor.all';
import 'monaco-editor/esm/vs/basic-languages/sql/sql.contribution';
import 'monaco-editor/min/vs/editor/editor.main.css';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
const props = defineProps({ projectId: String, files: Array, openIds: Array, activeId: String, preview: Object });
const emit = defineEmits(['open', 'close', 'edit', 'save', 'versions', 'download', 'preview-close']);
const host = ref(null); const current = computed(() => props.files.find(file => file.id === props.activeId));
const openFiles = computed(() => props.openIds.map(id => props.files.find(file => file.id === id)).filter(Boolean));
const models = new Map(); const views = new Map();
const instance = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
let editor, listener, displayed, setting = false;
function activate() {
  if (!editor) return;
  if (displayed) views.set(displayed, editor.saveViewState());
  const file = props.preview ? { id: 'preview:' + props.preview.path, draft: props.preview.content, kind: 'SQL', editable: false } : current.value;
  if (!file) { editor.setModel(null); displayed = null; return; }
  const key = props.projectId + ':' + file.id;
  let model = models.get(key);
  if (!model) { model = monaco.editor.createModel(file.draft ?? file.content, file.kind === 'MAPPING' ? 'plaintext' : 'sql', monaco.Uri.parse(`inmemory://sql-studio/${instance}/${encodeURIComponent(key)}`)); models.set(key, model); }
  const content = file.draft ?? file.content;
  setting = true;
  if (model.getValue() !== content) model.setValue(content);
  if (editor.getModel() !== model) { editor.setModel(model); editor.restoreViewState(views.get(key)); }
  editor.updateOptions({ readOnly: !file.editable }); displayed = key; setting = false;
  nextTick(() => editor.layout());
}
onMounted(() => {
  globalThis.MonacoEnvironment = { ...(globalThis.MonacoEnvironment || {}), getWorker: () => new EditorWorker() };
  // Monaco 0.52's occurrence highlighter can reject a pending Delayer on a fast model switch.
  // SQL token coloring remains enabled; avoid that optional asynchronous contribution here.
  editor = monaco.editor.create(host.value, { model: null, automaticLayout: true, occurrencesHighlight:'off', theme: 'vs', fontSize: 14,
    fontFamily: 'Cascadia Code, Consolas, monospace', tabSize: 4, scrollBeyondLastLine: false, minimap: { enabled: true }, padding: { top: 14, bottom: 14 } });
  listener = editor.onDidChangeModelContent(() => { if (!setting && current.value && !props.preview) emit('edit', { id: current.value.id, content: editor.getValue() }); });
  editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => emit('save'));
  activate();
});
watch(() => [props.activeId, props.preview, props.files], activate, { deep: true });
watch(() => props.projectId, () => { editor?.setModel(null); models.forEach(model => model.dispose()); models.clear(); views.clear(); displayed = null; activate(); });
onActivated(() => nextTick(() => editor?.layout()));
onBeforeUnmount(() => { listener?.dispose(); editor?.dispose(); models.forEach(model => model.dispose()); });
</script>
