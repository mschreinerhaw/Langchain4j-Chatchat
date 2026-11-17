<template>
  <PanelCard title="操作审计" description="记录登录、部署审批、资源操作与平台维护的请求及结果。">
    <template #actions><button @click="refresh">刷新审计</button></template>
    <ResourceState :loading="loading && !data.length" :error="error" @retry="refresh" />
    <DataTable :rows="data" :columns="columns">
      <template #created_at="{ row }">{{ dateTime(row.created_at) }}</template>
      <template #target="{ row }">
        <code>{{ row.target }}</code>
      </template>
    </DataTable>
  </PanelCard>
</template>
<script setup>
  import { adminApi } from '../../api/admin';
  import { useResource } from '../../composables/useResource';
  import { dateTime } from '../../utils/format';
  import PanelCard from '../../components/ui/PanelCard.vue';
  import DataTable from '../../components/ui/DataTable.vue';
  import ResourceState from '../../components/ui/ResourceState.vue';
  const { data, loading, error, refresh } = useResource(adminApi.audit, {
    initial: [],
    interval: 15000,
  });
  const columns = [
    { key: 'created_at', label: '时间' },
    { key: 'actor', label: '操作人' },
    { key: 'action', label: '操作' },
    { key: 'target', label: '目标' },
    { key: 'outcome', label: '结果' },
  ];
</script>
