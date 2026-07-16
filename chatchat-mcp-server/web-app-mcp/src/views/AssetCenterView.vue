<template>
  <div class="view-stack">
    <el-card class="workspace-panel el-workspace-card" shadow="never">
      <template #header>
        <div class="panel-heading">
          <div>
            <h2>资产中心</h2>
            <p>服务器、数据库和 API 网关资产会自动发布为 MCP Tool。</p>
          </div>
          <div class="panel-actions">
            <el-button plain :loading="busyAction === 'ops'" @click="refreshOpsTools">刷新运维/API网关工具</el-button>
            <el-button plain :loading="busyAction === 'sql'" @click="refreshSqlTools">刷新数据库工具</el-button>
            <el-button plain type="primary" :loading="busyAction === 'asset-index'" @click="openAssetIndexRebuild">重建资产索引</el-button>
            <el-button plain type="primary" :loading="busyAction === 'template-index'" @click="rebuildTemplateIndex">重建模板索引</el-button>
          </div>
        </div>
      </template>

      <el-tabs v-model="activeTab" class="workspace-tabs">
        <el-tab-pane label="服务器资产" name="ssh" />
        <el-tab-pane label="数据库资产" name="sql" />
        <el-tab-pane label="API 网关资产" name="http" />
        <el-tab-pane label="执行模板" name="templates" />
        <el-tab-pane label="索引检索" name="index-search" />
      </el-tabs>
    </el-card>

    <CrudCatalog
      v-if="activeTab === 'ssh'"
      title="服务器资产"
      subtitle="维护 Linux/SSH 运维工具可用的主机资产。"
      search-placeholder="搜索名称、工具、Host、用户、标签或环境"
      :columns="sshColumns"
      :form-fields="sshFields"
      :defaults="sshDefaults"
      :searchable-fields="['name', 'toolName', 'title', 'description', 'hostname', 'username', 'environment', 'tags']"
      :list-action="api.listSsh"
      :save-action="api.saveSsh"
      :remove-action="api.deleteSsh"
      :test-action="testSsh"
      :form-test-action="testSsh"
      form-test-label="测试连接"
      :rebuild-action="() => api.rebuildAssetIndex('ssh_host')"
      rebuild-label="重建服务器索引"
      @notify="$emit('notify', $event)"
      @error="$emit('error', $event)"
      @result="$emit('result', $event)"
    />

    <CrudCatalog
      v-if="activeTab === 'sql'"
      title="数据库资产"
      subtitle="维护数据库连接、元数据范围、模板白名单和安全治理配置。"
      search-placeholder="搜索名称、工具、JDBC、账号、数据库类型或环境"
      :columns="sqlColumns"
      :form-fields="sqlFields"
      :defaults="sqlDefaults"
      :searchable-fields="['name', 'toolName', 'title', 'description', 'jdbcUrl', 'username', 'databaseType', 'environment']"
      :list-action="api.listSql"
      :save-action="api.saveSql"
      :remove-action="api.deleteSql"
      :test-action="testSql"
      :form-test-action="testSql"
      form-test-label="测试连接"
      :extra-actions="sqlExtraActions"
      :rebuild-action="api.rebuildSelectedSqlAssetIndexes"
      rebuild-requires-selection
      rebuild-label="重建数据库索引"
      @notify="$emit('notify', $event)"
      @error="$emit('error', $event)"
      @result="$emit('result', $event)"
    />

    <CrudCatalog
      v-if="activeTab === 'http'"
      title="API 网关资产"
      subtitle="维护可复用的 HTTP/API 网关资产。"
      search-placeholder="搜索名称、工具、URL、环境、方法或标签"
      :columns="httpColumns"
      :form-fields="httpFields"
      :defaults="httpDefaults"
      :searchable-fields="['name', 'toolName', 'title', 'description', 'urlTemplate', 'environment', 'method', 'category', 'tags']"
      :list-action="api.listHttp"
      :save-action="api.saveHttp"
      :remove-action="api.deleteHttp"
      :test-action="testHttp"
      :form-test-action="testHttp"
      form-test-label="测试请求"
      :rebuild-action="() => api.rebuildAssetIndex('http_endpoint')"
      rebuild-label="重建网关索引"
      @notify="$emit('notify', $event)"
      @error="$emit('error', $event)"
      @result="$emit('result', $event)"
    />

    <section v-if="activeTab === 'templates'" class="workspace-panel">
      <header class="panel-heading">
        <div>
          <h2>执行模板</h2>
          <p>统一维护 SSH 命令模板和 SQL 运维模板。</p>
        </div>
        <div class="panel-actions">
          <el-button plain :loading="busyAction === 'template-index'" @click="rebuildTemplateIndex">重建模板索引</el-button>
          <el-button type="primary" plain @click="openTemplateImport">导入模板</el-button>
        </div>
      </header>

      <el-tabs v-model="activeTemplateTab" class="workspace-tabs">
        <el-tab-pane label="SSH 命令模板" name="ssh-template" lazy>
          <CrudCatalog
            v-if="activeTemplateTab === 'ssh-template'"
            key="ssh-command-template-catalog"
            title="SSH 命令模板"
            subtitle="维护 Linux 命令模板、参数 Schema、风险等级和意图信号。"
            search-placeholder="搜索模板编号、名称、描述、分类或意图信号"
            :columns="commandTemplateColumns"
            :form-fields="commandTemplateFields"
            :defaults="commandTemplateDefaults"
            :list-filters="commandTemplateListFilters"
            :searchable-fields="['code', 'title', 'description', 'category', 'riskLevel', 'intentSignalsJson']"
            :list-action="api.listCommandTemplates"
            :save-action="api.saveCommandTemplate"
              :remove-action="api.deleteCommandTemplate"
              :rebuild-action="api.rebuildTemplateIndex"
              rebuild-label="重建模板索引"
              @loaded="sshCommandTemplates = $event"
              @notify="$emit('notify', $event)"
              @error="$emit('error', $event)"
            />
        </el-tab-pane>
        <el-tab-pane label="SQL 运维模板" name="sql-template" lazy>
          <CrudCatalog
            v-if="activeTemplateTab === 'sql-template'"
            key="sql-ops-template-catalog"
            title="SQL 运维模板"
            subtitle="维护 SQL 运维查询模板、数据库类型、路由标签和意图信号。"
            search-placeholder="搜索模板编号、名称、描述、分类、数据库类型或意图信号"
            :columns="sqlTemplateColumns"
            :form-fields="sqlTemplateFields"
            :defaults="sqlTemplateDefaults"
            :list-filters="sqlTemplateListFilters"
            :searchable-fields="['code', 'title', 'description', 'category', 'databaseType', 'datasourceId', 'intentSignalsJson']"
            :list-action="api.listSqlTemplates"
            :save-action="api.saveSqlTemplate"
            :remove-action="api.deleteSqlTemplate"
            :rebuild-action="api.rebuildTemplateIndex"
            @loaded="sqlOpsTemplates = $event"
            rebuild-label="重建模板索引"
            @notify="$emit('notify', $event)"
            @error="$emit('error', $event)"
          />
        </el-tab-pane>
      </el-tabs>
    </section>

    <section v-if="activeTab === 'index-search'" class="workspace-panel">
      <header class="panel-heading">
        <div>
          <h2>索引检索测试</h2>
          <p>直接查询当前系统本地索引，查看 MCP 检索实际返回的结构化结果。</p>
        </div>
        <el-button type="primary" :loading="searchBusy" @click="runSearch">检索</el-button>
      </header>

      <el-form class="entity-form" label-position="top" @submit.prevent="runSearch">
        <el-row :gutter="16">
          <el-col :xs="24" :md="8" :lg="4">
            <el-form-item label="索引">
              <el-select v-model="search.indexType" class="w-100">
                <el-option v-for="option in searchIndexOptions" :key="option.value" :label="option.label" :value="option.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8" :lg="5">
            <el-form-item label="查询">
              <el-input v-model.trim="search.query" placeholder="账户交易账户额度分析" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8" :lg="4">
            <el-form-item label="表名">
              <el-input v-model.trim="search.tableName" placeholder="os_historystep" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8" :lg="4">
            <el-form-item label="库/Schema">
              <el-input v-model.trim="search.database" placeholder="livebos" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8" :lg="5">
            <el-form-item label="资产名">
              <el-input v-model.trim="search.assetName" placeholder="TDH大数据集群" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8" :lg="4">
            <el-form-item label="资产类型">
              <el-select v-model="search.assetType" class="w-100">
                <el-option label="全部" value="" />
                <el-option label="sql_datasource" value="sql_datasource" />
                <el-option label="ssh_host" value="ssh_host" />
                <el-option label="http_endpoint" value="http_endpoint" />
                <el-option label="api_service" value="api_service" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8" :lg="4">
            <el-form-item label="环境">
              <el-select v-model="search.env" class="w-100">
                <el-option label="全部" value="" />
                <el-option v-for="option in environmentOptions" :key="option.value" :label="option.label" :value="option.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8" :lg="4">
            <el-form-item label="数据库类型">
              <el-select v-model="search.databaseType" class="w-100" filterable>
                <el-option label="全部" value="" />
                <el-option v-for="option in databaseTypeOptions" :key="option.value" :label="option.label" :value="option.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8" :lg="4">
            <el-form-item label="Limit">
              <el-input-number v-model="search.limit" class="w-100" :min="1" :max="50" controls-position="right" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8" :lg="4">
            <el-form-item label="字段">
              <el-select v-model="search.includeColumns" class="w-100">
                <el-option label="返回" :value="true" />
                <el-option label="不返回" :value="false" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="16" :lg="8">
            <el-form-item label="标签">
              <el-input v-model.trim="search.labels" placeholder="meta,prod" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <el-table v-if="searchRows.length" class="settings-table" :data="searchRows" border stripe>
        <el-table-column prop="kind" label="类型" width="100" />
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column prop="assetType" label="资产类型" min-width="140" />
        <el-table-column prop="database" label="库/Schema" min-width="130" />
        <el-table-column prop="table" label="表名" min-width="150" />
        <el-table-column prop="score" label="分数" width="100" />
        <el-table-column prop="description" label="描述" min-width="220" />
      </el-table>
      <pre v-if="searchResult" class="json-block mt-3"><code>{{ searchResult }}</code></pre>
    </section>

    <ModalPanel :open="templateImportOpen" title="导入模板" subtitle="粘贴模板 DSL，先验证再导入。" wide @close="templateImportOpen = false">
      <el-form class="entity-form" label-position="top" @submit.prevent="validateTemplateDsl">
        <el-form-item label="模板类型">
          <el-select v-model="templateImport.templateType" class="w-100">
            <el-option label="Linux / SSH 命令模板" value="LINUX_CMD" />
            <el-option label="SQL 运维模板" value="SQL_OPS" />
            <el-option label="数据库查询模板" value="DATABASE_QUERY" />
          </el-select>
        </el-form-item>
        <el-form-item label="模板 DSL">
          <el-input v-model="templateImport.dsl" class="codebox" type="textarea" :rows="14" spellcheck="false" />
        </el-form-item>
      </el-form>
      <pre v-if="templateImportResult" class="json-block"><code>{{ templateImportResult }}</code></pre>
      <template #footer>
        <el-button @click="templateImportOpen = false">取消</el-button>
        <el-button plain :loading="busyAction === 'template-validate'" @click="validateTemplateDsl">验证</el-button>
        <el-button type="primary" :loading="busyAction === 'template-import'" @click="importTemplateDsl">导入</el-button>
      </template>
    </ModalPanel>

    <ModalPanel :open="assetIndexRebuildOpen" title="重建资产索引" subtitle="选择要重建的资产索引范围。" @close="assetIndexRebuildOpen = false">
      <el-form class="entity-form" label-position="top" @submit.prevent="submitAssetIndexRebuild">
        <el-form-item label="重建类型">
          <el-select v-model="assetIndexRebuildType" class="w-100">
            <el-option v-for="option in assetIndexRebuildOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assetIndexRebuildOpen = false">取消</el-button>
        <el-button type="primary" :loading="busyAction === 'asset-index'" @click="submitAssetIndexRebuild">重建</el-button>
      </template>
    </ModalPanel>
  </div>
</template>

<script src="../scripts/views/AssetCenterView.js"></script>
