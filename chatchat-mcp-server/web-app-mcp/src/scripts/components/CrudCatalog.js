import { ElMessageBox } from 'element-plus';
import ModalPanel from '../../components/ModalPanel.vue';
import { parseJsonObject, prettyJson } from '../../utils/json';
import { buildTestNotification, isTestFailure } from '../../utils/test-result';
import '../../styles/components/crud-catalog.css';

export default {
  name: 'CrudCatalog',
  components: { ModalPanel },
  props: {
    title: { type: String, required: true },
    subtitle: { type: String, default: '' },
    columns: { type: Array, required: true },
    formFields: { type: Array, required: true },
    listAction: { type: Function, required: true },
    saveAction: { type: Function, required: true },
    removeAction: { type: Function, default: null },
    batchRemove: { type: Function, default: null },
    toggleAction: { type: Function, default: null },
    testAction: { type: Function, default: null },
    formTestAction: { type: Function, default: null },
    formTestLabel: { type: String, default: '测试' },
    formPreviewType: { type: String, default: '' },
    refreshAction: { type: Function, default: null },
    rebuildAction: { type: Function, default: null },
    rebuildLabel: { type: String, default: '' },
    rebuildRequiresSelection: { type: Boolean, default: false },
    extraActions: { type: Array, default: () => [] },
    defaults: { type: Object, default: () => ({}) },
    searchableFields: { type: Array, default: () => [] },
    listFilters: { type: Array, default: () => [] },
    pageSize: { type: Number, default: 10 },
    emptyText: { type: String, default: '暂无数据。' },
    searchPlaceholder: { type: String, default: '搜索' },
    formSubtitle: { type: String, default: '' }
  },
  emits: ['notify', 'error', 'result', 'loaded'],
  data() {
    return {
      busy: false,
      rowOperation: null,
      items: [],
      keyword: '',
      listFilterValues: {},
      page: 1,
      selectedIds: new Set(),
      formOpen: false,
      form: {},
      jsonDraft: {},
      listDraft: {},
      listInput: {},
      objectDraft: {},
      objectPresetSelection: {},
      schemaDraft: {},
      templatePickerOpen: false,
      templatePickerField: null,
      templatePickerKeyword: '',
      templatePickerFilterValue: '',
      templatePickerPage: 1,
      templatePickerPageSize: 8,
      templatePickerSelected: [],
      collapsedFormSections: [],
      metadataScopeOptions: {},
      metadataScopeKeyword: '',
      metadataScopeOpenKey: '',
      formTestResult: null,
      databaseSqlSelectedIndexes: {},
      databaseSqlActiveTabs: {},
      databaseSqlSideTabs: {},
      databaseParameterValidationAttempted: false,
      schemaTypeOptions: [
        { value: 'string', label: '文本' },
        { value: 'number', label: '数字' },
        { value: 'integer', label: '整数' },
        { value: 'boolean', label: '布尔' },
        { value: 'object', label: '对象' },
        { value: 'array', label: '数组' }
      ],
      databaseParamSourceOptions: [
        { value: 'user_input', label: '用户输入' },
        { value: 'today', label: '当天自然日 today' },
        { value: 'natural_date', label: '当天自然日 natural_date' },
        { value: 'month', label: '当前月份 month' },
        { value: 'month_start', label: '当月第一天 month_start' },
        { value: 'month_end', label: '当月最后一天 month_end' },
        { value: 'trade_date', label: '当前交易日 trade_date' },
        { value: 'trade_date-1', label: '上一交易日 trade_date-1' },
        { value: 'trade_date+1', label: '下一交易日 trade_date+1' }
      ],
      editingId: ''
    };
  },
  computed: {
    filtered() {
      const keyword = this.keyword.toLowerCase();
      const fields = this.searchableFields.length ? this.searchableFields : this.columns.map(column => column.key);
      return this.items.filter(item => {
        const matchesFilters = this.listFilters.every(filter => this.matchesListFilter(item, filter));
        if (!matchesFilters) return false;
        if (!keyword) return true;
        return fields.some(field => String(item[field] ?? '').toLowerCase().includes(keyword));
      });
    },
    pageCount() {
      return Math.max(1, Math.ceil(this.filtered.length / this.pageSize));
    },
    visibleItems() {
      const current = Math.min(this.page, this.pageCount);
      const start = (current - 1) * this.pageSize;
      return this.filtered.slice(start, start + this.pageSize);
    },
    formTitle() {
      return this.editingId ? `编辑${this.title}` : `新增${this.title}`;
    },
    visibleFormFields() {
      return this.formFields.filter(field => this.isFieldVisible(field));
    },
    formSections() {
      const sections = [];
      this.visibleFormFields.forEach(field => {
        const sectionKey = field.section || 'basic';
        let section = sections.find(item => item.key === sectionKey);
        if (!section) {
          section = {
            key: sectionKey,
            title: field.sectionTitle || defaultSectionTitle(sectionKey),
            subtitle: field.sectionSubtitle || '',
            fields: []
          };
          sections.push(section);
        }
        section.fields.push(field);
      });
      return sections;
    },
    hasDatabaseWorkflowField() {
      return this.formFields.some(field => field.type === 'databaseSqlSteps');
    },
    databaseSystemParamSourceOptions() {
      return this.databaseParamSourceOptions.filter(option => option.value !== 'user_input');
    },
    templatePickerTitle() {
      return this.templatePickerField?.label || '选择模板';
    },
    templatePickerSubtitle() {
      return this.templatePickerField?.pickerSubtitle || '搜索并勾选允许该资产使用的命令模板。';
    },
    templatePickerFilterOptions() {
      const source = this.templatePickerField?.filterOptions;
      const options = typeof source === 'function' ? source() : source;
      return Array.isArray(options) ? options : [];
    },
    templatePickerFilterLabel() {
      return this.templatePickerField?.filterLabel || '类型';
    },
    templatePickerItems() {
      if (!this.templatePickerField) return [];
      const source = this.templatePickerField.items;
      const items = typeof source === 'function' ? source() : source;
      return Array.isArray(items) ? items : [];
    },
    templatePickerFilteredItems() {
      const keyword = this.templatePickerKeyword.trim().toLowerCase();
      return this.templatePickerItems.filter(item => {
        if (item.enabled === false && this.templatePickerField?.enabledOnly !== false) return false;
        const filterKey = this.templatePickerField?.filterKey;
        if (filterKey && this.templatePickerFilterValue
          && String(item?.[filterKey] || '').trim().toLowerCase() !== this.templatePickerFilterValue.toLowerCase()) {
          return false;
        }
        if (!keyword) return true;
        return [
          this.templatePickerItemKey(item),
          item.title,
          item.name,
          item.description,
          item.category,
          item.databaseType,
          item.riskLevel,
          item.commandTemplate,
          item.sqlTemplate
        ].some(value => String(value || '').toLowerCase().includes(keyword));
      });
    },
    templatePickerPageCount() {
      return Math.max(1, Math.ceil(this.templatePickerFilteredItems.length / this.templatePickerPageSize));
    },
    templatePickerVisibleItems() {
      const page = Math.min(this.templatePickerPage, this.templatePickerPageCount);
      const start = (page - 1) * this.templatePickerPageSize;
      return this.templatePickerFilteredItems.slice(start, start + this.templatePickerPageSize);
    },
    databasePreviewData() {
      const data = this.formTestResult?.data || {};
      if (Array.isArray(data.resultSets) && data.resultSets.length) {
        return data.resultSets.find(item => item.success !== false) || data.resultSets[0];
      }
      return data;
    },
    databasePreviewColumns() {
      return Array.isArray(this.databasePreviewData.columns) ? this.databasePreviewData.columns : [];
    },
    databasePreviewRows() {
      return Array.isArray(this.databasePreviewData.rows) ? this.databasePreviewData.rows : [];
    }
  },
  watch: {
    keyword() {
      this.page = 1;
    },
    listFilterValues: {
      deep: true,
      handler() {
        this.page = 1;
      }
    },
    pageCount(value) {
      if (this.page > value) this.page = value;
    },
    templatePickerKeyword() {
      this.templatePickerPage = 1;
    },
    templatePickerFilterValue() {
      this.templatePickerPage = 1;
    },
    templatePickerPageCount(value) {
      if (this.templatePickerPage > value) this.templatePickerPage = value;
    }
  },
  mounted() {
    this.load();
  },
  methods: {
    matchesListFilter(item, filter) {
      const value = String(this.listFilterValues[filter.key] || '').trim();
      if (!value) return true;
      const itemValue = String(item?.[filter.key] ?? '').trim().toLowerCase();
      const selected = this.listFilterOptions(filter).find(option => String(option.value) === value);
      const acceptedValues = [value, ...(Array.isArray(selected?.matches) ? selected.matches : [])]
        .map(candidate => String(candidate || '').trim().toLowerCase())
        .filter(Boolean);
      return acceptedValues.includes(itemValue);
    },
    async load() {
      this.busy = true;
      try {
        this.items = await this.listAction() || [];
        this.selectedIds = new Set([...this.selectedIds].filter(id => this.items.some(item => item.id === id)));
        this.$emit('loaded', this.items);
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.busy = false;
      }
    },
    listFilterOptions(filter) {
      const source = typeof filter.options === 'function' ? filter.options() : filter.options;
      return Array.isArray(source) ? source : [];
    },
    openCreate() {
      this.editingId = '';
      this.form = { ...this.defaults };
      this.formTestResult = null;
      this.prepareJsonDraft();
      this.prepareFormSections();
      this.formOpen = true;
    },
    openEdit(item) {
      this.editingId = item.id || '';
      this.form = JSON.parse(JSON.stringify({ ...this.defaults, ...item }));
      this.formTestResult = null;
      this.prepareJsonDraft();
      this.prepareFormSections();
      this.formOpen = true;
    },
    prepareJsonDraft() {
      this.databaseParameterValidationAttempted = false;
      this.jsonDraft = {};
      this.formFields.filter(field => field.type === 'json').forEach(field => {
        this.jsonDraft[field.key] = prettyJson(this.form[field.key], field.defaultValue ?? {});
      });
      this.listDraft = {};
      this.listInput = {};
      this.formFields.filter(field => field.type === 'jsonStringList' || field.type === 'templatePicker').forEach(field => {
        this.listDraft[field.key] = parseStringList(this.form[field.key], field.defaultValue ?? []);
        this.listInput[field.key] = '';
      });
      this.objectDraft = {};
      this.objectPresetSelection = {};
      this.formFields.filter(field => field.type === 'jsonObjectString' || field.type === 'jsonObject').forEach(field => {
        this.objectDraft[field.key] = objectToRows(parseObjectValue(this.form[field.key], field.defaultValue ?? {}));
        this.objectPresetSelection[field.key] = '';
      });
      this.schemaDraft = {};
      this.formFields.filter(field => field.type === 'jsonSchemaString' || field.type === 'jsonSchema').forEach(field => {
        this.schemaDraft[field.key] = schemaToRows(parseObjectValue(this.form[field.key], field.defaultValue ?? objectSchema()));
      });
      this.formFields.filter(field => field.type === 'databaseParamConfig').forEach(field => {
        const schema = parseObjectValue(this.form[field.schemaKey], field.defaultSchema ?? objectSchema());
        const params = parseObjectValue(this.form[field.paramsKey], {});
        this.schemaDraft[field.key] = databaseSchemaToRows(schema, params);
      });
      this.formFields.filter(field => field.type === 'databaseSqlSteps').forEach(field => {
        this.form[field.key] = normalizeDatabaseSqlSteps(this.form[field.key], this.form.sqlTemplate);
        this.databaseSqlSelectedIndexes[field.key] = 0;
        this.databaseSqlActiveTabs[field.key] = 'sql';
        this.databaseSqlSideTabs[field.key] = 'inputs';
      });
      this.formFields.filter(field => field.type === 'databaseParamConfig').forEach(field => {
        this.syncDatabaseParamsFromSql(field, false);
      });
      this.formFields.filter(field => field.unitScale).forEach(field => {
        const value = Number(this.form[field.key]);
        if (Number.isFinite(value)) {
          this.form[field.key] = value / field.unitScale;
        }
      });
      this.metadataScopeOptions = {};
      this.metadataScopeKeyword = '';
      this.metadataScopeOpenKey = '';
    },
    prepareFormSections() {
      this.collapsedFormSections = this.formSections
        .filter(section => defaultSectionCollapsed(section.key))
        .map(section => section.key);
    },
    async saveForm() {
      if (!this.validateFormBeforeSubmit()) return;
      if (!this.validateDatabaseParameterCompleteness(false)) return;
      this.busy = true;
      try {
        const payload = this.formPayload();
        const saved = await this.saveAction(payload);
        this.formOpen = false;
        this.$emit('notify', { title: '保存成功', message: saved?.title || saved?.toolName || saved?.name || '' });
        await this.load();
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.busy = false;
      }
    },
    formPayload() {
      const payload = { ...this.form };
      this.formFields.filter(field => field.type === 'json').forEach(field => {
        payload[field.key] = parseJsonObject(this.jsonDraft[field.key], field.defaultValue ?? {});
      });
      this.formFields.filter(field => field.type === 'jsonStringList' || field.type === 'templatePicker').forEach(field => {
        payload[field.key] = JSON.stringify(this.listDraft[field.key] || []);
      });
      this.formFields.filter(field => field.type === 'jsonObjectString').forEach(field => {
        payload[field.key] = JSON.stringify(rowsToObject(this.objectDraft[field.key] || []));
      });
      this.formFields.filter(field => field.type === 'jsonObject').forEach(field => {
        payload[field.key] = rowsToObject(this.objectDraft[field.key] || []);
      });
      this.formFields.filter(field => field.type === 'jsonSchemaString').forEach(field => {
        payload[field.key] = JSON.stringify(rowsToSchema(this.schemaDraft[field.key] || []));
      });
      this.formFields.filter(field => field.type === 'jsonSchema').forEach(field => {
        payload[field.key] = rowsToSchema(this.schemaDraft[field.key] || []);
      });
      this.formFields.filter(field => field.type === 'databaseParamConfig').forEach(field => {
        const rows = this.schemaDraft[field.key] || [];
        payload[field.schemaKey] = rowsToDatabaseSchema(rows);
        payload[field.paramsKey] = rowsToDatabaseParams(rows);
        delete payload[field.key];
      });
      this.formFields.filter(field => field.type === 'databaseSqlSteps').forEach(field => {
        payload[field.key] = normalizeDatabaseSqlSteps(this.form[field.key], this.form.sqlTemplate)
          .map((step, index) => ({
            ...step,
            executionOrder: index + 1,
            parameters: databaseStaticParametersToObject(step.staticParameterEntries)
          }));
        payload.sqlTemplate = payload[field.key][0]?.sqlContent || payload.sqlTemplate || '';
      });
      this.formFields.filter(field => field.unitScale).forEach(field => {
        const value = Number(payload[field.key]);
        payload[field.key] = Number.isFinite(value) ? Math.round(value * field.unitScale) : payload[field.key];
      });
      return payload;
    },
    async testFormDraft() {
      if (!this.formTestAction) return;
      if (!this.validateFormBeforeSubmit()) return;
      if (!this.validateDatabaseParameterCompleteness(true)) return;
      this.busy = true;
      try {
        const result = await this.formTestAction(this.formPayload());
        this.formTestResult = result;
        if (!isTestFailure(result)) this.updateMetadataScopeOptions(result);
        this.$emit('notify', {
          ...buildTestNotification(result)
        });
        if (!this.formPreviewType) {
          this.$emit('result', {
            title: `${this.form.toolName || this.form.name || this.title} 测试结果`,
            value: result
          });
        }
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.busy = false;
      }
    },
    async removeItem(item) {
      const confirmed = await this.confirm(`确定删除 ${item.title || item.toolName || item.name || item.id} 吗？`);
      if (!confirmed) return;
      await this.run(() => this.removeAction(item.id), '删除成功');
    },
    async removeSelected() {
      const confirmed = await this.confirm(`确定删除选中的 ${this.selectedIds.size} 条数据吗？`);
      if (!confirmed) return;
      const ids = [...this.selectedIds];
      await this.run(() => this.batchRemove(ids), '批量删除成功');
      this.clearSelection();
    },
    async toggleItem(item) {
      await this.run(() => this.toggleAction(item.id, item.enabled === false), item.enabled === false ? '已启用' : '已停用');
    },
    async testItem(item) {
      if (!this.validateFieldsBeforeSubmit(item, false)) return;
      try {
        const { value: raw } = await ElMessageBox.prompt('请输入测试参数 JSON', '测试调用', {
          inputType: 'textarea',
          inputValue: '{}',
          confirmButtonText: '执行',
          cancelButtonText: '取消'
        });
        if (this.busy || this.rowOperation) return;
        this.rowOperation = { type: 'test', rowId: item.id };
        const result = await this.testAction(item, parseJsonObject(raw, {}));
        this.$emit('notify', buildTestNotification(result));
        this.$emit('result', {
          title: `${item.title || item.toolName || item.name || '资源'} 测试结果`,
          value: result
        });
      } catch (error) {
        if (error !== 'cancel' && error !== 'close') {
          this.$emit('error', error);
        }
      } finally {
        if (this.rowOperation?.type === 'test' && this.rowOperation?.rowId === item.id) {
          this.rowOperation = null;
        }
      }
    },
    extraActionLabel(action, row) {
      return typeof action.label === 'function' ? action.label(row) : action.label;
    },
    extraActionType(action, row) {
      return typeof action.type === 'function' ? action.type(row) : action.type || 'primary';
    },
    extraActionDisabled(action, row) {
      if (this.busy || (this.rowOperation && !this.isRowOperation('extra', row, action))) return true;
      return typeof action.disabled === 'function' ? action.disabled(row) : !!action.disabled;
    },
    async runExtraAction(action, row) {
      if (!action || typeof action.run !== 'function') return;
      if (this.busy || this.rowOperation) return;
      this.rowOperation = { type: 'extra', rowId: row.id, actionKey: action.key || '' };
      try {
        await action.run(row);
        this.$emit('notify', { title: action.successMessage || '操作成功' });
        this.items = await this.listAction() || [];
        this.$emit('loaded', this.items);
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.rowOperation = null;
      }
    },
    async runRebuild() {
      if (this.rebuildRequiresSelection && !this.selectedIds.size) return;
      await this.run(
        () => this.rebuildAction([...this.selectedIds]),
        this.rebuildRequiresSelection ? `已重建选中的 ${this.selectedIds.size} 个资产索引` : '索引已重建'
      );
    },
    async run(action, message) {
      this.busy = true;
      try {
        const result = await action();
        this.$emit('notify', { title: message });
        await this.load();
        return result;
      } catch (error) {
        this.$emit('error', error);
        return null;
      } finally {
        this.busy = false;
      }
    },
    handleSelectionChange(rows) {
      this.selectedIds = new Set(rows.map(row => row.id).filter(Boolean));
    },
    selectVisible() {
      const next = new Set(this.selectedIds);
      this.visibleItems.forEach(item => item.id && next.add(item.id));
      this.selectedIds = next;
    },
    clearSelection() {
      this.selectedIds = new Set();
    },
    isRowOperation(type, row, action = null) {
      if (!this.rowOperation || this.rowOperation.type !== type || this.rowOperation.rowId !== row?.id) return false;
      if (type !== 'extra') return true;
      return this.rowOperation.actionKey === (action?.key || '');
    },
    rowActionDisabled(type, row, action = null) {
      return this.busy || Boolean(this.rowOperation && !this.isRowOperation(type, row, action));
    },
    fieldOptions(field) {
      return typeof field.options === 'function' ? field.options() : field.options || [];
    },
    isFieldVisible(field) {
      if (typeof field.visible === 'function') return field.visible(this.form);
      if (typeof field.hidden === 'function') return !field.hidden(this.form);
      return field.visible !== false && field.hidden !== true;
    },
    renderedSectionFields(section) {
      const fields = Array.isArray(section?.fields) ? section.fields : [];
      if (!this.hasDatabaseWorkflowField) return fields;
      return fields.filter(field => field.type !== 'databaseParamConfig');
    },
    isFieldRequired(field, form = this.form) {
      return typeof field.required === 'function' ? Boolean(field.required(form)) : field.required === true;
    },
    fieldValidationValue(field, form = this.form, useDraft = true) {
      if (field.type === 'jsonStringList' || field.type === 'templatePicker') {
        return useDraft ? (this.listDraft[field.key] || []) : parseStringList(form[field.key], field.defaultValue ?? []);
      }
      return form[field.key];
    },
    isEmptyFieldValue(value) {
      if (value == null) return true;
      if (typeof value === 'string') return value.trim() === '';
      if (Array.isArray(value)) return value.length === 0;
      return false;
    },
    validateFormBeforeSubmit() {
      return this.validateFieldsBeforeSubmit(this.form, true);
    },
    validateDatabaseParameterCompleteness(requireTestValues) {
      this.databaseParameterValidationAttempted = Boolean(requireTestValues);
      const paramField = this.formFields.find(field => field.type === 'databaseParamConfig');
      if (!paramField) return true;
      const rows = this.schemaDraft[paramField.key] || [];
      const errors = [];
      const names = new Set();
      rows.forEach((row, index) => {
        const name = String(row?.name || '').trim();
        if (!name) {
          errors.push(`第 ${index + 1} 个流程输入缺少参数名`);
        } else if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) {
          errors.push(`参数 ${name} 名称不合法`);
        } else if (names.has(name)) {
          errors.push(`参数 ${name} 重复`);
        } else {
          names.add(name);
        }
        if (!row?.defaultSource) errors.push(`参数 ${name || index + 1} 未选择来源`);
      });

      const missingTestValues = rows
        .filter(row => row?.required
          && (!row.defaultSource || row.defaultSource === 'user_input')
          && this.isEmptyFieldValue(row.testValue))
        .map(row => String(row.name || '').trim())
        .filter(Boolean);

      const workflowField = this.formFields.find(field => field.type === 'databaseSqlSteps');
      const steps = workflowField ? this.databaseSqlSteps(workflowField) : [];
      steps.forEach((step, stepIndex) => {
        const fixedNames = new Set((step.staticParameterEntries || []).map(item => String(item?.name || '').trim()).filter(Boolean));
        const mappings = new Map((step.parameterMappings || []).map(item => [String(item?.parameter || '').trim(), item]));
        fixedNames.forEach(name => {
          if (names.has(name)) {
            errors.push(`步骤 ${stepIndex + 1} 的参数 ${name} 同时配置了流程输入和固定值，请只保留一种来源`);
          }
        });
        mappings.forEach((mapping, name) => {
          if (names.has(name) && String(mapping?.sourceType || 'USER_INPUT').toUpperCase() !== 'USER_INPUT') {
            errors.push(`步骤 ${stepIndex + 1} 的参数 ${name} 同时配置了流程输入和${databaseMappingSourceLabel(mapping.sourceType)}，请只保留一种来源`);
          }
        });
        extractDatabaseQueryParameters(step.sqlContent).filter(param => !param.dynamic).forEach(param => {
          const mapping = mappings.get(param.name);
          if (!names.has(param.name) && !fixedNames.has(param.name) && !mapping) {
            errors.push(`步骤 ${stepIndex + 1} 的参数 ${param.name} 尚未配置来源`);
          }
          if (mapping?.sourceType === 'SYSTEM_CONTEXT' && this.isEmptyFieldValue(mapping.sourceKey)) {
            errors.push(`步骤 ${stepIndex + 1} 的参数 ${param.name} 未选择系统内置参数`);
          }
          if (mapping?.sourceType === 'STATIC' && this.isEmptyFieldValue(mapping.defaultValue)) {
            errors.push(`步骤 ${stepIndex + 1} 的固定参数 ${param.name} 未填写值`);
          }
        });
      });

      if (requireTestValues && missingTestValues.length) {
        errors.push(`请填写必填参数的流程测试值：${missingTestValues.join('、')}`);
      }
      if (!errors.length) return true;
      if (workflowField) {
        this.databaseSqlSideTabs[workflowField.key] = 'inputs';
      }
      this.$emit('error', new Error(`参数配置不完整：${[...new Set(errors)].join('；')}`));
      return false;
    },
    validateFieldsBeforeSubmit(form, useDraft) {
      const errors = [];
      const invalidSections = new Set();
      this.visibleFormFields.forEach(field => {
        const value = this.fieldValidationValue(field, form, useDraft);
        if (this.isFieldRequired(field, form) && this.isEmptyFieldValue(value)) {
          errors.push(`${field.label}不能为空`);
          invalidSections.add(field.section || 'basic');
          return;
        }
        if (Array.isArray(field.requiredAnyOf)) {
          const values = (Array.isArray(value) ? value : [])
            .map(item => String(item || '').trim().toLowerCase())
            .filter(Boolean);
          const accepted = field.requiredAnyOf.map(item => String(item).trim().toLowerCase());
          if (!accepted.some(item => values.includes(item))) {
            errors.push(field.requiredAnyOfMessage || `${field.label}至少需要包含：${field.requiredAnyOf.join('、')}`);
            invalidSections.add(field.section || 'basic');
          }
        }
        if (field.type === 'databaseSqlSteps' && Array.isArray(value)) {
          const codes = new Set();
          value.forEach((step, index) => {
            const stepNumber = index + 1;
            if (this.isEmptyFieldValue(step?.sqlCode)) {
              errors.push(`第 ${stepNumber} 条 SQL 的节点编码不能为空`);
            } else if (codes.has(String(step.sqlCode).trim().toUpperCase())) {
              errors.push(`第 ${stepNumber} 条 SQL 的节点编码重复`);
            } else {
              codes.add(String(step.sqlCode).trim().toUpperCase());
            }
            if (this.isEmptyFieldValue(step?.sqlName)) {
              errors.push(`第 ${stepNumber} 条 SQL 的名称不能为空`);
            }
            if (this.isEmptyFieldValue(step?.sqlDescription)) {
              errors.push(`第 ${stepNumber} 条 SQL 的结果集说明不能为空`);
            }
            if (this.isEmptyFieldValue(step?.sqlContent)) {
              errors.push(`第 ${stepNumber} 条 SQL 内容不能为空`);
            }
            const staticParameterNames = new Set();
            (step?.staticParameterEntries || []).forEach((parameter, parameterIndex) => {
              const name = String(parameter?.name || '').trim();
              if (!name) {
                errors.push(`第 ${stepNumber} 条 SQL 的独立参数 ${parameterIndex + 1} 缺少参数名`);
              } else if (staticParameterNames.has(name)) {
                errors.push(`第 ${stepNumber} 条 SQL 的独立参数 ${name} 重复`);
              } else {
                staticParameterNames.add(name);
              }
            });
            (step?.parameterMappings || []).forEach((mapping, mappingIndex) => {
              if (this.isEmptyFieldValue(mapping?.parameter)) {
                errors.push(`第 ${stepNumber} 条 SQL 的参数映射 ${mappingIndex + 1} 缺少参数名`);
              }
              if (mapping?.sourceType === 'UPSTREAM_RESULT' && this.isEmptyFieldValue(mapping?.sourceNode)) {
                errors.push(`第 ${stepNumber} 条 SQL 的上游结果参数 ${mappingIndex + 1} 缺少来源节点`);
              }
            });
            if (errors.length) invalidSections.add(field.section || 'basic');
          });
          const workflowError = validateDatabaseSqlWorkflow(value);
          if (workflowError) {
            errors.push(workflowError);
            invalidSections.add(field.section || 'basic');
          }
        }
      });
      if (!errors.length) return true;
      this.collapsedFormSections = this.collapsedFormSections.filter(section => !invalidSections.has(section));
      this.$emit('error', new Error(`请完善必填参数：${errors.join('；')}`));
      return false;
    },
    fieldColSpan(field) {
      if (field.compact) return 8;
      if (field.span === 'col-12') return 24;
      if (field.span === 'col-md-8') return 16;
      if (field.span === 'col-md-4') return 8;
      return 12;
    },
    isFormSectionCollapsed(key) {
      return this.collapsedFormSections.includes(key);
    },
    toggleFormSection(key) {
      this.collapsedFormSections = this.isFormSectionCollapsed(key)
        ? this.collapsedFormSections.filter(item => item !== key)
        : [...this.collapsedFormSections, key];
    },
    addListEntry(field) {
      const raw = this.listInput[field.key] || '';
      const entries = raw
        .split(/[,，\n]+/)
        .map(item => item.trim())
        .filter(Boolean);
      if (!entries.length) return;
      const current = this.listDraft[field.key] || [];
      this.listDraft[field.key] = [...new Set([...current, ...entries])];
      this.listInput[field.key] = '';
    },
    removeListEntry(key, index) {
      this.listDraft[key] = (this.listDraft[key] || []).filter((_, currentIndex) => currentIndex !== index);
    },
    openTemplatePicker(field) {
      this.templatePickerField = field;
      this.templatePickerKeyword = '';
      const defaultFilterKey = field.filterDefaultFromForm;
      this.templatePickerFilterValue = defaultFilterKey
        ? String(this.form?.[defaultFilterKey] || '').trim()
        : String(field.filterDefaultValue || '').trim();
      this.templatePickerPage = 1;
      this.templatePickerSelected = [...(this.listDraft[field.key] || [])];
      this.templatePickerOpen = true;
    },
    templatePickerItemKey(item) {
      const key = this.templatePickerField?.itemKey || 'code';
      return String(item?.[key] || '').trim();
    },
    toggleTemplatePickerItem(item) {
      const key = this.templatePickerItemKey(item);
      if (!key) return;
      const selected = new Set(this.templatePickerSelected);
      if (selected.has(key)) selected.delete(key);
      else selected.add(key);
      this.templatePickerSelected = [...selected];
    },
    selectTemplatePickerVisible() {
      const selected = new Set(this.templatePickerSelected);
      this.templatePickerVisibleItems.forEach(item => {
        const key = this.templatePickerItemKey(item);
        if (key) selected.add(key);
      });
      this.templatePickerSelected = [...selected];
    },
    selectTemplatePickerFiltered() {
      const selected = new Set(this.templatePickerSelected);
      this.templatePickerFilteredItems.forEach(item => {
        const key = this.templatePickerItemKey(item);
        if (key) selected.add(key);
      });
      this.templatePickerSelected = [...selected];
    },
    clearTemplatePickerVisible() {
      const visible = new Set(this.templatePickerVisibleItems.map(item => this.templatePickerItemKey(item)).filter(Boolean));
      this.templatePickerSelected = this.templatePickerSelected.filter(key => !visible.has(key));
    },
    clearTemplatePickerFiltered() {
      const filtered = new Set(this.templatePickerFilteredItems.map(item => this.templatePickerItemKey(item)).filter(Boolean));
      this.templatePickerSelected = this.templatePickerSelected.filter(key => !filtered.has(key));
    },
    confirmTemplatePicker() {
      if (this.templatePickerField?.key) {
        this.listDraft[this.templatePickerField.key] = [...new Set(this.templatePickerSelected)];
      }
      this.templatePickerOpen = false;
    },
    removeTemplateSelection(key, entry) {
      this.listDraft[key] = (this.listDraft[key] || []).filter(item => item !== entry);
    },
    clearTemplateSelection(key) {
      this.listDraft[key] = [];
    },
    updateMetadataScopeOptions(result) {
      this.formFields.filter(field => field.type === 'metadataScopePicker').forEach(field => {
        const options = typeof field.extractOptions === 'function'
          ? field.extractOptions(result)
          : extractMetadataScopeOptions(result);
        this.metadataScopeOptions[field.key] = normalizeStringList(options);
        if (this.metadataScopeOptions[field.key].length) {
          this.metadataScopeOpenKey = field.key;
          this.metadataScopeKeyword = '';
        }
      });
    },
    toggleMetadataScopePanel(key) {
      this.metadataScopeOpenKey = this.metadataScopeOpenKey === key ? '' : key;
      this.metadataScopeKeyword = '';
    },
    metadataScopeSelected(key) {
      return parseCsvList(this.form[key]);
    },
    metadataScopeVisibleOptions(key) {
      const keyword = this.metadataScopeKeyword.trim().toLowerCase();
      return (this.metadataScopeOptions[key] || [])
        .filter(option => !keyword || option.toLowerCase().includes(keyword))
        .slice(0, 80);
    },
    toggleMetadataScopeValue(key, option) {
      const values = this.metadataScopeSelected(key);
      const next = values.includes(option)
        ? values.filter(value => value !== option)
        : [...values, option];
      this.form[key] = next.join(',');
    },
    removeMetadataScopeValue(key, option) {
      this.form[key] = this.metadataScopeSelected(key)
        .filter(value => value !== option)
        .join(',');
    },
    clearMetadataScopeValue(key) {
      this.form[key] = '';
    },
    addDatabaseParamEntry(field, param = {}) {
      this.schemaDraft[field.key] = [...(this.schemaDraft[field.key] || []), databaseParamRow(param)];
    },
    removeDatabaseParamEntry(field, index) {
      this.schemaDraft[field.key] = (this.schemaDraft[field.key] || []).filter((_, currentIndex) => currentIndex !== index);
    },
    handleDatabaseParamSourceChange(entry) {
      if (entry.defaultSource && entry.defaultSource !== 'user_input') {
        entry.testValue = '';
        entry.required = false;
      }
    },
    reconcileDatabaseFlowInputs(field, stepsOverride = null) {
      if (!field) return;
      const rows = this.schemaDraft[field.key] || [];
      const existing = new Map(rows.map(row => [String(row?.name || '').trim(), row]).filter(([name]) => name));
      const steps = stepsOverride || normalizeDatabaseSqlSteps(this.form[field.sqlStepsKey], this.form[field.sqlKey]);
      const externalInputs = new Map();
      steps.forEach(step => {
        (step.parameterMappings || []).forEach(mapping => {
          if (String(mapping?.sourceType || 'USER_INPUT').toUpperCase() !== 'USER_INPUT') return;
          const parameter = String(mapping?.parameter || '').trim();
          const sourceKey = String(mapping?.sourceKey || parameter).trim();
          if (sourceKey && (!externalInputs.has(sourceKey) || mapping.required === true)) {
            externalInputs.set(sourceKey, mapping);
          }
        });
      });
      this.schemaDraft[field.key] = [...externalInputs.entries()].map(([name, mapping]) => {
        const row = existing.get(name) || databaseParamRow({ name, type: 'string', required: mapping.required === true });
        row.name = name;
        row.defaultSource = 'user_input';
        row.required = mapping.required === true;
        return row;
      });
    },
    handleDatabaseFlowInputRequiredChange(param) {
      const name = String(param?.name || '').trim();
      if (!name) return;
      this.formFields.filter(field => field.type === 'databaseSqlSteps').forEach(field => {
        this.databaseSqlSteps(field).forEach(step => {
          (step.parameterMappings || []).forEach(mapping => {
            const sourceType = String(mapping?.sourceType || 'USER_INPUT').toUpperCase();
            const sourceKey = String(mapping?.sourceKey || mapping?.parameter || '').trim();
            if (sourceType === 'USER_INPUT' && sourceKey === name) {
              mapping.required = param.required === true;
            }
          });
        });
      });
    },
    syncDatabaseParamsFromSql(field, notifyUser = false, targetSqlCode = '') {
      const rows = [...(this.schemaDraft[field.key] || [])];
      const steps = normalizeDatabaseSqlSteps(this.form[field.sqlStepsKey], this.form[field.sqlKey]);
      const stepsToSync = targetSqlCode
        ? steps.filter(step => String(step.sqlCode || '') === String(targetSqlCode))
        : steps;
      const detectedNames = new Set();
      let schemaAdded = 0;
      let mappingAdded = 0;
      let fixedSkipped = 0;
      let mappedSkipped = 0;
      let dynamicConfigured = 0;
      const ensureSchemaRow = param => {
        detectedNames.add(param.name);
        const existed = rows.find(row => row.name === param.name);
        if (existed) {
          if (param.dynamic) {
            existed.defaultSource = param.defaultSource;
            existed.required = false;
            existed.testValue = '';
          }
          if (param.required && (!existed.defaultSource || existed.defaultSource === 'user_input')) {
            existed.required = true;
          }
          return false;
        }
        rows.push(databaseParamRow({
          name: param.name,
          type: 'string',
          required: param.required,
          defaultSource: param.defaultSource,
          testValue: param.dynamic ? '' : param.example,
          exampleValue: param.example,
          description: param.dynamic
            ? `Runtime dynamic parameter: ${param.defaultSource}`
            : `Query parameter: ${param.name}`
        }));
        schemaAdded += 1;
        return true;
      };
      stepsToSync.forEach(step => {
        const staticNames = new Set((step.staticParameterEntries || [])
          .map(entry => String(entry?.name || '').trim())
          .filter(Boolean));
        const mappings = [...(step.parameterMappings || [])];
        const mappingsByName = new Map(mappings
          .filter(mapping => mapping?.parameter)
          .map(mapping => [String(mapping.parameter).trim(), mapping]));
        extractDatabaseQueryParameters(step.sqlContent).forEach(param => {
          if (param.dynamic) {
            if (!mappingsByName.has(param.name)) {
              mappings.push({
                parameter: param.name,
                sourceType: 'SYSTEM_CONTEXT',
                sourceKey: param.defaultSource,
                sourceNode: '',
                sourceExpression: '$.rows[0]',
                defaultValue: '',
                required: false
              });
              mappingsByName.set(param.name, mappings[mappings.length - 1]);
              mappingAdded += 1;
            }
            dynamicConfigured += 1;
            return;
          }
          if (staticNames.has(param.name)) {
            fixedSkipped += 1;
            return;
          }
          const mapping = mappingsByName.get(param.name);
          if (mapping) {
            if (String(mapping.sourceType || 'USER_INPUT').toUpperCase() === 'USER_INPUT') {
              ensureSchemaRow(param);
            } else {
              mappedSkipped += 1;
            }
            return;
          }
          const schemaParam = rows.find(row => String(row?.name || '').trim() === param.name);
          if (schemaParam?.defaultSource && schemaParam.defaultSource !== 'user_input') {
            mappings.push({
              parameter: param.name,
              sourceType: 'SYSTEM_CONTEXT',
              sourceKey: schemaParam.defaultSource,
              sourceNode: '',
              sourceExpression: '$.rows[0]',
              defaultValue: '',
              required: false
            });
            mappingsByName.set(param.name, mappings[mappings.length - 1]);
            mappingAdded += 1;
            mappedSkipped += 1;
            return;
          }
          mappings.push({
            parameter: param.name,
            sourceType: 'USER_INPUT',
            sourceKey: param.name,
            sourceNode: '',
            sourceExpression: '$.rows[0]',
            defaultValue: '',
            required: true
          });
          mappingsByName.set(param.name, mappings[mappings.length - 1]);
          mappingAdded += 1;
          ensureSchemaRow(param);
        });
        step.parameterMappings = mappings;
      });
      this.schemaDraft[field.key] = rows;
      this.form[field.sqlStepsKey] = steps;
      this.reconcileDatabaseFlowInputs(field, steps);
      const staleUserInputs = targetSqlCode ? [] : rows
          .filter(row => (!row.defaultSource || row.defaultSource === 'user_input') && !detectedNames.has(row.name))
          .map(row => row.name)
          .filter(Boolean);
      if (notifyUser) {
        const details = [
          schemaAdded > 0 ? `新增 ${schemaAdded} 个集合参数` : '集合参数无新增',
          mappingAdded > 0 ? `生成 ${mappingAdded} 个节点映射` : '节点映射无新增',
          fixedSkipped > 0 ? `${fixedSkipped} 项由固定参数提供` : '',
          mappedSkipped > 0 ? `${mappedSkipped} 项已有非用户映射` : '',
          dynamicConfigured > 0 ? `${dynamicConfigured} 项为系统动态参数` : '',
          staleUserInputs.length ? `发现 ${staleUserInputs.length} 个未被当前 SQL 使用的旧参数，请人工确认` : ''
        ].filter(Boolean);
        this.$emit('notify', {
          title: '参数扫描完成',
          message: `${details.join('；')}。`
        });
      }
    },
    syncDatabaseSqlStepParams(entry) {
      if (!String(entry?.sqlContent || '').trim()) {
        this.$emit('error', '请先填写当前节点的 SQL 模板');
        return;
      }
      const paramField = this.formFields.find(field => field.type === 'databaseParamConfig');
      if (!paramField) {
        this.$emit('error', '当前数据库查询未配置参数模型');
        return;
      }
      this.syncDatabaseParamsFromSql(paramField, true, entry.sqlCode);
    },
    databaseParamSummary(field) {
      const params = extractDatabaseQueryParameters(this.databaseSqlTextForField(field));
      if (!params.length) return '';
      return `参数：${params.map(param => param.dynamic ? `${param.name}:自动` : param.name).join(', ')}`;
    },
    databaseParamNodeSummaries(field) {
      const steps = normalizeDatabaseSqlSteps(this.form[field.sqlStepsKey], this.form[field.sqlKey]);
      return steps.map(step => databaseSqlStepParameterSummary(step)).filter(summary => summary.total > 0);
    },
    databaseSqlTextForField(field) {
      const steps = normalizeDatabaseSqlSteps(this.form[field.sqlStepsKey], this.form[field.sqlKey]);
      if (steps.length) {
        return steps.map(step => step.sqlContent || '').join('\n');
      }
      return this.form[field.sqlKey] || '';
    },
    databaseSqlSteps(field) {
      return Array.isArray(this.form[field.key]) ? this.form[field.key] : [];
    },
    databaseSelectedSqlStep(field) {
      const steps = this.databaseSqlSteps(field);
      if (!steps.length) return null;
      const index = Math.max(0, Math.min(Number(this.databaseSqlSelectedIndexes[field.key] || 0), steps.length - 1));
      return steps[index];
    },
    databaseSelectedSqlStepIndex(field) {
      const steps = this.databaseSqlSteps(field);
      if (!steps.length) return -1;
      return Math.max(0, Math.min(Number(this.databaseSqlSelectedIndexes[field.key] || 0), steps.length - 1));
    },
    selectDatabaseSqlStep(field, index) {
      this.databaseSqlSelectedIndexes[field.key] = index;
    },
    databaseParamConfigField() {
      return this.formFields.find(field => field.type === 'databaseParamConfig') || null;
    },
    databaseSqlStepInputCount(step) {
      return (step?.staticParameterEntries?.length || 0) + (step?.parameterMappings?.length || 0);
    },
    databaseParamRequiresTestValue(param) {
      return Boolean(param?.required) && (!param.defaultSource || param.defaultSource === 'user_input');
    },
    databaseParamTestValueMissing(param) {
      return this.databaseParamRequiresTestValue(param) && this.isEmptyFieldValue(param.testValue);
    },
    databaseSqlWorkflowEnabled(field) {
      return this.databaseSqlSteps(field).some(step => step.workflowEnabled === true);
    },
    setDatabaseSqlWorkflowEnabled(field, enabled) {
      const steps = normalizeDatabaseSqlSteps(this.form[field.key], this.form.sqlTemplate);
      this.form[field.key] = steps.map((step, index) => ({
        ...step,
        workflowEnabled: enabled === true,
        dependencies: enabled === true ? step.dependencies : [],
        executionOrder: index + 1
      }));
    },
    databaseSqlDependencyOptions(field, current) {
      return this.databaseSqlSteps(field)
        .filter(step => step !== current && step.enabled !== false && step.sqlCode)
        .map(step => ({ value: step.sqlCode, label: `${step.sqlName || step.sqlCode}（${step.sqlCode}）` }));
    },
    databaseSqlWorkflowLevels(field) {
      return databaseSqlWorkflowLevels(this.databaseSqlSteps(field));
    },
    databaseSqlDisplayLevels(field) {
      const steps = this.databaseSqlSteps(field).filter(step => step?.enabled !== false);
      return this.databaseSqlWorkflowEnabled(field)
        ? databaseSqlWorkflowLevels(steps)
        : steps.map(step => [step]);
    },
    addDatabaseSqlParameterMapping(entry) {
      entry.parameterMappings = [...(entry.parameterMappings || []), {
        parameter: '', sourceType: 'USER_INPUT', sourceKey: '', sourceNode: '',
        sourceExpression: '$.rows[0]', defaultValue: '', required: false
      }];
    },
    handleDatabaseSqlMappingSourceChange(mapping) {
      const sourceType = String(mapping?.sourceType || 'USER_INPUT').toUpperCase();
      if (sourceType === 'USER_INPUT') {
        mapping.sourceKey = String(mapping.sourceKey || mapping.parameter || '').trim();
        mapping.sourceNode = '';
        mapping.defaultValue = '';
      } else if (sourceType === 'SYSTEM_CONTEXT') {
        mapping.sourceNode = '';
        mapping.defaultValue = '';
        mapping.required = false;
      } else if (sourceType === 'UPSTREAM_RESULT') {
        mapping.sourceKey = '';
        mapping.defaultValue = '';
      } else if (sourceType === 'STATIC') {
        mapping.sourceKey = '';
        mapping.sourceNode = '';
        mapping.required = true;
      }
      this.reconcileDatabaseFlowInputs(this.databaseParamConfigField());
    },
    removeDatabaseSqlParameterMapping(entry, index) {
      entry.parameterMappings = (entry.parameterMappings || []).filter((_, currentIndex) => currentIndex !== index);
      this.reconcileDatabaseFlowInputs(this.databaseParamConfigField());
    },
    addDatabaseSqlStaticParameter(entry) {
      entry.staticParameterEntries = [...(entry.staticParameterEntries || []), {
        name: '', type: 'string', value: ''
      }];
    },
    removeDatabaseSqlStaticParameter(entry, index) {
      entry.staticParameterEntries = (entry.staticParameterEntries || []).filter((_, currentIndex) => currentIndex !== index);
    },
    normalizeDatabaseSqlStaticParameterValue(parameter) {
      if (parameter.type === 'dynamic_date') {
        if (!/^\$\{(?:today|natural_date|month|month_start|month_end|trade_date(?:[+-]\d+)?)\}$/.test(String(parameter.value || ''))) {
          parameter.value = '${trade_date}';
        }
        return;
      }
      parameter.value = coerceDatabaseStaticParameterValue(parameter.value, parameter.type || 'string');
    },
    addDatabaseResultUnit(entry) {
      entry.unitDescriptionEntries = [...(entry.unitDescriptionEntries || []), { field: '', unit: '' }];
    },
    removeDatabaseResultUnit(entry, index) {
      entry.unitDescriptionEntries = (entry.unitDescriptionEntries || []).filter((_, currentIndex) => currentIndex !== index);
    },
    addDatabaseSqlStep(field, source = {}) {
      const steps = normalizeDatabaseSqlSteps(this.form[field.key], this.form.sqlTemplate);
      steps.push(databaseSqlStepRow({ executionOrder: steps.length + 1, workflowEnabled: true, ...source }, steps.length));
      if (steps.length > 1) steps.forEach(step => { step.workflowEnabled = true; });
      this.form[field.key] = resequenceDatabaseSqlSteps(steps);
      this.databaseSqlSelectedIndexes[field.key] = steps.length - 1;
      this.databaseSqlActiveTabs[field.key] = 'basic';
    },
    copyDatabaseSqlStep(field, index) {
      const steps = normalizeDatabaseSqlSteps(this.form[field.key], this.form.sqlTemplate);
      const copy = databaseSqlStepRow({
        ...steps[index],
        sqlCode: `${steps[index]?.sqlCode || 'SQL'}_COPY`,
        sqlName: `${steps[index]?.sqlName || 'SQL'} Copy`
      }, index + 1);
      steps.splice(index + 1, 0, copy);
      this.form[field.key] = resequenceDatabaseSqlSteps(steps);
      this.databaseSqlSelectedIndexes[field.key] = index + 1;
    },
    removeDatabaseSqlStep(field, index) {
      const steps = normalizeDatabaseSqlSteps(this.form[field.key], this.form.sqlTemplate);
      steps.splice(index, 1);
      this.form[field.key] = resequenceDatabaseSqlSteps(steps);
      this.databaseSqlSelectedIndexes[field.key] = Math.max(0, Math.min(index, steps.length - 1));
    },
    moveDatabaseSqlStep(field, index, delta) {
      const steps = normalizeDatabaseSqlSteps(this.form[field.key], this.form.sqlTemplate);
      const nextIndex = index + delta;
      if (nextIndex < 0 || nextIndex >= steps.length) return;
      const [item] = steps.splice(index, 1);
      steps.splice(nextIndex, 0, item);
      this.form[field.key] = resequenceDatabaseSqlSteps(steps);
      this.databaseSqlSelectedIndexes[field.key] = nextIndex;
    },
    appendDatabasePreviewParam(field, sampleValue) {
      return this.appendDatabasePreviewParams([field], { [field]: sampleValue });
    },
    appendDatabasePreviewParams(fields, sampleRow = {}) {
      const paramField = this.formFields.find(field => field.type === 'databaseParamConfig');
      if (!paramField) return;
      const rows = [...(this.schemaDraft[paramField.key] || [])];
      const names = rows.map(row => row.name).filter(Boolean);
      let added = 0;
      fields.forEach(field => {
        const name = uniqueName(normalizeParameterName(field), names);
        names.push(name);
        const existed = rows.some(row => row.name === name);
        if (!existed) {
          rows.push(databaseParamRow({
            name,
            type: inferJsonSchemaType(sampleRow?.[field]),
            required: true,
            defaultSource: 'user_input',
            testValue: sampleRow?.[field],
            exampleValue: sampleRow?.[field],
            description: field === name ? `预览字段 ${field}` : `预览字段 ${field}，参数名已转换`
          }));
          added += 1;
        }
      });
      this.schemaDraft[paramField.key] = rows;
      this.$emit('notify', {
        title: '参数已添加',
        message: added > 0 ? `已从查询结果提取 ${added} 个列名参数。` : '参数已存在。'
      });
    },
    formatPreviewCell(value) {
      if (value == null) return '';
      if (typeof value === 'object') return JSON.stringify(value);
      return String(value);
    },
    prettyPreviewJson(value) {
      return prettyJson(value, {});
    },
    addObjectEntry(key) {
      this.objectDraft[key] = [...(this.objectDraft[key] || []), { key: '', value: '' }];
    },
    objectPresetOptions(field) {
      const source = field.objectPresets || field.presets;
      const options = typeof source === 'function' ? source(this.form) : source;
      return Array.isArray(options) ? options : [];
    },
    addObjectPreset(field) {
      const selectedId = String(this.objectPresetSelection[field.key] || '').trim();
      if (!selectedId) return;
      const preset = this.objectPresetOptions(field)
        .find(option => String(option.id || option.key) === selectedId);
      if (!preset?.key) return;
      const rows = [...(this.objectDraft[field.key] || [])];
      const exists = rows.some(row => String(row.key || '').trim().toLowerCase() === String(preset.key).trim().toLowerCase());
      if (!exists) {
        rows.push({ key: preset.key, value: preset.value ?? '' });
        this.objectDraft[field.key] = rows;
      }
      this.objectPresetSelection[field.key] = '';
    },
    removeObjectEntry(key, index) {
      this.objectDraft[key] = (this.objectDraft[key] || []).filter((_, currentIndex) => currentIndex !== index);
    },
    addSchemaEntry(key) {
      this.schemaDraft[key] = [...(this.schemaDraft[key] || []), {
        name: '',
        type: 'string',
        required: false,
        description: ''
      }];
    },
    removeSchemaEntry(key, index) {
      this.schemaDraft[key] = (this.schemaDraft[key] || []).filter((_, currentIndex) => currentIndex !== index);
    },
    formatColumn(item, column) {
      if (column.formatter) return column.formatter(item[column.key], item);
      if (column.type === 'enabled') return item[column.key] === false ? '停用' : '启用';
      const value = item[column.key];
      return value === undefined || value === null || value === '' ? '-' : String(value);
    },
    tagType(value) {
      if (value === false || value === 'DISABLED' || value === 'OFFLINE') return 'info';
      if (value === true || value === 'ENABLED' || value === 'ONLINE' || value === 'ACTIVE') return 'success';
      return 'primary';
    },
    async confirm(message) {
      try {
        await ElMessageBox.confirm(message, '确认操作', {
          type: 'warning',
          confirmButtonText: '确认',
          cancelButtonText: '取消'
        });
        return true;
      } catch (error) {
        return false;
      }
    }
  }
};

