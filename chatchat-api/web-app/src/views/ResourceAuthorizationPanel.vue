<template>
  <section class="resource-auth">
    <header class="resource-auth-head">
      <div><p>资源授权</p><h2>角色与 Agent 资源授权</h2><small>选择角色及其绑定的 Agent，分别配置领域技能、文档和 MCP 工具授权。</small></div>
    </header>
    <div v-if="notice" class="resource-auth-notice" :class="{ error: failed }">{{ notice }}</div>
    <div class="resource-auth-context">
      <label class="resource-auth-field">当前角色
        <select v-model="roleId" :disabled="!!busyKey">
          <option value="">选择角色</option>
          <option v-for="role in roles" :key="role.id" :value="role.id">{{ role.roleName }}（{{ role.roleCode }}）</option>
        </select>
      </label>
    </div>
    <div class="resource-auth-columns">
      <div class="resource-auth-card">
        <h3>{{ allAgentAccess ? '角色可访问的 Agent' : '角色绑定的 Agent' }}</h3>
        <p class="resource-auth-card-description">{{ allAgentAccess ? `“${selectedRoleName}”拥有全部 Agent 访问权限，当前共 ${boundAgentItems.length} 个。` : `“${selectedRoleName}”已绑定 ${boundAgentItems.length} 个 Agent。` }}</p>
        <div class="resource-auth-tools">
          <input v-model.trim="agentQuery" type="search" placeholder="搜索 Agent 名称或 ID" />
          <button type="button" :disabled="!!busyKey" @click="loadRoleBindings">刷新</button>
        </div>
        <p class="resource-auth-hint">{{ allAgentAccess ? '来源：全部 Agent 访问权限。无需逐个绑定，新增 Agent 自动包含。' : '绑定关系在角色管理中维护。' }}</p>
        <div v-if="!roleId" class="resource-auth-empty">请先选择角色</div>
        <div v-else-if="bindingLoading" class="resource-auth-empty">正在加载…</div>
        <div v-else-if="!filteredBoundAgents.length" class="resource-auth-empty">{{ boundAgentItems.length ? '没有匹配的 Agent' : '该角色尚未绑定 Agent' }}</div>
        <div v-else class="resource-auth-list">
          <button v-for="item in filteredBoundAgents" :key="item.id" type="button"
            class="resource-auth-item resource-auth-agent" :class="{ selected: skillId === item.id }"
            :aria-pressed="skillId === item.id" :disabled="!!busyKey" @click="skillId = item.id">
            <span><strong>{{ item.name }}</strong><small>{{ item.id }}</small></span>
          </button>
        </div>
      </div>
      <div class="resource-auth-card">
        <h3>资源授权</h3>
        <p class="resource-auth-card-description">{{ selectedRoleName }} / {{ selectedAgentName }}</p>
        <div class="resource-auth-kinds">
          <button v-for="kind in grantKinds" :key="kind.value" type="button" :disabled="!!busyKey"
            :class="{ active: grantKind === kind.value }" @click="grantKind = kind.value">{{ kind.label }}</button>
        </div>
        <div class="resource-auth-tools">
          <input v-model.trim="query" type="search" placeholder="筛选当前页名称或 ID" />
          <button type="button" :disabled="!!busyKey" @click="reloadResources">刷新</button>
        </div>
        <p class="resource-auth-hint">{{ inheritedFullAccess ? '已继承角色级全部资源授权，无需逐项勾选，新增资源自动包含。本页只读展示生效状态；显式禁止和资源自身访问限制仍然生效。' : '勾选后仅授权当前角色使用所选 Agent 时访问该资源；取消勾选将禁止此关系访问该资源。资源自身的访问限制仍然生效。' }}</p>
        <div v-if="!roleId" class="resource-auth-empty">请先选择角色</div>
        <div v-else-if="!skillId" class="resource-auth-empty">请先从左侧选择已绑定的 Agent</div>
        <div v-else-if="loading || grantsLoading" class="resource-auth-empty">正在加载…</div>
        <div v-else-if="!grantItems.length" class="resource-auth-empty">没有匹配的资源</div>
        <div v-else class="resource-auth-list">
          <label v-for="item in grantItems" :key="item.id" class="resource-auth-item resource-auth-resource">
            <input type="checkbox" :checked="hasGrant(item.id)" :disabled="!!busyKey || !grantsLoaded || inheritedFullAccess"
              @change="toggleGrant(item.id, $event.target.checked)" />
            <span><strong>{{ item.name }}</strong><small>{{ item.id }}</small></span>
          </label>
        </div>
        <div v-if="grantKind === 'KNOWLEDGE'" class="resource-auth-page">
          <button type="button" :disabled="documentPage <= 1 || loading || !!busyKey" @click="documentPage--">上一页</button>
          <span>{{ documentPage }} / {{ documentPages }}</span>
          <button type="button" :disabled="documentPage >= documentPages || loading || !!busyKey" @click="documentPage++">下一页</button>
        </div>
        <p v-if="skillId && grantsLoaded" class="resource-auth-count">当前列表已授权 {{ selectedGrantCount }} / {{ grantItems.length }} 项{{ grantKindLabel }}{{ inheritedFullAccess ? '（继承全量授权）' : '' }}</p>
      </div>
    </div>
  </section>
