<template>
  <section class="sql-studio">
    <header class="sql-studio-bar"><div class="sql-studio-brand"><span>SQL</span><div><strong>SQL Studio</strong><small>Mapping 驱动的 SQL 工程</small></div></div>
      <select aria-label="SQL 工程" :value="projectId" :disabled="busy" @change="selectProject($event.target.value)"><option value="" disabled>选择工程</option><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</option></select>
      <button type="button" :disabled="busy" @click="newProjectOpen = true">＋ 新建工程</button>
      <label class="sql-dialect">方言<select v-model="dialect" aria-label="SQL 方言"><option v-for="option in dialects" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>
      <button class="sql-primary" type="button" :disabled="busy || !activeFile?.editable || !!preview" @click="saveActive">保存文件</button>
    </header>
    <p v-if="error" class="sql-studio-notice error" role="alert">{{ error }}<button @click="error = ''" aria-label="关闭错误">×</button></p>
    <p v-if="message" class="sql-studio-notice" role="status">{{ message }}<button @click="message = ''" aria-label="关闭提示">×</button></p>
    <div v-if="projectId" class="sql-studio-grid">
      <SqlProjectTree :files="files" :active-id="activeId" :mapping-ids="mappingIds" :busy="busy" @open="openFile" @mapping="toggle(mappingIds, $event)" @upload="upload" @create="createFile" @refresh="action(refreshFiles)" />
      <SqlEditorPane :project-id="projectId" :files="files" :open-ids="openIds" :active-id="activeId" :preview="preview" @open="openFile" @close="closeFile" @edit="editFile" @save="saveActive" @versions="showVersions" @download="download" @preview-close="preview = null" />
      <SqlAssistantPanel :mapping-count="mappingIds.length" :references="references" :reference-ids="referenceIds" :models="models" :agents="agents" :agent-id="agentId" :model-name="modelName" :prompt="prompt" :busy="busy" :generations="generations" :generation-id="generationId" :result="result"
        @reference="toggle(referenceIds, $event)" @agent="agentId = $event" @model="modelName = $event" @prompt="prompt = $event" @generate="generate" @generation="selectGeneration" @resume="resume" @cancel="cancel" @preview="preview = $event" @apply="apply" />
    </div>
    <div v-else class="sql-studio-empty"><strong>创建你的 SQL 工程</strong><p>上传 Mapping，选择历史 SQL 风格参考，使用现有 Agent 生成并审核工程文件。</p><button class="sql-primary" @click="newProjectOpen = true">新建工程</button></div>
    <Teleport to="body"><div v-if="newProjectOpen" class="sql-dialog-backdrop" @mousedown.self="newProjectOpen = false"><form class="sql-dialog" @submit.prevent="createProject"><h2>新建 SQL 工程</h2><label>工程名称<input v-model="projectName" maxlength="180" autofocus placeholder="例如：销售明细 ETL" /></label><footer><button type="button" @click="newProjectOpen = false">取消</button><button class="sql-primary" :disabled="busy || !projectName.trim()">创建工程</button></footer></form></div></Teleport>
    <Teleport to="body"><div v-if="versionFile" class="sql-dialog-backdrop" @mousedown.self="versionFile = null"><section class="sql-dialog sql-version-dialog"><h2>{{ versionFile.path }} · 保存版本</h2><p>历史版本仅供查看，不覆盖当前编辑。</p><details v-for="version in versions" :key="version.version"><summary>v{{ version.version }}{{ version.sourceTaskId ? ' · Agent 产物' : ' · 用户保存' }}</summary><pre>{{ version.content }}</pre></details><footer><button @click="versionFile = null">关闭</button></footer></section></div></Teleport>
  </section>
