<template>
  <section class="feature-view favorites-view">
    <header class="favorites-header">
      <div>
        <p>个人收藏中心</p>
        <span>统一管理收藏的历史会话和文档。</span>
      </div>
    </header>

    <p v-if="error" class="favorite-error">{{ error }}</p>
    <p v-if="message" class="favorite-message">{{ message }}</p>

    <section class="favorite-search-panel">
      <label>
        <span>收藏检索</span>
        <input
          v-model="keyword"
          type="search"
          placeholder="搜索会话、文档标题或分类"
          @keyup.enter="searchFavorites"
        >
      </label>
      <button type="button" class="light-button" @click="searchFavorites">
        搜索
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
        <div class="favorite-category-heading">
          <span>分类</span>
          <button
            type="button"
            class="favorite-category-add"
            aria-label="新建收藏分类"
            title="新建分类"
            @click="openCategoryDialog()"
          >
            <Plus :size="16" />
          </button>
        </div>
        <div
          v-if="openCategoryActionName"
          class="favorite-category-action-backdrop"
          @click="closeCategoryActions"
        ></div>
        <div
          v-for="category in categoryOptions"
          :key="category.value"
          class="favorite-category-row"
          :class="{
            active: activeCategory === category.value,
            'menu-open': openCategoryActionName === category.value
          }"
        >
          <button
            type="button"
            class="favorite-category-filter"
            :class="{ active: activeCategory === category.value }"
            @click="selectCategory(category.value)"
          >
            <span :title="category.label">{{ category.label }}</span>
            <strong>{{ category.count }}</strong>
          </button>
          <div v-if="isMutableCategory(category.value)" class="favorite-category-row-actions">
            <button
              type="button"
              class="favorite-category-action-trigger"
              title="分类操作"
              aria-label="分类操作"
              :aria-expanded="openCategoryActionName === category.value"
              @click.stop="toggleCategoryActions(category.value)"
            >
              <MoreHorizontal :size="16" />
            </button>
            <div
              v-if="openCategoryActionName === category.value"
              class="favorite-category-action-menu"
              @click.stop
            >
              <button type="button" @click="openCategoryDialog(category)">
                <Pencil :size="14" />
                <span>修改分类</span>
              </button>
              <button type="button" class="danger-action" @click="openCategoryDeleteDialog(category)">
                <Trash2 :size="14" />
                <span>删除分类</span>
              </button>
            </div>
          </div>
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
      <form class="favorite-category-dialog" @submit.prevent="saveCategory">
        <header>
          <div>
            <p>收藏分类</p>
            <h2>{{ editingCategoryName ? "修改分类" : "新建分类" }}</h2>
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
            {{ categorySaving ? "保存中" : (editingCategoryName ? "保存修改" : "创建分类") }}
          </button>
        </footer>
      </form>
    </div>

    <div v-if="categoryDeleteDialogOpen" class="favorite-dialog-backdrop" @click.self="closeCategoryDeleteDialog">
      <section class="favorite-category-dialog favorite-delete-dialog" role="dialog" aria-modal="true" aria-labelledby="favorite-delete-title">
        <header>
          <div>
            <p>收藏分类</p>
            <h2 id="favorite-delete-title">删除分类？</h2>
          </div>
          <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" :disabled="categoryDeleteSubmitting" @click="closeCategoryDeleteDialog">×</button>
        </header>
        <div class="favorite-delete-body">
          <span class="favorite-delete-icon"><Trash2 :size="20" /></span>
          <div>
            <strong>确定删除“{{ categoryDeleteItem?.label }}”吗？</strong>
            <p>分类中的 {{ categoryDeleteItem?.count || 0 }} 项收藏不会被删除，将自动移至“默认”分类。</p>
          </div>
        </div>
        <footer>
          <button type="button" class="secondary-button" :disabled="categoryDeleteSubmitting" @click="closeCategoryDeleteDialog">取消</button>
          <button type="button" class="danger-confirm-button" :disabled="categoryDeleteSubmitting" @click="deleteCategory">
            {{ categoryDeleteSubmitting ? "删除中" : "删除分类" }}
          </button>
        </footer>
      </section>
    </div>
  </section>
</template>

<script src="../js/views/FavoritesView.js"></script>