function defaultSectionTitle(key) {
  const titles = {
    basic: '基础信息',
    connection: '连接配置',
    runtime: '运行策略',
    metadata: '元数据范围',
    authorization: '授权与标签',
    request: '请求配置',
    advanced: '高级配置'
  };
  return titles[key] || '其他配置';
}

function defaultSectionCollapsed(key) {
  return ['runtime', 'authorization', 'advanced'].includes(key);
}

function parseStringList(value, fallback = []) {
  if (Array.isArray(value)) {
    return normalizeStringList(value);
  }
  if (value == null || String(value).trim() === '') {
    return normalizeStringList(fallback);
  }
  const text = String(value).trim();
  try {
    const parsed = JSON.parse(text);
    return Array.isArray(parsed) ? normalizeStringList(parsed) : normalizeStringList(fallback);
  } catch (error) {
    return normalizeStringList(text.split(/[,，\n]+/));
  }
}

function normalizeStringList(value) {
  return [...new Set((Array.isArray(value) ? value : [])
    .map(item => String(item ?? '').trim())
    .filter(Boolean))];
}

function parseCsvList(value) {
  return normalizeStringList(String(value || '').split(/[,，;\r\n]+/));
}

function extractMetadataScopeOptions(result) {
  const diagnostics = result?.diagnostics || {};
  return diagnostics.metadataScopeOptions || diagnostics.availableDatabases || diagnostics.availableSchemas || [];
}

