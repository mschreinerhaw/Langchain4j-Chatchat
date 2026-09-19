import { describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
  createDomainSkill: vi.fn(), createDomainSkillCategory: vi.fn(), deleteDomainSkill: vi.fn(), fetchDomainSkills: vi.fn(),
  getStoredAuthSession: vi.fn(() => ({ username: "admin" })), importDomainSkill: vi.fn(),
  publishDomainSkill: vi.fn(), recallDomainSkill: vi.fn(), reindexDomainSkill: vi.fn(),
  reindexDomainSkillCategory: vi.fn(), updateDomainSkill: vi.fn()
}));
vi.mock("../../services/api.js", () => api);
import DomainSkillsView from "./DomainSkillsView.js";

describe("DomainSkillsView", () => {
  it("shows the default five-skill publication quota", () => {
    const label = DomainSkillsView.computed.quotaLabel.call({ quota: { licenseValid: true, limited: true, published: 2, maximum: 5, remaining: 3 } });
    expect(label).toContain("2 / 5"); expect(label).toContain("剩余 3");
  });

  it("publishes into the dedicated domain skill index", async () => {
    api.publishDomainSkill.mockResolvedValue({ id: "skill-1", name: "风险识别" });
    const context = { busy: false, error: "", message: "", load: vi.fn(), perform: DomainSkillsView.methods.perform };
    await DomainSkillsView.methods.publishSkill.call(context, { id: "skill-1" });
    expect(api.publishDomainSkill).toHaveBeenCalledWith("skill-1");
    expect(context.message).toContain("领域技能索引"); expect(context.load).toHaveBeenCalledOnce();
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
      importFile: { name: "old.zip" }, importName: "old", importOpen: false, error: "previous error"
    };

    DomainSkillsView.methods.openImport.call(context);

    expect(context.importOpen).toBe(true);
    expect(context.importCategory).toBe("");
    expect(context.importFile).toBeNull();
    expect(context.importName).toBe("");
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
