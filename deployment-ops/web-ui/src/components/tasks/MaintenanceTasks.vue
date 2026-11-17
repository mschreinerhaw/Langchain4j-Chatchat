<template>
  <PanelCard
    title="平台维护任务"
    description="平台升级和 H2 恢复由独立维护进程执行，保留逐步骤的持久化记录。"
  >
    <ResourceState :error="error" :loading="loading && !data.length" @retry="refresh" />
    <DataTable :rows="data" :columns="columns">
      <template #action="{ row }">
        {{ row.action === 'restore' ? 'H2 数据恢复' : '平台版本升级' }}
      </template>
      <template #target="{ row }">
        <RouterLink :to="`/tasks/maintenance/0/${row.id}`">{{ row.target || row.id }}</RouterLink>
      </template>
      <template #status="{ row }"><StateBadge :status="row.status" /></template>
      <template #createdAt="{ row }">{{ dateTime(row.createdAt) }}</template>
    </DataTable>
  </PanelCard>
</template>
<script setup>
  import { maintenanceJobs } from '../../api/platform';
  import { useResource } from '../../composables/useResource';
  import { dateTime } from '../../utils/format';
  import PanelCard from '../ui/PanelCard.vue';
  import DataTable from '../ui/DataTable.vue';
  import ResourceState from '../ui/ResourceState.vue';
  import StateBadge from '../ui/StateBadge.vue';
  const { data, error, loading, refresh } = useResource(maintenanceJobs, {
      initial: [],
      interval: 5000,
    }),
    columns = [
      { key: 'action', label: '维护类型' },
      { key: 'target', label: '作用范围' },
      { key: 'status', label: '状态' },
      { key: 'actor', label: '申请人' },
      { key: 'createdAt', label: '提交时间' },
    ];
</script>