function parseObjectValue(value, fallback = {}) {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    return value;
  }
  if (value == null || String(value).trim() === '') {
    return fallback;
  }
  try {
    const parsed = JSON.parse(String(value));
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : fallback;
  } catch (error) {
    return fallback;
  }
}

function objectToRows(value) {
  return Object.entries(value || {}).map(([key, entryValue]) => ({
    key,
    value: String(entryValue ?? '')
  }));
}

function rowsToObject(rows) {
  return rows.reduce((result, row) => {
    const key = String(row.key || '').trim();
    if (key) result[key] = row.value ?? '';
    return result;
  }, {});
}

function schemaToRows(schema) {
  const properties = schema?.properties && typeof schema.properties === 'object' ? schema.properties : {};
  const required = Array.isArray(schema?.required) ? schema.required : [];
  return Object.entries(properties).map(([name, definition]) => ({
    name,
    type: definition?.type || 'string',
    required: required.includes(name),
    description: definition?.description || ''
  }));
}

function rowsToSchema(rows) {
  const properties = {};
  const required = [];
  rows.forEach(row => {
    const name = String(row.name || '').trim();
    if (!name) return;
    properties[name] = {
      type: row.type || 'string'
    };
    if (row.description) {
      properties[name].description = row.description;
    }
    if (row.required) {
      required.push(name);
    }
  });
  return {
    type: 'object',
    properties,
    required,
    additionalProperties: false
  };
}

