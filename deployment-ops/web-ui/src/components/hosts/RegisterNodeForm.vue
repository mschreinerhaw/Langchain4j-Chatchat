<template>
  <PanelCard
    title="注册服务器执行器"
    description="执行器须已通过离线安装配置；远程连接必须使用 HTTPS。"
  >
    <form @submit.prevent="save">
      <div class="form-grid">
        <label>
          服务器名称
          <input v-model="form.name" maxlength="255" required />
        </label>
        <label>
          执行器地址
          <input v-model="form.url" type="url" placeholder="https://host:19242" required />
        </label>
        <label class="full">
          执行器访问令牌
          <input v-model="form.token" type="password" autocomplete="off" required />
          <small>从目标服务器受保护的配置中获取，保存后不回显。</small>
        </label>
      </div>
      <label>
        <input v-model="confirmed" type="checkbox" required />
        我确认该执行器已安装，且有权管理其声明的资源范围。
      </label>
      <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
      <div class="form-actions">
        <button type="button" @click="$emit('cancel')">取消</button>
        <button class="primary" :disabled="busy || !confirmed">注册服务器</button>
      </div>
    </form>
  </PanelCard>
</template>
<script setup>
  import { ref, reactive } from 'vue';
  import { post } from '../../api/client';
  import { useCluster } from '../../stores/cluster';
  import PanelCard from '../ui/PanelCard.vue';
  const emit = defineEmits(['cancel', 'saved']),
    cluster = useCluster(),
    form = reactive({ name: '', url: '', token: '' }),
    busy = ref(false),
    confirmed = ref(false),
    error = ref('');
  async function save() {
    busy.value = true;
    error.value = '';
    try {
      await post('/nodes', { ...form });
      form.token = '';
      await cluster.refresh();
      emit('saved');
    } catch (e) {
      error.value = e.message;
    } finally {
      busy.value = false;
    }
  }
</script>
