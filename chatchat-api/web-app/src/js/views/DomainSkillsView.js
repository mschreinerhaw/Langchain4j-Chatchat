import { nextTick } from "vue";
import {
  createDomainSkill, createDomainSkillCategory, deleteDomainSkill, fetchDomainSkills, getStoredAuthSession,
  importDomainSkill, publishDomainSkill, recallDomainSkill, reindexDomainSkill,
  reindexDomainSkillCategory, updateDomainSkill
} from "../../services/api.js";
import { formatDateTime } from "../utils/uiFormatters.js";
import "../../styles/pages/domain-skills.css";

const starter = `# 领域技能名称

## 适用场景

说明该技能适合解决的问题和使用边界。

## 工作步骤

1. 明确输入和目标。
2. 按领域规则分析并验证。
3. 输出结论、依据和风险提示。`;

const emptyForm = () => ({ id: "", name: "", category: "", description: "", markdownContent: starter });

export default {
  name: "DomainSkillsView",
  data: () => ({
    loading: true, busy: false, error: "", message: "", skills: [], categories: [],
    quota: { maximum: 5, published: 0, remaining: 5, source: "DEFAULT", limited: true, licenseValid: true },
    filters: { keyword: "", category: "", status: "", page: 0, pageSize: 12 },
    total: 0, skillCount: 0, totalPages: 0, editorOpen: false, importOpen: false, form: emptyForm(),
    importFile: null, importName: "", importCategory: "", categoryDialogOpen: false,
    newCategoryName: "", categorySaving: false, categoryError: ""
  }),
  computed: {
    isAdmin() {
      const session = getStoredAuthSession() || {};
      return String(session.username || session.userName || session.user?.username || "").toLowerCase() === "admin";
    },
    quotaLabel() {
      if (!this.quota.licenseValid) return "License 无效";
      if (!this.quota.limited) return `已发布 ${this.quota.published} 个 · 不限数量`;
      return `已发布 ${this.quota.published} / ${this.quota.maximum} 个 · 剩余 ${this.quota.remaining} 个`;
    },
    categoryOptions() {
      return this.categories.map((category) => typeof category === "string"
        ? { name: category, count: 0 }
        : { name: category?.name || "", count: Number(category?.count || 0) }).filter((category) => category.name);
    }
  },
  mounted() { this.load(); },
  methods: {
    formatTime: formatDateTime,
    async load(resetPage = false) {
      if (resetPage) this.filters.page = 0;
      this.loading = true; this.error = "";
      try {
        const payload = await fetchDomainSkills(this.filters);
        this.skills = Array.isArray(payload?.skills) ? payload.skills : [];
        this.categories = Array.isArray(payload?.categories) ? payload.categories : [];
        this.quota = payload?.quota || this.quota;
        this.total = Number(payload?.total || 0); this.skillCount = Number(payload?.skillCount ?? payload?.total ?? 0);
        this.totalPages = Number(payload?.totalPages || 0);
      } catch (error) { this.error = error.message || "领域技能加载失败"; }
      finally { this.loading = false; }
    },
    openCreate() {
      if (!this.categoryOptions.length) { this.openCategoryDialog(); return; }
      this.form = { ...emptyForm(), category: this.filters.category || this.categoryOptions[0].name };
      this.editorOpen = true;
      this.error = "";
    },
    openImport() {
      if (!this.categoryOptions.length) { this.openCategoryDialog(); return; }
      this.importCategory = this.filters.category || this.categoryOptions[0].name;
      this.importOpen = true;
      this.error = "";
    },
    async selectCategory(category) {
      this.filters.category = category;
      await this.load(true);
    },
    async openCategoryDialog() {
      this.newCategoryName = "";
      this.categoryDialogOpen = true;
      this.categoryError = "";
      this.error = "";
      this.message = "";
      await nextTick();
      this.$refs.categoryNameInput?.focus();
    },
    closeCategoryDialog() {
      if (this.categorySaving) return;
      this.categoryDialogOpen = false;
      this.newCategoryName = "";
      this.categoryError = "";
    },
    async saveCategory() {
      const name = this.newCategoryName.trim();
      if (!name) {
        this.categoryError = "请输入分类名称";
        this.$refs.categoryNameInput?.focus();
        return;
      }
      this.categorySaving = true;
      this.categoryError = "";
      try {
        const created = await createDomainSkillCategory(name);
        const categoryName = created?.name || name;
        this.categoryDialogOpen = false;
        this.newCategoryName = "";
        this.filters.category = categoryName;
        this.message = `分类“${categoryName}”已创建`;
        await this.load(true);
      } catch (error) {
        this.categoryError = error.message || "分类创建失败";
      } finally {
        this.categorySaving = false;
      }
    },
    openEdit(skill) { this.form = { id: skill.id, name: skill.name || "", category: skill.category || "", description: skill.description || "", markdownContent: skill.markdownContent || "" }; this.editorOpen = true; },
    async save() {
      if (!this.form.name.trim() || !this.form.category.trim() || !this.form.markdownContent.trim()) return;
      await this.perform(async () => {
        if (this.form.id) await updateDomainSkill(this.form.id, this.form); else await createDomainSkill(this.form);
        this.editorOpen = false; this.message = "领域技能草稿已保存"; await this.load();
      }, "领域技能保存失败");
    },
    chooseImport(event) { this.importFile = event.target.files?.[0] || null; },
    async importSkill() {
      if (!this.importFile || !this.importCategory.trim()) return;
      await this.perform(async () => {
        await importDomainSkill(this.importFile, this.importName.trim(), this.importCategory.trim());
        this.importOpen = false; this.importFile = null; this.importName = ""; this.importCategory = "";
        this.message = "技能包已导入为草稿"; await this.load(true);
      }, "技能包导入失败");
    },
    async publishSkill(skill) { await this.perform(async () => { const value = await publishDomainSkill(skill.id); this.message = `“${value.name}”已发布到领域技能索引`; await this.load(); }, "领域技能发布失败"); },
    async recallSkill(skill) { await this.perform(async () => { await recallDomainSkill(skill.id); this.message = `“${skill.name}”已回收，Agent 将不再加载该技能`; await this.load(); }, "领域技能回收失败"); },
    async reindexSkill(skill) {
      await this.perform(async () => {
        await reindexDomainSkill(skill.id);
        this.message = `“${skill.name}”索引已重建`;
      }, "领域技能索引重建失败");
    },
    async reindexCategory(category) {
      await this.perform(async () => {
        const result = await reindexDomainSkillCategory(category.name);
        this.message = `分类“${category.name}”索引重建完成：成功 ${result?.reindexed || 0}，跳过 ${result?.skipped || 0}，失败 ${result?.failed || 0}`;
      }, "分类索引重建失败");
    },
    async removeSkill(skill) {
      if (!window.confirm(`确定删除领域技能“${skill.name}”吗？`)) return;
      await this.perform(async () => { await deleteDomainSkill(skill.id); this.message = "领域技能已删除"; await this.load(); }, "领域技能删除失败");
    },
    async perform(action, fallback) { this.busy = true; this.error = ""; try { await action(); } catch (error) { this.error = error.message || fallback; } finally { this.busy = false; } },
    go(page) { if (page >= 0 && page < this.totalPages) { this.filters.page = page; this.load(); } }
  }
};
