import { ElMessageBox } from 'element-plus';
import { apiServicesApi, assetsApi, authApi, authorizationApi, databaseApi } from '../../services/api';
import '../../styles/views/settings.css';

export default {
  name: 'SettingsView',
  emits: ['notify', 'error', 'password-changed'],
  data() {
    return {
      busy: false,
      activeTab: 'users',
      users: [],
      currentUser: null,
      userKeyword: '',
      loginAudits: [],
      loginAuditKeyword: '',
      loginAuditAction: '',
      loginAuditResult: '',
      loginAuditPage: 1,
      loginAuditPageSize: 20,
      loginAuditTotal: 0,
      roles: [],
      permissions: [],
      permissionKeyword: '',
      permissionPage: 1,
      permissionPageSize: 10,
      selectedManagedUser: null,
      userPasswordDialogVisible: false,
      passwordSaving: false,
      passwordForm: { currentPassword: '', newPassword: '', confirmPassword: '' },
      selectedRole: null,
      authorizationDialogVisible: false,
      authorizationLoading: false,
      authorizationSaving: false,
      authorizationAssets: [],
      selectedAssetKeys: [],
      originalAssetKeys: [],
      assetTypeFilter: 'all',
      assetGroupFilter: '',
      assetKeyword: ''
    };
  },
  computed: {
    filteredUsers() {
      const keyword = this.userKeyword.toLowerCase();
      if (!keyword) return this.users;
      return this.users.filter(user => [
        user.id,
        user.username,
        user.displayName,
        user.tenantId,
        user.sourceLabel,
        user.status,
        ...(user.roleIds || [])
      ].some(value => String(value || '').toLowerCase().includes(keyword)));
    },
    userPasswordDialogTitle() {
      const username = this.selectedManagedUser?.username || this.selectedManagedUser?.displayName || '';
      return username ? `修改用户密码 - ${username}` : '修改用户密码';
    },
    selectedRoleTitle() {
      if (!this.selectedRole) return '从上方角色列表点击“管理授权”后查看。';
      if (this.isSuperAdmin(this.selectedRole)) return 'SUPER_ADMIN 默认拥有全部资产访问权限。';
      const roleName = this.selectedRole.roleName || this.selectedRole.roleCode || this.selectedRole.id;
      return `${roleName} 已授权 ${this.permissions.length} 条权限。`;
    },
    filteredPermissions() {
      const keyword = this.permissionKeyword.toLowerCase();
      if (!keyword) return this.permissions;
      return this.permissions.filter(permission => [
        permission.id,
        permission.localToolName,
        permission.toolId,
        permission.scopeExpression,
        permission.targetId,
        permission.tenantId,
        permission.effect,
        permission.remark
      ].some(value => String(value || '').toLowerCase().includes(keyword)));
    },
    pagedPermissions() {
      const start = (this.permissionPage - 1) * this.permissionPageSize;
      return this.filteredPermissions.slice(start, start + this.permissionPageSize);
    },
    loginAuditRows() {
      return this.loginAudits.map(item => {
        const detail = this.parseJson(item.detail);
        return {
          ...item,
          detailData: detail,
          createdAtText: this.formatTime(item.createdAt),
          actionLabel: this.loginAuditActionLabel(item.actionName),
          resultLabel: this.loginAuditResultLabel(item.result),
          reason: detail.reason || '',
          userAgentText: item.userAgent || detail.userAgent || '-'
        };
      });
    },
    loginAuditPageCount() {
      return Math.max(1, Math.ceil(this.loginAuditTotal / Math.max(1, this.loginAuditPageSize)));
    },
    loginAuditPageStart() {
      return this.loginAuditTotal === 0 ? 0 : (this.loginAuditPage - 1) * this.loginAuditPageSize + 1;
    },
    loginAuditPageEnd() {
      return Math.min(this.loginAuditTotal, this.loginAuditPage * this.loginAuditPageSize);
    },
    authorizationDialogTitle() {
      const roleName = this.selectedRole?.roleName || this.selectedRole?.roleCode || this.selectedRole?.id || '';
      return roleName ? `管理授权 - ${roleName}` : '管理授权';
    },
    assetGroups() {
      const groups = new Map();
      this.authorizationAssets
        .filter(asset => this.assetTypeFilter === 'all' || asset.type === this.assetTypeFilter)
        .forEach(asset => {
          const value = asset.groupCode || asset.groupName || 'default';
          const label = asset.groupName || asset.groupCode || 'default';
          groups.set(value, { value, label });
        });
      return Array.from(groups.values()).sort((a, b) => a.label.localeCompare(b.label));
    },
    filteredAuthorizationAssets() {
      const keyword = this.assetKeyword.toLowerCase();
      return this.authorizationAssets.filter(asset => {
        if (this.assetTypeFilter !== 'all' && asset.type !== this.assetTypeFilter) return false;
        if (this.assetGroupFilter && asset.groupCode !== this.assetGroupFilter && asset.groupName !== this.assetGroupFilter) return false;
        if (!keyword) return true;
        return [
          asset.toolName,
          asset.title,
          asset.description,
          asset.groupCode,
          asset.groupName,
          asset.typeLabel
        ].some(value => String(value || '').toLowerCase().includes(keyword));
      });
    }
  },
  mounted() {
    this.loadUsers();
    this.loadRoles();
    this.loadLoginAudits(false);
  },
  methods: {
    async loadUsers() {
      await this.run(async () => {
        const [currentUser, remoteUsers] = await Promise.all([
          authApi.currentUser(),
          authorizationApi.users()
        ]);
        this.currentUser = currentUser || {};
        this.users = [
          this.localAdminUser(currentUser || {}),
          ...(remoteUsers || []).map(this.toRemoteUser)
        ].filter(Boolean);
      }, '用户列表已刷新', false);
    },
    localAdminUser(currentUser) {
      const username = currentUser.username || 'admin';
      return {
        id: 'mcp-admin',
        username,
        displayName: username,
        tenantId: '',
        roleIds: ['MCP_ADMIN'],
        status: currentUser.authenticated === false ? 'offline' : 'enabled',
        source: 'mcp',
        sourceLabel: 'MCP 本地'
      };
    },
    toRemoteUser(user) {
      return {
        id: user.id,
        username: user.username,
        displayName: user.displayName || user.username,
        tenantId: user.tenantId,
        roleIds: user.roleIds || [],
        status: user.status || 'enabled',
        source: 'remote',
        sourceLabel: '远端'
      };
    },
    canChangeUserPassword(user) {
      return user?.source === 'mcp';
    },
    openUserPasswordDialog(user) {
      this.selectedManagedUser = user;
      this.passwordForm = { currentPassword: '', newPassword: '', confirmPassword: '' };
      this.userPasswordDialogVisible = true;
    },
    async saveUserPassword() {
      if (!this.selectedManagedUser) return;
      if (!this.passwordForm.newPassword || this.passwordForm.newPassword !== this.passwordForm.confirmPassword) {
        this.$emit('error', new Error('两次输入的新密码不一致'));
        return;
      }
      this.passwordSaving = true;
      try {
        await authApi.changeManagedPassword({
          currentPassword: this.passwordForm.currentPassword,
          newPassword: this.passwordForm.newPassword
        });
        this.userPasswordDialogVisible = false;
        this.$emit('notify', { title: '用户密码已修改' });
        this.$emit('password-changed');
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.passwordSaving = false;
      }
    },
    async loadSnapshot() {
      await this.run(async () => {
        await authorizationApi.snapshot();
      }, '授权已刷新', false);
    },
    async syncSnapshot() {
      await this.run(async () => {
        await authorizationApi.sync();
        this.roles = await authorizationApi.roles() || [];
      }, '授权已同步');
    },
    async loadRoles() {
      await this.run(async () => {
        this.roles = await authorizationApi.roles() || [];
      }, '本地角色已刷新', false);
    },
    async loadLoginAudits(toast = false) {
      await this.run(async () => {
        const page = await authApi.loginAudits({
          page: this.loginAuditPage,
          pageSize: this.loginAuditPageSize,
          actionName: this.loginAuditAction,
          result: this.loginAuditResult,
          keyword: this.loginAuditKeyword
        });
        this.loginAudits = Array.isArray(page?.items) ? page.items : [];
        this.loginAuditTotal = Number(page?.total || 0);
        this.loginAuditPage = Number(page?.page || this.loginAuditPage);
        this.loginAuditPageSize = Number(page?.pageSize || this.loginAuditPageSize);
      }, '登录审计已刷新', toast);
    },
    searchLoginAudits() {
      this.loginAuditPage = 1;
      return this.loadLoginAudits(true);
    },
    changeLoginAuditPage(page) {
      this.loginAuditPage = Math.min(Math.max(1, page), this.loginAuditPageCount);
      return this.loadLoginAudits(false);
    },
    changeLoginAuditPageSize(size) {
      this.loginAuditPageSize = Number(size) || 20;
      this.loginAuditPage = 1;
      return this.loadLoginAudits(false);
    },
    loginAuditActionLabel(actionName) {
      const labels = {
        'admin-login': '管理员登录'
      };
      return labels[actionName] || actionName || '登录';
    },
    loginAuditResultLabel(result) {
      const labels = {
        success: '成功',
        failure: '失败'
      };
      return labels[result] || result || '-';
    },
    formatTime(value) {
      if (!value) return '-';
      const timestamp = typeof value === 'number' ? value : Date.parse(value);
      if (!Number.isFinite(timestamp)) return String(value);
      return new Date(timestamp).toLocaleString();
    },
    parseJson(value) {
      if (!value || typeof value !== 'string') return {};
      try {
        const parsed = JSON.parse(value);
        return parsed && typeof parsed === 'object' ? parsed : {};
      } catch (error) {
        return {};
      }
    },
    async openAuthorizationDialog(role) {
      this.selectedRole = role;
      this.permissions = [];
      this.permissionKeyword = '';
      this.permissionPage = 1;
      if (this.isSuperAdmin(role)) {
        this.$emit('notify', { title: 'SUPER_ADMIN 默认拥有全部访问权限' });
        return;
      }
      this.authorizationDialogVisible = true;
      this.authorizationLoading = true;
      this.assetTypeFilter = 'all';
      this.assetGroupFilter = '';
      this.assetKeyword = '';
      try {
        const [assets, permissions] = await Promise.all([
          this.fetchAuthorizationAssets(),
          authorizationApi.rolePermissions(role.id, role.tenantId)
        ]);
        this.authorizationAssets = assets;
        this.permissions = permissions || [];
        const assetKeys = new Set(assets.map(asset => asset.toolName));
        const selected = this.permissions
          .map(permission => permission.localToolName || permission.toolId)
          .filter(toolName => toolName && assetKeys.has(toolName));
        this.selectedAssetKeys = Array.from(new Set(selected));
        this.originalAssetKeys = [...this.selectedAssetKeys];
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.authorizationLoading = false;
      }
    },
    async fetchAuthorizationAssets() {
      const [databaseQueries, apiServices, httpAssets, sshHosts, sqlDatasources] = await Promise.all([
        databaseApi.list(),
        apiServicesApi.list(),
        assetsApi.listHttp(),
        assetsApi.listSsh(),
        assetsApi.listSql()
      ]);
      return [
        ...(databaseQueries || []).map(item => this.toAuthorizationAsset(item, 'database_query', '数据查询')),
        ...(apiServices || []).map(item => this.toAuthorizationAsset(item, 'api_service', 'API 服务')),
        ...(httpAssets || []).map(item => this.toAuthorizationAsset(item, 'http_endpoint', 'HTTP 资产', {
          groupCode: item.category || item.environment,
          groupName: item.category || item.environment,
          scopeCapability: 'execute',
          scopeAction: 'request'
        })),
        ...(sshHosts || []).map(item => this.toAuthorizationAsset(item, 'ssh_host', '主机资产', {
          groupCode: item.environment,
          groupName: item.environment,
          scopeCapability: 'execute',
          scopeAction: 'command'
        })),
        ...(sqlDatasources || []).map(item => this.toAuthorizationAsset(item, 'sql_datasource', 'SQL 数据源', {
          title: item.title || item.name || item.toolName,
          groupCode: item.databaseType || item.environment,
          groupName: item.databaseType || item.environment,
          scopeCapability: 'execute',
          scopeAction: 'query'
        }))
      ]
        .filter(asset => asset.toolName)
        .sort((a, b) => `${a.typeLabel}${a.title}`.localeCompare(`${b.typeLabel}${b.title}`));
    },
    toAuthorizationAsset(item, type, typeLabel, options = {}) {
      const domain = item.id || item.toolName;
      return {
        id: item.id,
        type,
        typeLabel,
        toolName: item.toolName,
        title: options.title || item.title || item.name || item.toolName,
        description: item.description || '',
        groupCode: options.groupCode || item.businessGroup || 'default',
        groupName: options.groupName || item.businessGroupName || item.businessGroup || 'default',
        enabled: item.enabled !== false,
        scopeExpression: options.scopeCapability && options.scopeAction
          ? this.assetScopeExpression(type, options.scopeCapability, options.scopeAction, domain)
          : ''
      };
    },
    assetScopeExpression(assetType, capability, action, domain) {
      const tenant = this.selectedRole?.tenantId;
      const attrs = [];
      if (tenant) attrs.push(`tenant=${tenant}`);
      if (domain) attrs.push(`domain=${domain}`);
      attrs.push('level=read');
      return `mcp:${assetType}:${capability}:${action}@${attrs.join(';')}`;
    },
    async loadRolePermissions() {
      if (!this.selectedRole || this.isSuperAdmin(this.selectedRole)) return;
      await this.run(async () => {
        this.permissions = await authorizationApi.rolePermissions(this.selectedRole.id, this.selectedRole.tenantId) || [];
        this.permissionPage = 1;
      }, '角色权限已加载', false);
    },
    resetPermissionPage() {
      this.permissionPage = 1;
    },
    changePermissionPage(page) {
      this.permissionPage = page;
    },
    changePermissionPageSize(size) {
      this.permissionPageSize = size;
      this.permissionPage = 1;
    },
    isSuperAdmin(role) {
      return String(role?.roleCode || '').toUpperCase() === 'SUPER_ADMIN';
    },
    isAssetSelected(asset) {
      return this.selectedAssetKeys.includes(asset.toolName);
    },
    toggleAsset(asset, checked) {
      const values = new Set(this.selectedAssetKeys);
      if (checked) {
        values.add(asset.toolName);
      } else {
        values.delete(asset.toolName);
      }
      this.selectedAssetKeys = Array.from(values);
    },
    selectFilteredAssets() {
      const values = new Set(this.selectedAssetKeys);
      this.filteredAuthorizationAssets
        .filter(asset => asset.enabled)
        .forEach(asset => values.add(asset.toolName));
      this.selectedAssetKeys = Array.from(values);
    },
    clearFilteredAssets() {
      const filtered = new Set(this.filteredAuthorizationAssets.map(asset => asset.toolName));
      this.selectedAssetKeys = this.selectedAssetKeys.filter(toolName => !filtered.has(toolName));
    },
    async saveAssetAuthorizations() {
      if (!this.selectedRole || this.isSuperAdmin(this.selectedRole)) return;
      this.authorizationSaving = true;
      try {
        const selected = new Set(this.selectedAssetKeys);
        const original = new Set(this.originalAssetKeys);
        const managedTools = new Set(this.authorizationAssets.map(asset => asset.toolName));
        const assetByToolName = new Map(this.authorizationAssets.map(asset => [asset.toolName, asset]));
        const permissionByToolName = new Map();
        this.permissions.forEach(permission => {
          const toolName = permission.localToolName || permission.toolId;
          if (toolName && managedTools.has(toolName)) {
            permissionByToolName.set(toolName, permission);
          }
        });

        const toCreate = [...selected].filter(toolName => !original.has(toolName));
        const toDelete = [...original]
          .filter(toolName => !selected.has(toolName))
          .map(toolName => permissionByToolName.get(toolName))
          .filter(permission => permission && permission.id);

        for (const toolName of toCreate) {
          const asset = assetByToolName.get(toolName);
          await authorizationApi.createRolePermission({
            roleId: this.selectedRole.id,
            tenantId: this.selectedRole.tenantId,
            localToolName: toolName,
            scopeExpression: asset?.scopeExpression || '',
            effect: 'allow',
            enabled: true,
            remark: 'MCP admin asset authorization'
          });
        }
        for (const permission of toDelete) {
          await authorizationApi.deleteRolePermission(permission.id);
        }

        this.permissions = await authorizationApi.rolePermissions(this.selectedRole.id, this.selectedRole.tenantId) || [];
        this.originalAssetKeys = [...this.selectedAssetKeys];
        this.permissionPage = 1;
        this.authorizationDialogVisible = false;
        this.$emit('notify', { title: '角色资产授权已保存' });
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.authorizationSaving = false;
      }
    },
    async removeRolePermission(permission) {
      const confirmed = await this.confirm('确定删除该角色权限吗？');
      if (!confirmed) return;
      await this.run(() => authorizationApi.deleteRolePermission(permission.id), '角色权限已删除');
      await this.loadRolePermissions();
    },
    async confirm(message) {
      try {
        await ElMessageBox.confirm(message, '确认操作', {
          type: 'warning',
          confirmButtonText: '确认',
          cancelButtonText: '取消'
        });
        return true;
      } catch (error) {
        return false;
      }
    },
    async run(action, title, toast = true) {
      this.busy = true;
      try {
        await action();
        if (toast) this.$emit('notify', { title });
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.busy = false;
      }
    }
  }
};
