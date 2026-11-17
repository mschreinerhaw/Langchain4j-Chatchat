<template>
  <div class="toolbar">
    <p class="muted">以应用环境组织服务组件、配置、任务与运行状态。</p>
    <RouterLink v-if="admin" to="/deploy" class="button primary">＋ 部署应用</RouterLink>
  </div>
  <PanelCard title="应用环境">
    <DataTable :rows="applications" :columns="columns" row-key="key">
      <template #environment="{ row }">
        <RouterLink :to="detail(row)">{{ row.environment }}</RouterLink>
        <small>{{ row.job?.packageName || '根据现有容器标签发现' }}</small>
      </template>
      <template #status="{ row }"><StateBadge :status="row.status" /></template>
      <template #components="{ row }">{{ row.services.length }} 个服务组件</template>
      <template #management="{ row }">
        {{ row.managed ? '平台签名部署' : '外部管理 · 只读发现' }}
      </template>
      <template #action="{ row }"><RouterLink :to="detail(row)">进入应用</RouterLink></template>
    </DataTable>
    <ResourceState
      :empty="!applications.length && !loading"
      title="尚未发现应用"
      description="可导入离线发行包部署 Runtime OS；现有业务应用会通过容器标签发现。"
    />
  </PanelCard>
</template>
<script setup>
  import { useCluster } from '../../stores/cluster';
  import { useSession } from '../../stores/session';
  import PanelCard from '../../components/ui/PanelCard.vue';
  import DataTable from '../../components/ui/DataTable.vue';
  import StateBadge from '../../components/ui/StateBadge.vue';
  import ResourceState from '../../components/ui/ResourceState.vue';
  const { applications, loading } = useCluster(),
    { admin } = useSession();
  const detail = (row) => `/applications/${row.nodeId}/${encodeURIComponent(row.environment)}`;
  const columns = [
    { key: 'environment', label: '应用环境' },
    { key: 'status', label: '状态' },
    { key: 'nodeName', label: '主机' },
    { key: 'components', label: '组件' },
    { key: 'management', label: '管理来源' },
    { key: 'action', label: '操作' },
  ];
</script>
