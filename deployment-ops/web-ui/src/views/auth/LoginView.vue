<template>
  <main class="login-page">
    <section class="login-intro">
      <span class="brand-mark">D</span>
      <p class="eyebrow">DEPLOYMENT OPS</p>
      <h1>
        管理应用的
        <br />
        整个运行周期。
      </h1>
      <p>
        从离线安装、组件编排到运行监控与故障恢复，
        <br />
        在同一个工作台中完成。
      </p>
      <div class="login-points">
        <span>本地 Spring Boot 进程</span>
        <span>嵌入式 H2 持久化</span>
        <span>独立于业务环境启动</span>
      </div>
    </section>
    <section class="login-card">
      <p class="eyebrow">ADMINISTRATOR ACCESS</p>
      <h2>登录运维工作台</h2>
      <p class="muted">使用首次安装时配置的管理员账号。</p>
      <form @submit.prevent="login">
        <label>
          账号
          <input v-model="credentials.username" autocomplete="username" required />
        </label>
        <label>
          密码
          <input
            v-model="credentials.password"
            type="password"
            autocomplete="current-password"
            required
          />
        </label>
        <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
        <button class="primary full-width" :disabled="busy">
          {{ busy ? '正在登录…' : '登录' }}
        </button>
      </form>
      <small class="muted">登录和管理操作会记录在平台审计日志中。</small>
    </section>
  </main>
</template>
<script setup>
  import { reactive, ref } from 'vue';
  import { useRouter, useRoute } from 'vue-router';
  import { useSession } from '../../stores/session';
  const credentials = reactive({ username: 'admin', password: '' }),
    error = ref(''),
    busy = ref(false),
    router = useRouter(),
    route = useRoute(),
    session = useSession();
  async function login() {
    busy.value = true;
    error.value = '';
    try {
      await session.login(credentials);
      credentials.password = '';
      const destination = String(route.query.redirect || '/overview');
      router.replace(
        destination.startsWith('/') && !destination.startsWith('//') ? destination : '/overview'
      );
    } catch (e) {
      error.value = e.message;
    } finally {
      busy.value = false;
    }
  }
</script>
