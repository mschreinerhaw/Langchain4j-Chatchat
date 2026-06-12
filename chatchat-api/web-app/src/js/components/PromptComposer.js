import { Bot, FileText, Globe, ImagePlus, Send, Trash2, Upload, XCircle } from "@lucide/vue";

export default {
  name: "PromptComposer",
  components: {
    Bot,
    FileText,
    Globe,
    ImagePlus,
    Send,
    Trash2,
    Upload,
    XCircle
  },
  props: {
    suggestions: {
      type: Array,
      default: () => []
    },
    modelValue: {
      type: String,
      default: ""
    },
    loading: {
      type: Boolean,
      default: false
    },
    agents: {
      type: Array,
      default: () => []
    },
    selectedAgentId: {
      type: String,
      default: ""
    },
    agentsLoading: {
      type: Boolean,
      default: false
    },
    showSuggestions: {
      type: Boolean,
      default: true
    },
    stopAvailable: {
      type: Boolean,
      default: false
    }
  },
  emits: ["clear", "image-upload", "pick", "send", "stop", "update:modelValue", "update:selectedAgentId", "upload"],
  data() {
    return {
      draft: this.modelValue,
      webSearch: false
    };
  },
  computed: {
    agentOptions() {
      return [
        {
          id: "",
          name: "通用对话",
          description: "不绑定 Agent 设置",
          documentWorkflow: false
        },
        ...this.agents
          .filter((agent) => agent?.marketStatus === "published")
          .map((agent) => ({
            ...agent,
            documentWorkflow: this.isDocumentWorkflowAgent(agent)
          }))
      ];
    },
    selectedAgent() {
      return this.agentOptions.find((agent) => agent.id === this.selectedAgentId) || this.agentOptions[0];
    },
    agentSelectLabel() {
      if (this.agentsLoading) {
        return "加载Agent";
      }
      return this.selectedAgent?.name || "通用对话";
    },
    documentWorkflowActive() {
      return !!this.selectedAgent?.documentWorkflow;
    },
    webSearchAvailable() {
      return this.isWebSearchAgent(this.selectedAgent);
    },
    primaryButtonDisabled() {
      return this.loading ? !this.stopAvailable : !this.draft.trim();
    },
    primaryButtonLabel() {
      return this.loading && this.stopAvailable ? "停止当前任务" : "发送问题";
    }
  },
  watch: {
    modelValue(value) {
      this.draft = value;
      this.$nextTick(this.adjustTextareaHeight);
    },
    draft(value) {
      this.$emit("update:modelValue", value);
      this.$nextTick(this.adjustTextareaHeight);
    },
    selectedAgentId() {
      if (!this.webSearchAvailable) {
        this.webSearch = false;
      }
    }
  },
  mounted() {
    this.adjustTextareaHeight();
  },
  methods: {
    handlePrimaryAction() {
      if (this.loading) {
        if (this.stopAvailable) {
          this.$emit("stop");
        }
        return;
      }
      this.send();
    },
    send() {
      const value = this.draft.trim();
      if (!value || this.loading) {
        return;
      }
      this.$emit("send", {
        query: value,
        agentId: this.selectedAgentId,
        agentName: this.selectedAgent?.name || "",
        documentWorkflow: this.documentWorkflowActive,
        webSearch: this.webSearch && this.webSearchAvailable
      });
    },
    toggleWebSearch() {
      if (!this.webSearchAvailable) {
        this.webSearch = false;
        return;
      }
      this.webSearch = !this.webSearch;
    },
    updateSelectedAgent(event) {
      this.$emit("update:selectedAgentId", event.target.value);
    },
    adjustTextareaHeight() {
      const textarea = this.$refs.composerTextarea;
      if (!textarea) {
        return;
      }
      const minHeight = 58;
      const maxHeight = 104;
      textarea.style.height = `${minHeight}px`;
      const nextHeight = Math.min(maxHeight, Math.max(minHeight, textarea.scrollHeight));
      textarea.style.height = `${nextHeight}px`;
      textarea.style.overflowY = textarea.scrollHeight > maxHeight ? "auto" : "hidden";
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
        ...(agent?.toolConfigs || []).map((config) => config?.toolName)
      ];
      return !!agent?.boundDocumentCount
        || (agent?.boundDocumentIds || []).length > 0
        || (agent?.boundDocumentTags || []).length > 0
        || toolNames.includes("document_search");
    },
    isWebSearchAgent(agent) {
      if (!agent?.id) {
        return true;
      }
      const toolNames = [
        ...(agent?.boundMcpToolNames || []),
        ...(agent?.toolConfigs || [])
          .filter((config) => config && config.enabled !== false)
          .map((config) => config?.toolName)
      ];
      return toolNames.some((toolName) => this.isWebSearchToolName(toolName));
    },
    isWebSearchToolName(toolName) {
      const value = String(toolName || "").toLowerCase();
      return value === "web_search" || value.endsWith("_web_search");
    }
  }
};
