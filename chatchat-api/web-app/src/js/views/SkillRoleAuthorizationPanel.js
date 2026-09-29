import {
  fetchDomainSkills,
  fetchResourceGrants,
  fetchRoleAuthorization
} from "../../services/api";

const AGENT_SKILL = "AGENT_SKILL";
const DOMAIN_SKILL = "SKILL";

function text(value) {
  if (Array.isArray(value)) return value.map(text).join(" ");
  if (value && typeof value === "object") return Object.values(value).map(text).join(" ");
  return String(value ?? "");
}

export function normalizeSkill(skill, resourceType) {
  const id = String(skill?.id || skill?.skillId || "").trim();
  const name = String(skill?.name || skill?.label || skill?.displayName || id).trim();
  const searchText = [
    id, name, skill?.description, skill?.category, skill?.status, skill?.marketStatus,
    skill?.tags, skill?.skillTags, skill?.usageScenarios
  ].map(text).join(" ").toLocaleLowerCase();
  return {
    ...skill,
    id,
    name,
    resourceType,
    key: `${resourceType}:${id}`,
    typeLabel: resourceType === AGENT_SKILL ? "Agent Skill" : "领域 Skill",
    searchText
  };
}

export function matchesSkillQuery(skill, query) {
  const normalized = String(query || "").trim().toLocaleLowerCase();
  return !normalized || String(skill?.searchText || "").includes(normalized);
}

function activeGrant(grant, now) {
  if (!grant?.enabled || grant?.principalType !== "ROLE") return false;
  if (!grant.expiresAt) return true;
  const expiresAt = new Date(grant.expiresAt).getTime();
  return Number.isFinite(expiresAt) && expiresAt > now;
}

function grantMatches(grant, skill) {
  return grant.resourceType === skill.resourceType
    && (grant.resourceId === "*" || grant.resourceId === skill.id);
}

export function buildSkillRoleIndex({ roles = [], skills = [], grants = [], roleBindings = {} }, now = Date.now()) {
  const active = grants.filter((grant) => activeGrant(grant, now));
  const byRole = {};
  const bySkill = {};
  roles.forEach((role) => { byRole[role.id] = []; });
  skills.forEach((skill) => { bySkill[skill.key] = []; });

  roles.forEach((role) => {
    const bindings = new Set(roleBindings[role.id] || []);
    skills.forEach((skill) => {
      const relevant = active.filter((grant) => grant.principalId === role.id && grantMatches(grant, skill));
      const denied = relevant.some((grant) => grant.effect === "DENY");
      if (denied) return;
      const explicitlyGranted = relevant.some((grant) => grant.effect === "ALLOW");
      const roleBound = skill.resourceType === AGENT_SKILL && bindings.has(skill.id);
      if (!explicitlyGranted && !roleBound) return;
      const sources = [
        ...(roleBound ? ["角色绑定"] : []),
        ...(explicitlyGranted ? ["资源授权"] : [])
      ];
      const association = { role, skill, sources };
      byRole[role.id].push(association);
      bySkill[skill.key].push(association);
    });
  });
  Object.values(byRole).forEach((items) => items.sort((a, b) => a.skill.name.localeCompare(b.skill.name, "zh-CN")));
  Object.values(bySkill).forEach((items) => items.sort((a, b) =>
    String(a.role.roleName || a.role.roleCode || "").localeCompare(
      String(b.role.roleName || b.role.roleCode || ""), "zh-CN")));
  return { byRole, bySkill };
}

