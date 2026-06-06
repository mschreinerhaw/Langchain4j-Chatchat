<template>
  <section class="feature-view library-view">
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

<script src="../js/views/LibraryView.js"></script>
