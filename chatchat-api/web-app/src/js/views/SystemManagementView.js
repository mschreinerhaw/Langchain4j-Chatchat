import {
  Building2,
  Copy,
  KeyRound,
  Link2,
  Pencil,
  Plus,
  RefreshCw,
  RotateCcw,
  Save,
  ShieldCheck,
  Trash2,
  Users,
  X
} from "@lucide/vue";
import "../../styles/pages/system-management.css";
import {
  AUTH_REQUIRED_EVENT,
  changeAdminPassword,
  clearAuthSession,
  createOrg,
  createEmbedLoginToken,
  createRole,
  createUser,
  deleteOrg,
  deleteRole,
  deleteUser,
  expireEmbedLoginToken,
  fetchEmbedLoginTokens,
  fetchAgentOptions,
  fetchEnterpriseSummary,
  fetchOrgs,
  fetchPermissions,
  fetchRoleAuthorization,
  fetchRoles,
  fetchTenants,
  fetchUsers,
  saveRoleAuthorization,
  syncEnterpriseOrgs,
  syncEnterpriseUsers,
  updateOrg,
  updateRole,
  updateUser
} from "../../services/api";

const blankRoleForm = () => ({
  id: "",
  roleName: "",
  roleCode: "",
  roleType: "business",
  status: "enabled",
  description: ""
});

const blankOrgForm = () => ({
  id: "",
  parentId: "",
  orgCode: "",
  orgName: "",
  sortOrder: 0,
  status: "enabled"
});

const blankUserForm = () => ({
  id: "",
  orgId: "",
  username: "",
  displayName: "",
  password: "",
  email: "",
  phone: "",
  status: "enabled",
  roleIds: []
});

const blankAdminPasswordForm = () => ({
  currentPassword: "",
  newPassword: "",
  confirmPassword: ""
});

