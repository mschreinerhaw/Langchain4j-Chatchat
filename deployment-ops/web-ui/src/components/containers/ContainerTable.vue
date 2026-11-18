<template>
  <DataTable :rows="rows" :columns="columns" row-key="Id">
    <template #name="{ row }">
      <RouterLink :to="`/containers/${row.nodeId}/${row.Id}`">{{ containerName(row) }}</RouterLink>
      <small>{{ row.Image }}</small>
    </template>
    <template #status="{ row }">
      <StateBadge :status="row.State" />
      <small>{{ row.Status }}</small>
    </template>
    <template #cpu="{ row }">{{ percent(row.managerMetrics?.cpuPercent) }}</template>
    <template #memory="{ row }">{{ bytes(row.managerMetrics?.memoryBytes) }}</template>
    <template #actions="{ row }">
      <div class="table-actions">
        <RouterLink class="button" :to="`/containers/${row.nodeId}/${row.Id}`">
          详情 / 日志
        </RouterLink>
        <ContainerActions :row="row" />
      </div>
    </template>
  </DataTable>
</template>
<script setup>
  import ContainerActions from './ContainerActions.vue';
  import { containerName, percent, bytes } from '../../utils/format';
  import DataTable from '../ui/DataTable.vue';
  import StateBadge from '../ui/StateBadge.vue';
  defineProps({ rows: { type: Array, default: () => [] } });
  const columns = [
    { key: 'name', label: '容器 / 镜像', width: '30%' },
    { key: 'status', label: '运行状态' },
    { key: 'nodeName', label: '主机' },
    { key: 'cpu', label: 'CPU' },
    { key: 'memory', label: '内存' },
    { key: 'actions', label: '操作' },
  ];
</script>
