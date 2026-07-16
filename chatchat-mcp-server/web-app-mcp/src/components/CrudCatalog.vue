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

    <ModalPanel :open="formOpen" :title="formTitle" :subtitle="formSubtitle" wide @close="formOpen = false">
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
              <span class="form-section-count">{{ section.fields.length }} 个配置区</span>
            </button>
            <el-row v-show="!isFormSectionCollapsed(section.key)" class="form-section-grid" :gutter="12">
              <template v-for="field in section.fields" :key="field.key">
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
                <el-input
                  v-else-if="field.type === 'textarea'"
                  v-model="form[field.key]"
                  type="textarea"
                  :rows="field.rows || 3"
                  :placeholder="field.placeholder"
                  :required="isFieldRequired(field)"
                />
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
                      <el-button plain @click="syncDatabaseParamsFromSql(field, true)">扫描并配置参数</el-button>
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
                <div v-else-if="field.type === 'databaseSqlSteps'" class="database-sql-step-editor">
                  <el-alert
                    type="info"
                    :closable="false"
                    show-icon
                    title="通过节点依赖编排执行流程；系统按依赖关系拓扑排序，同一层节点可并行执行。"
                  />
                  <div class="database-param-toolbar">
                    <div>
                      <strong>SQL 明细列表</strong>
                      <p>拖动顺序表达展示顺序，前置依赖决定真实执行顺序。每条 SQL 可独立映射参数和定义结果语义。</p>
                    </div>
                    <div class="database-param-actions">
                      <span class="database-workflow-switch-label">依赖编排</span>
                      <el-switch :model-value="databaseSqlWorkflowEnabled(field)" @change="setDatabaseSqlWorkflowEnabled(field, $event)" />
                      <el-button type="primary" plain @click="addDatabaseSqlStep(field)">新增 SQL</el-button>
                    </div>
                  </div>
                  <div v-if="databaseSqlSteps(field).length && databaseSqlWorkflowEnabled(field)" class="database-workflow-board">
                    <div class="database-workflow-title">执行依赖</div>
                    <div class="database-workflow-levels">
                      <div v-for="(level, levelIndex) in databaseSqlWorkflowLevels(field)" :key="`workflow-level-${levelIndex}`" class="database-workflow-level">
                        <span class="database-workflow-level-label">L{{ levelIndex + 1 }}</span>
                        <div class="database-workflow-nodes">
                          <div
                            v-for="node in level"
                            :key="node.sqlCode"
                            class="database-workflow-node"
                            :title="`${node.sqlName || node.sqlCode}${node.dependencies?.length ? `；依赖 ${node.dependencies.join('、')}` : '；起始节点'}`"
                          >
                            <code>{{ node.sqlCode }}</code>
                            <span>{{ node.sqlName || node.sqlCode }}</span>
                            <small v-if="node.dependencies?.length">← {{ node.dependencies.join('、') }}</small>
                          </div>
                        </div>
                        <span v-if="levelIndex < databaseSqlWorkflowLevels(field).length - 1" class="database-workflow-arrow">→</span>
                      </div>
                    </div>
                  </div>
                  <div
                    v-for="(entry, index) in databaseSqlSteps(field)"
                    :key="`${field.key}-sql-step-${index}`"
                    class="database-sql-step-card"
                  >
                    <div class="database-sql-step-head">
                      <strong>{{ entry.sqlName || `SQL ${index + 1}` }}</strong>
                      <div class="database-param-actions">
                        <el-switch v-model="entry.enabled" active-text="启用" inactive-text="停用" />
                        <el-button plain size="small" :disabled="index === 0" @click="moveDatabaseSqlStep(field, index, -1)">上移</el-button>
                        <el-button plain size="small" :disabled="index >= databaseSqlSteps(field).length - 1" @click="moveDatabaseSqlStep(field, index, 1)">下移</el-button>
                        <el-button plain size="small" @click="copyDatabaseSqlStep(field, index)">复制</el-button>
                        <el-button plain type="danger" size="small" @click="removeDatabaseSqlStep(field, index)">删除</el-button>
                      </div>
                    </div>
                    <el-row :gutter="10">
                      <el-col :xs="24" :md="8">
                          <el-form-item label="SQL 名称" required>
                          <el-input v-model.trim="entry.sqlName" placeholder="例如 InnoDB 状态" />
                        </el-form-item>
                      </el-col>
                      <el-col :xs="24" :md="8">
                        <el-form-item label="节点编码" required>
                          <el-input v-model.trim="entry.sqlCode" placeholder="例如 QUERY_CUSTOMER_BASE" />
                        </el-form-item>
                      </el-col>
                      <el-col :xs="24" :md="8">
                        <el-form-item label="前置依赖">
                          <el-select v-model="entry.dependencies" class="w-100" multiple clearable collapse-tags placeholder="无依赖，为起始节点">
                            <el-option v-for="option in databaseSqlDependencyOptions(field, entry)" :key="option.value" :label="option.label" :value="option.value" />
                          </el-select>
                        </el-form-item>
                      </el-col>
                      <el-col :xs="12" :md="5">
                        <el-form-item label="超时（秒）">
                          <el-input-number v-model="entry.timeoutSeconds" class="w-100" :min="1" :max="300" controls-position="right" />
                        </el-form-item>
                      </el-col>
                      <el-col :xs="12" :md="5">
                        <el-form-item label="自定义返回行数">
                          <el-input-number v-model="entry.maxResultRows" class="w-100" :min="1" controls-position="right" />
                        </el-form-item>
                      </el-col>
                      <el-col :xs="24" :md="6">
                        <el-form-item label="失败策略">
                          <el-select v-model="entry.failureStrategy" class="w-100">
                            <el-option label="失败停止" value="STOP" />
                            <el-option label="失败继续" value="CONTINUE" />
                          </el-select>
                        </el-form-item>
                      </el-col>
                      <el-col :xs="24" :md="6">
                        <el-form-item label="空结果策略">
                          <el-select v-model="entry.emptyResultStrategy" class="w-100">
                            <el-option label="继续执行" value="CONTINUE" />
                            <el-option label="跳过下游" value="SKIP_DEPENDENTS" />
                            <el-option label="终止流程" value="STOP" />
                          </el-select>
                        </el-form-item>
                      </el-col>
                      <el-col :xs="24">
                          <el-form-item label="结果集说明" required>
                          <el-input v-model="entry.sqlDescription" type="textarea" :rows="2" placeholder="说明一行代表什么、包含哪些指标，以及模型应如何使用该结果集。" />
                        </el-form-item>
                      </el-col>
                      <el-col :xs="24">
                          <el-form-item label="只读 SQL" required>
                          <el-input v-model="entry.sqlContent" class="codebox" type="textarea" :rows="6" spellcheck="false" placeholder="填写 SELECT、SHOW、DESCRIBE 或 EXPLAIN 查询" />
                        </el-form-item>
                      </el-col>
                      <el-col :xs="24">
                        <div class="database-node-config-block">
                          <div class="database-node-config-head">
                            <div><strong>SQL 独立参数</strong><p>配置仅当前 SQL 使用的固定参数，无需填写 JSON。动态值请在下方“节点参数映射”中配置。</p></div>
                            <el-button plain size="small" @click="addDatabaseSqlStaticParameter(entry)">新增参数</el-button>
                          </div>
                          <div v-for="(parameter, parameterIndex) in entry.staticParameterEntries" :key="`${entry.sqlCode}-static-${parameterIndex}`" class="database-static-parameter-row">
                            <el-input v-model.trim="parameter.name" placeholder="参数名，如 status" />
                            <el-select v-model="parameter.type" placeholder="参数类型" @change="normalizeDatabaseSqlStaticParameterValue(parameter)">
                              <el-option label="文本" value="string" />
                              <el-option label="整数" value="integer" />
                              <el-option label="小数" value="number" />
                              <el-option label="布尔值" value="boolean" />
                              <el-option label="日期文本" value="date" />
                            </el-select>
                            <el-switch v-if="parameter.type === 'boolean'" v-model="parameter.value" active-text="是" inactive-text="否" />
                            <el-input-number v-else-if="parameter.type === 'integer'" v-model="parameter.value" class="w-100" :precision="0" controls-position="right" />
                            <el-input-number v-else-if="parameter.type === 'number'" v-model="parameter.value" class="w-100" controls-position="right" />
                            <el-date-picker v-else-if="parameter.type === 'date'" v-model="parameter.value" class="w-100" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" />
                            <el-input v-else v-model="parameter.value" placeholder="参数值" />
                            <el-button plain type="danger" size="small" @click="removeDatabaseSqlStaticParameter(entry, parameterIndex)">删除</el-button>
                          </div>
                          <div v-if="!entry.staticParameterEntries?.length" class="database-param-empty">暂无固定参数。</div>
                        </div>
                      </el-col>
                      <el-col :xs="24">
                        <div class="database-node-config-block">
                          <div class="database-node-config-head">
                            <div><strong>节点参数映射</strong><p>将当前 SQL 参数绑定到用户输入、系统上下文、固定值或上游节点结果。</p></div>
                            <el-button plain size="small" @click="addDatabaseSqlParameterMapping(entry)">新增映射</el-button>
                          </div>
                          <div v-for="(mapping, mappingIndex) in entry.parameterMappings" :key="`${entry.sqlCode}-mapping-${mappingIndex}`" class="database-node-mapping-row">
                            <el-input v-model.trim="mapping.parameter" placeholder="参数名" />
                            <el-select v-model="mapping.sourceType">
                              <el-option label="用户输入" value="USER_INPUT" />
                              <el-option label="系统上下文" value="SYSTEM_CONTEXT" />
                              <el-option label="上游结果" value="UPSTREAM_RESULT" />
                              <el-option label="固定值" value="STATIC" />
                            </el-select>
                            <el-select v-if="mapping.sourceType === 'UPSTREAM_RESULT'" v-model="mapping.sourceNode" placeholder="来源节点">
                              <el-option v-for="option in databaseSqlDependencyOptions(field, entry)" :key="option.value" :label="option.label" :value="option.value" />
                            </el-select>
                            <el-input v-else-if="mapping.sourceType !== 'STATIC'" v-model.trim="mapping.sourceKey" placeholder="来源键，默认同参数名" />
                            <el-input v-if="mapping.sourceType === 'UPSTREAM_RESULT'" v-model.trim="mapping.sourceExpression" placeholder="$.rows[0].customer_no" />
                            <el-input v-else v-model="mapping.defaultValue" :placeholder="mapping.sourceType === 'STATIC' ? '固定值' : '默认值'" />
                            <el-checkbox v-model="mapping.required">必填</el-checkbox>
                            <el-button plain type="danger" size="small" @click="removeDatabaseSqlParameterMapping(entry, mappingIndex)">删除</el-button>
                          </div>
                          <div v-if="!entry.parameterMappings?.length" class="database-param-empty">未配置时沿用集合层用户输入参数。</div>
                        </div>
                      </el-col>
                      <el-col :xs="24">
                        <div class="database-node-config-block">
                          <div class="database-node-config-head"><div><strong>结果集语义</strong><p>帮助模型理解数据粒度、关联主键、空结果含义和使用方式。</p></div><el-switch v-model="entry.returnToModel" active-text="返回模型" /></div>
                          <el-row :gutter="10">
                            <el-col :xs="24" :md="8"><el-input v-model.trim="entry.resultSemantic.resultSetName" placeholder="结果集名称，如 customer_asset" /></el-col>
                            <el-col :xs="24" :md="8"><el-input v-model.trim="entry.resultSemantic.businessEntity" placeholder="业务实体，如 客户" /></el-col>
                            <el-col :xs="24" :md="8"><el-input v-model.trim="entry.resultSemantic.dataGranularity" placeholder="数据粒度，如 客户-日期" /></el-col>
                            <el-col :xs="24" :md="8"><el-input v-model.trim="entry.primaryKeysText" placeholder="关联主键，逗号分隔" /></el-col>
                            <el-col :xs="24" :md="8"><el-input v-model.trim="entry.resultSemantic.timeField" placeholder="时间字段" /></el-col>
                            <el-col :xs="24" :md="8"><el-input v-model.trim="entry.resultSemantic.emptyMeaning" placeholder="空结果代表什么" /></el-col>
                            <el-col :xs="24"><el-input v-model.trim="entry.resultSemantic.modelUsage" placeholder="模型应如何使用这个结果集" /></el-col>
                            <el-col :xs="24">
                              <div class="database-result-units-head"><span>字段单位</span><el-button plain size="small" @click="addDatabaseResultUnit(entry)">新增单位</el-button></div>
                              <div v-for="(unit, unitIndex) in entry.unitDescriptionEntries" :key="`${entry.sqlCode}-unit-${unitIndex}`" class="database-result-unit-row">
                                <el-input v-model.trim="unit.field" placeholder="字段名，如 total_asset" />
                                <el-input v-model.trim="unit.unit" placeholder="单位，如 元、%、万元" />
                                <el-button plain type="danger" size="small" @click="removeDatabaseResultUnit(entry, unitIndex)">删除</el-button>
                              </div>
                            </el-col>
                          </el-row>
                        </div>
                      </el-col>
                    </el-row>
                  </div>
                  <div v-if="!databaseSqlSteps(field).length" class="database-param-empty">
                    暂无 SQL 明细，点击“新增 SQL”开始配置。
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
            <p>使用当前 SQL 模板和查询参数测试执行结果。</p>
          </div>
          <el-text v-if="busy" type="info">正在执行...</el-text>
        </div>

        <div v-if="!formTestResult" class="database-preview-empty">
          填写只读 SQL 后点击测试调用。
        </div>
        <el-alert
          v-else-if="formTestResult.success === false"
          type="error"
          :title="formTestResult.errorMessage || '数据库查询执行失败'"
          :closable="false"
          show-icon
        />
        <div v-else class="database-preview-result">
          <div class="database-preview-summary">
            <el-tag type="success" effect="light">成功</el-tag>
            <span>{{ formTestResult.message || '查询完成' }}</span>
            <span>返回 {{ databasePreviewData.rowCount ?? databasePreviewRows.length }} 行</span>
            <span>上限 {{ databasePreviewData.maxRows ?? '-' }} 行</span>
            <el-tag v-if="databasePreviewData.possiblyTruncated" type="warning" effect="light">可能已截断</el-tag>
          </div>

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
          <div v-else class="database-preview-empty">查询成功，但没有返回列。</div>

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
