<template>
  <el-tabs v-model="activeTab" class="mcp-service-tabs">
    <el-tab-pane label="外部 MCP 模板" name="external">
      <CrudCatalog
        title="外部 MCP 服务"
        subtitle="注册外部服务、归属父类模板，发现工具后审核启用。仅远端声明只读的工具会发布给 Runtime。"
        search-placeholder="搜索名称、端点或父类模板"
        :columns="externalColumns"
        :form-fields="externalFormFields"
        :defaults="externalDefaults"
        :searchable-fields="['name', 'endpoint', 'parentToolName', 'workflowId']"
        :list-action="externalApi.list"
        :save-action="externalApi.save"
        :remove-action="externalApi.remove"
        :toggle-action="externalApi.setEnabled"
        :extra-actions="externalActions"
        form-subtitle="先选择 API、数据库或 HTTP 父类模板与 MCP 执行工作流，再发现并审核远端工具。"
        @notify="$emit('notify', $event)"
        @error="$emit('error', $event)"
        @loaded="syncSelectedService"
      />
    </el-tab-pane>
    <el-tab-pane label="本机接入凭证" name="inbound">
      <CrudCatalog
        title="本机接入凭证"
        subtitle="维护可调用当前服务的 MCP 客户端注册信息。"
        search-placeholder="搜索服务名、端点、环境或权限组"
        :columns="columns"
        :form-fields="formFields"
        :defaults="defaults"
        :searchable-fields="['name', 'endpoint', 'environment', 'permissionGroup']"
        :list-action="api.list"
        :save-action="api.save"
        :remove-action="api.remove"
        :toggle-action="api.setEnabled"
        form-subtitle="注册可访问当前 MCP Server 的客户端或上游服务。请填写服务名称、访问端点和运行环境，路由标签与能力描述会用于后续检索和授权。"
        @notify="$emit('notify', $event)"
        @error="$emit('error', $event)"
      />
    </el-tab-pane>
  </el-tabs>

  <el-dialog v-model="templatesOpen" :title="`${selectedService?.name || ''} · 工具模板`" width="min(900px, 94vw)">
    <p>归属父类：{{ parentTitle(selectedService?.parentToolName) }} · 执行工作流：{{ selectedService?.workflowId }}</p>
    <el-table :data="selectedService?.templates || []" border stripe empty-text="尚未发现工具，请先点击“发现工具”">
      <el-table-column prop="title" label="模板名称" min-width="170" />
      <el-table-column prop="name" label="远端工具名" min-width="180" />
      <el-table-column prop="description" label="说明" min-width="220" show-overflow-tooltip />
      <el-table-column label="运行策略" width="110">
        <template #default="{ row }"><el-tag :type="row.readOnly ? 'success' : 'warning'">{{ row.readOnly ? '只读可调用' : '不自动调用' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="{ row }"><el-button link type="primary" :disabled="!selectedService?.enabled || !row.readOnly" @click="selectTemplate(row)">测试</el-button></template>
      </el-table-column>
    </el-table>
    <template v-if="selectedTemplate">
      <p>测试 {{ selectedTemplate.name }}：输入 JSON 参数，调用仅限已启用的只读模板。</p>
      <el-input v-model="invokeArguments" type="textarea" :rows="5" spellcheck="false" />
      <el-button type="primary" :loading="invoking" @click="invokeTemplate">执行测试</el-button>
      <el-input v-if="invokeResult" v-model="invokeResult" type="textarea" :rows="8" readonly />
    </template>
  </el-dialog>
</template>

<script src="../scripts/views/McpServicesView.js"></script>

