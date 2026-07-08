import { ElMessageBox } from 'element-plus';
import ModalPanel from '../../components/ModalPanel.vue';
import { parseJsonObject, prettyJson } from '../../utils/json';
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
    extraActions: { type: Array, default: () => [] },
    defaults: { type: Object, default: () => ({}) },
    searchableFields: { type: Array, default: () => [] },
    pageSize: { type: Number, default: 10 },
    emptyText: { type: String, default: '暂无数据。' },
    searchPlaceholder: { type: String, default: '搜索' },
    formSubtitle: { type: String, default: '' }
  },
  emits: ['notify', 'error', 'result', 'loaded'],
  data() {
    return {
      busy: false,
      items: [],
      keyword: '',
      page: 1,
      selectedIds: new Set(),
      formOpen: false,
      form: {},
      jsonDraft: {},
      listDraft: {},
      listInput: {},
      objectDraft: {},
      schemaDraft: {},
      templatePickerOpen: false,
      templatePickerField: null,
      templatePickerKeyword: '',
      templatePickerPage: 1,
      templatePickerPageSize: 8,
      templatePickerSelected: [],
      collapsedFormSections: [],
      metadataScopeOptions: {},
      metadataScopeKeyword: '',
      metadataScopeOpenKey: '',
      formTestResult: null,
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
      if (!keyword) return this.items;
      const fields = this.searchableFields.length ? this.searchableFields : this.columns.map(column => column.key);
      return this.items.filter(item => fields.some(field => String(item[field] ?? '').toLowerCase().includes(keyword)));
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
    templatePickerTitle() {
      return this.templatePickerField?.label || '选择模板';
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
      return this.formTestResult?.data || {};
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
    pageCount(value) {
      if (this.page > value) this.page = value;
    },
    templatePickerKeyword() {
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
      this.formFields.filter(field => field.type === 'jsonObjectString' || field.type === 'jsonObject').forEach(field => {
        this.objectDraft[field.key] = objectToRows(parseObjectValue(this.form[field.key], field.defaultValue ?? {}));
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
      this.formFields.filter(field => field.unitScale).forEach(field => {
        const value = Number(payload[field.key]);
        payload[field.key] = Number.isFinite(value) ? Math.round(value * field.unitScale) : payload[field.key];
      });
      return payload;
    },
    async testFormDraft() {
      if (!this.formTestAction) return;
      this.busy = true;
      try {
        const result = await this.formTestAction(this.formPayload());
        this.formTestResult = result;
        this.updateMetadataScopeOptions(result);
        this.$emit('notify', {
          title: result?.success === false ? '测试失败' : '测试成功',
          message: result?.success === false ? (result?.errorMessage || '测试失败。') : '测试完成。'
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
      try {
        const { value: raw } = await ElMessageBox.prompt('请输入测试参数 JSON', '测试调用', {
          inputType: 'textarea',
          inputValue: '{}',
          confirmButtonText: '执行',
          cancelButtonText: '取消'
        });
        const result = await this.testAction(item, parseJsonObject(raw, {}));
        this.$emit('result', {
          title: `${item.title || item.toolName || item.name || '资源'} 测试结果`,
          value: result
        });
      } catch (error) {
        if (error !== 'cancel' && error !== 'close') {
          this.$emit('error', error);
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
      return typeof action.disabled === 'function' ? action.disabled(row) : !!action.disabled;
    },
    async runExtraAction(action, row) {
      if (!action || typeof action.run !== 'function') return;
      await this.run(() => action.run(row), action.successMessage || '操作成功');
    },
    async runRebuild() {
      await this.run(() => this.rebuildAction(), '索引已重建');
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
    fieldOptions(field) {
      return typeof field.options === 'function' ? field.options() : field.options || [];
    },
    isFieldVisible(field) {
      if (typeof field.visible === 'function') return field.visible(this.form);
      if (typeof field.hidden === 'function') return !field.hidden(this.form);
      return field.visible !== false && field.hidden !== true;
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
    clearTemplatePickerVisible() {
      const visible = new Set(this.templatePickerVisibleItems.map(item => this.templatePickerItemKey(item)).filter(Boolean));
      this.templatePickerSelected = this.templatePickerSelected.filter(key => !visible.has(key));
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
    syncDatabaseParamsFromSql(field, notifyUser = false) {
      const params = extractDatabaseQueryParameters(this.form[field.sqlKey] || '');
      const rows = [...(this.schemaDraft[field.key] || [])];
      let added = 0;
      params.forEach(param => {
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
          return;
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
        added += 1;
      });
      this.schemaDraft[field.key] = rows;
      if (notifyUser) {
        this.$emit('notify', {
          title: '参数已同步',
          message: added > 0 ? `新增 ${added} 个参数定义。` : '参数定义已是最新。'
        });
      }
    },
    databaseParamSummary(field) {
      const params = extractDatabaseQueryParameters(this.form[field.sqlKey] || '');
      if (!params.length) return '';
      return `参数：${params.map(param => param.dynamic ? `${param.name}:自动` : param.name).join(', ')}`;
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

function databaseQueryParamDescriptor(name, dynamic, token = name) {
  return {
    name,
    dynamic,
    defaultSource: dynamic ? token : 'user_input',
    required: !dynamic,
    example: databaseQueryParamExample(name)
  };
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
