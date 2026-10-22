import { authApi } from "../../services/api";
import "../../styles/admin-users.css";
export default {
  name: "AdminLoginAuditsView",
  emits: ["error"],
  data: () => ({
    rows: [],
    total: 0,
    page: 1,
    pageSize: 20,
    keyword: "",
    result: "",
    loading: false,
    serial: 0,
  }),
  mounted() {
    this.load();
  },
  methods: {
    async load() {
      const serial = ++this.serial;
      this.loading = true;
      try {
        const data = await authApi.loginAudits({
          keyword: this.keyword,
          result: this.result,
          page: this.page,
          pageSize: this.pageSize,
        });
        if (serial !== this.serial) return;
        this.rows = data.items;
        this.total = data.total;
      } catch (error) {
        this.$emit("error", error);
      } finally {
        if (serial === this.serial) this.loading = false;
      }
    },
    search() {
      this.page = 1;
      this.load();
    },
    changePage(value) {
      this.page = value;
      this.load();
    },
    changeSize(value) {
      this.pageSize = value;
      this.search();
    },
    formatTime(value) {
      return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "-";
    },
    reason(row) {
      try {
        return JSON.parse(row.detail || "{}").reason || "-";
      } catch {
        return "-";
      }
    },
  },
};
