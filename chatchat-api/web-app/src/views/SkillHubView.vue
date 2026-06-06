<template>
  <section class="feature-view">
    <header>
      <p>SkillHub</p>
      <h1>管理分析技能与调用链</h1>
    </header>
    <div class="feature-grid compact">
      <article v-for="skill in skills" :key="skill.name" class="feature-card">
        <span>{{ skill.short }}</span>
        <h2>{{ skill.name }}</h2>
        <p>{{ skill.status }}</p>
      </article>
    </div>
  </section>
</template>

<script>
export default {
  name: "SkillHubView",
  data() {
    return {
      skills: [
        { short: "S1", name: "财报解读", status: "已接入公告解析、指标抽取和摘要生成。" },
        { short: "S2", name: "行业日报", status: "已接入行情、新闻和研报片段。" },
        { short: "S3", name: "组合复盘", status: "支持收益归因、风险暴露和调仓建议草稿。" }
      ]
    };
  }
};
</script>
