import { nextTick } from "vue";
import {
  createDomainSkill, createDomainSkillCategory, deleteDomainSkill, fetchDomainSkills, getStoredAuthSession,
  importDomainSkill, importDomainSkillFromUrl, publishDomainSkill, recallDomainSkill, reindexDomainSkill,
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
const editorStateKey = (form = {}) => JSON.stringify({
  id: form.id || "", name: form.name || "", category: form.category || "",
  description: form.description || "", markdownContent: form.markdownContent || ""
});
const importStateKey = (state = {}) => JSON.stringify({
  mode: state.importMode || "file", name: state.importName || "", category: state.importCategory || "",
  url: state.importUrl || "", fileName: state.importFile?.name || "", fileSize: state.importFile?.size || 0,
  fileModified: state.importFile?.lastModified || 0
});

export default {
  name: "DomainSkillsView",
  data: () => ({
    loading: true, busy: false, error: "", message: "", skills: [], categories: [],
    quota: { maximum: 5, published: 0, remaining: 5, source: "DEFAULT", limited: true, licenseValid: true },
    filters: { keyword: "", category: "", status: "", page: 0, pageSize: 12 },
    total: 0, skillCount: 0, totalPages: 0, editorOpen: false, importOpen: false, form: emptyForm(),
    importMode: "file", importFile: null, importUrl: "", importName: "", importCategory: "", categoryDialogOpen: false,
    newCategoryName: "", categorySaving: false, categoryError: "", publicationLimitOpen: false,
    publicationLimit: { maximum: 5, published: 5, skillName: "" }, editorMessage: "", importMessage: "",
    editorSnapshot: "", importSnapshot: "",
    confirmDialog: { open: false, kind: "", title: "", message: "", confirmLabel: "确定", danger: false, skill: null }
  }),
  computed: {
    isAdmin() {
      const session = getStoredAuthSession() || {};
      return String(session.username || session.userName || session.user?.username || "").toLowerCase() === "admin";
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
      this.form = { ...emptyForm(), category: this.filters.category || this.categoryOptions[0]?.name || "" };
      this.editorSnapshot = editorStateKey(this.form);
      this.editorOpen = true;
      this.editorMessage = "";
      this.error = "";
    },
    openImport() {
      this.importCategory = this.filters.category || this.categoryOptions[0]?.name || "";
      this.importMode = "file";
      this.importFile = null;
      this.importUrl = "";
      this.importName = "";
      this.importSnapshot = importStateKey(this);
      this.importOpen = true;
      this.importMessage = "";
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
    openEdit(skill) { this.form = { id: skill.id, name: skill.name || "", category: skill.category || "", description: skill.description || "", markdownContent: skill.markdownContent || "" }; this.editorSnapshot = editorStateKey(this.form); this.editorMessage = ""; this.editorOpen = true; },
    async save() {
      if (!this.form.name.trim() || !this.form.category.trim() || !this.form.markdownContent.trim()) return;
      await this.perform(async () => {
        const saved = this.form.id
          ? await updateDomainSkill(this.form.id, this.form)
          : await createDomainSkill(this.form);
        if (saved?.id) this.form.id = saved.id;
        this.editorSnapshot = editorStateKey(this.form);
        this.editorMessage = "领域技能草稿已保存";
        await this.load();
      }, "领域技能保存失败");
    },
    chooseImport(event) { this.importFile = event.target.files?.[0] || null; this.importMessage = ""; },
    async importSkill() {
      const category = this.importCategory.trim();
      const url = this.importUrl.trim();
      if (!category || (this.importMode === "file" ? !this.importFile : !url)) return;
      await this.perform(async () => {
        if (this.importMode === "url") {
          await importDomainSkillFromUrl(url, this.importName.trim(), category);
        } else {
          await importDomainSkill(this.importFile, this.importName.trim(), category);
        }
        this.importFile = null; this.importUrl = ""; this.importName = "";
        if (this.$refs?.importFileInput) this.$refs.importFileInput.value = "";
        this.importSnapshot = importStateKey(this);
        this.importMessage = "技能包已导入为草稿，可继续导入其他技能";
        await this.load(true);
      }, "技能包导入失败");
    },
    requestCloseEditor() {
      if (this.busy) return;
      if (editorStateKey(this.form) !== this.editorSnapshot) {
        this.openConfirmDialog("editor", "放弃未保存的修改？", "当前技能内容尚未保存，关闭后修改将无法恢复。", "放弃修改", true);
        return;
      }
      this.editorOpen = false;
    },
    requestCloseImport() {
      if (this.busy) return;
      if (importStateKey(this) !== this.importSnapshot) {
        this.openConfirmDialog("import", "放弃当前导入？", "已填写的导入内容尚未提交，关闭后需要重新选择或填写。", "放弃导入", true);
        return;
      }
      this.importOpen = false;
    },
    openConfirmDialog(kind, title, message, confirmLabel = "确定", danger = false, skill = null) {
      this.confirmDialog = { open: true, kind, title, message, confirmLabel, danger, skill };
    },
    closeConfirmDialog() {
      this.confirmDialog = { open: false, kind: "", title: "", message: "", confirmLabel: "确定", danger: false, skill: null };
    },
    async confirmPendingAction() {
      const { kind, skill } = this.confirmDialog;
      this.closeConfirmDialog();
      if (kind === "editor") this.editorOpen = false;
      if (kind === "import") this.importOpen = false;
      if (kind === "delete" && skill) {
        await this.perform(async () => {
          await deleteDomainSkill(skill.id);
          this.message = "领域技能已删除";
          await this.load();
        }, "领域技能删除失败");
      }
    },
    async publishSkill(skill) {
      this.busy = true; this.error = ""; this.message = "";
      try {
        const value = await publishDomainSkill(skill.id);
        this.message = `“${value.name}”已发布到领域技能索引`;
        await this.load();
      } catch (error) {
        if (String(error?.message || "").includes("SKILL_LICENSE_LIMIT_EXCEEDED")) {
          this.publicationLimit = {
            maximum: Number(this.quota?.maximum || 5),
            published: Number(this.quota?.published || this.quota?.maximum || 5),
            skillName: skill?.name || ""
          };
          this.publicationLimitOpen = true;
        } else {
          this.error = error?.message || "领域技能发布失败";
        }
      } finally {
        this.busy = false;
      }
    },
    closePublicationLimit() { this.publicationLimitOpen = false; },
    async viewPublishedSkills() {
      this.publicationLimitOpen = false;
      this.filters.status = "PUBLISHED";
      await this.load(true);
    },
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
      this.openConfirmDialog("delete", "删除领域技能？", `确定删除“${skill.name}”吗？删除后无法恢复。`, "删除", true, skill);
    },
    async perform(action, fallback) { this.busy = true; this.error = ""; try { await action(); } catch (error) { this.error = error.message || fallback; } finally { this.busy = false; } },
    go(page) { if (page >= 0 && page < this.totalPages) { this.filters.page = page; this.load(); } }
  }
};
