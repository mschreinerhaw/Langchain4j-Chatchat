<template>
  <section class="feature-view">
    <header>
      <p>我的收藏</p>
      <h1>重点标的与研究框架</h1>
    </header>
    <div class="library-list">
      <article v-for="favorite in favorites" :key="favorite">
        <strong>{{ favorite }}</strong>
        <span>已收藏</span>
      </article>
    </div>
  </section>
</template>

<script>
export default {
  name: "FavoritesView",
  data() {
    return {
      favorites: ["宁德时代", "光伏行业分析框架", "北向资金流向监控", "半导体产业链图谱"]
    };
  }
};
</script>
