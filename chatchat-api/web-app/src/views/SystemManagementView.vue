<template>
  <section class="feature-view system-management-view" :class="{ 'has-message': !!message }">
    <header v-if="!embedded" class="system-header">
      <div>
        <nav aria-label="面包屑">权限管理 / {{ sectionTitle }}</nav
        ><h1>{{ sectionTitle }}</h1>
        <span class="system-section-description">{{ sectionDescription }}</span>
      </div>
    </header>

    <div v-if="message" class="system-message" :class="{ error: !!error }">
      {{ message }}
    </div>

    <div class="rbac-board">
      <aside v-if="activeManagementTab === 'users'" class="rbac-panel user-panel system-tab-panel">
        <div class="panel-head">
          <div>
            <p>用户</p>
            <h2>用户档案</h2>
          </div>
          <div class="mini-actions">
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

        <div class="permission-toolbar"
          ><label class="permission-search"
            >搜索用户<input v-model="entityQuery" type="search" placeholder="输入名称" /></label
          ><label
            >状态<select v-model="entityStatus"
              ><option value="">全部状态</option
              ><option value="enabled">启用</option
              ><option value="disabled">停用</option></select
            ></label
          ><span>{{ filteredEntities.length }} 项</span></div
        ><div class="entity-table">
          <div class="entity-table-head user-table-row">
            <span>用户信息</span>
            <span>组织</span>
            <span>角色</span>
            <span>状态</span>
            <span>操作</span>
          </div>
          <div
            v-for="user in pagedUsers"
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
              <button
                type="button"
                class="icon-button"
                title="编辑账户"
                @click="openUserModal(user)"
              >
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
          <div v-if="users.length === 0" class="empty-state">暂无用户</div> </div
        ><footer class="permission-pagination"
          ><span>共 {{ filteredEntities.length }} 项</span
          ><label
            >每页<select v-model.number="entityPageSize"
              ><option :value="20">20 项</option
              ><option :value="50">50 项</option></select
            ></label
          ><button :disabled="entityPage <= 1" @click="entityPage--">上一页</button
          ><span>{{ entityPage }} / {{ entityPages }}</span
          ><button :disabled="entityPage >= entityPages" @click="entityPage++"
            >下一页</button
          ></footer
        >
      </aside>

      <aside
        v-else-if="activeManagementTab === 'organizations'"
        class="rbac-panel organization-panel system-tab-panel"
      >
        <div class="panel-head">
          <div>
            <p>组织机构</p>
            <h2>组织档案</h2>
          </div>
          <div class="mini-actions">
            <button type="button" @click="openOrgModal()"><Plus :size="14" />新增组织</button
            ><button type="button" title="同步组织" @click="syncOrgs" :disabled="loading">
              <RefreshCw :size="14" />
              {{ loading ? "同步中" : "同步组织" }}
            </button>
          </div>
        </div>

        <div class="permission-toolbar"
          ><label class="permission-search"
            >搜索组织<input v-model="entityQuery" type="search" placeholder="输入名称" /></label
          ><label
            >状态<select v-model="entityStatus"
              ><option value="">全部状态</option
              ><option value="enabled">启用</option
              ><option value="disabled">停用</option></select
            ></label
          ><span>{{ filteredEntities.length }} 项</span></div
        ><div class="entity-table">
          <div class="entity-table-head organization-table-row">
            <span>组织名称</span>

            <span>上级组织</span>
            <span>层级</span>
            <span>成员数</span>
            <span>状态</span>
            <span>操作</span></div
          >
          <div
            v-for="org in pagedOrgs"
            :key="org.id"
            class="entity-table-row organization-table-row"
          >
            <span class="organization-name-cell">
              <i :style="{ width: `${org.level * 22}px` }" aria-hidden="true"></i>
              <Building2 :size="16" />
              <strong>{{ org.orgName }}</strong>
            </span>

            <span>{{ org.parentId ? orgName(org.parentId) : "根组织" }}</span>
            <span>{{ org.level + 1 }}</span>
            <span>{{ orgUserCounts[org.id] || 0 }}</span>
            <span
              ><em :class="['status-pill', org.status]">{{ statusLabel(org.status) }}</em></span
            >
            <span class="entity-row-actions"
              ><button type="button" @click="openOrgModal(org)">编辑</button
              ><details
                ><summary>详情</summary><small>{{ org.orgCode }}</small></details
              ></span
            ></div
          >
          <div v-if="orgTree.length === 0" class="empty-state"
            >暂无组织机构，请先同步组织</div
          > </div
        ><footer class="permission-pagination"
          ><span>共 {{ filteredEntities.length }} 项</span
          ><label
            >每页<select v-model.number="entityPageSize"
              ><option :value="20">20 项</option
              ><option :value="50">50 项</option></select
            ></label
          ><button :disabled="entityPage <= 1" @click="entityPage--">上一页</button
          ><span>{{ entityPage }} / {{ entityPages }}</span
          ><button :disabled="entityPage >= entityPages" @click="entityPage++"
            >下一页</button
          ></footer
        >
      </aside>

      <aside v-else class="rbac-panel login-audit-panel system-tab-panel">
        <div class="panel-head">
          <div>
            <p>审计</p>
            <h2>登录行为</h2>
          </div>
          <div class="mini-actions">
            <button
              type="button"
              title="刷新登录审计"
              @click="searchLoginAuditLogs"
              :disabled="loading"
            >
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
          <button
            type="button"
            class="ghost-button compact-button"
            @click="searchLoginAuditLogs"
            :disabled="loading"
            >查询</button
          >
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
              :title="
                [audit.loginUser, audit.detailData.displayName || audit.actorId]
                  .filter(Boolean)
                  .join('\n')
              "
            >
              <strong>{{ audit.loginUser }}</strong>
              <small>{{ audit.detailData.displayName || audit.actorId || "未识别账户" }}</small>
            </span>
            <span class="login-audit-detail-cell" :title="audit.tenantName">{{
              audit.tenantName
            }}</span>
            <span class="login-audit-detail-cell" :title="audit.loginAction">{{
              audit.loginAction
            }}</span>
            <span>
              <em :class="['status-pill', audit.result]">{{ audit.loginResult }}</em>
            </span>
            <span class="login-audit-detail-cell" :title="audit.ipAddress">{{
              audit.ipAddress
            }}</span>
            <span class="login-audit-detail-cell" :title="audit.macAddress">{{
              audit.macAddress
            }}</span>
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
          <span
            >显示 {{ loginAuditPageStart }}-{{ loginAuditPageEnd }} 条，共
            {{ loginAuditTotal }} 条</span
          >
          <select :value="loginAuditPageSize" @change="changeLoginAuditPageSize">
            <option :value="10">10 条/页</option>
            <option :value="20">20 条/页</option>
            <option :value="50">50 条/页</option>
            <option :value="100">100 条/页</option>
          </select>
          <button
            type="button"
            :disabled="loginAuditPage <= 1 || loading"
            @click="changeLoginAuditPage(loginAuditPage - 1)"
            >上一页</button
          >
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
          <button
            type="button"
            :disabled="loginAuditPage >= loginAuditPageCount || loading"
            @click="changeLoginAuditPage(loginAuditPage + 1)"
            >下一页</button
          >
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
            <button
              type="button"
              class="app-dialog-close"
              aria-label="关闭"
              title="关闭"
              @click="closeOrgModal"
            >
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
            <button
              type="button"
              class="app-dialog-close"
              aria-label="关闭"
              title="关闭"
              @click="closeUserModal"
            >
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
              <input
                v-model="userForm.password"
                type="password"
                :placeholder="userForm.id ? '留空不修改' : '默认 123456'"
              />
            </label>
            <label>
              <span>所属组织</span>
              <select v-model="userForm.orgId">
                <option value="">未分配组织</option>
                <option v-for="org in selectableOrgTree" :key="org.id" :value="org.id">
                  {{
                    `${"　".repeat(org.level)}${org.orgName}${org.status === "enabled" ? "" : "（已停用）"}`
                  }}
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
            <button
              type="button"
              class="app-dialog-close"
              aria-label="关闭"
              title="关闭"
              @click="closeAdminPasswordModal"
            >
              <X :size="18" />
            </button>
          </div>

          <div class="entity-form modal-entity-form">
            <label class="full-span">
              <span>当前密码</span>
              <input
                v-model="adminPasswordForm.currentPassword"
                type="password"
                autocomplete="current-password"
              />
            </label>
            <label>
              <span>新密码</span>
              <input
                v-model="adminPasswordForm.newPassword"
                type="password"
                autocomplete="new-password"
              />
            </label>
            <label>
              <span>确认新密码</span>
              <input
                v-model="adminPasswordForm.confirmPassword"
                type="password"
                autocomplete="new-password"
              />
            </label>
          </div>

          <div class="modal-actions">
            <button type="button" class="ghost-button" @click="closeAdminPasswordModal"
              >取消</button
            >
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
            <button
              type="button"
              class="app-dialog-close"
              aria-label="关闭"
              title="关闭"
              @click="closeApiTokenModal"
            >
              <X :size="18" />
            </button>
          </div>

          <div class="api-token-owner">
            <span class="api-token-owner-avatar">{{
              (apiTokenTargetUser?.displayName || apiTokenTargetUser?.username || "U").slice(0, 1)
            }}</span>
            <span>
              <strong>{{ apiTokenTargetUser?.displayName }}</strong>
              <small>{{ apiTokenTargetUser?.username }} · 继承当前角色与 Agent 权限</small>
            </span>
            <em>权限实时同步</em>
          </div>

          <div class="api-token-create-card">
            <div class="api-token-section-title">
              <span
                ><strong>创建新令牌</strong
                ><small>令牌生成后可立即用于已授权的 Agent API</small></span
              >
            </div>
            <div class="api-token-create-form">
              <label>
                <span>令牌名称</span>
                <input
                  v-model.trim="apiTokenName"
                  type="text"
                  maxlength="128"
                  placeholder="例如：生产环境数据助手"
                />
              </label>
              <label>
                <span>有效期</span>
                <select v-model.number="apiTokenDuration">
                  <option
                    v-for="option in apiTokenDurations"
                    :key="option.value"
                    :value="option.value"
                  >
                    {{ option.label }}
                  </option>
                </select>
              </label>
              <button
                type="button"
                class="primary-button api-token-create-button"
                :disabled="apiTokenSaving || !apiTokenName"
                @click="createApiToken"
              >
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
            <button
              type="button"
              class="primary-button api-token-copy-button"
              @click="copyApiTokenSecret(apiTokenLatestSecret)"
            >
              <Copy :size="14" />
              复制
            </button>
          </div>

          <div class="api-token-list-section">
            <div class="api-token-section-title api-token-list-title">
              <span
                ><strong>已创建的令牌</strong><small>管理有效期、使用情况和访问状态</small></span
              >
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
                <span class="api-token-name"
                  ><strong>{{ token.tokenName }}</strong
                  ><small>{{ token.tokenPreview }}</small></span
                >
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
            <button
              type="button"
              class="app-dialog-close"
              aria-label="关闭"
              title="关闭"
              @click="closeApiTokenCopyDialog"
            >
              <X :size="17" />
            </button>
          </header>
          <div class="api-token-copy-dialog-body">
            <p
              >浏览器未授予自动复制权限。令牌已为你选中，可以点击下方按钮或按 <kbd>Ctrl</kbd> +
              <kbd>C</kbd> 复制。</p
            >
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
            <button type="button" class="secondary-button" @click="closeApiTokenCopyDialog"
              >取消</button
            >
            <button type="button" class="primary-button" @click="copyApiTokenFromDialog">
              <Copy :size="15" />
              复制令牌
            </button>
          </footer>
        </section>
      </div>
    </Teleport>
  </section>
</template>

<script src="../js/views/SystemManagementView.js"></script>
