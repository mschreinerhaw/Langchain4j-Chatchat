<template>
  <section class="feature-view">
    <header>
      <p>能力广场</p>
      <h1>投研能力快速编排</h1>
    </header>
    <div class="feature-grid">
      <article v-for="item in capabilities" :key="item.title" class="feature-card">
        <span>{{ item.code }}</span>
        <h2>{{ item.title }}</h2>
        <p>{{ item.description }}</p>
      </article>
    </div>
  </section>
</template>

<script>
export default {
  name: "CapabilityMarketView",
  data() {
    return {
      capabilities: [
        { code: "Q", title: "财报问答", description: "围绕公告、财务指标和管理层讨论生成结构化分析。" },
        { code: "F", title: "估值框架", description: "按行业模板拆解估值假设、盈利预测和风险变量。" },
        { code: "R", title: "研报生成", description: "将数据、图谱和引用来源组合为可复核投研草稿。" },
        { code: "M", title: "异动监控", description: "跟踪资金、舆情、价格和行业事件的同步变化。" }
      ]
    };
  }
};
</script>
