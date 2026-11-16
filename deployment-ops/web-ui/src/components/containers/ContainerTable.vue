<template>
  <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
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
        <template v-if="admin && owned(row)">
          <button v-if="row.State !== 'running'" @click="operate(row, 'start')">启动</button>
          <button v-if="row.State === 'running'" @click="operate(row, 'stop')">停止</button>
          <button v-if="row.State === 'running'" @click="operate(row, 'restart')">重启</button>
          <button
            v-if="row.State !== 'running'"
            class="danger-button"
            @click="operate(row, 'delete')"
          >
            删除
          </button>
        </template>
      </div>
      <small v-if="installationOwned(row)">通过所属应用任务管理生命周期</small>
      <small v-else-if="!owned(row)">外部资源 · 只读发现</small>
    </template>
  </DataTable>
</template>
<script setup>
  import { ref } from 'vue';
  import { useSession } from '../../stores/session';
  import { useOperations } from '../../composables/useOperations';
  import { containerName, percent, bytes, owned, installationOwned } from '../../utils/format';
  import DataTable from '../ui/DataTable.vue';
  import StateBadge from '../ui/StateBadge.vue';
  defineProps({ rows: { type: Array, default: () => [] } });
  const { admin } = useSession(),
    { preview } = useOperations(),
    error = ref('');
  const columns = [
    { key: 'name', label: '容器 / 镜像', width: '30%' },
    { key: 'status', label: '运行状态' },
    { key: 'nodeName', label: '主机' },
    { key: 'cpu', label: 'CPU' },
    { key: 'memory', label: '内存' },
    { key: 'actions', label: '操作' },
  ];
  async function operate(row, action) {
    try {
      error.value = '';
      await preview(row.nodeId, { action: 'container.' + action, id: row.Id });
    } catch (e) {
      error.value = e.message;
    }
  }
</script>