</template>

<script>
import {
  createResourceGrant, fetchDomainSkills, fetchMcpRegisteredTools,
  fetchResearchLibrary, fetchResourceGrants, fetchRoleAuthorization
} from "../services/api";
import "../styles/pages/resource-authorization.css";

const grantKinds = [
  { value: "SKILL", label: "领域技能" },
  { value: "KNOWLEDGE", label: "文档" },
  { value: "MCP_TOOL", label: "MCP 工具" }
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
      skillId: "", grantKind: "SKILL", agentQuery: "", query: "",
      documentPage: 1, documentPages: 1,
      categories: [], documents: [], domainSkills: [], mcpTools: [], grants: [], roleAgentIds: [],
      loading: false, bindingLoading: false, grantsLoading: false, grantsLoaded: false, allAgentAccess: false,
      busyKey: "", notice: "", failed: false, noticeTimer: null,
      catalogVersion: 0, bindingVersion: 0, grantVersion: 0
    };
  },
  computed: {
    selectedRoleName() { return this.roles.find((role) => role.id === this.roleId)?.roleName || "未选择"; },
    selectedAgentName() { return this.boundAgentItems.find((agent) => agent.id === this.skillId)?.name || "未选择 Agent"; },
    boundAgentItems() {
      const agents = new Map(this.agents.map((agent) => [String(agent.id).toLowerCase(), agent]));
      if (this.allAgentAccess) return [...agents.values()].map((agent) => ({ id: String(agent.id), name: agent.name || agent.id }));
      return [...new Set(this.roleAgentIds.map((id) => String(id).toLowerCase()))].map((id) => {
        const agent = agents.get(id);
        return { id: agent ? String(agent.id) : id, name: agent?.name || id };
      });
    },
    filteredBoundAgents() {
      const query = this.agentQuery.toLowerCase();
      return this.boundAgentItems.filter((agent) => `${agent.name} ${agent.id}`.toLowerCase().includes(query));
    },
    roleGrants() {
      return this.grants.filter((grant) => grant.principalType === "ROLE" && grant.principalId === this.roleId
        && (!grant.agentId || grant.agentId === this.skillId) && grant.enabled
        && (!grant.expiresAt || new Date(grant.expiresAt).getTime() > Date.now()));
    },
    inheritedFullAccess() {
      return this.allAgentAccess && this.roleGrants.some((grant) => !grant.agentId && grant.resourceId === '*' && grant.effect === 'ALLOW');
    },
    selectedGrantCount() { return this.grantItems.filter((item) => this.hasGrant(item.id)).length; },
    grantKindLabel() { return grantKinds.find((kind) => kind.value === this.grantKind)?.label || "资源"; },
    grantItems() {
      let items;
      if (this.grantKind === "SKILL") items = this.domainSkills.map((skill) => ({ id: String(skill.id), name: skill.name || skill.id }));
      else if (this.grantKind === "KNOWLEDGE_BASE") items = this.categories.map((category) => {
        const name = typeof category === "string" ? category : category.name;
        return { id: String(name || "").trim().toLowerCase(), name: name || "未分类" };
      });
      else if (this.grantKind === "KNOWLEDGE") items = this.documents.filter((doc) => doc.docId)
        .map((doc) => ({ id: String(doc.docId), name: doc.title || doc.fileName || doc.docId }));
      else items = this.mcpTools.map((tool) => {
        const id = tool.localToolName || tool.toolName || tool.name || tool.id;
        return { id: String(id || ""), name: tool.chineseAlias || tool.displayName || tool.remoteToolName || id };
      });
      const query = this.query.toLowerCase();
      return items.filter((item) => item.id && `${item.name} ${item.id}`.toLowerCase().includes(query));
    }
  },
  watch: {
    tenantId() {
      this.roleId = this.roles[0]?.id || ""; this.skillId = ""; this.roleAgentIds = [];
      this.reload();
    },
    initialRoleId(value) { if (value) this.roleId = value; },
    roleId() {
      this.skillId = ""; this.roleAgentIds = [];
      this.loadGrants(); this.loadRoleBindings();
    },
    boundAgentItems(items) { if (!items.some((item) => item.id === this.skillId)) this.skillId = items[0]?.id || ""; },
    grantKind() { this.query = ""; this.loadGrants(); },
    skillId() { this.loadGrants(); },
    documentPage() { this.loadCatalog(); }
  },
  mounted() { this.reload(); },
  beforeUnmount() {
    if (this.noticeTimer) clearTimeout(this.noticeTimer);
    this.catalogVersion++; this.bindingVersion++; this.grantVersion++;
  },
  methods: {
    showNotice(message, failed = false) {
      if (this.noticeTimer) clearTimeout(this.noticeTimer);
      this.notice = message; this.failed = failed;
      this.noticeTimer = setTimeout(() => { this.notice = ""; this.noticeTimer = null; }, failed ? 5000 : 3000);
    },
    showError(error) { this.showNotice(error?.message || "操作失败", true); },
    hasGrant(id) {
      const matching = this.roleGrants.filter((grant) => grant.resourceId === id || grant.resourceId === '*');
      return matching.some((grant) => grant.effect === 'ALLOW') && !matching.some((grant) => grant.effect === 'DENY');
    },
    async reload() { await Promise.all([this.loadRoleBindings(), this.reloadResources()]); },
    async reloadResources() { await Promise.all([this.loadCatalog(), this.loadGrants()]); },
    async loadRoleBindings() {
      const version = ++this.bindingVersion;
      this.roleAgentIds = []; this.allAgentAccess = false;
      if (!this.roleId) { this.bindingLoading = false; return; }
      this.bindingLoading = true;
      try {
        const authorization = await fetchRoleAuthorization(this.roleId);
        if (version === this.bindingVersion) {
          this.roleAgentIds = Array.isArray(authorization?.agentIds) ? authorization.agentIds : [];
          this.allAgentAccess = authorization?.allAgentAccess === true;
        }
      } catch (error) { if (version === this.bindingVersion) this.showError(error); }
      finally { if (version === this.bindingVersion) this.bindingLoading = false; }
    },
    async loadCatalog() {
      const version = ++this.catalogVersion;
      this.categories = []; this.documents = []; this.domainSkills = []; this.mcpTools = [];
      if (!this.tenantId) { this.loading = false; return; }
      this.loading = true;
      try {
        const [library, skills, tools] = await Promise.all([
          fetchResearchLibrary({ tenantId: this.tenantId, page: this.documentPage, pageSize: 20 }),
          this.loadAllDomainSkills(), fetchMcpRegisteredTools()
        ]);
        if (version !== this.catalogVersion) return;
        this.categories = Array.isArray(library?.categories) ? library.categories : [];
        this.documents = Array.isArray(library?.documents) ? library.documents : [];
        this.documentPages = Math.max(1, Number(library?.totalPages) || 1);
        this.domainSkills = skills;
        this.mcpTools = Array.isArray(tools) ? tools : [];
      } catch (error) { if (version === this.catalogVersion) this.showError(error); }
      finally { if (version === this.catalogVersion) this.loading = false; }
    },
    async loadAllDomainSkills() {
      const first = await fetchDomainSkills({ page: 0, pageSize: 100 });
      const result = Array.isArray(first?.skills) ? [...first.skills] : [];
      for (let page = 1; page < (Number(first?.totalPages) || 1); page++) {
        const next = await fetchDomainSkills({ page, pageSize: 100 });
        if (Array.isArray(next?.skills)) result.push(...next.skills);
      }
      return result;
    },
    async loadGrants() {
      const version = ++this.grantVersion;
      this.grants = []; this.grantsLoaded = false;
      if (!this.tenantId || !this.roleId || !this.skillId) { this.grantsLoading = false; return; }
      this.grantsLoading = true;
      try {
        const [rows, inherited] = await Promise.all([
          fetchResourceGrants(this.tenantId, this.grantKind, this.skillId),
          fetchResourceGrants(this.tenantId, this.grantKind)
        ]);
        if (version === this.grantVersion) {
          this.grants = [...(Array.isArray(rows) ? rows : []), ...(Array.isArray(inherited) ? inherited : [])];
          this.grantsLoaded = true;
        }
      } catch (error) { if (version === this.grantVersion) this.showError(error); }
      finally { if (version === this.grantVersion) this.grantsLoading = false; }
    },
    async toggleGrant(id, checked) {
      if (!this.roleId || !this.tenantId || !this.skillId || this.busyKey || !this.grantsLoaded || this.inheritedFullAccess) return;
      const roleId = this.roleId, kind = this.grantKind, agentId = this.skillId;
      this.busyKey = id; this.notice = "";
      try {
        await createResourceGrant({ tenantId: this.tenantId, resourceType: kind, resourceId: id,
          principalType: "ROLE", principalId: roleId, agentId, effect: checked ? "ALLOW" : "DENY", enabled: true });
        await this.loadGrants();
        this.showNotice("当前角色下的 Agent 资源授权已保存");
      } catch (error) { this.showError(error); await this.loadGrants(); }
      finally { this.busyKey = ""; }
    }
  }
};
</script>
