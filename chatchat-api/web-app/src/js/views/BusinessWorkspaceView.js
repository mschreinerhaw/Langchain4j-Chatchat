import ChatAssistantView from "../../views/ChatAssistantView.vue";
import BusinessRoleNotes from "../../components/BusinessRoleNotes.vue";
import { apiRequest } from "../../services/api";

const lines = (value) => [...new Set(value.split("\n").map((item) => item.trim()).filter(Boolean))];

export default {
  name: "BusinessWorkspaceView",
  components: { BusinessRoleNotes, ChatAssistantView },
  props: {
    panel: { type: String, default: "chat" },
    title: { type: String, default: "" },
    welcome: { type: String, default: "" },
    defaultAgentId: { type: String, default: "" },
    recommendedQuestions: { type: String, default: "" },
    recommendedSkillIds: { type: String, default: "" },
    workGuides: { type: String, default: "" },
    knowledgeCategories: { type: String, default: "" },
    userId: String,
    tenantId: String,
    workspaceContext: Object,
    selectedConversation: Object,
    pendingDraft: Object,
    outcomeHistory: { type: Array, default: () => [] },
  },
  emits: ["conversation-active", "history-saved", "open-conversation", "update:panel"],
  data: () => ({
    draft: null, draftSerial: 0,
    skills: [], skillKeyword: "", skillsBusy: false, skillError: "", skillPage: 0, skillPages: 0,
    recommendationCards: [], agents: [],
    knowledge: [], knowledgeQuery: "", knowledgeCategory: "", knowledgeBusy: false,
    knowledgeError: "", knowledgePage: 1, knowledgeHasMore: false,
  }),
  computed: {
    questionList() { return lines(this.recommendedQuestions); },
    guideList() { return lines(this.workGuides); },
    categoryList() { return lines(this.knowledgeCategories); },
    recommendations() {
      const ids = lines(this.recommendedSkillIds);
      return ids.map((id) => this.recommendationCards.find((skill) => skill.id === id)).filter(Boolean);
    },
  },
  watch: {
    panel: {
      immediate: true,
      handler(panel) {
        if (panel === "skills") this.loadSkills(0);
        if (panel === "knowledge") this.loadKnowledge(1);
      },
    },
    selectedConversation(value) { if (value) this.openPanel("chat"); },
    pendingDraft(value) {
      if (!value) return;
      this.draft = null;
      this.openPanel("chat");
    },
  },
  mounted() {
    if (this.recommendedSkillIds) this.loadRecommendations();
  },
  methods: {
    openPanel(panel) {
      this.$emit("update:panel", panel);
    },
    async loadRecommendations() {
      const params = new URLSearchParams({ ids: lines(this.recommendedSkillIds).slice(0, 24).join(","), pageSize: 24, summary: true });
      try {
        const payload = await apiRequest("/interactions/resources/skills?" + params, { workspaceContext: this.workspaceContext });
        this.recommendationCards = payload?.skills || [];
      } catch {
        this.recommendationCards = [];
      }
    },
    async loadSkills(page) {
      if (this.skillsBusy) return;
      this.skillsBusy = true;
      this.skillError = "";
      this.skills = [];
      const params = new URLSearchParams({ keyword: this.skillKeyword.trim(), page, pageSize: 18, summary: true });
      const options = { workspaceContext: this.workspaceContext };
      try {
        const [payload, catalog] = await Promise.all([
          apiRequest("/interactions/resources/skills?" + params, options),
          apiRequest("/interactions/resources/agents", options),
        ]);
        this.skills = payload?.skills || [];
        this.agents = (catalog?.agents || []).filter((agent) => agent.marketStatus === "published");
        this.skillPage = payload?.page || 0;
        this.skillPages = payload?.totalPages || 0;
      } catch (error) {
        this.skillError = error.message;
      } finally {
        this.skillsBusy = false;
      }
    },
    async loadKnowledge(page) {
      if (this.knowledgeBusy) return;
      this.knowledgeBusy = true;
      this.knowledgeError = "";
      this.knowledge = [];
      const params = new URLSearchParams({
        keyword: this.knowledgeQuery.trim(), tag: this.knowledgeCategory, page, pageSize: 12,
      });
      try {
        const payload = await apiRequest("/search?" + params, { workspaceContext: this.workspaceContext });
        this.knowledge = payload?.results || [];
        this.knowledgePage = payload?.page || page;
        this.knowledgeHasMore = Boolean(payload?.hasMore);
      } catch (error) {
        this.knowledgeError = error.message;
      } finally {
        this.knowledgeBusy = false;
      }
    },
    eligibleAgents(skill) {
      return this.agents.filter((agent) => agent.boundDomainSkillIds?.includes(skill.id)
        && ["agent_chat", "role_chat", "tool_agent"].includes(agent.defaultMode));
    },
    openSkill(skill) {
      this.skillKeyword = skill.name;
      if (this.panel === "skills") this.loadSkills(0);
      else this.openPanel("skills");
    },
    prepareSkill(skill, agent, example = "") {
      this.prepareChat("请使用「" + skill.name + "」分析：" + example, agent);
    },
    prepareChat(prompt, agent) {
      this.openPanel("chat");
      this.draft = {
        id: "business-" + ++this.draftSerial,
        agentId: agent?.id,
        title: agent?.name,
        prompt,
        newSession: Boolean(agent),
      };
    },
    clearChat() {
      this.openPanel("chat");
      this.draft = null;
      this.$refs.chat?.clearChat();
    },
    openOutcome(item) {
      this.openPanel("chat");
      this.$emit("open-conversation", item);
    },
    outcomeStatus(status) {
      return { completed: "已完成", success: "已完成", running: "执行中", pending: "等待处理",
        failed: "失败", cancelled: "已取消", partial: "部分完成", empty: "无结果" }[status] || status || "状态待确认";
    },
  },
};
