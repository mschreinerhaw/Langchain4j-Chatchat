import { Bot, FileText, Send, XCircle } from "@lucide/vue";
import { exceedsChatTextLimit, pastedFiles } from '../utils/chatUserAssets';

export default {
  name: "PromptComposer",
  components: {
    Bot,
    FileText,
    Send,
    XCircle
  },
  props: {
    attachments: {type:Array,default:()=>[]},
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
    defaultModelName: {
      type: String,
      default: ""
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
  emits: ["clear", "files", "retry-file", "remove-file", "pick", "send", "stop", "update:modelValue", "update:selectedAgentId"],
  data() {
    return {
      draft: this.modelValue
    };
  },
  computed: {
    agentOptions() {
      return [
        {
          id: "",
          name: this.defaultModelName || "通用对话",
          description: this.defaultModelName ? "默认模型" : "不绑定 Agent 设置",
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
        return "加载 Agent";
      }
      return this.selectedAgent?.name || "通用对话";
    },
    documentWorkflowActive() {
      return !!this.selectedAgent?.documentWorkflow;
    },
    longText(){return exceedsChatTextLimit(this.draft);},
    primaryButtonDisabled() {
      return this.loading ? !this.stopAvailable : (!this.draft.trim() && !this.attachments.length) || this.attachments.some(file=>file.state==='uploading');
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
      const value = this.draft;
      if ((!value.trim() && !this.attachments.length) || this.loading || this.attachments.some(file=>file.state==='uploading')) {
        return;
      }
      this.$emit("send", {
        query: value,
        agentId: this.selectedAgentId,
        agentName: this.selectedAgent?.name || "",
        documentWorkflow: this.documentWorkflowActive
      });
    },
    handlePaste(event){const files=pastedFiles(event.clipboardData);if(!files.length)return;event.preventDefault();if(!this.loading)this.$emit('files',files);},
    handleEnter(event){if(event.isComposing||event.keyCode===229)return;event.preventDefault();this.send();},
    handleDragOver(event){if(Array.from(event.dataTransfer?.types||[]).includes('Files'))event.preventDefault();},
    handleDrop(event){if(!event.dataTransfer?.files?.length)return;event.preventDefault();if(!this.loading)this.$emit('files',Array.from(event.dataTransfer.files));},
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
        ...(agent?.toolConfigs || []).map((config) => config?.toolName)
      ];
      return !!agent?.boundDocumentCount
        || (agent?.boundDocumentIds || []).length > 0
        || (agent?.boundDocumentTags || []).length > 0
        || toolNames.includes("document_search");
    }
  }
};
