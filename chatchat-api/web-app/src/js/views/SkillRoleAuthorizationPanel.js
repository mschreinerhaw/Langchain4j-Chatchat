import { fetchSkillRoleQuery } from "../../services/api";
const emptyPage = () => ({ items: [], total: 0, page: 1, totalPages: 1 });
export default {
  name: "SkillRoleAuthorizationPanel",
  props: { tenantId: { type: String, default: "" } },
  data() {
    return {
      mode: "role", query: "", relationQuery: "", resourceType: "", selected: null,
      catalog: emptyPage(), relations: emptyPage(), catalogPage: 1, relationPage: 1, pageSize: 20,
      catalogLoading: false, relationLoading: false, catalogError: "", relationError: "",
      catalogVersion: 0, relationVersion: 0, searchTimer: null, relationTimer: null,
      catalogAbort: null, relationAbort: null
    };
  },
  computed: {
    selectedName() { return this.selected?.name || ""; },
    busy() { return this.catalogLoading || this.relationLoading; }
  },
  watch: {
    tenantId() { this.reset(); },
    query() {
      this.invalidateCatalog();
      clearTimeout(this.searchTimer);
      this.searchTimer = setTimeout(() => { this.catalogPage = 1; this.loadCatalog(); }, 300);
    },
    relationQuery() {
      this.invalidateRelations();
      this.relationLoading = !!this.selected;
      clearTimeout(this.relationTimer);
      this.relationTimer = setTimeout(() => { this.relationPage = 1; this.loadRelations(); }, 300);
    },
    resourceType() {
      if (this.mode === "role") { this.relationPage = 1; this.loadRelations(); }
      else this.reset();
    }
  },
  mounted() { this.loadCatalog(); },
  beforeUnmount() {
    clearTimeout(this.searchTimer); clearTimeout(this.relationTimer);
    this.catalogAbort?.abort(); this.relationAbort?.abort();
    this.catalogVersion++; this.relationVersion++;
  },
  methods: {
    typeLabel(type) { return type === "AGENT_SKILL" ? "Agent" : "领域技能"; },
    key(item) { return (item.resource_type || "ROLE") + ":" + item.id; },
    invalidateRelations() {
      this.relationVersion++; this.relationAbort?.abort();
      this.relations = emptyPage(); this.relationError = ""; this.relationLoading = false;
    },
    invalidateCatalog() {
      this.catalogVersion++; this.catalogAbort?.abort(); this.catalog = emptyPage();
      this.selected = null; this.catalogError = ""; this.catalogLoading = true;
      this.invalidateRelations();
    },
    reset() {
      clearTimeout(this.searchTimer); clearTimeout(this.relationTimer);
      this.catalogPage = 1; this.relationPage = 1; this.selected = null;
      this.invalidateRelations(); this.loadCatalog();
    },
    selectMode(mode) {
      if (mode === this.mode) return;
      this.mode = mode; this.query = ""; this.relationQuery = ""; this.reset();
    },
    select(item) {
      this.selected = item; this.relationPage = 1;
      clearTimeout(this.relationTimer); this.loadRelations();
    },
    changePage(target, page) {
      const max = target === "catalog" ? this.catalog.totalPages : this.relations.totalPages;
      page = Math.max(1, Math.min(max, Math.floor(Number(page) || 1)));
      if (target === "catalog") { this.catalogPage = page; this.loadCatalog(); }
      else { this.relationPage = page; this.loadRelations(); }
    },
    resize() { this.catalogPage = 1; this.relationPage = 1; this.reload(); },
    reload() {
      clearTimeout(this.searchTimer); clearTimeout(this.relationTimer);
      this.loadCatalog();
    },
    async loadCatalog() {
      const version = ++this.catalogVersion;
      this.catalogAbort?.abort(); this.catalogAbort = new AbortController();
      const previousKey = this.selected ? this.key(this.selected) : "";
      this.selected = null; this.catalog = emptyPage(); this.invalidateRelations();
      this.catalogError = ""; this.catalogLoading = !!this.tenantId;
      if (!this.tenantId) return;
      try {
        const data = await fetchSkillRoleQuery({
          tenantId: this.tenantId, view: this.mode === "role" ? "roles" : "skills",
          query: this.query, resourceType: this.mode === "skill" ? this.resourceType : "",
          page: this.catalogPage, pageSize: this.pageSize
        }, this.catalogAbort.signal);
        if (version !== this.catalogVersion) return;
        this.catalog = data; this.catalogPage = data.page;
        const selected = data.items.find(item => this.key(item) === previousKey) || data.items[0];
        if (selected) this.select(selected);
      } catch (error) {
        if (version === this.catalogVersion && error?.name !== "AbortError") this.catalogError = error?.message || "对象列表加载失败";
      } finally { if (version === this.catalogVersion) this.catalogLoading = false; }
    },
    async loadRelations() {
      const version = ++this.relationVersion;
      this.relationAbort?.abort(); this.relationAbort = new AbortController();
      this.relations = emptyPage(); this.relationError = "";
      this.relationLoading = !!this.selected;
      if (!this.selected) return;
      try {
        const data = await fetchSkillRoleQuery({
          tenantId: this.tenantId, view: "relations", query: this.relationQuery,
          roleId: this.mode === "role" ? this.selected.id : "",
          skillId: this.mode === "skill" ? this.selected.id : "",
          resourceType: this.mode === "skill" ? this.selected.resource_type : this.resourceType,
          page: this.relationPage, pageSize: this.pageSize
        }, this.relationAbort.signal);
        if (version === this.relationVersion) { this.relations = data; this.relationPage = data.page; }
      } catch (error) {
        if (version === this.relationVersion && error?.name !== "AbortError") this.relationError = error?.message || "授权明细加载失败";
      } finally { if (version === this.relationVersion) this.relationLoading = false; }
    }
  }
};
