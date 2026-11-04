<template>
  <section class="feature-view model-management-view">
    <header class="feature-page-header">
      <div class="feature-page-heading">
        <span class="feature-breadcrumb">平台管理 / 模型管理</span>
        <h1>模型管理</h1>
        <p>统一管理大语言模型和向量模型。页面维护的配置发布后才会供系统使用。</p>
      </div>
      <div class="feature-page-actions">
        <button type="button" class="feature-button" @click="openProviderManager"><Search :size="16" />检索引擎</button>
        <button type="button" class="feature-button primary" @click="openCreate"
          ><Plus :size="16" />新增模型</button
        >
      </div>
    </header>

    <p v-if="message" role="status" class="feature-alert" :class="error ? 'error' : 'success'">{{
      message
    }}</p>
    <div class="feature-summary-panel model-summary">
      <div class="feature-summary-primary">
        <span class="feature-summary-icon"><Cpu :size="19" /></span>
        <div><strong>模型配置</strong><small>集中查看连接信息与运行状态</small></div>
      </div>
      <div class="model-summary-stats">
        <span
          ><strong>{{ models.length }}</strong> 个模型</span
        >
        <span
          ><strong>{{ models.filter((model) => model.published).length }}</strong> 个已发布</span
        >
      </div>
    </div>

    <div v-if="models.length" class="feature-filter-bar model-filter-bar">
      <label class="feature-search-field">
        <Search class="feature-search-icon" :size="17" aria-hidden="true" />
        <input
          v-model.trim="searchQuery"
          type="search"
          aria-label="检索模型"
          placeholder="检索别名、名称、描述、模型 ID 或接口地址"
        />
      </label>
      <label class="feature-select-field">
        <span>模型类型</span>
        <select v-model="typeFilter">
          <option value="">全部类型</option>
          <option value="chat">大语言模型</option>
          <option value="embedding">向量模型</option>
        </select>
      </label>
      <span class="feature-result-count"
        >找到 <strong>{{ filteredModels.length }}</strong> 个模型</span
      >
    </div>

    <div v-if="loading" class="feature-empty-state model-state" role="status">
      <span class="feature-empty-icon loading" aria-hidden="true"></span>
      <strong>正在加载模型</strong><p>请稍候…</p>
    </div>
    <div v-else-if="!models.length" class="feature-empty-state model-state">
      <span class="feature-empty-icon"><Cpu :size="18" /></span>
      <strong>暂无模型配置</strong>
      <p>添加模型后，即可在这里管理连接和默认模型。</p>
      <button type="button" class="feature-button primary" @click="openCreate"
        ><Plus :size="16" />新增模型</button
      >
    </div>
    <div v-else-if="!filteredModels.length" class="feature-empty-state model-state">
      <span class="feature-empty-icon"><Search :size="18" /></span>
      <strong>没有找到匹配的模型</strong>
      <p>试试其他关键字或模型类型。</p>
      <button type="button" class="feature-button" @click="clearFilters">清除筛选</button>
    </div>
    <div v-else class="feature-card-grid model-grid">
      <article
        v-for="model in pagedModels"
        :key="`${model.type}:${model.name}`"
        class="feature-content-card model-card"
      >
        <div class="model-card-head">
          <span class="model-type-icon"
            ><Database v-if="model.type === 'embedding'" :size="20" /><MessageSquare
              v-else
              :size="20"
          /></span>
          <div class="model-card-title"
            ><strong :title="model.alias || model.name">{{ model.alias || model.name }}</strong
            ><small :title="model.name"
              >{{ model.name }} ·
              {{ model.type === "embedding" ? "向量模型" : "大语言模型" }}</small
            ></div
          >
          <span
            class="feature-status"
            :class="model.published && !model.pendingChanges ? 'published' : 'warning'"
            >{{
              !model.published ? "待发布" : model.pendingChanges ? "待发布更新" : "已发布"
            }}</span
          >
        </div>
        <div class="model-badges">
          <span v-if="model.activeDefault" class="feature-status builtin"
            ><Star :size="12" />当前默认</span
          >
          <span v-else-if="model.defaultModel" class="feature-status builtin"
            ><Star :size="12" />拟设默认</span
          >
          <span class="feature-status" :class="model.enabled ? 'published' : 'recalled'">{{
            model.pendingChanges || !model.published
              ? model.enabled
                ? "草稿启用"
                : "草稿停用"
              : model.enabled
                ? "已启用"
                : "已停用"
          }}</span>
          <span class="feature-status" :class="model.hasApiKey ? 'published' : 'warning'"
            ><KeyRound :size="12" />{{ model.hasApiKey ? "密钥已配置" : "密钥未配置" }}</span
          >
        </div>
        <p class="model-description">{{ model.description || "暂无能力与适用范围描述" }}</p>
        <dl class="model-details">
          <div
            ><dt>服务端模型</dt
            ><dd :title="model.providerModel || model.name">{{
              model.providerModel || model.name
            }}</dd></div
          >
          <div
            ><dt>接口地址</dt><dd :title="model.baseUrl">{{ model.baseUrl || "未设置" }}</dd></div
          >
        </dl>
        <div class="feature-card-actions model-actions">
          <button type="button" class="feature-button" @click="openEdit(model)"
            ><Pencil :size="14" />编辑</button
          >
          <button
            type="button"
            class="feature-button"
            :disabled="!model.enabled || model.defaultModel"
            @click="makeDefault(model)"
            ><Star :size="14" />设为默认</button
          >
          <button
            v-if="!model.published || model.pendingChanges"
            type="button"
            class="feature-button primary"
            :disabled="publishingKey === `${model.type}:${model.name}`"
            @click="publish(model)"
            >{{ publishingKey === `${model.type}:${model.name}` ? "发布中…" : "发布" }}</button
          >
          <button type="button" class="feature-button danger" @click="remove(model)"
            ><Trash2 :size="14" />删除</button
          >
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
      <form
        class="feature-modal model-dialog"
        aria-labelledby="model-dialog-title"
        @submit.prevent="save"
      >
        <div class="feature-modal-header">
          <div
            ><h2 id="model-dialog-title">{{ isNew ? "新增模型" : "编辑模型" }}</h2
            ><p>配置模型连接与运行参数</p></div
          >
          <button
            type="button"
            class="feature-icon-button"
            aria-label="关闭"
            @click="editing = false"
            ><X :size="20"
          /></button>
        </div>
        <div class="model-form-section">
          <h3>基础信息</h3>
          <div class="model-form-grid">
            <label class="feature-form-field"
              ><span>类型</span
              ><select v-model="form.type" :disabled="!isNew"
                ><option value="chat">大语言模型</option
                ><option value="embedding">向量模型</option></select
              ></label
            >
            <label class="feature-form-field"
              ><span>配置名称</span
              ><input
                v-model.trim="form.name"
                required
                :readonly="!isNew"
                placeholder="例如 deepseek-chat"
            /></label>
            <label class="feature-form-field"
              ><span>模型别名</span
              ><input v-model.trim="form.alias" maxlength="128" placeholder="例如 通用问答模型"
            /></label>
            <label class="feature-form-field"
              ><span>服务端模型 ID</span
              ><input v-model.trim="form.providerModel" placeholder="留空时使用配置名称"
            /></label>
            <label class="feature-form-field"
              ><span>协议</span
              ><input v-model.trim="form.protocol" placeholder="auto / openai / dashscope-native"
            /></label>
            <label class="feature-form-field model-field-wide"
              ><span>接口地址</span
              ><input v-model.trim="form.baseUrl" required type="url" placeholder="https://..."
            /></label>
            <label class="feature-form-field model-field-wide"
              ><span>中文描述</span
              ><textarea
                v-model.trim="form.description"
                rows="3"
                placeholder="说明模型能力、适用场景和使用限制"
              ></textarea>
            </label>
          </div>
        </div>
        <div class="model-form-section">
          <h3>运行参数</h3>
          <div class="model-form-grid">
            <label v-if="form.type === 'embedding'" class="feature-form-field"
              ><span>向量维度</span
              ><input v-model.number="form.dimension" required type="number" min="1"
            /></label>
            <label class="feature-form-field"
              ><span>模型请求超时时间（秒）</span
              ><input v-model.number="form.requestTimeoutSeconds" required type="number" min="1" max="1200" step="1"
            /></label>
            <label v-if="form.type === 'chat'" class="feature-form-field"
              ><span>最大输出 Token（-1 为不限制）</span
              ><input v-model.number="form.maxTokens" type="number" min="-1"
            /></label>
            <label v-if="form.type === 'chat'" class="feature-form-field"
              ><span>最大重试次数</span
              ><input v-model.number="form.maxRetries" type="number" min="0"
            /></label>
          </div>
          <p class="model-form-hint">单次模型 API 请求的最长等待时间，默认 300 秒。此配置适用于 Chat、Agent、Runtime OS、Cline 等所有使用该模型的功能。</p>
          <p v-if="form.type === 'embedding'" class="model-form-hint"
            >修改已建索引的向量维度后，需要重建对应索引。</p
          >
        </div>
        <div v-if="form.type === 'chat'" class="model-form-section">
          <h3>联网搜索</h3>
          <label class="model-checkbox"><input v-model="form.internetSearchEnabled" type="checkbox" />联网搜索</label>
          <div class="search-engine-heading"><span>检索引擎（可多选）</span><button type="button" class="feature-button" @click="openProviderManager">维护检索引擎</button></div>
          <p v-if="providerError && !providerManagerOpen" class="feature-alert error">{{ providerError }}</p>
          <div class="search-engine-options">
            <label v-for="provider in readySearchProviders" :key="provider.id" class="search-engine-option">
              <input v-model="form.searchProviderIds" type="checkbox" :value="provider.id" />
              <span><strong>{{ provider.name }}</strong><small>{{ provider.endpoint }}</small></span>
            </label>
            <label v-for="provider in unavailableSearchProviders" :key="provider.id" class="search-engine-option unavailable">
              <input v-model="form.searchProviderIds" type="checkbox" :value="provider.id" />
              <span>{{ provider.name }}<small>当前不可用，请取消勾选或在维护中启用</small></span>
            </label>
          </div>
          <p v-if="!readySearchProviders.length" class="model-form-hint">尚无可用检索引擎，请先维护名称、URL 和 API Key 并启用。</p>
          <p class="model-form-hint">保存后立即生效。可勾选多个引擎，Cline 在授权范围内自主选择；分析代码仍在隔离环境中运行。</p>
        </div>
        <div class="model-form-section">
          <h3>访问与状态</h3>
          <label class="feature-form-field"
            ><span>API Key</span
            ><input
              v-model="form.apiKey"
              type="password"
              autocomplete="new-password"
              :placeholder="isNew ? '输入 API Key' : '留空则保持原有密钥'"
          /></label>
          <p class="model-form-hint">密钥提交后加密保存，页面不会回显。</p>
          <div class="model-options">
            <label class="model-checkbox"
              ><input v-model="form.enabled" type="checkbox" @change="onEnabledChange" />启用</label
            >
            <label class="model-checkbox"
              ><input v-model="form.defaultModel" type="checkbox" />设为该类型的默认模型</label
            >
          </div>
        </div>
        <div class="feature-modal-actions model-dialog-actions"
          ><button type="button" class="feature-button" @click="editing = false">取消</button
          ><button type="submit" class="feature-button primary" :disabled="saving">{{
            saving ? "保存中…" : "保存模型"
          }}</button></div
        >
      </form>
    </div>
    <div v-if="providerManagerOpen" class="feature-modal-backdrop search-engine-backdrop" @click.self="closeProviderManager">
      <div class="feature-modal search-engine-dialog" role="dialog" aria-modal="true" aria-label="维护检索引擎">
        <header class="feature-modal-header"><div><h2>检索引擎</h2><p class="model-form-hint">统一维护连接信息，供模型按需勾选。</p></div>
          <button type="button" class="feature-icon-button" aria-label="关闭检索引擎维护" @click="closeProviderManager"><X :size="18" /></button>
        </header>
        <div class="search-engine-manager">
          <aside class="search-engine-list">
            <button type="button" class="feature-button" :disabled="providerSaving" @click="newProvider"><Plus :size="16" />新增引擎</button>
            <p v-if="!searchProviders.length" class="model-form-hint">还没有维护检索引擎。</p>
            <article v-for="provider in searchProviders" :key="provider.id" :class="['search-engine-card',{selected:providerForm.id===provider.id}]">
              <strong>{{ provider.name }}</strong><small>{{ provider.endpoint }}</small>
              <span class="model-form-hint">{{ !provider.enabled ? '已停用' : provider.configured ? '已配置 · 已启用' : '待配置 API Key' }}</span>
              <div class="search-engine-card-actions"><button type="button" class="feature-button" :disabled="providerSaving" @click="editProvider(provider)">编辑</button>
                <button type="button" class="feature-button danger" :disabled="providerSaving" @click="pendingProviderDelete=provider">删除</button></div>
            </article>
          </aside>
          <form class="search-engine-form" @submit.prevent="saveProvider">
            <h3>{{ providerForm.id ? '编辑检索引擎' : '新增检索引擎' }}</h3>
            <p v-if="providerError" role="alert" class="feature-alert error">{{ providerError }}</p>
            <p v-if="providerMessage" role="status" class="feature-alert success">{{ providerMessage }}</p>
            <label class="feature-form-field"><span>名称</span><input v-model.trim="providerForm.name" required maxlength="128" placeholder="例如：团队资讯搜索" /></label>
            <label class="feature-form-field"><span>接口协议</span><select v-model="providerForm.kind" :disabled="!!providerForm.id"><option value="TAVILY">Tavily</option><option value="BRAVE">Brave Search</option><option value="TENCENT">腾讯 WSA</option></select></label>
            <label class="feature-form-field"><span>URL</span><input v-model.trim="providerForm.endpoint" required type="url" placeholder="https://检索服务地址" /></label>
            <label v-if="providerForm.kind==='TENCENT'" class="feature-form-field"><span>Secret ID</span><input v-model.trim="providerForm.secretId" type="password" autocomplete="new-password" :placeholder="providerForm.configured ? '留空保留原有 Secret ID' : '输入腾讯 Secret ID'" /></label>
            <label class="feature-form-field"><span>{{ providerForm.kind==='TENCENT' ? 'API Key（Secret Key）' : 'API Key' }}</span><input v-model="providerForm.apiKey" type="password" autocomplete="new-password" :placeholder="providerForm.configured ? '留空保留原有密钥' : '输入 API Key'" /></label>
            <p class="model-form-hint">密钥加密保存，不会回显。相同接口协议可维护多个不同名称和 URL 的引擎。</p>
            <label class="model-checkbox"><input v-model="providerForm.enabled" type="checkbox" />启用此检索引擎</label>
            <details class="search-engine-limits"><summary>调用限制</summary><div class="model-form-grid">
              <label class="feature-form-field"><span>超时（毫秒）</span><input v-model.number="providerForm.timeoutMillis" type="number" min="1000" max="30000" required /></label>
              <label class="feature-form-field"><span>每分钟调用上限</span><input v-model.number="providerForm.requestsPerMinute" type="number" min="1" max="600" required /></label>
              <label class="feature-form-field"><span>单任务调用上限</span><input v-model.number="providerForm.maxTaskCalls" type="number" min="1" max="50" required /></label>
              <label class="feature-form-field"><span>每日调用上限</span><input v-model.number="providerForm.maxDailyCalls" type="number" min="1" max="100000" required /></label>
            </div></details>
            <div class="search-engine-card-actions"><button type="button" class="feature-button" :disabled="providerSaving" @click="closeProviderManager">关闭</button><button type="submit" class="feature-button primary" :disabled="providerSaving">{{ providerSaving ? '保存中…' : '保存引擎' }}</button></div>
          </form>
        </div>
      </div>
    </div>
    <AppConfirmDialog v-if="pendingProviderDelete" title="删除检索引擎" :message="`确定删除「${pendingProviderDelete.name}」？引用此引擎的模型需要重新选择。`" @cancel="pendingProviderDelete=null" @confirm="confirmProviderDelete" />
    <AppConfirmDialog
      v-if="pendingDelete"
      title="删除模型"
      :message="`确定删除模型「${pendingDelete.alias || pendingDelete.name}」（${pendingDelete.name}）？删除后将无法继续使用此模型。`"
      @cancel="pendingDelete = null"
      @confirm="confirmDelete"
    />
  </section>
</template>

<script src="../js/views/ModelManagementView.js"></script>
