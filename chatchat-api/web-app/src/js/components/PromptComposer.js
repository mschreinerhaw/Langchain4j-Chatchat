import { Bot, ChartColumn, FileText, Send, XCircle, Lightbulb, LoaderCircle, ChevronDown, X, Paperclip } from "@lucide/vue";
import { exceedsChatTextLimit, pastedFiles } from "../utils/chatUserAssets";
import { defaultReportPresentation, reportPresentation } from '../utils/reportLayout';
import { readWorkspaceDraft, saveWorkspaceDraft } from '../utils/workspaceDrafts';
import { getWorkspaceContext, workspaceStorageKey } from '../utils/workspaceContext';

import CodingReasoningSelect from '../../components/CodingReasoningSelect.vue';
import ToolbarOptionPicker from '../../components/ToolbarOptionPicker.vue';
import BusinessReferencePicker from '../../components/BusinessReferencePicker.vue';
import {availableReasoning, reasoningOptions} from '../utils/codingReasoning';

const chartScenarios = [
  {value:'financial-auto', label:'金融分析 · 按市场适配', shortLabel:'按市场适配'},
  {value:'financial-cn', label:'金融分析 · 红涨绿跌', shortLabel:'红涨绿跌'},
  {value:'financial-international', label:'金融分析 · 绿涨红跌', shortLabel:'绿涨红跌'},
  {value:'auto', label:'通用分析', shortLabel:'通用分析'},
];
const knownChartScenario = value => chartScenarios.some(option => option.value === value);
function savedChartScenario(scope) {
  const saved = readWorkspaceDraft('exploration-chart-style', scope)
    || readWorkspaceDraft('report-presentation', scope)?.styleProfile;
  return knownChartScenario(saved) ? saved : '';
}

