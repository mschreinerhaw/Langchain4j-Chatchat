<template>
  <section v-if="plan || state==='planning'" class="studio-code-plan" aria-label="代码协作计划" aria-live="polite">
    <header><strong>{{ state==='planning' ? '正在制定计划' : '初始代码协作计划' }}</strong><span>{{ state==='planning' ? '尚未开始探索' : state==='failed' ? '后续处理未完成' : state==='complete' ? '本轮处理已结束' : state==='ready' ? '计划已就绪' : '分析处理中' }}</span></header>
    <template v-if="plan"><p>{{ plan.intent?.goal }}</p><ol><li v-for="step in plan.plan?.steps || []" :key="step.id">{{ step.input?.description }}</li></ol>
      <details v-if="plan.context?.missing_info?.length"><summary>待补充信息 · {{ plan.context.missing_info.length }}</summary><ul><li v-for="item in plan.context.missing_info" :key="item">{{ item }}</li></ul></details>
      <small>初始计划不代表已执行或验证，后续调整与探索结果以 Runtime 记录为准。代码变更仍需审查后应用。</small>
    </template>
  </section>
</template>
<script setup>
defineProps({ plan:Object, state:{type:String,default:'exploring'} });
</script>
<style scoped>
.studio-code-plan{margin:10px 0;padding:12px;border:1px solid #dbe5f5;border-radius:9px;background:#f7faff;color:#334764;font-size:12px;line-height:1.65}
.studio-code-plan header{display:flex;align-items:center;justify-content:space-between;gap:8px}.studio-code-plan header span,.studio-code-plan small{color:#72829a;font-size:11px}.studio-code-plan p{margin:8px 0}.studio-code-plan ol,.studio-code-plan ul{padding-left:20px;margin:6px 0}.studio-code-plan li+li{margin-top:4px}.studio-code-plan small{display:block;margin-top:8px}.studio-code-plan summary{cursor:pointer}
</style>
