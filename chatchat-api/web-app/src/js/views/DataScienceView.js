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
  publishPythonScript,
  requestPythonCodeAssist,
  savePythonScript
} from "../../services/api";
import { errorMessage, formatDateTime } from "../utils/uiFormatters";

globalThis.MonacoEnvironment = { getWorker: () => new EditorWorker() };

const starter = `import json
import os

# Agent 参数通过环境变量传入
params = json.loads(os.environ.get("CHATCHAT_INPUT_JSON", "{}"))

def main(data):
    return {"received": data, "message": "hello from isolated Python"}

print(json.dumps(main(params), ensure_ascii=False))
`;

const completions = [
  ["CHATCHAT_INPUT_JSON", 'os.environ.get("CHATCHAT_INPUT_JSON", "{}")', "Agent 传入的 JSON 参数"],
  [
    "读取 Agent 参数",
    'params = json.loads(os.environ.get("CHATCHAT_INPUT_JSON", "{}"))',
    "读取运行参数"
  ],
  ["输出 JSON 结果", "print(json.dumps(result, ensure_ascii=False))", "向 Agent 返回 JSON"],
  ["main 函数", "def main(data):\n    ${1:result} = {}\n    return ${1:result}", "数据科学模板入口"]
];

export default {
  name: "DataScienceView",
  data: () => ({
    tabs: [
      { id: "environment", label: "Python 环境" },
      { id: "develop", label: "Python 开发" },
      { id: "scripts", label: "我的脚本" }
    ],
    tab: "environment",
    loading: true,
    busy: false,
    error: "",
    message: "",
    assets: [],
    scripts: [],
    executions: [],
    environmentCatalog: [],
    assetOpen: false,
    publishOpen: false,
    consoleText: "",
    parametersText: "{}",
    bottomTab: "console",
    explorerQuery: "",
    explorerPage: 1,
    explorerPageSize: 7,
    editor: null,
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
    cursorPosition: { lineNumber: 1, column: 1 },
    aiOpen: true,
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
      inputSchema: '{"type":"object","properties":{}}',
      outputSchema: '{"type":"object"}'
    }
  }),
  computed: {
    readyAssets() {
      return this.assets.filter((asset) => asset.status === "READY");
    },
    selectedEnvironment() {
      return this.environmentCatalog.find((env) => env.id === this.assetForm.environmentId);
    },
    canPublish() {
      return this.form.id && this.form.status === "TESTED";
    },
    dirty() {
      return this.form.sourceCode !== this.savedSource;
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
    }
  },
  watch: {
    environmentQuery() {
      this.environmentPage = 1;
    },
    explorerQuery() {
      this.explorerPage = 1;
    },
    scriptCatalogQuery() {
      this.scriptCatalogPage = 1;
    },
    tab(value) {
      if (value !== "develop") return;
      if (!this.form.id && this.scripts[0]) {
        const script = this.scripts[0];
        this.form = {
          id: script.id,
          assetId: script.assetId,
          fileName: script.fileName,
          title: script.title,
          sourceCode: script.sourceCode,
          status: script.status
        };
        this.savedSource = script.sourceCode || "";
      }
      nextTick(() => this.initializeEditor());
    },
    "form.sourceCode"(value) {
      const model = this.editor?.getModel();
      if (model && model.getValue() !== value) model.setValue(value || "");
    }
  },
  mounted() {
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
    this.disposeEditor();
  },
  methods: {
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
        this.scripts = data?.scripts || [];
        this.executions = data?.executions || [];
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
      } catch (error) {
        this.error = errorMessage(error, "工作台加载失败");
      } finally {
        if (!background) this.loading = false;
        if (this.tab === "develop")
          nextTick(() => {
            this.initializeEditor();
            this.editor?.layout();
          });
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
      monaco.languages.registerCompletionItemProvider("python", {
        provideCompletionItems: (model, position) => {
          const word = model.getWordUntilPosition(position);
          const range = new monaco.Range(
            position.lineNumber,
            word.startColumn,
            position.lineNumber,
            word.endColumn
          );
          return {
            suggestions: completions.map(([label, insertText, detail]) => ({
              label,
              range,
              kind: monaco.languages.CompletionItemKind.Snippet,
              insertText,
              insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
              detail
            }))
          };
        }
      });
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
          suggest: { showWords: true, preview: true },
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
      this.editor?.dispose();
      this.editor = null;
    },
    openAssetDialog() {
      this.assetOpen = true;
      this.error = "";
    },
    async createAsset() {
      await this.action(async () => {
        const asset = await createPythonAsset(this.assetForm);
        this.assetOpen = false;
        this.message =
          asset.status === "READY"
            ? "Python Asset 已就绪"
            : "环境创建失败，请检查 Docker 服务和镜像配置";
        await this.load();
      });
    },
    newScript() {
      this.form = {
        id: "",
        assetId: this.readyAssets[0]?.id || "",
        fileName: "analysis.py",
        title: "",
        sourceCode: starter,
        status: "DRAFT"
      };
      this.savedSource = starter;
      this.clearConsole();
      this.tab = "develop";
      nextTick(() => this.editor?.focus());
    },
    selectScript(script) {
      this.form = {
        id: script.id,
        assetId: script.assetId,
        fileName: script.fileName,
        title: script.title,
        sourceCode: script.sourceCode,
        status: script.status
      };
      this.savedSource = script.sourceCode || "";
      this.tab = "develop";
      this.clearConsole();
      nextTick(() => this.editor?.focus());
    },
    async save() {
      await this.action(async () => {
        const saved = await savePythonScript(this.form);
        this.form = { ...this.form, ...saved };
        this.savedSource = saved.sourceCode || this.form.sourceCode;
        this.message = "脚本已保存为新版本";
        await this.load(true);
      });
    },
    async run() {
      let parameters;
      try {
        parameters = JSON.parse(this.parametersText || "{}");
      } catch (error) {
        this.error = "执行参数必须是合法 JSON";
        this.bottomTab = "parameters";
        return;
      }
      if (!this.form.id || this.busy) return;
      const startedAt = Date.now();
      this.busy = true;
      this.error = "";
      this.message = "";
      this.bottomTab = "console";
      this.runState = "running";
      this.runFeedback = { startedAt, status: "RUNNING" };
      this.consoleText = `[${new Date(startedAt).toLocaleTimeString()}] 正在准备隔离执行环境…`;
      try {
        if (this.dirty) {
          this.consoleText += "\n检测到未保存修改，正在保存当前版本…";
          const saved = await savePythonScript(this.form);
          this.form = { ...this.form, ...saved };
          this.savedSource = saved.sourceCode || this.form.sourceCode;
        }
        this.consoleText += `\n启动 ${this.form.fileName}…\n`;
        const result = await executePythonScript(this.form.id, parameters);
        const succeeded = result.status === "SUCCEEDED";
        this.runState = succeeded ? "succeeded" : "failed";
        this.runFeedback = result;
        this.consoleText = this.executionLog(result, startedAt);
        if (!succeeded)
          this.error = `测试执行失败${
            result.exitCode == null ? "" : `（退出码 ${result.exitCode}）`
          }`;
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
        this.error = message;
      } finally {
        this.busy = false;
        nextTick(() => this.editor?.layout());
      }
    },
    async publish() {
      await this.action(async () => {
        await publishPythonScript(this.form.id, this.publishForm);
        this.publishOpen = false;
        this.message = "Python 模板已发布并注册到 Agent Runtime";
        await this.load();
      });
    },
    useAiExample(value) {
      this.aiPrompt = value;
      this.$refs.aiPrompt?.focus();
    },
    async askAi() {
      if (!this.aiPrompt.trim()) {
        this.error = "请先输入代码生成提示词";
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
        this.error = errorMessage(error, "AI 代码补全失败");
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
        this.error = "AI 代码写入编辑器失败，请重新生成后再试";
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
    formatDocument() {
      this.editor?.getAction("editor.action.formatDocument")?.run();
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
        this.error = errorMessage(error, "操作失败");
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
