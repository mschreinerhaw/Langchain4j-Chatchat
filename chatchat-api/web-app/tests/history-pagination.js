import { createApp, h } from "vue";
import AssistantSidebar from "../src/components/AssistantSidebar.vue";
import "../src/styles/app.css";
import "../src/styles/layout.css";
import "../src/styles/base.css";

const records = (page) => Array.from({ length: page === 3 ? 1 : 10 }, (_, index) => ({
  id: `${page}-${index}`, question: `Page ${page} conversation ${index}`, createdAt: "2026-10-06T10:00:00Z"
}));
createApp({
  components: { AssistantSidebar },
  data: () => ({ items: records(1), page: 1, loading: false }),
  methods: {
    async load({ page }) {
      this.loading = true;
      await new Promise(resolve => setTimeout(resolve, 700));
      this.items = records(page);
      this.page = page;
      this.loading = false;
    }
  },
  render() {
    return h(AssistantSidebar, { activeView: "chat", historyManagerItems: this.items,
      historyManagerPage: this.page, historyManagerTotal: 21, historyManagerPageCount: 3,
      historyManagerLoading: this.loading, onLoadHistoryManager: this.load });
  }
}).mount("#app");
