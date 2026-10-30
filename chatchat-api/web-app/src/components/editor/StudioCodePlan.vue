<template>
  <section
    v-if="plan || state === 'planning'"
    class="studio-code-plan"
    aria-label="代码协作计划"
    aria-live="polite"
  >
    <header
      ><strong>{{ state === "planning" ? "正在制定计划" : "任务处理计划" }}</strong
      ><span>{{
        state === "planning"
          ? "尚未开始探索"
          : state === "failed"
            ? "后续处理未完成"
            : state === "complete"
              ? "本轮处理已结束"
              : state === "ready"
                ? "计划已就绪"
                : "分析处理中"
      }}</span></header
    >
    <template v-if="plan"
      ><p>{{ planText(plan.intent?.goal) }}</p
      ><ol
        ><li v-for="step in plan.plan?.steps || []" :key="step.id">{{
          planText(step.input?.description)
        }}</li></ol
      >
      <details v-if="plan.context?.missing_info?.length"
        ><summary>待补充信息 · {{ plan.context.missing_info.length }}</summary
        ><ul
          ><li v-for="item in plan.context.missing_info" :key="item">{{ planText(item) }}</li></ul
        ></details
      >
      <small
        >初始计划不代表已执行或验证，后续进展以处理记录为准。代码变更需审查后应用。</small
      >
    </template>
  </section>
</template>
<script src="../../js/components/editor/StudioCodePlan.js"></script>
<style scoped src="../../styles/components/editor/studio-code-plan.css"></style>
