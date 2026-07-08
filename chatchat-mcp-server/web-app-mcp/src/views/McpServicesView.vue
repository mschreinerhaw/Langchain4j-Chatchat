<template>
  <CrudCatalog
    title="MCP 服务"
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
</template>

<script src="../scripts/views/McpServicesView.js"></script>

