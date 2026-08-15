<template>
  <main class="login-page">
    <div class="login-grid-bg"></div>

    <header class="login-topbar">
      <div class="login-brand-mini">
        <img class="login-product-logo" src="/ruiheng-nexus-logo.svg" alt="睿衡智联 RuiHeng Nexus" />
      </div>
      <span>Enterprise AI Capability Platform · v1.0.0</span>
    </header>

    <section class="login-shell">
      <section class="login-hero" aria-label="睿衡智联平台能力">
        <h1>企业级智能能力 <span>连接中心</span></h1>
        <p>
          统一管理数据、知识、工具与智能服务，让企业系统安全、可信地接入 AI Agent。
        </p>

        <div class="runtime-stage">
          <div class="runtime-stage-head">
            <strong>360° AI 能力协同体系</strong>
            <small>数据、知识、业务能力、智能体与安全治理形成可信闭环</small>
          </div>
          <div class="runtime-tags">
            <span>任务规划</span>
            <span>企业知识</span>
            <span>业务能力</span>
            <span>流程编排</span>
            <span>安全接入</span>
          </div>
          <div class="runtime-orbit">
            <svg viewBox="0 0 620 410" preserveAspectRatio="none" aria-hidden="true" focusable="false">
              <path d="M310 62 C514 62 538 205 408 328" />
              <path d="M408 328 C290 390 118 345 132 182" />
              <path d="M132 182 C138 66 252 38 310 62" />
            </svg>
            <div class="orbit-center">智能体</div>
            <div class="orbit-node node-memory">企业知识</div>
            <div class="orbit-node node-tool">业务能力</div>
            <div class="orbit-node node-gateway">安全接入</div>
            <div class="orbit-node node-workflow">流程编排</div>
            <div class="orbit-node node-planner">任务规划</div>
          </div>
        </div>
      </section>

      <aside class="login-card">
        <div class="login-card-head">
            <img class="brand-mark product-mark large" src="/ruiheng-mark.svg" alt="" aria-hidden="true" />
          <div>
            <h2>睿衡智联</h2>
            <p>RuiHeng Nexus · 企业智能能力统一入口</p>
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

    <!-- <footer class="login-copyright">© 1996-2026 RuiHeng Technology. All Rights Reserved.</footer> -->
    <footer class="login-copyright">© 1996-2026 RuiHeng by Apexsoft Technology. All Rights Reserved.</footer>
  </main>
</template>

<script src="../scripts/views/LoginView.js"></script>
