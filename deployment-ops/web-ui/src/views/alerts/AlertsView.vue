<template>
  <div class="tabs">
    <button :class="{ active: tab === 'current' }" @click="tab = 'current'">
      当前资源异常（{{ alerts.length }}）
    </button>
    <button :class="{ active: tab === 'services' }" @click="tab = 'services'">服务健康检查</button>
    <button :class="{ active: tab === 'history' }" @click="tab = 'history'">
      服务状态变更历史
    </button>
  </div>
  <PanelCard
    v-if="tab === 'current'"
    title="当前运行异常"
    description="来自节点连接、容器状态、健康探针和部署任务的实时状态。"
  >
    <DataTable :rows="alerts" :columns="columns">
      <template #severity="{ row }"><StateBadge :status="row.severity" /></template>
      <template #title="{ row }">
        <RouterLink :to="row.route">{{ row.title }}</RouterLink>
      </template>
      <template #time="{ row }">{{ dateTime(row.time) }}</template>
    </DataTable>
  </PanelCard>
  <PanelCard v-else-if="tab === 'services'" title="业务服务与 DolphinScheduler 多地址监控">
    <ResourceState :loading="loading" :error="error" @retry="refresh" />
    <DataTable :rows="data?.services || []" :columns="serviceColumns">
      <template #status="{ row }">
        <StateBadge :status="row.enabled ? row.status : 'disabled'" />
      </template>
      <template #checked_at="{ row }">{{ dateTime(row.checked_at) }}</template>
    </DataTable>
    <p class="muted">
      在系统管理的服务监控配置中维护 Runtime OS、Coding Agent、MCP、Python 和 DolphinScheduler
      地址。
    </p>
  </PanelCard>
  <PanelCard v-else title="持久化的服务状态变更">
    <ResourceState :error="error" @retry="refresh" />
    <DataTable :rows="data?.history || []" :columns="historyColumns">
      <template #status="{ row }"><StateBadge :status="row.status" /></template>
      <template #created_at="{ row }">{{ dateTime(row.created_at) }}</template>
    </DataTable>
  </PanelCard>
</template>
<script setup>
  import { ref } from 'vue';
  import { useRoute } from 'vue-router';
  import { useCluster } from '../../stores/cluster';
  import { platformApi } from '../../api/platform';
  import { useResource } from '../../composables/useResource';
  import { dateTime } from '../../utils/format';
  import PanelCard from '../../components/ui/PanelCard.vue';
  import DataTable from '../../components/ui/DataTable.vue';
  import StateBadge from '../../components/ui/StateBadge.vue';
  import ResourceState from '../../components/ui/ResourceState.vue';
  const { alerts } = useCluster(),
    tab = ref(
      ['current', 'services', 'history'].includes(useRoute().query.tab)
        ? useRoute().query.tab
        : 'current'
    );
  const { data, loading, error, refresh } = useResource(
    async (signal) => ({
      services: await platformApi.services(signal),
      history: await platformApi.alerts(signal),
    }),
    { interval: 15000 }
  );
  const columns = [
      { key: 'severity', label: '级别' },
      { key: 'title', label: '异常' },
      { key: 'resource', label: '影响资源' },
      { key: 'detail', label: '详情' },
      { key: 'time', label: '采样时间' },
    ],
    serviceColumns = [
      { key: 'name', label: '服务' },
      { key: 'cluster_name', label: '集群' },
      { key: 'url', label: '检测地址' },
      { key: 'status', label: '当前状态' },
      { key: 'latency_ms', label: '延迟 (ms)' },
      { key: 'checked_at', label: '最后检测' },
    ],
    historyColumns = [
      { key: 'name', label: '服务' },
      { key: 'cluster_name', label: '集群' },
      { key: 'status', label: '状态' },
      { key: 'message', label: '说明' },
      { key: 'created_at', label: '发生时间' },
    ];
</script>
