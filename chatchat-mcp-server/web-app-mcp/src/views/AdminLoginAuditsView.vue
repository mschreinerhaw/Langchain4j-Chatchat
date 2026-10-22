<template>
  <section class="workspace-panel" v-loading="loading">
    <header class="panel-heading"
      ><div><h2>登录审计</h2><p>查询 MCP 本地管理账号的登录结果、来源与终端信息。</p></div
      ><el-button @click="load">刷新</el-button></header
    >
    <div class="admin-user-filters"
      ><el-input
        v-model="keyword"
        aria-label="搜索登录日志"
        clearable
        placeholder="搜索用户、IP、终端或失败原因"
        @keyup.enter="search"
      /><el-select v-model="result" aria-label="登录结果" @change="search"
        ><el-option label="全部结果" value="" /><el-option label="成功" value="success" /><el-option
          label="失败"
          value="failure" /></el-select
      ><el-button @click="search">查询</el-button><span>共 {{ total }} 条</span></div
    >
    <el-table :data="rows" empty-text="暂无匹配的登录记录" class="admin-audit-table">
      <el-table-column label="时间" width="190"
        ><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column
      >
      <el-table-column prop="username" label="用户" min-width="130" show-overflow-tooltip />
      <el-table-column label="结果" width="90"
        ><template #default="{ row }"
          ><el-tag :type="row.result === 'success' ? 'success' : 'danger'">{{
            row.result === "success" ? "成功" : "失败"
          }}</el-tag></template
        ></el-table-column
      >
      <el-table-column prop="ipAddress" label="来源 IP" width="160" />
      <el-table-column prop="userAgent" label="终端" min-width="200" show-overflow-tooltip />
      <el-table-column label="失败原因" min-width="160" show-overflow-tooltip
        ><template #default="{ row }">{{ reason(row) }}</template></el-table-column
      >
    </el-table>
    <div class="admin-audit-pagination"
      ><el-pagination
        background
        layout="total, sizes, prev, pager, next"
        :total="total"
        :page-size="pageSize"
        :page-sizes="[20, 50, 100]"
        :current-page="page"
        @current-change="changePage"
        @size-change="changeSize"
    /></div>
  </section>
</template>
<script src="../scripts/views/AdminLoginAuditsView.js"></script>
