<template>
  <PanelCard
    title="创建业务容器"
    description="使用本地镜像，先预览资源计划，再确认执行。创建后需明确执行启动操作。"
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
          <input v-model="form.name" pattern="[a-zA-Z0-9][a-zA-Z0-9_.-]+" required />
        </label>
        <label class="full">
          本地镜像
          <select v-model="form.image" aria-label="本地镜像" required>
            <option value="" disabled>选择本地不可变镜像</option>
            <option v-for="image in images" :key="image.Id" :value="image.Id">
              {{ image.RepoTags?.join(', ') || image.Id }}
            </option>
          </select>
        </label>
        <label>
          应用分组
          <input v-model="form.application" />
        </label>
        <label>
          运行用户 UID:GID
          <input v-model="form.user" placeholder="例如 1000:1000" pattern="[0-9]+:[0-9]+" />
        </label>
        <label>
          内存上限 (MiB)
          <input v-model.number="form.memoryMiB" type="number" min="16" required />
        </label>
        <label>
          CPU 核数上限
          <input v-model.number="form.cpus" type="number" min="0.1" step="0.1" required />
        </label>
        <label class="full">
          启动参数
          <textarea v-model="command" placeholder="每行一个参数；留空使用镜像默认命令"></textarea>
        </label>
        <label>
          环境变量
          <textarea v-model="environment" placeholder="每行一个 KEY=VALUE"></textarea>
        </label>
        <label>
          端口映射
          <textarea v-model="ports" placeholder="每行一个 主机端口:容器端口"></textarea>
        </label>
      </div>
      <VolumeMountFields v-model="volumes" :node="node" />
      <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
      <div class="form-actions">
        <button type="button" @click="$emit('cancel')">取消</button>
        <button class="primary" :disabled="busy">预览创建计划</button>
      </div>
    </form>
  </PanelCard>
</template>
<script setup>
  import { reactive, ref, computed, watch } from 'vue';
  import { useCluster } from '../../stores/cluster';
  import { useOperations } from '../../composables/useOperations';
  import PanelCard from '../ui/PanelCard.vue';
  import VolumeMountFields from './VolumeMountFields.vue';
  const volumes = ref([]);
  const emit = defineEmits(['cancel', 'planned']),
    { nodes, inventory } = useCluster(),
    { preview, busy } = useOperations(),
    node = ref(nodes.value[0]?.id),
    error = ref(''),
    form = reactive({ name: '', image: '', application: '', user: '', memoryMiB: 512, cpus: 1 }),
    command = ref(''),
    environment = ref(''),
    ports = ref('');
  const images = computed(
    () => inventory.value.find((n) => n.id === Number(node.value))?.data?.images || []
  );
  watch(node, () => (form.image = ''));
  async function submit() {
    try {
      error.value = '';
      const env = {};
      for (const line of environment.value.split('\n').filter(Boolean)) {
        const index = line.indexOf('=');
        if (index < 1) throw Error('环境变量必须使用 KEY=VALUE 格式。');
        env[line.slice(0, index)] = line.slice(index + 1);
      }
      const mappings = ports.value
        .split('\n')
        .filter(Boolean)
        .map((line) => {
          const [host, container, ...extra] = line.trim().split(':');
          if (extra.length || !/^\d+$/.test(host) || !/^\d+$/.test(container))
            throw Error('端口映射必须使用 主机端口:容器端口 格式。');
          return { host: Number(host), container: Number(container), bind: '0.0.0.0' };
        });
      const spec = {
        action: 'container.create',
        ...form,
        environment: env,
        ports: mappings,
        volumes: volumes.value,
        command: command.value ? command.value.split('\n') : [],
      };
      if (!spec.user) delete spec.user;
      await preview(node.value, spec);
      emit('planned');
    } catch (e) {
      error.value = e.message;
    }
  }
</script>
