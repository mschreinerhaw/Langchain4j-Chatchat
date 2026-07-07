<template>
  <main class="login-view">
    <section class="login-shell" aria-label="ChatChat 登录首页">
      <section class="login-hero" aria-labelledby="login-hero-title">
        <header class="login-brand">
          <img src="/agent-runtime-logo.svg" alt="Agent Runtime" />
        </header>

        <div class="login-hero-copy">
          <h1 id="login-hero-title">企业统一智能工作平台</h1>
          <p>
            一个入口连接数据、知识、业务与 AI 助手，让查询、分析、协同和办公形成完整闭环。
          </p>
        </div>

        <div class="work-cycle" aria-label="360°智能工作循环">
          <div class="cycle-orbit" aria-hidden="true">
            <div class="cycle-center">
              <strong>AI</strong>
              <span>360° 智能工作循环</span>
            </div>
            <div class="cycle-node node-data">
              <strong>数据</strong>
              <span>业务查询</span>
            </div>
            <div class="cycle-node node-knowledge">
              <strong>知识</strong>
              <span>制度检索</span>
            </div>
            <div class="cycle-node node-work">
              <strong>办公</strong>
              <span>报告生成</span>
            </div>
            <div class="cycle-node node-collab">
              <strong>协同</strong>
              <span>待办审批</span>
            </div>
          </div>
          <div class="cycle-caption">
            <strong>AI 贯穿业务全过程</strong>
            <span>统一访问企业数据、知识库、业务系统和 AI 助手，沉淀可追溯的工作结果。</span>
          </div>
        </div>
      </section>

      <section class="login-card" aria-labelledby="login-title">
        <div class="login-card-head">
          <span class="login-secure-badge">
            <ShieldCheck :size="16" />
            安全登录
          </span>
          <h2 id="login-title">欢迎登录</h2>
          <p>使用企业统一账号访问智能业务平台</p>
        </div>

        <form class="login-form" @submit.prevent="submitLogin">
          <label class="login-field">
            <span class="field-label">账号</span>
            <span class="field-control">
              <UserRound :size="18" />
              <input v-model.trim="form.username" type="text" autocomplete="username" placeholder="请输入企业账号" />
            </span>
          </label>

          <label class="login-field">
            <span class="field-label">密码</span>
            <span class="field-control">
              <LockKeyhole :size="18" />
              <input v-model="form.password" type="password" autocomplete="current-password" placeholder="请输入登录密码" />
            </span>
          </label>

          <label class="login-field">
            <span class="field-label">验证码</span>
            <span class="captcha-row">
              <span class="field-control">
                <BadgeCheck :size="18" />
                <input v-model.trim="form.captcha" type="text" autocomplete="off" maxlength="4" placeholder="输入验证码" />
              </span>
              <button type="button" class="captcha-code" title="刷新验证码" aria-label="刷新验证码" @click="refreshCaptcha">
                <strong>{{ captchaCode }}</strong>
                <RefreshCw :size="14" />
              </button>
            </span>
          </label>

          <div class="login-row">
            <label class="login-option">
              <input v-model="form.rememberAccount" type="checkbox" />
              <span>记住账号</span>
            </label>
            <span class="login-admin-tip">忘记密码请联系管理员</span>
          </div>

          <p v-if="error" class="login-error">{{ error }}</p>

          <button class="login-submit" type="submit" :disabled="loading">
            <LogIn :size="18" />
            <span>{{ loading ? "登录中..." : "进入智能工作台" }}</span>
          </button>
        </form>

        <ul class="login-card-foot">
          <li>企业数据全程权限控制</li>
          <li>所有操作均记录审计日志</li>
          <li>支持统一身份认证</li>
        </ul>
      </section>
    </section>

    <footer class="login-footer">&copy; 2026 ChatChat Enterprise. All rights reserved.</footer>
  </main>
</template>

<script src="../js/views/LoginView.js"></script>
