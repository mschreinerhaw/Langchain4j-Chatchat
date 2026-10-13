import { computed, ref, watch, onMounted, onActivated, onDeactivated, onBeforeUnmount } from 'vue';
import AppNotice from '../../components/AppNotice.vue';
import AppConfirmDialog from '../../components/AppConfirmDialog.vue';
import SqlProjectTree from '../../components/sql-studio/SqlProjectTree.vue';
import SqlEditorPane from '../../components/sql-studio/SqlEditorPane.vue';
import SqlAssistantPanel from '../../components/sql-studio/SqlAssistantPanel.vue';
import SqlBottomPanel from '../../components/sql-studio/SqlBottomPanel.vue';
import { FolderTree, PanelBottom, Sparkles, Plus, Save, WandSparkles, FileCode2, CircleCheck } from '@lucide/vue';
import * as sql from '../../services/sqlStudioApi';
import { fetchPythonCodeModels, fetchAgentOptions, cancelAgentTask } from '../../services/api';
import { saveWorkspaceDraft, readWorkspaceDraft } from '../utils/workspaceDrafts';
import { dirtySqlFile, mergeSqlFiles, restoreSqlDrafts, assertSqlApplySafe, mergeAppliedSqlFile, terminalSqlTask } from '../utils/sqlStudioState';
import { mappingSchemaColumns } from '../editor/codeIntelligence';
import { clampSqlPanel, sqlAssistantAnswer, sqlConversationPrompt } from '../utils/sqlIdeState';
export default {
  components: { AppNotice, AppConfirmDialog, SqlProjectTree, SqlEditorPane, SqlAssistantPanel, SqlBottomPanel, FolderTree, PanelBottom, Sparkles, Plus, Save, WandSparkles, FileCode2, CircleCheck },
  props: { workspaceContext: Object, tenantId: String, userId: String },
  emits: [],
  setup(props, { emit: emitEvent }) {

const dialects = [{ value:'spark',label:'Spark SQL' },{ value:'hive',label:'Hive' },{ value:'postgresql',label:'PostgreSQL' },{ value:'mysql',label:'MySQL' },{ value:'oracle',label:'Oracle' },{ value:'tsql',label:'T-SQL' },{ value:'trino',label:'Trino' },{ value:'ansi',label:'ANSI SQL' }];
const folders = ref([]), folderOpen = ref(false), folderPath = ref(''), newFileFolder = ref('');
const confirmation = ref(null);
let confirmResolve;
function confirmDelete(title, message) { if (confirmation.value) return Promise.resolve(false); return new Promise(resolve => { confirmation.value = {title,message}; confirmResolve = resolve; }); }
function resolveConfirm(value) { confirmation.value = null; confirmResolve?.(value); confirmResolve = null; }
const projects = ref([]), projectId = ref(''), files = ref([]), references = ref([]), models = ref([]), agents = ref([]);
const dialect = ref('spark'), agentId = ref(''), modelName = ref(''), prompt = ref('');
const executionMode = ref('model');
const libraryOpen = ref(false), libraryKind = ref('MAPPING'), libraryItems = ref([]), librarySelected = ref([]);
const mappingDocuments = ref([]);
const mappingDocumentIds = computed(() => mappingDocuments.value.map(item => item.docId));
const mappingIds = ref([]), referenceIds = ref([]), openIds = ref([]), activeId = ref(''), preview = ref(null);
const generations = ref([]), generationId = ref(''), result = ref(null);
const busy = ref(false), error = ref(''), message = ref(''), newProjectOpen = ref(false), projectName = ref('');
const versions = ref([]), versionFile = ref(null);
const editorPane = ref(null), newFileOpen = ref(false), newFilePath = ref('sql/query.sql');
const activeFile = computed(() => files.value.find(file => file.id === activeId.value));
const taskRunning = computed(() => Boolean(result.value?.task && !terminalSqlTask(result.value.task.status)));
const dirtyCount = computed(() => files.value.filter(dirtySqlFile).length);
const schemaColumns = computed(() => mappingSchemaColumns(files.value, mappingIds.value));
const selectedMappings = computed(() => files.value.filter(file => mappingIds.value.includes(file.id)));
const chatTurns = ref([]), selectedCode = ref(null), problemCount = ref(0), cursor = ref(null);
const layout = ref({ explorer: true, assistant: true, bottom: false, bottomTab: 'problems', explorerWidth: 252, assistantWidth: 336, bottomHeight: 180 });
const ideStyle = computed(() => ({ '--sql-explorer-width': layout.value.explorer ? `${layout.value.explorerWidth}px` : '0px', '--sql-assistant-width': layout.value.assistant ? `${layout.value.assistantWidth}px` : '0px', '--sql-explorer-grip': layout.value.explorer ? '5px' : '0px', '--sql-assistant-grip': layout.value.assistant ? '5px' : '0px' }));
let active = true, pollTimer, draftTimer, loadSerial = 0, initialized = false, resizeCleanup;
const namespace = id => `sql-studio-project-${id}`;
function persist() {
  if (!projectId.value) return true;
  const saved = saveWorkspaceDraft(namespace(projectId.value), props.workspaceContext, {
    openIds: openIds.value, activeId: activeId.value, prompt: prompt.value, mappingDocuments: mappingDocuments.value, mappingIds: mappingIds.value, referenceIds: referenceIds.value,
    chatTurns: chatTurns.value.slice(-30),
    drafts: files.value.filter(dirtySqlFile).map(file => ({ id:file.id, content:file.draft, baseVersion:file.draftBaseVersion }))
  });
  if (!saved) { error.value = '本地草稿存储已满或不可用，请保存文件后再离开工程。'; return false; }
  saveWorkspaceDraft('sql-studio-selection', props.workspaceContext, { projectId: projectId.value });
  return true;
}
function beforeUnload(event) { persist(); if (files.value.some(dirtySqlFile)) { event.preventDefault(); event.returnValue = ''; } }
watch([files, openIds, activeId, prompt, mappingIds, mappingDocuments, referenceIds, chatTurns], () => { clearTimeout(draftTimer); draftTimer = setTimeout(persist, 200); }, { deep:true });
watch(layout, value => saveWorkspaceDraft('sql-studio-ide-layout', props.workspaceContext, value), { deep:true });
function togglePanel(panel) { layout.value[panel] = !layout.value[panel]; }
function resetSize(panel) { if (panel === 'bottom') layout.value.bottomHeight = 180; else layout.value[`${panel}Width`] = panel === 'explorer' ? 252 : 336; }
function startResize(panel, event) {
  if (event.button !== 0) return;
  event.preventDefault(); resizeCleanup?.();
  const start = panel === 'bottom' ? event.clientY : event.clientX;
  const key = panel === 'bottom' ? 'bottomHeight' : `${panel}Width`;
  const initial = layout.value[key];
  const move = next => {
    const delta = (panel === 'assistant' || panel === 'bottom' ? -1 : 1) * ((panel === 'bottom' ? next.clientY : next.clientX) - start);
    layout.value[key] = clampSqlPanel(initial + delta, panel === 'bottom' ? 100 : 180, panel === 'bottom' ? 420 : 520);
  };
  const stop = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', stop); resizeCleanup = null; };
  resizeCleanup = stop; window.addEventListener('pointermove', move); window.addEventListener('pointerup', stop);
}
async function action(fn) { if (busy.value) return; busy.value = true; error.value = ''; try { return await fn(); } catch (ex) { error.value = ex.message || '操作失败'; } finally { busy.value = false; } }
function toggle(list, id) { const index = list.indexOf(id); index < 0 ? list.push(id) : list.splice(index, 1); }
function chooseExecutionMode(mode) { executionMode.value = mode; agentId.value = ''; modelName.value = ''; }
async function chooseLibrary() { await action(async () => { libraryKind.value = 'MAPPING'; libraryItems.value = await sql.sqlLibraryFiles('MAPPING'); librarySelected.value = [...mappingDocumentIds.value]; libraryOpen.value = true; }); }
function importLibrary() {
  mappingDocuments.value = libraryItems.value.filter(item => librarySelected.value.includes(item.docId)).map(item => ({ docId:item.docId, fileName:item.fileName || item.title }));
  libraryOpen.value = false;
  persist();
}
function removeMappingDocument(id) { mappingDocuments.value = mappingDocuments.value.filter(item => item.docId !== id); }
function openFile(id) { if (!openIds.value.includes(id)) openIds.value.push(id); activeId.value = id; preview.value = null; }
function openReference(file) { if (files.value.some(item => item.id === file.id)) openFile(file.id); else preview.value = { path:file.path, content:file.content, reference:true }; }
function closeFile(id) { openIds.value = openIds.value.filter(item => item !== id); if (activeId.value === id) activeId.value = openIds.value.at(-1) || ''; }
function editFile({ id, content }) { const file = files.value.find(item => item.id === id); if (file) file.draft = content; }
function useSelection(selection) { selectedCode.value = selection?.text ? selection : null; if (selectedCode.value) layout.value.assistant = true; }
function useMappingReference(reference) {
  if (!reference?.file) return;
  addFileMention(reference.file);
  const details = [reference.sheet && `Sheet: ${reference.sheet}`, reference.row && `Row: ${reference.row}`, `Field: ${reference.field}`, reference.value && `Value: ${reference.value}`].filter(Boolean).join(', ');
  prompt.value += `[${details}] `;
}
function addFileMention(file) {
  if (!file?.path) return;
  const base = prompt.value.replace(/@[^\s]*$/, '').trimEnd();
  prompt.value = `${base}${base ? ' ' : ''}@${file.path} `;
  if (file.kind === 'MAPPING' && !mappingIds.value.includes(file.id)) mappingIds.value.push(file.id);
  if (file.kind !== 'MAPPING' && !referenceIds.value.includes(file.id)) referenceIds.value.push(file.id);
  layout.value.assistant = true;
}
async function selectProject(id) {
  if (!persist()) return;
  clearTimeout(pollTimer); const serial = ++loadSerial;
  busy.value = true; error.value = '';
  try {
    const [loaded, history, referenceFiles, loadedFolders] = await Promise.all([sql.sqlFiles(id), sql.sqlGenerations(id), sql.sqlReferences(), sql.sqlFolders(id)]);
    if (serial !== loadSerial) return;
    const stored = readWorkspaceDraft(namespace(id), props.workspaceContext);
    folders.value = loadedFolders; newFileFolder.value = '';
    projectId.value = id; files.value = restoreSqlDrafts(mergeSqlFiles(loaded, []), stored);
    references.value = referenceFiles; dialect.value = projects.value.find(project => project.id === id)?.dialect || 'spark';
    openIds.value = (stored?.openIds || []).filter(fileId => files.value.some(file => file.id === fileId));
    activeId.value = openIds.value.includes(stored?.activeId) ? stored.activeId : openIds.value.at(-1) || '';
    mappingDocuments.value = Array.isArray(stored?.mappingDocuments) ? stored.mappingDocuments : [];
    mappingIds.value = (stored?.mappingIds || []).filter(fileId => files.value.some(file => file.id === fileId && file.kind === 'MAPPING'));
    referenceIds.value = (stored?.referenceIds || []).filter(fileId => references.value.some(file => file.id === fileId));
    if (!stored) { const first = files.value.find(file => file.kind === 'SQL') || files.value[0]; if (first) openFile(first.id); }
    prompt.value = stored?.prompt || ''; chatTurns.value = Array.isArray(stored?.chatTurns) ? stored.chatTurns.slice(-30) : [];
    selectedCode.value = null; preview.value = null; generations.value = history; generationId.value = history[0]?.id || ''; result.value = null;
    await refreshGeneration();
  } catch (ex) { error.value = ex.message; } finally { if (serial === loadSerial) busy.value = false; }
}
async function refreshFiles() {
  const id = projectId.value; const loaded = await sql.sqlFiles(id);
  const [referenceFiles, loadedFolders] = await Promise.all([sql.sqlReferences(), sql.sqlFolders(id)]);
  if (id === projectId.value) { folders.value = loadedFolders; files.value = mergeSqlFiles(loaded, files.value); references.value = referenceFiles; }
}
async function createProject() { await action(async () => { const project = await sql.createSqlProject({ name:projectName.value.trim(), dialect:dialect.value }); projects.value.unshift(project); newProjectOpen.value = false; projectName.value = ''; await selectProject(project.id); }); }
async function deleteProject() {
  const current = projects.value.find(item => item.id === projectId.value);
  if (!current || !await confirmDelete('删除工程', `删除工程「${current.name}」及其中全部文件夹、文件和生成记录？此操作无法撤销。${files.value.some(dirtySqlFile) ? '\n工程包含未保存的编辑内容。' : ''}`)) return;
  await action(async () => {
    await sql.deleteSqlProject(current.id);
    projects.value = projects.value.filter(item => item.id !== current.id);
    projectId.value = ''; folders.value = []; files.value = []; references.value = []; generations.value = [];
    openIds.value = []; activeId.value = ''; mappingDocuments.value = []; mappingIds.value = []; referenceIds.value = []; preview.value = null;
    const next = projects.value[0]; if (next) await selectProject(next.id);
    message.value = '工程已删除';
  });
}
async function deleteFile(file) {
  const current = files.value.find(item => item.id === file.id);
  if (!current || !await confirmDelete('删除文件', `删除文件「${current.path}」及其版本历史？${dirtySqlFile(current) ? '\n该文件包含未保存的编辑内容。' : ''}`)) return;
  await action(async () => {
    await sql.deleteSqlFile(projectId.value, current.id, current.version);
    files.value = files.value.filter(item => item.id !== current.id);
    openIds.value = openIds.value.filter(id => id !== current.id);
    if (activeId.value === current.id) activeId.value = openIds.value.at(-1) || '';
    mappingIds.value = mappingIds.value.filter(id => id !== current.id);
    referenceIds.value = referenceIds.value.filter(id => id !== current.id);
    references.value = await sql.sqlReferences();
    message.value = '文件已删除';
  });
}
async function createFolder() { await action(async () => { await sql.createSqlFolder(projectId.value, folderPath.value.trim()); folders.value = await sql.sqlFolders(projectId.value); folderOpen.value = false; message.value = '文件夹已创建，可以在其中新建文件'; }); }
function openNewFolder(parent = '') { folderPath.value = parent ? parent + '/' : ''; folderOpen.value = true; }
async function createFile() { await action(async () => {
  if (!folders.value.includes(newFileFolder.value)) throw new Error('请先创建并选择目标文件夹');
  const name = newFilePath.value.trim();
  if (!name || name.includes('/') || name.includes('\\') || !name.toLowerCase().endsWith('.sql')) throw new Error('请输入以 .sql 结尾的文件名');
  const saved = await sql.saveSqlFile(projectId.value, {path:newFileFolder.value + '/' + name,kind:'SQL',content:'',expectedVersion:0});
  files.value.push({...saved,draft:saved.content,draftBaseVersion:saved.version}); openFile(saved.id); newFileOpen.value=false; message.value='文件已创建';
}); }
function openNewFile(folder) {
  if (!folders.value.length) { message.value='请先新建文件夹，再在文件夹中创建文件'; openNewFolder(); return; }
  newFileFolder.value = typeof folder === 'string' && folders.value.includes(folder) ? folder : (folders.value.includes(newFileFolder.value) ? newFileFolder.value : folders.value[0]);
  newFilePath.value='query.sql'; newFileOpen.value=true;
}
function formatActive() { editorPane.value?.formatDocument(); }
async function saveActive() {
  const file = activeFile.value; if (!file?.editable || preview.value) return;
  await action(async () => {
    const content = file.draft; const id = projectId.value;
    const saved = await sql.saveSqlFile(id, { path:file.path, kind:file.kind, content, expectedVersion:file.draftBaseVersion });
    const index = files.value.findIndex(current => current.id === file.id);
    if (id === projectId.value && index >= 0) files.value[index] = { ...saved, draft:files.value[index].draft === content ? saved.content : files.value[index].draft, draftBaseVersion:saved.version, conflict:false };
    message.value = '文件已保存'; persist();
  });
}
async function upload({ files:uploads, kind }) { await action(async () => { for (const file of uploads) { const fileKind = kind === 'AUTO' ? file.name.toLowerCase().endsWith('.sql') ? 'REFERENCE' : 'MAPPING' : kind; const saved = await sql.uploadSqlInput(projectId.value, file, fileKind); files.value.push({ ...saved, draft:saved.content, draftBaseVersion:saved.version }); if (fileKind === 'MAPPING') mappingIds.value.push(saved.id); openFile(saved.id); } references.value = await sql.sqlReferences(); }); }
async function generate() { await action(async () => {
  if (taskRunning.value) throw new Error('当前任务仍在运行，请等待结果或取消后再发送。');
  if (files.value.some(dirtySqlFile)) throw new Error('请先保存工程中的编辑内容，让生成任务读取一致的文件版本。');
  const id = projectId.value, text = prompt.value.trim();
  if (!text) return;
  const request = sqlConversationPrompt(text, chatTurns.value, selectedCode.value);
  const prepared = await sql.prepareSqlGeneration(id, { prompt:request, dialect:dialect.value, mappingDocumentIds:mappingDocumentIds.value, mappingIds:mappingIds.value, referenceIds:referenceIds.value, agentId:executionMode.value === 'agent' ? agentId.value || null : null, modelName:executionMode.value === 'model' ? modelName.value || null : null });
  chatTurns.value.push({ id:`user:${prepared.generationId}`, role:'user', text, generationId:prepared.generationId });
  chatTurns.value.push({ id:`assistant:${prepared.generationId}`, role:'assistant', text:'正在由 Agent 分析工程上下文…', generationId:prepared.generationId });
  prompt.value = ''; selectedCode.value = null;
  // Persist the context before submission, so a network interruption can be retried idempotently.
  generations.value.unshift({ id:prepared.generationId, taskId:null, applied:false, prepared }); generationId.value = prepared.generationId;
  try { await sql.submitSqlGeneration(id, prepared); } finally { await refreshGeneration(); }
}); }
async function resume() { await action(async () => { const prepared = result.value?.generation.prepared; if (!prepared) return; await sql.submitSqlGeneration(projectId.value, prepared); await refreshGeneration(); }); }
async function selectGeneration(id) { generationId.value = id; preview.value = null; await refreshGeneration(); }
async function refreshGeneration() {
  clearTimeout(pollTimer);
  const id = projectId.value, generation = generationId.value;
  if (!active || !id || !generation) return;
  try {
    const loaded = await sql.sqlGeneration(id, generation);
    if (id !== projectId.value || generation !== generationId.value || !active) return;
    result.value = loaded;
    const turn = chatTurns.value.find(item => item.id === `assistant:${generation}`);
    if (turn) turn.text = sqlAssistantAnswer(loaded);
    if ((loaded.files?.length || loaded.outputError) && !layout.value.bottom) layout.value.bottomTab = loaded.outputError ? 'problems' : 'output';
    if (loaded.task && !terminalSqlTask(loaded.task.status)) pollTimer = setTimeout(refreshGeneration, 1500);
  } catch (ex) { if (active && id === projectId.value && generation === generationId.value) error.value = ex.message; }
}
async function apply() { await action(async () => {
  const candidates = result.value.files; assertSqlApplySafe(candidates, files.value);
  const saved = await sql.applySqlGeneration(projectId.value, generationId.value, candidates.map(file => file.path));
  for (const file of saved) { const index = files.value.findIndex(current => current.id === file.id); const clean = mergeAppliedSqlFile(file, files.value[index]); index < 0 ? files.value.push(clean) : files.value.splice(index,1,clean); }
  preview.value = null; if (saved[0]) openFile(saved[0].id); await refreshGeneration(); references.value = await sql.sqlReferences(); persist(); message.value = `已保存 ${saved.length} 个生成文件`;
}); }
async function cancel() { await action(async () => { await cancelAgentTask(result.value.task.taskId, props.tenantId); await refreshGeneration(); }); }
async function showVersions(file) { await action(async () => { versions.value = await sql.sqlVersions(projectId.value, file.id); versionFile.value = file; }); }
async function download(file) { await action(() => sql.downloadSqlFile(projectId.value, file)); }
async function renameFile({ file, path }) { await action(async () => {
  const current = files.value.find(item => item.id === file.id);
  if (!current || dirtySqlFile(current)) throw new Error('请先保存当前文件，再重命名。');
  const saved = await sql.renameSqlFile(projectId.value, current.id, path, current.version);
  const index = files.value.findIndex(item => item.id === current.id);
  if (index >= 0) files.value[index] = { ...saved, draft:saved.content, draftBaseVersion:saved.version };
  references.value = await sql.sqlReferences();
  message.value = `已重命名为 ${saved.path}`;
}); }
onMounted(async () => {
  window.addEventListener('beforeunload', beforeUnload);
  const savedLayout = readWorkspaceDraft('sql-studio-ide-layout', props.workspaceContext);
  if (savedLayout && typeof savedLayout === 'object') {
    Object.assign(layout.value, savedLayout);
    layout.value.explorerWidth = clampSqlPanel(Number(layout.value.explorerWidth) || 252, 180, 520);
    layout.value.assistantWidth = clampSqlPanel(Number(layout.value.assistantWidth) || 336, 180, 520);
    layout.value.bottomHeight = clampSqlPanel(Number(layout.value.bottomHeight) || 180, 100, 420);
  }
  await action(async () => {
    const loaded = await Promise.allSettled([sql.sqlProjects(), fetchPythonCodeModels(), fetchAgentOptions()]);
    if (loaded[0].status !== 'fulfilled') throw loaded[0].reason;
    projects.value = loaded[0].value; models.value = loaded[1].status === 'fulfilled' ? loaded[1].value : [];
    agents.value = loaded[2].status === 'fulfilled' ? loaded[2].value : [];
    const stored = readWorkspaceDraft('sql-studio-selection', props.workspaceContext);
    const id = projects.value.find(project => project.id === stored?.projectId)?.id || projects.value[0]?.id;
    if (id) await selectProject(id); initialized = true;
  });
});
onActivated(() => { active = true; if (initialized) refreshGeneration(); });
onDeactivated(() => { active = false; clearTimeout(pollTimer); persist(); });
onBeforeUnmount(() => { resolveConfirm(false); active = false; resizeCleanup?.(); clearTimeout(pollTimer); clearTimeout(draftTimer); persist(); window.removeEventListener('beforeunload', beforeUnload); });
  return { confirmation, resolveConfirm, folders, folderOpen, folderPath, newFileFolder, createFolder, openNewFolder, mappingDocuments, removeMappingDocument, dialects, projects, projectId, files, references, models, agents, dialect, agentId, modelName, executionMode, chooseExecutionMode, prompt, mappingIds, schemaColumns, selectedMappings, referenceIds, openIds, activeId, preview, generations, generationId, result, busy, error, message, newProjectOpen, projectName, versions, versionFile, activeFile, action, toggle, openFile, openReference, closeFile, editFile, selectProject, refreshFiles, createProject, deleteProject, createFile, deleteFile, saveActive, upload, generate, resume, selectGeneration, refreshGeneration, apply, cancel, showVersions, download, chatTurns, selectedCode, problemCount, cursor, layout, ideStyle, togglePanel, resetSize, startResize, useSelection, useMappingReference, addFileMention, renameFile, libraryOpen, libraryKind, libraryItems, librarySelected, chooseLibrary, importLibrary, editorPane, newFileOpen, newFilePath, openNewFile, formatActive, taskRunning, dirtyCount };

  }
};
