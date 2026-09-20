import { describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
  createDomainSkill: vi.fn(), createDomainSkillCategory: vi.fn(), deleteDomainSkill: vi.fn(), deleteDomainSkillCategory: vi.fn(), fetchDomainSkills: vi.fn(),
  getStoredAuthSession: vi.fn(() => ({ username: "admin" })), importDomainSkill: vi.fn(), importDomainSkillFromUrl: vi.fn(),
  publishDomainSkill: vi.fn(), recallDomainSkill: vi.fn(), reindexDomainSkill: vi.fn(),
  reindexDomainSkillCategory: vi.fn(), renameDomainSkillCategory: vi.fn(), updateDomainSkill: vi.fn()
}));
vi.mock("../../services/api.js", () => api);
import DomainSkillsView from "./DomainSkillsView.js";

describe("DomainSkillsView", () => {
  it("publishes into the dedicated domain skill index", async () => {
    api.publishDomainSkill.mockResolvedValue({ id: "skill-1", name: "风险识别" });
    const context = { busy: false, error: "", message: "", load: vi.fn(), perform: DomainSkillsView.methods.perform };
    await DomainSkillsView.methods.publishSkill.call(context, { id: "skill-1" });
    expect(api.publishDomainSkill).toHaveBeenCalledWith("skill-1");
    expect(context.message).toContain("领域技能索引"); expect(context.load).toHaveBeenCalledOnce();
  });

  it("shows the custom publication limit prompt when the quota is full", async () => {
    api.publishDomainSkill.mockRejectedValue(new Error("SKILL_LICENSE_LIMIT_EXCEEDED: publication limit 5 reached"));
    const context = {
      busy: false, error: "", message: "old", publicationLimitOpen: false,
      publicationLimit: {}, quota: { maximum: 5, published: 5 }, load: vi.fn()
    };

    await DomainSkillsView.methods.publishSkill.call(context, { id: "skill-6", name: "合规复核" });

    expect(context.publicationLimitOpen).toBe(true);
    expect(context.publicationLimit).toEqual({ maximum: 5, published: 5, skillName: "合规复核" });
    expect(context.error).toBe("");
    expect(context.load).not.toHaveBeenCalled();
  });

  it("creates and selects a standalone category", async () => {
    api.createDomainSkillCategory.mockResolvedValue({ name: "金融", count: 0 });
    const context = {
      newCategoryName: " 金融 ", categorySaving: false, categoryDialogOpen: true,
      filters: { category: "", page: 2 }, message: "", error: "", load: vi.fn()
    };
    await DomainSkillsView.methods.saveCategory.call(context);
    expect(api.createDomainSkillCategory).toHaveBeenCalledWith("金融");
    expect(context.filters.category).toBe("金融");
    expect(context.load).toHaveBeenCalledWith(true);
  });

  it("renames a category and keeps the active filter in sync", async () => {
    api.renameDomainSkillCategory.mockResolvedValue({ id: "category-1", name: "客户洞察", count: 2, manageable: true });
    const context = {
      categoryDialogMode: "rename", editingCategoryId: "category-1", editingCategoryOriginalName: "客户画像",
      newCategoryName: " 客户洞察 ", categorySaving: false, categoryDialogOpen: true, categoryError: "",
      filters: { category: "客户画像", page: 0 }, message: "", error: "", load: vi.fn()
    };

    await DomainSkillsView.methods.saveCategory.call(context);

    expect(api.renameDomainSkillCategory).toHaveBeenCalledWith("category-1", "客户洞察");
    expect(context.filters.category).toBe("客户洞察");
    expect(context.categoryDialogOpen).toBe(false);
  });

  it("deletes an empty category after custom confirmation", async () => {
    api.deleteDomainSkillCategory.mockResolvedValue(true);
    const category = { id: "category-1", name: "空分类", count: 0, manageable: true };
    const context = {
      confirmDialog: { open: true, kind: "delete-category", category },
      closeConfirmDialog: DomainSkillsView.methods.closeConfirmDialog,
      perform: DomainSkillsView.methods.perform, busy: false, error: "", message: "",
      filters: { category: "空分类", page: 0 }, load: vi.fn()
    };

    await DomainSkillsView.methods.confirmPendingAction.call(context);

    expect(api.deleteDomainSkillCategory).toHaveBeenCalledWith("category-1");
    expect(context.filters.category).toBe("");
    expect(context.load).toHaveBeenCalledWith(true);
  });

  it("opens skill creation instead of redirecting to category creation", () => {
    const context = {
      categoryOptions: [], filters: { category: "" }, form: null,
      editorOpen: false, error: "previous error"
    };

    DomainSkillsView.methods.openCreate.call(context);

    expect(context.editorOpen).toBe(true);
    expect(context.form.category).toBe("");
    expect(context.error).toBe("");
  });

  it("opens skill import instead of redirecting to category creation", () => {
    const context = {
      categoryOptions: [], filters: { category: "" }, importCategory: "old",
      importMode: "url", importFile: { name: "old.zip" }, importUrl: "https://old.example/SKILL.md",
      importName: "old", importOpen: false, error: "previous error"
    };

    DomainSkillsView.methods.openImport.call(context);

    expect(context.importOpen).toBe(true);
    expect(context.importMode).toBe("file");
    expect(context.importCategory).toBe("");
    expect(context.importFile).toBeNull();
    expect(context.importUrl).toBe("");
    expect(context.importName).toBe("");
    expect(context.importAdvancedOpen).toBe(false);
    expect(context.importHttpMethod).toBe("GET");
  });

  it("imports a skill from an internet address", async () => {
    api.importDomainSkillFromUrl.mockResolvedValue({ id: "skill-url" });
    const context = {
      busy: false, error: "", message: "", importMode: "url", importFile: null,
      importUrl: " https://skills.example/SKILL.md ", importName: "Internet Skill",
      importCategory: "Research", importOpen: true, importHttpMethod: "POST",
      importQueryParams: '{"version":"latest"}', importHeaders: '{"Authorization":"Bearer token"}',
      importRequestBody: '{"format":"markdown"}', importAllowPrivateNetwork: true, load: vi.fn(),
      perform: DomainSkillsView.methods.perform
    };

    await DomainSkillsView.methods.importSkill.call(context);

    expect(api.importDomainSkillFromUrl).toHaveBeenCalledWith(
      "https://skills.example/SKILL.md", "Internet Skill", "Research", {
        method: "POST", queryParams: { version: "latest" }, headers: { Authorization: "Bearer token" },
        body: '{"format":"markdown"}', allowPrivateNetwork: true
      });
    expect(context.importOpen).toBe(true);
    expect(context.importMessage).toContain("可继续导入");
    expect(context.load).toHaveBeenCalledWith(true);
  });

  it("keeps the create dialog open and switches the saved draft to edit mode", async () => {
    api.createDomainSkill.mockResolvedValue({ id: "skill-new" });
    const context = {
      busy: false, error: "", message: "", editorOpen: true, editorMessage: "",
      form: { id: "", name: "新技能", category: "研究", description: "", markdownContent: "# 新技能" },
      load: vi.fn(), perform: DomainSkillsView.methods.perform
    };

    await DomainSkillsView.methods.save.call(context);

    expect(context.editorOpen).toBe(true);
    expect(context.form.id).toBe("skill-new");
    expect(context.editorMessage).toContain("已保存");
    expect(context.load).toHaveBeenCalledOnce();
  });

  it("uses the styled confirmation dialog for unsaved editor content", () => {
    const context = {
      busy: false, editorOpen: true, editorSnapshot: "different",
      form: { id: "", name: "未保存技能", category: "研究", description: "", markdownContent: "# 内容" },
      confirmDialog: {}, openConfirmDialog: DomainSkillsView.methods.openConfirmDialog
    };

    DomainSkillsView.methods.requestCloseEditor.call(context);

    expect(context.editorOpen).toBe(true);
    expect(context.confirmDialog.open).toBe(true);
    expect(context.confirmDialog.kind).toBe("editor");
    expect(context.confirmDialog.title).toContain("未保存");
  });

  it("uses the styled danger confirmation dialog before deleting a skill", async () => {
    const context = { confirmDialog: {}, openConfirmDialog: DomainSkillsView.methods.openConfirmDialog };

    await DomainSkillsView.methods.removeSkill.call(context, { id: "skill-1", name: "客户画像" });

    expect(context.confirmDialog.open).toBe(true);
    expect(context.confirmDialog.kind).toBe("delete");
    expect(context.confirmDialog.danger).toBe(true);
    expect(context.confirmDialog.message).toContain("客户画像");
    expect(api.deleteDomainSkill).not.toHaveBeenCalled();
  });

  it("rebuilds one skill and one category index", async () => {
    api.reindexDomainSkill.mockResolvedValue({ id: "skill-1" });
    api.reindexDomainSkillCategory.mockResolvedValue({ reindexed: 2, skipped: 1, failed: 0 });
    const context = { busy: false, error: "", message: "", perform: DomainSkillsView.methods.perform };

    await DomainSkillsView.methods.reindexSkill.call(context, { id: "skill-1", name: "风险识别" });
    expect(api.reindexDomainSkill).toHaveBeenCalledWith("skill-1");
    await DomainSkillsView.methods.reindexCategory.call(context, { name: "合规风控" });
    expect(api.reindexDomainSkillCategory).toHaveBeenCalledWith("合规风控");
    expect(context.message).toContain("成功 2");
  });
});