function databaseSchemaToRows(schema, testParams = {}) {
  const normalized = parseObjectValue(schema, objectSchema());
  const properties = normalized.properties && typeof normalized.properties === 'object' ? normalized.properties : {};
  const required = Array.isArray(normalized.required) ? normalized.required : [];
  return Object.entries(properties).map(([name, definition]) => {
    const defaultSource = definition?.defaultSource || 'user_input';
    return databaseParamRow({
      name,
      type: definition?.type || 'string',
      required: required.includes(name),
      defaultSource,
      testValue: testParams?.[name],
      exampleValue: Array.isArray(definition?.examples) ? definition.examples[0] : definition?.example,
      description: definition?.description || ''
    });
  });
}

function databaseParamRow(param = {}) {
  const defaultSource = param.defaultSource || (param.dynamic ? 'trade_date' : 'user_input');
  return {
    name: param.name || '',
    type: param.type || 'string',
    required: Boolean(param.required) && defaultSource === 'user_input',
    defaultSource,
    testValue: formatParamValue(param.testValue),
    exampleValue: formatParamValue(param.exampleValue),
    description: param.description || ''
  };
}

function rowsToDatabaseSchema(rows) {
  const properties = {};
  const required = [];
  const names = new Set();
  rows.forEach(row => {
    const name = String(row.name || '').trim();
    if (!name) return;
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) {
      throw new Error(`查询参数名 ${name} 不合法，只能使用字母、数字和下划线，且不能以数字开头`);
    }
    if (names.has(name)) {
      throw new Error(`查询参数名 ${name} 重复`);
    }
    names.add(name);
    const type = row.type || 'string';
    const defaultSource = row.defaultSource || 'user_input';
    const definition = { type };
    if (row.description) {
      definition.description = row.description;
    }
    if (defaultSource !== 'user_input') {
      definition.defaultSource = defaultSource;
    }
    if (row.exampleValue !== '') {
      definition.examples = [coerceParamValue(row.exampleValue, type)];
    }
    properties[name] = definition;
    if (row.required && defaultSource === 'user_input') {
      required.push(name);
    }
  });
  return {
    type: 'object',
    properties,
    required,
    additionalProperties: false
  };
}

