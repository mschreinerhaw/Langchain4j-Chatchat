<template>
  <section class="sql-mapping-preview">
    <header><strong>{{ file.path }}</strong><span>Mapping 表格预览 · v{{ file.version }}</span></header>
    <div v-if="loading" class="sql-mapping-placeholder">正在读取已授权文件…</div>
    <div v-else-if="error" class="sql-inline-error">{{ error }}</div>
    <template v-else-if="sheets.length">
      <nav class="sql-mapping-sheets" aria-label="工作表"><button v-for="name in sheets" :key="name" :class="{ active: name === activeSheet }" @click="activeSheet = name">{{ name }}</button></nav>
      <div class="sql-mapping-grid"><table><thead><tr><th>#</th><th v-for="(cell, col) in rows[0] || []" :key="col" :class="{ selected: selectedColumn === col }" @click="selectedColumn = col">{{ cell || columnName(col) }}</th></tr></thead><tbody><tr v-for="(row, index) in rows.slice(1)" :key="index"><th :class="{ selected: selectedRow === index + 1 }" @click="selectedRow = index + 1">{{ index + 2 }}</th><td v-for="(cell, col) in row" :key="col" :class="{ selected: selectedColumn === col || selectedRow === index + 1 }" @click="selectedColumn = col; selectedRow = index + 1">{{ cell }}</td></tr></tbody></table></div>
      <footer>{{ rows.length }} 行 · {{ rows[0]?.length || 0 }} 列<span v-if="selectedColumn !== null"> · 字段 {{ rows[0]?.[selectedColumn] || columnName(selectedColumn) }}<template v-if="selectedRow !== null"> · 第 {{ selectedRow + 1 }} 行</template></span><button v-if="selectedColumn !== null" type="button" @click="referenceField">引用所选映射到 AI</button></footer>
    </template>
    <pre v-else class="sql-mapping-text">{{ file.content }}</pre>
  </section>
</template>
<script src="../../js/components/sql-studio/SqlMappingPreview.js"></script>
