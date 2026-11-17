<template>
  <div class="data-table">
    <div v-if="searchable" class="table-toolbar">
      <input v-model="query" class="search" :aria-label="searchLabel" :placeholder="searchLabel" />
      <span class="muted">{{ filtered.length }} 条记录</span>
      <slot name="toolbar" />
    </div>
    <div class="table-scroll">
      <table>
        <thead>
          <tr>
            <th v-for="column in columns" :key="column.key" :style="{ width: column.width }">
              {{ column.label }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in paged" :key="row[rowKey] ?? index">
            <td v-for="column in columns" :key="column.key">
              <slot :name="column.key" :row="row" :value="row[column.key]">
                {{ row[column.key] ?? '—' }}
              </slot>
            </td>
          </tr>
          <tr v-if="!paged.length">
            <td :colspan="columns.length" class="table-empty">
              {{ query ? '没有匹配的记录' : '暂无记录' }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <footer v-if="filtered.length > pageSize" class="pagination">
      <span>第 {{ page }} / {{ pages }} 页</span>
      <button :disabled="page === 1" @click="page--">上一页</button>
      <button :disabled="page === pages" @click="page++">下一页</button>
    </footer>
  </div>
</template>
<script setup>
  import { ref, computed, watch } from 'vue';
  const props = defineProps({
    rows: { type: Array, default: () => [] },
    columns: { type: Array, required: true },
    rowKey: { type: String, default: 'id' },
    pageSize: { type: Number, default: 20 },
    searchable: { type: Boolean, default: true },
    searchLabel: { type: String, default: '搜索名称、状态或主机…' },
  });
  const query = ref(''),
    page = ref(1);
  const filtered = computed(() =>
    props.rows.filter(
      (row) =>
        !query.value ||
        [
          ...props.columns.map((column) => row[column.key]),
          row.Name,
          row.Names,
          row.RepoTags,
          row.Image,
          row.State,
          row.Id,
        ].some((value) =>
          String(Array.isArray(value) ? value.join(' ') : value ?? '')
            .toLowerCase()
            .includes(query.value.toLowerCase())
        )
    )
  );
  const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / props.pageSize)));
  const paged = computed(() =>
    filtered.value.slice((page.value - 1) * props.pageSize, page.value * props.pageSize)
  );
  watch(query, () => (page.value = 1));
  watch(pages, (value) => (page.value = Math.min(page.value, value)));
</script>
