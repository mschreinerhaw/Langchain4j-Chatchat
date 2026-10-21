import {
  createResourceGrant,
  fetchDomainSkills,
  fetchMcpRegisteredTools,
  fetchResearchLibrary,
  fetchResourceGrants,
  fetchRoleAuthorization,
} from "../../services/api";
import "../../styles/pages/resource-authorization.css";

const grantKinds = [
  { value: "SKILL", label: "领域技能" },
  { value: "KNOWLEDGE", label: "文档" },
  { value: "MCP_TOOL", label: "MCP 工具" },
];

export default {
  name: "ResourceAuthorizationPanel",
  props: {
    tenantId: { type: String, default: "" },
    roles: { type: Array, default: () => [] },
    agents: { type: Array, default: () => [] },
    initialRoleId: { type: String, default: "" },
  },
  data() {
    return {
      grantKinds,
      roleId: this.initialRoleId || this.roles[0]?.id || "",
      skillId: "",
      grantKind: "SKILL",
      agentQuery: "",
      query: "",
      documentPage: 1,
      documentPages: 1,
      categories: [],
      documents: [],
      domainSkills: [],
      mcpTools: [],
      grants: [],
      roleAgentIds: [],
      loading: false,
      bindingLoading: false,
      grantsLoading: false,
      grantsLoaded: false,
      allAgentAccess: false,
      busyKey: "",
      notice: "",
      failed: false,
      noticeTimer: null,
      catalogVersion: 0,
      bindingVersion: 0,
      grantVersion: 0,
      catalogTenantId: "",
    };
  },
  computed: {
    selectedRoleName() {
      return this.roles.find((role) => role.id === this.roleId)?.roleName || "未选择";
    },
    selectedAgentName() {
      return (
        this.boundAgentItems.find((agent) => agent.id === this.skillId)?.name || "未选择 Agent"
      );
    },
    boundAgentItems() {
      const agents = new Map(this.agents.map((agent) => [String(agent.id).toLowerCase(), agent]));
      if (this.allAgentAccess)
        return [...agents.values()].map((agent) => ({
          id: String(agent.id),
          name: agent.name || agent.id,
        }));
      return [...new Set(this.roleAgentIds.map((id) => String(id).toLowerCase()))].map((id) => {
        const agent = agents.get(id);
        return { id: agent ? String(agent.id) : id, name: agent?.name || id };
      });
    },
    filteredBoundAgents() {
      const query = this.agentQuery.toLowerCase();
      return this.boundAgentItems.filter((agent) =>
        `${agent.name} ${agent.id}`.toLowerCase().includes(query)
      );
    },
    roleGrants() {
      return this.grants.filter(
        (grant) =>
          grant.principalType === "ROLE" &&
          grant.principalId === this.roleId &&
          (!grant.agentId || grant.agentId === this.skillId) &&
          grant.enabled &&
          (!grant.expiresAt || new Date(grant.expiresAt).getTime() > Date.now())
      );
    },
    inheritedFullAccess() {
      return (
        this.allAgentAccess &&
        this.roleGrants.some(
          (grant) => !grant.agentId && grant.resourceId === "*" && grant.effect === "ALLOW"
        )
      );
    },
    selectedGrantCount() {
      return this.grantItems.filter((item) => this.hasGrant(item.id)).length;
    },
    grantKindLabel() {
      return grantKinds.find((kind) => kind.value === this.grantKind)?.label || "资源";
    },
    grantItems() {
      let items;
      if (this.grantKind === "SKILL")
        items = this.domainSkills.map((skill) => ({
          id: String(skill.id),
          name: skill.name || skill.id,
        }));
      else if (this.grantKind === "KNOWLEDGE_BASE")
        items = this.categories.map((category) => {
          const name = typeof category === "string" ? category : category.name;
          return {
            id: String(name || "")
              .trim()
              .toLowerCase(),
            name: name || "未分类",
          };
        });
      else if (this.grantKind === "KNOWLEDGE")
        items = this.documents
          .filter((doc) => doc.docId)
          .map((doc) => ({ id: String(doc.docId), name: doc.title || doc.fileName || doc.docId }));
      else
        items = this.mcpTools.map((tool) => {
          const id = tool.localToolName || tool.toolName || tool.name || tool.id;
          return {
            id: String(id || ""),
            name: tool.chineseAlias || tool.displayName || tool.remoteToolName || id,
          };
        });
      const query = this.query.toLowerCase();
      return items.filter(
        (item) => item.id && `${item.name} ${item.id}`.toLowerCase().includes(query)
      );
    },
  },
  watch: {
    tenantId() {
      this.roleId = this.roles[0]?.id || "";
      this.skillId = "";
      this.roleAgentIds = [];
      this.reload();
    },
    initialRoleId(value) {
      if (value) this.roleId = value;
    },
    roleId() {
      this.skillId = "";
      this.roleAgentIds = [];
      this.allAgentAccess = false;
      this.loadGrants();
      this.loadRoleBindings();
    },
    boundAgentItems(items) {
      if (!items.some((item) => item.id === this.skillId)) this.skillId = items[0]?.id || "";
    },
    grantKind() {
      this.query = "";
      this.loadGrants();
    },
    skillId() {
      this.loadGrants();
    },
    documentPage() {
      this.loadCatalog();
    },
  },
  mounted() {
    this.reload();
  },
  beforeUnmount() {
    if (this.noticeTimer) clearTimeout(this.noticeTimer);
    this.catalogVersion++;
    this.bindingVersion++;
    this.grantVersion++;
  },
  methods: {
    showNotice(message, failed = false) {
      if (this.noticeTimer) clearTimeout(this.noticeTimer);
      this.notice = message;
      this.failed = failed;
      this.noticeTimer = setTimeout(
        () => {
          this.notice = "";
          this.noticeTimer = null;
        },
        failed ? 5000 : 3000
      );
    },
    showError(error) {
      this.showNotice(error?.message || "操作失败", true);
    },
    hasGrant(id) {
      const matching = this.roleGrants.filter(
        (grant) => grant.resourceId === id || grant.resourceId === "*"
      );
      return (
        matching.some((grant) => grant.effect === "ALLOW") &&
        !matching.some((grant) => grant.effect === "DENY")
      );
    },
    async reload() {
      await Promise.all([this.loadRoleBindings(), this.reloadResources()]);
    },
    async reloadResources() {
      await Promise.all([this.loadCatalog(), this.loadGrants()]);
    },
    async loadRoleBindings() {
      const version = ++this.bindingVersion;
      if (!this.roleId) {
        this.roleAgentIds = [];
        this.allAgentAccess = false;
        this.bindingLoading = false;
        return;
      }
      this.bindingLoading = true;
      try {
        const authorization = await fetchRoleAuthorization(this.roleId);
        if (version === this.bindingVersion) {
          this.roleAgentIds = Array.isArray(authorization?.agentIds) ? authorization.agentIds : [];
          this.allAgentAccess = authorization?.allAgentAccess === true;
        }
      } catch (error) {
        if (version === this.bindingVersion) {
          this.roleAgentIds = [];
          this.allAgentAccess = false;
          this.showError(error);
        }
      } finally {
        if (version === this.bindingVersion) this.bindingLoading = false;
      }
    },
    async loadCatalog() {
      const version = ++this.catalogVersion;
      if (this.catalogTenantId !== this.tenantId) {
        this.categories = [];
        this.documents = [];
        this.domainSkills = [];
        this.mcpTools = [];
        this.catalogTenantId = this.tenantId;
      }
      if (!this.tenantId) {
        this.loading = false;
        return;
      }
      this.loading = true;
      try {
        const [library, skills, tools] = await Promise.all([
          fetchResearchLibrary({ tenantId: this.tenantId, page: this.documentPage, pageSize: 20 }),
          this.loadAllDomainSkills(),
          fetchMcpRegisteredTools(),
        ]);
        if (version !== this.catalogVersion) return;
        this.categories = Array.isArray(library?.categories) ? library.categories : [];
        this.documents = Array.isArray(library?.documents) ? library.documents : [];
        this.documentPages = Math.max(1, Number(library?.totalPages) || 1);
        this.domainSkills = skills;
        this.mcpTools = Array.isArray(tools) ? tools : [];
      } catch (error) {
        if (version === this.catalogVersion) this.showError(error);
      } finally {
        if (version === this.catalogVersion) this.loading = false;
      }
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
      this.grants = [];
      this.grantsLoaded = false;
      if (!this.tenantId || !this.roleId || !this.skillId) {
        this.grantsLoading = false;
        return;
      }
      this.grantsLoading = true;
      try {
        const [rows, inherited] = await Promise.all([
          fetchResourceGrants(this.tenantId, this.grantKind, this.skillId),
          fetchResourceGrants(this.tenantId, this.grantKind),
        ]);
        if (version === this.grantVersion) {
          this.grants = [
            ...(Array.isArray(rows) ? rows : []),
            ...(Array.isArray(inherited) ? inherited : []),
          ];
          this.grantsLoaded = true;
        }
      } catch (error) {
        if (version === this.grantVersion) this.showError(error);
      } finally {
        if (version === this.grantVersion) this.grantsLoading = false;
      }
    },
    async toggleGrant(id, checked) {
      if (
        !this.roleId ||
        !this.tenantId ||
        !this.skillId ||
        this.busyKey ||
        this.loading ||
        this.bindingLoading ||
        !this.grantsLoaded ||
        this.inheritedFullAccess
      )
        return;
      const roleId = this.roleId,
        kind = this.grantKind,
        agentId = this.skillId;
      this.busyKey = id;
      this.notice = "";
      try {
        await createResourceGrant({
          tenantId: this.tenantId,
          resourceType: kind,
          resourceId: id,
          principalType: "ROLE",
          principalId: roleId,
          agentId,
          effect: checked ? "ALLOW" : "DENY",
          enabled: true,
        });
        await this.loadGrants();
        this.showNotice("当前角色下的 Agent 资源授权已保存");
      } catch (error) {
        this.showError(error);
        await this.loadGrants();
      } finally {
        this.busyKey = "";
      }
    },
  },
};
