import { apiRequest } from "../../services/api";

const types = [
  { id: "skills", label: "技能", endpoint: "/interactions/resources/skills" },
  { id: "knowledgeBases", label: "知识库", endpoint: "/workspaces/business/knowledge-bases" },
];

export default {
  props: { keyword: String, workspaceContext: Object },
  emits: ["select"],
  data: () => ({ types, type: "skills", items: [], cursor: 0, page: 0, pages: 0, busy: false, error: "", request: null }),
  watch: {
    keyword: { immediate: true, handler() { this.load(0); } },
  },
  beforeUnmount() { this.request?.abort(); },
  methods: {
    selectType(type) {
      if (this.type === type) return;
      this.type = type;
      this.load(0);
    },
    async load(page) {
      this.request?.abort();
      const request = new AbortController();
      this.request = request;
      this.busy = true;
      this.items = [];
      this.cursor = 0;
      this.error = "";
      const { endpoint } = types.find((item) => item.id === this.type);
      const query = new URLSearchParams({ keyword: this.keyword || "", page, pageSize: 6, summary: true });
      try {
        const data = await apiRequest(endpoint + "?" + query, { signal: request.signal, workspaceContext: this.workspaceContext });
        if (request.signal.aborted) return;
        this.items = data.skills || data.items || [];
        this.page = data.page || 0;
        this.pages = data.totalPages || 0;
      } catch (error) {
        if (!request.signal.aborted) this.error = error.message;
      } finally {
        if (!request.signal.aborted) this.busy = false;
      }
    },
    move(delta) {
      if (this.items.length) this.cursor = (this.cursor + delta + this.items.length) % this.items.length;
    },
    choose(item = this.items[this.cursor]) {
      if (item) this.$emit("select", { id: item.id, name: item.name, type: this.type });
    },
  },
};
