import { computed, ref, watch, onMounted, onBeforeUnmount, onActivated, nextTick } from 'vue';
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import 'monaco-editor/esm/vs/editor/editor.all';
import 'monaco-editor/esm/vs/basic-languages/sql/sql.contribution';
import 'monaco-editor/min/vs/editor/editor.main.css';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import { getLanguageAdapter, editorPreferences, editorLanguageOptions, applyFormatIfUnchanged } from '../../editor/codeIntelligence';
import { sqlDiagnostics } from '../../editor/sqlDiagnostics';
import EditorPreferences from '../../../components/editor/EditorPreferences.vue';

export default {
  components: { EditorPreferences },
  props: { projectId: String, files: Array, openIds: Array, activeId: String, preview: Object, dialect: String, schemaColumns: Array, workspaceContext: Object },
  emits: ['open', 'close', 'edit', 'save', 'versions', 'download', 'preview-close'],
  setup(props, { emit: emitEvent }) {
const emit = emitEvent;


const host = ref(null); const current = computed(() => props.files.find(file => file.id === props.activeId));
const openFiles = computed(() => props.openIds.map(id => props.files.find(file => file.id === id)).filter(Boolean));
const models = new Map(); const views = new Map();
const instance = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
let editor, listener, completionProvider, displayed, setting = false, diagnosticTimer, diagnosticSerial = 0;
const sqlAdapter = getLanguageAdapter('sql');
const preferences = editorPreferences(props.workspaceContext, 'sql');
function applyPreferences(next) {
  Object.assign(preferences, next);
  editor?.updateOptions(editorLanguageOptions('sql', preferences));
  for (const model of models.values()) model.updateOptions({ tabSize: preferences.tabSize, insertSpaces: true });
}
function scheduleDiagnostics() {
  clearTimeout(diagnosticTimer);
  const model = editor?.getModel();
  const serial = ++diagnosticSerial;
  if (!model || model.getLanguageId() !== 'sql') return;
  const version = model.getVersionId();
  diagnosticTimer = setTimeout(async () => {
    const markers = await sqlDiagnostics(model.getValue(), props.dialect || 'spark');
    if (serial === diagnosticSerial && !model.isDisposed() && model.getVersionId() === version) monaco.editor.setModelMarkers(model, 'code-intelligence', markers);
  }, 350);
}
async function formatDocument() {
  const model = editor?.getModel();
  if (!model || model.getLanguageId() !== 'sql' || editor.getOption(monaco.editor.EditorOption.readOnly)) return;
  const source = model.getValue(), version = model.getVersionId();
  try {
    const formatted = sqlAdapter.format(source, { dialect: props.dialect, preferences });
    applyFormatIfUnchanged(editor, model, version, source, formatted);
  } catch (error) { console.warn('SQL format failed', error); }
}
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
  editor.updateOptions({ ...editorLanguageOptions(model.getLanguageId(), preferences), readOnly: !file.editable });
  model.updateOptions({ tabSize: preferences.tabSize, insertSpaces: true });
  displayed = key; setting = false; scheduleDiagnostics();
  nextTick(() => editor.layout());
}
onMounted(() => {
  globalThis.MonacoEnvironment = { ...(globalThis.MonacoEnvironment || {}), getWorker: () => new EditorWorker() };
  // Monaco 0.52's occurrence highlighter can reject a pending Delayer on a fast model switch.
  // SQL token coloring remains enabled; avoid that optional asynchronous contribution here.
  editor = monaco.editor.create(host.value, { model: null, automaticLayout: true, occurrencesHighlight:'off', theme: 'vs', fontSize: 14,
    fontFamily: 'Cascadia Code, Consolas, monospace', ...editorLanguageOptions('sql', preferences), scrollBeyondLastLine: false, minimap: { enabled: true }, padding: { top: 14, bottom: 14 } });
  completionProvider = monaco.languages.registerCompletionItemProvider('sql', {
    provideCompletionItems(model, position) {
      if (!model.uri.toString().includes(`/sql-studio/${instance}/`)) return { suggestions: [] };
      const word = model.getWordUntilPosition(position);
      const range = new monaco.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn);
      return { suggestions: sqlAdapter.complete(props.schemaColumns || []).map(item => ({
        label: item.label, insertText: item.label, range,
        kind: item.category === 'field' ? monaco.languages.CompletionItemKind.Field : monaco.languages.CompletionItemKind.Keyword
      })) };
    }
  });
  listener = editor.onDidChangeModelContent(() => { if (!setting && current.value && !props.preview) emit('edit', { id: current.value.id, content: editor.getValue() }); scheduleDiagnostics(); });
  editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => emit('save'));
  editor.addAction({ id: 'code-intelligence.format-sql', label: 'Format SQL', keybindings: [monaco.KeyMod.Shift | monaco.KeyMod.Alt | monaco.KeyCode.KeyF], run: formatDocument });
  activate();
});
watch(() => [props.activeId, props.preview, props.files], activate, { deep: true });
watch(() => props.projectId, () => { editor?.setModel(null); models.forEach(model => model.dispose()); models.clear(); views.clear(); displayed = null; activate(); });
watch(() => props.dialect, scheduleDiagnostics);
onActivated(() => nextTick(() => editor?.layout()));
onBeforeUnmount(() => { clearTimeout(diagnosticTimer); diagnosticSerial++; completionProvider?.dispose(); listener?.dispose(); editor?.dispose(); models.forEach(model => model.dispose()); });
  return { host, current, openFiles, formatDocument, applyPreferences };

  }
};
