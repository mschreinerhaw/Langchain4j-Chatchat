<template>
  <section class="feature-view favorites-view">
    <header>
      <p>{{ userId }} 收藏</p>
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

<script src="../js/views/FavoritesView.js"></script>
