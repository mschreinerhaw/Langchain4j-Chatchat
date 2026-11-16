<template>
  <div class="components-grid">
    <article v-for="component in components" :key="component.Id" class="component-card">
      <h3>
        <RouterLink :to="`/containers/${component.nodeId}/${component.Id}`">
          {{
            component.Labels?.['deployment-ops.component'] ||
            component.Labels?.['com.docker.compose.service'] ||
            containerName(component)
          }}
        </RouterLink>
        <StateBadge
          :status="
            component.managerMetrics?.health && component.managerMetrics.health !== 'none'
              ? component.managerMetrics.health
              : component.State
          "
        />
      </h3>
      <dl class="facts">
        <dt>镜像</dt>
        <dd>{{ component.Image }}</dd>
        <dt>主机</dt>
        <dd>{{ component.nodeName }}</dd>
        <dt>端口</dt>
        <dd>
          {{
            (component.Ports || [])
              .map((p) =>
                p.PublicPort ? `${p.PublicPort} → ${p.PrivatePort}` : String(p.PrivatePort)
              )
              .join(', ') || '内部网络'
          }}
        </dd>
        <dt>CPU</dt>
        <dd>{{ percent(component.managerMetrics?.cpuPercent) }}</dd>
      </dl>
    </article>
  </div>
</template>
<script setup>
  import StateBadge from '../ui/StateBadge.vue';
  import { containerName, percent } from '../../utils/format';
  defineProps({ components: { type: Array, default: () => [] } });
</script>
