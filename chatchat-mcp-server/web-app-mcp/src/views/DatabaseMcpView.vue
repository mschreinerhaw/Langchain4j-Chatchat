<template>
  <div class="view-stack">
    <CategoryCardPager
      v-if="activeTab === 'queries'"
      :cards="categoryCards"
      :selected-key="selectedCategory"
      aria-label="数据能力分类"
      @select="selectCategory"
    />

    <el-card class="workspace-panel el-workspace-card" shadow="never">
      <template #header>
        <div class="panel-heading">
          <div>
            <h2>{{ pageTitle }}</h2>
            <p v-if="section === 'queries'"
              >本页展示手工查询模板与 SQL Studio 发布记录；SQL Studio 记录通过详情查看，修改及版本恢复沿用源工程。手工查询父模板
              {{ parentTemplateName }}
              默认不发布。通过“子模板发布”继承，限定角色、用户及可用查询模板。</p
            >
            <p v-else>沿用原有交易日历和批量导入配置流程。</p>
          </div>
        </div>
      </template>
    </el-card>

    <CrudCatalog
      v-if="activeTab === 'queries'"
      ref="catalog"
      :title="pageTitle"
      subtitle="分类维护金融数据服务，并将安全查询发布为带业务元数据的 MCP 工具。"
      search-placeholder="搜索名称、描述、实现步骤、工作台步骤、标签或数据源"
      :columns="columns"
      :form-fields="formFields"
      :query-sources="querySources"
      :query-family="queryFamily"
      :defaults="defaults"
      :list-filters="[{ key: 'sourceKind', label: '模板来源', options: [{ value: 'SQL_STUDIO', label: 'SQL Studio' }, { value: 'MANUAL', label: '手工模板' }] }]"
      :searchable-fields="[
        'toolName',
        'title',
        'capabilityCategory',
        'businessGroupName',
        'domain',
        'businessScope',
        'description',
        'implementationSteps',
        'tags',
        'indexTags',
        'sqlSteps',
        'datasourceId',
        'sourceLabel',
        'code',
        'parameterSummary',
      ]"
      :list-action="listQueries"
      :save-action="api.save"
      :remove-action="api.remove"
      :batch-remove="api.batchRemove"
      :toggle-action="api.setEnabled"
      :test-action="testSaved"
      :extra-actions="metadataActions"
      :form-test-action="testDraft"
      form-test-label="测试调用"
      form-preview-type="databaseQuery"
      :rebuild-action="api.rebuildIndex"
      rebuild-label="重建索引"
      form-subtitle="配置可检索的数据能力。显示名称、描述、实现步骤、工作台步骤名称与说明、业务及索引标签都会进入 BM25+kNN 混合索引。"
      @notify="$emit('notify', $event)"
      @error="$emit('error', $event)"
      @result="$emit('result', $event)"
    />

    <ModalPanel v-if="studioDetail" :open="studioDetailOpen" :title="studioDetail.title" wide @close="studioDetailOpen = false">
      <el-alert :title="studioDetail.accessNotice" type="info" :closable="false" show-icon />
      <el-descriptions :column="2" border class="mt-3">
        <el-descriptions-item label="来源">SQL Studio</el-descriptions-item>
        <el-descriptions-item label="MCP 工具"><code>{{ studioDetail.toolName }}</code></el-descriptions-item>
        <el-descriptions-item label="发布版本">v{{ studioDetail.version }}</el-descriptions-item>
        <el-descriptions-item label="发布状态">{{ studioDetail.publicationState }}</el-descriptions-item>
        <el-descriptions-item label="数据源">{{ studioDetail.datasourceName }}</el-descriptions-item>
        <el-descriptions-item label="发布时间">{{ studioDetail.publishedAt }}</el-descriptions-item>
        <el-descriptions-item label="所属租户">{{ studioDetail.tenantId }}</el-descriptions-item>
        <el-descriptions-item label="发布者">{{ studioDetail.createdBy }}</el-descriptions-item>
        <el-descriptions-item label="模板编号">{{ studioDetail.code }}</el-descriptions-item>
        <el-descriptions-item label="发布编号">{{ studioDetail.publicationId }}</el-descriptions-item>
        <el-descriptions-item label="源工程">{{ studioDetail.source?.projectId }}</el-descriptions-item>
        <el-descriptions-item label="源文件">{{ studioDetail.source?.fileId }} / v{{ studioDetail.source?.fileVersion }}</el-descriptions-item>
      </el-descriptions>
      <h3 class="mt-3">SQL</h3>
      <pre class="json-block"><code>{{ studioDetail.sqlTemplate }}</code></pre>
      <h3>参数 Schema</h3>
      <pre class="json-block"><code>{{ prettyJson(studioDetail.parameterSchema) }}</code></pre>
      <h3>真实试执行记录</h3>
      <pre class="json-block"><code>{{ prettyJson(studioDetail.testEvidence) }}</code></pre>
      <h3>发布版本记录</h3>
      <pre class="json-block"><code>{{ prettyJson(studioDetail.history) }}</code></pre>
      <h3>同步授权规则</h3>
      <p>调用还需满足 Runtime OS 的 Agent 工具绑定及当前用户、角色授权；以下规则不替代运行时权限判定。</p>
      <pre v-if="studioDetail.authorizationRules" class="json-block"><code>{{ prettyJson(studioDetail.authorizationRules) }}</code></pre>
      <template v-if="studioDetail.agentScopeStatus">
        <h4>发布者可调用的 Agent 范围</h4>
        <el-alert :title="studioDetail.agentScopeNotice" type="info" :closable="false" />
        <el-alert v-if="studioDetail.agentScopeStatus === 'UNAVAILABLE'" title="当前无法读取 Agent 授权范围，请稍后刷新。" type="warning" :closable="false" />
        <pre v-else class="json-block"><code>{{ prettyJson(studioDetail.accessibleAgents) }}</code></pre>
      </template>
      <p v-else>当前管理账号无权读取授权详情，请使用 Runtime OS 对应租户账号查看。</p>
      <template #footer><el-button @click="studioDetailOpen = false">关闭</el-button></template>
    </ModalPanel>

    <section v-if="activeTab === 'calendar'" class="workspace-panel database-calendar-panel">
      <header class="panel-heading">
        <div>
          <h2>交易日历配置</h2>
          <p>用于 trade_date 等动态日期参数解析。</p>
        </div>
        <div class="panel-actions">
          <el-button plain size="small" :disabled="busy" @click="loadTradingCalendar"
            >刷新</el-button
          >
          <el-button plain type="primary" size="small" :disabled="busy" @click="testCalendar"
            >测试查询</el-button
          >
          <el-button type="primary" size="small" :disabled="busy" @click="saveCalendar"
            >保存</el-button
          >
        </div>
      </header>
      <el-row :gutter="12" class="database-calendar-form">
        <el-col :xs="24" :md="12" class="database-calendar-function-field">
          <label class="form-label">交易日数据库资产</label>
          <el-select
            v-model="calendar.datasourceId"
            class="w-100"
            clearable
            filterable
            placeholder="请选择交易日数据库资产"
            @change="resetCalendarFunctionTestState"
          >
            <el-option
              v-for="option in datasourceSelectOptions"
              :key="String(option.value)"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-col>
        <el-col v-if="false" :xs="24" :md="10">
          <label class="form-label">数据源 ID</label>
          <el-input v-model.trim="calendar.datasourceId" placeholder="SQL 数据源 ID" />
        </el-col>
        <el-col :xs="24">
          <label class="form-label">日历 SQL</label>
          <el-input
            v-model="calendar.sqlTemplate"
            class="codebox"
            type="textarea"
            :rows="5"
            spellcheck="false"
            @input="resetCalendarFunctionTestState"
          />
          <p class="field-help"
            >SQL 必须返回两列：自然日 yyyyMMdd 和交易日 yyyyMMdd；只允许 SELECT。</p
          >
        </el-col>
        <el-col :xs="24" :md="10">
          <label class="form-label">动态日期函数</label>
          <el-select
            v-model="calendarFunctionName"
            class="w-100"
            filterable
            :disabled="!calendarQueryTestPassed"
            placeholder="请先测试查询"
          >
            <el-option
              v-for="option in tradingCalendarFunctionOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <el-button
            plain
            :disabled="busy || !calendarQueryTestPassed"
            @click="testCalendarFunction"
            >测试函数</el-button
          >
        </el-col>
        <el-col v-if="false" :xs="24" :md="6" class="form-action-col">
          <el-button
            plain
            :disabled="busy || !calendarQueryTestPassed"
            @click="testCalendarFunction"
            >测试函数</el-button
          >
        </el-col>
      </el-row>
    </section>

    <section v-if="activeTab === 'dsl'" class="workspace-panel database-import-panel">
      <header class="panel-heading">
        <div>
          <h2>批量导入</h2>
          <p>批量校验并导入数据库查询模板。</p>
        </div>
        <div class="panel-actions">
          <el-button plain :disabled="busy" @click="validateDsl">校验内容</el-button>
          <el-button type="primary" :disabled="busy" @click="importDsl">批量导入</el-button>
        </div>
      </header>
      <el-row :gutter="12">
        <el-col :xs="24" :md="12">
          <label class="form-label">目标数据源资产</label>
          <el-select
            v-model="dslDatasourceId"
            class="w-100"
            clearable
            filterable
            placeholder="可选；默认使用模板内 datasourceId"
          >
            <el-option
              v-for="option in querySourceOptions"
              :key="String(option.value)"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-col>
        <el-col v-if="false" :xs="24" :md="12">
          <label class="form-label">目标数据源 ID</label>
          <el-input v-model.trim="dslDatasourceId" placeholder="可选；导入数据库查询模板时使用" />
        </el-col>
        <el-col :xs="24" :md="12">
          <label class="form-label">目标注册表</label>
          <el-input v-model.trim="dslTargetRegistry" placeholder="可选" />
        </el-col>
        <el-col :xs="24">
          <el-input
            v-model="dslBody"
            class="codebox"
            type="textarea"
            :rows="10"
            spellcheck="false"
            placeholder="粘贴批量导入 JSON"
          />
        </el-col>
      </el-row>
      <pre v-if="dslResult" class="json-block mt-3"><code>{{ dslResult }}</code></pre>
    </section>
  </div>
</template>

<script src="../scripts/views/DatabaseMcpView.js"></script>
