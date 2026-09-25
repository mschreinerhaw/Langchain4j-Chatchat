<template>
  <el-config-provider size="default">
    <LoginView v-if="!authenticated" @authenticated="handleAuthenticated" />
    <div v-else class="app-shell">
      <aside class="sidebar">
        <div class="sidebar-brand">
          <img class="brand-mark product-mark" :src="`${publicPath}lingdong-mark.svg`" alt="" aria-hidden="true" />
          <div>
            <div class="sidebar-title">灵动智联</div>
            <small>LingDong Nexus</small>
          </div>
        </div>
        <el-menu class="sidebar-menu" :default-active="activeView" :default-openeds="['settings']" @select="activeView = $event">
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
        <el-button class="sidebar-logout" @click="handleLogout">
          <el-icon><SwitchButton /></el-icon>
          <span>退出登录</span>
        </el-button>
      </aside>

      <main class="app-main" :class="{ 'is-system-settings': isSystemSettingsView }">
        <header class="topbar" :class="{ 'is-compact': isSystemSettingsView }">
          <div v-if="!isSystemSettingsView">
            <h1>{{ activeNav.label }}</h1>
            <p>MCP Endpoint: <code>{{ mcpEndpoint }}</code></p>
          </div>
          <div v-else class="topbar-context">系统设置 <span>/</span> {{ activeNav.label }}</div>
          <p v-if="isSystemSettingsView" class="topbar-endpoint">MCP Endpoint: <code>{{ mcpEndpoint }}</code></p>
        </header>

        <KeepAlive include="ApiServicesView,AssetCenterView,DatabaseMcpView,TemplateQueryPublicationsView">
          <component
            :is="activeNav.component"
            :key="activeView"
            v-bind="activeNav.section ? { section: activeNav.section } : {}"
            @notify="notify"
            @error="handleError"
            @result="showResult"
            @password-changed="forceRelogin"
          />
        </KeepAlive>
      </main>

      <ModalPanel :open="resultOpen" :title="resultTitle" wide @close="resultOpen = false">
        <JsonBlock :value="resultValue" />
      </ModalPanel>
    </div>
  </el-config-provider>
</template>

<script src="./scripts/App.js"></script>
