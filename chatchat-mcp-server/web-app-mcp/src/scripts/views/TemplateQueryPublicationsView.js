import { templateQueryPublicationsApi as api } from '../../services/api';
import '../../styles/views/template-query-publications.css';

export const TYPE_LABELS = {
  ssh_host: 'SSH 命令模板',
  sql_datasource: '数据库运维 SQL 模板',
  http_endpoint: 'HTTP 请求模板',
  database_query: '业务数据库查询模板',
  api_service: 'API 服务模板'
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
      services: [],
      roles: [],
      keyword: '',
      typeFilter: '',
      dialogOpen: false,
      form: this.emptyForm()
    };
  },
  computed: {
    filteredTemplates() {
      const keyword = this.keyword.trim().toLowerCase();
      return this.templates.filter(item => {
        const typeMatched = !this.typeFilter || item.assetType === this.typeFilter;
        const text = [item.templateId, item.title, item.description, item.category]
          .filter(Boolean).join(' ').toLowerCase();
        return typeMatched && (!keyword || text.includes(keyword));
      });
    },
    groupedTemplates() {
      return Object.entries(TYPE_LABELS).map(([assetType, label]) => ({
        assetType,
        label,
        items: this.filteredTemplates.filter(item => item.assetType === assetType)
      })).filter(group => group.items.length);
    },
    selectedTypes() {
      const selected = new Set(this.form.templateKeys);
      return Object.entries(TYPE_LABELS)
        .filter(([assetType]) => this.templates.some(item => item.assetType === assetType && selected.has(item.key)))
        .map(([, label]) => label);
    }
  },
  mounted() {
    this.load();
  },
  methods: {
    emptyForm() {
      return { id: '', serviceId: '', roleId: '', templateKeys: [], enabled: true };
    },
    async load() {
      this.loading = true;
      try {
        const [bindings, templates, services, roles] = await Promise.all([
          api.list(), api.templates(), api.services(), api.roles()
        ]);
        this.bindings = Array.isArray(bindings) ? bindings : [];
        this.templates = Array.isArray(templates) ? templates : [];
        this.services = Array.isArray(services) ? services : [];
        this.roles = Array.isArray(roles) ? roles : [];
      } catch (error) {
        this.$emit('error', error);
      } finally {
        this.loading = false;
      }
    },
    openCreate() {
      this.form = this.emptyForm();
      this.keyword = '';
      this.typeFilter = '';
      this.dialogOpen = true;
    },
    openEdit(binding) {
      this.form = {
        id: binding.id,
        serviceId: binding.serviceId,
        roleId: binding.roleId,
        templateKeys: [...(binding.templateKeys || [])],
        enabled: binding.enabled !== false
      };
      this.keyword = '';
      this.typeFilter = '';
      this.dialogOpen = true;
    },
    async save() {
      if (!this.form.serviceId || !this.form.roleId) {
        this.$emit('error', new Error('请选择 MCP 服务和角色'));
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
  }
};
