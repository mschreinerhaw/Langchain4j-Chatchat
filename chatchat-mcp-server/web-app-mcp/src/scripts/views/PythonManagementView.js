import { businessCategoriesApi, pythonApi } from '../../services/api';
import '../../styles/views/python-management.css';

const emptyForm = () => ({
  id: '', name: '', description: '', dockerImage: 'chatchat-python-runtime:3.11-v1', pythonVersion: '3.11',
  cpuLimit: '2', memoryLimit: '4g', diskLimit: '2g', tmpfsLimit: '512m', runtimeUser: '10001:10001',
  networkPolicy: 'NONE', networkName: '', timeoutSeconds: 300,
  requirementsText: 'numpy==2.1.0\npandas==2.2.2\nscipy==1.14.0\nscikit-learn==1.5.1\npyarrow==17.0.0\nopenpyxl==3.1.5\nrequests==2.32.3\npydantic==2.8.2\nmatplotlib==3.9.1'
});

export default {
  name: 'PythonManagementView', emits: ['notify', 'error', 'result'],
  data: () => ({ busy: false, searchBusy: false, tab: 'environments', environments: [], templates: [], categories: [], query: '', categoryId: '', hits: [], searched: false, indexOverview: {}, dialogOpen: false, metadataOpen: false, metadataForm: {}, resultOpen: false, resultText: '', form: emptyForm() }),
  mounted() { this.load(); },
  methods: {
    async load() {
      this.busy = true;
      try { [this.environments, this.templates, this.categories, this.indexOverview] = await Promise.all([pythonApi.environments(), pythonApi.templates(), businessCategoriesApi.list(), pythonApi.indexOverview()]); }
      catch (error) { this.$emit('error', error); }
      finally { this.busy = false; }
    },
    async searchTemplates() {
      if (!this.query.trim() && !this.categoryId) { this.clearSearch(); return; }
      this.searchBusy = true;
      try { this.hits = await pythonApi.searchTemplates(this.query.trim(), this.categoryId, 50); this.searched = true; }
      catch (error) { this.$emit('error', error); }
      finally { this.searchBusy = false; }
    },
    clearSearch() { this.query = ''; this.categoryId = ''; this.hits = []; this.searched = false; },
    categoryLabel(id) { const item = this.categories.find(category => category.id === id); return item ? `${item.name} / ${item.code}` : (id || '-'); },
    openMetadata(row) { this.metadataForm = { id: row.id, templateName: row.templateName, scenario: row.scenario, description: row.description, categoryId: row.categoryId || '', keywords: row.keywords || '', domain: row.domain || '', inputSchemaJson: row.inputSchemaJson || '{}', outputSchemaJson: row.outputSchemaJson || '{}' }; this.metadataOpen = true; },
    async saveMetadata() { await this.perform(async () => { const { id, ...payload } = this.metadataForm; await pythonApi.updateTemplateMetadata(id, payload); this.metadataOpen = false; await this.load(); }, '模板分类与检索属性已更新'); },
    async rebuildIndex() { await this.perform(async () => { this.indexOverview = await pythonApi.rebuildIndex(); this.hits = []; this.searched = false; }, 'Python 模板 BM25 + KNN 索引已重建'); },
    openEnvironment(row) { this.form = row ? { ...row, requirementsText: this.requirementsText(row.requirementsJson) } : emptyForm(); this.dialogOpen = true; },
    requirementsText(value) { try { return JSON.parse(value || '[]').join('\n'); } catch { return ''; } },
    async saveEnvironment() {
      if (!this.form.name || !this.form.dockerImage) { this.$emit('error', new Error('环境名称和 Docker 镜像不能为空')); return; }
      const payload = { ...this.form, requirements: String(this.form.requirementsText || '').split(/\r?\n/).map(value => value.trim()).filter(Boolean) };
      delete payload.requirementsText;
      await this.perform(async () => { await pythonApi.saveEnvironment(payload); this.dialogOpen = false; await this.load(); }, '环境配置已保存，发布后 API 用户才可选择');
    },
    async toggleEnvironment(row) { await this.perform(async () => { await pythonApi.publishEnvironment(row.id, row.status !== 'PUBLISHED'); await this.load(); }, row.status === 'PUBLISHED' ? '环境已取消发布，现在可以编辑' : '环境已发布'); },
    async toggleTemplate(row) { await this.perform(async () => { await pythonApi.setTemplateEnabled(row.id, row.status !== 'PUBLISHED'); await this.load(); this.clearSearch(); }, row.status === 'PUBLISHED' ? '模板已停用' : '模板已启用'); },
    async testTemplate(row) { await this.perform(async () => { const result = await pythonApi.executeTemplate(row.id, {}); this.resultText = JSON.stringify(result, null, 2); this.resultOpen = true; }, '模板试运行完成'); },
    async perform(fn, message) { this.busy = true; try { await fn(); this.$emit('notify', { title: '操作成功', message, type: 'success' }); } catch (error) { this.$emit('error', error); } finally { this.busy = false; } }
  }
};
