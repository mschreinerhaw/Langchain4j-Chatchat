<template>
  <ol class="step-list">
    <li v-for="(step, index) in steps" :key="step.id" :class="step.status">
      <span class="step-number">{{ step.status === 'succeeded' ? '✓' : index + 1 }}</span>
      <div class="step-content">
        <header>
          <strong>{{ labels[step.id] || step.id }}</strong>
          <StateBadge :status="step.status" />
        </header>
        <p>
          {{ step.kind === 'job' ? '初始化 / 验证任务' : '业务服务组件' }}
          <span v-if="step.startedAt">· {{ dateTime(step.startedAt) }}</span>
        </p>
        <p v-if="step.error || step.message" class="notice danger">
          {{ step.error || step.message }}
        </p>
        <details v-if="step.logs || step.output">
          <summary>查看执行日志</summary>
          <pre class="log-viewer">{{ step.logs || step.output }}</pre>
        </details>
      </div>
    </li>
  </ol>
</template>
<script setup>
  import StateBadge from '../ui/StateBadge.vue';
  import { dateTime } from '../../utils/format';
  defineProps({ steps: { type: Array, default: () => [] }, node: [String, Number] });
  const labels = {
    verify_archive: '校验发行文件与校验和',
    verify_database: '验证 H2 数据兼容性',
    stop_platform: '停止平台进程',
    restore_database: '恢复管理数据库',
    start_platform: '启动平台进程',
    health_check: '平台健康检查',
    extract_release: '解包并验证发行清单',
    snapshot_database: '保留升级前数据库',
    install_release: '切换平台版本',
  };
</script>
