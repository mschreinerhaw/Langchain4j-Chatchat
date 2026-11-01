<template>
  <div class="view-stack">
    <CategoryCardPager
      v-if="activeTab === 'queries'"
      :cards="categoryCards"
      :selected-key="selectedCategory"
      aria-label="数据能力分类"
      @select="selectCategory"
    />

    <el-card
      v-if="activeTab !== 'queries'"
      class="workspace-panel el-workspace-card"
      shadow="never"
    >
      <template #header>
        <div class="panel-heading">
          <div>
            <h2>{{ pageTitle }}</h2>
            <p v-if="section === 'queries'"
              >统一管理查询模板，支持 SQL Studio 和手工配置两种来源。</p
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
      subtitle="统一管理查询模板，按业务分类查找和使用。"
      search-placeholder="搜索模板名称、描述或数据源"
      :columns="columns"
      :form-fields="sharedFormFields"
      :load-item="loadQueryItem"
      :query-sources="querySources"
      :query-family="queryFamily"
      :defaults="defaults"
      :list-filters="[
        {
          key: 'sourceKind',
          label: '模板来源',
          options: [
            { value: 'SQL_STUDIO', label: 'SQL Studio' },
            { value: 'MANUAL', label: '手工模板' },
          ],
        },
      ]"
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
      :save-action="saveQuery"
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
      form-subtitle="维护模板用途、数据源与查询参数。"
      @notify="$emit('notify', $event)"
      @error="$emit('error', $event)"
      @result="$emit('result', $event)"
    />

    <ModalPanel
      v-if="studioDetail"
      :open="studioDetailOpen"
      :title="studioDetail.title || studioDetail.toolName"
      subtitle="查询模板详情"
      wide
      @close="studioDetailOpen = false"
    >
      <div class="database-query-detail">
        <p class="database-query-description">{{ studioDetail.description || "暂无描述" }}</p>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="业务分类">{{
            queryCategory(studioDetail)
          }}</el-descriptions-item>
          <el-descriptions-item label="状态"
            ><el-tag :type="studioDetail.enabled === false ? 'info' : 'success'" effect="light">{{
              queryStatus(studioDetail)
            }}</el-tag></el-descriptions-item
          >
          <el-descriptions-item label="数据源">{{
            queryDatasource(studioDetail)
          }}</el-descriptions-item>
          <el-descriptions-item label="来源">{{
            studioDetail.sourceKind === "SQL_STUDIO" ? "SQL Studio" : "手工模板"
          }}</el-descriptions-item>
          <el-descriptions-item v-if="studioDetail.sourceKind === 'SQL_STUDIO'" label="发布时间">{{
            queryDate(studioDetail.publishedAt)
          }}</el-descriptions-item>
          <el-descriptions-item v-if="studioDetail.sourceKind === 'SQL_STUDIO'" label="发布版本"
            >v{{ studioDetail.version }}</el-descriptions-item
          >
        </el-descriptions>
        <p v-if="studioDetail.sourceKind === 'SQL_STUDIO'" class="database-query-help"
          >名称、描述和分类可在当前列表编辑，测试使用同一参数窗口；查询内容由来源工程维护。</p
        >
        <el-collapse
          v-model="detailSections"
          class="database-query-technical"
          aria-label="模板技术详情"
          @change="loadDetailMetadata"
        >
          <el-collapse-item title="查询与参数" name="query">
            <template v-if="studioDetail.sourceKind === 'SQL_STUDIO'">
              <h4>查询语句</h4><pre>{{ studioDetail.sqlTemplate }}</pre> <h4>参数定义</h4
              ><pre>{{ prettyJson(studioDetail.parameterSchema) }}</pre>
            </template>
            <template v-else>
              <h4>查询步骤</h4
              ><pre>{{ prettyJson(studioDetail.sqlSteps || studioDetail.sqlTemplate) }}</pre>
              <h4>参数定义</h4><pre>{{ prettyJson(studioDetail.inputSchema) }}</pre>
            </template>
          </el-collapse-item>
          <el-collapse-item title="技术记录" name="records">
            <dl class="database-query-identifiers">
              <dt>工具标识</dt><dd>{{ studioDetail.toolName }}</dd> <dt>模板编号</dt
              ><dd>{{ studioDetail.code || studioDetail.id }}</dd>
              <template v-if="studioDetail.sourceKind === 'SQL_STUDIO'">
                <dt>所属租户</dt><dd>{{ studioDetail.tenantId }}</dd> <dt>发布者</dt
                ><dd>{{ studioDetail.createdBy }}</dd> <dt>发布编号</dt
                ><dd>{{ studioDetail.publicationId }}</dd> <dt>来源工程</dt
                ><dd>{{ studioDetail.source?.projectId }}</dd> <dt>来源文件</dt
                ><dd
                  >{{ studioDetail.source?.fileId }} / v{{ studioDetail.source?.fileVersion }}</dd
                >
              </template>
            </dl>
            <template v-if="studioDetail.sourceKind === 'SQL_STUDIO'">
              <h4>试执行记录</h4><pre>{{ prettyJson(studioDetail.testEvidence) }}</pre>
              <h4>发布历史</h4><pre>{{ prettyJson(studioDetail.history) }}</pre>
            </template>
            <template v-else>
              <h4>元数据</h4>
              <p v-if="detailMetadataLoading">正在读取元数据…</p>
              <p v-else-if="detailMetadataError"
                >元数据暂不可用。<el-button link type="primary" @click="loadDetailMetadata"
                  >重试</el-button
                ></p
              >
              <pre v-else>{{ prettyJson(studioDetail.technicalMetadata) }}</pre>
            </template>
          </el-collapse-item>
          <el-collapse-item
            v-if="studioDetail.sourceKind === 'SQL_STUDIO'"
            title="权限说明"
            name="permissions"
          >
            <p>{{ studioDetail.accessNotice }}</p>
            <pre v-if="studioDetail.authorizationRules">{{
              prettyJson(studioDetail.authorizationRules)
            }}</pre>
            <template v-if="studioDetail.agentScopeStatus">
              <p>{{ studioDetail.agentScopeNotice }}</p>
              <p v-if="studioDetail.agentScopeStatus === 'UNAVAILABLE'"
                >当前无法读取 Agent 授权范围，请稍后刷新。</p
              >
              <pre v-else>{{ prettyJson(studioDetail.accessibleAgents) }}</pre>
            </template>
            <p v-else>当前账号无权读取授权详情。</p>
          </el-collapse-item>
        </el-collapse>
      </div>
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
