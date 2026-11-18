<template>
  <ResourceState :error="error" :loading="loading && !data" @retry="refresh" />
  <div v-if="data" class="grid-three">
    <MetricChart
      label="CPU 使用率"
      :value="data.host.cpuPercent"
      :samples="data.history"
      field="cpuPercent"
    />
    <MetricChart
      label="内存使用率"
      :value="data.host.memoryPercent"
      :samples="data.history"
      field="memoryPercent"
      :description="bytes(data.host.memoryUsedBytes) + ' / ' + bytes(data.host.memoryTotalBytes)"
    />
    <MetricChart
      label="数据盘使用率"
      :value="data.host.diskPercent"
      :samples="data.history"
      field="diskPercent"
      :description="(data.host.diskMount || '') + ' · 可用 ' + bytes(data.host.diskAvailableBytes)"
    />
  </div>
</template>
<script setup>
  import { infrastructureApi } from '../../api/infrastructure';
  import { useResource } from '../../composables/useResource';
  import { bytes } from '../../utils/format';
  import MetricChart from './MetricChart.vue';
  import ResourceState from '../ui/ResourceState.vue';
  const props = defineProps({ node: { type: [String, Number], required: true } });
  const { data, error, loading, refresh } = useResource(
    async (signal) => {
      const [host, history] = await Promise.all([
        infrastructureApi.host(props.node, signal),
        infrastructureApi.history(props.node, signal),
      ]);
      return { host, history };
    },
    { interval: 15000 }
  );
</script>
