<template>
  <ResourceState
    v-if="!app"
    :loading="loading"
    :empty="!loading"
    title="未找到此应用环境"
    description="请检查主机连接，或返回应用列表刷新。"
  />
  <template v-else>
    <div class="application-banner">
      <div>
        <h2>
          {{ app.environment }}
          <StateBadge :status="app.status" />
        </h2>
        <p class="muted">
          版本：{{ app.job?.version || '外部管理' }}　主机：{{ app.nodeName }}　{{
            app.managed ? '平台管理的独立应用环境' : '现有业务应用，只读关联'
          }}
        </p>
      </div>
      <div class="toolbar">
        <RouterLink
          v-if="app.managed && admin"
          :to="{ path: '/deploy', query: { node: app.nodeId, environment: app.environment } }"
          class="button primary"
        >
          配置 / 升级
        </RouterLink>
        <RouterLink to="/applications" class="button">返回应用列表</RouterLink>
      </div>
    </div>
    <div class="tabs">
      <RouterLink
        v-for="item in tabs"
        :key="item[0]"
        :to="root + '/' + item[0]"
        :class="{ active: tab === item[0] }"
      >
        {{ item[1] }}
      </RouterLink>
    </div>
    <template v-if="tab === 'overview'">
      <h3>组件视图 · {{ app.services.length }} 个服务</h3>
      <RuntimeServiceOverview
        v-if="app.job?.runtimeAdapter === 'runtime-os-native/v1'"
        :components="app.services"
      />
      <ComponentGrid v-else :components="app.services" />
      <PanelCard title="容器运行状态"><ContainerTable :rows="app.containers" /></PanelCard>
    </template>
    <PanelCard v-else-if="tab === 'components'" title="服务与初始化组件">
      <ContainerTable :rows="app.containers" />
    </PanelCard>
    <PanelCard
      v-else-if="tab === 'configuration'"
      title="当前部署配置"
      description="敏感参数由执行器保管，不返回页面。"
    >
      <dl v-if="app.job" class="facts">
        <template v-for="(value, key) in app.job.parameters" :key="key">
          <dt>{{ key }}</dt>
          <dd>{{ value }}</dd>
        </template>
      </dl>
      <ResourceState
        v-else
        :empty="true"
        title="外部应用配置由原管理系统维护"
        description="可在容器详情查看已脱敏的运行配置。"
      />
    </PanelCard>
    <template v-else-if="tab === 'monitoring'">
      <NativeRuntimeHealth
        v-if="app.job?.runtimeAdapter === 'runtime-os-native/v1'"
        :key="app.job.id"
        :node="app.nodeId"
        :job="app.job.id"
      />
      <PanelCard title="应用资源与健康">
        <ComponentGrid
          v-if="app.job?.runtimeAdapter !== 'runtime-os-native/v1'"
          :components="app.services"
        />
        <DataTable :rows="app.services" :columns="metricColumns" row-key="Id">
          <template #name="{ row }">{{ containerName(row) }}</template>
          <template #cpu="{ row }">{{ percent(row.managerMetrics?.cpuPercent) }}</template>
          <template #memory="{ row }">
            {{ bytes(row.managerMetrics?.memoryBytes) }} /
            {{ bytes(row.managerMetrics?.memoryLimitBytes) }}
          </template>
          <template #health="{ row }">
            <StateBadge :status="row.managerMetrics?.health || 'not_configured'" />
          </template>
        </DataTable>
      </PanelCard>
    </template>
    <PanelCard v-else-if="tab === 'history'" title="部署历史">
      <DataTable :rows="history" :columns="historyColumns">
        <template #status="{ row }"><StateBadge :status="row.status" /></template>
        <template #createdAt="{ row }">{{ dateTime(row.createdAt) }}</template>
        <template #action="{ row }">
          <RouterLink :to="`/tasks/deployment/${row.nodeId}/${row.id}`">步骤与日志</RouterLink>
        </template>
      </DataTable>
    </PanelCard>
  </template>
</template>
<script setup>
  import RuntimeServiceOverview from '../../components/applications/RuntimeServiceOverview.vue';
  import NativeRuntimeHealth from '../../components/applications/NativeRuntimeHealth.vue';
  import { computed } from 'vue';
  import { useRoute } from 'vue-router';
  import { useCluster } from '../../stores/cluster';
  import { useSession } from '../../stores/session';
  import { containerName, bytes, percent, dateTime } from '../../utils/format';
  import PanelCard from '../../components/ui/PanelCard.vue';
  import StateBadge from '../../components/ui/StateBadge.vue';
  import ResourceState from '../../components/ui/ResourceState.vue';
  import DataTable from '../../components/ui/DataTable.vue';
  import ComponentGrid from '../../components/applications/ComponentGrid.vue';
  import ContainerTable from '../../components/containers/ContainerTable.vue';
  const route = useRoute(),
    { applications, jobs, loading } = useCluster(),
    { admin } = useSession();
  const app = computed(() =>
    applications.value.find(
      (a) => String(a.nodeId) === route.params.node && a.environment === route.params.environment
    )
  );
  const tabs = [
      ['overview', '应用详情'],
      ['components', '服务组件'],
      ['configuration', '配置'],
      ['monitoring', '资源监控'],
      ['history', '部署历史'],
    ],
    tab = computed(() => route.params.tab || 'overview'),
    root = computed(
      () => `/applications/${route.params.node}/${encodeURIComponent(route.params.environment)}`
    );
  const history = computed(() =>
    jobs.value
      .filter(
        (j) => String(j.nodeId) === route.params.node && j.environment === route.params.environment
      )
      .sort((a, b) => b.createdAt - a.createdAt)
  );
  const metricColumns = [
      { key: 'name', label: '服务组件' },
      { key: 'cpu', label: 'CPU' },
      { key: 'memory', label: '内存使用 / 限额' },
      { key: 'health', label: '健康检查' },
    ],
    historyColumns = [
      { key: 'version', label: '版本' },
      { key: 'status', label: '任务结果' },
      { key: 'createdAt', label: '开始时间' },
      { key: 'actor', label: '执行人' },
      { key: 'action', label: '详情' },
    ];
</script>
