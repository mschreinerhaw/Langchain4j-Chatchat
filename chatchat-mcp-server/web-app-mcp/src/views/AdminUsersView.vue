<template>
  <section class="workspace-panel admin-users" v-loading="loading">
    <header class="panel-heading"
      ><div
        ><h2>用户管理</h2
        ><p>管理 MCP 服务本地账号及功能菜单。业务工具与数据访问权限在 Runtime OS 统一维护。</p></div
      >
      <div class="panel-actions"
        ><el-button @click="load">刷新</el-button
        ><el-button type="primary" @click="openCreate">创建用户</el-button></div
      >
    </header>
    <div class="admin-user-filters">
      <el-input
        v-model="keyword"
        aria-label="搜索用户"
        clearable
        placeholder="搜索用户名"
        @keyup.enter="search"
      />
      <el-select v-model="status" aria-label="用户状态" @change="search"
        ><el-option label="全部状态" value="" /><el-option label="启用" value="enabled" /><el-option
          label="停用"
          value="disabled"
      /></el-select>
      <el-button @click="search">查询</el-button><span>共 {{ total }} 位用户</span>
    </div>
    <div class="admin-user-layout">
      <aside class="admin-user-list" aria-label="用户列表">
        <button
          v-for="user in users"
          :key="user.username"
          type="button"
          class="admin-user-item"
          :class="{ selected: selected?.username === user.username }"
          @click="select(user)"
        >
          <strong>{{ user.username }}</strong
          ><span
            >{{ user.superAdmin ? "内置管理员" : "普通管理用户" }} ·
            {{ user.enabled ? "启用" : "停用" }}</span
          >
        </button>
        <el-empty
          v-if="!users.length"
          description="没有匹配用户，可调整筛选或创建用户。"
          :image-size="60"
        />
        <el-pagination
          small
          layout="prev, pager, next"
          :current-page="page"
          :page-size="20"
          :total="total"
          @current-change="changePage"
        />
      </aside>
      <article v-if="selected" class="admin-user-detail">
        <header class="admin-detail-heading"
          ><div
            ><span>当前用户</span><h3>{{ selected.username }}</h3></div
          ><el-tag :type="selected.enabled ? 'success' : 'info'">{{
            selected.enabled ? "启用" : "停用"
          }}</el-tag></header
        >
        <el-alert
          v-if="selected.superAdmin"
          title="内置管理员拥有所有管理菜单权限，不能停用或修改授权。"
          type="info"
          :closable="false"
        />
        <el-form label-position="top" class="admin-account-form">
          <el-form-item label="账号状态"
            ><el-switch
              v-model="enabled"
              :disabled="selected.superAdmin"
              active-text="启用"
              inactive-text="停用"
              aria-label="账号启用状态"
          /></el-form-item>
          <el-form-item v-if="!selected.superAdmin" label="重置密码"
            ><el-input
              v-model="password"
              aria-label="重置密码"
              type="password"
              show-password
              autocomplete="new-password"
              placeholder="留空则保留原密码，8-128 位"
          /></el-form-item>
        </el-form>
        <div class="admin-detail-heading"
          ><div
            ><h3>功能菜单授权</h3
            ><p>勾选允许操作的管理模块；业务模块仍需本机 License 授权。</p></div
          >
          <el-input
            v-model="menuKeyword"
            aria-label="搜索功能菜单"
            placeholder="搜索菜单"
            clearable
            class="admin-menu-search"
          />
        </div>
        <el-table :data="filteredMenus" row-key="key" size="small" class="admin-menu-table">
          <el-table-column label="授权" width="70"
            ><template #default="{ row }"
              ><el-checkbox
                :model-value="checked.includes(row.key)"
                :disabled="selected.superAdmin || inherited(row)"
                :aria-label="'授权 ' + row.label"
                @change="toggle(row.key, $event)" /></template
          ></el-table-column>
          <el-table-column prop="label" label="功能菜单" min-width="150"
            ><template #default="{ row }"
              ><span :class="{ 'admin-child-menu': row.parentKey }">{{ row.label }}</span></template
            ></el-table-column
          >
          <el-table-column prop="description" label="功能说明" min-width="240" />
          <el-table-column label="授权来源" width="140"
            ><template #default="{ row }">{{
              selected.superAdmin
                ? "内置管理员"
                : inherited(row)
                  ? "上级模块授权"
                  : checked.includes(row.key)
                    ? "用户直接授权"
                    : "未授权"
            }}</template></el-table-column
          >
        </el-table>
        <footer class="admin-save-row"
          ><span>授权修改保存后生效；停用和重置密码会使该用户会话失效。</span
          ><el-button type="primary" :disabled="selected.superAdmin" :loading="saving" @click="save"
            >保存用户与授权</el-button
          ></footer
        >
      </article>
      <el-empty v-else description="从左侧选择用户以管理功能菜单授权。" />
    </div>
    <el-dialog v-model="createOpen" title="创建用户" width="480px" :close-on-click-modal="false">
      <el-form label-position="top"
        ><el-form-item label="用户名"
          ><el-input
            v-model.trim="newUsername"
            aria-label="新用户名称"
            autocomplete="off"
            placeholder="3-64 位，以字母开头"
        /></el-form-item>
        <el-form-item label="初始密码"
          ><el-input
            v-model="newPassword"
            aria-label="初始密码"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="8-128 位" /></el-form-item
      ></el-form>
      <el-alert
        title="创建后选择用户配置菜单权限。未授权用户可登录，但无法访问管理模块。"
        type="info"
        :closable="false"
      />
      <template #footer
        ><el-button @click="createOpen = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="create">确认创建</el-button></template
      >
    </el-dialog>
  </section>
</template>
<script src="../scripts/views/AdminUsersView.js"></script>
