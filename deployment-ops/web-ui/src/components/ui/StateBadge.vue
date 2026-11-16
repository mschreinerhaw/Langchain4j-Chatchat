<template><span class="state-badge" :class="tone"><i />{{ label }}</span></template>
<script setup>
import { computed } from 'vue';
const props = defineProps({ status: { type: String, default: 'UNKNOWN' } });
const states = {
  running: ['运行中', 'success'], healthy: ['健康', 'success'], succeeded: ['成功', 'success'], success: ['成功', 'success'], passed: ['通过', 'success'],
  failed: ['失败', 'danger'], unhealthy: ['不健康', 'danger'], critical: ['严重', 'danger'], recovery_required: ['需要恢复', 'danger'],
  pending: ['等待执行', 'neutral'], queued: ['排队中', 'neutral'], created: ['已创建', 'neutral'],
  disabled: ['已禁用', 'neutral'], not_configured: ['未配置 Docker 探针', 'neutral'],
  stopped: ['已停止', 'neutral'], exited: ['已退出', 'warning'], degraded: ['部分异常', 'warning'], warning: ['警告', 'warning'],
  interrupted: ['执行中断', 'warning'], unknown: ['状态未知', 'warning'], not_deployed: ['未部署', 'neutral'],
  cancelled: ['已取消', 'neutral'], rolled_back: ['已回滚', 'neutral'], rolling_back: ['回滚中', 'info'],
  starting: ['启动中', 'info'], stopping: ['停止中', 'info'], verifying: ['校验中', 'info'], verified: ['已验证', 'success'],
  creating: ['创建中', 'info'], configuring: ['配置中', 'info'], replacing: ['切换中', 'info'], checking: ['健康检查中', 'info'],
};
const value = computed(() => states[props.status?.toLowerCase()] || [props.status, 'neutral']);
const label = computed(() => value.value[0]), tone = computed(() => value.value[1]);
</script>
