<template>
  <div class="components-grid">
    <article v-for="group in groups" :key="group.name" class="component-card">
      <h3>
        {{ group.name }}
        <StateBadge :status="group.status" />
      </h3>
      <p class="muted">{{ group.running }} / {{ group.members.length }} 个服务运行中</p>
      <div class="runtime-service-list">
        <RouterLink
          v-for="member in group.members"
          :key="member.Id"
          :to="`/containers/${member.nodeId}/${member.Id}`"
        >
          <span>{{ service(member) }}</span>
          <StateBadge :status="state(member)" />
        </RouterLink>
      </div>
    </article>
  </div>
</template>
<script setup>
  import { computed } from 'vue';
  import StateBadge from '../ui/StateBadge.vue';
  import { containerName } from '../../utils/format';
  const props = defineProps({ components: { type: Array, default: () => [] } }),
    categories = [
      ['API / MCP / News', ['api', 'mcp', 'news']],
      ['Temporal 工作流', ['temporal']],
      [
        'Coding / Python 执行',
        [
          'executor-daemon',
          'executor1',
          'executor2',
          'studio-executor',
          'recovery1',
          'recovery2',
          'node1',
          'node2',
          'studio',
          'worker1',
          'worker2',
        ],
      ],
      ['Web 与应用入口', ['gateway', 'frontend-resource-hub', 'frontend-worker']],
      ['数据库与持久化存储', ['postgresql', 'temporal-db', 'redis', 'opensearch', 'silo']],
      ['Runtime 业务守护', ['manager']],
    ];
  const service = (c) =>
      c.Labels?.['com.docker.compose.service'] ||
      c.Labels?.['deployment-ops.component'] ||
      containerName(c),
    state = (c) =>
      c.State === 'running' && c.managerMetrics?.health && c.managerMetrics.health !== 'none'
        ? c.managerMetrics.health
        : c.State;
  const groups = computed(() => {
    const assigned = new Set(),
      result = categories.map(([name, roles]) => {
        const members = props.components.filter((c) => roles.includes(service(c)));
        members.forEach((c) => assigned.add(c.Id));
        return { name, members };
      });
    const other = props.components.filter((c) => !assigned.has(c.Id));
    if (other.length) result.push({ name: '其他服务', members: other });
    return result
      .filter((g) => g.members.length)
      .map((g) => ({
        ...g,
        running: g.members.filter((c) => c.State === 'running').length,
        status: g.members.every(
          (c) =>
            c.State === 'running' && !['unhealthy', 'starting'].includes(c.managerMetrics?.health)
        )
          ? 'running'
          : 'degraded',
      }));
  });
</script>
