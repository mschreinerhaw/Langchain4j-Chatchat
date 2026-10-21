<template>
  <section class="category-card-pager" :aria-label="ariaLabel">
    <div
      ref="grid"
      class="capability-category-grid capability-summary-grid"
      :style="{ '--category-columns': visibleColumns }"
    >
      <button
        v-for="(category, index) in pagedCards"
        :key="cardKey(category, index)"
        type="button"
        class="capability-category-card"
        :class="{ active: selectedKey === categoryKey(category) }"
        :title="cardTitle(category)"
        @click="$emit('select', category)"
      >
        <span class="capability-category-title">{{ category.name }}</span>
        <strong>{{ category.count }}</strong>
        <small>{{ category.description || "暂无分类说明" }}</small>
      </button>
    </div>

    <footer v-if="cards.length > pageSize" class="category-card-pagination">
      <span>共 {{ cards.length }} 项</span>
      <el-pagination
        v-model:current-page="page"
        small
        background
        layout="prev, pager, next"
        :page-size="pageSize"
        :total="cards.length"
        :pager-count="5"
      />
    </footer>
  </section>
</template>

<script src="../scripts/components/CategoryCardPager.js"></script>

<style scoped src="../styles/components/category-card-pager.css"></style>
