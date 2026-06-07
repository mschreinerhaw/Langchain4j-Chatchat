<template>
  <section class="feature-view system-management-view">
    <header class="system-header">
      <div>
        <p>系统管理</p>
        <h1>角色权限管理</h1>
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

    <div class="rbac-board">
      <aside class="rbac-panel role-panel">
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
            <button type="button" title="同步用户" @click="syncUsers" :disabled="loading">
              <RefreshCw :size="14" />
              用户
            </button>
            <button type="button" @click="openRoleModal()">
              <Plus :size="14" />
              新增
            </button>
            <button type="button" :disabled="!selectedRoleId" @click="openRoleModal(selectedRole)">
              <Pencil :size="14" />
              编辑
            </button>
            <button type="button" :disabled="!selectedRoleId" @click="removeRole">
              <Trash2 :size="14" />
              删除
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
            <span>绑定用户</span>
          </div>
          <button
            v-for="role in roles"
            :key="role.id"
            type="button"
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
            <span>{{ role.id === selectedRoleId ? selectedUserIds.length : "-" }}</span>
          </button>
        </div>
      </aside>

    </div>

    <Teleport to="body">
      <div v-if="roleModalOpen" class="permission-modal-backdrop" @click.self="closeRoleModal">
        <form class="role-modal" @submit.prevent="saveRoleForm">
          <div class="modal-head">
            <div>
              <p>角色权限配置</p>
              <h2>{{ roleForm.id ? "修改角色" : "新建角色" }}</h2>
            </div>
            <button type="button" class="icon-button" title="关闭" @click="closeRoleModal">
              <X :size="18" />
            </button>
          </div>

          <div class="role-config-body">
            <section class="role-config-panel">
              <div class="manage-list-head">
                <strong>角色信息</strong>
              </div>
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
            </section>

            <section class="role-config-panel">
              <div class="manage-list-head">
                <strong>组织范围</strong>
              </div>
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
            </section>

            <section class="role-config-panel">
              <div class="manage-list-head">
                <strong>功能权限</strong>
                <div class="mini-actions">
                  <button type="button" @click="selectAllPermissions">全选</button>
                  <button type="button" @click="clearPermissions">清空</button>
                </div>
              </div>
              <div class="permission-tree auth-check-list">
                <label
                  v-for="permission in permissionTree"
                  :key="permission.id"
                  class="permission-item"
                  :style="{ '--level': permission.level }"
                >
                  <input v-model="draftPermissionIds" type="checkbox" :value="permission.id" />
                  <span>
                    <strong>{{ permission.permissionName }}</strong>
                    <small>{{ permission.permissionCode }}</small>
                  </span>
                  <em>{{ typeLabel(permission.permissionType) }}</em>
                </label>
              </div>
            </section>

            <section class="role-config-panel">
              <div class="manage-list-head">
                <strong>绑定用户</strong>
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
            </section>
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

      <div v-if="userPickerOpen" class="permission-modal-backdrop" @click.self="closeUserPicker">
        <div class="user-picker-modal">
          <div class="modal-head">
            <div>
              <p>添加用户</p>
              <h2>批量选择已创建用户</h2>
            </div>
            <button type="button" class="icon-button" title="关闭" @click="closeUserPicker">
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
