<template>
  <section class="permission-console">
    <header class="permission-page-heading"
      ><div
        ><nav aria-label="面包屑"
          >权限管理 <span>/</span> {{ mode === "roles" ? "角色权限" : "资源授权" }}</nav
        ><h1>{{ mode === "roles" ? "角色权限" : "资源授权" }}</h1
        ><p>{{
          mode === "roles"
            ? "选择角色，按业务资源分配权限，查看成员和生效范围。"
            : "按资源查看角色与用户授权，所有修改统一记录、统一生效。"
        }}</p></div
      ><div class="permission-heading-actions"
        ><button :disabled="busy" @click="load">刷新</button
        ><button
          v-if="mode === 'roles' && canManageRoles"
          class="permission-primary"
          @click="openRole()"
          >新增角色</button
        ></div
      ></header
    >
    <p v-if="notice" :class="['permission-notice', { error: failed }]" role="status">{{
      notice
    }}</p>
    <p v-if="busy && !ready" class="permission-loading" role="status"
      >正在加载当前租户的权限目录…</p
    >
    <div v-if="ready" class="permission-master-detail" :aria-busy="busy">
      <aside class="permission-master"
        ><div class="permission-master-head"
          ><strong>{{ mode === "roles" ? "角色列表" : "资源目录" }}</strong
          ><span>{{ masterItems.length }}</span></div
        ><label class="sr-only" for="permission-master-search">{{
          mode === "roles" ? "搜索角色" : "搜索资源"
        }}</label
        ><input
          id="permission-master-search"
          v-model="masterQuery"
          type="search"
          :placeholder="mode === 'roles' ? '搜索角色名称' : '搜索资源名称'"
        />
        <select v-if="mode === 'resources'" v-model="resourceGroup" aria-label="资源类型"
          ><option value="">全部资源类型</option
          ><option v-for="group in resourceGroups" :key="group.id" :value="group.id">{{
            group.label
          }}</option></select
        >
        <div class="permission-master-list"
          ><button
            v-for="item in masterPage"
            :key="item.key || item.id"
            :class="[
              'permission-master-item',
              { active: mode === 'roles' ? item.id === roleId : item.key === resourceKey },
            ]"
            :aria-pressed="mode === 'roles' ? item.id === roleId : item.key === resourceKey"
            @click="chooseMaster(item)"
            ><strong>{{ item.roleName || item.name }}</strong
            ><small>{{
              mode === "roles"
                ? (item.status === "enabled" ? "启用" : "停用") +
                  " · " +
                  (item.description || "自定义角色")
                : groupLabel(item.group) + " · " + item.action
            }}</small></button
          ><p v-if="!masterItems.length" class="permission-empty"
            >没有匹配项。请调整搜索条件{{ mode === "roles" ? "，或新增角色" : "" }}。</p
          ></div
        >
        <footer class="permission-pagination"
          ><button :disabled="masterPageNumber <= 1" @click="masterPageNumber--">上一页</button
          ><span>{{ masterPageNumber }} / {{ masterPages }}</span
          ><button :disabled="masterPageNumber >= masterPages" @click="masterPageNumber++"
            >下一页</button
          ></footer
        >
      </aside>
      <main class="permission-detail">
        <template v-if="mode === 'roles' && selectedRole">
          <header class="permission-detail-heading"
            ><div
              ><p>当前角色</p><h2>{{ selectedRole.roleName }}</h2
              ><span>{{
                selectedRole.description || "为该角色配置工作空间、功能操作和受控资源访问。"
              }}</span></div
            ><div class="permission-heading-actions"
              ><button v-if="canManageRoles" @click="openRole(selectedRole)">编辑角色与成员</button
              ><button v-if="canManageRoles" class="danger" @click="removeRole">删除</button></div
            ></header
          >
          <div class="permission-role-facts"
            ><span>{{ selectedAuthorization.userIds?.length || 0 }} 位成员</span
            ><span>{{ selectedAuthorization.agentIds?.length || 0 }} 项查询服务绑定</span
            ><span>{{
              selectedRole.status === "enabled" ? "角色已启用" : "角色已停用，授权不生效"
            }}</span></div
          >
          <template v-if="selectedResource"
            ><button class="permission-back" @click="resourceKey = ''">← 返回角色授权列表</button
            ><PermissionResourceEditor
              :resource="selectedResource"
              :catalog="catalog"
              :services="extras.services"
              :role-id="roleId"
              :can-edit="canEdit"
              @changed="reloadAfterChange"
          /></template>
          <template v-else>
            <nav class="permission-category-tabs" aria-label="授权分类"
              ><button
                v-for="group in resourceGroups"
                :key="group.id"
                :class="{ active: roleGroup === group.id }"
                @click="roleGroup = group.id"
                >{{ group.label }}</button
              ></nav
            >
            <div class="permission-toolbar"
              ><label class="permission-search"
                >搜索资源<input v-model="rowQuery" type="search" placeholder="资源名称" /></label
              ><label
                >授权状态<select v-model="stateFilter"
                  ><option value="">全部状态</option
                  ><option value="direct">角色直接授权</option
                  ><option value="inherited">租户继承</option
                  ><option value="none">无显式规则</option></select
                ></label
              ><span>{{ roleRows.length }} 项</span></div
            >
            <div
              v-if="['workspace', 'function'].includes(roleGroup) && canEdit"
              class="permission-batch"
              ><label
                ><input
                  type="checkbox"
                  :checked="allVisibleSelected"
                  :disabled="!roleRows.length"
                  @change="selectVisible($event.target.checked)"
                />
                选择当前筛选结果（{{ roleRows.length }} 项）</label
              ><button
                class="permission-primary"
                :disabled="busy || !permissionDirty"
                @click="savePermissions"
                >{{ busy ? "保存中…" : "保存权限变更" }}</button
              ></div
            >
            <div v-if="!['workspace', 'function'].includes(roleGroup)" class="permission-batch"
              ><label
                ><input
                  type="checkbox"
                  :checked="allResourceRowsSelected"
                  @change="selectResourceRows($event.target.checked)"
                />选择当前筛选结果</label
              ><div class="permission-heading-actions"
                ><span>已选 {{ bulkSelected.length }} 项</span
                ><button :disabled="!bulkSelected.length || busy" @click="bulkSelected = []"
                  >清除选择</button
                ><button
                  class="permission-primary"
                  :disabled="!bulkSelected.length || busy"
                  @click="allowSelectedResources"
                  >批量添加允许规则</button
                ></div
              ></div
            >
            <div class="permission-table-wrap"
              ><table class="permission-table"
                ><thead
                  ><tr
                    ><th>选择</th><th>资源名称</th><th>类型 / 动作</th><th>授权状态</th
                    ><th>来源 / 范围</th><th>操作</th></tr
                  ></thead
                ><tbody
                  ><tr v-for="row in visibleRoleRows" :key="row.key"
                    ><td v-if="row.backend === 'permission'"
                      ><input
                        type="checkbox"
                        :checked="permissionDraft.includes(row.id)"
                        :aria-label="'授权 ' + row.name"
                        :disabled="!canEdit || busy"
                        @change="togglePermission(row.id, $event.target.checked)" /></td
                    ><td v-else
                      ><input
                        v-model="bulkSelected"
                        type="checkbox"
                        :value="row.key"
                        :disabled="busy || !canEditResource(row)"
                        :aria-label="'选择 ' + row.name" /></td
                    ><td
                      ><strong>{{ row.name }}</strong></td
                    ><td
                      >{{ groupLabel(row.group) }}<small>{{ row.action }}</small></td
                    ><td
                      ><span class="permission-badge">{{ stateFor(row).status }}</span
                      ><small
                        v-if="
                          row.backend === 'permission' &&
                          permissionDraft.includes(row.id) !== originalPermissions.includes(row.id)
                        "
                        >待保存</small
                      ></td
                    ><td
                      >{{
                        stateFor(row).direct.length
                          ? "角色直接授权"
                          : stateFor(row).inherited.length
                            ? "租户继承"
                            : "无显式规则"
                      }}<small>{{
                        stateFor(row).rules.some((g) => g.agentId)
                          ? "含查询服务限定"
                          : stateFor(row).rules.some((g) => g.scopeExpression)
                            ? "按数据范围生效"
                            : "当前租户 · 当前资源"
                      }}</small></td
                    ><td
                      ><button class="permission-link" @click="resourceKey = row.key">{{
                        canEdit ? "管理授权" : "查看授权"
                      }}</button></td
                    ></tr
                  ><tr v-if="!roleRows.length"
                    ><td colspan="6" class="permission-empty"
                      >没有匹配的资源。可切换分类或清除筛选条件。</td
                    ></tr
                  ></tbody
                ></table
              ></div
            >
            <footer class="permission-pagination"
              ><span>共 {{ roleRows.length }} 项</span
              ><label
                >每页<select v-model.number="pageSize"
                  ><option :value="20">20 项</option
                  ><option :value="50">50 项</option></select
                ></label
              ><button :disabled="rowPage <= 1" @click="rowPage--">上一页</button
              ><span>{{ rowPage }} / {{ rowPages }}</span
              ><button :disabled="rowPage >= rowPages" @click="rowPage++">下一页</button></footer
            >
            <p class="permission-footnote"
              >显示显式规则及其来源，最终访问还受账户状态、资源原有权限、数据范围和拒绝规则约束。</p
            >
          </template>
        </template>
        <PermissionResourceEditor
          v-else-if="mode === 'resources' && selectedResource"
          :resource="selectedResource"
          :catalog="catalog"
          :services="extras.services"
          :can-edit="canEdit"
          @changed="reloadAfterChange"
        />
        <div v-else class="permission-empty"
          >请从左侧选择{{ mode === "roles" ? "角色" : "资源" }}，查看详情并管理授权。</div
        >
      </main>
    </div>
    <Teleport to="body"
      ><div v-if="roleForm" class="permission-modal-backdrop" @click.self="closeRole"
        ><form class="permission-profile-dialog" @submit.prevent="saveRole"
          ><header
            ><h2>{{ roleForm.id ? "编辑角色与成员" : "新增角色" }}</h2
            ><button type="button" aria-label="关闭角色编辑" @click="closeRole">×</button></header
          ><p v-if="formError" role="alert" class="permission-notice error">{{ formError }}</p
          ><div class="permission-form-grid"
            ><label>角色名称<input v-model.trim="roleForm.roleName" required /></label
            ><label
              >状态<select v-model="roleForm.status"
                ><option value="enabled">启用</option
                ><option value="disabled">停用</option></select
              ></label
            ></div
          ><label>角色说明<textarea v-model.trim="roleForm.description" rows="2" /></label
          ><details :open="!roleForm.id"
            ><summary>角色编码与类型</summary
            ><div class="permission-form-grid"
              ><label>角色编码<input v-model.trim="roleForm.roleCode" required /></label
              ><label
                >类型<select v-model="roleForm.roleType"
                  ><option value="business">业务角色</option
                  ><option value="system">系统角色</option></select
                ></label
              ></div
            ></details
          ><details v-if="canAuthorizeRoles"
            ><summary>角色成员（{{ memberDraft.length }}）</summary
            ><div class="permission-check-grid"
              ><label v-for="user in catalog.users" :key="user.id"
                ><input v-model="memberDraft" type="checkbox" :value="user.id" />{{
                  user.displayName || user.username
                }}</label
              ></div
            ></details
          ><details v-if="canAuthorizeRoles"
            ><summary>查询服务访问（{{ serviceDraft.length }}）</summary
            ><p>保留现有本地查询服务绑定；资源和工具授权仍独立校验。</p
            ><div class="permission-check-grid"
              ><label v-for="service in extras.services" :key="service.id"
                ><input v-model="serviceDraft" type="checkbox" :value="service.id" />{{
                  service.name
                }}</label
              ></div
            ></details
          ><details v-if="canAuthorizeRoles"
            ><summary>组织范围</summary
            ><div v-for="(scope, index) in scopeDraft" :key="index" class="permission-scope-row"
              ><select v-model="scope.scopeType" aria-label="组织范围类型"
                ><option value="all">全部组织</option
                ><option value="org">指定组织</option
                ><option value="org_and_children">指定组织及下级</option></select
              ><select
                v-if="scope.scopeType !== 'all'"
                v-model="scope.orgId"
                required
                aria-label="范围所属组织"
                ><option value="">请选择组织</option
                ><option v-for="org in orgs" :key="org.id" :value="org.id">{{
                  org.orgName
                }}</option></select
              ><button type="button" @click="scopeDraft.splice(index, 1)">移除</button></div
            ><button
              type="button"
              @click="scopeDraft.push({ scopeType: 'org_and_children', orgId: '' })"
              >添加范围</button
            ></details
          ><footer
            ><button type="button" @click="closeRole">取消</button
            ><button class="permission-primary" :disabled="busy">{{
              busy ? "保存中…" : "保存角色"
            }}</button></footer
          ></form
        ></div
      ></Teleport
    >
  </section>
</template>
<script src="../js/views/PermissionConsoleView.js"></script>