export default {
  name: "SystemManagementView",
  components: {
    Building2,
    Copy,
    KeyRound,
    Link2,
    Pencil,
    Plus,
    RefreshCw,
    RotateCcw,
    Save,
    ShieldCheck,
    Trash2,
    Users,
    X
  },
  data() {
    return {
      loading: false,
      savingOrg: false,
      savingRole: false,
      savingUser: false,
      savingAdminPassword: false,
      embedTokenLoading: false,
      embedTokenSaving: false,
      orgModalOpen: false,
      roleModalOpen: false,
      userModalOpen: false,
      adminPasswordModalOpen: false,
      embedTokenModalOpen: false,
      error: "",
      message: "",
      activeManagementTab: "users",
      summary: {},
      tenants: [],
      orgs: [],
      roles: [],
      users: [],
      permissions: [],
      agentOptions: [],
      embedTokens: [],
      embedTokenDuration: 86400,
      embedTokenLatestUrl: "",
      selectedTenantId: "",
      selectedRoleId: "",
      selectedPermissionIds: [],
      selectedAgentIds: [],
      focusedPermissionId: "",
      selectedUserIds: [],
      scopeType: "org_and_children",
      scopeOrgId: "",
      draftPermissionIds: [],
      draftAgentIds: [],
      draftUserIds: [],
      draftScopeType: "org_and_children",
      draftScopeOrgId: "",
      agentPickerOpen: false,
      agentPickerQuery: "",
      tempPickerAgentIds: [],
      userPickerOpen: false,
      tempPickerUserIds: [],
      orgForm: blankOrgForm(),
      roleForm: blankRoleForm(),
      userForm: blankUserForm(),
      adminPasswordForm: blankAdminPasswordForm()
    };
  },
  computed: {
    metrics() {
      return [
        { label: "组织", value: this.summary.orgCount ?? this.orgs.length },
        { label: "用户", value: this.summary.userCount ?? this.users.length },
        { label: "角色", value: this.summary.roleCount ?? this.roles.length },
        { label: "权限", value: this.summary.permissionCount ?? this.permissions.length }
      ];
    },
    orgTree() {
      const children = new Map();
      this.orgs.forEach((org) => {
        const parent = org.parentId || "root";
        if (!children.has(parent)) {
          children.set(parent, []);
        }
        children.get(parent).push(org);
      });
      const result = [];
      const walk = (parentId, level) => {
        (children.get(parentId) || []).forEach((org) => {
          result.push({ ...org, level });
          walk(org.id, level + 1);
        });
      };
      walk("root", 0);
      return result.length ? result : this.orgs.map((org) => ({ ...org, level: 0 }));
    },
    orgUserCounts() {
      return this.users.reduce((counts, user) => {
        if (user.orgId) {
          counts[user.orgId] = (counts[user.orgId] || 0) + 1;
        }
        return counts;
      }, {});
    },
    permissionTree() {
      const children = new Map();
      this.permissions.forEach((item) => {
        const parent = item.parentId || "root";
        if (!children.has(parent)) {
          children.set(parent, []);
        }
        children.get(parent).push(item);
      });
      const result = [];
      const walk = (parentId, level) => {
        (children.get(parentId) || []).forEach((item) => {
          result.push({ ...item, level });
          walk(item.id, level + 1);
        });
      };
      walk("root", 0);
      return result.length ? result : this.permissions.map((item) => ({ ...item, level: 0 }));
    },
    permissionGroups() {
      const selected = new Set(this.draftPermissionIds);
      const groups = [];
      let currentGroup = null;

      this.permissionTree.forEach((permission) => {
        if (permission.level === 0 || !currentGroup) {
          currentGroup = {
            root: permission,
            children: [],
            permissionIds: [permission.id]
          };
          groups.push(currentGroup);
          return;
        }

        currentGroup.children.push({
          ...permission,
          displayLevel: Math.max(permission.level - 1, 0)
        });
        currentGroup.permissionIds.push(permission.id);
      });

      return groups.map((group) => ({
        ...group,
        selectedCount: group.permissionIds.filter((id) => selected.has(id)).length,
        totalCount: group.permissionIds.length
      }));
    },
    savedPermissionTree() {
      const selected = new Set(this.selectedPermissionIds);
      return this.permissionTree.filter((permission) => selected.has(permission.id));
    },
    assignedUsers() {
      const selected = new Set(this.selectedUserIds);
      return this.users.filter((user) => selected.has(user.id));
    },
    scopedUsers() {
      if (this.draftScopeType === "all" || !this.draftScopeOrgId) {
        return this.users;
      }
      const orgIds = new Set([this.draftScopeOrgId]);
      if (this.draftScopeType === "org_and_children") {
        let changed = true;
        while (changed) {
          changed = false;
          this.orgs.forEach((org) => {
            if (org.parentId && orgIds.has(org.parentId) && !orgIds.has(org.id)) {
              orgIds.add(org.id);
              changed = true;
            }
          });
        }
      }
      return this.users.filter((user) => orgIds.has(user.orgId));
    },
    selectedDraftUsers() {
      const selected = new Set(this.draftUserIds);
      return this.users.filter((user) => selected.has(user.id));
    },
    selectedDraftAgents() {
      const selected = new Set(this.draftAgentIds);
      return this.agentOptions.filter((agent) => selected.has(agent.id));
    },
    filteredAgentOptions() {
      const query = this.agentPickerQuery.trim().toLowerCase();
      if (!query) {
        return this.agentOptions;
      }
      return this.agentOptions.filter((agent) => [
        agent.id,
        agent.name,
        agent.description,
        agent.category,
        agent.marketStatus,
        agent.status
      ].some((value) => String(value || "").toLowerCase().includes(query)));
    },
    roleInfoSummary() {
      const name = this.roleForm.roleName || "未填写角色名称";
      const code = this.roleForm.roleCode ? ` · ${this.roleForm.roleCode}` : "";
      return `${name}${code}`;
    },
    scopeSummary() {
      if (this.draftScopeType === "all") {
        return "全部组织";
      }
      const org = this.orgs.find((item) => item.id === this.draftScopeOrgId);
      const scope = this.draftScopeType === "org" ? "指定组织" : "组织及下级";
      return `${scope}${org ? ` · ${org.orgName}` : ""}`;
    },
    draftAgentSummary() {
      if (!this.draftAgentIds.length) {
        return "未绑定 Agent";
      }
      const names = this.selectedDraftAgents.map((agent) => agent.name || agent.id).slice(0, 3);
      const suffix = this.draftAgentIds.length > names.length ? ` 等 ${this.draftAgentIds.length} 个` : "";
      return `${names.join("、")}${suffix}`;
    },
    availableDraftUsers() {
      const selected = new Set(this.draftUserIds);
      return this.scopedUsers.filter((user) => !selected.has(user.id));
    },
    focusedPermission() {
      return this.permissions.find((permission) => permission.id === this.focusedPermissionId) || null;
    },
    selectedRole() {
      return this.roles.find((role) => role.id === this.selectedRoleId) || null;
    },
    rolesById() {
      return this.roles.reduce((result, role) => {
        result[role.id] = role;
        return result;
      }, {});
    },
    embedTokenDurations() {
      return [
        { label: "1 小时", value: 3600 },
        { label: "1 天", value: 86400 },
        { label: "7 天", value: 604800 },
        { label: "30 天", value: 2592000 },
        { label: "永久", value: 0 }
      ];
    }
  },
  mounted() {
    this.loadInitialData();
  },
  watch: {
    draftScopeType() {
      this.normalizeDraftUsersInScope();
    },
    draftScopeOrgId() {
      this.normalizeDraftUsersInScope();
    }
  },
  methods: {
    async loadInitialData() {
      this.loading = true;
      this.setNotice("");
      try {
        const [summary, tenants, permissions, agentOptions] = await Promise.all([
          fetchEnterpriseSummary(),
          fetchTenants(),
          fetchPermissions(),
          fetchAgentOptions()
        ]);
        this.summary = summary || {};
        this.tenants = Array.isArray(tenants) ? tenants : [];
        this.permissions = Array.isArray(permissions) ? permissions : [];
        this.agentOptions = Array.isArray(agentOptions) ? agentOptions : [];
        this.selectedTenantId = this.tenants[0]?.id || "";
        await this.loadTenantData();
      } catch (error) {
        this.setNotice(error.message || "系统管理数据加载失败", true);
      } finally {
        this.loading = false;
      }
    },
    async loadTenantData(keepRoleId = "") {
      if (!this.selectedTenantId) {
        return;
      }
      const [orgs, roles, users, summary] = await Promise.all([
        fetchOrgs(this.selectedTenantId),
        fetchRoles(this.selectedTenantId),
        fetchUsers(this.selectedTenantId),
        fetchEnterpriseSummary()
      ]);
      this.orgs = Array.isArray(orgs) ? orgs : [];
      this.roles = Array.isArray(roles) ? roles : [];
      this.users = Array.isArray(users) ? users : [];
      this.summary = summary || this.summary;
      const nextRole = this.roles.find((role) => role.id === keepRoleId) || this.roles[0];
      if (nextRole) {
        await this.selectRole(nextRole);
      } else {
        this.resetRoleForm();
        this.selectedRoleId = "";
        this.selectedPermissionIds = [];
        this.selectedAgentIds = [];
        this.selectedUserIds = [];
      }
    },
    async handleTenantChange() {
      this.loading = true;
      try {
        await this.loadTenantData();
      } catch (error) {
        this.setNotice(error.message || "租户数据加载失败", true);
      } finally {
        this.loading = false;
      }
    },
    async selectRole(role) {
      this.selectedRoleId = role.id;
      this.roleForm = {
        id: role.id,
        roleName: role.roleName || "",
        roleCode: role.roleCode || "",
        roleType: role.roleType || "business",
        status: role.status || "enabled",
        description: role.description || ""
      };
      try {
        const authorization = await fetchRoleAuthorization(role.id);
        this.selectedPermissionIds = Array.isArray(authorization?.permissionIds)
          ? [...authorization.permissionIds]
          : [];
        this.selectedUserIds = Array.isArray(authorization?.userIds)
          ? [...authorization.userIds]
          : [];
        this.selectedAgentIds = Array.isArray(authorization?.agentIds)
          ? [...authorization.agentIds]
          : [];
        const scope = Array.isArray(authorization?.orgScopes) ? authorization.orgScopes[0] : null;
        this.scopeType = scope?.scopeType || "org_and_children";
        this.scopeOrgId = scope?.orgId || "";
      } catch (error) {
        this.setNotice(error.message || "角色授权加载失败", true);
      }
    },
    resetRoleForm() {
      this.roleForm = blankRoleForm();
      this.selectedRoleId = "";
    },
    resetOrgForm() {
      this.orgForm = blankOrgForm();
    },
    openOrgModal(org = null) {
      if (org) {
        this.editOrg(org);
      } else {
        this.resetOrgForm();
      }
      this.orgModalOpen = true;
    },
    closeOrgModal() {
      this.orgModalOpen = false;
      this.resetOrgForm();
    },
    editOrg(org) {
      this.orgForm = {
        id: org.id,
        parentId: org.parentId || "",
        orgCode: org.orgCode || "",
        orgName: org.orgName || "",
        sortOrder: org.sortOrder ?? 0,
        status: org.status || "enabled"
      };
    },
    async saveOrgForm() {
      if (!this.orgForm.orgName || !this.orgForm.orgCode) {
        this.setNotice("请填写组织名称和组织编码", true);
        return;
      }
      this.savingOrg = true;
      try {
        const payload = {
          tenantId: this.selectedTenantId,
          parentId: this.orgForm.parentId || null,
          orgCode: this.orgForm.orgCode,
          orgName: this.orgForm.orgName,
          sortOrder: Number(this.orgForm.sortOrder) || 0,
          status: this.orgForm.status
        };
        const saved = this.orgForm.id
          ? await updateOrg(this.orgForm.id, payload)
          : await createOrg(payload);
        this.setNotice(this.orgForm.id ? "组织已更新" : "组织已新增");
        this.resetOrgForm();
        this.orgModalOpen = false;
        await this.loadTenantData(this.selectedRoleId);
        if (saved?.id) {
          this.userForm.orgId = saved.id;
        }
      } catch (error) {
        this.setNotice(error.message || "组织保存失败", true);
      } finally {
        this.savingOrg = false;
      }
    },
    async removeOrg(org) {
      if (!org?.id) {
        return;
      }
      const childCount = this.orgs.filter((item) => item.parentId === org.id).length;
      const userCount = this.orgUserCounts[org.id] || 0;
      const suffix = childCount || userCount
        ? `，该组织下有 ${childCount} 个下级组织、${userCount} 个账户`
        : "";
      if (!window.confirm(`确认删除组织「${org.orgName}」${suffix}？`)) {
        return;
      }
      this.savingOrg = true;
      try {
        await deleteOrg(org.id);
        this.setNotice("组织已删除");
        if (this.orgForm.id === org.id) {
          this.resetOrgForm();
        }
        await this.loadTenantData(this.selectedRoleId);
      } catch (error) {
        this.setNotice(error.message || "组织删除失败", true);
      } finally {
        this.savingOrg = false;
      }
    },
    resetUserForm() {
      this.userForm = blankUserForm();
    },
    openUserModal(user = null) {
      if (user) {
        this.editUser(user);
      } else {
        this.resetUserForm();
      }
      this.userModalOpen = true;
    },
    closeUserModal() {
      this.userModalOpen = false;
      this.resetUserForm();
    },
    openAdminPasswordModal() {
      this.adminPasswordForm = blankAdminPasswordForm();
      this.adminPasswordModalOpen = true;
    },
    closeAdminPasswordModal() {
      this.adminPasswordModalOpen = false;
      this.adminPasswordForm = blankAdminPasswordForm();
    },
    async saveAdminPasswordForm() {
      const form = this.adminPasswordForm;
      if (!form.currentPassword || !form.newPassword || !form.confirmPassword) {
        this.setNotice("请完整填写当前密码、新密码和确认密码", true);
        return;
      }
      if (form.newPassword.length < 6) {
        this.setNotice("新密码至少 6 位", true);
        return;
      }
      if (form.newPassword !== form.confirmPassword) {
        this.setNotice("两次输入的新密码不一致", true);
        return;
      }
      this.savingAdminPassword = true;
      try {
        await changeAdminPassword({
          currentPassword: form.currentPassword,
          newPassword: form.newPassword,
          confirmPassword: form.confirmPassword
        });
        this.setNotice("admin 密码已修改，请使用新密码重新登录");
        this.closeAdminPasswordModal();
        clearAuthSession();
        window.dispatchEvent(new CustomEvent(AUTH_REQUIRED_EVENT, {
          detail: { reason: "admin_password_changed" }
        }));
      } catch (error) {
        this.setNotice(error.message || "admin 密码修改失败", true);
      } finally {
        this.savingAdminPassword = false;
      }
    },
    async openEmbedTokenModal(user = null) {
      if (!this.isAdminUser(user)) {
        this.setNotice("仅 admin 用户可以查看嵌入登录 URL", true);
        return;
      }
      this.embedTokenModalOpen = true;
      this.embedTokenLatestUrl = "";
      await this.loadEmbedTokens();
    },
    closeEmbedTokenModal() {
      this.embedTokenModalOpen = false;
      this.embedTokenLatestUrl = "";
    },
    async loadEmbedTokens() {
      this.embedTokenLoading = true;
      try {
        const tokens = await fetchEmbedLoginTokens();
        this.embedTokens = Array.isArray(tokens) ? tokens : [];
      } catch (error) {
        this.setNotice(error.message || "嵌入登录授权加载失败", true);
      } finally {
        this.embedTokenLoading = false;
      }
    },
    async createEmbedToken() {
      this.embedTokenSaving = true;
      try {
        const token = await createEmbedLoginToken(Number(this.embedTokenDuration) || 0);
        this.embedTokenLatestUrl = this.buildEmbedLoginUrl(token);
        await this.loadEmbedTokens();
        this.setNotice("嵌入登录 URL 已生成");
      } catch (error) {
        this.setNotice(error.message || "嵌入登录 URL 生成失败", true);
      } finally {
        this.embedTokenSaving = false;
      }
    },
    async expireEmbedToken(token) {
      if (!token?.id || !window.confirm("确认让该授权立即过期？")) {
        return;
      }
      this.embedTokenSaving = true;
      try {
        await expireEmbedLoginToken(token.id);
        await this.loadEmbedTokens();
        this.setNotice("嵌入登录授权已过期");
      } catch (error) {
        this.setNotice(error.message || "嵌入登录授权过期失败", true);
      } finally {
        this.embedTokenSaving = false;
      }
    },
    async copyEmbedTokenUrl(token) {
      const url = typeof token === "string" ? token : this.buildEmbedLoginUrl(token);
      if (!url) {
        return;
      }
      try {
        await navigator.clipboard.writeText(url);
        this.setNotice("嵌入登录 URL 已复制");
      } catch (error) {
        window.prompt("复制嵌入登录 URL", url);
      }
    },
    buildEmbedLoginUrl(token) {
      const rawToken = token?.token || "";
      if (!rawToken) {
        return "";
      }
      const baseUrl = `${window.location.origin}${window.location.pathname || "/"}`;
      return `${baseUrl}?embedToken=${encodeURIComponent(rawToken)}#/chat`;
    },
    editUser(user) {
      this.userForm = {
        id: user.id,
        orgId: user.orgId || "",
        username: user.username || "",
        displayName: user.displayName || "",
        password: "",
        email: user.email || "",
        phone: user.phone || "",
        status: user.status || "enabled",
        roleIds: Array.isArray(user.roleIds) ? [...user.roleIds] : []
      };
    },
    setUserRole(roleId) {
      this.userForm.roleIds = roleId ? [roleId] : [];
    },
    async saveUserForm() {
      if (!this.userForm.username || !this.userForm.displayName) {
        this.setNotice("请填写账号和姓名", true);
        return;
      }
      this.savingUser = true;
      try {
        const user = {
          tenantId: this.selectedTenantId,
          orgId: this.userForm.orgId || null,
          username: this.userForm.username,
          displayName: this.userForm.displayName,
          email: this.userForm.email,
          phone: this.userForm.phone,
          status: this.userForm.status
        };
        if (this.userForm.password) {
          user.passwordHash = this.userForm.password;
        }
        const payload = {
          user,
          roleIds: this.userForm.roleIds
        };
        const saved = this.userForm.id
          ? await updateUser(this.userForm.id, payload)
          : await createUser(payload);
        this.setNotice(this.userForm.id ? "账户已更新" : "账户已新增");
        this.resetUserForm();
        this.userModalOpen = false;
        await this.loadTenantData(this.selectedRoleId);
        if (saved?.id) {
          this.editUser(saved);
        }
      } catch (error) {
        this.setNotice(error.message || "账户保存失败", true);
      } finally {
        this.savingUser = false;
      }
    },
    async removeUser(user) {
      if (this.isAdminUser(user)) {
        this.setNotice("admin 用户禁止删除", true);
        return;
      }
      if (!user?.id || !window.confirm(`确认删除账户「${user.displayName || user.username}」？`)) {
        return;
      }
      this.savingUser = true;
      try {
        await deleteUser(user.id);
        this.setNotice("账户已删除");
        if (this.userForm.id === user.id) {
          this.resetUserForm();
        }
        await this.loadTenantData(this.selectedRoleId);
      } catch (error) {
        this.setNotice(error.message || "账户删除失败", true);
      } finally {
        this.savingUser = false;
      }
    },
    openRoleModal(role = null) {
      this.roleForm = role
        ? {
            id: role.id,
            roleName: role.roleName || "",
            roleCode: role.roleCode || "",
            roleType: role.roleType || "business",
            status: role.status || "enabled",
            description: role.description || ""
        }
        : blankRoleForm();
      this.draftPermissionIds = role ? [...this.selectedPermissionIds] : [];
      this.draftAgentIds = role ? [...this.selectedAgentIds] : [];
      this.draftUserIds = role ? [...this.selectedUserIds] : [];
      this.draftScopeType = role ? this.scopeType || "org_and_children" : "org_and_children";
      this.draftScopeOrgId = role ? this.scopeOrgId || "" : "";
      this.userPickerOpen = false;
      this.agentPickerOpen = false;
      this.tempPickerUserIds = [];
      this.tempPickerAgentIds = [];
      this.agentPickerQuery = "";
      this.roleModalOpen = true;
    },
    closeRoleModal() {
      this.roleModalOpen = false;
      this.roleForm = this.selectedRole
        ? {
            id: this.selectedRole.id,
            roleName: this.selectedRole.roleName || "",
            roleCode: this.selectedRole.roleCode || "",
            roleType: this.selectedRole.roleType || "business",
            status: this.selectedRole.status || "enabled",
            description: this.selectedRole.description || ""
          }
        : blankRoleForm();
      this.draftPermissionIds = [];
      this.draftAgentIds = [];
      this.draftUserIds = [];
      this.draftScopeType = this.scopeType || "org_and_children";
      this.draftScopeOrgId = this.scopeOrgId || "";
      this.agentPickerOpen = false;
      this.userPickerOpen = false;
      this.tempPickerAgentIds = [];
      this.tempPickerUserIds = [];
      this.agentPickerQuery = "";
    },
    async saveRoleForm() {
      if (!this.roleForm.roleName || !this.roleForm.roleCode) {
        this.setNotice("请填写角色名称和角色编码", true);
        return;
      }
      this.savingRole = true;
      try {
        const payload = {
          tenantId: this.selectedTenantId,
          roleName: this.roleForm.roleName,
          roleCode: this.roleForm.roleCode,
          roleType: this.roleForm.roleType,
          status: this.roleForm.status,
          description: this.roleForm.description
        };
        const saved = this.roleForm.id
          ? await updateRole(this.roleForm.id, payload)
          : await createRole(payload);
        await saveRoleAuthorization(saved.id, {
          permissionIds: this.draftPermissionIds,
          agentIds: this.draftAgentIds,
          orgScopes: [
            {
              scopeType: this.draftScopeType,
              orgId: this.draftScopeType === "all" ? null : this.draftScopeOrgId
            }
          ],
          userIds: this.draftUserIds
        });
        this.setNotice("角色已保存");
        this.roleModalOpen = false;
        await this.loadTenantData(saved.id);
      } catch (error) {
        this.setNotice(error.message || "角色保存失败", true);
      } finally {
        this.savingRole = false;
      }
    },
    async removeRole(role = null) {
      const roleId = role?.id || this.selectedRoleId;
      const roleName = role?.roleName || this.selectedRole?.roleName || "当前角色";
      if (!roleId || !window.confirm(`确认删除角色「${roleName}」？`)) {
        return;
      }
      this.savingRole = true;
      try {
        await deleteRole(roleId);
        this.setNotice("角色已删除");
        await this.loadTenantData();
      } catch (error) {
        this.setNotice(error.message || "角色删除失败", true);
      } finally {
        this.savingRole = false;
      }
    },
    selectAllPermissions() {
      this.draftPermissionIds = this.permissions.map((permission) => permission.id);
    },
    clearPermissions() {
      this.draftPermissionIds = [];
    },
    togglePermissionGroup(group, checked) {
      const groupIds = new Set(group.permissionIds);
      if (checked) {
        this.draftPermissionIds = Array.from(new Set([...this.draftPermissionIds, ...group.permissionIds]));
        return;
      }
      this.draftPermissionIds = this.draftPermissionIds.filter((id) => !groupIds.has(id));
    },
    selectAllAgents() {
      this.draftAgentIds = this.agentOptions.map((agent) => agent.id);
    },
    clearAgents() {
      this.draftAgentIds = [];
    },
    removeDraftAgent(agentId) {
      this.draftAgentIds = this.draftAgentIds.filter((id) => id !== agentId);
    },
    openAgentPicker() {
      this.tempPickerAgentIds = [...this.draftAgentIds];
      this.agentPickerQuery = "";
      this.agentPickerOpen = true;
    },
    closeAgentPicker() {
      this.agentPickerOpen = false;
      this.tempPickerAgentIds = [];
      this.agentPickerQuery = "";
    },
    selectAllPickerAgents() {
      this.tempPickerAgentIds = this.filteredAgentOptions.map((agent) => agent.id);
    },
    clearPickerAgents() {
      this.tempPickerAgentIds = [];
    },
    confirmAgentPicker() {
      this.draftAgentIds = [...this.tempPickerAgentIds];
      this.closeAgentPicker();
    },
    addDraftUser(userId) {
      if (!userId || this.draftUserIds.includes(userId)) {
        return;
      }
      this.draftUserIds = [...this.draftUserIds, userId];
    },
    removeDraftUser(userId) {
      this.draftUserIds = this.draftUserIds.filter((id) => id !== userId);
    },
    openUserPicker() {
      this.tempPickerUserIds = [...this.draftUserIds];
      this.userPickerOpen = true;
    },
    closeUserPicker() {
      this.userPickerOpen = false;
      this.tempPickerUserIds = [];
    },
    selectAllPickerUsers() {
      this.tempPickerUserIds = this.scopedUsers.map((user) => user.id);
    },
    clearPickerUsers() {
      this.tempPickerUserIds = [];
    },
    confirmUserPicker() {
      this.draftUserIds = [...this.tempPickerUserIds];
      this.closeUserPicker();
    },
    normalizeDraftUsersInScope() {
      if (this.draftScopeType === "all" || !this.draftScopeOrgId) {
        return;
      }
      const scopedIds = new Set(this.scopedUsers.map((user) => user.id));
      this.draftUserIds = this.draftUserIds.filter((id) => scopedIds.has(id));
    },
    focusPermission(permission) {
      this.focusedPermissionId = permission?.id || "";
    },
    async syncOrgs() {
      this.loading = true;
      try {
        const result = await syncEnterpriseOrgs(this.selectedTenantId);
        this.setNotice(`组织同步完成：新增 ${result.created}，更新 ${result.updated}`);
        await this.loadTenantData(this.selectedRoleId);
      } catch (error) {
        this.setNotice(error.message || "组织同步失败", true);
      } finally {
        this.loading = false;
      }
    },
    async syncUsers() {
      this.loading = true;
      try {
        const result = await syncEnterpriseUsers(this.selectedTenantId);
        this.setNotice(`用户同步完成：新增 ${result.created}，更新 ${result.updated}`);
        await this.loadTenantData(this.selectedRoleId);
      } catch (error) {
        this.setNotice(error.message || "用户同步失败", true);
      } finally {
        this.loading = false;
      }
    },
    orgName(orgId) {
      return this.orgs.find((org) => org.id === orgId)?.orgName || "未分配组织";
    },
    typeLabel(type) {
      const labels = {
        menu: "菜单",
        button: "按钮",
        api: "接口"
      };
      return labels[type] || type || "权限";
    },
    roleTypeLabel(type) {
      const labels = {
        platform: "平台",
        tenant: "租户",
        business: "业务",
        guest: "访客"
      };
      return labels[type] || type || "业务";
    },
    statusLabel(status) {
      const labels = {
        enabled: "启用",
        disabled: "停用",
        locked: "锁定"
      };
      return labels[status] || status || "启用";
    },
    isAdminUser(user) {
      return String(user?.username || "").toLowerCase() === "admin";
    },
    embedTokenStatusLabel(token) {
      if (this.isEmbedTokenExpired(token)) {
        return "已过期";
      }
      return token?.status === "active" ? "有效" : "已过期";
    },
    isEmbedTokenExpired(token) {
      if (!token || token.status !== "active") {
        return true;
      }
      if (!token.expiresAt) {
        return false;
      }
      return new Date(token.expiresAt).getTime() <= Date.now();
    },
    formatDateTime(value) {
      if (!value) {
        return "永久";
      }
      const date = new Date(value);
      if (Number.isNaN(date.getTime())) {
        return value;
      }
      return date.toLocaleString();
    },
    roleNamesForUser(user) {
      return (Array.isArray(user?.roleIds) ? user.roleIds : [])
        .map((roleId) => this.rolesById[roleId]?.roleName)
        .filter(Boolean);
    },
    scopeTypeLabel(type) {
      const labels = {
        all: "全部组织",
        org: "指定组织",
        org_and_children: "组织及下级",
        custom: "自定义组织"
      };
      return labels[type] || type || "未配置";
    },
    setNotice(text, isError = false) {
      this.message = text;
      this.error = isError ? text : "";
    }
  }
};
