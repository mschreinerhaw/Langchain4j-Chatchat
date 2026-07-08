<template>
  <el-config-provider size="default">
    <LoginView v-if="!authenticated" @authenticated="handleAuthenticated" />
    <div v-else class="app-shell">
      <aside class="sidebar">
        <div class="sidebar-brand">
          <div class="brand-mark">M</div>
          <div>
            <div class="sidebar-title">LiveMCP</div>
            <small>MCP 服务管理中心</small>
          </div>
        </div>
        <el-menu class="sidebar-menu" :default-active="activeView" @select="activeView = $event">
          <el-menu-item v-for="item in navItems" :key="item.key" :index="item.key">
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </el-menu-item>
        </el-menu>
        <el-button class="sidebar-logout" @click="handleLogout">
          <el-icon><SwitchButton /></el-icon>
          <span>退出登录</span>
        </el-button>
      </aside>

      <main class="app-main">
        <header class="topbar">
          <div>
            <h1>{{ activeNav.label }}</h1>
            <p>MCP Endpoint: <code>{{ mcpEndpoint }}</code></p>
          </div>
        </header>

        <component
          :is="activeNav.component"
          @notify="notify"
          @error="handleError"
          @result="showResult"
          @password-changed="forceRelogin"
        />
      </main>

      <ModalPanel :open="resultOpen" :title="resultTitle" wide @close="resultOpen = false">
        <JsonBlock :value="resultValue" />
      </ModalPanel>
    </div>
  </el-config-provider>
</template>

<script src="./scripts/App.js"></script>
