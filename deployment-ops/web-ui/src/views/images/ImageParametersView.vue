<template>
  <ResourceState :loading="loading && !data" :error="error" @retry="refresh" />
  <template v-if="data">
    <div class="application-banner">
      <h2>镜像默认启动参数</h2>
      <RouterLink class="button" to="/images">返回镜像列表</RouterLink>
    </div>
    <p>
      <code>{{ route.params.id }}</code>
    </p>
    <PanelCard
      title="镜像内置默认值"
      description="从当前 Docker 镜像读取。修改平台模板不会改写镜像，也不会自动重启现有容器。"
    >
      <dl class="facts">
        <template
          v-for="key in [
            'Entrypoint',
            'Cmd',
            'User',
            'WorkingDir',
            'ExposedPorts',
            'Volumes',
            'Healthcheck',
            'environment',
          ]"
          :key="key"
        >
          <dt>{{ key }}</dt>
          <dd>
            <pre>{{ JSON.stringify(data.image[key] ?? null, null, 2) }}</pre>
          </dd>
        </template>
      </dl>
    </PanelCard>
    <PanelCard
      title="平台创建默认模板"
      description="保存到本机 H2，按不可变镜像 ID 区分；新建容器时加载，可逐次覆盖。密码不明文回显。"
    >
      <form v-if="admin" @submit.prevent="save">
        <LaunchParameterFields ref="fields" :initial="data.defaults" />
        <p class="notice">
          数据卷需在创建时明确选择；镜像声明的持久化目录必须完整映射。运行中的
          CMD、环境变量、端口和挂载需重新部署才会生效。
        </p>
        <p v-if="failure" class="notice danger" role="alert">{{ failure }}</p>
        <button class="primary">预览并保存默认模板</button>
      </form>
      <p v-else>当前账号可以查看默认参数，修改需要管理员权限。</p>
    </PanelCard>
  </template>
</template>
<script setup>
  import { ref } from 'vue';
  import { useRoute } from 'vue-router';
  import { infrastructureApi } from '../../api/infrastructure';
  import { useResource } from '../../composables/useResource';
  import { useSession } from '../../stores/session';
  import { useOperations } from '../../composables/useOperations';
  import PanelCard from '../../components/ui/PanelCard.vue';
  import ResourceState from '../../components/ui/ResourceState.vue';
  import LaunchParameterFields from '../../components/containers/LaunchParameterFields.vue';
  const route = useRoute(),
    { admin } = useSession(),
    { preview } = useOperations(),
    fields = ref(null),
    failure = ref('');
  const { data, loading, error, refresh } = useResource((signal) =>
    infrastructureApi.imageParameters(route.params.node, route.params.id, signal)
  );
  async function save() {
    try {
      failure.value = '';
      await preview(route.params.node, {
        action: 'image.defaults',
        id: route.params.id,
        configuration: fields.value.read(),
      });
    } catch (e) {
      failure.value = e.message;
    }
  }
</script>
