import { describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
  createDomainSkill: vi.fn(), createDomainSkillCategory: vi.fn(), deleteDomainSkill: vi.fn(), fetchDomainSkills: vi.fn(),
  getStoredAuthSession: vi.fn(() => ({ username: "admin" })), importDomainSkill: vi.fn(), importDomainSkillFromUrl: vi.fn(),
  publishDomainSkill: vi.fn(), recallDomainSkill: vi.fn(), reindexDomainSkill: vi.fn(),
  reindexDomainSkillCategory: vi.fn(), updateDomainSkill: vi.fn()
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
  });

  it("imports a skill from an internet address", async () => {
    api.importDomainSkillFromUrl.mockResolvedValue({ id: "skill-url" });
    const context = {
      busy: false, error: "", message: "", importMode: "url", importFile: null,
      importUrl: " https://skills.example/SKILL.md ", importName: "Internet Skill",
      importCategory: "Research", importOpen: true, load: vi.fn(),
      perform: DomainSkillsView.methods.perform
    };

    await DomainSkillsView.methods.importSkill.call(context);

    expect(api.importDomainSkillFromUrl).toHaveBeenCalledWith(
      "https://skills.example/SKILL.md", "Internet Skill", "Research");
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
