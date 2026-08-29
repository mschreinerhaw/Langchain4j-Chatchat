<template>
  <section class="feature-view system-management-view">
    <header class="system-header">
      <div>
        <p>系统管理</p>
        <h1>用户与角色管理</h1>
      </div>
    </header>

    <div v-if="message" class="system-message" :class="{ error: !!error }">
      {{ message }}
    </div>

    <div class="system-metrics">
      <article v-for="metric in metrics" :key="metric.label">
        <strong>{{ metric.value }}</strong>
        <span>{{ metric.label }}</span>
      </article>
    </div>

    <nav class="system-tabs" aria-label="系统管理模块">
      <button
        type="button"
        :class="{ active: activeManagementTab === 'users' }"
        @click="activeManagementTab = 'users'"
      >
        <Users :size="16" />
        <span>用户管理</span>
      </button>
      <button
        type="button"
        :class="{ active: activeManagementTab === 'roles' }"
        @click="activeManagementTab = 'roles'"
      >
        <ShieldCheck :size="16" />
        <span>角色管理</span>
      </button>
      <button
        type="button"
        :class="{ active: activeManagementTab === 'logins' }"
        @click="activeManagementTab = 'logins'"
      >
        <KeyRound :size="16" />
        <span>登录审计</span>
      </button>
    </nav>

    <div class="rbac-board">
      <aside v-if="activeManagementTab === 'users'" class="rbac-panel user-panel system-tab-panel">
        <div class="panel-head">
          <div>
            <p>用户</p>
            <h2>用户档案</h2>
          </div>
          <div class="mini-actions">
            <button type="button" title="同步组织" @click="syncOrgs" :disabled="loading">
              <RefreshCw :size="14" />
              组织
            </button>
            <button type="button" title="同步用户" @click="syncUsers" :disabled="loading">
              <RefreshCw :size="14" />
              用户
            </button>
            <button type="button" title="修改 admin 密码" @click="openAdminPasswordModal">
              <KeyRound :size="14" />
              admin 密码
            </button>
            <button type="button" @click="openUserModal()">
              <Plus :size="14" />
              新增账户
            </button>
          </div>
        </div>

        <div class="entity-table">
          <div class="entity-table-head user-table-row">
            <span>用户信息</span>
            <span>组织</span>
            <span>角色</span>
            <span>状态</span>
            <span>操作</span>
          </div>
          <div
            v-for="user in users"
            :key="user.id"
            class="entity-table-row user-table-row"
            :class="{ 'admin-user-row': isAdminUser(user) }"
          >
            <span>
              <strong>{{ user.displayName }}</strong>
              <small>{{ user.username }}</small>
            </span>
            <span>{{ orgName(user.orgId) }}</span>
            <span>
              <small>{{ roleNamesForUser(user).join("、") || "未分配" }}</small>
            </span>
            <span>
              <em :class="['status-pill', user.status]">{{ statusLabel(user.status) }}</em>
            </span>
            <span class="entity-row-actions">
              <button
                type="button"
                class="ghost-button compact-button"
                title="管理 Agent API 令牌"
                :disabled="user.status !== 'enabled'"
                @click="openApiTokenModal(user)"
              >
                token
              </button>
              <button type="button" class="icon-button" title="编辑账户" @click="openUserModal(user)">
                <Pencil :size="15" />
              </button>
              <button
                v-if="!isProtectedUser(user)"
                type="button"
                class="icon-button"
                title="删除账户"
                @click="removeUser(user)"
              >
                <Trash2 :size="15" />
              </button>
            </span>
          </div>
          <div v-if="users.length === 0" class="empty-state">暂无用户</div>
        </div>
      </aside>

      <aside v-else-if="activeManagementTab === 'roles'" class="rbac-panel role-panel system-tab-panel">
        <div class="panel-head">
          <div>
            <p>角色</p>
            <h2>角色档案</h2>
          </div>
          <div class="mini-actions">
            <button type="button" title="同步组织" @click="syncOrgs" :disabled="loading">
              <RefreshCw :size="14" />
              组织
            </button>
            <button type="button" @click="openOrgModal()">
              <Plus :size="14" />
              新增组织
            </button>
            <button type="button" @click="openRoleModal()">
              <Plus :size="14" />
              新增角色
            </button>
          </div>
        </div>

        <div class="role-record-table">
          <div class="role-record-head">
            <span>角色名称</span>
            <span>角色编码</span>
            <span>类型</span>
            <span>状态</span>
            <span>已配权限</span>
            <span>绑定Agent</span>
            <span>绑定用户</span>
            <span>操作</span>
          </div>
          <div
            v-for="role in roles"
            :key="role.id"
            class="role-record"
            :class="{ active: role.id === selectedRoleId }"
            @click="selectRole(role)"
          >
            <span>
              <strong>{{ role.roleName }}</strong>
              <small>{{ role.description || "无备注" }}</small>
            </span>
            <span>{{ role.roleCode }}</span>
            <span>{{ roleTypeLabel(role.roleType) }}</span>
            <span>
              <em :class="['status-pill', role.status]">{{ statusLabel(role.status) }}</em>
            </span>
            <span>{{ role.id === selectedRoleId ? selectedPermissionIds.length : "-" }}</span>
            <span>{{ role.id === selectedRoleId ? selectedAgentIds.length : "-" }}</span>
            <span>{{ role.id === selectedRoleId ? selectedUserIds.length : "-" }}</span>
            <span class="entity-row-actions">
              <button type="button" class="icon-button" title="编辑角色" @click.stop="openRoleModal(role)">
                <Pencil :size="15" />
              </button>
              <button type="button" class="icon-button" title="删除角色" @click.stop="removeRole(role)">
                <Trash2 :size="15" />
              </button>
            </span>
          </div>
        </div>
      </aside>

      <aside v-else class="rbac-panel login-audit-panel system-tab-panel">
        <div class="panel-head">
          <div>
            <p>审计</p>
            <h2>登录行为</h2>
          </div>
          <div class="mini-actions">
            <button type="button" title="刷新登录审计" @click="searchLoginAuditLogs" :disabled="loading">
              <RefreshCw :size="14" />
              刷新
            </button>
          </div>
        </div>

        <div class="login-audit-toolbar">
          <input
            v-model.trim="loginAuditKeyword"
            type="search"
            placeholder="搜索用户、IP、MAC、终端、失败原因"
            @keydown.enter="searchLoginAuditLogs"
          />
          <select v-model="loginAuditTenantId" @change="searchLoginAuditLogs">
            <option value="">全部租户</option>
            <option v-for="tenant in tenants" :key="tenant.id" :value="tenant.id">
              {{ tenant.tenantName || tenant.tenantCode || tenant.id }}
            </option>
          </select>
          <select v-model="loginAuditAction" @change="searchLoginAuditLogs">
            <option value="">全部方式</option>
            <option value="login">密码登录</option>
            <option value="embed-login">历史嵌入登录</option>
            <option value="agent-api-token-authenticate">Agent API 认证</option>
          </select>
          <select v-model="loginAuditResult" @change="searchLoginAuditLogs">
            <option value="">全部结果</option>
            <option value="success">成功</option>
            <option value="failure">失败</option>
          </select>
          <button type="button" class="ghost-button compact-button" @click="searchLoginAuditLogs" :disabled="loading">查询</button>
        </div>

        <div class="login-audit-table">
          <div class="login-audit-head">
            <span>时间</span>
            <span>用户</span>
            <span>租户</span>
            <span>方式</span>
            <span>结果</span>
            <span>IP</span>
            <span>MAC</span>
            <span>终端</span>
          </div>
          <div v-for="audit in loginAuditRows" :key="audit.id" class="login-audit-row">
            <span :title="audit.loginTime">{{ audit.loginTime }}</span>
            <span
              class="login-audit-detail-cell"
              :title="[audit.loginUser, audit.detailData.displayName || audit.actorId].filter(Boolean).join('\n')"
            >
              <strong>{{ audit.loginUser }}</strong>
              <small>{{ audit.detailData.displayName || audit.actorId || "未识别账户" }}</small>
            </span>
            <span class="login-audit-detail-cell" :title="audit.tenantName">{{ audit.tenantName }}</span>
            <span class="login-audit-detail-cell" :title="audit.loginAction">{{ audit.loginAction }}</span>
            <span>
              <em :class="['status-pill', audit.result]">{{ audit.loginResult }}</em>
            </span>
            <span class="login-audit-detail-cell" :title="audit.ipAddress">{{ audit.ipAddress }}</span>
            <span class="login-audit-detail-cell" :title="audit.macAddress">{{ audit.macAddress }}</span>
            <span
              class="login-audit-detail-cell"
              :title="[audit.failureReason, audit.userAgent].filter(Boolean).join('\n')"
            >
              <small>{{ audit.userAgent }}</small>
            </span>
          </div>
          <div v-if="loginAuditRows.length === 0" class="empty-state">暂无登录审计</div>
        </div>

        <nav class="login-audit-pagination" aria-label="登录审计分页">
          <span>显示 {{ loginAuditPageStart }}-{{ loginAuditPageEnd }} 条，共 {{ loginAuditTotal }} 条</span>
          <select :value="loginAuditPageSize" @change="changeLoginAuditPageSize">
            <option :value="10">10 条/页</option>
            <option :value="20">20 条/页</option>
            <option :value="50">50 条/页</option>
            <option :value="100">100 条/页</option>
          </select>
          <button type="button" :disabled="loginAuditPage <= 1 || loading" @click="changeLoginAuditPage(loginAuditPage - 1)">上一页</button>
          <button
            v-for="pageNumber in loginAuditPageButtons"
            :key="pageNumber"
            type="button"
            :class="{ active: pageNumber === loginAuditPage }"
            :disabled="loading"
            @click="changeLoginAuditPage(pageNumber)"
          >
            {{ pageNumber }}
          </button>
          <button type="button" :disabled="loginAuditPage >= loginAuditPageCount || loading" @click="changeLoginAuditPage(loginAuditPage + 1)">下一页</button>
        </nav>
      </aside>

    </div>

    <Teleport to="body">
      <div v-if="orgModalOpen" class="permission-modal-backdrop">
        <form class="entity-modal" @submit.prevent="saveOrgForm">
          <div class="modal-head">
            <div>
              <p>组织档案</p>
              <h2>{{ orgForm.id ? "编辑组织" : "新增组织" }}</h2>
            </div>
            <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" @click="closeOrgModal">
              <X :size="18" />
            </button>
          </div>

          <div class="entity-form modal-entity-form">
            <label>
              <span>组织名称</span>
              <input v-model.trim="orgForm.orgName" type="text" />
            </label>
            <label>
              <span>组织编码</span>
              <input v-model.trim="orgForm.orgCode" type="text" />
            </label>
            <label>
              <span>上级组织</span>
              <select v-model="orgForm.parentId">
                <option value="">无上级组织</option>
                <option
                  v-for="org in orgTree"
                  :key="org.id"
                  :value="org.id"
                  :disabled="org.id === orgForm.id"
                >
                  {{ `${"　".repeat(org.level)}${org.orgName}` }}
                </option>
              </select>
            </label>
            <label>
              <span>排序</span>
              <input v-model.number="orgForm.sortOrder" type="number" min="0" />
            </label>
            <label>
              <span>状态</span>
              <select v-model="orgForm.status">
                <option value="enabled">启用</option>
                <option value="disabled">停用</option>
              </select>
            </label>
          </div>

          <div class="modal-actions">
            <button type="button" class="ghost-button" @click="closeOrgModal">取消</button>
            <button type="submit" class="primary-button" :disabled="savingOrg">
              <Save :size="16" />
              <span>{{ orgForm.id ? "保存组织" : "新增组织" }}</span>
            </button>
          </div>
        </form>
      </div>

      <div v-if="userModalOpen" class="permission-modal-backdrop">
        <form class="entity-modal" @submit.prevent="saveUserForm">
          <div class="modal-head">
            <div>
              <p>账户档案</p>
              <h2>{{ userForm.id ? "编辑账户" : "新增账户" }}</h2>
            </div>
            <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" @click="closeUserModal">
              <X :size="18" />
            </button>
          </div>

          <div class="entity-form modal-entity-form">
            <label>
              <span>账号</span>
              <input v-model.trim="userForm.username" type="text" :disabled="!!userForm.id" />
            </label>
            <label>
              <span>姓名</span>
              <input v-model.trim="userForm.displayName" type="text" />
            </label>
            <label>
              <span>密码</span>
              <input v-model="userForm.password" type="password" :placeholder="userForm.id ? '留空不修改' : '默认 123456'" />
            </label>
            <label>
              <span>所属组织</span>
              <select v-model="userForm.orgId">
                <option value="">未分配组织</option>
                <option v-for="org in orgTree" :key="org.id" :value="org.id">
                  {{ `${"　".repeat(org.level)}${org.orgName}` }}
                </option>
              </select>
            </label>
            <label>
              <span>邮箱</span>
              <input v-model.trim="userForm.email" type="email" />
            </label>
            <label>
              <span>电话</span>
              <input v-model.trim="userForm.phone" type="tel" />
            </label>
            <label>
              <span>状态</span>
              <select v-model="userForm.status">
                <option value="enabled">启用</option>
                <option value="disabled">停用</option>
                <option value="locked">锁定</option>
              </select>
            </label>
            <label class="full-span">
              <span>角色</span>
              <select :value="userForm.roleIds[0] || ''" @change="setUserRole($event.target.value)">
                <option value="">未分配角色</option>
                <option v-for="role in roles" :key="role.id" :value="role.id">
                  {{ role.roleName }}
                </option>
              </select>
            </label>
          </div>

          <div class="modal-actions">
            <button type="button" class="ghost-button" @click="closeUserModal">取消</button>
            <button type="submit" class="primary-button" :disabled="savingUser">
              <Save :size="16" />
              <span>{{ userForm.id ? "保存账户" : "新增账户" }}</span>
            </button>
          </div>
        </form>
      </div>

      <div v-if="adminPasswordModalOpen" class="permission-modal-backdrop">
        <form class="entity-modal" @submit.prevent="saveAdminPasswordForm">
          <div class="modal-head">
            <div>
              <p>安全设置</p>
              <h2>修改 admin 密码</h2>
            </div>
            <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" @click="closeAdminPasswordModal">
              <X :size="18" />
            </button>
          </div>

          <div class="entity-form modal-entity-form">
            <label class="full-span">
              <span>当前密码</span>
              <input v-model="adminPasswordForm.currentPassword" type="password" autocomplete="current-password" />
            </label>
            <label>
              <span>新密码</span>
              <input v-model="adminPasswordForm.newPassword" type="password" autocomplete="new-password" />
            </label>
            <label>
              <span>确认新密码</span>
              <input v-model="adminPasswordForm.confirmPassword" type="password" autocomplete="new-password" />
            </label>
          </div>

          <div class="modal-actions">
            <button type="button" class="ghost-button" @click="closeAdminPasswordModal">取消</button>
            <button type="submit" class="primary-button" :disabled="savingAdminPassword">
              <Save :size="16" />
              <span>保存新密码</span>
            </button>
          </div>
        </form>
      </div>

      <div v-if="apiTokenModalOpen" class="permission-modal-backdrop">
        <div class="api-token-modal">
          <div class="modal-head api-token-modal-head">
            <div class="api-token-heading">
              <span class="api-token-heading-icon"><KeyRound :size="19" /></span>
              <div>
                <p>访问凭证</p>
                <h2>Agent API 令牌</h2>
                <span>为应用和自动化服务创建安全访问凭证</span>
              </div>
            </div>
            <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" @click="closeApiTokenModal">
              <X :size="18" />
            </button>
          </div>

          <div class="api-token-owner">
            <span class="api-token-owner-avatar">{{ (apiTokenTargetUser?.displayName || apiTokenTargetUser?.username || "U").slice(0, 1) }}</span>
            <span>
              <strong>{{ apiTokenTargetUser?.displayName }}</strong>
              <small>{{ apiTokenTargetUser?.username }} · 继承当前角色与 Agent 权限</small>
            </span>
            <em>权限实时同步</em>
          </div>

          <div class="api-token-create-card">
            <div class="api-token-section-title">
              <span><strong>创建新令牌</strong><small>令牌生成后可立即用于已授权的 Agent API</small></span>
            </div>
            <div class="api-token-create-form">
              <label>
                <span>令牌名称</span>
                <input v-model.trim="apiTokenName" type="text" maxlength="128" placeholder="例如：生产环境数据助手" />
              </label>
              <label>
                <span>有效期</span>
                <select v-model.number="apiTokenDuration">
                  <option v-for="option in apiTokenDurations" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </option>
                </select>
              </label>
              <button type="button" class="primary-button api-token-create-button" :disabled="apiTokenSaving || !apiTokenName" @click="createApiToken">
                <Plus :size="16" />
                <span>{{ apiTokenSaving ? "处理中..." : "创建令牌" }}</span>
              </button>
            </div>
          </div>

          <div v-if="apiTokenLatestSecret" class="api-token-secret-card">
            <span class="api-token-secret-icon"><ShieldCheck :size="20" /></span>
            <span class="api-token-secret-content">
              <strong>令牌已创建，请立即保存</strong>
              <small>出于安全考虑，关闭窗口后将无法再次查看完整令牌。</small>
              <code>{{ apiTokenLatestSecret }}</code>
            </span>
            <button type="button" class="primary-button api-token-copy-button" @click="copyApiTokenSecret(apiTokenLatestSecret)">
              <Copy :size="14" />
              复制
            </button>
          </div>

          <div class="api-token-list-section">
            <div class="api-token-section-title api-token-list-title">
              <span><strong>已创建的令牌</strong><small>管理有效期、使用情况和访问状态</small></span>
              <em>{{ apiTokens.length }} 个</em>
            </div>
            <div class="api-token-table">
            <div class="api-token-head">
              <span>名称 / Token</span>
              <span>状态</span>
              <span>有效期</span>
              <span>最后使用</span>
              <span>次数</span>
              <span>操作</span>
            </div>
            <div v-for="token in apiTokens" :key="token.id" class="api-token-row">
              <span class="api-token-name"><strong>{{ token.tokenName }}</strong><small>{{ token.tokenPreview }}</small></span>
              <em :class="['status-pill', isApiTokenInactive(token) ? 'disabled' : '']">
                {{ apiTokenStatusLabel(token) }}
              </em>
              <span>{{ apiTokenExpiryLabel(token) }}</span>
              <span>{{ token.lastUsedAt ? formatDateTime(token.lastUsedAt) : "-" }}</span>
              <span>{{ token.usedCount || 0 }}</span>
              <span class="entity-row-actions">
                <button
                  type="button"
                  class="api-token-action"
                  title="重置令牌"
                  :disabled="apiTokenSaving"
                  @click="resetApiToken(token)"
                >
                  <RotateCcw :size="15" />
                  <span>重置</span>
                </button>
                <button
                  type="button"
                  class="api-token-action danger"
                  title="吊销令牌"
                  :disabled="isApiTokenInactive(token) || apiTokenSaving"
                  @click="revokeApiToken(token)"
                >
                  <Trash2 :size="15" />
                  <span>吊销</span>
                </button>
              </span>
            </div>
            <div v-if="!apiTokenLoading && apiTokens.length === 0" class="empty-state">
              <KeyRound :size="22" />
              <strong>还没有 API 令牌</strong>
              <span>创建后即可安全调用已授权的 Agent</span>
            </div>
            <div v-if="apiTokenLoading" class="empty-state">加载中...</div>
            </div>
          </div>
        </div>
      </div>

      <div
        v-if="apiTokenCopyDialogOpen"
        class="api-token-copy-backdrop"
        role="presentation"
        @mousedown.self="closeApiTokenCopyDialog"
      >
        <section
          class="api-token-copy-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="api-token-copy-title"
          @keydown.esc="closeApiTokenCopyDialog"
        >
          <header>
            <span class="api-token-copy-dialog-icon"><Copy :size="19" /></span>
            <span>
              <small>安全复制</small>
              <h3 id="api-token-copy-title">复制 Agent API 令牌</h3>
            </span>
            <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" @click="closeApiTokenCopyDialog">
              <X :size="17" />
            </button>
          </header>
          <div class="api-token-copy-dialog-body">
            <p>浏览器未授予自动复制权限。令牌已为你选中，可以点击下方按钮或按 <kbd>Ctrl</kbd> + <kbd>C</kbd> 复制。</p>
            <label>
              <span>完整令牌</span>
              <input
                ref="apiTokenCopyInput"
                :value="apiTokenCopySecret"
                type="text"
                readonly
                spellcheck="false"
                autocomplete="off"
                @click="$event.target.select()"
              />
            </label>
            <div class="api-token-copy-security-note">
              <ShieldCheck :size="16" />
              <span>请妥善保存，关闭令牌管理窗口后将无法再次查看完整内容。</span>
            </div>
          </div>
          <footer>
            <button type="button" class="secondary-button" @click="closeApiTokenCopyDialog">取消</button>
            <button type="button" class="primary-button" @click="copyApiTokenFromDialog">
              <Copy :size="15" />
              复制令牌
            </button>
          </footer>
        </section>
      </div>

      <div v-if="roleModalOpen" class="permission-modal-backdrop">
        <form class="role-modal" @submit.prevent="saveRoleForm">
          <div class="modal-head">
            <div>
              <p>角色权限配置</p>
              <h2>{{ roleForm.id ? "修改角色" : "新建角色" }}</h2>
            </div>
            <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" @click="closeRoleModal">
              <X :size="18" />
            </button>
          </div>

          <div class="role-config-body">
            <section class="role-config-panel permission-config-panel">
              <div class="manage-list-head">
                <strong>功能权限</strong>
                <div class="mini-actions">
                  <button type="button" @click="selectAllPermissions">全选</button>
                  <button type="button" @click="clearPermissions">清空</button>
                </div>
              </div>
              <div class="permission-tree auth-check-list">
                <details
                  v-for="group in permissionGroups"
                  :key="group.root.id"
                  class="permission-category"
                  open
                >
                  <summary class="permission-category-head">
                    <input
                      type="checkbox"
                      :checked="group.selectedCount === group.totalCount"
                      :indeterminate.prop="group.selectedCount > 0 && group.selectedCount < group.totalCount"
                      @click.stop
                      @change="togglePermissionGroup(group, $event.target.checked)"
                    />
                    <span class="permission-category-title">
                      <strong>{{ group.root.permissionName }}</strong>
                      <small>{{ group.root.permissionCode }}</small>
                    </span>
                    <em class="permission-category-type">{{ typeLabel(group.root.permissionType) }}</em>
                    <span class="permission-category-count">{{ group.selectedCount }} / {{ group.totalCount }}</span>
                  </summary>
                  <div v-if="group.children.length" class="permission-category-items">
                    <label
                      v-for="permission in group.children"
                      :key="permission.id"
                      class="permission-item"
                      :style="{ '--level': permission.displayLevel }"
                    >
                      <input v-model="draftPermissionIds" type="checkbox" :value="permission.id" />
                      <span>
                        <strong>{{ permission.permissionName }}</strong>
                        <small>{{ permission.permissionCode }}</small>
                      </span>
                      <em>{{ typeLabel(permission.permissionType) }}</em>
                    </label>
                  </div>
                </details>
                <div v-if="permissionGroups.length === 0" class="empty-state">暂无功能权限</div>
              </div>
            </section>

            <div class="role-config-side-stack">
              <details class="role-config-panel role-config-collapsible">
                <summary class="manage-list-head role-config-summary">
                  <strong>角色信息</strong>
                  <span>{{ roleInfoSummary }}</span>
                </summary>
                <div class="role-form-grid">
                  <label>
                    <span>角色名称</span>
                    <input v-model.trim="roleForm.roleName" type="text" />
                  </label>
                  <label>
                    <span>角色编码</span>
                    <input v-model.trim="roleForm.roleCode" type="text" />
                  </label>
                  <label>
                    <span>角色类型</span>
                    <select v-model="roleForm.roleType">
                      <option value="platform">平台</option>
                      <option value="tenant">租户</option>
                      <option value="business">业务</option>
                      <option value="guest">访客</option>
                    </select>
                  </label>
                  <label>
                    <span>状态</span>
                    <select v-model="roleForm.status">
                      <option value="enabled">启用</option>
                      <option value="disabled">停用</option>
                    </select>
                  </label>
                  <details class="role-remark-details">
                    <summary>
                      <span>备注</span>
                      <span class="role-remark-cue">展开填写</span>
                    </summary>
                    <textarea
                      v-model.trim="roleForm.description"
                      rows="2"
                      placeholder="填写角色补充说明"
                    ></textarea>
                  </details>
                </div>
              </details>

              <details class="role-config-panel role-config-collapsible">
                <summary class="manage-list-head role-config-summary">
                  <strong>组织范围</strong>
                  <span>{{ scopeSummary }}</span>
                </summary>
                <div class="scope-box">
                  <label>
                    <span>数据范围</span>
                    <select v-model="draftScopeType">
                      <option value="all">全部组织</option>
                      <option value="org">指定组织</option>
                      <option value="org_and_children">组织及下级</option>
                    </select>
                  </label>
                  <label v-if="draftScopeType !== 'all'">
                    <span>组织</span>
                    <select v-model="draftScopeOrgId">
                      <option value="">未选择</option>
                      <option v-for="org in orgs" :key="org.id" :value="org.id">
                        {{ org.orgName }}
                      </option>
                    </select>
                  </label>
                </div>
              </details>

              <details class="role-config-panel role-config-collapsible">
                <summary class="manage-list-head role-config-summary">
                  <strong>Agent绑定</strong>
                  <span>{{ draftAgentSummary }}</span>
                </summary>
                <button type="button" class="agent-bind-select" @click="openAgentPicker">
                  <span>{{ draftAgentSummary }}</span>
                  <em>{{ draftAgentIds.length }} / {{ agentOptions.length }}</em>
                </button>
                <div v-if="selectedDraftAgents.length" class="selected-agent-chips">
                  <button
                    v-for="agent in selectedDraftAgents"
                    :key="agent.id"
                    type="button"
                    title="移除Agent"
                    @click="removeDraftAgent(agent.id)"
                  >
                    {{ agent.name || agent.id }}
                  </button>
                </div>
                <div v-else class="empty-state">当前角色未绑定Agent，点击上方区域编辑</div>
              </details>

              <details class="role-config-panel role-config-collapsible">
                <summary class="manage-list-head role-config-summary">
                  <strong>绑定用户</strong>
                  <span>{{ draftUserIds.length }} 个用户</span>
                </summary>
                <div class="manage-list-head role-config-subhead">
                  <span></span>
                  <button type="button" class="ghost-button compact-button" @click="openUserPicker">
                    <Plus :size="14" />
                    添加/编辑
                  </button>
                </div>
                <div class="user-bind-table editable-bind-table" @click="openUserPicker">
                  <div class="user-bind-head">
                    <span>用户信息</span>
                    <span>状态</span>
                    <span>操作</span>
                  </div>
                  <div v-for="user in selectedDraftUsers" :key="user.id" class="user-bind-row">
                    <span class="user-bind-detail">
                      <strong>{{ user.displayName }}</strong>
                      <small>账号：{{ user.username }}</small>
                      <small>组织：{{ orgName(user.orgId) }}</small>
                      <small v-if="user.email">邮箱：{{ user.email }}</small>
                      <small v-if="user.phone">电话：{{ user.phone }}</small>
                    </span>
                    <em :class="['status-pill', user.status]">{{ statusLabel(user.status) }}</em>
                    <button type="button" class="icon-button" title="移除用户" @click.stop="removeDraftUser(user.id)">
                      <Trash2 :size="15" />
                    </button>
                  </div>
                  <div v-if="selectedDraftUsers.length === 0" class="empty-state">当前角色未绑定用户，点击添加</div>
                </div>
              </details>
            </div>
          </div>

          <div class="modal-actions">
            <button type="button" class="ghost-button" @click="closeRoleModal">取消</button>
            <button type="submit" class="primary-button" :disabled="savingRole">
              <Save :size="16" />
              <span>保存角色配置</span>
            </button>
          </div>
        </form>
      </div>

      <div v-if="agentPickerOpen" class="permission-modal-backdrop">
        <div class="user-picker-modal agent-picker-modal">
          <div class="modal-head">
            <div>
              <p>Agent绑定</p>
              <h2>搜索并选择可用Agent</h2>
            </div>
            <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" @click="closeAgentPicker">
              <X :size="18" />
            </button>
          </div>

          <div class="user-picker-tools agent-picker-tools">
            <label>
              <span>搜索Agent</span>
              <input v-model.trim="agentPickerQuery" type="search" placeholder="名称、ID、分类或状态" />
            </label>
            <button type="button" class="ghost-button compact-button" @click="selectAllPickerAgents">全选当前结果</button>
            <button type="button" class="ghost-button compact-button" @click="clearPickerAgents">清空</button>
            <span>{{ tempPickerAgentIds.length }} / {{ agentOptions.length }}</span>
          </div>

          <div class="agent-picker-list">
            <label v-for="agent in filteredAgentOptions" :key="agent.id" class="agent-picker-record">
              <input v-model="tempPickerAgentIds" type="checkbox" :value="agent.id" />
              <span>
                <strong>{{ agent.name || agent.id }}</strong>
                <small>{{ agent.id }} · {{ agent.marketStatus || agent.status || "draft" }}</small>
              </span>
            </label>
            <div v-if="filteredAgentOptions.length === 0" class="empty-state">没有匹配的Agent</div>
          </div>

          <div class="modal-actions">
            <button type="button" class="ghost-button" @click="closeAgentPicker">取消</button>
            <button type="button" class="primary-button" @click="confirmAgentPicker">
              <Save :size="16" />
              <span>确认绑定</span>
            </button>
          </div>
        </div>
      </div>

      <div v-if="userPickerOpen" class="permission-modal-backdrop">
        <div class="user-picker-modal">
          <div class="modal-head">
            <div>
              <p>添加用户</p>
              <h2>批量选择已创建用户</h2>
            </div>
            <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" @click="closeUserPicker">
              <X :size="18" />
            </button>
          </div>

          <div class="user-picker-tools">
            <button type="button" class="ghost-button compact-button" @click="selectAllPickerUsers">全选</button>
            <button type="button" class="ghost-button compact-button" @click="clearPickerUsers">清空</button>
            <span>{{ tempPickerUserIds.length }} / {{ scopedUsers.length }}</span>
          </div>

          <div class="user-picker-table">
            <div class="user-picker-head">
              <span>选择</span>
              <span>姓名</span>
              <span>账号</span>
              <span>组织</span>
              <span>状态</span>
            </div>
            <label v-for="user in scopedUsers" :key="user.id" class="user-picker-record">
              <span><input v-model="tempPickerUserIds" type="checkbox" :value="user.id" /></span>
              <strong>{{ user.displayName }}</strong>
              <span>{{ user.username }}</span>
              <span>{{ orgName(user.orgId) }}</span>
              <em :class="['status-pill', user.status]">{{ statusLabel(user.status) }}</em>
            </label>
            <div v-if="scopedUsers.length === 0" class="empty-state">当前组织范围内暂无已创建用户</div>
          </div>

          <div class="modal-actions">
            <button type="button" class="ghost-button" @click="closeUserPicker">取消</button>
            <button type="button" class="primary-button" @click="confirmUserPicker">
              <Save :size="16" />
              <span>确认添加</span>
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </section>
</template>

<script src="../js/views/SystemManagementView.js"></script>