function rowsToDatabaseParams(rows) {
  return rows.reduce((result, row) => {
    const name = String(row.name || '').trim();
    const defaultSource = row.defaultSource || 'user_input';
    if (!name || defaultSource !== 'user_input' || row.testValue === '') {
      return result;
    }
    result[name] = coerceParamValue(row.testValue, row.type || 'string');
    return result;
  }, {});
}

function normalizeDatabaseSqlSteps(value, legacySql = '') {
  const raw = Array.isArray(value) ? value : [];
  const steps = raw
    .filter(Boolean)
    .map((step, index) => databaseSqlStepRow(step, index));
  if (!steps.length && legacySql) {
    steps.push(databaseSqlStepRow({
      sqlCode: 'SQL_1',
      sqlName: 'SQL 1',
      sqlDescription: 'Primary result set returned by this query template.',
      sqlContent: legacySql,
      executionOrder: 1,
      enabled: true,
      failureStrategy: 'STOP'
    }, 0));
  }
  return resequenceDatabaseSqlSteps(steps);
}

function databaseSqlStepRow(step = {}, index = 0) {
  const parameters = step.parameters && typeof step.parameters === 'object' && !Array.isArray(step.parameters)
    ? step.parameters
    : parseObjectValue(step.parametersJson, {});
  const staticParameterEntries = Array.isArray(step.staticParameterEntries)
    ? step.staticParameterEntries.map(databaseStaticParameterRow)
    : Object.entries(parameters).map(([name, value]) => databaseStaticParameterRow({ name, value }));
  const parameterMappings = Array.isArray(step.parameterMappings) ? step.parameterMappings.map(mapping => ({
    parameter: mapping.parameter || '',
    sourceType: String(mapping.sourceType || 'USER_INPUT').toUpperCase(),
    sourceKey: mapping.sourceKey || '',
    sourceNode: mapping.sourceNode || '',
    sourceExpression: mapping.sourceExpression || '$.rows[0]',
    defaultValue: mapping.defaultValue ?? '',
    required: mapping.required === true
  })) : [];
  const mappedParameters = new Set(parameterMappings.map(mapping => String(mapping.parameter || '').trim()).filter(Boolean));
  staticParameterEntries.forEach(parameter => {
    const name = String(parameter.name || '').trim();
    if (!name || mappedParameters.has(name)) return;
    parameterMappings.push({
      parameter: name,
      sourceType: 'STATIC',
      sourceKey: '',
      sourceNode: '',
      sourceExpression: '$.rows[0]',
      defaultValue: parameter.value,
      required: true
    });
    mappedParameters.add(name);
  });
  const semantic = step.resultSemantic && typeof step.resultSemantic === 'object' ? step.resultSemantic : {};
  const primaryKeys = step.primaryKeysText !== undefined
    ? String(step.primaryKeysText || '').split(',').map(value => value.trim()).filter(Boolean)
    : Array.isArray(semantic.primaryKeys) ? semantic.primaryKeys : [];
  const unitDescriptionEntries = Array.isArray(step.unitDescriptionEntries)
    ? step.unitDescriptionEntries
    : Object.entries(semantic.unitDescriptions || {}).map(([field, unit]) => ({ field, unit }));
  const unitDescriptions = unitDescriptionEntries.reduce((result, entry) => {
    const field = String(entry?.field || '').trim();
    if (field) result[field] = String(entry?.unit || '').trim();
    return result;
  }, {});
  return {
    sqlCode: step.sqlCode || `SQL_${index + 1}`,
    sqlName: step.sqlName || `SQL ${index + 1}`,
    sqlDescription: step.sqlDescription || step.description || '',
    sqlContent: step.sqlContent || step.sql || '',
    executionOrder: Number(step.executionOrder || index + 1),
    workflowEnabled: step.workflowEnabled === true,
    dependencies: Array.isArray(step.dependencies) ? [...new Set(step.dependencies.filter(Boolean))] : [],
    enabled: step.enabled !== false,
    timeoutSeconds: Number(step.timeoutSeconds || 30),
    failureStrategy: String(step.failureStrategy || 'STOP').toUpperCase() === 'CONTINUE' ? 'CONTINUE' : 'STOP',
    emptyResultStrategy: ['CONTINUE', 'SKIP_DEPENDENTS', 'STOP'].includes(String(step.emptyResultStrategy || '').toUpperCase())
      ? String(step.emptyResultStrategy).toUpperCase() : 'CONTINUE',
    maxResultRows: Number(step.maxResultRows || step.maxRows || 50),
    parameters: {},
    staticParameterEntries: [],
    parameterMappings,
    resultSemantic: {
      resultSetName: semantic.resultSetName || String(step.sqlCode || `sql_${index + 1}`).toLowerCase(),
      businessEntity: semantic.businessEntity || '',
      primaryKeys,
      timeField: semantic.timeField || '',
      dataGranularity: semantic.dataGranularity || '',
      unitDescriptions,
      emptyMeaning: semantic.emptyMeaning || '',
      modelUsage: semantic.modelUsage || ''
    },
    primaryKeysText: primaryKeys.join(', '),
    unitDescriptionEntries,
    returnToModel: step.returnToModel !== false
  };
}

