<template>
  <section v-if="evidence?.candidates?.length" class="sql-metadata-evidence" aria-label="元数据证据">
    <strong>候选表与结构证据</strong>
    <div v-for="table in evidence.candidates" :key="table.tableRef" class="sql-metadata-candidate">
      <button type="button" @click="inspect(table)" :disabled="loading">{{ [table.database,table.schema,table.tableName].filter(Boolean).join('.') }}</button>
      <p>{{ table.reason }}</p><small>关键字段：{{ table.keyColumns.join('、') }}</small>
      <small v-if="table.relationsStatus === 'NOT_COLLECTED'">尚无已采集的外键关系证据</small>
    </div>
    <p v-if="loading" role="status">正在读取当前授权字段…</p><p v-if="error" role="alert">{{ error }}</p>
    <details v-if="detail" open><summary>{{ detail.tableName }} · {{ detail.columns.length }} 个可见字段</summary>
      <div class="sql-metadata-table-scroll"><table><thead><tr><th>字段</th><th>类型</th><th>键</th><th>注释</th></tr></thead>
        <tbody><tr v-for="column in detail.columns" :key="column.name"><td>{{ column.name }}</td><td>{{ column.columnType || column.dataType }}</td><td>{{ column.columnKey || '未提供' }}</td><td>{{ column.comment || '未提供' }}</td></tr></tbody></table></div>
      <p v-if="detail.relationsStatus === 'NOT_COLLECTED'">此快照尚未采集外键证据。</p>
      <ul v-else-if="detail.foreignKeys?.length"><li v-for="(key, index) in detail.foreignKeys" :key="index">{{ key.column }} → {{ key.referencedDatabase }}.{{ key.referencedTable }}.{{ key.referencedColumn }}（JDBC 外键元数据）</li></ul>
      <p v-else>当前授权范围没有可展示的已采集外键。</p>
    </details>
  </section>
</template>
<script src="../../js/components/sql-studio/SqlMetadataEvidence.js"></script>
