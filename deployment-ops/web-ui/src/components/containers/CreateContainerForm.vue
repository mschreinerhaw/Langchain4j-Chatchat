<template>
  <PanelCard
    title="创建业务容器"
    description="选择镜像后加载默认模板。审阅后创建，再明确启动；不修改现有容器。"
  >
    <form @submit.prevent="submit">
      <div class="form-grid">
        <label>
          目标主机
          <select v-model="node" required>
            <option v-for="n in nodes" :key="n.id" :value="n.id">{{ n.name }}</option>
          </select>
        </label>
        <label>
          容器名称
          <input v-model="name" pattern="[a-z][a-z0-9_.-]{2,63}" required />
        </label>
        <label class="full">
          本地镜像
          <select v-model="image" aria-label="本地镜像" required>
            <option value="" disabled>选择本地不可变镜像</option>
            <option v-for="item in images" :key="item.Id" :value="item.Id">
              {{ item.RepoTags?.join(', ') || item.Id }}
            </option>
          </select>
        </label>
        <label>
          应用分组
          <input v-model="application" />
        </label>
      </div>
      <p v-if="fetching" role="status">正在加载镜像默认参数…</p>
      <template v-if="parameters">
        <RouterLink class="button" :to="`/images/${node}/${image}/parameters`">
          查看 / 修改镜像默认模板
        </RouterLink>
        <details>
          <summary>查看镜像内置默认值</summary>
          <pre>{{ JSON.stringify(parameters.image, null, 2) }}</pre>
        </details>
        <LaunchParameterFields ref="fields" :initial="parameters.defaults" />
        <VolumeMountFields v-model="volumes" :node="node" />
      </template>
      <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
      <div class="form-actions">
        <button type="button" @click="$emit('cancel')">取消</button>
        <button class="primary" :disabled="busy || fetching || !parameters">预览创建计划</button>
      </div>
    </form>
  </PanelCard>
</template>
<script setup>
  import { ref, computed, watch } from 'vue';
  import { useCluster } from '../../stores/cluster';
  import { useOperations } from '../../composables/useOperations';
  import { infrastructureApi } from '../../api/infrastructure';
  import PanelCard from '../ui/PanelCard.vue';
  import VolumeMountFields from './VolumeMountFields.vue';
  import LaunchParameterFields from './LaunchParameterFields.vue';
  const emit = defineEmits(['cancel', 'planned']),
    { nodes, inventory } = useCluster(),
    { preview, busy } = useOperations();
  const node = ref(nodes.value[0]?.id),
    name = ref(''),
    image = ref(''),
    application = ref(''),
    volumes = ref([]),
    parameters = ref(null),
    fetching = ref(false),
    error = ref(''),
    fields = ref(null);
  const images = computed(
    () => inventory.value.find((n) => n.id === Number(node.value))?.data?.images || []
  );
  watch(node, () => {
    image.value = '';
  });
  let generation = 0;
  watch(image, async (value) => {
    const current = ++generation;
    parameters.value = null;
    volumes.value = [];
    if (!value) {
      fetching.value = false;
      return;
    }
    fetching.value = true;
    error.value = '';
    try {
      const result = await infrastructureApi.imageParameters(node.value, value);
      if (current === generation) {
        parameters.value = result;
        volumes.value = result.defaults.volumes || [];
      }
    } catch (e) {
      if (current === generation) error.value = e.message;
    } finally {
      if (current === generation) fetching.value = false;
    }
  });
  async function submit() {
    try {
      error.value = '';
      await preview(node.value, {
        action: 'container.create',
        name: name.value,
        image: image.value,
        application: application.value,
        ...fields.value.read(),
        volumes: volumes.value,
      });
      emit('planned');
    } catch (e) {
      error.value = e.message;
    }
  }
</script>
