<template>
  <el-card class="workspace-panel el-workspace-card" shadow="never">
    <template #header>
      <div class="panel-heading">
        <div>
          <h2>{{ title }}</h2>
          <p v-if="subtitle">{{ subtitle }}</p>
        </div>
        <div class="panel-actions">
          <el-select
            v-for="filter in listFilters"
            :key="filter.key"
            v-model="listFilterValues[filter.key]"
            class="catalog-filter-select"
            clearable
            filterable
            :placeholder="filter.placeholder || filter.label"
          >
            <el-option
              v-for="option in listFilterOptions(filter)"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <el-input v-model.trim="keyword" class="search-input" clearable :placeholder="searchPlaceholder">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button
            v-if="rebuildLabel"
            plain
            :loading="busy"
            :disabled="Boolean(rowOperation) || (rebuildRequiresSelection && !selectedIds.size)"
            @click="runRebuild"
          >
            <el-icon><Refresh /></el-icon>
            <span>{{ rebuildLabel }}</span>
          </el-button>
          <el-button v-if="refreshAction" plain :loading="busy" @click="load">
            <el-icon><Refresh /></el-icon>
            <span>刷新</span>
          </el-button>
          <el-button type="primary" @click="openCreate">
            <el-icon><Plus /></el-icon>
            <span>新增</span>
          </el-button>
        </div>
      </div>
    </template>

    <div class="bulk-row">
      <el-text type="info">共 {{ items.length }} 条，匹配 {{ filtered.length }} 条，已选择 {{ selectedIds.size }} 条</el-text>
      <el-button-group>
        <el-button plain @click="selectVisible">选择当前页</el-button>
        <el-button plain @click="clearSelection">清空选择</el-button>
        <el-button v-if="batchRemove" type="danger" plain :disabled="!selectedIds.size || busy" @click="removeSelected">
          删除选中
        </el-button>
      </el-button-group>
    </div>

    <el-table
      class="catalog-table"
      :data="visibleItems"
      border
      stripe
      row-key="id"
      empty-text="暂无数据"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="48" />
      <el-table-column v-for="column in columns" :key="column.key" :prop="column.key" :label="column.label" min-width="140">
        <template #default="{ row }">
          <el-tag v-if="column.type === 'badge'" :type="tagType(row[column.key])" effect="light">
            {{ formatColumn(row, column) }}
          </el-tag>
          <code v-else-if="column.type === 'code'">{{ formatColumn(row, column) }}</code>
          <span v-else>{{ formatColumn(row, column) }}</span>
        </template>
      </el-table-column>
      <el-table-column fixed="right" label="操作" width="230">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="busy || Boolean(rowOperation)" @click="openEdit(row)">编辑</el-button>
          <el-button
            v-if="testAction"
            link
            type="primary"
            :loading="isRowOperation('test', row)"
            :disabled="rowActionDisabled('test', row)"
            @click="testItem(row)"
          >测试</el-button>
          <el-button
            v-for="action in extraActions"
            :key="action.key || extraActionLabel(action, row)"
            link
            :type="extraActionType(action, row)"
            :loading="isRowOperation('extra', row, action)"
            :disabled="extraActionDisabled(action, row)"
            @click="runExtraAction(action, row)"
          >
            {{ extraActionLabel(action, row) }}
          </el-button>
          <el-button v-if="toggleAction && row.id" link type="warning" :disabled="busy || Boolean(rowOperation)" @click="toggleItem(row)">
            {{ row.enabled === false ? '启用' : '停用' }}
          </el-button>
          <el-button v-if="removeAction && row.id" link type="danger" :disabled="busy || Boolean(rowOperation)" @click="removeItem(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <footer class="pagination-row">
      <el-text type="info">第 {{ page }} / {{ pageCount }} 页</el-text>
      <el-pagination
        background
        layout="prev, pager, next"
        :current-page="page"
        :page-size="pageSize"
        :total="filtered.length"
        @current-change="page = $event"
      />
    </footer>

    <ModalPanel :open="formOpen" :title="formTitle" :subtitle="formSubtitle" wide :workbench="hasDatabaseWorkflowField" :maximizable="hasDatabaseWorkflowField" @close="formOpen = false">
      <el-form class="entity-form" label-position="top" @submit.prevent="saveForm">
        <div class="entity-form-layout">
          <section
            v-for="section in formSections"
            :key="section.key"
            class="form-section"
            :class="{ 'is-collapsed': isFormSectionCollapsed(section.key) }"
          >
            <button
              class="form-section-header"
              type="button"
              :aria-expanded="!isFormSectionCollapsed(section.key)"
              @click="toggleFormSection(section.key)"
            >
              <span>
                <strong>{{ section.title }}</strong>
                <small v-if="section.subtitle">{{ section.subtitle }}</small>
              </span>
              <span class="form-section-count">{{ renderedSectionFields(section).length }} 个配置区</span>
            </button>
            <el-row v-show="!isFormSectionCollapsed(section.key)" class="form-section-grid" :gutter="12">
              <template v-for="field in renderedSectionFields(section)" :key="field.key">
                <el-col :xs="24" :md="fieldColSpan(field)">
              <el-form-item :label="field.label" :required="isFieldRequired(field)">
                <el-select
                  v-if="field.type === 'select'"
                  v-model="form[field.key]"
                  class="w-100"
                  filterable
                  clearable
                  :allow-create="field.allowCreate"
                  :default-first-option="field.allowCreate"
                  :reserve-keyword="false"
                  :placeholder="field.placeholder"
                  :required="isFieldRequired(field)"
                >
                  <el-option v-for="option in fieldOptions(field)" :key="String(option.value)" :label="option.label" :value="option.value" />
                </el-select>
                <div v-else-if="field.type === 'textarea'" class="text-template-field">
                  <el-input
                    v-model="form[field.key]"
                    type="textarea"
                    :rows="field.rows || 3"
                    :placeholder="field.placeholder"
                    :required="isFieldRequired(field)"
                  />
                  <div v-if="field.textPresets?.length" class="text-template-presets">
                    <span>快速模板</span>
                    <el-button
                      v-for="preset in field.textPresets"
                      :key="`${field.key}-${preset.label}`"
                      plain
                      size="small"
                      @click="applyTextPreset(field, preset)"
                    >{{ preset.label }}</el-button>
                  </div>
                </div>
                <el-input
                  v-else-if="field.type === 'json'"
                  v-model="jsonDraft[field.key]"
                  class="codebox"
                  type="textarea"
                  :rows="field.rows || 7"
                  spellcheck="false"
                />
                <div v-else-if="field.type === 'jsonStringList'" class="tag-field">
                  <div class="tag-field-box">
                    <el-tag
                      v-for="(entry, index) in listDraft[field.key]"
                      :key="`${field.key}-${entry}-${index}`"
                      closable
                      effect="light"
                      @close="removeListEntry(field.key, index)"
                    >
                      {{ entry }}
                    </el-tag>
                    <el-text v-if="!listDraft[field.key]?.length" type="info">暂无配置</el-text>
                  </div>
                  <div class="visual-list-input">
                    <el-input
                      v-model.trim="listInput[field.key]"
                      class="tag-field-input"
                      :placeholder="field.placeholder || '输入后回车添加'"
                      @keyup.enter="addListEntry(field)"
                      @blur="addListEntry(field)"
                    />
                    <el-button plain @click="addListEntry(field)">添加</el-button>
                  </div>
                </div>
                <div v-else-if="field.type === 'templatePicker'" class="template-select-field">
                  <div class="visual-list-tags">
                    <el-tag
                      v-for="entry in listDraft[field.key]"
                      :key="`${field.key}-${entry}`"
                      closable
                      effect="light"
                      @close="removeTemplateSelection(field.key, entry)"
                    >
                      {{ entry }}
                    </el-tag>
                    <el-text v-if="!listDraft[field.key]?.length" type="info">暂未选择模板</el-text>
                  </div>
                  <div class="visual-list-input">
                    <el-button type="primary" plain @click="openTemplatePicker(field)">选择模板</el-button>
                    <el-button plain :disabled="!listDraft[field.key]?.length" @click="clearTemplateSelection(field.key)">清空选择</el-button>
                  </div>
                </div>
                <div v-else-if="field.type === 'metadataScopePicker'" class="metadata-scope-editor">
                  <div class="metadata-scope-input-row">
                    <el-input
                      v-model.trim="form[field.key]"
                      :placeholder="field.placeholder || '可手动输入，多个值用逗号分隔'"
                    />
                    <el-button plain @click="toggleMetadataScopePanel(field.key)">选择库/Schema</el-button>
                  </div>
                  <div class="visual-list-tags">
                    <el-tag
                      v-for="entry in metadataScopeSelected(field.key)"
                      :key="`${field.key}-${entry}`"
                      closable
                      effect="light"
                      @close="removeMetadataScopeValue(field.key, entry)"
                    >
                      {{ entry }}
                    </el-tag>
                    <el-text v-if="!metadataScopeSelected(field.key).length" type="info">未选择</el-text>
                  </div>
                  <div v-if="metadataScopeOpenKey === field.key" class="metadata-scope-panel">
                    <el-input v-model.trim="metadataScopeKeyword" clearable size="small" placeholder="搜索库名/Schema" />
                    <div class="metadata-scope-options">
                      <el-button
                        v-for="option in metadataScopeVisibleOptions(field.key)"
                        :key="`${field.key}-${option}`"
                        :type="metadataScopeSelected(field.key).includes(option) ? 'primary' : 'default'"
                        plain
                        size="small"
                        @click="toggleMetadataScopeValue(field.key, option)"
                      >
                        {{ option }}
                      </el-button>
                      <el-text v-if="!metadataScopeVisibleOptions(field.key).length" type="info">
                        测试连接后可选择库名，也可直接手动输入。
                      </el-text>
                    </div>
                  </div>
                </div>
                <div v-else-if="field.type === 'databaseParamConfig'" class="database-param-editor">
                  <div class="database-param-toolbar">
                    <div>
                      <strong>{{ field.tableTitle || '查询参数配置' }}</strong>
                      <p>{{ field.tableSubtitle || '维护模型入参和页面测试值。日期参数可在默认来源中选择。' }}</p>
                    </div>
                    <div class="database-param-actions">
                      <el-button plain @click="syncDatabaseParamsFromSql(field, true)">同步参数</el-button>
                      <el-button type="primary" plain @click="addDatabaseParamEntry(field)">新增参数</el-button>
                    </div>
                  </div>
                  <div v-if="databaseParamNodeSummaries(field).length" class="database-param-summary database-param-node-summary">
                    <div v-for="summary in databaseParamNodeSummaries(field)" :key="summary.code">
                      <strong>{{ summary.name }}</strong>
                      <span v-if="summary.userInput.length">用户输入：{{ summary.userInput.join('、') }}</span>
                      <span v-if="summary.upstream.length">上游结果：{{ summary.upstream.join('、') }}</span>
                      <span v-if="summary.fixed.length">固定值：{{ summary.fixed.join('、') }}</span>
                      <span v-if="summary.dynamic.length">系统动态：{{ summary.dynamic.join('、') }}</span>
                      <span v-if="summary.unconfigured.length" class="database-param-unconfigured">待配置：{{ summary.unconfigured.join('、') }}</span>
                    </div>
                  </div>
                  <div class="database-param-table">
                    <div class="database-param-head">
                      <span>参数名</span>
                      <span>类型</span>
                      <span>必填</span>
                      <span>默认来源</span>
                      <span>测试值</span>
                      <span>示例值</span>
                      <span>说明</span>
                      <span>操作</span>
                    </div>
                    <div
                      v-for="(entry, index) in schemaDraft[field.key]"
                      :key="`${field.key}-db-param-${index}`"
                      class="database-param-row"
                    >
                      <el-input v-model.trim="entry.name" placeholder="status" />
                      <el-select v-model="entry.type" class="w-100">
                        <el-option v-for="option in schemaTypeOptions" :key="option.value" :label="option.label" :value="option.value" />
                      </el-select>
                      <el-checkbox v-model="entry.required" />
                      <el-select
                        v-model="entry.defaultSource"
                        class="w-100"
                        filterable
                        allow-create
                        default-first-option
                        :reserve-keyword="false"
                        @change="handleDatabaseParamSourceChange(entry)"
                      >
                        <el-option
                          v-for="option in databaseParamSourceOptions"
                          :key="option.value"
                          :label="option.label"
                          :value="option.value"
                        />
                      </el-select>
                      <el-input v-model="entry.testValue" :disabled="entry.defaultSource && entry.defaultSource !== 'user_input'" placeholder="页面测试值" />
                      <el-input v-model="entry.exampleValue" placeholder="模型示例值" />
                      <el-input v-model="entry.description" placeholder="参数业务含义" />
                      <el-button plain type="danger" @click="removeDatabaseParamEntry(field, index)">删除</el-button>
                    </div>
                    <div v-if="!schemaDraft[field.key]?.length" class="database-param-empty">
                      暂无参数，可从 SQL 模板同步生成。
                    </div>
                  </div>
                </div>
                <div v-else-if="field.type === 'databaseSqlSteps'" class="database-flow-workspace">
                  <header class="database-flow-toolbar">
                    <div>
                      <strong>查询流程工作台</strong>
                      <p>按“添加步骤—配置当前步骤—检查执行关系—测试流程”的顺序完成查询。</p>
                    </div>
                    <div class="database-flow-toolbar-actions">
                      <span>{{ databaseSqlSteps(field).length }} 个步骤</span>
                      <label>依赖执行</label>
                      <el-switch :model-value="databaseSqlWorkflowEnabled(field)" @change="setDatabaseSqlWorkflowEnabled(field, $event)" />
                      <el-button v-if="formTestAction" plain :loading="busy" @click="testFormDraft">测试流程</el-button>
                    </div>
                  </header>

                  <div v-if="databaseSqlSteps(field).length" class="database-flow-columns">
                    <aside class="database-flow-steps">
                      <div class="database-flow-column-head">
                        <div><strong>执行步骤</strong><small>展示顺序不等于执行顺序</small></div>
                        <el-button text type="primary" @click="addDatabaseSqlStep(field)">＋ 添加</el-button>
                      </div>
                      <button
                        v-for="(step, index) in databaseSqlSteps(field)"
                        :key="`${field.key}-flow-step-${index}`"
                        type="button"
                        class="database-flow-step"
                        :class="{ active: databaseSelectedSqlStepIndex(field) === index, disabled: step.enabled === false }"
                        @click="selectDatabaseSqlStep(field, index)"
                      >
                        <span class="database-flow-step-number">{{ index + 1 }}</span>
                        <span class="database-flow-step-copy">
                          <strong>{{ step.sqlName || `步骤 ${index + 1}` }}</strong>
                          <code>{{ step.sqlCode }}</code>
                          <small v-if="step.dependencies?.length">依赖：{{ step.dependencies.join('、') }}</small>
                          <small v-else>{{ index === 0 ? '起始步骤' : '无前置依赖，可并行' }}</small>
                        </span>
                      </button>
                    </aside>

                    <section class="database-flow-detail">
                      <template v-for="entry in [databaseSelectedSqlStep(field)]" :key="entry?.sqlCode || 'selected-step'">
                        <div v-if="entry" class="database-flow-detail-inner">
                          <header class="database-flow-detail-head">
                            <div>
                              <span>步骤 {{ databaseSelectedSqlStepIndex(field) + 1 }}</span>
                              <strong>{{ entry.sqlName || entry.sqlCode }}</strong>
                            </div>
                            <div class="database-flow-step-actions">
                              <el-switch v-model="entry.enabled" active-text="启用" />
                              <el-button plain size="small" :disabled="databaseSelectedSqlStepIndex(field) === 0" @click="moveDatabaseSqlStep(field, databaseSelectedSqlStepIndex(field), -1)">上移</el-button>
                              <el-button plain size="small" :disabled="databaseSelectedSqlStepIndex(field) >= databaseSqlSteps(field).length - 1" @click="moveDatabaseSqlStep(field, databaseSelectedSqlStepIndex(field), 1)">下移</el-button>
                              <el-button plain size="small" @click="copyDatabaseSqlStep(field, databaseSelectedSqlStepIndex(field))">复制</el-button>
                              <el-button plain type="danger" size="small" @click="removeDatabaseSqlStep(field, databaseSelectedSqlStepIndex(field))">删除</el-button>
                            </div>
                          </header>

                          <nav class="database-flow-tabs">
                            <button v-for="tab in [{ key: 'basic', label: '基础信息' }, { key: 'sql', label: 'SQL 配置' }, { key: 'inputs', label: '输入参数' }, { key: 'output', label: '输出定义' }, { key: 'rules', label: '执行规则' }]" :key="tab.key" type="button" :class="{ active: databaseSqlActiveTabs[field.key] === tab.key }" @click="databaseSqlActiveTabs[field.key] = tab.key">{{ tab.label }}</button>
                          </nav>

                          <div v-if="databaseSqlActiveTabs[field.key] === 'basic'" class="database-flow-tab-panel">
                            <div class="database-flow-form-grid two">
                              <el-form-item label="步骤名称" required><el-input v-model.trim="entry.sqlName" placeholder="例如：查询客户资产" /></el-form-item>
                              <el-form-item label="步骤编码" required><el-input v-model.trim="entry.sqlCode" placeholder="QUERY_CUSTOMER_ASSET" /></el-form-item>
                            </div>
                            <el-form-item label="步骤说明" required><el-input v-model="entry.sqlDescription" type="textarea" :rows="4" placeholder="说明这一步查询什么，以及结果将用于什么分析。" /></el-form-item>
                          </div>

                          <div v-else-if="databaseSqlActiveTabs[field.key] === 'sql'" class="database-flow-tab-panel">
                            <div class="database-sql-editor-head"><div><strong>只读 SQL</strong><small>支持 SELECT、SHOW、DESCRIBE、EXPLAIN</small></div><div><el-button plain size="small" @click="syncDatabaseSqlStepParams(entry)">扫描参数</el-button><el-button v-if="formTestAction" type="primary" plain size="small" :loading="busy" @click="testFormDraft">试运行流程</el-button></div></div>
                            <el-input v-model="entry.sqlContent" class="codebox database-flow-codebox" type="textarea" :rows="16" spellcheck="false" placeholder="SELECT ... WHERE customer_id = :customerId" />
                            <p v-pre class="database-flow-tip">可识别 :name、${trade_date}、{{name}}；扫描只补充缺失参数，不覆盖已有配置。</p>
                          </div>

                          <div v-else-if="databaseSqlActiveTabs[field.key] === 'inputs'" class="database-flow-tab-panel">
                            <div class="database-node-config-block">
                              <div class="database-node-config-head"><div><strong>参数来源</strong><p>明确每个参数来自流程输入、上游结果、固定值或系统变量。</p></div><div class="database-node-config-actions"><el-button plain type="primary" size="small" @click="syncDatabaseSqlStepParams(entry)">同步参数</el-button><el-button plain size="small" @click="addDatabaseSqlParameterMapping(entry)">新增来源</el-button></div></div>
                              <div v-for="(mapping, mappingIndex) in entry.parameterMappings" :key="`${entry.sqlCode}-mapping-${mappingIndex}`" class="database-node-mapping-row">
                                <el-input v-model.trim="mapping.parameter" placeholder="参数名" @change="reconcileDatabaseFlowInputs(databaseParamConfigField())" />
                                <el-select v-model="mapping.sourceType" @change="handleDatabaseSqlMappingSourceChange(mapping)"><el-option label="流程输入" value="USER_INPUT" /><el-option label="系统变量" value="SYSTEM_CONTEXT" /><el-option label="上游步骤结果" value="UPSTREAM_RESULT" /><el-option label="固定值" value="STATIC" /></el-select>
                                <el-select v-if="mapping.sourceType === 'UPSTREAM_RESULT'" v-model="mapping.sourceNode" placeholder="选择上游步骤"><el-option v-for="option in databaseSqlDependencyOptions(field, entry)" :key="option.value" :label="option.label" :value="option.value" /></el-select>
                                <el-select v-else-if="mapping.sourceType === 'SYSTEM_CONTEXT'" v-model="mapping.sourceKey" filterable placeholder="选择系统内置参数"><el-option v-for="option in databaseSystemParamSourceOptions" :key="option.value" :label="option.label" :value="option.value" /></el-select>
                                <el-input v-else-if="mapping.sourceType !== 'STATIC'" v-model.trim="mapping.sourceKey" placeholder="来源字段，默认同名" @change="reconcileDatabaseFlowInputs(databaseParamConfigField())" />
                                <el-input v-if="mapping.sourceType === 'UPSTREAM_RESULT'" v-model.trim="mapping.sourceExpression" placeholder="$.rows[0].customer_id" />
                                <el-input v-else v-model="mapping.defaultValue" :placeholder="mapping.sourceType === 'STATIC' ? (mapping.parameter === 'busi_date' ? 'YYYYMMDD，如 20260105' : '固定值') : '默认值'" />
                                <el-checkbox v-model="mapping.required" @change="reconcileDatabaseFlowInputs(databaseParamConfigField())">必填</el-checkbox>
                                <el-button plain type="danger" size="small" @click="removeDatabaseSqlParameterMapping(entry, mappingIndex)">删除</el-button>
                              </div>
                              <div v-if="!entry.parameterMappings?.length" class="database-compact-empty">尚无参数来源，点击“同步参数”从 SQL 自动识别。</div>
                            </div>
                          </div>

                          <div v-else-if="databaseSqlActiveTabs[field.key] === 'output'" class="database-flow-tab-panel">
                            <div class="database-output-switch"><div><strong>参与最终输出</strong><small>关闭后仍可供下游步骤使用，但不会直接返回给模型。</small></div><el-switch v-model="entry.returnToModel" /></div>
                            <div class="database-flow-form-grid three"><el-input v-model.trim="entry.resultSemantic.resultSetName" placeholder="结果名称，如 customer_asset" /><el-input v-model.trim="entry.resultSemantic.businessEntity" placeholder="业务对象，如 客户" /><el-input v-model.trim="entry.resultSemantic.dataGranularity" placeholder="数据粒度，如 客户-日期" /><el-input v-model.trim="entry.primaryKeysText" placeholder="关联主键，逗号分隔" /><el-input v-model.trim="entry.resultSemantic.timeField" placeholder="时间字段" /><el-input v-model.trim="entry.resultSemantic.emptyMeaning" placeholder="空结果含义" /></div>
                            <el-input v-model.trim="entry.resultSemantic.modelUsage" type="textarea" :rows="3" placeholder="说明模型应如何理解和使用这个结果集" />
                            <div class="database-result-units-head"><span>字段业务单位</span><el-button plain size="small" @click="addDatabaseResultUnit(entry)">新增字段</el-button></div>
                            <div v-for="(unit, unitIndex) in entry.unitDescriptionEntries" :key="`${entry.sqlCode}-unit-${unitIndex}`" class="database-result-unit-row"><el-input v-model.trim="unit.field" placeholder="字段名" /><el-input v-model.trim="unit.unit" placeholder="单位或业务说明" /><el-button plain type="danger" size="small" @click="removeDatabaseResultUnit(entry, unitIndex)">删除</el-button></div>
                          </div>

                          <div v-else class="database-flow-tab-panel">
                            <el-form-item v-if="databaseSqlWorkflowEnabled(field)" label="等待哪些步骤完成后执行"><el-select v-model="entry.dependencies" class="w-100" multiple clearable collapse-tags placeholder="不选择则为起始步骤，可与其他起始步骤并行"><el-option v-for="option in databaseSqlDependencyOptions(field, entry)" :key="option.value" :label="option.label" :value="option.value" /></el-select></el-form-item>
                            <div class="database-flow-form-grid two"><el-form-item label="超时时间（秒）"><el-input-number v-model="entry.timeoutSeconds" class="w-100" :min="1" :max="300" controls-position="right" /></el-form-item><el-form-item label="最大返回行数"><el-input-number v-model="entry.maxResultRows" class="w-100" :min="1" controls-position="right" /></el-form-item><el-form-item label="执行失败时"><el-select v-model="entry.failureStrategy"><el-option label="终止整个流程" value="STOP" /><el-option label="跳过并继续" value="CONTINUE" /></el-select></el-form-item><el-form-item label="没有数据时"><el-select v-model="entry.emptyResultStrategy"><el-option label="正常继续" value="CONTINUE" /><el-option label="跳过依赖它的步骤" value="SKIP_DEPENDENTS" /><el-option label="终止整个流程" value="STOP" /></el-select></el-form-item></div>
                          </div>
                        </div>
                      </template>
                    </section>

                    <aside class="database-flow-inspector">
                      <nav class="database-flow-side-tabs"><button type="button" :class="{ active: databaseSqlSideTabs[field.key] === 'inputs' }" @click="databaseSqlSideTabs[field.key] = 'inputs'">流程输入</button><button type="button" :class="{ active: databaseSqlSideTabs[field.key] === 'plan' }" @click="databaseSqlSideTabs[field.key] = 'plan'">执行预览</button><button type="button" :class="{ active: databaseSqlSideTabs[field.key] === 'test' }" @click="databaseSqlSideTabs[field.key] = 'test'">测试结果</button></nav>
                      <div v-if="databaseSqlSideTabs[field.key] === 'inputs'" class="database-flow-side-panel">
                        <template v-for="paramField in [databaseParamConfigField()]" :key="paramField?.key || 'flow-inputs'">
                          <template v-if="paramField">
                            <div class="database-flow-side-head"><div><strong>对外输入参数</strong><small>由各步骤中选择“流程输入”的参数自动汇总</small></div></div>
                            <div
                              v-for="(param, paramIndex) in schemaDraft[paramField.key]"
                              :key="`flow-param-${paramIndex}`"
                              class="database-flow-input-card"
                              :class="{
                                'is-required': databaseParamRequiresTestValue(param),
                                'is-invalid': databaseParameterValidationAttempted && databaseParamTestValueMissing(param)
                              }"
                            >
                              <div class="database-flow-input-label"><span><i>*</i> 参数名称</span><em v-if="databaseParamRequiresTestValue(param)">必填参数</em></div>
                              <div><el-input v-model="param.name" disabled /><el-select v-model="param.type"><el-option v-for="option in schemaTypeOptions" :key="option.value" :label="option.label" :value="option.value" /></el-select></div>
                              <el-tag type="info" effect="plain">来源：流程输入</el-tag>
                              <el-input v-model="param.testValue" :placeholder="databaseParamRequiresTestValue(param) ? '流程测试值（测试必填）' : '流程测试值（选填）'" />
                              <small v-if="databaseParameterValidationAttempted && databaseParamTestValueMissing(param)" class="database-flow-input-error">请填写该必填参数的流程测试值</small>
                              <div><el-checkbox v-model="param.required" @change="handleDatabaseFlowInputRequiredChange(param)">设为必填</el-checkbox></div>
                            </div>
                            <div v-if="!schemaDraft[paramField.key]?.length" class="database-compact-empty">尚无流程输入，可从当前 SQL 扫描生成。</div>
                            <el-button class="w-100" plain @click="syncDatabaseParamsFromSql(paramField, true)">同步全部步骤参数</el-button>
                          </template>
                        </template>
                      </div>
                      <div v-else-if="databaseSqlSideTabs[field.key] === 'plan'" class="database-flow-side-panel">
                        <div class="database-flow-side-head"><div><strong>执行层级</strong><small>{{ databaseSqlWorkflowEnabled(field) ? '同一层步骤可并行执行' : '当前按展示顺序依次执行' }}</small></div></div>
                        <div v-for="(level, levelIndex) in databaseSqlDisplayLevels(field)" :key="`side-level-${levelIndex}`" class="database-flow-plan-level"><span>第 {{ levelIndex + 1 }} 层<i v-if="level.length > 1">并行</i></span><div v-for="node in level" :key="node.sqlCode"><strong>{{ node.sqlName || node.sqlCode }}</strong><small v-if="node.dependencies?.length">等待：{{ node.dependencies.join('、') }}</small><small v-else>直接执行</small></div></div>
                      </div>
                      <div v-else class="database-flow-side-panel">
                        <div class="database-flow-side-head"><div><strong>流程测试</strong><small>保存前使用当前配置进行整体试运行</small></div><el-button type="primary" plain size="small" :loading="busy" @click="testFormDraft">运行</el-button></div>
                        <div v-if="busy" class="database-compact-empty">正在执行查询流程…</div>
                        <div v-else-if="!formTestResult" class="database-compact-empty">尚未运行测试。</div>
                        <div v-else class="database-flow-test-overview" :class="{ 'is-failed': formTestResult.success === false }">
                          <div><el-tag :type="formTestResult.success === false ? 'danger' : 'success'">{{ formTestResult.success === false ? '执行失败' : '执行成功' }}</el-tag><strong>{{ formTestResult.success === false ? '查询流程未完成' : '查询流程已完成' }}</strong></div>
                          <dl>
                            <div><dt>执行流程</dt><dd>{{ databaseTestExecutedSteps || '当前查询流程' }}</dd></div>
                            <div><dt>返回记录</dt><dd>{{ databaseTestTotalRows }} 条</dd></div>
                            <div v-if="databaseTestDurationMs !== null"><dt>执行耗时</dt><dd>{{ databaseTestDurationMs }} ms</dd></div>
                          </dl>
                          <p v-if="formTestResult.success === false">{{ databaseTestErrorSummary || '请在下方预览结果中查看详细错误。' }}</p>
                          <small>参数代入 SQL、数据表和完整错误请查看下方“预览结果”。</small>
                        </div>
                      </div>
                    </aside>
                  </div>

                  <div v-else class="database-flow-empty">
                    <span>1</span><strong>添加第一个执行步骤</strong><p>先说明这一步要查询什么，再填写 SQL；系统会引导配置输入、输出和执行规则。</p><el-button type="primary" @click="addDatabaseSqlStep(field)">添加 SQL 步骤</el-button>
                  </div>
                </div>
                <div v-else-if="field.type === 'jsonObjectString' || field.type === 'jsonObject'" class="visual-object-editor">
                  <div v-if="objectPresetOptions(field).length" class="visual-object-preset-toolbar">
                    <el-select
                      v-model="objectPresetSelection[field.key]"
                      clearable
                      filterable
                      placeholder="选择常用项"
                    >
                      <el-option
                        v-for="option in objectPresetOptions(field)"
                        :key="`${field.key}-preset-${option.id || option.key}`"
                        :label="option.label || option.key"
                        :value="option.id || option.key"
                      />
                    </el-select>
                    <el-button plain @click="addObjectPreset(field)">添加常用项</el-button>
                  </div>
                  <el-text v-if="!objectDraft[field.key]?.length" type="info">
                    {{ field.emptyText || '暂无键值，点击下方按钮新增。' }}
                  </el-text>
                  <div
                    v-for="(entry, index) in objectDraft[field.key]"
                    :key="`${field.key}-object-${index}`"
                    class="visual-object-row"
                  >
                    <el-input v-model.trim="entry.key" :placeholder="field.keyPlaceholder || '名称'" />
                    <el-input v-model="entry.value" :placeholder="field.valuePlaceholder || '值'" />
                    <el-button plain type="danger" @click="removeObjectEntry(field.key, index)">删除</el-button>
                  </div>
                  <el-button plain @click="addObjectEntry(field.key)">新增键值</el-button>
                </div>
                <div v-else-if="field.type === 'jsonSchemaString' || field.type === 'jsonSchema'" class="visual-schema-editor">
                  <el-text v-if="!schemaDraft[field.key]?.length" type="info">
                    {{ field.emptyText || '暂无参数，点击下方按钮新增。' }}
                  </el-text>
                  <div
                    v-for="(entry, index) in schemaDraft[field.key]"
                    :key="`${field.key}-schema-${index}`"
                    class="visual-schema-row"
                  >
                    <el-input v-model.trim="entry.name" :placeholder="field.namePlaceholder || '参数名'" />
                    <el-select v-model="entry.type" class="w-100">
                      <el-option v-for="option in schemaTypeOptions" :key="option.value" :label="option.label" :value="option.value" />
                    </el-select>
                    <el-checkbox v-model="entry.required">必填</el-checkbox>
                    <el-input v-model="entry.description" :placeholder="field.descriptionPlaceholder || '说明'" />
                    <el-button plain type="danger" @click="removeSchemaEntry(field.key, index)">删除</el-button>
                  </div>
                  <el-button plain @click="addSchemaEntry(field.key)">新增参数</el-button>
                </div>
                <el-switch v-else-if="field.type === 'checkbox'" v-model="form[field.key]" />
                <el-input-number
                  v-else-if="field.type === 'number'"
                  v-model="form[field.key]"
                  class="w-100"
                  :min="field.min || 0"
                  :step="field.step || 1"
                  :placeholder="field.placeholder"
                  controls-position="right"
                />
                <el-input
                  v-else
                  v-model="form[field.key]"
                  :type="field.type || 'text'"
                  :placeholder="field.placeholder"
                  :required="isFieldRequired(field)"
                />
                <div v-if="field.help" class="form-text">{{ field.help }}</div>
              </el-form-item>
                </el-col>
              </template>
            </el-row>
          </section>
        </div>
      </el-form>

      <section v-if="formPreviewType === 'databaseQuery'" class="database-preview-panel">
        <div class="database-preview-title">
          <div>
            <h3>预览结果</h3>
            <p>集中查看参数代入 SQL、返回数据表、完整错误和 JSON 结果。</p>
          </div>
          <el-text v-if="busy" type="info">正在执行...</el-text>
        </div>

        <div v-if="!formTestResult" class="database-preview-empty">
          完成配置后点击“测试流程”，详细结果将在这里展示。
        </div>
        <div v-else class="database-preview-result">
          <div class="database-preview-summary">
            <el-tag :type="formTestResult.success === false ? 'danger' : 'success'" effect="light">
              {{ formTestResult.success === false ? '失败' : '成功' }}
            </el-tag>
            <span>{{ formTestResult.message || '查询完成' }}</span>
            <span>SQL {{ databasePreviewResultSets.length }} 条</span>
            <span>合计返回 {{ databaseTestTotalRows }} 行</span>
          </div>

          <el-alert
            v-if="formTestResult.success === false"
            class="database-flow-test-error"
            type="error"
            :title="formTestResult.errorMessage || '数据库查询执行失败'"
            :closable="false"
            show-icon
          />

          <el-tabs v-model="databasePreviewActiveTab" type="card" class="database-preview-tabs">
            <el-tab-pane
              v-for="resultSet in databasePreviewResultSets"
              :key="resultSet.previewKey"
              :name="resultSet.previewKey"
            >
              <template #label>
                <span class="database-preview-tab-label">
                  <i :class="resultSet.success === false ? 'is-error' : 'is-success'"></i>
                  {{ resultSet.previewName }}
                  <small>{{ resultSet.rowCount ?? (resultSet.rows?.length || 0) }} 行</small>
                </span>
              </template>

              <div v-if="databasePreviewActiveTab === resultSet.previewKey" class="database-preview-tab-content">
                <div class="database-preview-summary database-preview-result-summary">
                  <el-tag :type="resultSet.success === false ? 'danger' : 'success'" size="small" effect="plain">
                    {{ resultSet.success === false ? '执行失败' : '执行成功' }}
                  </el-tag>
                  <span>返回 {{ databasePreviewData.rowCount ?? databasePreviewRows.length }} 行</span>
                  <span>上限 {{ databasePreviewData.maxRows ?? '-' }} 行</span>
                  <span v-if="databasePreviewData.durationMs != null">耗时 {{ databasePreviewData.durationMs }} ms</span>
                  <el-tag v-if="databasePreviewData.possiblyTruncated" type="warning" size="small" effect="light">可能已截断</el-tag>
                </div>

                <el-alert
                  v-if="databasePreviewData.success === false"
                  type="error"
                  :title="databasePreviewData.errorMessage || '该 SQL 执行失败'"
                  :closable="false"
                  show-icon
                />

                <details v-if="databasePreviewResolvedSql" class="database-resolved-sql" open>
                  <summary>{{ databasePreviewData.previewName }} · 参数代入后 SQL</summary>
                  <pre><code>{{ databasePreviewResolvedSql }}</code></pre>
                </details>

                <div v-if="databasePreviewColumns.length" class="database-preview-fields">
                  <div class="database-preview-fields-head">
                    <span>从查询结果列名生成参数</span>
                    <el-button plain size="small" @click="appendDatabasePreviewParams(databasePreviewColumns, databasePreviewRows[0] || {})">
                      全部字段
                    </el-button>
                  </div>
                  <div class="database-preview-field-list">
                    <el-button
                      v-for="column in databasePreviewColumns"
                      :key="column"
                      plain
                      size="small"
                      @click="appendDatabasePreviewParam(column, databasePreviewRows[0]?.[column])"
                    >
                      {{ column }}
                    </el-button>
                  </div>
                </div>

                <el-table
                  v-if="databasePreviewColumns.length"
                  class="database-preview-table"
                  :data="databasePreviewRows"
                  border
                  size="small"
                  empty-text="查询成功，但没有返回行"
                >
                  <el-table-column
                    v-for="column in databasePreviewColumns"
                    :key="column"
                    :prop="column"
                    :label="column"
                    min-width="140"
                  >
                    <template #default="{ row }">{{ formatPreviewCell(row[column]) }}</template>
                  </el-table-column>
                </el-table>
                <div v-else-if="databasePreviewData.success !== false" class="database-preview-empty">查询成功，但没有返回列。</div>
              </div>
            </el-tab-pane>
          </el-tabs>

          <details class="database-preview-json">
            <summary>完整 JSON</summary>
            <pre class="json-block"><code>{{ prettyPreviewJson(formTestResult) }}</code></pre>
          </details>
        </div>
      </section>

      <template #footer>
        <el-button v-if="formTestAction" plain :loading="busy" @click="testFormDraft">{{ formTestLabel }}</el-button>
        <el-button @click="formOpen = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="saveForm">保存</el-button>
      </template>
    </ModalPanel>

    <ModalPanel
      :open="templatePickerOpen"
      :title="templatePickerTitle"
      :subtitle="templatePickerSubtitle"
      wide
      @close="templatePickerOpen = false"
    >
      <div class="template-picker-toolbar" :class="{ 'has-filter': templatePickerFilterOptions.length }">
        <el-select
          v-if="templatePickerFilterOptions.length"
          v-model="templatePickerFilterValue"
          clearable
          :placeholder="`全部${templatePickerFilterLabel}`"
        >
          <el-option
            v-for="option in templatePickerFilterOptions"
            :key="String(option.value)"
            :label="option.label"
            :value="String(option.value)"
          />
        </el-select>
        <el-input v-model.trim="templatePickerKeyword" clearable placeholder="搜索模板编号、名称、分类或描述">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button plain @click="selectTemplatePickerVisible">选择当前页</el-button>
        <el-button plain @click="selectTemplatePickerFiltered">选择全部匹配</el-button>
        <el-button plain @click="clearTemplatePickerVisible">清除当前页</el-button>
        <el-button plain @click="clearTemplatePickerFiltered">清除全部匹配</el-button>
      </div>

      <el-table class="template-picker-table" :data="templatePickerVisibleItems" border stripe empty-text="暂无可选模板">
        <el-table-column label="" width="56" align="center">
          <template #default="{ row }">
            <el-checkbox :model-value="templatePickerSelected.includes(templatePickerItemKey(row))" @change="toggleTemplatePickerItem(row)" />
          </template>
        </el-table-column>
        <el-table-column label="模板编号" min-width="150">
          <template #default="{ row }"><code>{{ templatePickerItemKey(row) || '-' }}</code></template>
        </el-table-column>
        <el-table-column label="模板名称" min-width="180">
          <template #default="{ row }">{{ row.title || row.name || '-' }}</template>
        </el-table-column>
        <el-table-column v-if="templatePickerFilterOptions.length" :label="templatePickerFilterLabel" min-width="120">
          <template #default="{ row }">{{ row[templatePickerField.filterKey] || '-' }}</template>
        </el-table-column>
        <el-table-column label="分类" min-width="140">
          <template #default="{ row }">{{ row.category || '-' }}</template>
        </el-table-column>
        <el-table-column label="风险" width="100">
          <template #default="{ row }">
            <el-tag :type="row.riskLevel === 'LOW' ? 'success' : 'warning'" effect="light">{{ row.riskLevel || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.enabled === false ? 'info' : 'success'" effect="light">{{ row.enabled === false ? '停用' : '启用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="描述" min-width="220">
          <template #default="{ row }">{{ row.description || row.commandTemplate || '-' }}</template>
        </el-table-column>
      </el-table>

      <footer class="pagination-row">
        <el-text type="info">已选择 {{ templatePickerSelected.length }} 个，第 {{ templatePickerPage }} / {{ templatePickerPageCount }} 页</el-text>
        <el-pagination
          background
          layout="prev, pager, next"
          :current-page="templatePickerPage"
          :page-size="templatePickerPageSize"
          :total="templatePickerFilteredItems.length"
          @current-change="templatePickerPage = $event"
        />
      </footer>

      <template #footer>
        <el-button @click="templatePickerOpen = false">取消</el-button>
        <el-button type="primary" @click="confirmTemplatePicker">确定</el-button>
      </template>
    </ModalPanel>
  </el-card>
</template>

<script src="../scripts/components/CrudCatalog.js"></script>