export default {
  name: "PromptComposer",
  components: {
    BusinessReferencePicker,
    Bot, ChartColumn, CodingReasoningSelect, ToolbarOptionPicker,
    FileText,
    Send,
    XCircle,
    Lightbulb, LoaderCircle, ChevronDown, X, Paperclip,
  },
  props: {
    businessReferencesEnabled: Boolean,
    compact: {type:Boolean,default:false},
    simpleTools: { type: Boolean, default: false },
    inheritedStyleProfile: String,
    reasoningEfforts: {type:Array,default:()=>[]},
    reasoningUnavailableLabel: String,
    showIdea: {type:Boolean,default:false},
    ideaContext: {type:Object,default:()=>({})},
    ideaProvider: {type:Function,default:null},
    allowFilePicker: {type:Boolean,default:false},
    showAgentPicker: {type:Boolean,default:false},
    agentRequired: {type:Boolean,default:false},
    inputDisabled: {type:Boolean,default:false},
    placeholder: {type:String,default:'输入问题，拖拽或粘贴分析附件，Shift + Enter 换行'},
    maxLength: {type:Number,default:8000},
    workspaceContext: {type:Object,default:null},
    conversationKey: { type: String, default: "" },
    attachments: { type: Array, default: () => [] },
    suggestions: {
      type: Array,
      default: () => [],
    },
    modelValue: {
      type: String,
      default: "",
    },
    loading: {
      type: Boolean,
      default: false,
    },
    runningVisualReport: {
      type: Boolean,
      default: false,
    },
    agents: {
      type: Array,
      default: () => [],
    },
    selectedAgentId: {
      type: String,
      default: "",
    },
    agentsLoading: {
      type: Boolean,
      default: false,
    },
    defaultModelName: {
      type: String,
      default: "",
    },
    showSuggestions: {
      type: Boolean,
      default: true,
    },
    stopAvailable: {
      type: Boolean,
      default: false,
    },
  },
  emits: [
    "clear",
    "files",
    "retry-file",
    "remove-file",
    "pick",
    "send",
    "stop",
    "update:modelValue",
    "update:selectedAgentId",
  ],
  data() {
    return {
      reportMode: "off",
      toolsOpen: false,
      resourceExplorationApproved: false,
      rememberedReasoning: readWorkspaceDraft('coding-reasoning',this.workspaceContext || getWorkspaceContext()),
      reportPresentation: reportPresentation(readWorkspaceDraft('report-presentation',this.workspaceContext || getWorkspaceContext()) || {}),
      chartScenarios,
      chartScenario: this.compact ? savedChartScenario(this.workspaceContext || getWorkspaceContext()) : '',
      draft: this.modelValue,
      mention: null, selectedReferences: [], referenceError: "",
      ideasOpen: false, ideaError: '', ideas: [], ideasLoading: false, ideaSerial: 0, ideaController: null,
    };
  },
  computed: {
    showRunningVisualReport() {
      return this.loading && this.runningVisualReport;
    },
    visualReportSelected() {
      return this.reportMode === "visual" || this.showRunningVisualReport;
    },
    visualReportTitle() {
      if (this.showRunningVisualReport) return "当前任务已启用图文报告";
      return this.reportMode === "visual" ? "本次发送将使用图文报告，点击取消" : "为本次发送开启图文报告";
    },
    reportPreferenceKey(){return workspaceStorageKey('report-presentation',this.workspaceContext || getWorkspaceContext());},
    activeChartScenario() {
      return this.chartScenario || (knownChartScenario(this.inheritedStyleProfile)
        ? this.inheritedStyleProfile : defaultReportPresentation.styleProfile);
    },
    agentOptions() {
      return [
        {
          id: "",
          name: this.agentRequired ? "选择已授权 Agent" : this.defaultModelName || "通用对话",
          description: this.defaultModelName ? "默认模型" : "不绑定 Agent 设置",
          documentWorkflow: false,
        },
        ...this.agents
          .filter((agent) => agent?.marketStatus === "published")
          .map((agent) => ({
            ...agent,
            documentWorkflow: this.isDocumentWorkflowAgent(agent),
          })),
      ];
    },
    reasoningOptions() {
      return reasoningOptions(this.reasoningEfforts);
    },
    selectedReasoning() {
      return availableReasoning(this.reasoningEfforts, this.rememberedReasoning);
    },
    selectedAgent() {
      return (
        this.agentOptions.find((agent) => agent.id === this.selectedAgentId) || this.agentOptions[0]
      );
    },
    agentSelectLabel() {
      if (this.agentsLoading) {
        return "加载 Agent";
      }
      return this.selectedAgent?.name || "通用对话";
    },
    documentWorkflowActive() {
      return !!this.selectedAgent?.documentWorkflow;
    },
    agentSelectionValid() { return !!this.selectedAgentId && this.agentOptions.some(agent=>agent.id===this.selectedAgentId); },
    longText() {
      return exceedsChatTextLimit(this.draft);
    },
    primaryButtonDisabled() {
      if(this.agentRequired&&!this.agentSelectionValid)return true;
      if(this.inputDisabled)return true;
      return this.loading
        ? !this.stopAvailable
        : (!this.draft.trim() && !this.attachments.length) ||
            this.attachments.some((file) => file.state === "uploading");
    },
    primaryButtonLabel() {
      return this.loading && this.stopAvailable ? "停止当前任务" : "发送问题";
    },
  },
  watch: {
    selectedAgentId() { this.invalidateIdeas(); },
    conversationKey() {
      this.reportMode = "off";
      this.mention = null;
      this.selectedReferences = [];
      this.referenceError = "";
      this.invalidateIdeas();
    },
    ideaContext() { this.invalidateIdeas(); },
    attachments: {deep:true,handler(){this.invalidateIdeas();}},
    loading(value) { if(value)this.invalidateIdeas(); },
    reportPresentation: {deep:true,handler(value){saveWorkspaceDraft('report-presentation',this.workspaceContext || getWorkspaceContext(),reportPresentation(value));}},
    reportPreferenceKey() {
      const scope = this.workspaceContext || getWorkspaceContext();
      this.rememberedReasoning = readWorkspaceDraft('coding-reasoning', scope);
      this.reportPresentation = reportPresentation(readWorkspaceDraft('report-presentation', scope) || {});
      if (this.compact) this.chartScenario = savedChartScenario(scope);
    },
    modelValue(value) {
      this.draft = value;
      this.$nextTick(this.adjustTextareaHeight);
    },
    draft(value) {
      this.invalidateIdeas();
      this.$emit("update:modelValue", value);
      this.$nextTick(this.adjustTextareaHeight);
    },
  },
  mounted() {
    this.adjustTextareaHeight();
  },
  beforeUnmount(){this.invalidateIdeas();},
  methods: {
    handleDraftInput(event) {
      this.adjustTextareaHeight();
      if (!this.businessReferencesEnabled || event.isComposing) return;
      const end = event.target.selectionStart;
      const match = /(?:^|\s)@([^\s@]{0,48})$/.exec(event.target.value.slice(0, end));
      this.mention = match ? { start: end - match[1].length - 1, end, keyword: match[1] } : null;
    },
    openReferences() {
      if (this.mention) { this.mention = null; return; }
      this.referenceError = "";
      this.mention = { keyword: "" };
      this.focusComposer();
    },
    addReference(item) {
      const exists = this.selectedReferences.some((selected) => selected.type === item.type && selected.id === item.id);
      if (!exists && this.selectedReferences.length >= 8) {
        this.referenceError = "最多添加 8 项资料。";
        return;
      }
      const range = this.mention;
      if (range?.start !== undefined) {
        this.draft = this.draft.slice(0, range.start) + this.draft.slice(range.end);
        this.$nextTick(() => this.$refs.composerTextarea?.setSelectionRange(range.start, range.start));
      }
      if (!exists) this.selectedReferences.push(item);
      if (item.type === "skills" && !this.selectedAgent?.boundDomainSkillIds?.includes(item.id)) {
        const selectedSkills = this.selectedReferences.filter((entry) => entry.type === "skills");
        const compatible = this.agents.filter((agent) => agent.marketStatus === "published"
          && selectedSkills.every((entry) => agent.boundDomainSkillIds?.includes(entry.id)));
        if (compatible.length === 1) this.$emit("update:selectedAgentId", compatible[0].id);
      }
      this.mention = null;
      this.referenceError = "";
      this.focusComposer();
    },
    removeReference(item) {
      this.selectedReferences = this.selectedReferences.filter((selected) => selected !== item);
      this.referenceError = "";
    },
    moveReference(event, delta) {
      if (!this.mention || event.isComposing) return;
      event.preventDefault();
      this.$refs.referencePicker?.move(delta);
    },
    invalidateIdeas(){this.ideaSerial++;this.ideaController?.abort();this.ideaController=null;this.ideasOpen=false;this.ideasLoading=false;this.ideas=[];this.ideaError='';},
    toggleIdeas(){if(this.ideasOpen){this.invalidateIdeas();return;}return this.generateIdeas();},
    async generateIdeas(){
      this.ideaController?.abort();
      const controller=new AbortController();this.ideaController=controller;
      const serial=++this.ideaSerial;this.ideasOpen=true;this.ideasLoading=true;this.ideaError='';this.ideas=[];
      let timer,onAbort;
      try{
        if(!this.ideaProvider)throw new Error('提问建议服务暂不可用，请稍后重试。');
        const result=await Promise.race([
          this.ideaProvider({draft:this.draft,agentId:this.selectedAgentId,signal:controller.signal}),
          new Promise((_,reject)=>{
            onAbort=()=>reject(new Error('已取消建议生成'));
            controller.signal.addEventListener('abort',onAbort,{once:true});
            timer=setTimeout(()=>{reject(new Error('模型生成建议超时，请点击“重新生成”重试。'));controller.abort();},50000);
          })
        ]);
        if(serial!==this.ideaSerial)return;
        const ideas=Array.isArray(result?.ideas)?result.ideas:[];
        if(!ideas.length)throw new Error('暂未生成提问建议，请重试。');
        this.ideas=ideas;
      }catch(error){if(serial===this.ideaSerial)this.ideaError=error.message||'提问建议生成失败，请重试。';}
      finally{clearTimeout(timer);if(onAbort)controller.signal.removeEventListener('abort',onAbort);if(serial===this.ideaSerial){this.ideasLoading=false;this.ideaController=null;}}
    },
    pickFiles(event){const files=Array.from(event.target.files||[]);event.target.value='';if(files.length&&!this.loading&&!this.inputDisabled)this.$emit('files',files);},
    applyIdea(idea) {
      if(this.loading || this.inputDisabled)return;
      const original=this.draft;
      const prompt=original.trim()&&!idea.prompt.includes(original)?`${original}\n\n分析问题：\n${idea.prompt}`:idea.prompt;
      if(prompt.length>this.maxLength){this.ideaError=`建议加上原问题超过 ${this.maxLength} 字，请先精简当前问题。`;return;}
      this.draft=prompt;this.ideasOpen=false;this.ideaError='';this.focusComposer();
    },
    handlePrimaryAction() {
      if (this.loading) {
        if (this.stopAvailable) {
          this.$emit("stop");
        }
        return;
      }
      this.send();
    },
    changeReasoning(value) {
      if (!this.reasoningOptions.some(option => option.value === value)) return;
      this.rememberedReasoning=value;
      saveWorkspaceDraft('coding-reasoning',this.workspaceContext || getWorkspaceContext(),value);
    },
    changeChartScenario(value) {
      if (!knownChartScenario(value)) return;
      this.chartScenario = value;
      saveWorkspaceDraft('exploration-chart-style', this.workspaceContext || getWorkspaceContext(), value);
    },
    send() {
      if (this.businessReferencesEnabled && this.selectedReferences.length && !this.selectedAgentId) {
        this.referenceError = "请先选择已授权的助手。";
        return;
      }
      if(this.agentRequired&&!this.agentSelectionValid)return;
      const value = this.draft;
      if (
        (!value.trim() && !this.attachments.length) ||
        this.loading ||
        this.inputDisabled ||
        this.attachments.some((file) => file.state === "uploading")
      ) {
        return;
      }
      const reportMode = this.reportMode;
      this.reportMode = "off";
      const presentation = this.compact
        ? (this.chartScenario ? {styleProfile:this.chartScenario} : undefined)
        : (reportMode === "visual" ? reportPresentation(this.reportPresentation) : undefined);
      this.$emit("send", {
        query: value,
        ...(this.businessReferencesEnabled && this.selectedReferences.length ? { businessReferences: {
          skills: this.selectedReferences.filter((item) => item.type === "skills").map((item) => item.id),
          knowledgeBases: this.selectedReferences.filter((item) => item.type === "knowledgeBases").map((item) => item.id),
        } } : {}),
        agentId: this.selectedAgentId,
        agentName: this.selectedAgent?.name || "",
        documentWorkflow: this.documentWorkflowActive,
        ...(this.reasoningOptions?.length ? {codingReasoningEffort:this.selectedReasoning} : {}),
        reportMode,
          resourceExplorationApproved: this.resourceExplorationApproved,
        ...(presentation ? {reportPresentation:presentation} : {}),
      });
        this.resourceExplorationApproved = false;
    },
    handlePaste(event) {
      const files = pastedFiles(event.clipboardData);
      if (!files.length) return;
      event.preventDefault();
      if (!this.loading && !this.inputDisabled) this.$emit("files", files);
    },
    handleEnter(event) {
      if (event.isComposing || event.keyCode === 229) return;
      event.preventDefault();
      if (this.mention) { this.$refs.referencePicker?.choose(); return; }
      this.send();
    },
    handleDragOver(event) {
      if (Array.from(event.dataTransfer?.types || []).includes("Files")) event.preventDefault();
    },
    handleDrop(event) {
      if (!event.dataTransfer?.files?.length) return;
      event.preventDefault();
      if (!this.loading && !this.inputDisabled) this.$emit("files", Array.from(event.dataTransfer.files));
    },
    updateSelectedAgent(event) {
      this.$emit("update:selectedAgentId", event.target.value);
    },
    adjustTextareaHeight() {
      const textarea = this.$refs.composerTextarea;
      if (!textarea) {
        return;
      }
      const styles = window.getComputedStyle(textarea);
      const minHeight = this.cssPixelValue(styles.getPropertyValue("min-height"), 58);
      const maxHeight = this.cssPixelValue(styles.getPropertyValue("max-height"), 96);
      textarea.style.height = `${minHeight}px`;
      const nextHeight = Math.min(maxHeight, Math.max(minHeight, textarea.scrollHeight));
      textarea.style.height = `${nextHeight}px`;
      textarea.style.overflowY = textarea.scrollHeight > maxHeight ? "auto" : "hidden";
    },
    cssPixelValue(value, fallback) {
      const parsed = Number.parseFloat(String(value || ""));
      return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
    },
    focusComposer() {
      this.$nextTick(() => {
        this.$refs.composerTextarea?.focus();
        this.adjustTextareaHeight();
      });
    },
    isDocumentWorkflowAgent(agent) {
      const toolNames = [
        ...(agent?.resolvedToolNames || []),
        ...(agent?.boundMcpToolNames || []),
        ...(agent?.toolConfigs || []).map((config) => config?.toolName),
      ];
      return (
        !!agent?.boundDocumentCount ||
        (agent?.boundDocumentIds || []).length > 0 ||
        (agent?.boundDocumentTags || []).length > 0 ||
        toolNames.includes("document_search")
      );
    },
  },
};
