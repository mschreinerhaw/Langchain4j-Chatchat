<template>
  <ResourceState :loading="loading" :error="error" @retry="refresh" />
  <template v-if="data">
    <PanelCard
      title="当前生效的启动参数"
      description="从真实容器读取；模板修改不会覆盖现有配置，密码和令牌已脱敏。"
    >
      <dl class="facts">
        <template
          v-for="key in ['Entrypoint', 'Cmd', 'User', 'WorkingDir', 'environment', 'Healthcheck']"
          :key="key"
        >
          <dt>{{ key }}</dt>
          <dd>
            <pre>{{ JSON.stringify(data.current[key] ?? null, null, 2) }}</pre>
          </dd>
        </template>
        <dt>端口映射</dt>
        <dd>
          <pre>{{ JSON.stringify(data.host.PortBindings, null, 2) }}</pre>
        </dd>
        <dt>数据卷</dt>
        <dd>
          <pre>{{ JSON.stringify(data.mounts, null, 2) }}</pre>
        </dd>
        <dt>网络</dt>
        <dd>{{ data.host.NetworkMode }}</dd>
      </dl>
      <RouterLink class="button" :to="`/images/${node}/${image}/parameters`">
        查看 / 修改镜像默认模板
      </RouterLink>
      <p class="notice">
        CMD、环境变量、端口、用户及挂载在创建时确定。应用组件请通过应用配置 /
        升级流程重新部署，使依赖、回滚和历史记录保持一致。
      </p>
    </PanelCard>
    <PanelCard
      title="修改当前容器资源与重启策略"
      description="只调整当前容器，不重建、不自动重启、不更改数据卷。降低内存可能影响业务，执行前需确认。"
    >
      <form v-if="admin && data.mutable" @submit.prevent="save">
        <div class="form-grid">
          <label>
            内存上限 (MiB)
            <input v-model.number="memory" type="number" min="16" max="131072" required />
          </label>
          <label>
            CPU 核数上限
            <input v-model.number="cpus" type="number" min="0.1" max="64" step="0.01" required />
          </label>
          <label>
            重启策略
            <select v-model="restart" aria-label="重启策略">
              <option value="no">不自动重启</option>
              <option value="unless-stopped">除非手动停止</option>
              <option value="always">始终重启</option>
              <option value="on-failure">失败时重启</option>
            </select>
          </label>
        </div>
        <p v-if="failure" class="notice danger" role="alert">{{ failure }}</p>
        <button class="primary">预览资源配置变更</button>
      </form>
      <p v-else>{{ data.reason || '当前账号无修改权限' }}</p>
    </PanelCard>
  </template>
</template>
<script setup>
  import { ref, watch } from 'vue';
  import { infrastructureApi } from '../../api/infrastructure';
  import { useResource } from '../../composables/useResource';
  import { useSession } from '../../stores/session';
  import { useOperations } from '../../composables/useOperations';
  import ResourceState from '../ui/ResourceState.vue';
  import PanelCard from '../ui/PanelCard.vue';
  const props = defineProps({
      node: { required: true },
      id: { required: true },
      image: { required: true },
    }),
    { admin } = useSession(),
    { preview } = useOperations(),
    memory = ref(256),
    cpus = ref(1),
    restart = ref('unless-stopped'),
    failure = ref('');
  const { data, loading, error, refresh } = useResource((signal) =>
    infrastructureApi.configuration(props.node, props.id, signal)
  );
  watch(data, (value) => {
    if (value) {
      memory.value = value.host.Memory ? Math.ceil(value.host.Memory / 1024 ** 2) : 256;
      cpus.value = value.host.NanoCpus ? value.host.NanoCpus / 1e9 : 1;
      restart.value = value.host.RestartPolicy?.Name || 'no';
    }
  });
  async function save() {
    try {
      failure.value = '';
      await preview(props.node, {
        action: 'container.update',
        id: props.id,
        memoryMiB: memory.value,
        cpus: cpus.value,
        restart: restart.value,
      });
    } catch (e) {
      failure.value = e.message;
    }
  }
</script>