function databaseStaticParameterRow(parameter = {}) {
  const inferredType = databaseStaticParameterType(parameter.value);
  const type = ['string', 'integer', 'number', 'boolean', 'date', 'dynamic_date'].includes(parameter.type)
    ? parameter.type
    : inferredType;
  return {
    name: parameter.name || '',
    type,
    value: coerceDatabaseStaticParameterValue(parameter.value, type)
  };
}

function databaseStaticParameterType(value) {
  if (typeof value === 'boolean') return 'boolean';
  if (typeof value === 'number') return Number.isInteger(value) ? 'integer' : 'number';
  if (typeof value === 'string' && /^\$\{(?:today|natural_date|month|month_start|month_end|trade_date(?:[+-]\d+)?)\}$/.test(value)) return 'dynamic_date';
  if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)) return 'date';
  return 'string';
}

function coerceDatabaseStaticParameterValue(value, type) {
  if (type === 'boolean') {
    if (typeof value === 'boolean') return value;
    return String(value).toLowerCase() === 'true';
  }
  if (type === 'integer') {
    const number = Number(value);
    return Number.isFinite(number) ? Math.trunc(number) : 0;
  }
  if (type === 'number') {
    const number = Number(value);
    return Number.isFinite(number) ? number : 0;
  }
  return value == null ? '' : String(value);
}

