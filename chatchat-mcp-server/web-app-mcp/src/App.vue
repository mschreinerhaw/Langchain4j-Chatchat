<template>
  <el-config-provider size="default">
    <LoginView v-if="!authenticated" @authenticated="handleAuthenticated" />
    <div v-else class="app-shell">
      <aside class="sidebar">
        <div class="sidebar-brand">
          <img
            class="brand-mark product-mark"
            :src="`${publicPath}lingdong-mark.svg`"
            alt=""
            aria-hidden="true"
          />
          <div>
            <div class="sidebar-title">灵动智联</div>
            <small>LingDong Nexus</small>
          </div>
        </div>
        <el-menu
          ref="sidebarMenu"
          class="sidebar-menu"
          :default-active="activeView"
          @select="activeView = $event"
        >
          <template v-for="item in navItems" :key="item.key">
            <el-sub-menu v-if="item.children?.length" :index="item.key">
              <template #title>
                <el-icon><component :is="item.icon" /></el-icon>
                <span>{{ item.label }}</span>
              </template>
              <el-menu-item v-for="child in item.children" :key="child.key" :index="child.key">
                <el-icon><component :is="child.icon" /></el-icon>
                <span>{{ child.label }}</span>
              </el-menu-item>
            </el-sub-menu>
            <el-menu-item v-else :index="item.key">
              <el-icon><component :is="item.icon" /></el-icon>
              <span>{{ item.label }}</span>
            </el-menu-item>
          </template>
        </el-menu>
        <details
          ref="accountCard"
          class="sidebar-account-card"
          @keydown.esc.prevent.stop="$refs.accountCard.open = false; $refs.accountSummary.focus()"
        >
          <summary ref="accountSummary" class="sidebar-account-summary">
            <span class="sidebar-account-avatar" aria-hidden="true">{{ user.slice(0, 2).toUpperCase() }}</span>
            <span class="sidebar-account-identity">
              <small>当前用户</small>
              <strong class="sidebar-account-name" :title="user">{{ user }}</strong>
            </span>
            <span class="sidebar-account-chevron" aria-hidden="true"></span>
          </summary>
          <div class="sidebar-account-actions">
            <button
              type="button"
              class="sidebar-account-action"
              @click="$refs.accountCard.open = false; passwordOpen = true"
            >
              <el-icon><Key /></el-icon><span>修改密码</span>
            </button>
            <button type="button" class="sidebar-account-action is-logout" @click="handleLogout">
              <el-icon><SwitchButton /></el-icon><span>退出登录</span>
            </button>
          </div>
        </details>
      </aside>

      <main class="app-main" :class="{ 'is-system-settings': isSystemSettingsView }">
        <header class="topbar" :class="{ 'is-compact': isSystemSettingsView }">
          <div v-if="!isSystemSettingsView">
            <h1>{{ activeNav.label }}</h1>
            <p
              >MCP Endpoint: <code>{{ mcpEndpoint }}</code></p
            >
          </div>
          <div v-else class="topbar-context">系统设置 <span>/</span> {{ activeNav.label }}</div>
          <p v-if="isSystemSettingsView" class="topbar-endpoint"
            >MCP Endpoint: <code>{{ mcpEndpoint }}</code></p
          >
        </header>

        <el-empty
          v-if="!navItems.length"
          description="当前账号尚未获得管理菜单权限，请联系管理员授权。"
        />
        <KeepAlive v-else include="ApiServicesView,AssetCenterView,DatabaseMcpView">
          <component
            :is="activeNav.component"
            :key="activeView"
            v-bind="{
              ...(activeNav.section ? { section: activeNav.section } : {}),
              ...(activeNav.queryFamily ? { queryFamily: activeNav.queryFamily } : {}),
            }"
            @notify="notify"
            @error="handleError"
            @result="showResult"
            @password-changed="forceRelogin"
          />
        </KeepAlive>
      </main>

      <el-dialog
        v-model="passwordOpen"
        title="修改我的密码"
        width="440px"
        @closed="
          currentPassword = '';
          newPassword = '';
        "
      >
        <el-form label-position="top" @submit.prevent="saveOwnPassword">
          <el-form-item label="当前密码"
            ><el-input
              v-model="currentPassword"
              aria-label="当前密码"
              type="password"
              show-password
              autocomplete="current-password"
          /></el-form-item>
          <el-form-item label="新密码"
            ><el-input
              v-model="newPassword"
              aria-label="新密码"
              type="password"
              show-password
              autocomplete="new-password"
          /></el-form-item>
        </el-form>
        <template #footer
          ><el-button @click="passwordOpen = false">取消</el-button
          ><el-button type="primary" :loading="passwordBusy" @click="saveOwnPassword"
            >保存密码</el-button
          ></template
        >
      </el-dialog>

      <ModalPanel :open="resultOpen" :title="resultTitle" wide @close="resultOpen = false">
        <JsonBlock :value="resultValue" />
      </ModalPanel>
    </div>
  </el-config-provider>
</template>

<script src="./scripts/App.js"></script>
