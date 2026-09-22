<template>
  <section class="feature-view model-management-view">
    <header class="feature-page-header">
      <div class="feature-page-heading">
        <span class="feature-breadcrumb">平台管理 / 模型管理</span>
        <h1>模型管理</h1>
        <p>统一管理大语言模型和向量模型。数据库中的最近配置优先于配置文件。</p>
      </div>
      <div class="feature-page-actions">
        <button type="button" class="feature-button primary" @click="openCreate"><Plus :size="16" />新增模型</button>
      </div>
    </header>

    <p v-if="message" role="status" class="feature-alert" :class="error ? 'error' : 'success'">{{ message }}</p>
    <div class="feature-summary-panel model-summary">
      <div class="feature-summary-primary">
        <span class="feature-summary-icon"><Cpu :size="19" /></span>
        <div><strong>模型配置</strong><small>集中查看连接信息与运行状态</small></div>
      </div>
      <div class="model-summary-stats">
        <span><strong>{{ models.length }}</strong> 个模型</span>
        <span><strong>{{ models.filter(model => model.enabled).length }}</strong> 个已启用</span>
      </div>
    </div>

    <div v-if="models.length" class="feature-filter-bar model-filter-bar">
      <label class="feature-search-field">
        <Search class="feature-search-icon" :size="17" aria-hidden="true" />
        <input v-model.trim="searchQuery" type="search" aria-label="检索模型" placeholder="检索别名、名称、描述、模型 ID 或接口地址" />
      </label>
      <label class="feature-select-field">
        <span>模型类型</span>
        <select v-model="typeFilter">
          <option value="">全部类型</option>
          <option value="chat">大语言模型</option>
          <option value="embedding">向量模型</option>
        </select>
      </label>
      <span class="feature-result-count">找到 <strong>{{ filteredModels.length }}</strong> 个模型</span>
    </div>

    <div v-if="loading" class="feature-empty-state model-state" role="status">
      <span class="feature-empty-icon loading" aria-hidden="true"></span>
      <strong>正在加载模型</strong><p>请稍候…</p>
    </div>
    <div v-else-if="!models.length" class="feature-empty-state model-state">
      <span class="feature-empty-icon"><Cpu :size="18" /></span>
      <strong>暂无模型配置</strong>
      <p>添加模型后，即可在这里管理连接和默认模型。</p>
      <button type="button" class="feature-button primary" @click="openCreate"><Plus :size="16" />新增模型</button>
    </div>
    <div v-else-if="!filteredModels.length" class="feature-empty-state model-state">
      <span class="feature-empty-icon"><Search :size="18" /></span>
      <strong>没有找到匹配的模型</strong>
      <p>试试其他关键字或模型类型。</p>
      <button type="button" class="feature-button" @click="clearFilters">清除筛选</button>
    </div>
    <div v-else class="feature-card-grid model-grid">
      <article v-for="model in pagedModels" :key="`${model.type}:${model.name}`" class="feature-content-card model-card">
        <div class="model-card-head">
          <span class="model-type-icon"><Database v-if="model.type === 'embedding'" :size="20" /><MessageSquare v-else :size="20" /></span>
          <div class="model-card-title"><strong :title="model.alias || model.name">{{ model.alias || model.name }}</strong><small :title="model.name">{{ model.name }} · {{ model.type === 'embedding' ? '向量模型' : '大语言模型' }}</small></div>
          <span class="feature-status" :class="model.enabled ? 'published' : 'recalled'">{{ model.enabled ? '已启用' : '已停用' }}</span>
        </div>
        <div class="model-badges">
          <span v-if="model.defaultModel" class="feature-status builtin"><Star :size="12" />默认模型</span>
          <span class="feature-status" :class="model.hasApiKey ? 'published' : 'warning'"><KeyRound :size="12" />{{ model.hasApiKey ? '密钥已配置' : '密钥未配置' }}</span>
        </div>
        <p class="model-description">{{ model.description || '暂无能力与适用范围描述' }}</p>
        <dl class="model-details">
          <div><dt>服务端模型</dt><dd :title="model.providerModel || model.name">{{ model.providerModel || model.name }}</dd></div>
          <div><dt>接口地址</dt><dd :title="model.baseUrl">{{ model.baseUrl || '未设置' }}</dd></div>
        </dl>
        <div class="feature-card-actions model-actions">
          <button type="button" class="feature-button" @click="openEdit(model)"><Pencil :size="14" />编辑</button>
          <button type="button" class="feature-button" :disabled="!model.enabled || model.defaultModel" @click="makeDefault(model)"><Star :size="14" />设为默认</button>
          <button type="button" class="feature-button danger" @click="remove(model)"><Trash2 :size="14" />删除</button>
        </div>
      </article>
    </div>
    <AppPagination
      v-if="!loading && filteredModels.length"
      :page="page"
      :page-size="pageSize"
      :total="filteredModels.length"
      aria-label="模型分页"
      @change="page = $event"
    />

    <div v-if="editing" class="feature-modal-backdrop" @click.self="editing = false">
      <form class="feature-modal model-dialog" aria-labelledby="model-dialog-title" @submit.prevent="save">
        <div class="feature-modal-header">
          <div><h2 id="model-dialog-title">{{ isNew ? '新增模型' : '编辑模型' }}</h2><p>配置模型连接与运行参数</p></div>
          <button type="button" class="feature-icon-button" aria-label="关闭" @click="editing = false"><X :size="20" /></button>
        </div>
        <div class="model-form-section">
          <h3>基础信息</h3>
          <div class="model-form-grid">
            <label class="feature-form-field"><span>类型</span><select v-model="form.type" :disabled="!isNew"><option value="chat">大语言模型</option><option value="embedding">向量模型</option></select></label>
            <label class="feature-form-field"><span>配置名称</span><input v-model.trim="form.name" required :readonly="!isNew" placeholder="例如 deepseek-chat" /></label>
            <label class="feature-form-field"><span>模型别名</span><input v-model.trim="form.alias" maxlength="128" placeholder="例如 通用问答模型" /></label>
            <label class="feature-form-field"><span>服务端模型 ID</span><input v-model.trim="form.providerModel" placeholder="留空时使用配置名称" /></label>
            <label class="feature-form-field"><span>协议</span><input v-model.trim="form.protocol" placeholder="auto / openai / dashscope-native" /></label>
            <label class="feature-form-field model-field-wide"><span>接口地址</span><input v-model.trim="form.baseUrl" required type="url" placeholder="https://..." /></label>
            <label class="feature-form-field model-field-wide"><span>中文描述</span><textarea v-model.trim="form.description" rows="3" placeholder="说明模型能力、适用场景和使用限制"></textarea></label>
          </div>
        </div>
        <div class="model-form-section">
          <h3>运行参数</h3>
          <div class="model-form-grid">
            <label v-if="form.type === 'embedding'" class="feature-form-field"><span>向量维度</span><input v-model.number="form.dimension" required type="number" min="1" /></label>
            <label class="feature-form-field"><span>请求超时（毫秒）</span><input v-model.number="form.timeout" type="number" min="1" /></label>
            <label v-if="form.type === 'chat'" class="feature-form-field"><span>最大输出 Token（-1 为不限制）</span><input v-model.number="form.maxTokens" type="number" min="-1" /></label>
            <label v-if="form.type === 'chat'" class="feature-form-field"><span>最大重试次数</span><input v-model.number="form.maxRetries" type="number" min="0" /></label>
          </div>
          <p v-if="form.type === 'embedding'" class="model-form-hint">修改已建索引的向量维度后，需要重建对应索引。</p>
        </div>
        <div class="model-form-section">
          <h3>访问与状态</h3>
          <label class="feature-form-field"><span>API Key</span><input v-model="form.apiKey" type="password" autocomplete="new-password" :placeholder="isNew ? '输入 API Key' : '留空则保持原有密钥'" /></label>
          <p class="model-form-hint">密钥提交后加密保存，页面不会回显。</p>
          <div class="model-options">
            <label class="model-checkbox"><input v-model="form.enabled" type="checkbox" />启用</label>
            <label class="model-checkbox"><input v-model="form.defaultModel" type="checkbox" />设为该类型的默认模型</label>
          </div>
        </div>
        <div class="feature-modal-actions model-dialog-actions"><button type="button" class="feature-button" @click="editing = false">取消</button><button type="submit" class="feature-button primary" :disabled="saving">{{ saving ? '保存中…' : '保存模型' }}</button></div>
      </form>
    </div>
  </section>
