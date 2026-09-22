<template>
  <section class="resource-auth">
    <header class="resource-auth-head">
      <div>
        <p>资源授权</p>
        <h2>角色权限与 Agent Skill 文档范围</h2>
        <small>运行时可访问范围 = 用户角色授权 ∩ Agent Skill 文档范围。</small>
      </div>
    </header>

    <div v-if="notice" class="resource-auth-notice" :class="{ error: failed }">{{ notice }}</div>

    <div class="resource-auth-flow" aria-label="运行时权限关系">
      <span>用户角色 <strong>{{ selectedRoleName }}</strong></span>
      <b>∩</b>
      <span>Agent Skill <strong>{{ selectedSkillName }}</strong></span>
      <b>→</b>
      <span>本次可读取的文档</span>
    </div>

    <div class="resource-auth-columns">
      <div class="resource-auth-card">
        <h3>① 角色可以使用什么</h3>
        <label class="resource-auth-field">角色
          <select v-model="roleId">
            <option value="">选择角色</option>
            <option v-for="role in roles" :key="role.id" :value="role.id">{{ role.roleName }}（{{ role.roleCode }}）</option>
          </select>
        </label>
        <div class="resource-auth-kinds">
          <button v-for="kind in grantKinds" :key="kind.value" type="button" :class="{ active: grantKind === kind.value }" @click="grantKind = kind.value">{{ kind.label }}</button>
        </div>
        <div class="resource-auth-tools">
          <input v-model.trim="query" type="search" placeholder="筛选当前页名称或 ID" />
          <button type="button" @click="reload">刷新</button>
        </div>
        <p class="resource-auth-hint">勾选后立即保存。文档分类按知识库范围授权；单篇文档可以单独授权。</p>
        <div v-if="loading" class="resource-auth-empty">正在加载…</div>
        <div v-else-if="!roleId" class="resource-auth-empty">请先选择角色</div>
        <div v-else-if="!grantItems.length" class="resource-auth-empty">没有匹配的资源</div>
        <div v-else class="resource-auth-list">
          <label v-for="item in grantItems" :key="item.id" class="resource-auth-item">
            <input type="checkbox" :checked="hasGrant(item.id)" :disabled="!!busyKey" @change="toggleGrant(item.id, $event.target.checked)" />
            <span><strong>{{ item.name }}</strong><small>{{ item.id }}</small></span>
          </label>
        </div>
        <div v-if="grantKind === 'KNOWLEDGE'" class="resource-auth-page">
          <button type="button" :disabled="documentPage <= 1 || loading" @click="documentPage--">上一页</button>
          <span>{{ documentPage }} / {{ documentPages }}</span>
          <button type="button" :disabled="documentPage >= documentPages || loading" @click="documentPage++">下一页</button>
        </div>
        <p v-if="selectedGrantCount" class="resource-auth-count">当前角色已授权 {{ selectedGrantCount }} 项{{ grantKindLabel }}</p>
      </div>

      <div class="resource-auth-card">
        <h3>② Agent Skill 可以读取什么</h3>
        <label class="resource-auth-field">Agent Skill
          <select v-model="skillId">
            <option value="">选择 Agent Skill</option>
            <option v-for="agent in agents" :key="agent.id" :value="agent.id">{{ agent.name || agent.id }}</option>
          </select>
        </label>
        <div class="resource-auth-kinds">
          <button type="button" :class="{ active: scopeKind === 'KNOWLEDGE_BASE' }" @click="scopeKind = 'KNOWLEDGE_BASE'">文档分类</button>
          <button type="button" :class="{ active: scopeKind === 'DOCUMENT' }" @click="scopeKind = 'DOCUMENT'">单篇文档</button>
        </div>
        <div class="resource-auth-tools">
          <input v-model.trim="scopeQuery" type="search" placeholder="筛选当前页名称或 ID" />
          <button type="button" @click="reload">刷新</button>
        </div>
        <p class="resource-auth-hint">Skill 范围只限定能力可读取的文档，不授予用户额外权限。</p>
        <div v-if="loading" class="resource-auth-empty">正在加载…</div>
        <div v-else-if="!skillId" class="resource-auth-empty">请先选择 Agent Skill</div>
        <div v-else-if="!scopeItems.length" class="resource-auth-empty">没有匹配的资源</div>
        <div v-else class="resource-auth-list">
          <label v-for="item in scopeItems" :key="item.id" class="resource-auth-item">
            <input type="checkbox" :checked="hasScope(item.id)" :disabled="!!busyKey" @change="toggleScope(item.id, $event.target.checked)" />
            <span><strong>{{ item.name }}</strong><small>{{ item.id }}</small></span>
          </label>
        </div>
        <div v-if="scopeKind === 'DOCUMENT'" class="resource-auth-page">
          <button type="button" :disabled="documentPage <= 1 || loading" @click="documentPage--">上一页</button>
          <span>{{ documentPage }} / {{ documentPages }}</span>
          <button type="button" :disabled="documentPage >= documentPages || loading" @click="documentPage++">下一页</button>
        </div>
        <p v-if="skillId" class="resource-auth-count">已限定 {{ enabledScopes.length }} 项；角色和 Skill 都允许时才可读取。</p>
      </div>
    </div>
  </section>
