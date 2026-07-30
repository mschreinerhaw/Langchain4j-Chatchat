<template>
  <div class="view-stack">
    <CrudCatalog
      title="业务分类维护"
      subtitle="统一维护服务器、数据库、API 资产及 API、数据库模板共用的业务分类。"
      search-placeholder="搜索分类编码、名称、业务领域、用途或检索关键词"
      :columns="columns"
      :form-fields="formFields"
      :defaults="defaults"
      :searchable-fields="['code', 'name', 'domain', 'description', 'keywords']"
      :list-action="api.list"
      :save-action="api.save"
      :remove-action="api.remove"
      :page-size="10"
      form-subtitle="分类由业务用户统一维护。名称、说明和关键词变更后，关联资产与模板继续使用同一分类主数据。"
      @notify="$emit('notify', $event)"
      @error="$emit('error', $event)"
      @result="$emit('result', $event)"
    />
  </div>
</template>

<script src="../scripts/views/BusinessCategoriesView.js"></script>
