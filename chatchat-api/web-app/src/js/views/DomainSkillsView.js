import {
  createDomainSkill, deleteDomainSkill, fetchDomainSkills, getStoredAuthSession,
  importDomainSkill, publishDomainSkill, recallDomainSkill, updateDomainSkill
} from "../../services/api.js";
import { formatDateTime } from "../utils/uiFormatters.js";
import "../../styles/pages/domain-skills.css";
import "../../styles/pages/domain-skills-extensions.css";

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
    total: 0, totalPages: 0, editorOpen: false, importOpen: false, form: emptyForm(),
    importFile: null, importName: "", importCategory: ""
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
        this.total = Number(payload?.total || 0); this.totalPages = Number(payload?.totalPages || 0);
      } catch (error) { this.error = error.message || "领域技能加载失败"; }
      finally { this.loading = false; }
    },
    openCreate() { this.form = emptyForm(); this.editorOpen = true; this.error = ""; },
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
    async removeSkill(skill) {
      if (!window.confirm(`确定删除领域技能“${skill.name}”吗？`)) return;
      await this.perform(async () => { await deleteDomainSkill(skill.id); this.message = "领域技能已删除"; await this.load(); }, "领域技能删除失败");
    },
    async perform(action, fallback) { this.busy = true; this.error = ""; try { await action(); } catch (error) { this.error = error.message || fallback; } finally { this.busy = false; } },
    go(page) { if (page >= 0 && page < this.totalPages) { this.filters.page = page; this.load(); } }
  }
};