</template>

<script>
import {
  createResourceGrant, createSkillResourceScope, deleteResourceGrant,
  deleteSkillResourceScope, fetchDomainSkills, fetchResearchLibrary,
  fetchResourceGrants, fetchSkillResourceScopes
} from "../services/api";
import "../styles/pages/resource-authorization.css";

const grantKinds = [
  { value: "AGENT_SKILL", label: "Agent Skill" },
  { value: "SKILL", label: "领域 Skill" },
  { value: "KNOWLEDGE_BASE", label: "文档分类" },
  { value: "KNOWLEDGE", label: "单篇文档" }
];

export default {
  name: "ResourceAuthorizationPanel",
  props: {
    tenantId: { type: String, default: "" },
    roles: { type: Array, default: () => [] },
    agents: { type: Array, default: () => [] },
    initialRoleId: { type: String, default: "" }
  },
  data() {
    return {
      grantKinds, roleId: this.initialRoleId || this.roles[0]?.id || "",
      skillId: "", grantKind: "AGENT_SKILL", scopeKind: "KNOWLEDGE_BASE",
      query: "", scopeQuery: "", documentPage: 1, documentPages: 1,
      categories: [], documents: [], domainSkills: [], grants: [], scopes: [],
      loading: false, busyKey: "", notice: "", failed: false, requestVersion: 0
    };
  },
  computed: {
    selectedRoleName() { return this.roles.find((role) => role.id === this.roleId)?.roleName || "未选择"; },
    selectedSkillName() { return this.agents.find((agent) => agent.id === this.skillId)?.name || "未选择"; },
    enabledScopes() { return this.scopes.filter((scope) => scope.enabled); },
    selectedGrantCount() { return this.grants.filter((grant) => grant.principalType === "ROLE" && grant.principalId === this.roleId && grant.effect === "ALLOW" && grant.enabled).length; },
    grantKindLabel() { return grantKinds.find((kind) => kind.value === this.grantKind)?.label || "资源"; },
    agentItems() { return this.agents.map((agent) => ({ id: String(agent.id), name: agent.name || agent.id })); },
    skillItems() { return this.domainSkills.map((skill) => ({ id: String(skill.id), name: skill.name || skill.id })); },
    categoryItems() { return this.categories.map((category) => {
      const name = typeof category === "string" ? category : category.name;
      return { id: String(name || "").trim().toLowerCase(), name: name || "未分类" };
    }).filter((item) => item.id); },
    documentItems() { return this.documents.filter((doc) => doc.docId).map((doc) => ({ id: String(doc.docId), name: doc.title || doc.fileName || doc.docId })); },
    grantItems() {
      const items = this.grantKind === "AGENT_SKILL" ? this.agentItems
        : this.grantKind === "SKILL" ? this.skillItems
          : this.grantKind === "KNOWLEDGE_BASE" ? this.categoryItems : this.documentItems;
      const q = this.query.toLowerCase();
      return q ? items.filter((item) => `${item.name} ${item.id}`.toLowerCase().includes(q)) : items;
    },
    scopeItems() {
      const items = this.scopeKind === "DOCUMENT" ? this.documentItems : this.categoryItems;
      const q = this.scopeQuery.toLowerCase();
      return q ? items.filter((item) => `${item.name} ${item.id}`.toLowerCase().includes(q)) : items;
    }
  },
  watch: {
    tenantId() { this.roleId = this.roles[0]?.id || ""; this.skillId = ""; this.reload(); },
    initialRoleId(value) { if (value) this.roleId = value; },
    grantKind() { this.loadGrants(); },
    skillId() { this.loadScopes(); },
    documentPage() { this.loadCatalog(); }
  },
  mounted() { this.reload(); },
  methods: {
    showError(error) { this.notice = error?.message || "操作失败"; this.failed = true; },
    hasGrant(id) { return this.grants.some((grant) => grant.resourceId === id && grant.principalType === "ROLE" && grant.principalId === this.roleId && grant.effect === "ALLOW" && grant.enabled); },
    hasScope(id) { return this.scopes.some((scope) => scope.resourceType === this.scopeKind && scope.resourceId === id && scope.enabled); },
    async reload() { await Promise.all([this.loadCatalog(), this.loadGrants(), this.loadScopes()]); },
    async loadCatalog() {
      if (!this.tenantId) return;
      const version = ++this.requestVersion;
      this.loading = true;
      try {
        const [library, skills] = await Promise.all([
          fetchResearchLibrary({ tenantId: this.tenantId, page: this.documentPage, pageSize: 20 }),
          this.loadAllDomainSkills()
        ]);
        if (version !== this.requestVersion) return;
        this.categories = Array.isArray(library?.categories) ? library.categories : [];
        this.documents = Array.isArray(library?.documents) ? library.documents : [];
        this.documentPages = Math.max(1, Number(library?.totalPages) || 1);
        this.domainSkills = skills;
      } catch (error) { if (version === this.requestVersion) this.showError(error); }
      finally { if (version === this.requestVersion) this.loading = false; }
    },
    async loadAllDomainSkills() {
      const first = await fetchDomainSkills({ page: 0, pageSize: 100 });
      const pages = Math.max(1, Number(first?.totalPages) || 1);
      const result = Array.isArray(first?.skills) ? [...first.skills] : [];
      for (let page = 1; page < pages; page++) {
        const next = await fetchDomainSkills({ page, pageSize: 100 });
        if (Array.isArray(next?.skills)) result.push(...next.skills);
      }
      return result;
    },
    async loadGrants() {
      if (!this.tenantId) return;
      const kind = this.grantKind;
      try {
        const rows = await fetchResourceGrants(this.tenantId, kind);
        if (kind === this.grantKind) this.grants = Array.isArray(rows) ? rows : [];
      } catch (error) { this.showError(error); }
    },
    async loadScopes() {
      if (!this.tenantId || !this.skillId) { this.scopes = []; return; }
      const skillId = this.skillId;
      try {
        const rows = await fetchSkillResourceScopes(this.tenantId, skillId);
        if (skillId === this.skillId) this.scopes = Array.isArray(rows) ? rows : [];
      } catch (error) { this.showError(error); }
    },
    async toggleGrant(id, checked) {
      if (!this.roleId || this.busyKey) return;
      this.busyKey = `grant:${id}`;
      this.notice = "";
      try {
        if (checked) {
          await createResourceGrant({ tenantId: this.tenantId, resourceType: this.grantKind, resourceId: id,
            principalType: "ROLE", principalId: this.roleId, effect: "ALLOW", enabled: true });
        } else {
          const matches = this.grants.filter((grant) => grant.resourceId === id && grant.principalType === "ROLE" && grant.principalId === this.roleId && grant.effect === "ALLOW");
          await Promise.all(matches.map((grant) => deleteResourceGrant(grant.id)));
        }
        await this.loadGrants();
        this.notice = "角色授权已保存"; this.failed = false;
      } catch (error) { this.showError(error); await this.loadGrants(); }
      finally { this.busyKey = ""; }
    },
    async toggleScope(id, checked) {
      if (!this.skillId || this.busyKey) return;
      this.busyKey = `scope:${id}`;
      this.notice = "";
      try {
        if (checked) {
          await createSkillResourceScope({ tenantId: this.tenantId, skillId: this.skillId,
            resourceType: this.scopeKind, resourceId: id, enabled: true });
        } else {
          const matches = this.scopes.filter((scope) => scope.resourceType === this.scopeKind && scope.resourceId === id);
          await Promise.all(matches.map((scope) => deleteSkillResourceScope(scope.id)));
        }
        await this.loadScopes();
        this.notice = "Skill 文档范围已保存"; this.failed = false;
      } catch (error) { this.showError(error); await this.loadScopes(); }
      finally { this.busyKey = ""; }
    }
  }
};
</script>
