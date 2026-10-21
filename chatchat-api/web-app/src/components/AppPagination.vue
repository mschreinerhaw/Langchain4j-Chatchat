<template>
  <nav v-if="total > 0" class="app-pagination" :aria-label="ariaLabel">
    <span class="app-pagination__summary">{{ start }}–{{ end }} / {{ total }} 条</span>
    <div class="app-pagination__actions">
      <button
        type="button"
        :disabled="disabled || currentPage <= 1"
        @click="$emit('change', currentPage - 1)"
      >
        上一页
      </button>
      <strong>第 {{ currentPage }} / {{ normalizedPageCount }} 页</strong>
      <button
        type="button"
        :disabled="disabled || currentPage >= normalizedPageCount"
        @click="$emit('change', currentPage + 1)"
      >
        下一页
      </button>
    </div>
  </nav>
</template>

<script src="../js/components/AppPagination.js"></script>

<style src="../styles/components/app-pagination.css"></style>
