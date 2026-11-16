<template>
  <PanelCard title="平台自身升级" description="使用原生离线发行包，升级期间业务容器继续运行。">
    <form @submit.prevent="upload">
      <div class="form-grid">
        <label>
          原生平台发行包 (.zip)
          <input
            type="file"
            accept=".zip"
            @change="file = $event.target.files[0]"
            :disabled="busy"
            required
          />
        </label>
        <label>
          发行包 SHA-256
          <input v-model="digest" pattern="[a-f0-9]{64}" required />
        </label>
      </div>
      <button :disabled="busy">上传并校验发行包</button>
    </form>
    <form v-if="release" @submit.prevent="upgrade" style="margin-top: 22px">
      <dl class="facts">
        <dt>待升级包</dt>
        <dd>{{ release.name }}</dd>
        <dt>校验和</dt>
        <dd>
          <code>{{ release.sha256 }}</code>
        </dd>
      </dl>
      <div class="notice warning" style="margin-top: 20px">
        维护程序将验证发行包、保留旧版本和数据库，再替换平台进程。不会调用业务容器启停命令。
      </div>
      <label>
        输入精确确认文本：
        <code>UPGRADE:{{ release.name }}</code>
        <input v-model="confirmation" required />
      </label>
      <button class="primary" :disabled="busy || confirmation !== 'UPGRADE:' + release.name">
        确认升级平台
      </button>
    </form>
    <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
    <p v-if="message" class="notice success">{{ message }}</p>
  </PanelCard>
</template>
<script setup>
  import { ref } from 'vue';
  import { platformApi } from '../../api/platform';
  import PanelCard from '../ui/PanelCard.vue';
  const file = ref(null),
    digest = ref(''),
    release = ref(null),
    confirmation = ref(''),
    busy = ref(false),
    error = ref(''),
    message = ref('');
  async function upload() {
    busy.value = true;
    error.value = '';
    try {
      release.value = await platformApi.uploadUpgrade(file.value, digest.value);
    } catch (e) {
      error.value = e.message;
    } finally {
      busy.value = false;
    }
  }
  async function upgrade() {
    busy.value = true;
    error.value = '';
    try {
      const result = await platformApi.maintenance({
        action: 'upgrade',
        name: release.value.name,
        confirmation: confirmation.value,
      });
      message.value = '升级任务已提交：' + result.id + '。平台恢复后请刷新页面。';
    } catch (e) {
      error.value = e.message;
    } finally {
      busy.value = false;
    }
  }
</script>
