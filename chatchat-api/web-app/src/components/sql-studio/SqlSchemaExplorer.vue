<template>
  <section class="sql-schema-explorer" aria-label="数据库 Schema 浏览器">
    <Teleport :to="controlsTarget || 'body'" :disabled="!controlsTarget">
    <div class="sql-schema-controls">
      <header
        ><strong>数据库上下文</strong
        ><button type="button" :disabled="busy" @click="refresh">刷新</button></header
      >
      <label v-show="providers.length > 1"
        >元数据服务<select v-model="serviceId" :disabled="busy" @change="chooseService"
          ><option value="">选择已注册服务</option
          ><option v-for="item in providers" :key="item.serviceId" :value="item.serviceId">{{
            item.name
          }}</option></select
        ></label
      >
      <label
        >数据源<input v-model="assetSearch" aria-label="搜索数据源" placeholder="搜索授权数据源…" />
        <select v-model="assetRef" aria-label="选择数据源" :disabled="busy || !serviceId" @change="chooseAsset"
          ><option value="">选择授权数据源</option
          ><option v-for="asset in filteredAssets" :key="asset.assetRef" :value="asset.assetRef">{{
            asset.displayName || asset.name
          }}</option></select
        ></label
      >
      <button v-if="assetsMore" type="button" :disabled="busy" @click="loadAssets(true)"
        >更多数据源</button
      >
      <label
        >{{ namespaceLabel }}<input v-model="namespaceSearch" aria-label="搜索数据库或 Schema" placeholder="搜索数据库或 Schema…" />
        <select
          v-model="selectedNamespace"
          aria-label="选择数据库或 Schema"
          :disabled="busy || !context"
          @change="chooseNamespace"
          ><option value="">全部授权命名空间</option
          ><option
            v-for="item in filteredNamespaces"
            :key="namespaceKey(item)"
            :value="namespaceKey(item)"
            >{{
              [item.database, item.schema].filter((v, i, a) => v && a.indexOf(v) === i).join(" / ")
            }}</option
          ></select
        ></label
      >
      <button v-if="namespacesMore" type="button" :disabled="busy" @click="loadNamespaces(true)"
        >更多命名空间</button
      >
      <small v-if="sourceDialect"
        >数据源方言：{{ sourceDialect
        }}<button type="button" @click="$emit('dialect', sourceDialect)">用于编辑器</button></small
      >
      <small>AI 在当前授权范围内按需发现表结构，无需预选表。编辑器与助手共用此上下文。</small>
      <p v-if="error" class="sql-schema-error" role="alert">{{ error }}</p>
      <p v-if="busy" role="status">正在读取授权元数据…</p>
      <p v-else-if="!providers.length">尚无可用元数据服务。</p>
    </div>
    </Teleport>
    <header><strong>数据源导航</strong></header>
      <form @submit.prevent="searchTables"
        ><input
          v-model="query"
          aria-label="搜索授权表"
          placeholder="搜索表或字段…"
          :disabled="!context"
        /><button type="submit" :disabled="busy || !context">搜索</button></form
      >
      <p v-if="error" class="sql-schema-error" role="alert">{{ error }}</p>
      <p v-if="busy" role="status">正在读取授权元数据…</p>
      <p v-else-if="!providers.length">尚无可用元数据服务。</p>
      <p v-else-if="!context">选择数据源后浏览 Schema。</p>
    <div
      v-if="context"
      ref="tableList"
      class="sql-schema-tables"
      role="tree"
      aria-label="授权库表"
      :aria-busy="busy"
    >
      <div
        v-for="table in tables"
        :key="table.tableRef"
        class="sql-schema-table"
        role="treeitem"
        :aria-expanded="Boolean(expanded[table.tableRef])"
      >
        <div
          ><input
            type="checkbox"
            :aria-label="'选择生成表 ' + table.tableName"
            :checked="isSelected(table)"
            :disabled="busy"
            @change="toggleSelection(table)"
          /><button
            class="sql-schema-table-name"
            type="button"
            :aria-label="'展开 ' + table.tableName"
            @click="expand(table)"
            >{{ expanded[table.tableRef] ? "▾" : "▸" }} {{ table.tableName }}</button
          ><button
            type="button"
            :disabled="busy"
            :aria-label="'插入表名 ' + table.tableName"
            @click="insert(table)"
            >插入</button
          ></div
        >
        <small
          >{{
            [table.database, table.schema].filter((v, i, a) => v && a.indexOf(v) === i).join(" / ")
          }}<template v-if="table.comment"> · {{ table.comment }}</template></small
        >
        <ul v-if="expanded[table.tableRef]" role="group"
          ><li v-for="column in fields[table.tableRef] || []" :key="column.name"
            ><button type="button" :disabled="busy" @click="insert(table, column)">{{
              column.name
            }}</button
            ><small
              >{{ column.columnType || column.dataType
              }}{{ column.columnKey ? " · " + column.columnKey : "" }}</small
            ></li
          ><li v-if="!fields[table.tableRef]?.length">未取得可见字段，不能视为结构已核验。</li></ul
        >
      </div>
      <p v-if="!busy && !tables.length && !error">当前范围没有可展示的表。</p>
    </div>
    <footer v-if="context" class="sql-schema-pagination" aria-label="元数据分页">
      <div class="sql-schema-page-summary"
        ><span>共 {{ total }} 张表</span
        ><label class="sql-schema-page-size"
          >每页<select
            v-model.number="tablePageSize"
            aria-label="每页表数量"
            :disabled="busy"
            @change="loadTables()"
            ><option :value="20">20</option
            ><option :value="50">50</option
            ><option :value="100">100</option></select
          >条</label
        ></div
      >
      <nav aria-label="库表翻页"
        ><button type="button" :disabled="busy || tablePage <= 1" @click="loadTables(tablePage - 1)"
          >上一页</button
        ><span aria-live="polite">{{ tablePage }} / {{ tablePages }} 页</span
        ><button type="button" :disabled="busy || !tablesMore" @click="loadTables(tablePage + 1)"
          >下一页</button
        ></nav
      >
    </footer>
    <section v-if="selectedTables.length" class="sql-schema-selection" aria-label="本次生成选表">
      <header
        ><strong>已选 {{ selectedTables.length }} / 16 张表</strong
        ><button type="button" @click="selectedTables = []">清空</button></header
      >
      <ul
        ><li v-for="item in selectedTables" :key="item.key"
          ><span :title="item.label">{{ item.label }}</span
          ><button
            type="button"
            :aria-label="'移除 ' + item.label"
            @click="removeSelection(item.key)"
            >移除</button
          ></li
        ></ul
      >
      <small>可跨数据库选择。生成时重新校验权限并读取元数据。</small>
    </section>
    <small class="sql-schema-evidence"
      >来源：平台注册元数据 · 未进行本轮联机核验。推断关系和业务口径不作为已确认 Schema。</small
    >
  </section>
</template>
<script src="../../js/components/sql-studio/SqlSchemaExplorer.js"></script>
<style scoped src="../../styles/components/sql-studio/sql-schema-explorer.css"></style>
