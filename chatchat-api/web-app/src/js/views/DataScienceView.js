import { markRaw, nextTick } from "vue";
import * as monaco from "monaco-editor/esm/vs/editor/editor.api";
import "monaco-editor/esm/vs/editor/editor.all";
import "monaco-editor/esm/vs/basic-languages/python/python.contribution";
import EditorWorker from "monaco-editor/esm/vs/editor/editor.worker?worker";
import "monaco-editor/min/vs/editor/editor.main.css";
import {
  createPythonAsset,
  executePythonScript,
  fetchMcpPythonEnvironments,
  fetchPythonCodeModels,
  fetchPythonAssistSkills,
  fetchPythonWorkbench,
  runPythonCodeTool,
  uploadPythonDataFile,
  downloadPythonDataFile,
  deletePythonDataFile,
  publishPythonScript,
  fetchPythonScriptTemplate,
  savePythonScript,
  deletePythonScript,
  savePythonScriptFolder,
  deletePythonScriptFolder,
  importPythonSystemExampleData
} from "../../services/api";
import { errorMessage, formatDateTime } from "../utils/uiFormatters";
import { calculateBottomPanelMaximum, parsePythonExecutionParameters } from "../utils/pythonWorkbench";
import { pythonCompletionItems } from "../utils/pythonCompletions";
import { getLanguageAdapter, editorPreferences, editorLanguageOptions, applyFormatIfUnchanged, pythonBlockIndent } from "../editor/codeIntelligence";
import { buildPythonLineDiff } from "../utils/pythonCodeDiff";
import PythonCodeDiff from "../../components/PythonCodeDiff.vue";
import UserSpaceBrowser from "../../components/UserSpaceBrowser.vue";
import CodeChangeReview from "../../components/editor/CodeChangeReview.vue";
import StudioCodePlan from "../../components/editor/StudioCodePlan.vue";
import { requestPlannedPythonAssist } from '../../services/studioCodePlanning';
import { runPythonCoding, cancelPythonCoding, resumePythonCoding } from '../../services/pythonCoding';
import { studioAiLabels, studioAiProgress, pythonProposalSource } from '../utils/studioAi';
import { beginStudioExchange, studioAssistantTurn, updateStudioTurn } from '../utils/studioConversation';
import EditorPreferences from "../../components/editor/EditorPreferences.vue";
import { Search, ChevronRight, ChevronDown, Folder, FileCode2, Plus, X, Play, Save, Settings2, PanelLeft,
  PanelRight, PanelBottom, Maximize2, Minimize2, Sparkles, Send, RefreshCw, ListTree, Box, Terminal,
  CircleCheck, CircleAlert, LoaderCircle, Trash2, Code2, BookOpen, FlaskConical, WandSparkles } from '@lucide/vue';
import { pythonOutline, searchPythonWorkspace, pythonSuggestionIsCurrent } from '../utils/pythonStudio';

globalThis.MonacoEnvironment = { getWorker: () => new EditorWorker() };

const starter = `import json
import os

# Agent 参数通过环境变量传入
params = json.loads(os.environ.get("CHATCHAT_INPUT_JSON", "{}"))

def main(data):
    return {"received": data, "message": "hello from isolated Python"}

print(json.dumps(main(params), ensure_ascii=False))
`;

const parameterReaderExample = `import json
import os

# “运行参数”中的 JSON 对象会完整注入此环境变量
params = json.loads(os.environ.get("CHATCHAT_INPUT_JSON", "{}"))
source_file = params.get("source_file")  # FILE 参数在这里已经是容器内只读路径
limit = int(params.get("limit", 100))
include_detail = bool(params.get("include_detail", False))`;

const defaultInputSchema = `{
  "type": "object",
  "properties": {
    "source_file": { "type": "FILE", "description": "需要分析的用户数据文件" },
    "limit": { "type": "integer", "default": 100 },
    "include_detail": { "type": "boolean", "default": true }
  },
  "required": ["source_file"],
  "additionalProperties": false
}`;

const workspaceLayoutDefaults = { explorer: 238, ai: 330, bottom: 250 };
import { workspaceStorageKey } from "../utils/workspaceContext";
import { saveWorkspaceDraft, readWorkspaceDraft, ownedPythonDraftTabs } from "../utils/workspaceDrafts";
const workspaceLayoutStorageKey = "chatchat.python-studio.layout.v1";

