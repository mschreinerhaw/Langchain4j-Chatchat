<template>
  <nav v-if="total > 0" class="app-pagination" :aria-label="ariaLabel">
    <span class="app-pagination__summary">{{ start }}–{{ end }} / {{ total }} 条</span>
    <div class="app-pagination__actions">
      <button type="button" :disabled="disabled || currentPage <= 1" @click="$emit('change', currentPage - 1)">
        上一页
      </button>
      <strong>第 {{ currentPage }} / {{ normalizedPageCount }} 页</strong>
      <button type="button" :disabled="disabled || currentPage >= normalizedPageCount" @click="$emit('change', currentPage + 1)">
        下一页
      </button>
    </div>
  </nav>
</template>

<script setup>
import { computed } from "vue";

const props = defineProps({
  page: { type: Number, default: 1 },
  pageSize: { type: Number, default: 10 },
  total: { type: Number, default: 0 },
  pageCount: { type: Number, default: 0 },
  disabled: { type: Boolean, default: false },
  ariaLabel: { type: String, default: "分页" }
});

defineEmits(["change"]);

const normalizedPageCount = computed(() => Math.max(1, props.pageCount || Math.ceil(props.total / props.pageSize)));
const currentPage = computed(() => Math.min(Math.max(1, props.page), normalizedPageCount.value));
const start = computed(() => props.total ? (currentPage.value - 1) * props.pageSize + 1 : 0);
const end = computed(() => Math.min(currentPage.value * props.pageSize, props.total));
</script>

<style>
.app-pagination {
  min-height: 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  color: #64748b;
  font-size: 13px;
}

.app-pagination__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.app-pagination__actions button {
  min-width: 72px;
  height: 34px;
  padding: 0 14px;
  border: 1px solid #d7dfeb;
  border-radius: 9px;
  background: #fff;
  color: #334155;
  cursor: pointer;
}

.app-pagination__actions button:hover:not(:disabled) {
  border-color: #8bb3ff;
  color: #2563eb;
  background: #f5f9ff;
}

.app-pagination__actions button:disabled {
  opacity: 0.48;
  cursor: not-allowed;
}

.app-pagination__actions strong {
  min-width: 92px;
  text-align: center;
  color: #475569;
  font-weight: 600;
}

@media (max-width: 640px) {
  .app-pagination { align-items: flex-start; flex-direction: column; padding: 10px 0; }
}
</style>
