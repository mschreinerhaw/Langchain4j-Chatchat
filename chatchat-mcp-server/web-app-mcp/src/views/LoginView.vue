<template>
  <main class="login-page">
    <div class="login-grid-bg"></div>

    <header class="login-topbar">
      <div class="login-brand-mini">
        <div class="brand-mark">M</div>
        <strong>LiveMCP</strong>
      </div>
      <span>Enterprise MCP Platform · v1.0.0</span>
    </header>

    <section class="login-shell">
      <section class="login-hero" aria-label="LiveMCP 平台能力">
        <h1>企业级 <span>MCP 服务管理中心</span></h1>
        <p>
          统一管理 MCP Server、工具注册、连接器、网关与 Agent Runtime，让企业内部系统能力安全、可控、可观测地接入 AI Agent。
        </p>

        <div class="runtime-stage">
          <div class="runtime-stage-head">
            <strong>360° Runtime 工作循环</strong>
            <small>模型、工具、记忆、知识与网关形成完整闭环</small>
          </div>
          <div class="runtime-tags">
            <span>Planner</span>
            <span>Memory</span>
            <span>Tool</span>
            <span>Knowledge</span>
            <span>Gateway</span>
          </div>
          <div class="runtime-orbit">
            <svg viewBox="0 0 620 410" preserveAspectRatio="none" aria-hidden="true" focusable="false">
              <path d="M310 62 C514 62 538 205 408 328" />
              <path d="M408 328 C290 390 118 345 132 182" />
              <path d="M132 182 C138 66 252 38 310 62" />
            </svg>
            <div class="orbit-center">LLM</div>
            <div class="orbit-node node-memory">Memory</div>
            <div class="orbit-node node-tool">Tool</div>
            <div class="orbit-node node-gateway">Gateway</div>
            <div class="orbit-node node-workflow">Workflow</div>
            <div class="orbit-node node-planner">Planner</div>
          </div>
        </div>
      </section>

      <aside class="login-card">
        <div class="login-card-head">
          <div class="brand-mark large">M</div>
          <div>
            <h2>LiveMCP</h2>
            <p>Enterprise MCP Platform</p>
          </div>
        </div>

        <el-form class="login-form" label-position="top" @submit.prevent="submit">
          <el-form-item label="用户名">
            <el-input v-model.trim="username" autocomplete="username" size="large" placeholder="请输入用户名" required>
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="password" type="password" autocomplete="current-password" size="large" placeholder="请输入密码" show-password required>
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>

          <div class="login-options">
            <el-checkbox v-model="remember">记住登录</el-checkbox>
            <button class="login-link" type="button">忘记密码？</button>
          </div>

          <el-alert v-if="error" class="login-alert" type="error" :title="error" show-icon :closable="false" />
          <el-button class="login-submit" type="primary" size="large" native-type="submit" :loading="busy">
            登录
          </el-button>
        </el-form>
      </aside>
    </section>

    <footer class="login-copyright">© 2026 LiveMCP · Build · Connect · Deploy</footer>
  </main>
</template>

<script src="../scripts/views/LoginView.js"></script>
