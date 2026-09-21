<template>
  <section class="feature-view model-management-view">
    <header class="feature-page-header model-header">
      <div class="feature-page-heading"><span class="feature-breadcrumb">平台管理</span><h1>模型管理</h1><p>统一管理大语言模型和向量模型。数据库中的最近配置优先于配置文件。</p></div>
      <button type="button" class="feature-button primary" @click="openCreate">新增模型</button>
    </header>
    <p v-if="message" role="status" class="feature-alert" :class="error ? 'error' : 'success'">{{ message }}</p>
    <div v-if="loading" class="model-state">正在加载模型…</div>
    <div v-else class="model-grid">
      <article v-for="model in models" :key="`${model.type}:${model.name}`" class="feature-card model-card">
        <div class="model-card-head">
          <div><strong>{{ model.name }}</strong><small>{{ model.type === 'embedding' ? '向量模型' : '大语言模型' }} · {{ model.providerModel }}</small></div>
          <span v-if="model.defaultModel" class="model-default">默认模型</span>
        </div>
        <p class="model-url" :title="model.baseUrl">{{ model.baseUrl }}</p>
        <small class="model-meta">API Key：{{ model.hasApiKey ? '已配置（不可查看）' : '未配置' }} <span>·</span> {{ model.enabled ? '已启用' : '已停用' }}</small>
        <div class="feature-card-actions model-actions">
          <button type="button" class="feature-button" @click="openEdit(model)">编辑</button>
          <button type="button" class="feature-button" :disabled="!model.enabled || model.defaultModel" @click="makeDefault(model)">设为默认</button>
          <button type="button" class="feature-button danger" @click="remove(model)">删除</button>
        </div>
      </article>
      <p v-if="!models.length" class="model-state">暂无模型配置。</p>
    </div>
    <div v-if="editing" class="model-dialog-backdrop" @click.self="editing = false">
      <form class="model-dialog" @submit.prevent="save">
        <h2>{{ isNew ? '新增模型' : '编辑模型' }}</h2>
        <label>类型<select v-model="form.type" :disabled="!isNew"><option value="chat">大语言模型</option><option value="embedding">向量模型</option></select></label>
        <label>配置名称<input v-model.trim="form.name" required :readonly="!isNew" placeholder="例如 deepseek-chat" /></label>
        <label>服务端模型 ID<input v-model.trim="form.providerModel" placeholder="留空时使用配置名称" /></label>
        <label>接口地址<input v-model.trim="form.baseUrl" required type="url" placeholder="https://..." /></label>
        <label>协议<input v-model.trim="form.protocol" placeholder="auto / openai / dashscope-native" /></label>
        <label v-if="form.type === 'embedding'">向量维度<input v-model.number="form.dimension" required type="number" min="1" /></label>
        <small v-if="form.type === 'embedding'">修改已建索引的向量维度后，需要重建对应索引。</small>
        <label>请求超时（毫秒）<input v-model.number="form.timeout" type="number" min="1" /></label>
        <label v-if="form.type === 'chat'">最大输出 Token（-1 为不限制）<input v-model.number="form.maxTokens" type="number" min="-1" /></label>
        <label v-if="form.type === 'chat'">最大重试次数<input v-model.number="form.maxRetries" type="number" min="0" /></label>
        <label>API Key<input v-model="form.apiKey" type="password" autocomplete="new-password" :placeholder="isNew ? '输入 API Key' : '留空则保持原有密钥'" /></label>
        <small>密钥提交后加密保存，页面不会回显。</small>
        <label class="model-checkbox"><input v-model="form.enabled" type="checkbox" />启用</label>
        <label class="model-checkbox"><input v-model="form.defaultModel" type="checkbox" />设为该类型的默认模型</label>
        <div class="feature-modal-actions model-actions"><button type="button" class="feature-button" @click="editing = false">取消</button><button type="submit" class="feature-button primary" :disabled="saving">保存</button></div>
      </form>
    </div>
  </section>
</template>

<script>
import { deletePlatformModel, fetchPlatformModels, savePlatformModel, setDefaultPlatformModel } from '../services/api';
import '../styles/pages/model-management.css';

const emptyForm = () => ({ name: '', type: 'chat', providerModel: '', baseUrl: '', protocol: 'auto',
  dimension: 1024, timeout: 30000, maxTokens: -1, maxRetries: 3,
  apiKey: '', enabled: true, defaultModel: false });

export default {
  name: 'ModelManagementView',
  data: () => ({ models: [], loading: false, saving: false, editing: false, isNew: true,
    form: emptyForm(), message: '', error: false }),
  mounted() { this.load(); },
  methods: {
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