function databaseStaticParametersToObject(entries) {
  return (entries || []).reduce((result, entry) => {
    const name = String(entry?.name || '').trim();
    if (!name) return result;
    result[name] = coerceDatabaseStaticParameterValue(entry.value, entry.type || 'string');
    return result;
  }, {});
}

function resequenceDatabaseSqlSteps(steps) {
  return (steps || []).map((step, index) => ({
    ...step,
    executionOrder: index + 1,
    sqlCode: step.sqlCode || `SQL_${index + 1}`,
    sqlName: step.sqlName || `SQL ${index + 1}`
  }));
}

function databaseSqlWorkflowLevels(steps) {
  const enabled = (steps || []).filter(step => step?.enabled !== false && step?.sqlCode);
  const byCode = new Map(enabled.map(step => [step.sqlCode, step]));
  const remaining = new Set(byCode.keys());
  const completed = new Set();
  const levels = [];
  while (remaining.size) {
    const ready = enabled.filter(step => remaining.has(step.sqlCode)
      && (step.dependencies || []).every(code => completed.has(code) || !byCode.has(code)));
    if (!ready.length) {
      levels.push(enabled.filter(step => remaining.has(step.sqlCode)));
      break;
    }
    levels.push(ready);
    ready.forEach(step => {
      remaining.delete(step.sqlCode);
      completed.add(step.sqlCode);
    });
  }
  return levels;
}

