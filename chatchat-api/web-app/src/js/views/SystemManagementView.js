import {
  Building2,
  Pencil,
  Plus,
  RefreshCw,
  Save,
  ShieldCheck,
  Trash2,
  Users,
  X
} from "@lucide/vue";
import "../../styles/pages/system-management.css";
import {
  createRole,
  deleteRole,
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
  updateRole
} from "../../services/api";

const blankRoleForm = () => ({
  id: "",
  roleName: "",
  roleCode: "",
  roleType: "business",
  status: "enabled",
  description: ""
});

export default {
  name: "SystemManagementView",
  components: {
    Building2,
    Pencil,
    Plus,
    RefreshCw,
    Save,
    ShieldCheck,
    Trash2,
    Users,
    X
  },
  data() {
    return {
      loading: false,
      savingRole: false,
      roleModalOpen: false,
      error: "",
      message: "",
      summary: {},
      tenants: [],
      orgs: [],
      roles: [],
      users: [],
      permissions: [],
      selectedTenantId: "",
      selectedRoleId: "",
      selectedPermissionIds: [],
      focusedPermissionId: "",
      selectedUserIds: [],
      scopeType: "org_and_children",
      scopeOrgId: "",
      draftPermissionIds: [],
      draftUserIds: [],
      draftScopeType: "org_and_children",
      draftScopeOrgId: "",
      userPickerOpen: false,
      tempPickerUserIds: [],
      roleForm: blankRoleForm()
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
    availableDraftUsers() {
      const selected = new Set(this.draftUserIds);
      return this.scopedUsers.filter((user) => !selected.has(user.id));
    },
    focusedPermission() {
      return this.permissions.find((permission) => permission.id === this.focusedPermissionId) || null;
    },
    selectedRole() {
      return this.roles.find((role) => role.id === this.selectedRoleId) || null;
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
        const [summary, tenants, permissions] = await Promise.all([
          fetchEnterpriseSummary(),
          fetchTenants(),
          fetchPermissions()
        ]);
        this.summary = summary || {};
        this.tenants = Array.isArray(tenants) ? tenants : [];
        this.permissions = Array.isArray(permissions) ? permissions : [];
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
      this.draftUserIds = role ? [...this.selectedUserIds] : [];
      this.draftScopeType = role ? this.scopeType || "org_and_children" : "org_and_children";
      this.draftScopeOrgId = role ? this.scopeOrgId || "" : "";
      this.userPickerOpen = false;
      this.tempPickerUserIds = [];
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
      this.draftUserIds = [];
      this.draftScopeType = this.scopeType || "org_and_children";
      this.draftScopeOrgId = this.scopeOrgId || "";
      this.userPickerOpen = false;
      this.tempPickerUserIds = [];
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
    async removeRole() {
      if (!this.selectedRoleId || !window.confirm("确认删除当前角色？")) {
        return;
      }
      this.savingRole = true;
      try {
        await deleteRole(this.selectedRoleId);
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
