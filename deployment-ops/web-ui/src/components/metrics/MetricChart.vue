<template>
  <article class="metric-card">
    <div class="metric-heading">
      <span>{{ label }}</span>
      <span class="muted">{{ unit }}</span>
    </div>
    <strong>{{ value == null ? '暂无采样' : Number(value).toFixed(1) + (unit || '') }}</strong>
    <svg
      viewBox="0 0 300 76"
      preserveAspectRatio="none"
      role="img"
      :aria-label="label + '历史趋势'"
    >
      <line x1="0" y1="72" x2="300" y2="72" stroke="var(--border)" />
      <polygon v-if="points" :points="'0,76 ' + points + ' 300,76'" fill="var(--accent-wash)" />
      <polyline
        v-if="points"
        :points="points"
        fill="none"
        stroke="var(--accent)"
        stroke-width="2"
        vector-effect="non-scaling-stroke"
      />
    </svg>
    <small class="muted">{{ description || '来自主机执行器的真实采样' }}</small>
  </article>
</template>
<script setup>
  import { computed } from 'vue';
  const props = defineProps({
    label: String,
    value: Number,
    unit: { type: String, default: '%' },
    samples: { type: Array, default: () => [] },
    field: String,
    description: String,
  });
  const points = computed(() => {
    const values = props.samples
      .map((v) => v[props.field])
      .filter((v) => v != null)
      .slice(-60);
    const max = props.unit === '%' ? 100 : Math.max(1, ...values);
    return values
      .map(
        (v, i) =>
          `${(i / Math.max(1, values.length - 1)) * 300},${
            72 - Math.max(0, Math.min(1, v / max)) * 66
          }`
      )
      .join(' ');
  });
</script>
