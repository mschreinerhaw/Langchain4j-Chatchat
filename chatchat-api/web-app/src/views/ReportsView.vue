<template>
  <section class="feature-view">
    <header>
      <p>我的报告</p>
      <h1>报告草稿与归档</h1>
    </header>
    <div class="table-like">
      <article v-for="report in reports" :key="report.title">
        <strong>{{ report.title }}</strong>
        <span>{{ report.type }}</span>
        <time>{{ report.date }}</time>
      </article>
    </div>
  </section>
</template>

<script>
export default {
  name: "ReportsView",
  data() {
    return {
      reports: [
        { title: "半导体行业周报", type: "行业周报", date: "2024-06-04" },
        { title: "贵州茅台投资价值分析", type: "公司深度", date: "2024-06-04" },
        { title: "市场每日跟踪报告", type: "策略日报", date: "2024-06-02" }
      ]
    };
  }
};
</script>
