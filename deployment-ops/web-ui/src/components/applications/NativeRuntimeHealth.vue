<template>
  <PanelCard
    title="Native Core / Python 执行环境"
    description="实时检查受限执行器的本地健康端点；安装能力验证与当前状态分别展示。"
  >
    <ResourceState :loading="loading && !data" :error="error" @retry="refresh" />
    <template v-if="data">
      <div class="components-grid">
        <article v-for="executor in data.executors" :key="executor.name" class="component-card">
          <h3>
            {{ executor.name }}
            <StateBadge :status="executor.status" />
          </h3>
          <dl class="facts">
            <dt>资源策略</dt>
            <dd>{{ executor.resourceEnforced ? '已准入并强制执行' : '未确认' }}</dd>
            <dt>响应延迟</dt>
            <dd>{{ executor.latencyMs }} ms</dd>
            <dt>采样时间</dt>
            <dd>{{ dateTime(data.sampledAt) }}</dd>
          </dl>
        </article>
      </div>
      <dl class="facts" style="margin-top: 20px">
        <dt>最近安装验收</dt>
        <dd>{{ dateTime(data.verifiedAt) }}</dd>
        <dt>Native Core</dt>
        <dd>
          <StateBadge
            :status="data.installationVerification.nativeCoreVerified ? 'passed' : 'UNKNOWN'"
          />
        </dd>
        <dt>Python 运行时</dt>
        <dd>
          <StateBadge
            :status="data.installationVerification.pythonRuntimeVerified ? 'passed' : 'UNKNOWN'"
          />
        </dd>
        <dt>专用执行守护进程</dt>
        <dd>{{ data.installationVerification.privateExecutorDaemon ? '已验证' : '尚未验证' }}</dd>
      </dl>
      <p class="muted">
        安装验收记录不代替实时健康检查。执行器不响应或未准入资源策略时，不会显示为健康。
      </p>
    </template>
  </PanelCard>
</template>
<script setup>
  import { deploymentsApi } from '../../api/deployments';
  import { useResource } from '../../composables/useResource';
  import { dateTime } from '../../utils/format';
  import PanelCard from '../ui/PanelCard.vue';
  import ResourceState from '../ui/ResourceState.vue';
  import StateBadge from '../ui/StateBadge.vue';
  const props = defineProps({ node: [Number, String], job: String }),
    { data, error, loading, refresh } = useResource(
      (signal) => deploymentsApi.health(props.node, props.job, signal),
      { interval: 15000 }
    );
</script>
