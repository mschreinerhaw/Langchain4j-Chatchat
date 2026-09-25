<template>
  <section class="resource-auth">
    <header class="resource-auth-head">
      <div>
        <p>资源授权</p>
        <h2>角色、Agent 与文档范围</h2>
        <small>先选同一角色和 Agent，再分别查看角色绑定、执行授权与该 Agent 的文档范围；文档读取仍取角色文档授权和 Agent 范围的交集。</small>
      </div>
    </header>

    <div v-if="notice" class="resource-auth-notice" :class="{ error: failed }">{{ notice }}</div>

    <div class="resource-auth-context" aria-label="共同查看对象">
      <label class="resource-auth-field">查看角色
        <select v-model="roleId">
          <option value="">选择角色</option>
          <option v-for="role in roles" :key="role.id" :value="role.id">{{ role.roleName }}（{{ role.roleCode }}）</option>
        </select>
      </label>
      <label class="resource-auth-field">查看 Agent
        <select v-model="skillId">
          <option value="">选择 Agent</option>
          <option v-for="agent in agents" :key="agent.id" :value="agent.id">{{ agent.name || agent.id }}</option>
        </select>
      </label>
    </div>
    <div class="resource-auth-flow" aria-label="选中角色与 Agent 的授权关系">
      <span>角色绑定 <strong>{{ !roleId || !skillId || !roleBindingLoaded ? "待查看" : selectedRoleBinding ? "已绑定" : "未绑定" }}</strong></span>
      <b>→</b>
      <span>执行资源授权 <strong>{{ !roleId || !skillId || !agentGrantLoaded ? "待查看" : selectedAgentGranted ? "已显式授权" : "未显式授权" }}</strong></span>
      <b>→</b>
      <span>Agent 文档范围 <strong>{{ !skillId || !scopeLoaded ? "待查看" : scopes.length ? `${enabledScopes.length} 项启用` : "未单独配置" }}</strong></span>
    </div>
    <p v-if="skillId && roleId" class="resource-auth-explanation">
      当前查看：<strong>{{ selectedRoleName }}</strong> × <strong>{{ selectedSkillName }}</strong>。
      角色绑定用于角色管理及 Agent API；左侧执行资源授权用于分析准入；右侧范围只限制 Agent 可读取的文档，不会增加角色的文档权限。
    </p>

    <div class="resource-auth-columns">
      <div class="resource-auth-card">
        <h3>① {{ selectedRoleName }}的执行资源授权</h3>
        <div class="resource-auth-kinds">
          <button v-for="kind in grantKinds" :key="kind.value" type="button" :class="{ active: grantKind === kind.value }" @click="grantKind = kind.value">{{ kind.label }}</button>
        </div>
        <div class="resource-auth-tools">
          <input v-model.trim="query" type="search" placeholder="筛选当前页名称或 ID" />
          <button type="button" @click="reload">刷新</button>
        </div>
        <p class="resource-auth-hint">勾选后立即保存。Agent Skill 授权与角色管理中的 Agent 绑定是不同配置；点击“查看范围”可在右侧查看同一个 Agent。</p>
        <div v-if="loading" class="resource-auth-empty">正在加载…</div>
        <div v-else-if="!roleId" class="resource-auth-empty">请先选择角色</div>
        <div v-else-if="!grantItems.length" class="resource-auth-empty">没有匹配的资源</div>
        <div v-else class="resource-auth-list">
          <div v-for="item in grantItems" :key="item.id" class="resource-auth-item" :class="{ selected: grantKind === 'AGENT_SKILL' && skillId === item.id }">
            <label class="resource-auth-item-main">
              <input type="checkbox" :checked="hasGrant(item.id)" :disabled="!!busyKey" @change="toggleGrant(item.id, $event.target.checked)" />
              <span><strong>{{ item.name }}</strong><small>{{ item.id }}</small></span>
            </label>
            <button v-if="grantKind === 'AGENT_SKILL'" type="button" class="resource-auth-inspect" :aria-label="`查看 ${item.name} 的文档范围`" @click="skillId = item.id">{{ skillId === item.id ? "正在查看" : "查看范围" }}</button>
          </div>
        </div>
        <div v-if="grantKind === 'KNOWLEDGE'" class="resource-auth-page">
          <button type="button" :disabled="documentPage <= 1 || loading" @click="documentPage--">上一页</button>
          <span>{{ documentPage }} / {{ documentPages }}</span>
          <button type="button" :disabled="documentPage >= documentPages || loading" @click="documentPage++">下一页</button>
        </div>
        <p v-if="selectedGrantCount" class="resource-auth-count">当前角色已授权 {{ selectedGrantCount }} 项{{ grantKindLabel }}</p>
      </div>

      <div class="resource-auth-card">
        <h3>② {{ selectedSkillName }}的文档范围</h3>
        <div class="resource-auth-kinds">
          <button type="button" :class="{ active: scopeKind === 'KNOWLEDGE_BASE' }" @click="scopeKind = 'KNOWLEDGE_BASE'">文档分类</button>
          <button type="button" :class="{ active: scopeKind === 'DOCUMENT' }" @click="scopeKind = 'DOCUMENT'">单篇文档</button>
        </div>
        <div class="resource-auth-tools">
          <input v-model.trim="scopeQuery" type="search" placeholder="筛选当前页名称或 ID" />
          <button type="button" @click="reload">刷新</button>
        </div>
        <p class="resource-auth-hint">只配置顶部选中的 Agent。文档范围不授予角色执行权限，也不扩大角色本身可读的文档。</p>
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
        <p v-if="skillId" class="resource-auth-count">{{ scopes.length ? `已配置 ${scopes.length} 项范围，其中 ${enabledScopes.length} 项启用` : "未单独配置范围；可能沿用 Agent 原有文档绑定" }}。实际可读文档仍需符合角色文档授权。</p>
      </div>
    </div>
  </section>
