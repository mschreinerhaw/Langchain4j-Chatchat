<template>
  <main class="login-page">
    <section class="login-intro">
      <span class="brand-mark">D</span>
      <p class="eyebrow">DEPLOYMENT OPS</p>
      <h1>
        选择存储位置
        <br />
        开始安装平台。
      </h1>
      <p>支持单盘服务器，也支持独立数据盘。先检查实际文件系统和容量，再初始化管理数据库。</p>
      <div class="login-points">
        <span>本地 Spring Boot + H2</span>
        <span>无需先启动业务系统</span>
        <span>离线镜像与应用编排</span>
      </div>
    </section>
    <section class="login-form-card">
      <h2>首次安装 · 存储配置</h2>
      <form @submit.prevent="check">
        <label>
          平台存储根目录
          <input v-model="root" required placeholder="/opt/deployment-ops" />
        </label>
        <label>
          应用数据目录
          <input v-model="runtime" required placeholder="/opt/deployment-ops/r" />
        </label>
        <label>
          存储方式
          <select v-model="mode" aria-label="存储方式">
            <option value="directory">使用目录（支持系统盘 / 单盘）</option>
            <option value="mount">要求独立挂载（可选）</option>
          </select>
        </label>
        <label v-if="mode === 'mount'">
          必须存在的挂载点
          <input v-model="mount" placeholder="例如 /mnt/disk01" required />
        </label>
        <label>
          安装令牌
          <input
            v-model="token"
            type="password"
            autocomplete="off"
            required
            placeholder="从 java -jar 启动日志获取"
          />
        </label>
        <p class="muted">
          管理数据库保存到 {{ root }}/data。目录不会因容量不足而自动改到别处；磁盘不足时会明确报错。
        </p>
        <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
        <button class="primary full-width" :disabled="busy || applying">检查存储配置</button>
      </form>
      <template v-if="review">
        <dl class="facts">
          <dt>数据目录</dt>
          <dd>{{ review.dataDirectory }}</dd>
          <dt>应用目录</dt>
          <dd>{{ review.runtimeDirectory }}</dd>
          <dt>实际挂载点</dt>
          <dd>{{ review.mountPoint }}</dd>
          <dt>文件系统</dt>
          <dd>{{ review.filesystem }}</dd>
          <dt>可用容量</dt>
          <dd>{{ bytes(review.freeBytes) }}</dd>
          <dt>存储位置</dt>
          <dd>{{ review.systemFilesystem ? '系统盘文件系统' : '独立数据文件系统' }}</dd>
        </dl>
        <p class="notice">
          已明确使用上述目录。平台管理数据和业务应用数据分别存放，平台停止不会级联停止业务容器。
        </p>
        <button class="primary full-width" :disabled="busy || applying" @click="apply">
          {{ applying ? '正在初始化平台…' : '确认目录并初始化平台' }}
        </button>
      </template>
    </section>
  </main>
</template>
<script setup>
  import { onMounted, ref, watch } from 'vue';
  import { get, post } from '../../api/client';
  import { bytes } from '../../utils/format';
  const root = ref('/opt/deployment-ops'),
    runtime = ref('/opt/deployment-ops/r'),
    mode = ref('directory'),
    mount = ref(''),
    token = ref(''),
    busy = ref(false),
    applying = ref(false),
    review = ref(null),
    error = ref('');
  let reviewed = null;
  watch(root, (next, previous) => {
    if (runtime.value === previous + '/r') runtime.value = next.replace(/\/$/, '') + '/r';
    review.value = null;
  });
  watch([runtime, mode, mount], () => {
    review.value = null;
  });
  onMounted(async () => {
    try {
      const status = await get('/storage/status');
      if (!status.requiresConfiguration) {
        location.replace('/#/login');
        return;
      }
      root.value = status.defaultDataDirectory.replace(/\/data$/, '');
      runtime.value = status.defaultRuntimeDirectory;
    } catch (e) {
      error.value = e.message;
    }
  });
  function selection() {
    return {
      token: token.value,
      dataDirectory: root.value.replace(/\/$/, '') + '/data',
      runtimeDirectory: runtime.value,
      requiredMount: mode.value === 'mount' ? mount.value : '',
    };
  }
  async function check() {
    busy.value = true;
    error.value = '';
    review.value = null;
    try {
      reviewed = selection();
      review.value = await post('/storage/check', reviewed);
    } catch (e) {
      error.value = e.message;
    } finally {
      busy.value = false;
    }
  }
  async function apply() {
    applying.value = true;
    error.value = '';
    try {
      if (JSON.stringify(selection()) !== JSON.stringify(reviewed))
        throw Error('配置已修改，请重新检查');
      await post('/storage/apply', reviewed);
      sessionStorage.setItem('ops-install-authorization', token.value);
      for (let i = 0; i < 120; i++) {
        await new Promise((resolve) => setTimeout(resolve, 1000));
        try {
          const response = await fetch('/api/storage/status', { cache: 'no-store' });
          if (response.ok && !(await response.json()).requiresConfiguration) {
            location.replace('/#/login');
            return;
          }
        } catch {}
      }
      throw Error('初始化尚未完成，请查看平台启动日志后刷新');
    } catch (e) {
      error.value = e.message;
      applying.value = false;
    }
  }
</script>
