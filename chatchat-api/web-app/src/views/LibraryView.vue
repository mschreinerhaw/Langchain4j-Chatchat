<template>
  <section class="feature-view">
    <header>
      <p>投研库</p>
      <h1>沉淀可复用的投研资产</h1>
    </header>
    <div class="library-list">
      <article v-for="item in library" :key="item.name">
        <strong>{{ item.name }}</strong>
        <span>{{ item.count }} 份资料</span>
      </article>
    </div>
  </section>
</template>

<script>
export default {
  name: "LibraryView",
  data() {
    return {
      library: [
        { name: "上市公司财报", count: 1280 },
        { name: "行业深度报告", count: 426 },
        { name: "宏观策略资料", count: 217 },
        { name: "会议纪要", count: 96 }
      ]
    };
  }
};
</script>
