<template>
  <section class="feature-view ai-search-view search-workspace">
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

<script src="../js/views/AiSearchView.js"></script>
