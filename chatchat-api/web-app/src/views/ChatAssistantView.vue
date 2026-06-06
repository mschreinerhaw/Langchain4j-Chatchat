<template>
  <div class="chat-view">
    <section v-if="!hasConversation" class="hero-panel">
      <div class="hero-orb">
        <span>AI</span>
      </div>
      <div class="spark spark-one"></div>
      <div class="spark spark-two"></div>
      <h1>下午好，我是您的AI投资助手</h1>
      <p>基于专业数据分析，助您洞察市场，把握投资机会</p>
    </section>

    <ChatMessageList v-if="hasConversation" ref="messageList" :messages="messages" :loading="loading" />
    <ResponseReferences
      :sources="lastResponse.sources"
      :tool-traces="lastResponse.toolTraces"
    />
    <p v-if="errorMessage" class="chat-error">{{ errorMessage }}</p>

    <PromptComposer
      v-model="question"
      :suggestions="suggestions"
      :loading="loading"
      :show-suggestions="!hasConversation"
      @pick="question = $event"
      @send="handleSend"
      @clear="clearChat"
      @upload="handleUpload"
    />

    <p class="risk-note">内容由AI生成，仅供参考，不构成投资建议。市场有风险，投资需谨慎。</p>
  </div>
</template>

<script>
import ChatMessageList from "../components/ChatMessageList.vue";
import PromptComposer from "../components/PromptComposer.vue";
import ResponseReferences from "../components/ResponseReferences.vue";
import { saveConversationHistory, sendInteractionMessage } from "../services/api";

function uid() {
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export default {
  name: "ChatAssistantView",
  components: {
    ChatMessageList,
    PromptComposer,
    ResponseReferences
  },
  data() {
    return {
      question: "",
      loading: false,
      errorMessage: "",
      conversationId: "",
      userId: "mx_48991534",
      messages: [],
      lastResponse: {
        sources: [],
        toolTraces: []
      },
      suggestions: [
        "分析贵州茅台未来半年投资价值",
        "对比宁德时代和比亚迪",
        "近一周A股市场有哪些异动",
        "生成半导体行业日报",
        "解读最新中芯国际财报"
      ]
    };
  },
  computed: {
    hasConversation() {
      return this.messages.length > 0 || this.loading;
    }
  },
  methods: {
    async handleSend(payload) {
      const query = typeof payload === "string" ? payload.trim() : payload.query;
      if (!query || this.loading) {
        return;
      }

      this.errorMessage = "";
      this.messages.push({
        id: uid(),
        role: "user",
        content: query,
        timestamp: Date.now()
      });
      this.question = "";
      this.loading = true;
      this.scrollMessages();

      try {
        const response = await sendInteractionMessage({
          conversationId: this.conversationId || undefined,
          userId: this.userId,
          mode: "llm_chat",
          query,
          maxResults: 5,
          historyWindow: 8,
          stream: false,
          toolInput: {
            deepThinking: !!payload.deepThinking,
            webSearch: !!payload.webSearch
          }
        });

        this.conversationId = response.conversationId || this.conversationId;
        this.lastResponse = {
          sources: Array.isArray(response.sources) ? response.sources : [],
          toolTraces: Array.isArray(response.toolTraces) ? response.toolTraces : []
        };
        this.messages.push({
          id: uid(),
          role: "assistant",
          content: response.answer || "服务端没有返回内容。",
          timestamp: response.timestamp || Date.now(),
          latencyMs: response.latencyMs,
          traces: this.lastResponse.toolTraces
        });
        this.saveHistory(query);
      } catch (error) {
        const message = error.message || "请求后端失败";
        this.errorMessage = message;
      } finally {
        this.loading = false;
        this.scrollMessages();
      }
    },
    clearChat() {
      this.question = "";
      this.messages = [];
      this.conversationId = "";
      this.errorMessage = "";
      this.lastResponse = {
        sources: [],
        toolTraces: []
      };
    },
    handleUpload() {
      this.errorMessage = "文件上传接口尚未接入，当前仅支持文本对话。";
    },
    saveHistory(question) {
      saveConversationHistory({
        userId: this.userId,
        question,
        conversationId: this.conversationId,
        mode: "llm_chat",
        messages: this.messages.map((message) => ({
          role: message.role,
          content: message.content,
          timestamp: message.timestamp,
          traces: message.traces || []
        }))
      }).catch(() => {});
    },
    scrollMessages() {
      this.$nextTick(() => {
        const panel = this.$refs.messageList?.$el;
        if (panel) {
          panel.scrollTop = panel.scrollHeight;
        }
      });
    }
  }
};
</script>
