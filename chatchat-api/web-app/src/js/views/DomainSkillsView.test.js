import { describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
  createDomainSkill: vi.fn(), deleteDomainSkill: vi.fn(), fetchDomainSkills: vi.fn(),
  getStoredAuthSession: vi.fn(() => ({ username: "admin" })), importDomainSkill: vi.fn(),
  publishDomainSkill: vi.fn(), recallDomainSkill: vi.fn(), updateDomainSkill: vi.fn()
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
});
