import {
  Building2,
  Copy,
  KeyRound,
  Pencil,
  Plus,
  RefreshCw,
  RotateCcw,
  Save,
  ShieldCheck,
  Trash2,
  Users,
  X,
} from "@lucide/vue";
import "../../styles/pages/system-management.css";
import "../../styles/pages/permission-workspace.css";

import {
  AUTH_REQUIRED_EVENT,
  getStoredAuthSession,
  changeAdminPassword,
  clearAuthSession,
  createOrg,
  createAgentApiToken,
  createUser,
  deleteUser,
  fetchAgentApiTokens,
  fetchLoginAuditLogs,
  fetchOrgs,
  fetchRoles,
  fetchUsers,
  resetAgentApiToken,
  revokeAgentApiToken,
  syncEnterpriseOrgs,
  syncEnterpriseUsers,
  updateOrg,
  updateUser,
} from "../../services/api";

const blankOrgForm = () => ({
  id: "",
  parentId: "",
  orgCode: "",
  orgName: "",
  sortOrder: 0,
  status: "enabled",
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
  roleIds: [],
});

const blankAdminPasswordForm = () => ({
  currentPassword: "",
  newPassword: "",
  confirmPassword: "",
});

export default {
  name: "SystemManagementView",
  props: {
    embedded: { type: Boolean, default: false },
    section: {
      type: String,
      default: "users",
      validator: (value) => ["users", "organizations", "logins"].includes(value),
    },
  },
  components: {
    Building2,
    Copy,
    KeyRound,
    Pencil,
    Plus,
    RefreshCw,

    RotateCcw,
    Save,
    ShieldCheck,
    Trash2,
    Users,
    X,
  },
  data() {
    return {
      loading: false,
      entityQuery: "",
      entityStatus: "",
      entityPage: 1,
      entityPageSize: 20,
      savingOrg: false,
      savingUser: false,
      savingAdminPassword: false,
      apiTokenLoading: false,
      apiTokenSaving: false,
      orgModalOpen: false,
      userModalOpen: false,
      adminPasswordModalOpen: false,
      apiTokenModalOpen: false,
      apiTokenCopyDialogOpen: false,
      error: "",
      message: "",
      noticeTimer: null,
      activeManagementTab: this.section,
      tenants: [],
      orgs: [],
      roles: [],
      users: [],
      loginAuditLogs: [],
      loginAuditTotal: 0,
      loginAuditPage: 1,
      loginAuditPageSize: 20,
      loginAuditKeyword: "",
      loginAuditAction: "",
      loginAuditResult: "",
      loginAuditTenantId: "",
      apiTokens: [],
      apiTokenDuration: 2592000,
      apiTokenName: "",
      apiTokenLatestSecret: "",
      apiTokenCopySecret: "",
      apiTokenTargetUser: null,
      selectedTenantId: "",
      orgForm: blankOrgForm(),
      userForm: blankUserForm(),
      adminPasswordForm: blankAdminPasswordForm(),
    };
  },
  computed: {
    filteredEntities() {
      const data = this.section === "users" ? this.users : this.orgTree;
      const query = this.entityQuery.toLowerCase();
      return data.filter(
        (item) =>
          (!this.entityStatus || item.status === this.entityStatus) &&
          [item.displayName, item.username, item.orgName].some((v) =>
            String(v || "")
              .toLowerCase()
              .includes(query)
          )
      );
    },
    entityPages() {
      return Math.max(1, Math.ceil(this.filteredEntities.length / this.entityPageSize));
    },
    pagedUsers() {
      return this.filteredEntities.slice(
        (this.entityPage - 1) * this.entityPageSize,
        this.entityPage * this.entityPageSize
      );
    },
    pagedOrgs() {
      return this.pagedUsers;
    },
    sectionTitle() {
      return { users: "用户管理", organizations: "组织管理", logins: "安全审计" }[this.section];
    },
    sectionDescription() {
      return {
        users: "管理账户、组织归属、角色分配及服务访问令牌",
        organizations: "维护组织档案与层级，查看成员归属",
        logins: "查询用户登录和服务访问认证记录",
      }[this.section];
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
    selectableOrgTree() {
      return this.orgTree.filter(
        (org) => org.status === "enabled" || org.id === this.userForm.orgId
      );
    },
    rolesById() {
      return this.roles.reduce((result, role) => {
        result[role.id] = role;
        return result;
      }, {});
    },
    apiTokenDurations() {
      return [
        { label: "1 天", value: 86400 },
        { label: "7 天", value: 604800 },
        { label: "30 天", value: 2592000 },
        { label: "90 天", value: 7776000 },
        { label: "1 年", value: 31536000 },
        { label: "永久", value: 0 },
      ];
    },
    loginAuditRows() {
      return this.loginAuditLogs.map((audit) => {
        const detail = this.parseAuditDetail(audit.detail);
        return {
          ...audit,
          detailData: detail,
          loginTime: this.formatDateTime(audit.createdAt),
          loginUser: detail.username || detail.attemptedUsername || audit.actorName || "-",
          loginAction: this.loginActionLabel(audit.actionName),
          loginResult: this.loginResultLabel(audit.result),
          tenantName: this.tenantName(detail.tenantId || audit.tenantId),
          ipAddress: detail.ipAddress || "-",
          macAddress: detail.macAddress || "-",
          userAgent: detail.userAgent || "-",
          failureReason: detail.reason || audit.detail || "",
        };
      });
    },
    loginAuditPageCount() {
      return Math.max(1, Math.ceil(this.loginAuditTotal / Math.max(1, this.loginAuditPageSize)));
    },
    loginAuditPageStart() {
      return this.loginAuditTotal === 0
        ? 0
        : (this.loginAuditPage - 1) * this.loginAuditPageSize + 1;
    },
    loginAuditPageEnd() {
      return Math.min(this.loginAuditTotal, this.loginAuditPage * this.loginAuditPageSize);
    },
    loginAuditPageButtons() {
      const total = this.loginAuditPageCount;
      const current = Math.min(this.loginAuditPage, total);
      const start = Math.max(1, current - 2);
      const end = Math.min(total, start + 4);
      const adjustedStart = Math.max(1, end - 4);
      const buttons = [];
      for (let page = adjustedStart; page <= end; page += 1) {
        buttons.push(page);
      }
      return buttons;
    },
  },
  mounted() {
    this.loadInitialData();
  },
  beforeUnmount() {
    if (this.noticeTimer) {
      globalThis.clearTimeout(this.noticeTimer);
      this.noticeTimer = null;
    }
  },
  watch: {
    entityQuery() {
      this.entityPage = 1;
    },
    entityStatus() {
      this.entityPage = 1;
    },
    entityPageSize() {
      this.entityPage = 1;
    },
    section(value) {
      this.activeManagementTab = value;
      this.loadInitialData();
    },
  },
  methods: {
    openOrgModal(org = null) {
      this.orgForm = org ? { ...org } : blankOrgForm();
      this.orgModalOpen = true;
    },
    async loadInitialData() {
      this.loading = true;
      this.setNotice("");
      try {
        const user = getStoredAuthSession()?.user;
        this.selectedTenantId = user?.tenantId || "";
        this.tenants = [{ id: this.selectedTenantId, tenantName: "当前租户" }];
        await this.loadTenantData();
      } catch (error) {
        this.setNotice(error.message, true);
      } finally {
        this.loading = false;
      }
    },
    async loadTenantData() {
      if (!this.selectedTenantId) return;
      if (this.activeManagementTab === "logins") {
        const audits = await fetchLoginAuditLogs(this.loginAuditQuery());
        this.loginAuditLogs = audits;
        this.applyLoginAuditPage(audits);
        return;
      }
      const [orgs, roles, users] = await Promise.all([
        fetchOrgs(this.selectedTenantId),
        this.activeManagementTab === "users" ? fetchRoles(this.selectedTenantId) : [],
        fetchUsers(this.selectedTenantId),
      ]);
      this.orgs = orgs || [];
      this.roles = roles || [];
      this.users = users || [];
    },
    resetOrgForm() {
      this.orgForm = blankOrgForm();
    },
    closeOrgModal() {
      this.orgModalOpen = false;
      this.resetOrgForm();
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
          status: this.orgForm.status,
        };
        const saved = this.orgForm.id
          ? await updateOrg(this.orgForm.id, payload)
          : await createOrg(payload);
        this.setNotice(this.orgForm.id ? "组织已更新" : "组织已新增");
        this.resetOrgForm();
        this.orgModalOpen = false;
        await this.loadTenantData();
        if (saved?.id) {
          this.userForm.orgId = saved.id;
        }
      } catch (error) {
        this.setNotice(error.message || "组织保存失败", true);
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
          confirmPassword: form.confirmPassword,
        });
        this.setNotice("admin 密码已修改，请使用新密码重新登录");
        this.closeAdminPasswordModal();
        clearAuthSession();
        window.dispatchEvent(
          new CustomEvent(AUTH_REQUIRED_EVENT, {
            detail: { reason: "admin_password_changed" },
          })
        );
      } catch (error) {
        this.setNotice(error.message || "admin 密码修改失败", true);
      } finally {
        this.savingAdminPassword = false;
      }
    },
    async openApiTokenModal(user = null) {
      if (!user?.id) {
        return;
      }
      if (user.status !== "enabled") {
        this.setNotice("停用或锁定用户不能生成 Agent API 令牌", true);
        return;
      }
      this.apiTokenTargetUser = user;
      this.apiTokenName = `${user.username} Agent API`;
      this.apiTokenModalOpen = true;
      this.apiTokenLatestSecret = "";
      await this.loadApiTokens();
    },
    closeApiTokenModal() {
      this.closeApiTokenCopyDialog();
      this.apiTokenModalOpen = false;
      this.apiTokenLatestSecret = "";
      this.apiTokenTargetUser = null;
    },
    async loadApiTokens() {
      this.apiTokenLoading = true;
      try {
        const tokens = await fetchAgentApiTokens(this.apiTokenTargetUser?.id || "");
        this.apiTokens = Array.isArray(tokens) ? tokens : [];
      } catch (error) {
        this.setNotice(error.message || "Agent API 令牌加载失败", true);
      } finally {
        this.apiTokenLoading = false;
      }
    },
    async createApiToken() {
      if (!this.apiTokenTargetUser?.id) {
        return;
      }
      this.apiTokenSaving = true;
      try {
        const duration = Number(this.apiTokenDuration) || 0;
        const issued = await createAgentApiToken({
          userId: this.apiTokenTargetUser.id,
          tokenName: this.apiTokenName,
          permanent: duration === 0,
          expiresInSeconds: duration === 0 ? null : duration,
        });
        this.apiTokenLatestSecret = issued?.secret || "";
        await this.loadApiTokens();
        this.setNotice("Agent API 令牌已生成，请立即复制保存");
      } catch (error) {
        this.setNotice(error.message || "Agent API 令牌生成失败", true);
      } finally {
        this.apiTokenSaving = false;
      }
    },
    async revokeApiToken(token) {
      if (!token?.id || !window.confirm("确认立即吊销该 Agent API 令牌？吊销后无法恢复。")) {
        return;
      }
      this.apiTokenSaving = true;
      try {
        await revokeAgentApiToken(token.id);
        await this.loadApiTokens();
        this.setNotice("Agent API 令牌已吊销");
      } catch (error) {
        this.setNotice(error.message || "Agent API 令牌吊销失败", true);
      } finally {
        this.apiTokenSaving = false;
      }
    },
    async resetApiToken(token) {
      if (!token?.id || !window.confirm("确认重置该令牌？旧令牌会立即失效。")) {
        return;
      }
      this.apiTokenSaving = true;
      try {
        const duration = Number(this.apiTokenDuration) || 0;
        const issued = await resetAgentApiToken(token.id, {
          permanent: duration === 0,
          expiresInSeconds: duration === 0 ? null : duration,
        });
        this.apiTokenLatestSecret = issued?.secret || "";
        await this.loadApiTokens();
        this.setNotice("令牌已重置，旧值已失效，请立即复制新令牌");
      } catch (error) {
        this.setNotice(error.message || "Agent API 令牌重置失败", true);
      } finally {
        this.apiTokenSaving = false;
      }
    },
    async copyApiTokenSecret(secret) {
      if (!secret) {
        return "";
      }
      try {
        if (!globalThis.navigator?.clipboard?.writeText) {
          throw new Error("Clipboard API is unavailable");
        }
        await globalThis.navigator.clipboard.writeText(secret);
        this.setNotice("Agent API 令牌已复制");
      } catch (error) {
        this.openApiTokenCopyDialog(secret);
      }
    },
    openApiTokenCopyDialog(secret) {
      this.apiTokenCopySecret = String(secret || "");
      this.apiTokenCopyDialogOpen = Boolean(this.apiTokenCopySecret);
      this.$nextTick(() => {
        const input = this.$refs.apiTokenCopyInput;
        input?.focus();
        input?.select();
      });
    },
    closeApiTokenCopyDialog() {
      this.apiTokenCopyDialogOpen = false;
      this.apiTokenCopySecret = "";
    },
    copyApiTokenFromDialog() {
      const input = this.$refs.apiTokenCopyInput;
      if (!input) {
        return;
      }
      input.focus();
      input.select();
      input.setSelectionRange?.(0, input.value.length);
      try {
        if (document.execCommand("copy")) {
          this.setNotice("Agent API 令牌已复制");
          this.closeApiTokenCopyDialog();
          return;
        }
      } catch (error) {
        // Keep the token selected so the user can copy it manually.
      }
      this.setNotice("浏览器未授权自动复制，请按 Ctrl+C 手动复制", true);
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
        roleIds: Array.isArray(user.roleIds) ? [...user.roleIds] : [],
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
          status: this.userForm.status,
        };
        if (this.userForm.password) {
          user.passwordHash = this.userForm.password;
        }
        const payload = {
          user,
          roleIds: this.userForm.roleIds,
        };
        const saved = this.userForm.id
          ? await updateUser(this.userForm.id, payload)
          : await createUser(payload);
        this.setNotice(this.userForm.id ? "账户已更新" : "账户已新增");
        this.resetUserForm();
        this.userModalOpen = false;
        await this.loadTenantData();
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
      if (this.isProtectedUser(user)) {
        this.setNotice("内置账户禁止删除", true);
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
        await this.loadTenantData();
      } catch (error) {
        this.setNotice(error.message || "账户删除失败", true);
      } finally {
        this.savingUser = false;
      }
    },
    async syncOrgs() {
      this.loading = true;
      try {
        const result = await syncEnterpriseOrgs(this.selectedTenantId);
        this.setNotice(`组织同步完成：新增 ${result.created}，更新 ${result.updated}`);
        await this.loadTenantData();
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
        await this.loadTenantData();
      } catch (error) {
        this.setNotice(error.message || "用户同步失败", true);
      } finally {
        this.loading = false;
      }
    },
    async loadLoginAuditLogs() {
      this.loading = true;
      try {
        const logs = await fetchLoginAuditLogs(this.loginAuditQuery());
        this.applyLoginAuditPage(logs);
        this.setNotice("登录审计已刷新");
      } catch (error) {
        this.setNotice(error.message || "登录审计加载失败", true);
      } finally {
        this.loading = false;
      }
    },
    loginAuditQuery() {
      return {
        page: this.loginAuditPage,
        pageSize: this.loginAuditPageSize,
        tenantId: this.loginAuditTenantId,
        actionName: this.loginAuditAction,
        result: this.loginAuditResult,
        keyword: this.loginAuditKeyword,
      };
    },
    applyLoginAuditPage(page) {
      if (Array.isArray(page)) {
        this.loginAuditLogs = page;
        this.loginAuditTotal = page.length;
        return;
      }
      this.loginAuditLogs = Array.isArray(page?.items) ? page.items : [];
      this.loginAuditTotal =
        Number(page?.total ?? page?.filteredCount ?? this.loginAuditLogs.length) || 0;
      this.loginAuditPage = Number(page?.page) || this.loginAuditPage;
      this.loginAuditPageSize = Number(page?.pageSize) || this.loginAuditPageSize;
    },
    async searchLoginAuditLogs() {
      this.loginAuditPage = 1;
      await this.loadLoginAuditLogs();
    },
    async changeLoginAuditPage(page) {
      const nextPage = Math.min(Math.max(1, page), this.loginAuditPageCount);
      if (nextPage === this.loginAuditPage) {
        return;
      }
      this.loginAuditPage = nextPage;
      await this.loadLoginAuditLogs();
    },
    async changeLoginAuditPageSize(event) {
      this.loginAuditPageSize = Number(event?.target?.value || event || 20);
      this.loginAuditPage = 1;
      await this.loadLoginAuditLogs();
    },
    tenantName(tenantId) {
      if (!tenantId) {
        return "未识别租户";
      }
      const tenant = this.tenants.find((item) => item.id === tenantId);
      return tenant?.tenantName || tenant?.tenantCode || tenantId;
    },
    orgName(orgId) {
      return this.orgs.find((org) => org.id === orgId)?.orgName || "未分配组织";
    },
    statusLabel(status) {
      const labels = {
        enabled: "启用",
        disabled: "停用",
        locked: "锁定",
      };
      return labels[status] || status || "启用";
    },
    loginActionLabel(actionName) {
      const labels = {
        login: "密码登录",
        "embed-login": "历史嵌入登录",
        "agent-api-token-authenticate": "Agent API 认证",
      };
      return labels[actionName] || actionName || "登录";
    },
    loginResultLabel(result) {
      const labels = {
        success: "成功",
        failure: "失败",
      };
      return labels[result] || result || "-";
    },
    parseAuditDetail(detail) {
      if (!detail || typeof detail !== "string") {
        return {};
      }
      try {
        const parsed = JSON.parse(detail);
        return parsed && typeof parsed === "object" ? parsed : {};
      } catch (error) {
        return {};
      }
    },
    isAdminUser(user) {
      return user?.protectedAccount === true;
    },
    isProtectedUser(user) {
      return user?.protectedAccount === true;
    },
    apiTokenStatusLabel(token) {
      if (this.isApiTokenInactive(token)) {
        return token?.status === "revoked" ? "已吊销" : "已过期";
      }
      return "有效";
    },
    isApiTokenInactive(token) {
      if (!token || token.status !== "active") {
        return true;
      }
      if (!token.expiresAt) {
        return false;
      }
      return new Date(token.expiresAt).getTime() <= Date.now();
    },
    apiTokenExpiryLabel(token) {
      if (token?.permanent) {
        return "永久";
      }
      if (!token?.expiresAt) {
        return "已过期";
      }
      return this.formatDateTime(token.expiresAt);
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
    setNotice(text, isError = false) {
      if (this.noticeTimer) {
        globalThis.clearTimeout(this.noticeTimer);
        this.noticeTimer = null;
      }
      this.message = text;
      this.error = isError ? text : "";
      if (text) {
        this.noticeTimer = globalThis.setTimeout(() => {
          this.message = "";
          this.error = "";
          this.noticeTimer = null;
        }, 3500);
      }
    },
  },
};
