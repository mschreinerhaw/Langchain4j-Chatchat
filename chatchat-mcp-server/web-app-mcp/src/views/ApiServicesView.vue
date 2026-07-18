<template>
  <el-tabs v-model="activeTab" class="workspace-tabs">
    <el-tab-pane label="API 服务维护" name="services">
      <CrudCatalog
        ref="catalog"
        title="API 服务"
        subtitle="将已登记 API 发布为 MCP 工具。"
        search-placeholder="搜索工具名称、显示名称、描述或分组"
        :columns="columns"
        :form-fields="formFields"
        :defaults="defaults"
        :searchable-fields="searchableFields"
        :list-action="api.list"
        :save-action="api.save"
        :remove-action="api.remove"
        :batch-remove="api.batchRemove"
        :toggle-action="api.setEnabled"
        :test-action="testService"
        :rebuild-action="api.rebuildIndex"
        rebuild-label="重建索引"
        form-subtitle="把已维护的 API 网关资产发布为 MCP 工具。这里主要配置工具名称、展示信息和模型调用时需要传入的业务参数。"
        @notify="$emit('notify', $event)"
        @error="$emit('error', $event)"
        @result="$emit('result', $event)"
      />
    </el-tab-pane>

    <el-tab-pane label="LiveData 导入" name="livedata">
      <section class="workspace-panel">
        <header class="panel-heading">
          <div>
            <h2>LiveData 导入</h2>
            <p>从 LiveData API 列表批量注册 MCP API 服务。</p>
          </div>
          <div class="panel-actions">
            <el-button plain :disabled="busy" :loading="busy" @click="loadLivedata">加载 API</el-button>
            <el-button type="primary" :disabled="busy || !selectedLivedata.size" :loading="busy" @click="registerSelected">
              注册选中
            </el-button>
          </div>
        </header>

        <div class="split-toolbar">
          <el-input
            v-model.trim="livedataKeyword"
            class="search-input"
            clearable
            placeholder="搜索 API 名称、工具名、服务或 namespace"
          />
          <el-checkbox v-model="overwriteExisting">覆盖同名工具</el-checkbox>
        </div>

        <el-table class="settings-table" :data="filteredLivedata" border stripe empty-text="暂无 LiveData API">
          <el-table-column label="" width="54" align="center">
            <template #default="{ row }">
              <el-checkbox :model-value="selectedLivedata.has(row.id)" @change="toggleLivedata(row.id)" />
            </template>
          </el-table-column>
          <el-table-column label="API" min-width="180">
            <template #default="{ row }">{{ row.name || row.title || '-' }}</template>
          </el-table-column>
          <el-table-column label="工具名称" min-width="180">
            <template #default="{ row }"><code>{{ row.toolName || '-' }}</code></template>
          </el-table-column>
          <el-table-column label="服务" min-width="160">
            <template #default="{ row }">{{ row.serviceName || row.namespace || '-' }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="110" />
          <el-table-column prop="version" label="版本" width="120" />
        </el-table>
      </section>

      <el-dialog v-model="livedataDatasourceDialogOpen" title="选择 LiveData 数据源" width="560px">
        <p class="form-dialog-subtitle">
          从数据资产中心已维护并启用的数据库资产中选择 LiveData 数据源。确认后将保存绑定关系并加载可注册的 API。
        </p>
        <el-form label-position="top">
          <el-form-item label="数据库资产" required>
            <el-select
              v-model="selectedLivedataDatasourceId"
              class="w-100"
              filterable
              placeholder="请选择 LiveData 数据库资产"
              :loading="busy"
            >
              <el-option
                v-for="option in livedataDatasourceOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
        </el-form>
        <el-empty
          v-if="!busy && !livedataDatasourceOptions.length"
          description="暂无已启用的数据库资产，请先到数据资产中心维护并启用 LiveData 数据源"
          :image-size="72"
        />
        <template #footer>
          <el-button :disabled="busy" @click="livedataDatasourceDialogOpen = false">取消</el-button>
          <el-button
            type="primary"
            :loading="busy"
            :disabled="busy || !selectedLivedataDatasourceId"
            @click="confirmLivedataDatasource"
          >
            确认并加载
          </el-button>
        </template>
      </el-dialog>
    </el-tab-pane>
  </el-tabs>
</template>

<script src="../scripts/views/ApiServicesView.js"></script>