function validateDatabaseSqlWorkflow(steps) {
  const enabled = (steps || []).filter(step => step?.enabled !== false);
  const codes = new Set(enabled.map(step => String(step.sqlCode || '').trim()));
  for (const step of enabled) {
    for (const dependency of step.dependencies || []) {
      if (dependency === step.sqlCode) return `节点 ${step.sqlCode} 不能依赖自身`;
      if (!codes.has(dependency)) return `节点 ${step.sqlCode} 依赖的节点 ${dependency} 不存在或未启用`;
    }
    for (const mapping of step.parameterMappings || []) {
      if (mapping.sourceType === 'UPSTREAM_RESULT' && !(step.dependencies || []).includes(mapping.sourceNode)) {
        return `节点 ${step.sqlCode} 的上游参数来源 ${mapping.sourceNode} 必须同时配置为前置依赖`;
      }
    }
  }
  const levels = databaseSqlWorkflowLevels(enabled);
  const planned = levels.reduce((count, level) => count + level.length, 0);
  if (planned !== enabled.length || (levels.at(-1) || []).some(step =>
    (step.dependencies || []).some(code => !levels.slice(0, -1).flat().some(item => item.sqlCode === code)))) {
    return 'SQL 执行流程存在循环依赖，请调整前置依赖';
  }
  return '';
}

const DATABASE_DYNAMIC_PARAMS = new Set([
  'today',
  'natural_date',
  'month',
  'month_start',
  'month_end',
  'trade_date'
]);

const DATABASE_DIRECT_DYNAMIC_TOKEN = /^(?:today|natural_date|month|month_start|month_end|trade_date[+-]?\d*)$/;

function extractDatabaseQueryParameters(sql) {
  const params = new Map();
  const text = String(sql || '');
  const namedPattern = /(^|[^:]):([A-Za-z_][A-Za-z0-9_]*)\b/g;
  let match;
  while ((match = namedPattern.exec(text)) !== null) {
    const name = match[2];
    params.set(name, databaseQueryParamDescriptor(name, DATABASE_DYNAMIC_PARAMS.has(name), name));
  }

  const dynamicPattern = /\$\{\s*([A-Za-z_][A-Za-z0-9_+-]*)\s*}/g;
  while ((match = dynamicPattern.exec(text)) !== null) {
    const token = match[1];
    if (!DATABASE_DIRECT_DYNAMIC_TOKEN.test(token)) continue;
    const name = token.startsWith('trade_date') ? 'trade_date' : token;
    if (!params.has(name)) {
      params.set(name, databaseQueryParamDescriptor(name, true, token));
    }
  }

  const mustachePattern = /\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*}}/g;
  while ((match = mustachePattern.exec(text)) !== null) {
    const name = match[1];
    if (!params.has(name)) {
      params.set(name, databaseQueryParamDescriptor(name, DATABASE_DYNAMIC_PARAMS.has(name), name));
    }
  }
  return [...params.values()];
}

function databaseSqlStepParameterSummary(step) {
  const summary = {
    code: step.sqlCode,
    name: step.sqlName || step.sqlCode,
    userInput: [],
    upstream: [],
    fixed: [],
    dynamic: [],
    unconfigured: [],
    total: 0
  };
  const staticNames = new Set((step.staticParameterEntries || [])
    .map(entry => String(entry?.name || '').trim())
    .filter(Boolean));
  const mappings = new Map((step.parameterMappings || [])
    .filter(mapping => mapping?.parameter)
    .map(mapping => [String(mapping.parameter).trim(), mapping]));
  extractDatabaseQueryParameters(step.sqlContent).forEach(param => {
    summary.total += 1;
    if (param.dynamic) {
      summary.dynamic.push(param.name);
      return;
    }
    if (staticNames.has(param.name)) {
      summary.fixed.push(param.name);
      return;
    }
    const mapping = mappings.get(param.name);
    if (!mapping) {
      summary.unconfigured.push(param.name);
      return;
    }
    const sourceType = String(mapping.sourceType || 'USER_INPUT').toUpperCase();
    if (sourceType === 'UPSTREAM_RESULT') summary.upstream.push(param.name);
    else if (sourceType === 'STATIC') summary.fixed.push(param.name);
    else if (sourceType === 'SYSTEM_CONTEXT') summary.dynamic.push(param.name);
    else summary.userInput.push(param.name);
  });
  return summary;
}

function databaseQueryParamDescriptor(name, dynamic, token = name) {
  return {
    name,
    dynamic,
    defaultSource: dynamic ? token : 'user_input',
    required: !dynamic,
    example: databaseQueryParamExample(name)
  };
}

function databaseMappingSourceLabel(sourceType) {
  return ({
    SYSTEM_CONTEXT: '系统变量',
    UPSTREAM_RESULT: '上游步骤结果',
    STATIC: '固定值'
  })[String(sourceType || '').toUpperCase()] || '其他来源';
}

function databaseQueryParamExample(name) {
  if (name === 'month') return '202607';
  if (['today', 'natural_date', 'month_start', 'month_end', 'trade_date'].includes(name)) return '20260707';
  if (name.toLowerCase().includes('branch')) return '0001';
  if (name.toLowerCase().includes('customer')) return 'CUST001';
  return '';
}

function coerceParamValue(text, type) {
  if (type === 'number' || type === 'integer') {
    const number = Number(text);
    if (!Number.isNaN(number)) {
      return type === 'integer' ? Math.trunc(number) : number;
    }
  }
  if (type === 'boolean') {
    return text === true || text === 'true' || text === '1' || text === 'yes' || text === '是';
  }
  if (type === 'object' || type === 'array') {
    try {
      return JSON.parse(text);
    } catch (error) {
      return text;
    }
  }
  return text;
}

function formatParamValue(value) {
  if (value == null) return '';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function normalizeParameterName(field) {
  const value = String(field || '').trim();
  if (/^[A-Za-z_][A-Za-z0-9_]*$/.test(value)) return value;
  const normalized = value.replace(/[^A-Za-z0-9_]/g, '_').replace(/_+/g, '_').replace(/^_+|_+$/g, '');
  if (!normalized) return 'param';
  return /^[A-Za-z_]/.test(normalized) ? normalized : `p_${normalized}`;
}

function uniqueName(baseName, reservedNames) {
  let name = baseName;
  let index = 2;
  while (reservedNames.includes(name)) {
    name = `${baseName}_${index}`;
    index += 1;
  }
  return name;
}

function inferJsonSchemaType(value) {
  if (typeof value === 'boolean') return 'boolean';
  if (typeof value === 'number') return Number.isInteger(value) ? 'integer' : 'number';
  if (Array.isArray(value)) return 'array';
  if (value && typeof value === 'object') return 'object';
  return 'string';
}

function objectSchema() {
  return {
    type: 'object',
    properties: {},
    required: [],
    additionalProperties: false
  };
}
