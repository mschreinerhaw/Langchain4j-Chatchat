<template>
  <main class="login-view">
    <header class="login-brand" aria-label="LiveRuntime">
      <img src="/agent-runtime-logo.svg" alt="LiveRuntime" />
    </header>

    <section class="login-shell" aria-label="LiveRuntime 登录首页">
      <section class="login-hero" aria-label="360°智能工作循环">
        <div class="ai-cycle" aria-label="360°智能工作循环">
          <div class="cycle-halo"></div>
          <svg class="cycle-svg" viewBox="0 0 640 640" aria-hidden="true" focusable="false">
            <defs>
              <linearGradient id="loginCycleGradient" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0%" stop-color="#2563eb" />
                <stop offset="45%" stop-color="#2f7df6" />
                <stop offset="76%" stop-color="#14b8a6" />
                <stop offset="100%" stop-color="#22c55e" />
              </linearGradient>
              <marker id="loginCycleArrowHead" markerWidth="22" markerHeight="22" refX="18" refY="11" orient="auto" markerUnits="userSpaceOnUse">
                <path d="M2,2 L20,11 L2,20 Q7,11 2,2 Z" fill="#22c55e"></path>
              </marker>
            </defs>
            <circle class="ring-bg" cx="320" cy="320" r="232"></circle>
            <path class="ring-main" marker-end="url(#loginCycleArrowHead)" d="M320 88 A232 232 0 1 1 319.8 88"></path>
            <path class="ring-light" d="M320 88 A232 232 0 1 1 319.8 88"></path>
          </svg>

          <div class="center-card">
            <div class="ai-title">AI</div>
            <div class="ai-desc">360° 智能工作循环</div>
          </div>

          <div class="node-card node-top" :class="{ active: cycleFeatureIndex === 0 }" @mouseenter="setCycleFeature(0)">
            <div class="node-icon"></div>
            <div class="node-title">数据</div>
            <div class="node-desc">业务查询</div>
          </div>

          <div class="node-card node-right green" :class="{ active: cycleFeatureIndex === 1 }" @mouseenter="setCycleFeature(1)">
            <div class="node-icon"></div>
            <div class="node-title">知识</div>
            <div class="node-desc">制度检索</div>
          </div>

          <div class="node-card node-bottom green" :class="{ active: cycleFeatureIndex === 2 }" @mouseenter="setCycleFeature(2)">
            <div class="node-icon"></div>
            <div class="node-title">办公</div>
            <div class="node-desc">报告生成</div>
          </div>

          <div class="node-card node-left" :class="{ active: cycleFeatureIndex === 3 }" @mouseenter="setCycleFeature(3)">
            <div class="node-icon"></div>
            <div class="node-title">协同</div>
            <div class="node-desc">待办审批</div>
          </div>

          <article class="cycle-info-card" :key="activeCycleFeature.title">
            <h3>{{ activeCycleFeature.title }}</h3>
            <p>{{ activeCycleFeature.detail }}</p>
            <div class="cycle-tag-row">
              <span v-for="tag in activeCycleFeature.tags" :key="tag">{{ tag }}</span>
            </div>
          </article>
        </div>
      </section>

      <section class="login-card" aria-labelledby="login-title">
        <div class="login-card-head">
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

    <footer class="login-footer">&copy; 2026 LiveRuntime. All rights reserved.</footer>
  </main>
</template>

<script src="../js/views/LoginView.js"></script>
