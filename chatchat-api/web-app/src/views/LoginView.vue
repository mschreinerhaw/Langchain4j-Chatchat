<template>
  <main class="login-view">
    <header class="login-brand" aria-label="Brand">
      <img src="/agent-runtime-logo.svg" alt="Agent Runtime" />
    </header>

    <form class="login-panel" @submit.prevent="submitLogin">
      <label class="login-field">
        <span>账号</span>
        <input v-model.trim="form.username" type="text" autocomplete="username" />
      </label>

      <label class="login-field">
        <span>密码</span>
        <input v-model="form.password" type="password" autocomplete="current-password" />
      </label>

      <label class="login-field">
        <span>验证码</span>
        <div class="captcha-row">
          <input v-model.trim="form.captcha" type="text" autocomplete="off" maxlength="4" />
          <button type="button" class="captcha-code" title="刷新验证码" @click="refreshCaptcha">
            <strong>{{ captchaCode }}</strong>
            <RefreshCw :size="14" />
          </button>
        </div>
      </label>

      <label class="login-option">
        <input v-model="form.rememberPassword" type="checkbox" />
        <span>记住密码</span>
      </label>

      <p v-if="error" class="login-error">{{ error }}</p>

      <button type="submit" :disabled="loading">
        <LogIn :size="17" />
        <span>{{ loading ? "登录中" : "登录" }}</span>
      </button>
    </form>

    <footer class="login-footer">&copy; 2026 ChatChat. All rights reserved.</footer>
  </main>
</template>

<script src="../js/views/LoginView.js"></script>
