<template>
  <section class="feature-view favorites-view">
    <header>
      <p>{{ userId }} 收藏</p>
      <h1>收藏夹</h1>
    </header>
    <p v-if="error" class="favorite-error">{{ error }}</p>

    <section class="favorite-search-panel">
      <label>
        <span>收藏检索</span>
        <input v-model="keyword" type="search" placeholder="按标题、类型或分类检索">
      </label>
      <button type="button" :disabled="loading" @click="loadFavorites">
        {{ loading ? "刷新中" : "刷新" }}
      </button>
    </section>

    <div class="favorite-layout">
      <aside class="favorite-categories">
        <button
          v-for="category in categoryOptions"
          :key="category.value"
          type="button"
          :class="{ active: activeCategory === category.value }"
          @click="selectCategory(category.value)"
        >
          <span>{{ category.label }}</span>
          <strong>{{ category.count }}</strong>
        </button>
      </aside>

      <div class="library-list">
        <article v-for="favorite in filteredFavorites" :key="favorite.id || favorite.targetId">
          <div>
            <strong>{{ favorite.title || favorite.targetId }}</strong>
            <span>{{ formatType(favorite.targetType) }} · {{ favoriteCategory(favorite) }}</span>
          </div>
          <div class="favorite-actions">
            <button type="button" @click="openFavorite(favorite)">打开</button>
            <button type="button" @click="removeFavorite(favorite)">取消收藏</button>
          </div>
        </article>
        <p v-if="!loading && favorites.length === 0" class="favorite-empty">暂无收藏内容</p>
        <p v-else-if="!loading && filteredFavorites.length === 0" class="favorite-empty">暂无匹配收藏</p>
      </div>
    </div>
  </section>
</template>

<script src="../js/views/FavoritesView.js"></script>