export default {
  name: "SkillRoleAuthorizationPanel",
  props: {
    tenantId: { type: String, default: "" },
    roles: { type: Array, default: () => [] },
    agents: { type: Array, default: () => [] },
    initialRoleId: { type: String, default: "" }
  },
  data() {
    return {
      mode: "role",
      selectedRoleId: this.initialRoleId || this.roles[0]?.id || "",
      selectedSkillKey: "",
      query: "",
      domainSkills: [],
      grants: [],
      roleBindings: {},
      loading: false,
      error: "",
      requestVersion: 0
    };
  },
  computed: {
    skills() {
      const values = new Map();
      this.agents.map((skill) => normalizeSkill(skill, AGENT_SKILL))
        .concat(this.domainSkills.map((skill) => normalizeSkill(skill, DOMAIN_SKILL)))
        .filter((skill) => skill.id)
        .forEach((skill) => values.set(skill.key, skill));
      return [...values.values()].sort((a, b) => a.name.localeCompare(b.name, "zh-CN"));
    },
    filteredSkills() {
      return this.skills.filter((skill) => matchesSkillQuery(skill, this.query));
    },
    index() {
      return buildSkillRoleIndex({
        roles: this.roles,
        skills: this.skills,
        grants: this.grants,
        roleBindings: this.roleBindings
      });
    },
    selectedRole() {
      return this.roles.find((role) => role.id === this.selectedRoleId) || null;
    },
    selectedSkill() {
      return this.skills.find((skill) => skill.key === this.selectedSkillKey) || null;
    },
    roleSkills() {
      return (this.index.byRole[this.selectedRoleId] || [])
        .filter((association) => matchesSkillQuery(association.skill, this.query));
    },
    skillRoles() {
      return this.index.bySkill[this.selectedSkillKey] || [];
    },
    summaryText() {
      if (this.mode === "role") {
        return this.selectedRole
          ? `${this.selectedRole.roleName || this.selectedRole.roleCode}拥有 ${this.roleSkills.length} 个匹配技能`
          : "请选择角色";
      }
      return this.selectedSkill
        ? `${this.selectedSkill.name}已授权给 ${this.skillRoles.length} 个角色`
        : `找到 ${this.filteredSkills.length} 个匹配技能`;
    }
  },
  watch: {
    tenantId() { this.reload(); },
    roles: {
      deep: true,
      handler() {
        if (!this.roles.some((role) => role.id === this.selectedRoleId)) {
          this.selectedRoleId = this.roles[0]?.id || "";
        }
      }
    },
    initialRoleId(value) { if (value) this.selectedRoleId = value; },
    filteredSkills(skills) {
      if (this.mode === "skill" && !skills.some((skill) => skill.key === this.selectedSkillKey)) {
        this.selectedSkillKey = skills[0]?.key || "";
      }
    }
  },
  mounted() { this.reload(); },
  methods: {
    selectMode(mode) {
      this.mode = mode;
      if (mode === "skill" && !this.selectedSkillKey) {
        this.selectedSkillKey = this.filteredSkills[0]?.key || "";
      }
    },
    async loadAllDomainSkills() {
      const first = await fetchDomainSkills({ page: 0, pageSize: 100 });
      const result = Array.isArray(first?.skills) ? [...first.skills] : [];
      const pages = Math.max(1, Number(first?.totalPages) || 1);
      for (let page = 1; page < pages; page += 1) {
        const next = await fetchDomainSkills({ page, pageSize: 100 });
        if (Array.isArray(next?.skills)) result.push(...next.skills);
      }
      return result;
    },
    async loadRoleBindings() {
      const results = await Promise.allSettled(this.roles.map(async (role) => {
        const authorization = await fetchRoleAuthorization(role.id);
        return [role.id, Array.isArray(authorization?.agentIds) ? authorization.agentIds : []];
      }));
      return Object.fromEntries(results
        .filter((result) => result.status === "fulfilled")
        .map((result) => result.value));
    },
    async reload() {
      if (!this.tenantId) return;
      const version = ++this.requestVersion;
      this.loading = true;
      this.error = "";
      try {
        const [domainSkills, agentGrants, domainGrants, roleBindings] = await Promise.all([
          this.loadAllDomainSkills(),
          fetchResourceGrants(this.tenantId, AGENT_SKILL),
          fetchResourceGrants(this.tenantId, DOMAIN_SKILL),
          this.loadRoleBindings()
        ]);
        if (version !== this.requestVersion) return;
        this.domainSkills = domainSkills;
        this.grants = [...(Array.isArray(agentGrants) ? agentGrants : []),
          ...(Array.isArray(domainGrants) ? domainGrants : [])];
        this.roleBindings = roleBindings;
        if (!this.selectedSkillKey) this.selectedSkillKey = this.filteredSkills[0]?.key || "";
      } catch (error) {
        if (version === this.requestVersion) this.error = error?.message || "技能角色关系加载失败";
      } finally {
        if (version === this.requestVersion) this.loading = false;
      }
    }
  }
};
