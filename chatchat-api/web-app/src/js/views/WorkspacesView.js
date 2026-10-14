import {
  ArrowUpRight, Boxes, Check, ChevronRight, CircleCheck, FlaskConical, LayoutGrid,
  Layers3, LoaderCircle, RefreshCw, Search, SearchX, SlidersHorizontal, Sparkles, Star, X
} from "@lucide/vue";
import AppPagination from "../../components/AppPagination.vue";

const CATEGORY_DEFINITIONS = [
  { id: "assistant", label: "智能协作", icon: Sparkles },
  { id: "studio", label: "数据开发", icon: FlaskConical },
  { id: "other", label: "其他应用", icon: Boxes }
];
function categoryFor(workspace) {
  return CATEGORY_DEFINITIONS.find(category => category.id === workspace.layoutType) || CATEGORY_DEFINITIONS[2];
}

export default {
  name: "WorkspacesView",
  components: { AppPagination, ArrowUpRight, Check, ChevronRight, CircleCheck, Layers3, LoaderCircle,
    RefreshCw, Search, SearchX, SlidersHorizontal, Star, X },
  props: {
    workspaces: { type: Array, default: () => [] },
    defaultWorkspaceId: { type: String, default: "" },
    loading: Boolean,
    notice: { type: String, default: "" }
  },
  emits: ["switch-workspace", "default-workspace", "reload-workspaces"],
  data() { return { searchQuery: "", categoryFilter: "all", page: 1, pageSize: 6 }; },
  computed: {
    categories() {
      return [{ id: "all", label: "全部空间", icon: LayoutGrid, count: this.workspaces.length },
        ...CATEGORY_DEFINITIONS.map(category => ({ ...category,
          count: this.workspaces.filter(workspace => categoryFor(workspace).id === category.id).length
        })).filter(category => category.count > 0)];
    },
    activeCategoryLabel() { return this.categories.find(category => category.id === this.categoryFilter)?.label || "全部空间"; },
    hasFilters() { return Boolean(this.searchQuery.trim()) || this.categoryFilter !== "all"; },
    filteredWorkspaces() {
      const query = this.searchQuery.trim().toLocaleLowerCase();
      return this.workspaces.filter(workspace => {
        const category = categoryFor(workspace);
        return (this.categoryFilter === "all" || category.id === this.categoryFilter)
          && (!query || [workspace.name, workspace.description, category.label]
            .some(text => String(text || "").toLocaleLowerCase().includes(query)));
      });
    },
    pageCount() { return Math.max(1, Math.ceil(this.filteredWorkspaces.length / this.pageSize)); },
    currentPage() { return Math.min(this.page, this.pageCount); },
    pagedWorkspaces() { return this.filteredWorkspaces.slice((this.currentPage - 1) * this.pageSize, this.currentPage * this.pageSize); }
  },
  watch: {
    searchQuery() { this.page = 1; },
    categoryFilter() { this.page = 1; },
    pageSize() { this.page = 1; },
    workspaces() {
      if (!this.categories.some(category => category.id === this.categoryFilter)) this.categoryFilter = "all";
      this.page = this.currentPage;
    }
  },
  methods: {
    workspaceCategory: categoryFor,
    workspaceIcon(workspace) {
      return { "layout-dashboard": LayoutGrid, flask: FlaskConical, sparkles: Sparkles,
        layers: Layers3, boxes: Boxes }[workspace.icon] || categoryFor(workspace).icon;
    },
    resetFilters() { this.searchQuery = ""; this.categoryFilter = "all"; this.page = 1; }
  }
};
