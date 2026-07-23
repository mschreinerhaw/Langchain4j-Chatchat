<template>
  <div class="view-stack">
    <el-card class="workspace-panel el-workspace-card" shadow="never">
      <template #header>
        <div class="panel-heading">
          <div>
            <h2>数据库查询</h2>
            <p>注册面向分析场景的只读 SQL 查询，并将其发布为 MCP 工具。</p>
          </div>
        </div>
      </template>

      <el-tabs v-model="activeTab" class="workspace-tabs">
        <el-tab-pane label="查询模板" name="queries" />
        <el-tab-pane label="交易日历" name="calendar" />
        <el-tab-pane label="批量导入" name="dsl" />
      </el-tabs>
    </el-card>

    <CrudCatalog
      v-if="activeTab === 'queries'"
      ref="catalog"
      title="数据库查询"
      subtitle="将安全 SQL 查询发布为 MCP 工具。"
      search-placeholder="搜索工具名称、标题、分类、描述或数据源"
      :columns="columns"
      :form-fields="formFields"
      :defaults="defaults"
      :searchable-fields="['toolName', 'title', 'businessGroup', 'businessGroupName', 'description', 'datasourceId']"
      :list-action="api.list"
      :save-action="api.save"
      :remove-action="api.remove"
      :batch-remove="api.batchRemove"
      :toggle-action="api.setEnabled"
      :test-action="testSaved"
      :form-test-action="testDraft"
      form-test-label="测试调用"
      form-preview-type="databaseQuery"
      :rebuild-action="api.rebuildIndex"
      rebuild-label="重建索引"
      form-subtitle="配置只读 SQL 查询模板。请先填写工具名称、数据源和 SQL，再按 SQL 占位符维护输入参数。"
      @notify="$emit('notify', $event)"
      @error="$emit('error', $event)"
      @result="$emit('result', $event)"
    />

    <section v-if="activeTab === 'calendar'" class="workspace-panel database-calendar-panel">
      <header class="panel-heading">
        <div>
          <h2>交易日历配置</h2>
          <p>用于 trade_date 等动态日期参数解析。</p>
        </div>
        <div class="panel-actions">
          <el-button plain size="small" :disabled="busy" @click="loadTradingCalendar">刷新</el-button>
          <el-button plain type="primary" size="small" :disabled="busy" @click="testCalendar">测试查询</el-button>
          <el-button type="primary" size="small" :disabled="busy" @click="saveCalendar">保存</el-button>
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
          <el-input v-model="calendar.sqlTemplate" class="codebox" type="textarea" :rows="5" spellcheck="false" @input="resetCalendarFunctionTestState" />
          <p class="field-help">SQL 必须返回两列：自然日 yyyyMMdd 和交易日 yyyyMMdd；只允许 SELECT。</p>
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
          <el-button plain :disabled="busy || !calendarQueryTestPassed" @click="testCalendarFunction">测试函数</el-button>
        </el-col>
        <el-col v-if="false" :xs="24" :md="6" class="form-action-col">
          <el-button plain :disabled="busy || !calendarQueryTestPassed" @click="testCalendarFunction">测试函数</el-button>
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
          <label class="form-label">目标数据库资产</label>
          <el-select
            v-model="dslDatasourceId"
            class="w-100"
            clearable
            filterable
            placeholder="可选；默认使用模板内 datasourceId"
          >
            <el-option
              v-for="option in datasourceSelectOptions"
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