export default {
  name: "DataScienceView",
  components: { UserSpaceBrowser, StudioCodePlan, CodeChangeReview, PythonCodeDiff, EditorPreferences, Search, ChevronRight, ChevronDown, Folder, FileCode2, Plus, X,
    Play, Save, Settings2, PanelLeft, PanelRight, PanelBottom, Maximize2, Minimize2, Sparkles, Send,
    RefreshCw, ListTree, Box, Terminal, CircleCheck, CircleAlert, LoaderCircle, Trash2, Code2, BookOpen, FlaskConical, WandSparkles },
  props: {
    workspaceContext: { type: Object, default: null },
    initialTab: {
      type: String,
      default: "environment"
    }
  },
  emits: ["navigate"],
  data: () => ({
    tab: "environment",
    loading: true,
    busy: false,
    error: "",
    errorTimer: null,
    message: "",
    messageTimer: null,
    draftSaveTimer: null,
    draftRestored: false,
    assets: [],
    scripts: [],
    folders: [],
    systemExamples: [],
    expandedFolders: {},
    systemExamplesOpen: true,
    ideDialog: {
      open: false,
      mode: "confirm",
      title: "",
      message: "",
      value: "",
      placeholder: "",
      confirmText: "确定",
      danger: false,
      resolve: null
    },
    executions: [],
    dataFiles: [],
    selectedDataFileId: "",
    dataQuery: "",
    dataPage: 1,
    dataPageSize: 10,
    dataUploadOpen: false,
    dataUploadFile: null,
    dataUploadForm: { purpose: "", retention: "PERMANENT" },
    environmentCatalog: [],
    assetOpen: false,
    publishOpen: false,
    publishUpdating: false,
    publishBusy: false,
    consoleText: "",
    parametersText: "{}",
    inputSchemaText: defaultInputSchema,
    bottomTab: "console",
    bottomOpen: true,
    outlineOpen: true,
    studioSearchOpen: false,
    studioSearchQuery: '',
    studioSearchMode: 'all',
    studioSearchIndex: 0,
    studioSettingsOpen: false,
    editorFontSize: 14,
    editorMinimap: true,
    pythonProblems: [],
    lintState: 'idle',
    aiMessages: [],
    aiCurrentTurn: null,
    aiFollowOutput: true,
    aiSourceContext: null,
    aiCodingTask: null,
    aiCodingSources: [],
    aiCodingProgress: null,
    aiCodingRequest: null,
    editorTabs: [],
    activeEditorTabKey: "",
    draftSequence: 0,
    explorerQuery: "",
    explorerPage: 1,
    explorerPageSize: 7,
    explorerRefreshing: false,
    editor: null,
    completionProvider: null,
    explorerWidth: workspaceLayoutDefaults.explorer,
    aiWidth: workspaceLayoutDefaults.ai,
    bottomHeight: workspaceLayoutDefaults.bottom,
    resizeState: null,
    environmentQuery: "",
    environmentPage: 1,
    environmentPageSize: 4,
    scriptCatalogQuery: "",
    scriptCatalogPage: 1,
    scriptCatalogPageSize: 10,
    isFullscreen: false,
    explorerOpen: true,
    runState: "idle",
    runFeedback: null,
    savedSource: starter,
    savedFileName: "analysis.py",
    savedFolderId: "",
    cursorPosition: { lineNumber: 1, column: 1 },
    aiOpen: true,
    aiPrompt: "",
    aiAction: "generate",
    aiRequestAction: 'generate',
    aiReviewOpen: false,
    aiBusy: false,
    aiSuggestion: null,
    aiDiff: null,
    aiChangeRequest: "",
    aiSelection: null,
    aiStage: "idle",
    aiCodePlan: null,
    aiProgressStep: 0,
    aiElapsedMs: 0,
    aiAppliedInfo: null,
    aiProgressTimer: null,
    aiDecoration: null,
    aiModels: [],
    aiModel: "",
    aiSkills: [],
    aiSkillIds: [],
    aiSkillPickerOpen: false,
    aiSkillSearchQuery: "",
    aiSkillCategoryFilter: "ALL",
    aiSkillDraftIds: [],
    aiExamples: [
      "读取 CSV 并按部门汇总金额",
      "校验输入字段并返回错误明细",
      "将 DataFrame 转成 Agent 可读的 JSON",
      "修复当前脚本的异常处理"
    ],
    assetForm: { name: "", description: "", environmentId: "" },
    form: {
      id: "",
      assetId: "",
      folderId: "",
      fileName: "analysis.py",
      title: "",
      sourceCode: starter,
      status: "DRAFT"
    },
    publishForm: {
      templateName: "",
      scenario: "",
      description: "",
      keywords: "",
      domain: "",
      version: "1.0.0",
      inputSchema: defaultInputSchema,
      outputSchema: '{"type":"object"}'
    }
  }),
  computed: {
    aiConversationMessages() {
      if (this.aiCurrentTurn && (this.aiBusy || this.aiSuggestion) && !this.aiMessages.some(turn=>turn.id===this.aiCurrentTurn.id)) return [...this.aiMessages,this.aiCurrentTurn];
      return this.aiMessages;
    },
    scriptOutline() { return pythonOutline(this.form.sourceCode); },
    activePythonAsset() { return this.assets.find(asset => asset.id === this.form.assetId); },
    pythonVersionLabel() { return this.activePythonAsset?.pythonVersion || '版本未报告'; },
    aiIsTextAnswer() { return ['explain','ask'].includes(this.aiSuggestion?.action); },
    aiOperationLabel() { return studioAiLabels[this.aiSuggestion?.action || (this.aiBusy ? this.aiRequestAction : this.aiAction)] || '代码生成'; },
    aiProgressLabel() { return this.aiStage==='planning' ? '正在制定代码协作计划' : studioAiProgress[this.aiRequestAction] || '正在处理请求'; },
    aiReviewFiles() {
      if(!this.aiSuggestion || this.aiIsTextAnswer)return [];
      if(this.aiSuggestion.files)return this.aiSuggestion.files.map(file=>({path:file.path,
        before:this.aiCodingSources.find(source=>source.path===file.path)?.content || '',after:file.content,
        isNew:!this.aiCodingSources.some(source=>source.path===file.path)}));
      const isNew=this.aiSuggestion.action==='test';
      return [{path:isNew ? `test_${(this.aiSourceContext?.fileName || 'script.py').replace(/\.py$/i,'')}.py` : this.aiSourceContext?.fileName || this.form.fileName,
        before:isNew ? '' : this.aiSourceContext?.source || '', after:pythonProposalSource(this.aiSourceContext,this.aiSuggestion),isNew}];
    },
    aiSuggestionStale() { return Boolean(this.aiSuggestion && !this.aiIsTextAnswer && !pythonSuggestionIsCurrent(this.aiSourceContext, this.activeEditorTabKey, this.form.sourceCode) && this.aiStage !== 'applied'); },
    studioCommands() { return [
      {id:'save',label:'保存当前文件',shortcut:'Ctrl S',keywords:'save'},
      {id:'run',label:'运行当前脚本',shortcut:'Ctrl Enter',keywords:'run'},
      {id:'newScript',label:'新建 Python 文件',keywords:'new'},
      {id:'formatDocument',label:'格式化当前文件',shortcut:'Shift Alt F',keywords:'format'},
      {id:'toggleAi',label:'切换 AI 助手',keywords:'assistant'},
      {id:'toggleExplorer',label:'切换资源管理器',keywords:'explorer'},
      {id:'toggleBottom',label:'切换运行面板',keywords:'output'}
    ]; },
    studioSearchResults() {
      const scripts = this.scripts.map(script => script.id === this.form.id ? {...script,sourceCode:this.form.sourceCode} : script);
      return searchPythonWorkspace(scripts,this.studioSearchQuery,this.studioCommands,this.studioSearchMode);
    },
    currentSectionLabel() {
      return {
        environment: "Python 环境",
        develop: "Python 开发",
        data: "用户空间",
        scripts: "用户空间"
      }[this.tab] || "Python 环境";
    },
    readyAssets() {
      return this.assets.filter((asset) => asset.status === "READY");
    },
    selectedEnvironment() {
      return this.environmentCatalog.find((env) => env.id === this.assetForm.environmentId);
    },
    workspaceStyle() {
      return {
        "--explorer-width": this.explorerOpen ? `${this.explorerWidth}px` : "0px",
        "--ai-width": this.aiOpen ? `${this.aiWidth}px` : "0px",
        "--bottom-height": this.bottomOpen ? `${this.bottomHeight}px` : "34px",
        "--bottom-resizer-size": this.bottomOpen ? "9px" : "0px"
      };
    },
    canPublish() {
      return this.form.id && this.form.status === "TESTED";
    },
    dirty() {
      return (
        this.form.sourceCode !== this.savedSource ||
        String(this.form.fileName || "").trim() !== this.savedFileName ||
        String(this.form.folderId || "") !== this.savedFolderId
      );
    },
    filteredEnvironmentAssets() {
      const query = this.environmentQuery.trim().toLowerCase();
      return query
        ? this.assets.filter((asset) =>
            `${asset.name || ""} ${asset.description || ""} ${asset.pythonVersion || ""} ${
              asset.status || ""
            }`
              .toLowerCase()
              .includes(query)
          )
        : this.assets;
    },
    environmentPageCount() {
      return Math.max(
        1,
        Math.ceil(this.filteredEnvironmentAssets.length / this.environmentPageSize)
      );
    },
    environmentCurrentPage() {
      return Math.min(this.environmentPage, this.environmentPageCount);
    },
    pagedEnvironmentAssets() {
      const start = (this.environmentCurrentPage - 1) * this.environmentPageSize;
      return this.filteredEnvironmentAssets.slice(start, start + this.environmentPageSize);
    },
    filteredDataFiles() {
      const query = this.dataQuery.trim().toLowerCase();
      return query ? this.dataFiles.filter((file) => `${file.fileName || ""} ${file.fileType || ""} ${file.status || ""}`.toLowerCase().includes(query)) : this.dataFiles;
    },
    dataPageCount() { return Math.max(1, Math.ceil(this.filteredDataFiles.length / this.dataPageSize)); },
    dataCurrentPage() { return Math.min(this.dataPage, this.dataPageCount); },
    pagedDataFiles() { const start = (this.dataCurrentPage - 1) * this.dataPageSize; return this.filteredDataFiles.slice(start, start + this.dataPageSize); },
    filteredScripts() {
      const query = this.explorerQuery.trim().toLowerCase();
      return query
        ? this.scripts.filter((script) =>
            `${script.fileName || ""} ${script.title || ""} ${script.status || ""}`
              .toLowerCase()
              .includes(query)
          )
        : this.scripts;
    },
    explorerFolders() {
      const groups = this.folders.map((folder) => ({
        ...folder,
        scripts: this.filteredScripts.filter((script) => script.folderId === folder.id)
      }));
      const unfiled = this.filteredScripts.filter((script) => !script.folderId || !this.folders.some((folder) => folder.id === script.folderId));
      return unfiled.length ? [...groups, { id: "", name: "未分类", scripts: unfiled, virtual: true }] : groups;
    },
    explorerPageCount() {
      return Math.max(1, Math.ceil(this.filteredScripts.length / this.explorerPageSize));
    },
    explorerCurrentPage() {
      return Math.min(this.explorerPage, this.explorerPageCount);
    },
    pagedExplorerScripts() {
      const start = (this.explorerCurrentPage - 1) * this.explorerPageSize;
      return this.filteredScripts.slice(start, start + this.explorerPageSize);
    },
    filteredCatalogScripts() {
      const query = this.scriptCatalogQuery.trim().toLowerCase();
      return query
        ? this.scripts.filter((script) =>
            `${script.fileName || ""} ${script.title || ""} ${script.status || ""} ${this.assetName(
              script.assetId
            )}`
              .toLowerCase()
              .includes(query)
          )
        : this.scripts;
    },
    scriptCatalogPageCount() {
      return Math.max(
        1,
        Math.ceil(this.filteredCatalogScripts.length / this.scriptCatalogPageSize)
      );
    },
    scriptCatalogCurrentPage() {
      return Math.min(this.scriptCatalogPage, this.scriptCatalogPageCount);
    },
    pagedCatalogScripts() {
      const start = (this.scriptCatalogCurrentPage - 1) * this.scriptCatalogPageSize;
      return this.filteredCatalogScripts.slice(start, start + this.scriptCatalogPageSize);
    },
    codeLines() {
      return Math.max(1, String(this.form.sourceCode || "").split("\n").length);
    },
    codeChars() {
      return String(this.form.sourceCode || "").length;
    },
    runStateLabel() {
      return (
        { idle: "等待运行", running: "执行中", succeeded: "执行成功", failed: "执行失败" }[
          this.runState
        ] || this.runState
      );
    },
    aiSuggestionLines() {
      return this.aiSuggestion?.code ? String(this.aiSuggestion.code).split("\n").length : 0;
    },
    aiShowsDiff() {
      return !this.aiIsTextAnswer && Boolean(this.aiDiff);
    },
    aiChangeScope() {
      if (!this.aiSelection) return "当前完整脚本";
      const start = this.aiSelection.startLineNumber;
      const end = this.aiSelection.endLineNumber;
      return start === end ? `选中代码 · 第 ${start} 行` : `选中代码 · 第 ${start}-${end} 行`;
    },
    aiApplyLabel() {
      if (this.aiSuggestion?.action === 'test') return '新建测试文件';
      if (this.aiSuggestion?.replaceSelection && this.aiSelection) return "替换选中代码";
      if (this.aiSuggestion?.action === "continue") return "插入到光标";
      return "替换当前脚本";
    },
    selectedAiModelLabel() {
      return (
        this.aiModels.find((model) => model.value === this.aiModel)?.label ||
        this.aiModel ||
        "系统默认模型"
      );
    },
    selectedAiSkills() {
      const selected = new Set(this.aiSkillIds);
      return this.aiSkills.filter((skill) => selected.has(skill.id));
    },
    aiSkillCategoryOptions() {
      return [...new Set(this.aiSkills.map((skill) => String(skill.category || "未分类").trim()).filter(Boolean))]
        .sort((left, right) => left.localeCompare(right, "zh-CN"));
    },
    filteredAiSkills() {
      const query = this.aiSkillSearchQuery.trim().toLowerCase();
      return this.aiSkills.filter((skill) => {
        const category = String(skill.category || "未分类").trim() || "未分类";
        if (this.aiSkillCategoryFilter !== "ALL" && category !== this.aiSkillCategoryFilter) return false;
        if (!query) return true;
        return `${skill.name || ""} ${category} ${skill.description || ""}`.toLowerCase().includes(query);
      });
    },
    aiSkillSelectionLabel() {
      return this.aiSkillIds.length ? `已选 ${this.aiSkillIds.length}/8` : "选择已发布技能";
    },
    parameterValidation() {
      try {
        const value = parsePythonExecutionParameters(this.parametersText);
        const keys = Object.keys(value);
        return {
          valid: true,
          text: keys.length
            ? `JSON 有效，将传入 ${keys.length} 个字段：${keys.join("、")}`
            : this.selectedDataFileId
              ? "JSON 有效；运行时会自动把所选数据绑定到 source_file"
              : "JSON 有效；不传业务字段，脚本将使用 Schema 或代码默认值"
        };
      } catch (error) {
        return { valid: false, text: error?.message || "请输入合法的 JSON 对象" };
      }
    }
  },
  watch: {
    aiMessages: { deep:true, handler() { this.scrollAiMessages(); } },
    aiBusy() { this.scrollAiMessages(); },
    initialTab: {
      immediate: true,
      handler(value) {
        if (["environment", "develop", "data", "scripts"].includes(value)) this.tab = value;
      }
    },
    environmentQuery() {
      this.environmentPage = 1;
    },
    explorerQuery() {
      this.explorerPage = 1;
    },
    scriptCatalogQuery() {
      this.scriptCatalogPage = 1;
    },
    dataQuery() { this.dataPage = 1; },
    tab(value) {
      if (value !== "develop") return;
      if (!this.editorTabs.length) {
        if (this.scripts[0]) this.selectScript(this.scripts[0], false);
        else this.newScript(false);
      }
      nextTick(() => this.initializeEditor());
    },
    form: {
      deep: true,
      handler(value) {
        const activeTab = this.editorTabs.find((item) => item.key === this.activeEditorTabKey);
        if (activeTab) activeTab.form = { ...value };
        this.scheduleWorkspaceDraft();
      }
    },
    "form.sourceCode"(value) {
      const model = this.editor?.getModel();
      if (model && model.getValue() !== value) model.setValue(value || "");
    }
  },
  mounted() {
    this.restoreWorkspaceLayout();
    this.load();
    window.addEventListener("beforeunload", this.persistWorkspaceDraft);
    window.addEventListener('keydown', this.studioHandleKeydown);
  },
  deactivated() { this.persistWorkspaceDraft(); },
  activated() {
    if (this.tab === "develop")
      nextTick(() => {
        this.initializeEditor();
        this.editor?.layout();
      });
  },
  beforeUnmount() {
    this.persistWorkspaceDraft();
    clearTimeout(this.draftSaveTimer);
    window.removeEventListener("beforeunload", this.persistWorkspaceDraft);
    window.removeEventListener('keydown', this.studioHandleKeydown);
    this.stopAiProgress();
    this.stopPaneResize();
    this.clearErrorTimer();
    this.clearMessageTimer();
    this.disposeEditor();
    this.finishIdeDialog(false);
  },
  methods: {
    openStudioSearch(mode = 'all') {
      this.studioSearchMode = mode; this.studioSearchQuery = mode === 'commands' ? '> ' : '';
      this.studioSearchIndex = 0; this.studioSearchOpen = true;
      nextTick(() => this.$refs.studioSearch?.focus());
    },
    studioHandleKeydown(event) {
      if (this.tab !== 'develop' || this.ideDialog.open || this.publishOpen || this.assetOpen) return;
      if (event.key === 'Escape') { this.studioSearchOpen = false; this.studioSettingsOpen = false; return; }
      if ((event.ctrlKey || event.metaKey) && ['p','k'].includes(event.key.toLowerCase())) {
        event.preventDefault(); this.openStudioSearch(event.shiftKey ? 'commands' : event.key.toLowerCase() === 'p' ? 'files' : 'all');
      }
    },
    async chooseStudioResult(item) {
      if (!item || this.busy) return;
      this.studioSearchOpen = false;
      if (item.kind === 'command') { await this[item.id]?.(); return; }
      this.selectScript(item.script);
      nextTick(() => this.jumpToPythonLine(item.line));
    },
    moveStudioSearch(delta) { this.studioSearchIndex = Math.min(Math.max(0,this.studioSearchIndex + delta),this.studioSearchResults.length - 1); },
    jumpToPythonLine(line) {
      const row = Math.max(1,Number(line) || 1);
      this.editor?.setPosition({lineNumber:row,column:1}); this.editor?.revealLineInCenter(row); this.editor?.focus();
    },
    updateStudioEditor() { this.editor?.updateOptions({fontSize:this.editorFontSize,minimap:{enabled:this.editorMinimap}}); },
    activateStudioModel(editorTab) {
      if (!this.editor || !this.activeEditorTabKey) return;
      this._studioModels ||= new Map();
      this._studioModelNonce ||= `${Date.now()}-${Math.random().toString(36).slice(2)}`;
      let model = this._studioModels.get(this.activeEditorTabKey);
      if (!model) {
        model = monaco.editor.createModel(this.form.sourceCode || '', 'python', monaco.Uri.parse(`inmemory://python-studio/${this._studioModelNonce}/${encodeURIComponent(this.activeEditorTabKey)}.py`));
        this._studioModels.set(this.activeEditorTabKey,model);
      }
      if (this.editor.getModel() !== model) {
        this.editor.setModel(model);
        if (editorTab?.viewState) this.editor.restoreViewState(editorTab.viewState);
      }
    },
    chooseAiMode(action) { if (!this.aiBusy && action!==this.aiAction) { this.aiAction = action; this.resetAiSuggestion(); } },
    confirmAiReview() { if(!this.aiReviewOpen || this.aiBusy || this.aiSuggestionStale)return; this.applyAiSuggestion(); this.aiReviewOpen=false; },
    scheduleWorkspaceDraft() {
      clearTimeout(this.draftSaveTimer);
      this.draftSaveTimer = setTimeout(() => this.persistWorkspaceDraft(), 300);
    },
    persistWorkspaceDraft() {
      if (!this.draftRestored) return;
      this.captureActiveEditorTab();
      saveWorkspaceDraft("chatchat.pythonDraft.v1", this.workspaceContext, {
        tabs: this.editorTabs.map(({ key, form, parametersText, inputSchemaText }) =>
          ({ key, form, parametersText, inputSchemaText })), activeKey: this.activeEditorTabKey, tab: this.tab,
        selectedDataFileId: this.selectedDataFileId
      });
    },
    restoreWorkspaceDraft() {
      if (this.draftRestored) return;
      this.draftRestored = true;
      const stored = readWorkspaceDraft("chatchat.pythonDraft.v1", this.workspaceContext);
      const tabs = ownedPythonDraftTabs(stored?.tabs, this.scripts, this.assets);
      if (tabs.length) {
        this.editorTabs = tabs;
        const active = tabs.find((tab) => tab.key === stored.activeKey) || tabs[0];
        this.activateEditorTab(active, { navigate: false });
      }
      if (this.dataFiles.some((file) => file.id === stored?.selectedDataFileId)) this.selectedDataFileId = stored.selectedDataFileId;
    },
    navigateToSection(section) {
      const routes = {
        environment: "dataScienceEnvironment",
        develop: "dataScienceDevelop",
        data: "dataScienceData",
        scripts: "dataScienceData"
      };
      if (!routes[section]) return;
      this.tab = section;
      this.$emit("navigate", routes[section]);
    },
    useUserSpaceData(file) {
      if(!this.dataFiles.some(item=>item.id===file.id))this.dataFiles.push(file);
      this.selectedDataFileId=file.id;
      this.navigateToSection('develop');
      this.showTransientMessage(`已选择用户空间数据 ${file.fileName}，可在运行参数中绑定`);
    },
    openUserSpaceScript(file) {
      this.newScript();
      if(/^[A-Za-z0-9][A-Za-z0-9_.-]{0,170}\.py$/.test(file.fileName))this.form.fileName=file.fileName;
      this.form.sourceCode=file.sourceCode;
      this.showTransientMessage('已在 Python 开发中打开副本，保存后成为开发脚本');
    },
    async load(silent = false) {
      const background = silent === true;
      if (!background) this.loading = true;
      this.error = "";
      try {
        const [data, environments, models, skills] = await Promise.all([
          fetchPythonWorkbench(),
          fetchMcpPythonEnvironments(),
          fetchPythonCodeModels(),
          fetchPythonAssistSkills()
        ]);
        this.assets = data?.assets || [];
        this.folders = data?.folders || [];
        this.scripts = data?.scripts || [];
        this.systemExamples = data?.systemExamples || [];
        for (const folder of this.folders) if (this.expandedFolders[folder.id] === undefined) this.expandedFolders[folder.id] = true;
        this.executions = data?.executions || [];
        this.dataFiles = data?.dataFiles || [];
        if (!this.dataFiles.some((file) => file.id === this.selectedDataFileId && file.status === "AVAILABLE"))
          this.selectedDataFileId = "";
        this.environmentCatalog = environments || [];
        this.aiModels = models || [];
        this.aiSkills = Array.isArray(skills) ? skills : [];
        this.aiSkillIds = this.aiSkillIds.filter((id) => this.aiSkills.some((skill) => skill.id === id));
        this.aiSkillDraftIds = this.aiSkillDraftIds.filter((id) => this.aiSkills.some((skill) => skill.id === id));
        if (!this.aiModels.some((model) => model.value === this.aiModel))
          this.aiModel =
            this.aiModels.find((model) => model.defaultModel)?.value ||
            this.aiModels[0]?.value ||
            "";
        if (!this.assetForm.environmentId && this.environmentCatalog[0])
          this.assetForm.environmentId = this.environmentCatalog[0].id;
        if (!this.form.assetId && this.readyAssets[0]) this.form.assetId = this.readyAssets[0].id;
        if (this.tab === "develop" && !this.editorTabs.length) {
          if (this.scripts[0]) this.selectScript(this.scripts[0], false);
          else this.newScript(false);
        }
        this.restoreWorkspaceDraft();
      } catch (error) {
        this.showTransientError(errorMessage(error, "工作台加载失败"));
      } finally {
        if (!background) this.loading = false;
        if (this.tab === "develop")
          nextTick(() => {
            this.initializeEditor();
            this.editor?.layout();
          });
      }
    },
    async refreshExplorer() {
      if (this.explorerRefreshing) return;
      this.explorerRefreshing = true;
      this.error = "";
      try {
        const data = await fetchPythonWorkbench();
        this.folders = data?.folders || [];
        this.scripts = data?.scripts || [];
        this.systemExamples = data?.systemExamples || [];
        for (const folder of this.folders)
          if (this.expandedFolders[folder.id] === undefined)
            this.expandedFolders[folder.id] = true;
      } catch (error) {
        this.showTransientError(errorMessage(error, "脚本文件列表刷新失败"));
      } finally {
        this.explorerRefreshing = false;
      }
    },
    initializeEditor() {
      if (!this.$refs.codeEditor) return;
      if (this.editor) {
        const node = this.editor.getDomNode?.();
        if (node?.isConnected && this.$refs.codeEditor.contains(node)) {
          this.editor.layout();
          return;
        }
        this.disposeEditor();
      }
      monaco.editor.defineTheme("chatchat-python", {
        base: "vs",
        inherit: true,
        rules: [
          { token: "comment", foreground: "6B8E6B" },
          { token: "keyword", foreground: "7C3AED" },
          { token: "string", foreground: "B45309" }
        ],
        colors: {
          "editor.background": "#ffffff",
          "editorLineNumber.foreground": "#a3aec2",
          "editorLineNumber.activeForeground": "#315b9a",
          "editor.selectionBackground": "#dbeafe",
          "editorCursor.foreground": "#2563eb",
          "editorIndentGuide.background1": "#edf1f7"
        }
      });
      this.completionProvider = markRaw(monaco.languages.registerCompletionItemProvider("python", {
        triggerCharacters: ["."],
        provideCompletionItems: (model, position) => {
          const word = model.getWordUntilPosition(position);
          const range = new monaco.Range(
            position.lineNumber,
            word.startColumn,
            position.lineNumber,
            word.endColumn
          );
          return {
            suggestions: pythonCompletionItems(
              model.getValue(),
              model.getLineContent(position.lineNumber).slice(0, position.column - 1),
              this.dataFiles
            ).map((completion) => ({
              label: completion.label,
              range,
              kind: ({
                class: monaco.languages.CompletionItemKind.Class,
                file: monaco.languages.CompletionItemKind.File,
                function: monaco.languages.CompletionItemKind.Function,
                keyword: monaco.languages.CompletionItemKind.Keyword,
                method: monaco.languages.CompletionItemKind.Method,
                module: monaco.languages.CompletionItemKind.Module,
                snippet: monaco.languages.CompletionItemKind.Snippet,
                variable: monaco.languages.CompletionItemKind.Variable
              })[completion.category] || monaco.languages.CompletionItemKind.Text,
              insertText: completion.insertText,
              insertTextRules: completion.snippet
                ? monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet
                : undefined,
              detail: completion.detail,
              sortText: completion.category === "variable" || completion.category === "function" ? "0" : "1"
            }))
          };
        }
      }));
      this.editor = markRaw(
        monaco.editor.create(this.$refs.codeEditor, {
          value: this.form.sourceCode || "",
          language: "python",
          theme: "chatchat-python",
          automaticLayout: true,
          occurrencesHighlight: 'off',
          fontSize: 14,
          readOnly: false,
          domReadOnly: false,
          fontFamily: "JetBrains Mono, Cascadia Code, Consolas, monospace",
          lineHeight: 23,
          minimap: { enabled: true, scale: 0.8 },
          padding: { top: 16, bottom: 16 },
          smoothScrolling: true,
          cursorSmoothCaretAnimation: "on",
          formatOnPaste: true,
          renderWhitespace: "selection",
          bracketPairColorization: { enabled: true },
          guides: { bracketPairs: true, indentation: true },
          suggest: {
            showWords: true,
            showSnippets: true,
            showMethods: true,
            showFunctions: true,
            showVariables: true,
            preview: true,
            snippetsPreventQuickSuggestions: false
          },
          suggestOnTriggerCharacters: true,
          acceptSuggestionOnEnter: "off",
          tabCompletion: "on",
          wordBasedSuggestions: "currentDocument",
          quickSuggestions: { other: true, comments: false, strings: false },
          ...editorLanguageOptions("python", editorPreferences(this.workspaceContext, "python")),
          scrollBeyondLastLine: false,
          stickyScroll: { enabled: true }
        })
      );
      this.editor.onDidChangeModelContent(() => {
        this.form.sourceCode = this.editor.getValue();
        this.scheduleRuffDiagnostics();
      });
      const initialModel = this.editor.getModel();
      this.activateStudioModel(this.editorTabs.find(item => item.key === this.activeEditorTabKey));
      if (initialModel !== this.editor.getModel()) initialModel?.dispose();
      this.editor.onDidChangeCursorPosition((event) => {
        this.cursorPosition = event.position;
      });
      this.pythonEnterListener = this.editor.onKeyDown(event => {
        if (event.keyCode !== monaco.KeyCode.Enter || event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return;
        const model = this.editor?.getModel(), selection = this.editor?.getSelection();
        if (!model || !selection || !selection.isEmpty()) return;
        const line = model.getLineContent(selection.startLineNumber).slice(0, selection.startColumn - 1);
        const indent = pythonBlockIndent(line, editorPreferences(this.workspaceContext, 'python').tabSize);
        if (indent == null) return;
        event.preventDefault(); event.stopPropagation();
        this.editor.executeEdits('python-smart-enter', [{ range: selection, text: `\n${indent}`, forceMoveMarkers: true }], () => {
          const nextLine = selection.startLineNumber + 1, column = indent.length + 1;
          return [new monaco.Selection(nextLine, column, nextLine, column)];
        });
      });
      this.editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => this.save());
      this.editor.addAction({ id: 'code-intelligence.format-python', label: 'Format Python', keybindings: [monaco.KeyMod.Shift | monaco.KeyMod.Alt | monaco.KeyCode.KeyF], run: () => this.formatDocument() });
      this.editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => this.run());
      this.editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyK, () => {
        this.openStudioSearch();
      });
      this.scheduleRuffDiagnostics();
    },
    scheduleRuffDiagnostics() {
      clearTimeout(this._ruffTimer);
      const model = this.editor?.getModel();
      const assetId = this.form.assetId;
      const version = model?.getVersionId();
      const serial = this._ruffSerial = (this._ruffSerial || 0) + 1;
      this.pythonProblems = []; this.lintState = 'checking';
      if (!model || !assetId || model.getValue().length > 200_000) {
        if (model) monaco.editor.setModelMarkers(model, 'ruff', []);
        this.lintState = 'unavailable'; return;
      }
      this._ruffTimer = setTimeout(async () => {
        try {
          const result = await runPythonCodeTool('ruff-check', assetId, model.getValue());
          if (serial !== this._ruffSerial || model !== this.editor?.getModel() || version !== model.getVersionId()) return;
          if (![0, 1].includes(result.exitCode)) { this.lintState = 'unavailable'; return; }
          const problems = JSON.parse(result.stdout || '[]');
          this.pythonProblems = problems; this.lintState = 'ready';
          monaco.editor.setModelMarkers(model, 'ruff', problems.map(problem => ({
            startLineNumber: problem.location.row,
            startColumn: problem.location.column,
            endLineNumber: problem.end_location?.row || problem.location.row,
            endColumn: problem.end_location?.column || problem.location.column + 1,
            message: `${problem.code}: ${problem.message}`,
            severity: monaco.MarkerSeverity.Warning
          })));
        } catch { if (serial === this._ruffSerial) this.lintState = 'unavailable'; }
      }, 650);
    },
    applyEditorPreferences(preferences) {
      this.editor?.updateOptions(editorLanguageOptions('python', preferences));
      this.editor?.getModel()?.updateOptions({ tabSize: preferences.tabSize, insertSpaces: true });
    },
    disposeEditor() {
      this.pythonEnterListener?.dispose();
      this.pythonEnterListener = null;
      clearTimeout(this._ruffTimer);
      this._ruffSerial = (this._ruffSerial || 0) + 1;
      this.aiDecoration?.clear();
      this.aiDecoration = null;
      this.completionProvider?.dispose();
      this.completionProvider = null;
      this.editor?.dispose();
      this._studioModels?.forEach(model => model.dispose()); this._studioModels?.clear();
      this.editor = null;
    },
    openAssetDialog() {
      this.assetOpen = true;
      this.error = "";
    },
    openDataUpload() { this.dataUploadFile = null; this.dataUploadForm = { purpose: "", retention: "PERMANENT" }; this.dataUploadOpen = true; },
    chooseDataFile(event) { this.dataUploadFile = event.target.files?.[0] || null; },
    dropDataFile(event) { this.dataUploadFile = event.dataTransfer?.files?.[0] || null; },
    async uploadData() {
      if (!this.dataUploadFile) { this.showTransientError("请选择需要上传的数据文件"); return; }
      await this.action(async () => { const form = new FormData(); form.append("file", this.dataUploadFile); form.append("purpose", this.dataUploadForm.purpose); form.append("retention", this.dataUploadForm.retention); await uploadPythonDataFile(form); this.dataUploadOpen = false; this.showTransientMessage("数据已加密传输到 MCP，可在 Python 中只读访问"); await this.load(true); });
    },
    async copyDataPath(file) {
      try {
        await this.copyTextToClipboard(file.pythonPath);
        this.clearErrorTimer();
        this.error = "";
        this.showTransientMessage("Python 路径已复制", 2000);
      } catch {
        this.showTransientError("浏览器限制了剪贴板访问，请选中 Python 路径后手动复制", 4000);
      }
    },
    async copyTextToClipboard(value) {
      const text = String(value || "");
      if (!text) throw new Error("没有可复制的内容");
      try {
        if (globalThis.navigator?.clipboard?.writeText) {
          await globalThis.navigator.clipboard.writeText(text);
          return;
        }
      } catch {
        // HTTP 页面或浏览器权限策略可能拒绝 Clipboard API，继续使用兼容方案。
      }
      const textarea = document.createElement("textarea");
      textarea.value = text;
      textarea.setAttribute("readonly", "");
      textarea.style.position = "fixed";
      textarea.style.opacity = "0";
      textarea.style.pointerEvents = "none";
      document.body.appendChild(textarea);
      try {
        textarea.focus();
        textarea.select();
        textarea.setSelectionRange(0, text.length);
        if (!document.execCommand?.("copy")) throw new Error("浏览器不支持兼容复制");
      } finally {
        textarea.remove();
      }
    },
    async downloadData(file) { await this.action(() => downloadPythonDataFile(file.id, file.fileName)); },
    async removeData(file) { if (!await this.openIdeConfirm({ title: "删除数据文件", message: `确认删除“${file.fileName}”？删除后脚本将无法读取。`, confirmText: "删除", danger: true })) return; await this.action(async () => { await deletePythonDataFile(file.id); this.showTransientMessage("数据文件已删除"); await this.load(true); }); },
    formatBytes(value) { const bytes = Number(value || 0); if (bytes < 1024) return `${bytes} B`; if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`; return `${(bytes / 1024 ** 2).toFixed(1)} MB`; },
    dataStatusLabel(status) { return ({ AVAILABLE: "可用", TRANSFERRING: "传输中", TRANSFER_FAILED: "传输失败" })[status] || status; },
    async createAsset() {
      await this.action(async () => {
        const asset = await createPythonAsset(this.assetForm);
        this.assetOpen = false;
        this.showTransientMessage(
          asset.status === "READY"
            ? "Python Asset 已就绪"
            : "环境创建失败，请检查 Docker 服务和镜像配置"
        );
        await this.load();
      });
    },
    newScript(navigate = true) {
      this.captureActiveEditorTab();
      this.draftSequence += 1;
      const preferredName =
        this.draftSequence === 1 ? "analysis.py" : `analysis-${this.draftSequence}.py`;
      const form = {
        id: "",
        assetId: this.readyAssets[0]?.id || "",
        folderId: "",
        fileName: preferredName,
        title: "",
        sourceCode: starter,
        status: "DRAFT"
      };
      const editorTab = {
        key: `draft-${Date.now()}-${this.draftSequence}`,
        form,
        savedSource: starter,
        savedFileName: preferredName,
        savedFolderId: "",
        consoleText: "",
        parametersText: "{}",
        inputSchemaText: defaultInputSchema,
        runState: "idle",
        runFeedback: null
      };
      this.editorTabs.push(editorTab);
      this.activateEditorTab(editorTab, { capture: false, navigate: navigate !== false });
    },
    selectScript(script, navigate = true) {
      const existingTab = this.editorTabs.find((item) => item.form.id === script.id);
      if (existingTab) {
        this.activateEditorTab(existingTab, { navigate: navigate !== false });
        return;
      }
      const editorTab = {
        key: `script-${script.id}`,
        form: {
          id: script.id,
          assetId: script.assetId,
          folderId: script.folderId || "",
          fileName: script.fileName,
          title: script.title,
          sourceCode: script.sourceCode,
          currentVersion: script.currentVersion,
          status: script.status
        },
        savedSource: script.sourceCode || "",
        savedFileName: script.fileName || "",
        savedFolderId: script.folderId || "",
        consoleText: "",
        parametersText: "{}",
        inputSchemaText: defaultInputSchema,
        runState: "idle",
        runFeedback: null
      };
      this.editorTabs.push(editorTab);
      this.activateEditorTab(editorTab, { navigate: navigate !== false });
    },
    activateEditorTab(editorTab, options = {}) {
      if (!editorTab || editorTab.key === this.activeEditorTabKey) {
        if (options.navigate !== false) this.navigateToSection("develop");
        nextTick(() => this.editor?.focus());
        return;
      }
      if (options.capture !== false) this.captureActiveEditorTab();
      this.activeEditorTabKey = editorTab.key;
      this.form = { ...editorTab.form };
      this.activateStudioModel(editorTab);
      this.aiMessages = editorTab.aiMessages || [];
      this.savedSource = editorTab.savedSource || "";
      this.savedFileName = editorTab.savedFileName || editorTab.form.fileName || "";
      this.savedFolderId = editorTab.savedFolderId || "";
      this.consoleText = editorTab.consoleText || "";
      this.parametersText = editorTab.parametersText || "{}";
      this.inputSchemaText = editorTab.inputSchemaText || defaultInputSchema;
      this.runState = editorTab.runState || "idle";
      this.runFeedback = editorTab.runFeedback || null;
      if (options.navigate !== false) this.navigateToSection("develop");
      this.resetAiSuggestion();
      nextTick(() => {
        this.editor?.layout();
        this.editor?.focus();
      });
    },
    captureActiveEditorTab() {
      const activeTab = this.editorTabs.find((item) => item.key === this.activeEditorTabKey);
      if (!activeTab) return;
      activeTab.viewState = this.editor?.saveViewState();
      activeTab.aiMessages = this.aiMessages;
      activeTab.form = { ...this.form };
      activeTab.savedSource = this.savedSource;
      activeTab.savedFileName = this.savedFileName;
      activeTab.savedFolderId = this.savedFolderId;
      activeTab.consoleText = this.consoleText;
      activeTab.parametersText = this.parametersText;
      activeTab.inputSchemaText = this.inputSchemaText;
      activeTab.runState = this.runState;
      activeTab.runFeedback = this.runFeedback;
    },
    closeEditorTab(editorTab) {
      if (editorTab.key === this.activeEditorTabKey) this.captureActiveEditorTab();
      const index = this.editorTabs.findIndex((item) => item.key === editorTab.key);
      if (index < 0) return;
      if (this.editorTabDirty(editorTab)) {
        this.openIdeConfirm({ title: "关闭未保存脚本", message: `“${editorTab.form.fileName || "未命名脚本"}”有未保存修改，关闭后修改将丢失。`, confirmText: "仍然关闭", danger: true }).then((confirmed) => {
          if (confirmed) this.closeEditorTabNow(editorTab);
        });
        return;
      }
      this.closeEditorTabNow(editorTab);
    },
    closeEditorTabNow(editorTab) {
      const wasActive = editorTab.key === this.activeEditorTabKey;
      const index = this.editorTabs.findIndex((item) => item.key === editorTab.key);
      if (index < 0) return;
      this.editorTabs.splice(index, 1);
      const model = this._studioModels?.get(editorTab.key);
      if (model && this.editor?.getModel() === model) this.editor.setModel(null);
      model?.dispose(); this._studioModels?.delete(editorTab.key);
      if (!wasActive) return;
      this.activeEditorTabKey = "";
      const nextTab = this.editorTabs[index] || this.editorTabs[index - 1];
      if (nextTab) this.activateEditorTab(nextTab, { capture: false });
      else this.newScript();
    },
    editorTabDirty(editorTab) {
      return (
        editorTab.form.sourceCode !== editorTab.savedSource ||
        String(editorTab.form.fileName || "").trim() !==
          (editorTab.savedFileName || editorTab.form.fileName || "") ||
        String(editorTab.form.folderId || "") !== String(editorTab.savedFolderId || "")
      );
    },
    async save() {
      const fileName = String(this.form.fileName || "").trim();
      if (!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,170}\.py$/.test(fileName)) {
        this.showTransientError("脚本文件名只能包含字母、数字、点、下划线或短横线，并且必须以 .py 结尾");
        return;
      }
      this.form.fileName = fileName;
      await this.action(async () => {
        const submitted = { ...this.form, expectedVersion: this.form.currentVersion ?? 0 };
        const tabKey = this.activeEditorTabKey;
        const saved = await savePythonScript(submitted);
        if (tabKey !== this.activeEditorTabKey) {
          const tab = this.editorTabs.find(item => item.key === tabKey);
          if (tab) { tab.form.id = saved.id; tab.form.currentVersion = saved.currentVersion; tab.savedSource = saved.sourceCode; }
        } else {
          const changedWhileSaving = this.form.sourceCode !== submitted.sourceCode;
          this.form = { ...this.form, ...saved, sourceCode: changedWhileSaving ? this.form.sourceCode : saved.sourceCode };
          this.savedSource = saved.sourceCode || submitted.sourceCode;
        }
        this.savedFileName = saved.fileName || this.form.fileName;
        this.savedFolderId = saved.folderId || "";
        if (tabKey === this.activeEditorTabKey) this.captureActiveEditorTab();
        this.showTransientMessage("脚本已保存为新版本");
        await this.load(true);
      });
    },
    async renameScript(script) {
      const editorTab = this.editorTabs.find((item) => item.form.id === script.id);
      const current = editorTab?.form || script;
      const name = await this.openIdePrompt({
        title: "重命名 Python 脚本",
        message: editorTab && this.editorTabDirty(editorTab)
          ? "重命名时会一并保存此页签中尚未保存的代码修改。"
          : "脚本内容和所属逻辑文件夹不会改变。",
        value: current.fileName,
        placeholder: "请输入以 .py 结尾的文件名",
        confirmText: "保存"
      });
      if (name == null || name.trim() === current.fileName) return;
      const fileName = name.trim();
      if (!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,170}\.py$/.test(fileName)) {
        this.showTransientError("脚本文件名只能包含字母、数字、点、下划线或短横线，并且必须以 .py 结尾");
        return;
      }
      await this.action(async () => {
        const saved = await savePythonScript({ ...current, fileName });
        if (editorTab) {
          editorTab.form = { ...editorTab.form, ...saved };
          editorTab.savedSource = saved.sourceCode || editorTab.form.sourceCode;
          editorTab.savedFileName = saved.fileName;
          editorTab.savedFolderId = saved.folderId || "";
          if (editorTab.key === this.activeEditorTabKey) {
            this.form = { ...editorTab.form };
            this.savedSource = editorTab.savedSource;
            this.savedFileName = editorTab.savedFileName;
            this.savedFolderId = editorTab.savedFolderId;
          }
        }
        await this.refreshExplorer();
      });
    },
    async removeScript(script) {
      if (!script?.id || !await this.openIdeConfirm({
        title: "删除 Python 脚本",
        message: `确认删除“${script.fileName}”及其历史版本？此操作不可撤销。`,
        confirmText: "删除",
        danger: true
      })) return;
      await this.action(async () => {
        await deletePythonScript(script.id);
        const removedIndex = this.editorTabs.findIndex((item) => item.form.id === script.id);
        const removedWasActive = removedIndex >= 0 && this.editorTabs[removedIndex].key === this.activeEditorTabKey;
        if (removedIndex >= 0) this.editorTabs.splice(removedIndex, 1);
        await this.refreshExplorer();
        if (!removedWasActive) return;
        this.activeEditorTabKey = "";
        const nextTab = this.editorTabs[removedIndex] || this.editorTabs[removedIndex - 1];
        if (nextTab) this.activateEditorTab(nextTab, { capture: false });
        else if (this.scripts[0]) this.selectScript(this.scripts[0]);
        else this.newScript();
      });
    },
    async createFolder() {
      const name = await this.openIdePrompt({ title: "新建逻辑文件夹", message: "文件夹仅用于脚本分类，不会改变 Python 运行路径。", value: "数据分析", placeholder: "请输入文件夹名称", confirmText: "创建" });
      if (name == null || !name.trim()) return;
      await this.action(async () => {
        const folder = await savePythonScriptFolder({ name: name.trim(), parentId: null, sortOrder: this.folders.length });
        this.expandedFolders[folder.id] = true;
        await this.load(true);
        this.showTransientMessage(`文件夹“${folder.name}”已创建`);
      });
    },
    async renameFolder(folder) {
      if (folder.virtual) return;
      const name = await this.openIdePrompt({ title: "重命名逻辑文件夹", message: "文件夹中的脚本和发布状态不会受到影响。", value: folder.name, placeholder: "请输入文件夹名称", confirmText: "保存" });
      if (name == null || !name.trim() || name.trim() === folder.name) return;
      await this.action(async () => {
        await savePythonScriptFolder({ id: folder.id, parentId: folder.parentId, name: name.trim(), sortOrder: folder.sortOrder });
        await this.load(true);
      });
    },
    async removeFolder(folder) {
      if (folder.virtual || !await this.openIdeConfirm({ title: "删除逻辑文件夹", message: `确认删除空文件夹“${folder.name}”？`, confirmText: "删除", danger: true })) return;
      await this.action(async () => { await deletePythonScriptFolder(folder.id); await this.load(true); });
    },
    openIdePrompt(options = {}) { return this.openIdeDialog({ ...options, mode: "prompt" }); },
    openIdeConfirm(options = {}) { return this.openIdeDialog({ ...options, mode: "confirm" }); },
    openIdeDialog(options) {
      if (this.ideDialog.open) this.finishIdeDialog(false);
      return new Promise((resolve) => {
        this.ideDialog = {
          open: true,
          mode: options.mode || "confirm",
          title: options.title || "请确认",
          message: options.message || "",
          value: options.value || "",
          placeholder: options.placeholder || "",
          confirmText: options.confirmText || "确定",
          danger: options.danger === true,
          resolve
        };
        nextTick(() => (this.$refs.ideDialogInput || this.$refs.ideDialogBackdrop)?.focus());
      });
    },
    submitIdeDialog() {
      if (this.ideDialog.mode === "prompt" && !String(this.ideDialog.value || "").trim()) return;
      this.finishIdeDialog(true);
    },
    finishIdeDialog(confirmed) {
      if (!this.ideDialog?.open) return;
      const resolve = this.ideDialog.resolve;
      const result = confirmed ? (this.ideDialog.mode === "prompt" ? String(this.ideDialog.value || "").trim() : true) : (this.ideDialog.mode === "prompt" ? null : false);
      this.ideDialog = { open: false, mode: "confirm", title: "", message: "", value: "", placeholder: "", confirmText: "确定", danger: false, resolve: null };
      if (typeof resolve === "function") resolve(result);
    },
    toggleFolder(folder) { this.expandedFolders[folder.id || "__unfiled"] = !this.folderExpanded(folder); },
    folderExpanded(folder) { const key = folder.id || "__unfiled"; return this.expandedFolders[key] !== false; },
    newScriptInFolder(folder) { this.newScript(); this.form.folderId = folder?.id || ""; this.captureActiveEditorTab(); },
    useSystemExample(example) {
      this.newScript();
      this.form.fileName = example.scriptFileName;
      this.form.title = example.name;
      this.form.sourceCode = example.sourceCode;
      this.inputSchemaText = example.inputSchema;
      this.savedSource = "";
      this.savedFileName = "";
      this.captureActiveEditorTab();
      nextTick(() => { this.editor?.setValue(example.sourceCode); this.editor?.focus(); });
      this.showTransientMessage(`${example.format} 示例已复制到未保存脚本`);
    },
    async importExampleData(example) {
      await this.action(async () => {
        const dataFile = await importPythonSystemExampleData(example.id);
        await this.load(true);
        this.selectedDataFileId = dataFile.id;
        this.showTransientMessage(`示例数据 ${dataFile.fileName} 已导入“用户空间”`);
      });
    },
    async run() {
      let parameters;
      try {
        parameters = parsePythonExecutionParameters(this.parametersText);
        if (this.selectedDataFileId && parameters.source_file == null) {
          parameters = { ...parameters, source_file: this.selectedDataFileId };
        }
      } catch (error) {
        this.showTransientError(error?.message || "执行参数必须是合法 JSON 对象");
        this.bottomTab = "parameters";
        this.bottomOpen = true;
        return;
      }
      if (!this.form.id || this.busy) return;
      const startedAt = Date.now();
      this.busy = true;
      this.error = "";
      this.message = "";
      this.bottomTab = "console";
      this.bottomOpen = true;
      this.runState = "running";
      this.runFeedback = { startedAt, status: "RUNNING" };
      this.consoleText = `[${new Date(startedAt).toLocaleTimeString()}] 正在准备隔离执行环境…`;
      try {
        if (this.dirty) {
          this.consoleText += "\n检测到未保存修改，正在保存当前版本…";
          const saved = await savePythonScript(this.form);
          this.form = { ...this.form, ...saved };
          this.savedSource = saved.sourceCode || this.form.sourceCode;
          this.savedFileName = saved.fileName || this.form.fileName;
          this.savedFolderId = saved.folderId || "";
          this.captureActiveEditorTab();
        }
        this.consoleText += `\n启动 ${this.form.fileName}…\n`;
        let inputSchema;
        try {
          inputSchema = parsePythonExecutionParameters(this.inputSchemaText);
        } catch (error) {
          throw new Error(`输入 Schema 无效：${error?.message || "必须是 JSON 对象"}`);
        }
        const result = await executePythonScript(this.form.id, parameters, inputSchema);
        const succeeded = result.status === "SUCCEEDED";
        this.runState = succeeded ? "succeeded" : "failed";
        this.runFeedback = result;
        this.consoleText = this.executionLog(result, startedAt);
        await this.load(true);
        const refreshed = this.scripts.find((script) => script.id === this.form.id);
        if (refreshed)
          this.form = {
            ...this.form,
            status: refreshed.status,
            currentVersion: refreshed.currentVersion,
            lastTestSucceeded: refreshed.lastTestSucceeded
          };
      } catch (error) {
        this.runState = "failed";
        const message = errorMessage(error, "脚本执行失败");
        this.runFeedback = { status: "FAILED", durationMs: Date.now() - startedAt };
        this.consoleText += `\n\n[执行失败] ${message}`;
      } finally {
        this.busy = false;
        this.captureActiveEditorTab();
        nextTick(() => this.editor?.layout());
      }
    },
    async publish() {
      if (this.publishBusy) return;
      const updating = this.publishUpdating;
      this.publishBusy = true;
      this.error = "";
      this.message = "";
      try {
        this.inputSchemaText = this.publishForm.inputSchema;
        await publishPythonScript(this.form.id, this.publishForm);
        await this.load(true);
        this.publishOpen = false;
        this.showTransientMessage(
          updating ? "Python 模板已更新并同步到 MCP" : "Python 模板已发布并同步到 MCP"
        );
      } catch (error) {
        this.showTransientError(errorMessage(error, "Python 模板发布失败"));
      } finally {
        this.publishBusy = false;
      }
    },
    async openPublishDialog() {
      if (!this.form.id || this.busy) return;
      if (this.dirty) {
        this.showTransientError("当前脚本有未保存的修改，请先保存并重新运行测试后再发布");
        return;
      }
      await this.action(async () => {
        const template = await fetchPythonScriptTemplate(this.form.id);
        this.publishUpdating = Boolean(template?.id);
        this.publishForm = template
          ? {
              templateName: template.templateName || "",
              scenario: template.scenario || "",
              description: template.description || "",
              keywords: template.keywords || "",
              domain: template.domain || "",
              version: template.version || "1.0.0",
              inputSchema: template.inputSchemaJson || this.inputSchemaText || defaultInputSchema,
              outputSchema: template.outputSchemaJson || '{"type":"object"}'
            }
          : {
              templateName: "",
              scenario: "",
              description: "",
              keywords: "",
              domain: "",
              version: "1.0.0",
              inputSchema: this.inputSchemaText || defaultInputSchema,
              outputSchema: '{"type":"object"}'
            };
        this.publishOpen = true;
      });
    },
    useAiExample(value) {
      this.aiPrompt = value;
      this.$refs.aiPrompt?.focus();
    },
    openAiSkillPicker() {
      if (this.aiBusy) return;
      this.aiSkillDraftIds = [...this.aiSkillIds];
      this.aiSkillSearchQuery = "";
      this.aiSkillCategoryFilter = "ALL";
      this.aiSkillPickerOpen = true;
      nextTick(() => this.$refs.aiSkillSearch?.focus());
    },
    closeAiSkillPicker() {
      this.aiSkillPickerOpen = false;
      this.aiSkillDraftIds = [];
    },
    toggleAiSkillDraft(skillId) {
      const selected = new Set(this.aiSkillDraftIds);
      if (selected.has(skillId)) selected.delete(skillId);
      else if (selected.size < 8) selected.add(skillId);
      this.aiSkillDraftIds = [...selected];
    },
    clearAiSkillDraft() {
      this.aiSkillDraftIds = [];
    },
    applyAiSkillSelection() {
      this.aiSkillIds = [...this.aiSkillDraftIds];
      this.aiSkillPickerOpen = false;
      this.aiSkillDraftIds = [];
    },
    async askAi() {
      if (this.aiBusy) return;
      if (!this.aiPrompt.trim()) {
        this.showTransientError("请先输入代码生成提示词");
        return;
      }
      const selection = this.editor?.getSelection();
      const hasSelection = selection && !selection.isEmpty();
      const selectedCode = hasSelection ? this.editor.getModel().getValueInRange(selection) : "";
      this.aiSelection = hasSelection
        ? {
            startLineNumber: selection.startLineNumber,
            startColumn: selection.startColumn,
            endLineNumber: selection.endLineNumber,
            endColumn: selection.endColumn
          }
        : null;
      this.aiBusy = true;
      this.aiSuggestion = null;
      this.aiDiff = null;
      this.aiChangeRequest = this.aiPrompt;
      this.aiAppliedInfo = null;
      this.aiStage = "planning";
      this.aiCodePlan = null;
      this.aiRequestAction = this.aiAction;
      this.error = "";
      const startedAt = Date.now();
      const sourceContext = {key:this.activeEditorTabKey,source:this.form.sourceCode,fileName:this.form.fileName,
        cursorOffset:this.editor.getModel().getOffsetAt(this.editor.getPosition()),
        selectionOffsets:hasSelection ? {start:this.editor.getModel().getOffsetAt(selection.getStartPosition()),end:this.editor.getModel().getOffsetAt(selection.getEndPosition())} : null};
      this.aiSourceContext = sourceContext;
      this.aiCodingTask=null;this.aiCodingProgress=null;
      this.captureActiveEditorTab();
      this.aiCodingSources=this.editorTabs.map(tab=>({path:tab.form.fileName,content:tab.form.sourceCode,key:tab.key}));
      const userText = this.aiPrompt;
      const requestMessages = this.aiMessages;
      const history = this.aiMessages.slice(-4).map(item => `${item.role}: ${item.text.slice(0,500)}`).join('\n');
      const requestPrompt = [`当前文件：${sourceContext.fileName}`,history && `之前的工程协作：\n${history}`, userText].filter(Boolean).join('\n\n').slice(-4000);
      const requestId = beginStudioExchange(requestMessages,userText,this.aiRequestAction,sourceContext.fileName);
      this.aiCurrentTurn = studioAssistantTurn(requestMessages,requestId);
      this.aiFollowOutput = true;
      this.aiPrompt = '';
      this.startAiProgress();
      try {
        const assistRequest = {
          action: this.aiRequestAction,
          prompt: requestPrompt,
          sourceCode: sourceContext.source,
          selectedCode,
          modelName: this.aiModel,
          skillIds: [...this.aiSkillIds]
        };
        const suggestion = await requestPlannedPythonAssist(assistRequest, async plan => {
          this.aiCodePlan = plan;
          this.aiStage = 'exploring';
          updateStudioTurn(studioAssistantTurn(requestMessages,requestId),'generating','计划已通过校验，正在根据当前代码和已选上下文生成结果。',{plan:plan.interpretationPlan,planId:plan.planId});
          await nextTick();
        }, planned=>{this.aiCodingRequest=planned;return runPythonCoding(planned,{requestId:String(requestId).replace(/[^a-zA-Z0-9_-]/g,'_'),
          entryPath:sourceContext.fileName,files:this.aiCodingSources.map(({path,content})=>({path,content})),
          onTask:task=>{this.aiCodingTask=task;},onProgress:state=>{this.aiCodingProgress=state;}});});
        const originalCode = suggestion.action==='test' ? '' : sourceContext.source;
        this.aiSuggestion = suggestion;
        this.aiDiff = ['explain','ask'].includes(suggestion.action) ? null : buildPythonLineDiff(originalCode, pythonProposalSource(sourceContext,suggestion));
        if(suggestion.files)this.aiDiff.changed=this.aiReviewFiles.some(file=>file.before!==file.after);
        this.aiElapsedMs = Date.now() - startedAt;
        this.aiStage = "ready";
        updateStudioTurn(studioAssistantTurn(requestMessages,requestId),'ready',['explain','ask'].includes(suggestion.action) ? suggestion.code : `为 ${sourceContext.fileName} 生成了${studioAiLabels[suggestion.action] || '代码'}建议，等待审查。`,{plan:suggestion.plan?.interpretationPlan || this.aiCodePlan?.interpretationPlan,planId:suggestion.plan?.planId || this.aiCodePlan?.planId});
        const originTab = this.editorTabs.find(tab => tab.key === sourceContext.key);
        if (originTab) originTab.aiMessages = requestMessages.slice(-16);
        if (this.activeEditorTabKey === sourceContext.key) this.aiMessages = requestMessages.slice(-16);
        this.aiProgressStep = 4;
      } catch (error) {
        this.aiStage = "failed";
        const detail = errorMessage(error, "AI 助手请求失败");
        updateStudioTurn(studioAssistantTurn(requestMessages,requestId),'failed',detail);
        const originTab = this.editorTabs.find(tab=>tab.key===sourceContext.key);
        if(originTab)originTab.aiMessages=requestMessages.slice(-16);
        this.showTransientError(detail);
      } finally {
        this.aiBusy = false;
        this.stopAiProgress();
      }
    },
    applyAiSuggestion() {
      if (!this.aiSuggestion?.code || !this.editor) return;
      if (this.aiIsTextAnswer || this.aiSuggestionStale) { this.showTransientError('当前文件或代码已变化，请基于最新内容重新生成建议。'); return; }
      if(this.aiSuggestion.files) {
        this.captureActiveEditorTab();
        for(const file of this.aiSuggestion.files) {
          const original=this.aiCodingSources.find(source=>source.path===file.path);
          const tab=original && this.editorTabs.find(item=>item.key===original.key);
          if(original && (!tab || tab.form.sourceCode!==original.content || tab.form.fileName!==original.path)
              || !original && this.editorTabs.some(item=>item.form.fileName.toLowerCase()===file.path.toLowerCase())) {
            this.showTransientError(`文件 ${file.path} 已变化，请重新生成建议。`);return;
          }
        }
        for(const file of this.aiSuggestion.files) {
          const original=this.aiCodingSources.find(source=>source.path===file.path);
          const tab=original && this.editorTabs.find(item=>item.key===original.key);
          if(tab) {
            tab.form={...tab.form,sourceCode:file.content};
            this._studioModels?.get(tab.key)?.setValue(file.content);
            if(tab.key===this.activeEditorTabKey){this.form.sourceCode=file.content;this.editor.getModel()?.setValue(file.content);}
          } else this.editorTabs.push({key:`coding-${++this.draftSequence}-${Date.now()}`,
            form:{id:'',assetId:this.form.assetId,folderId:'',fileName:file.path,title:'',sourceCode:file.content,status:'DRAFT'},
            savedSource:'',savedFileName:file.path,consoleText:'',parametersText:'{}',inputSchemaText:defaultInputSchema,runState:'idle'});
        }
        this.aiStage='applied';updateStudioTurn(this.aiCurrentTurn,'applied','已应用审查后的文件变更，等待保存。');return;
      }
      if (this.aiSuggestion.action === 'test') {
        const code = this.aiSuggestion.code;
        const name = this.form.fileName.replace(/\.py$/i,'');
        const assetId = this.form.assetId;
        updateStudioTurn(this.aiCurrentTurn,'applied',`已将建议应用为 test_${name}.py，等待保存。`);
        this.newScript(); this.form.assetId = assetId; this.form.fileName = `test_${name}.py`; this.form.sourceCode = code;
        this.aiAppliedInfo = {verb:'新建',mode:'测试文件',lines:code.split('\n').length}; this.aiStage = 'applied';
        return;
      }
      const model = this.editor.getModel();
      if (!model) return;
      const code = String(this.aiSuggestion.code).replace(/\r\n/g, "\n");
      let range;
      let mode;
      if (this.aiSuggestion.replaceSelection && this.aiSelection) {
        range = new monaco.Range(
          this.aiSelection.startLineNumber,
          this.aiSelection.startColumn,
          this.aiSelection.endLineNumber,
          this.aiSelection.endColumn
        );
        mode = "选中代码";
      } else if (this.aiSuggestion.action === "continue") {
        const position = model.getPositionAt(this.aiSourceContext.cursorOffset);
        range = new monaco.Range(
          position.lineNumber,
          position.column,
          position.lineNumber,
          position.column
        );
        mode = "光标位置";
      } else {
        range = model.getFullModelRange();
        mode = "当前脚本";
      }
      const startOffset = model.getOffsetAt(range.getStartPosition());
      this.editor.pushUndoStop();
      const applied = this.editor.executeEdits("python-ai", [
        { range, text: code, forceMoveMarkers: true }
      ]);
      this.editor.pushUndoStop();
      if (!applied) {
        this.aiStage = "failed";
        this.showTransientError("AI 代码写入编辑器失败，请重新生成后再试");
        return;
      }
      this.form.sourceCode = model.getValue();
      const start = model.getPositionAt(startOffset);
      const end = model.getPositionAt(startOffset + code.length);
      const changedRange = new monaco.Range(
        start.lineNumber,
        start.column,
        end.lineNumber,
        end.column
      );
      this.editor.setSelection(changedRange);
      this.editor.revealRangeInCenter(changedRange);
      this.aiDecoration?.clear();
      const decoration = this.editor.createDecorationsCollection([
        { range: changedRange, options: { isWholeLine: true, className: "ai-applied-line" } }
      ]);
      this.aiDecoration = markRaw(decoration);
      setTimeout(() => decoration.clear(), 2200);
      this.aiAppliedInfo = {
        mode,
        verb: this.aiSuggestion.action === "continue" ? "插入" : "替换",
        lines: Math.max(1, code.split("\n").length),
        at: new Date().toLocaleTimeString()
      };
      this.aiStage = "applied";
      updateStudioTurn(this.aiCurrentTurn,'applied',`代码建议已应用到 ${this.form.fileName}，当前修改尚未保存。`);
      this.editor.focus();
    },
    async cancelAiCoding(){try{if(this.aiCodingTask)await cancelPythonCoding(this.aiCodingTask);}catch(error){this.showTransientError(errorMessage(error,'取消任务失败'));}},
    async resumeAiCoding(){
      if(this.aiBusy||!this.aiCodingTask||!this.aiCodingRequest)return;
      this.aiBusy=true;this.aiStage='exploring';
      try {
        this.aiSuggestion=await resumePythonCoding(this.aiCodingTask,this.aiCodingRequest,{entryPath:this.aiSourceContext.fileName,plan:this.aiCodePlan,
          onProgress:state=>{this.aiCodingProgress=state;}});
        this.aiDiff=buildPythonLineDiff(this.aiSourceContext.source,this.aiSuggestion.code);
        this.aiDiff.changed=this.aiReviewFiles.some(file=>file.before!==file.after);this.aiStage='ready';
      }catch(error){this.aiStage='failed';this.showTransientError(errorMessage(error,'恢复任务失败'));}
      finally{this.aiBusy=false;}
    },
    startAiProgress() {
      this.stopAiProgress();
      this.aiProgressStep = 3;
      const startedAt = Date.now();
      this.aiElapsedMs = 0;
      this.aiProgressTimer = setInterval(() => {
        this.aiElapsedMs = Date.now() - startedAt;
      }, 1000);
    },
    scrollAiMessages() {
      nextTick(()=>{const box=this.$refs.aiMessagesScroll;if(box&&this.aiFollowOutput)box.scrollTop=box.scrollHeight;});
    },
    trackAiScroll() {
      const box=this.$refs.aiMessagesScroll;
      if(box)this.aiFollowOutput=box.scrollHeight-box.scrollTop-box.clientHeight<64;
    },
    newPythonConversation() {
      if(this.aiBusy)return;
      this.resetAiSuggestion();this.aiCurrentTurn=null;this.aiMessages=[];this.aiPrompt='';this.aiFollowOutput=true;
      const tab=this.editorTabs.find(item=>item.key===this.activeEditorTabKey);
      if(tab)tab.aiMessages=[];
    },
    stopAiProgress() {
      if (this.aiProgressTimer) clearInterval(this.aiProgressTimer);
      this.aiProgressTimer = null;
    },
    resetAiSuggestion() {
      this.aiReviewOpen = false;
      this.aiSuggestion = null;
      this.aiDiff = null;
      this.aiChangeRequest = "";
      this.aiAppliedInfo = null;
      this.aiStage = "idle";
      this.aiCodePlan = null;
      this.aiProgressStep = 0;
    },
    async formatDocument() {
      const model = this.editor?.getModel();
      if (!model) return;
      const before = model.getValue();
      const version = model.getVersionId();
      let normalized = getLanguageAdapter('python').format(before);
      if (this.form.assetId) {
        try {
          const result = await runPythonCodeTool('ruff-format', this.form.assetId, before);
          if (result.exitCode === 0) normalized = result.stdout;
          else this.showTransientError(result.stderr || 'Ruff 格式化暂不可用，已使用本地缩进整理');
        } catch { this.showTransientError('Ruff 格式化暂不可用，已使用本地缩进整理'); }
      }
      if (!applyFormatIfUnchanged(this.editor, model, version, before, normalized)) return;
      this.form.sourceCode = model.getValue();
      this.showTransientMessage(this.form.sourceCode === before ? "代码格式已符合规范" : "代码格式化完成");
      this.editor.focus();
    },
    executionLog(result, startedAt) {
      const duration = result.durationMs ?? Date.now() - startedAt;
      const lines = [
        `[${new Date().toLocaleTimeString()}] ${
          result.status === "SUCCEEDED" ? "执行完成" : "执行结束"
        }`,
        `状态: ${result.status || "UNKNOWN"}  |  耗时: ${duration} ms  |  退出码: ${
          result.exitCode ?? "-"
        }`,
        result.containerId ? `容器: ${result.containerId}` : ""
      ].filter(Boolean);
      if (result.stdout) lines.push("\n--- stdout ---", result.stdout);
      if (result.stderr) lines.push("\n--- stderr ---", result.stderr);
      if (!result.stdout && !result.stderr) lines.push("\n（程序没有输出 stdout 或 stderr）");
      return lines.join("\n");
    },
    clearConsole() {
      this.consoleText = "";
      this.runState = "idle";
      this.runFeedback = null;
      this.captureActiveEditorTab();
    },
    restoreWorkspaceLayout() {
      try {
        const key = workspaceStorageKey(workspaceLayoutStorageKey, this.workspaceContext);
        const stored = JSON.parse((key ? globalThis.localStorage?.getItem(key) : null) || "{}");
        this.explorerWidth = this.clamp(Number(stored.explorer), 180, 480, workspaceLayoutDefaults.explorer);
        this.aiWidth = this.clamp(Number(stored.ai), 260, 620, workspaceLayoutDefaults.ai);
        this.bottomHeight = this.clamp(Number(stored.bottom), 100, 520, workspaceLayoutDefaults.bottom);
        this.explorerOpen = stored.explorerOpen !== false; this.aiOpen = stored.aiOpen !== false;
        this.bottomOpen = stored.bottomOpen !== false;
      } catch {
        this.explorerWidth = workspaceLayoutDefaults.explorer;
        this.aiWidth = workspaceLayoutDefaults.ai;
        this.bottomHeight = workspaceLayoutDefaults.bottom;
      }
    },
    persistWorkspaceLayout() {
      try {
        const key = workspaceStorageKey(workspaceLayoutStorageKey, this.workspaceContext);
        if (!key) return;
        globalThis.localStorage?.setItem(key, JSON.stringify({
          explorer: this.explorerWidth,
          ai: this.aiWidth,
          bottom: this.bottomHeight
          , explorerOpen:this.explorerOpen, aiOpen:this.aiOpen, bottomOpen:this.bottomOpen
        }));
      } catch {
        // 浏览器禁用本地存储时仍保留当前会话中的拖拽结果。
      }
    },
    clamp(value, minimum, maximum, fallback = minimum) {
      return Number.isFinite(value) ? Math.min(maximum, Math.max(minimum, value)) : fallback;
    },
    startPaneResize(type, event) {
      if (event.button !== 0 || globalThis.innerWidth < 900) return;
      event.preventDefault();
      const startSize = type === "explorer"
        ? this.explorerWidth
        : type === "ai"
          ? this.aiWidth
          : this.bottomHeight;
      this.resizeState = { type, startX: event.clientX, startY: event.clientY, startSize };
      document.body.classList.add("ds-pane-resizing", `ds-pane-resizing-${type}`);
      globalThis.addEventListener("pointermove", this.resizePane);
      globalThis.addEventListener("pointerup", this.stopPaneResize);
      globalThis.addEventListener("pointercancel", this.stopPaneResize);
    },
    resizePane(event) {
      const state = this.resizeState;
      const workspace = this.$refs.workspace;
      if (!state || !workspace) return;
      const bounds = workspace.getBoundingClientRect();
      if (state.type === "explorer") {
        const maximum = Math.max(180, Math.min(480, bounds.width - this.aiWidth - 420));
        this.explorerWidth = this.clamp(state.startSize + event.clientX - state.startX, 180, maximum);
      } else if (state.type === "ai") {
        const maximum = Math.max(260, Math.min(620, bounds.width - this.explorerWidth - 420));
        this.aiWidth = this.clamp(state.startSize - event.clientX + state.startX, 260, maximum);
      } else {
        const mainHeight = this.$refs.codeEditor?.parentElement?.clientHeight || bounds.height - 58;
        // Computing this from Monaco's current height made the maximum shrink as the
        // bottom panel grew, which effectively prevented upward resizing.
        const maximum = calculateBottomPanelMaximum(mainHeight);
        this.bottomHeight = this.clamp(state.startSize - event.clientY + state.startY, 100, maximum);
      }
      this.editor?.layout();
    },
    stopPaneResize() {
      if (!this.resizeState) return;
      this.resizeState = null;
      document.body.classList.remove("ds-pane-resizing", "ds-pane-resizing-explorer", "ds-pane-resizing-ai", "ds-pane-resizing-bottom");
      globalThis.removeEventListener("pointermove", this.resizePane);
      globalThis.removeEventListener("pointerup", this.stopPaneResize);
      globalThis.removeEventListener("pointercancel", this.stopPaneResize);
      this.persistWorkspaceLayout();
      nextTick(() => this.editor?.layout());
    },
    resetPaneSize(type) {
      if (type === "explorer") this.explorerWidth = workspaceLayoutDefaults.explorer;
      else if (type === "ai") this.aiWidth = workspaceLayoutDefaults.ai;
      else this.bottomHeight = workspaceLayoutDefaults.bottom;
      this.persistWorkspaceLayout();
      nextTick(() => this.editor?.layout());
    },
    clearMessageTimer() {
      if (this.messageTimer) clearTimeout(this.messageTimer);
      this.messageTimer = null;
    },
    clearErrorTimer() {
      if (this.errorTimer) clearTimeout(this.errorTimer);
      this.errorTimer = null;
    },
    showTransientError(text, durationMs = 5000) {
      this.clearErrorTimer();
      this.error = text;
      this.errorTimer = setTimeout(() => {
        if (this.error === text) this.error = "";
        this.errorTimer = null;
      }, durationMs);
    },
    showTransientMessage(text, durationMs = 5000) {
      this.clearMessageTimer();
      this.message = text;
      this.messageTimer = setTimeout(() => {
        if (this.message === text) this.message = "";
        this.messageTimer = null;
      }, durationMs);
    },
    useParameterExample() {
      const availableFile = this.dataFiles.find(
        (file) => file.status === "AVAILABLE" && file.id === this.selectedDataFileId
      ) || this.dataFiles.find((file) => file.status === "AVAILABLE" && file.id);
      if (!availableFile) {
        this.showTransientError("请先在“用户空间”中选择一个可用数据文件");
        return;
      }
      this.parametersText = JSON.stringify(
        {
          source_file: availableFile.id,
          limit: 100,
          include_detail: true
        },
        null,
        2
      );
    },
    insertParameterReaderExample() {
      const editor = this.editor;
      const selection = editor?.getSelection();
      if (!editor || !selection) return;
      const model = editor.getModel();
      const prefix = selection.startLineNumber > 1 && model.getLineContent(selection.startLineNumber - 1).trim()
        ? "\n\n"
        : "";
      editor.pushUndoStop();
      editor.executeEdits("parameter-reader-example", [
        { range: selection, text: `${prefix}${parameterReaderExample}\n`, forceMoveMarkers: true }
      ]);
      editor.pushUndoStop();
      this.form.sourceCode = model.getValue();
      this.showTransientMessage("运行参数读取示例已插入脚本，可根据字段名调整");
      editor.focus();
    },
    toggleBottom() {
      this.bottomOpen = !this.bottomOpen;
      this.persistWorkspaceLayout();
      nextTick(() => this.editor?.layout());
    },
    toggleExplorer() {
      this.explorerOpen = !this.explorerOpen;
      this.persistWorkspaceLayout();
      nextTick(() => this.editor?.layout());
    },
    toggleAi() {
      this.aiOpen = !this.aiOpen;
      this.persistWorkspaceLayout();
      nextTick(() => this.editor?.layout());
    },
    toggleFullscreen() {
      this.isFullscreen = !this.isFullscreen;
      nextTick(() => this.editor?.layout());
    },
    async action(fn) {
      this.busy = true;
      this.error = "";
      this.message = "";
      try {
        await fn();
      } catch (error) {
        this.showTransientError(errorMessage(error, "操作失败"));
      } finally {
        this.busy = false;
      }
    },
    assetName(id) {
      return this.assets.find((asset) => asset.id === id)?.name || id;
    },
    assetStatusLabel(status) {
      return (
        { READY: "可用", PROVISIONING: "准备中", FAILED: "不可用", DISABLED: "已停用" }[
          String(status || "").toUpperCase()
        ] || "准备中"
      );
    },
    environmentStatusMessage(status) {
      return (
        {
          READY: "环境已就绪，可以开始 Python 开发",
          PROVISIONING: "环境正在准备中，请稍后再试",
          FAILED: "环境暂时不可用，请联系管理员",
          DISABLED: "环境已停用，请联系管理员"
        }[String(status || "").toUpperCase()] || "环境正在准备中，请稍后再试"
      );
    },
    statusClass(status) {
      return String(status || "").toLowerCase();
    },
    formatTime(value) {
      return formatDateTime(value, "-");
    }
  }
};
