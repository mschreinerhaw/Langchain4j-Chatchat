<template>
  <main>
    <p class="brand">RUNTIME OS · DEPLOYMENT MANAGER</p>
    <h1>应用部署与运维</h1>
    <p>Spring Boot + Vue 3 CLI 迁移开发版</p>
    <section>
      <h2>控制面连接</h2>
      <p role="status">{{ status }}</p>
      <button :disabled="loading" @click="check">检查连接</button>
      <small>此结果只表示管理服务可连接，不代表 Runtime OS 应用健康。</small>
    </section>
    <section>
      <h2>当前开发状态</h2>
      <p>应用发布库、安装向导、组件健康和运维任务正在迁移。此页面不提供尚未接通的部署操作。</p>
    </section>
  </main>
</template>
<script setup>
import { ref, onMounted } from 'vue';
const status = ref('尚未检查');
const loading = ref(false);
async function check() {
  loading.value = true;
  try {
    const response = await fetch('/actuator/health', { signal: AbortSignal.timeout(5000) });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const result = await response.json();
    status.value = `管理服务：${result.status}`;
  } catch (error) { status.value = `连接失败：${error.message}`; }
  finally { loading.value = false; }
}
onMounted(check);
</script>
<style>
body{margin:0;background:#f5f7fa;color:#203043;font:16px system-ui,sans-serif}main{max-width:960px;margin:64px auto;padding:0 24px}.brand{color:#466e96;letter-spacing:2px;font-size:13px}h1{font-size:32px}section{margin:24px 0;background:white;border:1px solid #dde4ec;border-radius:8px;padding:24px}h2{font-size:20px}small{display:block;margin-top:16px;color:#617181}button{background:#245e9a;color:white;border:0;border-radius:4px;padding:10px 20px;cursor:pointer}button:disabled{opacity:.5}
</style>
