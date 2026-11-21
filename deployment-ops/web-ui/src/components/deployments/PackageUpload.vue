<template>
  <form @submit.prevent="upload">
    <div class="form-grid">
      <label>
        离线签名应用包
        <input
          type="file"
          accept=".zip"
          :disabled="busy"
          @change="file = $event.target.files[0]"
          required
        />
        <small>包含组件清单、离线镜像、校验和与签名。</small>
      </label>
      <div>
        <label for="package-upload-sha256">发行方 SHA-256</label>
        <input
          id="package-upload-sha256"
          v-model.trim="digest"
          pattern="[a-fA-F0-9]{64}"
          :disabled="busy"
          required
          placeholder="64 位校验和"
        />
        <small>也可选择发行方提供的校验和文件。</small>
        <input
          type="file"
          accept=".sha256,.txt"
          aria-label="读取 SHA-256 文件"
          :disabled="busy"
          @change="readChecksum"
        />
      </div>
    </div>
    <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
    <div v-if="busy || status">
      <div class="progress-track"><span :style="{ width: progress + '%' }" /></div>
      <p class="muted">{{ status }} {{ progress }}%</p>
    </div>
    <div class="toolbar">
      <button class="primary" :disabled="busy">
        {{ busy ? '正在上传并验证…' : '上传并验证应用包' }}
      </button>
      <small>4 MiB 分片；重新选择相同文件及校验和可续传。</small>
    </div>
  </form>
</template>
<script setup>
  import { ref, onUnmounted } from 'vue';
  import { deploymentsApi } from '../../api/deployments';
  const props = defineProps({ node: [Number, String] }),
    emit = defineEmits(['verified']),
    file = ref(null),
    digest = ref(''),
    busy = ref(false),
    error = ref(''),
    status = ref(''),
    progress = ref(0);
  let controller,
    timer,
    disposed = false;
  const delay = () =>
    new Promise((resolve) => {
      timer = setTimeout(resolve, 1500);
    });
  async function readChecksum(event) {
    const checksum = event.target.files[0];
    if (!checksum) return;
    error.value = '';
    try {
      if (checksum.size > 8192) throw Error('请选择发行包对应的 SHA-256 文件。');
      const text = (await checksum.text()).trim();
      const match = text.match(/^([a-f0-9]{64})(?:[ \t]+[^\r\n]+)?$/i);
      if (!match) throw Error('校验和文件应只包含该发行包的一条 SHA-256 记录。');
      digest.value = match[1].toLowerCase();
    } catch (e) {
      error.value = e.message;
    } finally {
      event.target.value = '';
    }
  }
  async function upload() {
    if (!props.node) {
      error.value = '请选择目标主机。';
      return;
    }
    busy.value = true;
    error.value = '';
    controller = new AbortController();
    try {
      let job = await deploymentsApi.upload(
        props.node,
        file.value,
        digest.value.toLowerCase(),
        (p) => {
          progress.value = Math.floor((p.offset / p.size) * 100);
          status.value = '正在上传';
        },
        controller.signal
      );
      while (!['verified', 'failed'].includes(job.status)) {
        status.value = '正在校验签名、清单与镜像';
        await delay();
        if (disposed) return;
        job = await deploymentsApi.uploadStatus(props.node, job.id, controller.signal);
      }
      if (job.status === 'failed') throw Error(job.message || '应用包验证失败');
      status.value = '签名与离线镜像验证通过';
      progress.value = 100;
      emit('verified', job);
    } catch (e) {
      if (e.name !== 'AbortError') error.value = e.message;
    } finally {
      busy.value = false;
    }
  }
  onUnmounted(() => {
    disposed = true;
    controller?.abort();
    clearTimeout(timer);
  });
</script>
