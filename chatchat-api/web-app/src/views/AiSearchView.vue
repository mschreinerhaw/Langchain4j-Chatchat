<template>
  <section class="feature-view search-workspace">
    <header>
      <p>AI搜索</p>
      <h1>面向投研的信息检索</h1>
    </header>
    <div class="search-panel">
      <input v-model="keyword" placeholder="搜索公司、行业、公告或宏观事件">
      <button type="button">搜索</button>
    </div>
    <article v-for="result in results" :key="result.title" class="result-row">
      <h2>{{ result.title }}</h2>
      <p>{{ result.summary }}</p>
      <span>{{ result.source }}</span>
    </article>
  </section>
</template>

<script>
export default {
  name: "AiSearchView",
  data() {
    return {
      keyword: "",
      results: [
        { title: "半导体设备国产化进度", summary: "结合行业订单、资本开支和政策支持进行动态跟踪。", source: "产业链资料库" },
        { title: "AI服务器需求变化", summary: "从GPU供给、云厂商CapEx和液冷方案扩散判断景气度。", source: "主题研究" }
      ]
    };
  }
};
</script>
