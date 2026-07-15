<template>
  <section class="workspace-panel">
    <header class="panel-heading">
      <div>
        <h2>执行命令审计</h2>
        <p>查询 SQL、SQL 脚本、数据库模板和 Linux 命令的执行记录。</p>
      </div>
      <el-button type="primary" :disabled="busy" :loading="busy" @click="search">
        <el-icon><Search /></el-icon>
        <span>查询</span>
      </el-button>
    </header>

    <el-row :gutter="12">
      <el-col :xs="24" :md="5">
        <el-input v-model.trim="filters.username" clearable placeholder="用户名称" @keyup.enter="search" />
      </el-col>
      <el-col :xs="24" :md="5">
        <el-input v-model.trim="filters.datasourceName" clearable placeholder="数据源名称" @keyup.enter="search" />
      </el-col>
      <el-col :xs="24" :md="5">
        <el-select v-model="filters.commandType" clearable placeholder="命令分类" class="w-100" @change="search">
          <el-option label="SQL 查询" value="SQL_QUERY" />
          <el-option label="SQL 脚本" value="SQL_SCRIPT" />
          <el-option label="数据库模板" value="DATABASE_QUERY" />
          <el-option label="Linux 命令" value="LINUX_COMMAND" />
        </el-select>
      </el-col>
      <el-col :xs="24" :md="4">
        <el-select v-model="filters.success" clearable placeholder="执行结果" class="w-100" @change="search">
          <el-option label="成功" :value="true" />
          <el-option label="失败" :value="false" />
        </el-select>
      </el-col>
      <el-col :xs="24" :md="5">
        <el-input v-model.trim="filters.keyword" clearable placeholder="命令/工具关键词" @keyup.enter="search" />
      </el-col>
    </el-row>

    <el-table class="settings-table" :data="logs" border stripe v-loading="busy" empty-text="暂无命令审计记录">
      <el-table-column label="执行时间" min-width="175">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column prop="username" label="用户名称" min-width="130" />
      <el-table-column label="分类" min-width="120">
        <template #default="{ row }">
          <el-tag effect="light">{{ commandTypeLabel(row.commandType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="datasourceName" label="执行数据源名称" min-width="190" show-overflow-tooltip />
      <el-table-column prop="commandSummary" label="执行命令短文" width="280" show-overflow-tooltip>
        <template #default="{ row }"><code>{{ row.commandSummary || '-' }}</code></template>
      </el-table-column>
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <el-tag :type="row.success ? 'success' : 'danger'" effect="light">
            {{ row.success ? '成功' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="110">
        <template #default="{ row }">{{ row.durationMs ?? '-' }} ms</template>
      </el-table-column>
      <el-table-column fixed="right" label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <footer class="pagination-row">
      <el-text type="info">共 {{ pageInfo.filteredCount }} 条，当前第 {{ pageInfo.page }} / {{ pageInfo.totalPages }} 页</el-text>
      <el-pagination
        background
        layout="sizes, prev, pager, next, jumper"
        :current-page="pageInfo.page"
        :page-size="pageInfo.pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="pageInfo.filteredCount"
        @current-change="changePage"
        @size-change="changePageSize"
      />
    </footer>

    <ModalPanel :open="detailOpen" title="命令审计详情" wide @close="detailOpen = false">
      <JsonBlock :value="detail" />
    </ModalPanel>
  </section>
</template>

<script src="../scripts/views/CommandAuditLogsView.js"></script>
