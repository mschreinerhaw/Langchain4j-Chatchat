<template>
  <section class="prompt-card">
    <h2 v-if="showSuggestions">您可以这样提问</h2>
    <div v-if="showSuggestions" class="prompt-suggestions">
      <button
        v-for="suggestion in suggestions"
        :key="suggestion"
        type="button"
        @click="$emit('pick', suggestion)"
      >
        {{ suggestion }}
      </button>
    </div>

    <form class="composer-box" @submit.prevent="send">
      <textarea
        v-model="draft"
        rows="4"
        :disabled="loading"
        placeholder="请输入您的问题，或使用 @ 调用能力，Shift + Enter 换行"
        @keydown.enter.exact.prevent="send"
      ></textarea>

      <div class="composer-toolbar">
        <button type="button" class="tool-button" :class="{ active: deepThinking }" @click="toggleDeepThinking">
          <Funnel class="tool-icon" :size="16" stroke-width="1.9" />
          深度思考(R1)
          <ChevronDown class="small-caret" :size="14" stroke-width="2" />
        </button>
        <button type="button" class="tool-button" :class="{ active: webSearch }" @click="toggleWebSearch">
          <Globe class="tool-icon" :size="16" stroke-width="1.9" />
          联网搜索
        </button>
        <button type="button" class="tool-button" @click="$emit('upload')">
          <Upload class="tool-icon" :size="16" stroke-width="1.9" />
          上传文件
        </button>
        <button type="button" class="tool-button clear" @click="$emit('clear')">
          <Trash2 class="tool-icon" :size="16" stroke-width="1.9" />
          清空对话
        </button>
      </div>

      <button class="send-button" type="submit" aria-label="发送问题" :disabled="loading || !draft.trim()">
        <Send :size="18" stroke-width="2" />
      </button>
    </form>
  </section>
</template>

<script>
import { ChevronDown, Funnel, Globe, Send, Trash2, Upload } from "@lucide/vue";

export default {
  name: "PromptComposer",
  components: {
    ChevronDown,
    Funnel,
    Globe,
    Send,
    Trash2,
    Upload
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
    showSuggestions: {
      type: Boolean,
      default: true
    }
  },
  emits: ["clear", "pick", "send", "update:modelValue", "upload"],
  data() {
    return {
      draft: this.modelValue,
      deepThinking: true,
      webSearch: false
    };
  },
  watch: {
    modelValue(value) {
      this.draft = value;
    },
    draft(value) {
      this.$emit("update:modelValue", value);
    }
  },
  methods: {
    send() {
      const value = this.draft.trim();
      if (!value || this.loading) {
        return;
      }
      this.$emit("send", {
        query: value,
        deepThinking: this.deepThinking,
        webSearch: this.webSearch
      });
    },
    toggleDeepThinking() {
      this.deepThinking = !this.deepThinking;
    },
    toggleWebSearch() {
      this.webSearch = !this.webSearch;
    }
  }
};
</script>
