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
  fetchPythonWorkbench,
  uploadPythonDataFile,
  downloadPythonDataFile,
  deletePythonDataFile,
  publishPythonScript,
  requestPythonCodeAssist,
  savePythonScript,
  deletePythonScript,
  savePythonScriptFolder,
  deletePythonScriptFolder,
  importPythonSystemExampleData
} from "../../services/api";
import { errorMessage, formatDateTime } from "../utils/uiFormatters";
import { calculateBottomPanelMaximum, formatPythonSource, parsePythonExecutionParameters } from "../utils/pythonWorkbench";
import { pythonCompletionItems } from "../utils/pythonCompletions";

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
const workspaceLayoutStorageKey = "chatchat.python-studio.layout.v1";

export default {
  name: "DataScienceView",
  props: {
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
    consoleText: "",
    parametersText: "{}",
    inputSchemaText: defaultInputSchema,
    bottomTab: "console",
    bottomOpen: false,
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
    aiOpen: false,
    aiPrompt: "",
    aiAction: "generate",
    aiBusy: false,
    aiSuggestion: null,
    aiSelection: null,
    aiStage: "idle",
    aiProgressStep: 0,
    aiElapsedMs: 0,
    aiAppliedInfo: null,
    aiProgressTimer: null,
    aiDecoration: null,
    aiModels: [],
    aiModel: "",
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
    currentSectionLabel() {
      return {
        environment: "Python 环境",
        develop: "Python 开发",
        data: "我的数据",
        scripts: "我的脚本"
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
    aiApplyLabel() {
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
  },
  activated() {
    if (this.tab === "develop")
      nextTick(() => {
        this.initializeEditor();
        this.editor?.layout();
      });
  },
  beforeUnmount() {
    this.stopAiProgress();
    this.stopPaneResize();
    this.clearErrorTimer();
    this.clearMessageTimer();
    this.disposeEditor();
    this.finishIdeDialog(false);
  },
  methods: {
    navigateToSection(section) {
      const routes = {
        environment: "dataScienceEnvironment",
        develop: "dataScienceDevelop",
        data: "dataScienceData",
        scripts: "dataScienceScripts"
      };
      if (!routes[section]) return;
      this.tab = section;
      this.$emit("navigate", routes[section]);
    },
    async load(silent = false) {
      const background = silent === true;
      if (!background) this.loading = true;
      this.error = "";
      try {
        const [data, environments, models] = await Promise.all([
          fetchPythonWorkbench(),
          fetchMcpPythonEnvironments(),
          fetchPythonCodeModels()
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
          acceptSuggestionOnEnter: "on",
          tabCompletion: "on",
          wordBasedSuggestions: "currentDocument",
          quickSuggestions: { other: true, comments: false, strings: false },
          tabSize: 4,
          scrollBeyondLastLine: false,
          stickyScroll: { enabled: true }
        })
      );
      this.editor.onDidChangeModelContent(() => {
        this.form.sourceCode = this.editor.getValue();
      });
      this.editor.onDidChangeCursorPosition((event) => {
        this.cursorPosition = event.position;
      });
      this.editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => this.save());
      this.editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => this.run());
      this.editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyK, () => {
        this.aiOpen = true;
        nextTick(() => this.$refs.aiPrompt?.focus());
      });
    },
    disposeEditor() {
      this.aiDecoration?.clear();
      this.aiDecoration = null;
      this.completionProvider?.dispose();
      this.completionProvider = null;
      this.editor?.dispose();
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
      nextTick(() => this.focusFileNameEditor());
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
        this.focusFileNameEditor();
        return;
      }
      this.form.fileName = fileName;
      await this.action(async () => {
        const saved = await savePythonScript(this.form);
        this.form = { ...this.form, ...saved };
        this.savedSource = saved.sourceCode || this.form.sourceCode;
        this.savedFileName = saved.fileName || this.form.fileName;
        this.savedFolderId = saved.folderId || "";
        this.captureActiveEditorTab();
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
        this.showTransientMessage(`示例数据 ${dataFile.fileName} 已导入“我的数据”`);
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
      await this.action(async () => {
        this.inputSchemaText = this.publishForm.inputSchema;
        await publishPythonScript(this.form.id, this.publishForm);
        this.publishOpen = false;
        this.showTransientMessage("Python 模板已发布并注册到 Agent Runtime");
        await this.load();
      });
    },
    openPublishDialog() {
      this.publishForm.inputSchema = this.inputSchemaText || defaultInputSchema;
      this.publishOpen = true;
    },
    useAiExample(value) {
      this.aiPrompt = value;
      this.$refs.aiPrompt?.focus();
    },
    async askAi() {
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
      this.aiAppliedInfo = null;
      this.aiStage = "generating";
      this.error = "";
      const startedAt = Date.now();
      this.startAiProgress();
      try {
        this.aiSuggestion = await requestPythonCodeAssist({
          action: this.aiAction,
          prompt: this.aiPrompt,
          sourceCode: this.form.sourceCode,
          selectedCode,
          modelName: this.aiModel
        });
        this.aiElapsedMs = Date.now() - startedAt;
        this.aiStage = "ready";
        this.aiProgressStep = 4;
      } catch (error) {
        this.aiStage = "failed";
        this.showTransientError(errorMessage(error, "AI 代码补全失败"));
      } finally {
        this.aiBusy = false;
        this.stopAiProgress();
      }
    },
    applyAiSuggestion() {
      if (!this.aiSuggestion?.code || !this.editor) return;
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
        const position = this.editor.getPosition();
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
      this.editor.focus();
    },
    startAiProgress() {
      this.stopAiProgress();
      this.aiProgressStep = 1;
      this.aiProgressTimer = setInterval(() => {
        if (this.aiProgressStep < 3) this.aiProgressStep += 1;
      }, 900);
    },
    stopAiProgress() {
      if (this.aiProgressTimer) clearInterval(this.aiProgressTimer);
      this.aiProgressTimer = null;
    },
    resetAiSuggestion() {
      this.aiSuggestion = null;
      this.aiAppliedInfo = null;
      this.aiStage = "idle";
      this.aiProgressStep = 0;
    },
    async formatDocument() {
      const model = this.editor?.getModel();
      if (!model) return;
      const before = model.getValue();
      const normalized = formatPythonSource(before);
      this.editor.pushUndoStop();
      this.editor.executeEdits("python-format", [
        { range: model.getFullModelRange(), text: normalized, forceMoveMarkers: true }
      ]);
      await this.editor.getAction("editor.action.reindentlines")?.run();
      this.editor.pushUndoStop();
      this.form.sourceCode = model.getValue();
      this.showTransientMessage(this.form.sourceCode === before ? "代码格式已符合规范" : "代码格式化完成");
      this.editor.focus();
    },
    focusFileNameEditor() {
      nextTick(() => {
        const input = this.$el?.querySelector(".editor-tab.active .file-name-input");
        input?.focus();
        input?.select();
      });
    },
    restoreFileName() {
      this.form.fileName = this.savedFileName || "analysis.py";
      this.editor?.focus();
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
        const stored = JSON.parse(globalThis.localStorage?.getItem(workspaceLayoutStorageKey) || "{}");
        this.explorerWidth = this.clamp(Number(stored.explorer), 180, 480, workspaceLayoutDefaults.explorer);
        this.aiWidth = this.clamp(Number(stored.ai), 260, 620, workspaceLayoutDefaults.ai);
        this.bottomHeight = this.clamp(Number(stored.bottom), 100, 520, workspaceLayoutDefaults.bottom);
      } catch {
        this.explorerWidth = workspaceLayoutDefaults.explorer;
        this.aiWidth = workspaceLayoutDefaults.ai;
        this.bottomHeight = workspaceLayoutDefaults.bottom;
      }
    },
    persistWorkspaceLayout() {
      try {
        globalThis.localStorage?.setItem(workspaceLayoutStorageKey, JSON.stringify({
          explorer: this.explorerWidth,
          ai: this.aiWidth,
          bottom: this.bottomHeight
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
        this.showTransientError("请先在“我的数据”中上传一个可用文件");
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
      nextTick(() => this.editor?.layout());
    },
    toggleExplorer() {
      this.explorerOpen = !this.explorerOpen;
      nextTick(() => this.editor?.layout());
    },
    toggleAi() {
      this.aiOpen = !this.aiOpen;
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
