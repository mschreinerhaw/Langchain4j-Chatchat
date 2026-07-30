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
        <small>{{ category.description || '暂无分类说明' }}</small>
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

<script>
export default {
  name: 'CategoryCardPager',
  props: {
    cards: { type: Array, default: () => [] },
    selectedKey: { type: String, default: '' },
    ariaLabel: { type: String, default: '业务分类' }
  },
  emits: ['select'],
  data() {
    return {
      page: 1,
      visibleColumns: 6,
      gridObserver: null
    };
  },
  computed: {
    pageSize() {
      return Math.max(2, this.visibleColumns * 2);
    },
    pageCount() {
      return Math.max(1, Math.ceil(this.cards.length / this.pageSize));
    },
    pagedCards() {
      const page = Math.min(this.page, this.pageCount);
      const start = (page - 1) * this.pageSize;
      return this.cards.slice(start, start + this.pageSize);
    }
  },
  watch: {
    pageCount() {
      this.normalizePage();
    },
    pageSize() {
      this.revealSelected();
    },
    selectedKey: {
      immediate: true,
      handler(value) {
        this.revealSelected(value);
      }
    },
    cards() {
      this.revealSelected();
    }
  },
  mounted() {
    this.$nextTick(this.observeGrid);
  },
  beforeUnmount() {
    this.gridObserver?.disconnect();
    window.removeEventListener('resize', this.measureGrid);
  },
  methods: {
    categoryKey(category) {
      return category?.id || category?.code || '';
    },
    cardKey(category, index) {
      return this.categoryKey(category) || `all-${index}`;
    },
    cardTitle(category) {
      return [category?.name, category?.description].filter(Boolean).join('\n');
    },
    normalizePage() {
      if (this.page > this.pageCount) this.page = this.pageCount;
      if (this.page < 1) this.page = 1;
    },
    revealSelected(value = this.selectedKey) {
      const index = this.cards.findIndex(card => this.categoryKey(card) === value);
      if (index >= 0) {
        this.page = Math.floor(index / this.pageSize) + 1;
      } else {
        this.normalizePage();
      }
    },
    observeGrid() {
      this.gridObserver?.disconnect();
      if (typeof ResizeObserver === 'function' && this.$refs.grid) {
        this.gridObserver = new ResizeObserver(() => this.measureGrid());
        this.gridObserver.observe(this.$refs.grid);
      } else {
        window.addEventListener('resize', this.measureGrid);
      }
      this.measureGrid();
    },
    measureGrid() {
      const width = this.$refs.grid?.clientWidth || 0;
      if (!width) return;
      const minimumCardWidth = 220;
      const gap = 12;
      this.visibleColumns = Math.max(
        1,
        Math.min(6, Math.floor((width + gap) / (minimumCardWidth + gap)))
      );
    }
  }
};
</script>

<style scoped>
.category-card-pager {
  min-width: 0;
}

.capability-category-grid {
  grid-template-columns: repeat(var(--category-columns), minmax(0, 1fr));
}

.capability-category-card {
  min-width: 0;
  align-content: start;
}

.capability-category-title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.capability-category-card small {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  line-clamp: 3;
}

.category-card-pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 14px;
  min-height: 40px;
  padding-top: 10px;
  color: #667085;
  font-size: 13px;
}

@media (max-width: 560px) {
  .category-card-pagination {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
