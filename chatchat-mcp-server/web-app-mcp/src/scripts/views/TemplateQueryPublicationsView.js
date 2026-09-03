import { templateQueryPublicationsApi as api } from '../../services/api';
import '../../styles/views/template-query-publications.css';

export const TYPE_LABELS = {
  ssh_host: 'SSH 命令模板',
  sql_datasource: '数据库运维 SQL 模板',
  http_endpoint: 'HTTP 请求模板',
  database_query: '业务数据库查询模板',
  api_service: 'API 服务模板',
  python_runtime: 'Python 分析模板'
};

export default {
  name: 'TemplateQueryPublicationsView',
  emits: ['notify', 'error'],
  data() {
    return {
      typeLabels: TYPE_LABELS,
      loading: false,
      saving: false,
      bindings: [],
      templates: [],
      members: [],
      templateLoading: false,
      parents: [],
      roles: [],
      publicationKeyword: '',
      publicationCategoryFilter: '',
      publicationPage: 1,
      publicationPageSize: 10,
      keyword: '',
      typeFilter: '',
      businessCategoryFilter: '',
      templatePage: 1,
      templatePageSize: 10,
      dialogOpen: false,
      form: this.emptyForm()
    };
  },
  computed: {
    publicationCategoryOptions() {
      const categories = new Map();
      this.parents.forEach(parent => {
        if (parent.assetType && !categories.has(parent.assetType)) {
          categories.set(parent.assetType, {
            value: parent.assetType,
            label: TYPE_LABELS[parent.assetType] || parent.title || parent.assetType
          });
        }
      });
      return [...categories.values()]
        .sort((left, right) => left.label.localeCompare(right.label, 'zh-CN'));
    },
    filteredBindings() {
      const keyword = this.publicationKeyword.trim().toLowerCase();
      return this.bindings.filter(binding => {
        const categoryMatched = !this.publicationCategoryFilter
          || binding.parentAssetType === this.publicationCategoryFilter;
        const text = [binding.toolName, binding.domainCode, binding.parentToolTitle,
          binding.parentToolName, binding.roleName, binding.roleCode, binding.username,
          binding.tenantName]
          .filter(Boolean).join(' ').toLowerCase();
        return categoryMatched && (!keyword || text.includes(keyword));
      });
    },
    paginatedBindings() {
      const start = (this.publicationPage - 1) * this.publicationPageSize;
      return this.filteredBindings.slice(start, start + this.publicationPageSize);
    },
    filteredTemplates() {
      const keyword = this.keyword.trim().toLowerCase();
      return this.templates.filter(item => {
        const typeMatched = !this.typeFilter || item.assetType === this.typeFilter;
        const businessCategory = (item.businessCategoryCode || '').trim();
        const categoryMatched = !this.businessCategoryFilter
          || (this.businessCategoryFilter === '__uncategorized__'
            ? !businessCategory : businessCategory === this.businessCategoryFilter);
        const text = [item.templateId, item.title, item.description, item.category,
          item.businessCategoryCode, item.businessCategoryName]
          .filter(Boolean).join(' ').toLowerCase();
        return typeMatched && categoryMatched && (!keyword || text.includes(keyword));
      });
    },
    businessCategoryOptions() {
      const source = this.typeFilter
        ? this.templates.filter(item => item.assetType === this.typeFilter)
        : this.templates;
      const categories = new Map();
      source.forEach(item => {
        const code = (item.businessCategoryCode || '').trim();
        if (code && !categories.has(code)) {
          const name = (item.businessCategoryName || '').trim();
          categories.set(code, { value: code, label: name && name !== code ? `${name}（${code}）` : code });
        }
      });
      const options = [...categories.values()]
        .sort((left, right) => left.label.localeCompare(right.label, 'zh-CN'));
      if (source.some(item => !(item.businessCategoryCode || '').trim())) {
        options.push({ value: '__uncategorized__', label: '未配置业务分类' });
      }
      return options;
    },
    paginatedTemplates() {
      const start = (this.templatePage - 1) * this.templatePageSize;
      return this.filteredTemplates.slice(start, start + this.templatePageSize);
    },
    groupedTemplates() {
      return Object.entries(TYPE_LABELS).map(([assetType, label]) => ({
        assetType,
        label,
        items: this.paginatedTemplates.filter(item => item.assetType === assetType)
      })).filter(group => group.items.length);
    },
    selectedTypes() {
      const selected = new Set(this.form.templateKeys);
      return Object.entries(TYPE_LABELS)
        .filter(([assetType]) => this.templates.some(item => item.assetType === assetType && selected.has(item.key)))
        .map(([, label]) => label);
    },
    selectedParent() {
      return this.parents.find(item => item.toolName === this.form.parentToolName) || null;
    }
  },
  mounted() {
    this.load();
  },
  methods: {
    emptyForm() {
      return {
        id: '', parentToolName: '', roleId: '', subjectType: 'ROLE', userId: '',
        domainCode: '', templateKeys: [], enabled: true, expectedRevision: null
      };
    },
    async load() {
      this.loading = true;
      try {
        const [bindings, parents, roles] = await Promise.all([
          api.list(), api.parents(), api.roles()
        ]);
        this.bindings = Array.isArray(bindings) ? bindings : [];
        this.parents = Array.isArray(parents) ? parents : [];
        this.roles = Array.isArray(roles) ? roles : [];
        const lastPage = Math.max(1, Math.ceil(this.filteredBindings.length / this.publicationPageSize));
        this.publicationPage = Math.min(this.publicationPage, lastPage);
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.loading = false;
      }
    },
    openCreate() {
      this.form = this.emptyForm();
      this.templates = [];
      this.members = [];
      this.keyword = '';
      this.typeFilter = '';
      this.businessCategoryFilter = '';
      this.templatePage = 1;
      this.dialogOpen = true;
    },
    async openEdit(binding) {
      this.form = {
        id: binding.id,
        parentToolName: binding.parentToolName,
        roleId: binding.roleId,
        subjectType: binding.subjectType || 'ROLE',
        userId: binding.userId || '',
        domainCode: binding.domainCode || '',
        templateKeys: [...(binding.templateKeys || [])],
        enabled: binding.enabled !== false,
        expectedRevision: binding.revision
      };
      this.keyword = '';
      this.typeFilter = binding.parentAssetType || '';
      this.businessCategoryFilter = '';
      this.templatePage = 1;
      this.dialogOpen = true;
      await this.loadRoleContext(false);
    },
    async onParentChange() {
      this.form.templateKeys = [];
      this.typeFilter = this.selectedParent?.assetType || '';
      await this.loadRoleContext(true);
    },
    async onRoleChange() {
      this.form.templateKeys = [];
      this.form.userId = '';
      await this.loadRoleContext(true);
    },
    async loadRoleContext(clearSelection) {
      if (!this.form.roleId || !this.form.parentToolName) {
        this.templates = [];
        this.members = [];
        return;
      }
      this.templateLoading = true;
      try {
        const [templates, members] = await Promise.all([
          api.templates(this.form.roleId, this.form.parentToolName), api.members(this.form.roleId)
        ]);
        this.templates = Array.isArray(templates) ? templates : [];
        this.members = Array.isArray(members) ? members : [];
        if (clearSelection) this.form.templateKeys = [];
        const allowed = new Set(this.templates.map(item => item.key));
        this.form.templateKeys = this.form.templateKeys.filter(key => allowed.has(key));
      } catch (error) {
        this.templates = [];
        this.members = [];
        this.$emit('error', error);
      } finally {
        this.templateLoading = false;
      }
    },
    onSubjectTypeChange() {
      if (this.form.subjectType !== 'USER') this.form.userId = '';
    },
    async save() {
      if (!/^[a-z][a-z0-9_]{0,63}$/.test(this.form.domainCode)
        || this.form.domainCode.endsWith('_template_query')) {
        this.$emit('error', new Error('领域编码必须以小写字母开头，只能包含小写字母、数字和下划线；无需填写固定后缀'));
        return;
      }
      if (!this.form.parentToolName || !this.form.roleId) {
        this.$emit('error', new Error('请选择父级模板检索工具和角色'));
        return;
      }
      if (this.form.subjectType === 'USER' && !this.form.userId) {
        this.$emit('error', new Error('请选择角色组成员'));
        return;
      }
      if (!this.form.templateKeys.length) {
        this.$emit('error', new Error('请至少勾选一个模板'));
        return;
      }
      this.saving = true;
      try {
        await api.save(this.form);
        this.dialogOpen = false;
        this.$emit('notify', { title: '模板查询范围已保存' });
        await this.load();
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.saving = false;
      }
    },
    async toggle(binding) {
      try {
        await api.setEnabled(binding.id, binding.enabled === false);
        await this.load();
      } catch (error) {
        this.$emit('error', error);
      }
    },
    async remove(binding) {
      try {
        await this.$confirm(`确认删除 ${binding.serviceName} / ${binding.roleName} 的模板范围？`, '删除绑定', {
          type: 'warning'
        });
        await api.remove(binding.id);
        this.$emit('notify', { title: '绑定已删除' });
        await this.load();
      } catch (error) {
        if (error !== 'cancel' && error !== 'close') this.$emit('error', error);
      }
    },
    selectVisible() {
      this.form.templateKeys = [...new Set([
        ...this.form.templateKeys,
        ...this.filteredTemplates.map(item => item.key)
      ])];
    },
    clearVisible() {
      const visible = new Set(this.filteredTemplates.map(item => item.key));
      this.form.templateKeys = this.form.templateKeys.filter(key => !visible.has(key));
    },
    bindingTypeLabels(binding) {
      const keys = binding.templateKeys || [];
      return Object.entries(TYPE_LABELS)
        .filter(([type]) => keys.some(key => key.startsWith(`${type}:`)))
        .map(([, label]) => label);
    }
  },
  watch: {
    publicationKeyword() {
      this.publicationPage = 1;
    },
    publicationCategoryFilter() {
      this.publicationPage = 1;
    },
    keyword() {
      this.templatePage = 1;
    },
    typeFilter() {
      this.businessCategoryFilter = '';
      this.templatePage = 1;
    },
    businessCategoryFilter() {
      this.templatePage = 1;
    }
  }
};
