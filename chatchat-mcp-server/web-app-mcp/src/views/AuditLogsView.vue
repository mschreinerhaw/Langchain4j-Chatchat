<template>
  <section class="workspace-panel">
    <header class="panel-heading">
      <div>
        <h2>调用审计</h2>
        <p>查询 MCP 工具调用记录和执行结果摘要。</p>
      </div>
      <el-button type="primary" :disabled="busy" :loading="busy" @click="search">
        <el-icon><Search /></el-icon>
        <span>查询</span>
      </el-button>
    </header>

    <el-row :gutter="12">
      <el-col :xs="24" :md="6">
        <el-input v-model.trim="filters.toolName" clearable placeholder="工具名称" @keyup.enter="search" />
      </el-col>
      <el-col :xs="24" :md="6">
        <el-input v-model.trim="filters.targetName" clearable placeholder="目标/服务名称" @keyup.enter="search" />
      </el-col>
      <el-col :xs="24" :md="6">
        <el-select v-model="filters.success" clearable placeholder="状态" class="w-100">
          <el-option label="成功" :value="true" />
          <el-option label="失败" :value="false" />
        </el-select>
      </el-col>
      <el-col :xs="24" :md="6">
        <el-input v-model.trim="filters.keyword" clearable placeholder="关键字" @keyup.enter="search" />
      </el-col>
    </el-row>

    <el-table class="settings-table" :data="logs" border stripe v-loading="busy" empty-text="暂无审计记录">
      <el-table-column label="时间" min-width="170">
        <template #default="{ row }">{{ formatDateTime(row.createdAt || row.invokeTime) }}</template>
      </el-table-column>
      <el-table-column label="工具" min-width="180">
        <template #default="{ row }"><code>{{ row.toolName || '-' }}</code></template>
      </el-table-column>
      <el-table-column label="目标/服务" min-width="180">
        <template #default="{ row }">{{ row.targetName || row.serviceName || row.clientName || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.success === false ? 'danger' : 'success'" effect="light">
            {{ row.success === false ? '失败' : '成功' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="120">
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

    <ModalPanel :open="detailOpen" title="审计详情" wide @close="detailOpen = false">
      <JsonBlock :value="detail" />
    </ModalPanel>
  </section>
</template>

<script src="../scripts/views/AuditLogsView.js"></script>