</template>

<script>
import { Cpu, Database, KeyRound, MessageSquare, Pencil, Plus, Search, Star, Trash2, X } from '@lucide/vue';
import AppPagination from '../components/AppPagination.vue';
import { deletePlatformModel, fetchPlatformModels, savePlatformModel, setDefaultPlatformModel } from '../services/api';
import '../styles/pages/model-management.css';

const emptyForm = () => ({ name: '', alias: '', description: '', type: 'chat', providerModel: '', baseUrl: '', protocol: 'auto',
  dimension: 1024, timeout: 30000, maxTokens: -1, maxRetries: 3,
  apiKey: '', enabled: true, defaultModel: false });

export default {
  name: 'ModelManagementView',
  components: { AppPagination, Cpu, Database, KeyRound, MessageSquare, Pencil, Plus, Search, Star, Trash2, X },
  data: () => ({ models: [], loading: false, saving: false, editing: false, isNew: true,
    form: emptyForm(), message: '', error: false, searchQuery: '', typeFilter: '', page: 1, pageSize: 9 }),
  computed: {
    filteredModels() {
      const query = this.searchQuery.toLocaleLowerCase();
      return this.models.filter(model => {
        if (this.typeFilter && model.type !== this.typeFilter) return false;
        if (!query) return true;
        return [model.alias, model.name, model.description, model.providerModel, model.baseUrl]
          .some(value => String(value || '').toLocaleLowerCase().includes(query));
      });
    },
    pageCount() { return Math.max(1, Math.ceil(this.filteredModels.length / this.pageSize)); },
    pagedModels() {
      const start = (this.page - 1) * this.pageSize;
      return this.filteredModels.slice(start, start + this.pageSize);
    }
  },
  watch: {
    searchQuery() { this.page = 1; },
    typeFilter() { this.page = 1; },
    filteredModels() { this.page = Math.min(this.page, this.pageCount); }
  },
  mounted() { this.load(); },
  methods: {
    clearFilters() { this.searchQuery = ''; this.typeFilter = ''; this.page = 1; },
    async load() {
      this.loading = true;
      try { this.models = await fetchPlatformModels(); this.error = false; }
      catch (error) { this.message = error.message; this.error = true; }
      finally { this.loading = false; }
    },
    openCreate() { this.form = emptyForm(); this.isNew = true; this.editing = true; },
    openEdit(model) { this.form = { ...model, apiKey: '' }; this.isNew = false; this.editing = true; },
    async save() {
      this.saving = true;
      try {
        await savePlatformModel(this.form);
        this.form.apiKey = '';
        this.editing = false;
        this.message = '模型配置已保存'; this.error = false;
        await this.load();
      } catch (error) { this.message = error.message; this.error = true; }
      finally { this.saving = false; }
    },
    async makeDefault(model) {
      try { await setDefaultPlatformModel(model.type, model.name); this.message = '默认模型已更新'; await this.load(); }
      catch (error) { this.message = error.message; this.error = true; }
    },
    async remove(model) {
      if (!window.confirm(`确定删除模型 ${model.name}？`)) return;
      try { await deletePlatformModel(model.type, model.name); this.message = '模型已删除'; await this.load(); }
      catch (error) { this.message = error.message; this.error = true; }
    }
  }
};
</script>
