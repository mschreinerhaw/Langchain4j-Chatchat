<template>
  <el-card class="workspace-panel el-workspace-card settings-card" shadow="never">
    <template #header>
      <div class="panel-heading">
        <div>
          <h2>系统设置</h2>
          <p>管理管理员密码和角色权限。</p>
        </div>
        <div class="panel-actions">
          <el-button plain :loading="busy" @click="loadSnapshot">
            <el-icon><Refresh /></el-icon>
            <span>刷新授权</span>
          </el-button>
          <el-button type="primary" plain :loading="busy" @click="syncSnapshot">
            <el-icon><Connection /></el-icon>
            <span>同步授权</span>
          </el-button>
        </div>
      </div>
    </template>

    <el-tabs v-model="activeTab" class="settings-tabs">
      <el-tab-pane label="用户管理" name="users">
        <div class="settings-section-head">
          <div>
            <h3>用户管理</h3>
            <p>查看 MCP 本地管理员和远端同步用户，点击行操作修改用户密码。</p>
          </div>
          <div class="panel-actions">
            <el-input v-model.trim="userKeyword" class="settings-search" clearable placeholder="搜索用户、租户、角色">
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>
            <el-button plain :loading="busy" @click="loadUsers">
              <el-icon><Refresh /></el-icon>
              <span>刷新用户</span>
            </el-button>
          </div>
        </div>

        <el-table class="settings-table" :data="filteredUsers" border stripe empty-text="暂无用户">
          <el-table-column label="用户" min-width="180">
            <template #default="{ row }">
              <strong>{{ row.displayName || row.username || row.id }}</strong>
              <div class="table-subtle">账号：{{ row.username || '-' }}</div>
              <div class="table-subtle">用户 ID：{{ row.id || '-' }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="sourceLabel" label="来源" width="150" />
          <el-table-column prop="tenantNo" label="租户编号（同租户共享）" min-width="190">
            <template #default="{ row }"><code>{{ row.tenantNo || '-' }}</code></template>
          </el-table-column>
          <el-table-column label="角色" min-width="220">
            <template #default="{ row }">
              <el-space wrap>
                <el-tag v-for="role in row.roleIds" :key="role" effect="light">{{ role }}</el-tag>
                <span v-if="!row.roleIds || !row.roleIds.length">-</span>
              </el-space>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="row.status === 'enabled' || row.status === 'active' ? 'success' : 'info'" effect="light">
                {{ row.status || 'enabled' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column fixed="right" label="操作" width="150">
            <template #default="{ row }">
              <el-button v-if="canChangeUserPassword(row)" link type="primary" @click="openUserPasswordDialog(row)">
                修改密码
              </el-button>
              <el-tag v-else type="info" effect="plain">仅查看</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="角色权限" name="rolePermissions">
        <div class="settings-section-head">
          <div>
            <h3>已同步角色</h3>
            <p>来自远端的角色信息会同步到 MCP server 本地角色表。</p>
          </div>
          <el-button plain :loading="busy" @click="loadRoles">
            <el-icon><Refresh /></el-icon>
            <span>刷新本地角色</span>
          </el-button>
        </div>

        <el-table class="settings-table" :data="roles" border stripe empty-text="暂无同步角色，请点击同步授权">
          <el-table-column prop="roleName" label="角色" min-width="180">
            <template #default="{ row }">
              <strong>{{ row.roleName || row.roleCode || row.id }}</strong>
              <div class="table-subtle">{{ row.roleCode || row.id }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="roleType" label="类型" width="140" />
          <el-table-column prop="tenantId" label="租户" min-width="220">
            <template #default="{ row }"><code>{{ row.tenantId || '-' }}</code></template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="row.status === 'enabled' ? 'success' : 'info'" effect="light">{{ row.status || '-' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column fixed="right" label="操作" width="150">
            <template #default="{ row }">
              <el-tag v-if="isSuperAdmin(row)" type="success" effect="light">默认全权限</el-tag>
              <el-button v-else link type="primary" @click="openAuthorizationDialog(row)">管理授权</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-divider />

        <div class="settings-section-head compact">
          <div>
            <h3>当前角色授权</h3>
            <p>{{ selectedRoleTitle }}</p>
          </div>
          <div class="panel-actions settings-permission-tools">
            <el-input
              v-model.trim="permissionKeyword"
              class="settings-search"
              clearable
              :disabled="!permissions.length"
              placeholder="搜索权限 ID、工具、租户、策略"
              @input="resetPermissionPage"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>
            <el-button plain :disabled="!selectedRole || isSuperAdmin(selectedRole)" :loading="busy" @click="loadRolePermissions">
              <el-icon><Refresh /></el-icon>
              <span>刷新授权明细</span>
            </el-button>
          </div>
        </div>

        <el-table class="settings-table" :data="pagedPermissions" border stripe empty-text="请选择角色或暂无匹配权限">
          <el-table-column prop="id" label="权限 ID" min-width="220">
            <template #default="{ row }"><code>{{ row.id }}</code></template>
          </el-table-column>
          <el-table-column label="授权工具" min-width="190">
            <template #default="{ row }">{{ row.localToolName || row.toolId || row.scopeExpression || '-' }}</template>
          </el-table-column>
          <el-table-column prop="targetId" label="角色" min-width="140" />
          <el-table-column prop="tenantId" label="租户" min-width="160">
            <template #default="{ row }">{{ row.tenantId || '-' }}</template>
          </el-table-column>
          <el-table-column prop="effect" label="策略" width="110">
            <template #default="{ row }">
              <el-tag :type="row.effect === 'deny' ? 'danger' : 'success'" effect="light">{{ row.effect || 'allow' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column fixed="right" label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="danger" :disabled="!row.id" @click="removeRolePermission(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <footer v-if="permissions.length" class="pagination-row">
          <el-text type="info">共 {{ permissions.length }} 条，匹配 {{ filteredPermissions.length }} 条</el-text>
          <el-pagination
            background
            layout="sizes, prev, pager, next, jumper"
            :current-page="permissionPage"
            :page-size="permissionPageSize"
            :page-sizes="[10, 20, 50, 100]"
            :total="filteredPermissions.length"
            @current-change="changePermissionPage"
            @size-change="changePermissionPageSize"
          />
        </footer>
      </el-tab-pane>

      <el-tab-pane label="登录审计" name="loginAudits">
        <div class="settings-section-head">
          <div>
            <h3>用户登录审计</h3>
            <p>查看 MCP 管理后台登录成功、失败、来源 IP、MAC 和终端信息。</p>
          </div>
          <div class="panel-actions login-audit-filters">
            <el-input
              v-model.trim="loginAuditKeyword"
              class="settings-search"
              clearable
              placeholder="搜索用户、IP、MAC、终端、失败原因"
              @keyup.enter="searchLoginAudits"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>
            <el-select v-model="loginAuditAction" class="login-audit-filter" placeholder="方式" @change="searchLoginAudits">
              <el-option label="全部方式" value="" />
              <el-option label="管理员登录" value="admin-login" />
            </el-select>
            <el-select v-model="loginAuditResult" class="login-audit-filter" placeholder="结果" @change="searchLoginAudits">
              <el-option label="全部结果" value="" />
              <el-option label="成功" value="success" />
              <el-option label="失败" value="failure" />
            </el-select>
            <el-button plain :loading="busy" @click="searchLoginAudits">
              <el-icon><Refresh /></el-icon>
              <span>查询</span>
            </el-button>
          </div>
        </div>

        <el-table class="settings-table" :data="loginAuditRows" border stripe empty-text="暂无登录审计">
          <el-table-column prop="createdAtText" label="时间" min-width="170" />
          <el-table-column label="用户" min-width="150">
            <template #default="{ row }">
              <strong>{{ row.username || 'anonymous' }}</strong>
              <div class="table-subtle">{{ row.reason || row.id }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="actionLabel" label="方式" width="130" />
          <el-table-column label="结果" width="100">
            <template #default="{ row }">
              <el-tag :type="row.result === 'success' ? 'success' : 'danger'" effect="light">
                {{ row.resultLabel }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="ipAddress" label="IP" min-width="150" />
          <el-table-column prop="macAddress" label="MAC" min-width="160">
            <template #default="{ row }">{{ row.macAddress || '-' }}</template>
          </el-table-column>
          <el-table-column label="终端" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">{{ row.userAgentText }}</template>
          </el-table-column>
        </el-table>

        <footer class="pagination-row">
          <el-text type="info">
            显示 {{ loginAuditPageStart }}-{{ loginAuditPageEnd }} 条，共 {{ loginAuditTotal }} 条
          </el-text>
          <el-pagination
            background
            layout="sizes, prev, pager, next, jumper"
            :current-page="loginAuditPage"
            :page-size="loginAuditPageSize"
            :page-sizes="[10, 20, 50, 100]"
            :total="loginAuditTotal"
            @current-change="changeLoginAuditPage"
            @size-change="changeLoginAuditPageSize"
          />
        </footer>
      </el-tab-pane>

    </el-tabs>

    <el-dialog
      v-model="userPasswordDialogVisible"
      class="modal-panel"
      width="min(560px, 94vw)"
      destroy-on-close
      :title="userPasswordDialogTitle"
    >
      <el-form class="settings-form" label-position="top" @submit.prevent="saveUserPassword">
        <el-form-item label="当前密码">
          <el-input v-model="passwordForm.currentPassword" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="passwordForm.newPassword" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="passwordForm.confirmPassword" type="password" show-password autocomplete="new-password" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="userPasswordDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="passwordSaving" @click="saveUserPassword">保存密码</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog
      v-model="authorizationDialogVisible"
      class="modal-panel authorization-dialog"
      width="min(980px, 96vw)"
      destroy-on-close
      :title="authorizationDialogTitle"
    >
      <div class="authorization-toolbar">
        <el-select v-model="assetTypeFilter" class="authorization-filter" placeholder="类型" size="small" @change="resetAuthorizationPage">
          <el-option label="MCP 工具" value="mcp_tool" />
          <el-option label="全部类型" value="all" />
          <el-option label="数据查询" value="database_query" />
          <el-option label="API 服务" value="api_service" />
          <el-option label="HTTP 资产" value="http_endpoint" />
          <el-option label="主机资产" value="ssh_host" />
          <el-option label="SQL 数据源" value="sql_datasource" />
        </el-select>
        <el-select v-model="assetGroupFilter" class="authorization-filter" clearable filterable placeholder="分类" size="small" @change="resetAuthorizationPage">
          <el-option v-for="group in assetGroups" :key="group.value" :label="group.label" :value="group.value" />
        </el-select>
        <el-input v-model.trim="assetKeyword" class="authorization-search" clearable size="small" placeholder="搜索名称、工具名、描述" @input="resetAuthorizationPage">
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
      </div>

      <div class="authorization-actions">
        <span>已选择 {{ selectedAssetKeys.length }} / {{ authorizationAssets.length }} 个资产</span>
        <div>
          <el-button plain size="small" :disabled="authorizationLoading" @click="selectFilteredAssets">全选当前结果</el-button>
          <el-button plain size="small" :disabled="authorizationLoading" @click="clearFilteredAssets">清空当前结果</el-button>
        </div>
      </div>

      <el-table
        class="settings-table authorization-asset-table"
        :data="pagedAuthorizationAssets"
        border
        stripe
        v-loading="authorizationLoading"
        empty-text="暂无可授权资产"
      >
        <el-table-column label="" width="54" align="center">
          <template #default="{ row }">
            <el-checkbox :model-value="isAssetSelected(row)" :disabled="!row.enabled" @change="checked => toggleAsset(row, checked)" />
          </template>
        </el-table-column>
        <el-table-column label="资产" min-width="230">
          <template #default="{ row }">
            <strong>{{ row.title || row.toolName }}</strong>
            <div class="table-subtle">{{ row.toolName }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="typeLabel" label="类型" width="120" />
        <el-table-column label="分类" min-width="170">
          <template #default="{ row }">{{ row.groupName || row.groupCode || 'default' }}</template>
        </el-table-column>
        <el-table-column label="描述" min-width="220">
          <template #default="{ row }">{{ row.description || '-' }}</template>
        </el-table-column>
        <el-table-column prop="enabled" label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" effect="light">{{ row.enabled ? 'enabled' : 'disabled' }}</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <footer class="pagination-row authorization-pagination">
        <el-text type="info">
          共 {{ authorizationAssets.length }} 个资产，当前筛选 {{ filteredAuthorizationAssets.length }} 个
        </el-text>
        <el-pagination
          background
          layout="sizes, prev, pager, next, jumper"
          :current-page="authorizationPage"
          :page-size="authorizationPageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="filteredAuthorizationAssets.length"
          @current-change="changeAuthorizationPage"
          @size-change="changeAuthorizationPageSize"
        />
      </footer>

      <template #footer>
        <div class="dialog-footer">
          <el-button @click="authorizationDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="authorizationSaving" @click="saveAssetAuthorizations">
            保存授权
          </el-button>
        </div>
      </template>
    </el-dialog>
  </el-card>
</template>

<script src="../scripts/views/SettingsView.js"></script>