</template>
<script setup>
import { computed, ref, watch, onMounted, onActivated, onDeactivated, onBeforeUnmount } from 'vue';
import SqlProjectTree from '../components/sql-studio/SqlProjectTree.vue';
import SqlEditorPane from '../components/sql-studio/SqlEditorPane.vue';
import SqlAssistantPanel from '../components/sql-studio/SqlAssistantPanel.vue';
import * as sql from '../services/sqlStudioApi';
import { fetchPythonCodeModels, fetchAgentOptions, cancelAgentTask } from '../services/api';
import { saveWorkspaceDraft, readWorkspaceDraft } from '../js/utils/workspaceDrafts';
import { dirtySqlFile, mergeSqlFiles, restoreSqlDrafts, assertSqlApplySafe, mergeAppliedSqlFile, terminalSqlTask } from '../js/utils/sqlStudioState';
import '../styles/pages/sql-studio.css';
const props = defineProps({ workspaceContext: Object, tenantId: String, userId: String });
const dialects = [{ value:'spark',label:'Spark SQL' },{ value:'hive',label:'Hive' },{ value:'postgresql',label:'PostgreSQL' },{ value:'mysql',label:'MySQL' },{ value:'oracle',label:'Oracle' },{ value:'tsql',label:'T-SQL' },{ value:'trino',label:'Trino' },{ value:'ansi',label:'ANSI SQL' }];
const projects = ref([]), projectId = ref(''), files = ref([]), references = ref([]), models = ref([]), agents = ref([]);
const dialect = ref('spark'), agentId = ref(''), modelName = ref(''), prompt = ref('');
const mappingIds = ref([]), referenceIds = ref([]), openIds = ref([]), activeId = ref(''), preview = ref(null);
const generations = ref([]), generationId = ref(''), result = ref(null);
const busy = ref(false), error = ref(''), message = ref(''), newProjectOpen = ref(false), projectName = ref('');
const versions = ref([]), versionFile = ref(null);
const activeFile = computed(() => files.value.find(file => file.id === activeId.value));
let active = true, pollTimer, draftTimer, loadSerial = 0, initialized = false;
const namespace = id => `sql-studio-project-${id}`;
function persist() {
  if (!projectId.value) return true;
  const saved = saveWorkspaceDraft(namespace(projectId.value), props.workspaceContext, {
    openIds: openIds.value, activeId: activeId.value, prompt: prompt.value, mappingIds: mappingIds.value, referenceIds: referenceIds.value,
    drafts: files.value.filter(dirtySqlFile).map(file => ({ id:file.id, content:file.draft, baseVersion:file.draftBaseVersion }))
  });
  if (!saved) { error.value = '本地草稿存储已满或不可用，请保存文件后再离开工程。'; return false; }
  saveWorkspaceDraft('sql-studio-selection', props.workspaceContext, { projectId: projectId.value });
  return true;
}
function beforeUnload(event) { persist(); if (files.value.some(dirtySqlFile)) { event.preventDefault(); event.returnValue = ''; } }
watch([files, openIds, activeId, prompt, mappingIds, referenceIds], () => { clearTimeout(draftTimer); draftTimer = setTimeout(persist, 200); }, { deep:true });
async function action(fn) { if (busy.value) return; busy.value = true; error.value = ''; try { return await fn(); } catch (ex) { error.value = ex.message || '操作失败'; } finally { busy.value = false; } }
function toggle(list, id) { const index = list.indexOf(id); index < 0 ? list.push(id) : list.splice(index, 1); }
function openFile(id) { if (!openIds.value.includes(id)) openIds.value.push(id); activeId.value = id; preview.value = null; }
function closeFile(id) { openIds.value = openIds.value.filter(item => item !== id); if (activeId.value === id) activeId.value = openIds.value.at(-1) || ''; }
function editFile({ id, content }) { const file = files.value.find(item => item.id === id); if (file) file.draft = content; }
async function selectProject(id) {
  if (!persist()) return;
  clearTimeout(pollTimer); const serial = ++loadSerial;
  busy.value = true; error.value = '';
  try {
    const [loaded, history, referenceFiles] = await Promise.all([sql.sqlFiles(id), sql.sqlGenerations(id), sql.sqlReferences()]);
    if (serial !== loadSerial) return;
    const stored = readWorkspaceDraft(namespace(id), props.workspaceContext);
    projectId.value = id; files.value = restoreSqlDrafts(mergeSqlFiles(loaded, []), stored);
    references.value = referenceFiles; dialect.value = projects.value.find(project => project.id === id)?.dialect || 'spark';
    openIds.value = (stored?.openIds || []).filter(fileId => files.value.some(file => file.id === fileId));
    activeId.value = openIds.value.includes(stored?.activeId) ? stored.activeId : openIds.value.at(-1) || '';
    mappingIds.value = (stored?.mappingIds || []).filter(fileId => files.value.some(file => file.id === fileId && file.kind === 'MAPPING'));
    referenceIds.value = (stored?.referenceIds || []).filter(fileId => references.value.some(file => file.id === fileId));
    prompt.value = stored?.prompt || ''; preview.value = null; generations.value = history; generationId.value = history[0]?.id || ''; result.value = null;
    await refreshGeneration();
  } catch (ex) { error.value = ex.message; } finally { if (serial === loadSerial) busy.value = false; }
}
async function refreshFiles() {
  const id = projectId.value; const loaded = await sql.sqlFiles(id);
  if (id === projectId.value) { files.value = mergeSqlFiles(loaded, files.value); references.value = await sql.sqlReferences(); }
}
async function createProject() { await action(async () => { const project = await sql.createSqlProject({ name:projectName.value.trim(), dialect:dialect.value }); projects.value.unshift(project); newProjectOpen.value = false; projectName.value = ''; await selectProject(project.id); }); }
async function createFile(path) { await action(async () => { const saved = await sql.saveSqlFile(projectId.value, { path, kind:'SQL', content:'', expectedVersion:0 }); files.value.push({ ...saved, draft:saved.content, draftBaseVersion:saved.version }); openFile(saved.id); }); }
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
async function upload({ files:uploads, kind }) { await action(async () => { for (const file of uploads) { const saved = await sql.uploadSqlInput(projectId.value, file, kind); files.value.push({ ...saved, draft:saved.content, draftBaseVersion:saved.version }); if (kind === 'MAPPING') mappingIds.value.push(saved.id); openFile(saved.id); } references.value = await sql.sqlReferences(); }); }
async function generate() { await action(async () => {
  if (files.value.some(dirtySqlFile)) throw new Error('请先保存工程中的编辑内容，让生成任务读取一致的文件版本。');
  const id = projectId.value;
  const prepared = await sql.prepareSqlGeneration(id, { prompt:prompt.value, dialect:dialect.value, mappingIds:mappingIds.value, referenceIds:referenceIds.value, agentId:agentId.value || null, modelName:modelName.value || null });
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
    if (loaded.task && !terminalSqlTask(loaded.task.status)) pollTimer = setTimeout(refreshGeneration, 1500);
  } catch (ex) { error.value = ex.message; }
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
onMounted(async () => {
  window.addEventListener('beforeunload', beforeUnload);
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
onBeforeUnmount(() => { active = false; clearTimeout(pollTimer); clearTimeout(draftTimer); persist(); window.removeEventListener('beforeunload', beforeUnload); });
</script>
