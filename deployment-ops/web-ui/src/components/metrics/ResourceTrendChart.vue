<template>
  <ResourceState :error="error" :loading="loading && !data" @retry="refresh" />
  <template v-if="data">
    <div class="chart-legend">
      <span>
        <i />
        CPU {{ percent(data.host.cpuPercent) }}
      </span>
      <span>
        <i class="memory" />
        内存 {{ percent(data.host.memoryPercent) }}
      </span>
    </div>
    <svg
      class="resource-trend"
      viewBox="0 0 360 150"
      role="img"
      aria-label="主机 CPU 与内存真实采样趋势"
    >
      <g v-for="value in [0, 25, 50, 75, 100]" :key="value">
        <line
          x1="34"
          :y1="120 - value"
          x2="352"
          :y2="120 - value"
          stroke="#e8eef6"
          stroke-dasharray="3 3"
        />
        <text x="0" :y="123 - value" fill="#8292a8" font-size="9">{{ value }}%</text>
      </g>
      <polyline :points="points('memoryPercent')" fill="none" stroke="#64b5f5" stroke-width="2" />
      <polyline :points="points('cpuPercent')" fill="none" stroke="#1678ea" stroke-width="2" />
      <text
        v-for="(sample, index) in ticks"
        :key="index"
        :x="34 + index * 159"
        y="143"
        :text-anchor="index === 0 ? 'start' : index === 2 ? 'end' : 'middle'"
        fill="#8292a8"
        font-size="8"
      >
        {{ time(sample) }}
      </text>
    </svg>
    <div class="resource-footnotes">
      <span>
        数据盘
        <b>{{ percent(data.host.diskPercent) }}</b>
      </span>
      <span>可用 {{ bytes(data.host.diskAvailableBytes) }}</span>
      <span>接收 {{ bytes(data.host.networkRxPerSecond) }}/s</span>
    </div>
  </template>
</template>
<script setup>
  import { computed } from 'vue';
  import { infrastructureApi } from '../../api/infrastructure';
  import { useResource } from '../../composables/useResource';
  import { bytes, percent } from '../../utils/format';
  import ResourceState from '../ui/ResourceState.vue';
  const props = defineProps({ node: [String, Number] });
  const { data, error, loading, refresh } = useResource(
    async (signal) => ({
      host: await infrastructureApi.host(props.node, signal),
      history: await infrastructureApi.history(props.node, signal),
    }),
    { interval: 15000 }
  );
  const samples = computed(() => (data.value?.history || []).slice(-60));
  function points(field) {
    return samples.value
      .map((sample, index) =>
        sample[field] == null
          ? null
          : `${34 + (index / Math.max(1, samples.value.length - 1)) * 318},${
              120 - Math.max(0, Math.min(100, sample[field]))
            }`
      )
      .filter(Boolean)
      .join(' ');
  }
  const ticks = computed(() => {
    const list = samples.value;
    return list.length ? [list[0], list[Math.floor(list.length / 2)], list.at(-1)] : [];
  });
  const time = (sample) =>
    new Date((sample.at || sample.collectedAt) * 1000).toLocaleTimeString('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    });
</script>
