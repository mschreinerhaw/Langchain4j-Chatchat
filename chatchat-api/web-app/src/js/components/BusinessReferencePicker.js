import { apiRequest } from "../../services/api";

const types = [
  { id: "skills", label: "技能", endpoint: "/interactions/resources/skills" },
  { id: "documents", label: "文档", endpoint: "/search" },
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
      this.pages = 0;
      this.cursor = 0;
      this.error = "";
      const { endpoint } = types.find((item) => item.id === this.type);
      const documents = this.type === "documents";
      const query = new URLSearchParams({ keyword: this.keyword || "", page: documents ? page + 1 : page, pageSize: 6 });
      if (!documents) query.set("summary", "true");
      try {
        const data = await apiRequest(endpoint + "?" + query, { signal: request.signal, workspaceContext: this.workspaceContext });
        if (request.signal.aborted) return;
        this.items = documents
          ? (data.results || []).map(document => ({ id: document.docId, name: document.title, category: document.source }))
          : data.skills || [];
        this.page = documents ? Math.max(0, (data.page || 1) - 1) : data.page || 0;
        this.pages = data.totalPages || 0;
      } catch (error) {
        if (!request.signal.aborted) this.error = documents && error.status === 403 ? "当前账号没有可选文档。" : error.message;
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