</template>

<script>
import {
  createResourceGrant, createSkillResourceScope, deleteResourceGrant,
  deleteSkillResourceScope, fetchDomainSkills, fetchResearchLibrary,
  fetchResourceGrants, fetchRoleAuthorization, fetchSkillResourceScopes
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
      categories: [], documents: [], domainSkills: [], grants: [], agentGrants: [], scopes: [],
      roleAgentIds: [], roleBindingLoaded: false, agentGrantLoaded: false, scopeLoaded: false,
      loading: false, busyKey: "", notice: "", failed: false, requestVersion: 0
    };
  },
  computed: {
    selectedRoleName() { return this.roles.find((role) => role.id === this.roleId)?.roleName || "未选择"; },
    selectedSkillName() { return this.agents.find((agent) => agent.id === this.skillId)?.name || "未选择"; },
    selectedRoleBinding() { return this.roleAgentIds.some((id) => id.toLowerCase() === this.skillId.toLowerCase()); },
    selectedAgentGranted() { return this.agentGrants.some((grant) => grant.resourceId === this.skillId && grant.principalType === "ROLE" && grant.principalId === this.roleId && grant.effect === "ALLOW" && grant.enabled); },
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
    roleId() { this.loadGrants(); this.loadAgentGrants(); this.loadRoleBindings(); },
    grantKind() { this.loadGrants(); },
    skillId() { this.loadScopes(); },
    documentPage() { this.loadCatalog(); }
  },
  mounted() { this.reload(); },
  methods: {
    showError(error) { this.notice = error?.message || "操作失败"; this.failed = true; },
    hasGrant(id) { return this.grants.some((grant) => grant.resourceId === id && grant.principalType === "ROLE" && grant.principalId === this.roleId && grant.effect === "ALLOW" && grant.enabled); },
    hasScope(id) { return this.scopes.some((scope) => scope.resourceType === this.scopeKind && scope.resourceId === id && scope.enabled); },
    async reload() { await Promise.all([this.loadCatalog(), this.loadGrants(), this.loadAgentGrants(), this.loadRoleBindings(), this.loadScopes()]); },
    async loadRoleBindings() {
      if (!this.roleId) { this.roleAgentIds = []; this.roleBindingLoaded = false; return; }
      const roleId = this.roleId;
      this.roleBindingLoaded = false;
      try {
        const authorization = await fetchRoleAuthorization(roleId);
        if (roleId === this.roleId) {
          this.roleAgentIds = Array.isArray(authorization?.agentIds) ? authorization.agentIds : [];
          this.roleBindingLoaded = true;
        }
      } catch (error) { if (roleId === this.roleId) this.showError(error); }
    },
    async loadAgentGrants() {
      if (!this.tenantId || !this.roleId) { this.agentGrants = []; this.agentGrantLoaded = false; return; }
      const roleId = this.roleId;
      this.agentGrantLoaded = false;
      try {
        const rows = await fetchResourceGrants(this.tenantId, "AGENT_SKILL");
        if (roleId === this.roleId) {
          this.agentGrants = Array.isArray(rows) ? rows : [];
          this.agentGrantLoaded = true;
        }
      } catch (error) { if (roleId === this.roleId) this.showError(error); }
    },
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
        if (kind === "AGENT_SKILL") {
          this.agentGrants = Array.isArray(rows) ? rows : [];
          this.agentGrantLoaded = true;
        }
      } catch (error) { this.showError(error); }
    },
    async loadScopes() {
      if (!this.tenantId || !this.skillId) { this.scopes = []; this.scopeLoaded = false; return; }
      const skillId = this.skillId;
      this.scopes = [];
      this.scopeLoaded = false;
      try {
        const rows = await fetchSkillResourceScopes(this.tenantId, skillId);
        if (skillId === this.skillId) {
          this.scopes = Array.isArray(rows) ? rows : [];
          this.scopeLoaded = true;
        }
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
