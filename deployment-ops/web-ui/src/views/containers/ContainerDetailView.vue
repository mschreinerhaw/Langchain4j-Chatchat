<template>
  <ResourceState :loading="loading && !data" :error="error" @retry="refresh" />
  <template v-if="data">
    <div class="application-banner">
      <div>
        <h2>
          {{ data.inspect.Name?.replace(/^\//, '') }}
          <StateBadge :status="data.inspect.State?.Status" />
        </h2>
        <p class="muted">{{ data.inspect.Config?.Image }} · {{ route.params.id.slice(0, 12) }}</p>
      </div>
      <div>
        <ContainerActions
          :row="{
            ...data.inspect,
            Id: route.params.id,
            State: data.inspect.State?.Status,
            Labels: data.inspect.Config?.Labels,
            nodeId: route.params.node,
          }"
        />
        <RouterLink to="/containers" class="button">
          {{ '\u8fd4\u56de\u5bb9\u5668\u5217\u8868' }}
        </RouterLink>
      </div>
    </div>
    <div class="summary-grid">
      <article class="summary-card">
        <div>
          <small>CPU 使用率</small>
          <strong>{{ percent(data.metrics.cpuPercent) }}</strong>
        </div>
      </article>
      <article class="summary-card">
        <div>
          <small>内存使用</small>
          <strong>{{ bytes(data.metrics.memoryBytes) }}</strong>
        </div>
      </article>
      <article class="summary-card">
        <div>
          <small>网络接收</small>
          <strong>{{ bytes(data.metrics.networkRxBytes) }}</strong>
        </div>
      </article>
      <article class="summary-card">
        <div>
          <small>网络发送</small>
          <strong>{{ bytes(data.metrics.networkTxBytes) }}</strong>
        </div>
      </article>
    </div>
    <PanelCard title="实时日志">
      <LogViewer :node="route.params.node" :id="route.params.id" />
    </PanelCard>
    <ContainerConfiguration
      :key="route.params.id"
      :node="route.params.node"
      :id="route.params.id"
      :image="data.inspect.Image"
    />
  </template>
</template>
<script setup>
  import ContainerActions from '../../components/containers/ContainerActions.vue';
  import ContainerConfiguration from '../../components/containers/ContainerConfiguration.vue';
  import { useRoute } from 'vue-router';
  import { infrastructureApi } from '../../api/infrastructure';
  import { useResource } from '../../composables/useResource';
  import { bytes, percent, dateTime } from '../../utils/format';
  import PanelCard from '../../components/ui/PanelCard.vue';
  import StateBadge from '../../components/ui/StateBadge.vue';
  import ResourceState from '../../components/ui/ResourceState.vue';
  import LogViewer from '../../components/containers/LogViewer.vue';
  const route = useRoute();
  const { data, loading, error, refresh } = useResource(
    async (signal) => {
      const inspect = await infrastructureApi.inspect(route.params.node, route.params.id, signal);
      const metrics = inspect.State?.Running
        ? await infrastructureApi.metrics(route.params.node, route.params.id, signal)
        : {};
      return { inspect, metrics };
    },
    { interval: 15000 }
  );
</script>
