import { describe, expect, it } from "vitest";
import {
  buildSkillRoleIndex,
  matchesSkillQuery,
  normalizeSkill
} from "./SkillRoleAuthorizationPanel";

describe("skill role authorization query", () => {
  it("matches a partial Chinese keyword across skill names, descriptions and tags", () => {
    const skills = [
      normalizeSkill({ id: "bond-doc", name: "固收文档查看", description: "债券资料检索" }, "AGENT_SKILL"),
      normalizeSkill({ id: "bond-morning", name: "晨间早报", skillTags: ["固收", "晨会"] }, "SKILL"),
      normalizeSkill({ id: "equity", name: "权益研究" }, "AGENT_SKILL")
    ];

    expect(skills.filter((skill) => matchesSkillQuery(skill, "固收")).map((skill) => skill.id))
      .toEqual(["bond-doc", "bond-morning"]);
  });

  it("builds both role-to-skill and skill-to-role views with deny and expiry semantics", () => {
    const roles = [
      { id: "role-a", roleName: "固收研究员", roleCode: "BOND_RESEARCH" },
      { id: "role-b", roleName: "投资经理", roleCode: "PM" }
    ];
    const agent = normalizeSkill({ id: "bond-doc", name: "固收文档查看" }, "AGENT_SKILL");
    const domain = normalizeSkill({ id: "bond-report", name: "固收晨间早报" }, "SKILL");
    const grants = [
      { resourceType: "SKILL", resourceId: "bond-report", principalType: "ROLE", principalId: "role-a", effect: "ALLOW", enabled: true },
      { resourceType: "AGENT_SKILL", resourceId: "*", principalType: "ROLE", principalId: "role-b", effect: "ALLOW", enabled: true },
      { resourceType: "AGENT_SKILL", resourceId: "bond-doc", principalType: "ROLE", principalId: "role-b", effect: "DENY", enabled: true },
      { resourceType: "SKILL", resourceId: "bond-report", principalType: "ROLE", principalId: "role-b", effect: "ALLOW", enabled: true, expiresAt: "2020-01-01T00:00:00Z" }
    ];

    const index = buildSkillRoleIndex({
      roles,
      skills: [agent, domain],
      grants,
      roleBindings: { "role-a": ["bond-doc"] }
    }, Date.parse("2026-09-29T00:00:00Z"));

    expect(index.byRole["role-a"].map((item) => item.skill.id)).toEqual(["bond-report", "bond-doc"]);
    expect(index.bySkill[agent.key].map((item) => item.role.id)).toEqual(["role-a"]);
    expect(index.byRole["role-b"]).toEqual([]);
  });
});
