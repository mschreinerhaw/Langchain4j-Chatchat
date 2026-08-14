<template>
  <section class="feature-view favorites-view">
    <header class="favorites-header">
      <div>
        <p>个人收藏中心</p>
        <h1>收藏夹</h1>
        <span>统一管理收藏的历史会话和文档。</span>
      </div>
    </header>

    <p v-if="error" class="favorite-error">{{ error }}</p>
    <p v-if="message" class="favorite-message">{{ message }}</p>

    <section class="favorite-search-panel">
      <label>
        <span>收藏检索</span>
        <input v-model="keyword" type="search" placeholder="搜索会话、文档标题或分类">
      </label>
      <button type="button" class="light-button" :disabled="loading" @click="loadFavorites">
        {{ loading ? "刷新中" : "刷新" }}
      </button>
    </section>

    <nav class="favorite-type-tabs" aria-label="收藏类型">
      <button
        v-for="type in typeOptions"
        :key="type.value"
        type="button"
        :class="{ active: activeType === type.value }"
        @click="activeType = type.value"
      >
        <span>{{ type.label }}</span>
        <strong>{{ type.count }}</strong>
      </button>
    </nav>

    <div class="favorite-layout">
      <aside class="favorite-categories">
        <div
          v-for="category in categoryOptions"
          :key="category.value"
          class="favorite-category-row"
        >
          <button
            type="button"
            class="favorite-category-filter"
            :class="{ active: activeCategory === category.value }"
            @click="selectCategory(category.value)"
          >
            <span>{{ category.label }}</span>
            <strong>{{ category.count }}</strong>
          </button>
          <button
            v-if="category.value === 'all'"
            type="button"
            class="favorite-category-add"
            aria-label="新建收藏分类"
            title="新建分类"
            @click="openCategoryDialog"
          >
            ＋
          </button>
        </div>
      </aside>

      <div class="library-list">
        <article v-for="favorite in filteredFavorites" :key="favorite.id || favorite.targetId">
          <div class="favorite-main">
            <span class="favorite-type-badge" :class="favoriteTypeClass(favorite)">{{ formatType(favorite.targetType) }}</span>
            <div>
              <strong>{{ favorite.title || favorite.targetId }}</strong>
              <span>{{ favoriteCategory(favorite) }} · {{ formatFavoriteTime(favorite.createdAt) }}</span>
            </div>
          </div>
          <div class="favorite-actions">
            <label class="favorite-category-select">
              <span>分类</span>
              <select
                :value="favoriteCategory(favorite)"
                :title="favoriteCategory(favorite)"
                :style="{ '--favorite-category-width': favoriteCategorySelectWidth(favorite) }"
                :disabled="categoryUpdatingIds[favorite.id]"
                @change="changeFavoriteCategory(favorite, $event.target.value)"
              >
                <option v-for="category in availableCategoryNames" :key="category" :value="category" :title="category">{{ category }}</option>
              </select>
            </label>
            <button
              type="button"
              class="favorite-open-button"
              :disabled="isUnsupportedDocumentFavorite(favorite)"
              :title="favoritePreviewTitle(favorite)"
              @click="openFavorite(favorite)"
            >
              打开
            </button>
            <button type="button" class="favorite-danger-button" @click="removeFavorite(favorite)">取消收藏</button>
          </div>
        </article>
        <p v-if="!loading && favorites.length === 0" class="favorite-empty">暂无收藏内容，可以从历史会话或文档库添加收藏。</p>
        <p v-else-if="!loading && filteredFavorites.length === 0" class="favorite-empty">暂无匹配收藏</p>
      </div>
    </div>

    <div v-if="categoryDialogOpen" class="favorite-dialog-backdrop" @click.self="closeCategoryDialog">
      <form class="favorite-category-dialog" @submit.prevent="createCategory">
        <header>
          <div>
            <p>收藏分类</p>
            <h2>新建分类</h2>
          </div>
          <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" :disabled="categorySaving" @click="closeCategoryDialog">×</button>
        </header>
        <label>
          <span>分类名称</span>
          <input ref="categoryNameInput" v-model.trim="newCategoryName" maxlength="80" placeholder="例如：项目资料、重点会话">
        </label>
        <p v-if="categoryDialogError" class="favorite-error">{{ categoryDialogError }}</p>
        <footer>
          <button type="button" class="secondary-button" :disabled="categorySaving" @click="closeCategoryDialog">取消</button>
          <button type="submit" class="primary-button" :disabled="categorySaving || !newCategoryName">
            {{ categorySaving ? "创建中" : "创建分类" }}
          </button>
        </footer>
      </form>
    </div>
  </section>
</template>

<script src="../js/views/FavoritesView.js"></script>
