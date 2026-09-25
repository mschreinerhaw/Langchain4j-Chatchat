<template>
  <section class="feature-view skill-hub-view agent-workshop-view">
    <header class="agent-workshop-header">
      <div>
        <p>Agent管理</p>
      </div>
    </header>

    <section class="agent-summary">
      <article>
        <span>Agent总数</span>
        <strong>{{ (summary.agentCount || 0) + remoteAgents.length }}</strong>
      </article>
      <article>
        <span>自定义</span>
        <strong>{{ summary.customCount || 0 }}</strong>
      </article>
      <article>
        <span>已发布</span>
        <strong>{{ summary.publishedCount || 0 }}</strong>
      </article>
      <article>
        <span>未上架</span>
        <strong>{{ summary.unpublishedCount || 0 }}</strong>
      </article>
      <article>
        <span>可用工具</span>
        <strong>{{ summary.availableToolCount || 0 }}</strong>
      </article>
      <article>
        <span>MCP工具</span>
        <strong>{{ summary.registeredMcpToolCount || 0 }}</strong>
      </article>
    </section>

    <section class="agent-list-controls">
      <header>
        <div>
          <strong>Agent列表</strong>
          <span>{{ agentTotal + matchingRemoteAgents.length }} / {{ (summary.agentCount || 0) + remoteAgents.length }} 个</span>
        </div>
        <div class="agent-light-actions">
          <button type="button" class="primary-button" @click="openCreateDialog">新增Agent</button>
          <button v-if="isPlatformAdmin" type="button" class="light-button" @click="openRemoteDialog">接入专有分析 Agent</button>
          <button type="button" class="light-button" @click="openImportDialog">批量导入</button>
          <button type="button" class="light-button" :disabled="selectedAgentCount === 0" @click="exportAgentsAsJson">
            导出已选JSON（{{ selectedAgentCount }}）
          </button>
          <button type="button" class="light-button" :disabled="selectedAgentCount === 0" @click="exportAgentsAsTable">
            导出已选表格（{{ selectedAgentCount }}）
          </button>
          <button v-if="selectedAgentCount" type="button" class="light-button" @click="clearAgentExportSelection">
            清除勾选
          </button>
          <button type="button" class="light-button" :disabled="loading" @click="refreshAgentList">
            {{ loading ? "刷新中" : "刷新" }}
          </button>
        </div>
      </header>

      <div class="agent-list-filters">
        <label class="agent-search-field">
          <span>检索Agent</span>
          <input v-model.trim="searchQuery" type="search" placeholder="名称、场景、标签或工具">
        </label>
        <label>
          <span>分类</span>
          <select v-model="agentCategoryFilter">
            <option v-for="option in agentCategoryOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>
        <label>
          <span>状态</span>
          <select v-model="agentStatusFilter">
            <option v-for="option in agentStatusOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>
        <label>
          <span>模型</span>
          <select v-model="agentModelFilter">
            <option v-for="option in agentModelOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>
        <button v-if="hasActiveAgentFilters" type="button" class="light-button" @click="resetAgentFilters">
          重置筛选
        </button>
      </div>
    </section>

    <p v-if="error" class="agent-error">{{ error }}</p>
    <p v-else-if="loading && agents.length === 0" class="agent-empty">正在加载后端Agent配置...</p>
    <p v-else-if="(summary.agentCount || 0) + remoteAgents.length === 0" class="agent-empty">暂无Agent配置，请先新增或接入一个。</p>
    <p v-else-if="agentTotal + matchingRemoteAgents.length === 0" class="agent-empty">没有匹配的Agent，请换一个关键词。</p>

    <div v-else class="feature-grid">
      <article v-for="agent in visibleRemoteAgents" :key="`remote:${agent.agentId}`" class="feature-card agent-card remote-agent-card">
        <span class="remote-card-source">专有分析算力</span>
        <div class="agent-card-head"><span>专</span><div><h2>{{ agent.metadata?.displayName || agent.agentId }}</h2><small>{{ agent.origin === 'GROUP' ? '集团内部平台' : '第三方平台' }}</small></div><strong :class="{ off: !agent.enabled }">{{ agent.enabled ? '已接入' : '已停用' }}</strong></div>
        <p>{{ agent.metadata?.professionalCapabilities?.join('、') || '尚未填写专业能力描述。' }}</p>
        <dl class="agent-meta"><div><dt>模式</dt><dd>专有分析</dd></div><div><dt>Skill</dt><dd>{{ agent.metadata?.analysisGrants?.skillIds?.length || 0 }} 个</dd></div><div><dt>文档</dt><dd>{{ agent.metadata?.analysisGrants?.documentIds?.length || 0 }} 份</dd></div><div><dt>MCP</dt><dd>{{ agent.metadata?.analysisGrants?.mcpToolNames?.length || 0 }} 个</dd></div></dl>
        <div class="agent-card-actions"><button type="button" class="secondary-button" @click="apiExampleAgentId = agent.agentId">分析 API</button><button type="button" class="secondary-button" @click="$emit('navigate', 'domainAnalysis')">开始分析</button></div>
      </article>
      <article
        v-for="agent in paginatedAgents"
        :key="agent.id"
        class="feature-card agent-card"
        :class="{ 'export-selected': isAgentSelectedForExport(agent) }"
      >
        <label class="agent-export-select">
          <input
            type="checkbox"
            :checked="isAgentSelectedForExport(agent)"
            :aria-label="`选择导出 ${agent.name || agent.id}`"
            :title="`选择导出 ${agent.name || agent.id}`"
            @change="setAgentExportSelection(agent, $event.target.checked)"
          >
        </label>
        <div class="agent-card-head">
          <span>{{ agent.shortName || agentBadge(agent) }}</span>
          <div>
            <h2>{{ agent.name }}</h2>
            <small>{{ agent.status }}</small>
          </div>
          <strong :class="{ off: agent.marketStatus !== 'published' }">{{ agent.marketStatusLabel || "未发布" }}</strong>
        </div>
        <p>{{ agent.description || "暂无业务描述。" }}</p>

        <section v-if="previewList(agent.usageScenarios, 3).length" class="agent-block">
          <strong>业务场景</strong>
          <ul>
            <li v-for="scenario in previewList(agent.usageScenarios, 3)" :key="`${agent.id}-${scenario}`">
              {{ scenario }}
            </li>
          </ul>
        </section>

        <div v-if="agent.defaultAgent || agent.defaultDataAsset?.enabled || agent.skillTags?.length" class="agent-tags">
          <span v-if="agent.defaultAgent" class="agent-default-tag">默认Agent</span>
          <span v-if="agent.defaultDataAsset?.enabled" class="agent-default-asset-tag">
            绑定资产：{{ agent.defaultDataAsset.assetName || agent.defaultDataAsset.assetId }}
          </span>
          <span v-for="tag in agent.skillTags" :key="`${agent.id}-${tag}`">{{ tag }}</span>
        </div>

        <dl class="agent-meta">
          <div>
            <dt>模式</dt>
            <dd>{{ agent.defaultMode || "-" }}</dd>
          </div>
          <div>
            <dt>模型</dt>
            <dd>{{ agent.modelName || defaultModelName() || "默认模型" }}</dd>
          </div>
          <div>
            <dt>环境</dt>
            <dd>{{ agentRuntimeEnvironmentLabel(agent) }}</dd>
          </div>
          <div>
            <dt>工具</dt>
            <dd>{{ toolCountLabel(agent) }}</dd>
          </div>
          <div>
            <dt>文档</dt>
            <dd>{{ documentCountLabel(agent) }}</dd>
          </div>
        </dl>

        <div v-if="previewList(agent.resolvedToolNames, 4).length" class="agent-tool-list">
          <span v-for="tool in previewList(agent.resolvedToolNames, 4)" :key="`${agent.id}-${tool}`">{{ tool }}</span>
        </div>

        <div class="agent-card-actions">
          <button type="button" class="secondary-button" @click="openEditDialog(agent)">设置</button>
          <button
            v-if="!agent.defaultAgent"
            type="button"
            class="secondary-button"
            :disabled="saving"
            @click="setDefaultAgent(agent)"
          >
            设为默认
          </button>
          <button
            v-if="agent.marketStatus !== 'published'"
            type="button"
            class="primary-button"
            :disabled="saving"
            @click="publishAgent(agent)"
          >
            发布能力
          </button>
          <button
            v-else
            type="button"
            class="secondary-button"
            :disabled="saving"
            @click="recallAgent(agent)"
          >
            回收能力
          </button>
          <button
            v-if="isPlatformAdmin && agent.marketStatus === 'published'"
            type="button"
            class="secondary-button"
            :disabled="curlExampleLoading"
            @click="openCurlExample(agent)"
          >
            API
          </button>
          <button
            v-if="agent.defaultAgent"
            type="button"
            class="secondary-button"
            disabled
          >
            默认不可删
          </button>
          <button
            v-else-if="!agent.builtin"
            type="button"
            class="danger-button"
            :disabled="saving"
            @click="removeAgent(agent)"
          >
            删除
          </button>
        </div>
      </article>
    </div>

    <nav v-if="agentTotal > agentPageSize" class="agent-pagination" aria-label="Agent分页">
      <span>第 {{ agentPage }} / {{ totalAgentPages }} 页，每页 {{ agentPageSize }} 个</span>
      <div>
        <button type="button" class="light-button" :disabled="agentPage <= 1" @click="goAgentPage(agentPage - 1)">
          上一页
        </button>
        <button
          v-for="page in agentPageButtons"
          :key="page"
          type="button"
          class="light-button"
          :class="{ active: page === agentPage }"
          @click="goAgentPage(page)"
        >
          {{ page }}
        </button>
        <button type="button" class="light-button" :disabled="agentPage >= totalAgentPages" @click="goAgentPage(agentPage + 1)">
          下一页
        </button>
      </div>
    </nav>

    <section v-if="apiExampleAgent" class="agent-list-controls" aria-label="专有分析 API 调用方式">
      <header><div><strong>{{ apiExampleAgent.metadata?.displayName || apiExampleAgent.agentId }} · 分析 API</strong><span>同步返回分析结果</span></div><button type="button" class="light-button" @click="apiExampleAgentId = ''">收起</button></header>
      <p><code>POST /api/v1/agent/analysis/domain-intelligence</code></p>
      <p>请求头：<code>Authorization: Bearer &lt;登录会话令牌&gt;</code>，<code>Content-Type: application/json</code>。使用有权访问所选 Skill 和证据的账号；Agent API Token 不能调用此接口。</p>
      <pre class="remote-api-example">{{ domainAnalysisApiRequest }}</pre>
      <p>响应中的 <code>data.synthesis</code> 为分析文本，<code>data.verification</code> 为校验信息。示例里的 Skill、文档和工具均须在调用者与该 Agent 的授权范围内；如果没有可用文档，请改为提供已授权的只读 MCP 工具证据。</p>
    </section>

    <div v-if="curlExampleOpen" class="agent-dialog-backdrop">
      <section class="agent-dialog agent-curl-dialog" role="dialog" aria-modal="true" aria-labelledby="agent-curl-title">
        <header class="agent-curl-header">
          <div class="agent-curl-title-wrap">
            <span class="agent-curl-brand-mark" aria-hidden="true">API</span>
            <div>
              <p>已发布 Agent · API 调用</p>
            <h2 id="agent-curl-title">{{ curlExample?.agentName || "curl 请求示例" }}</h2>
              <span>复制示例后，即可在终端管理问答任务的完整生命周期</span>
            </div>
          </div>
          <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" :disabled="curlExampleLoading" @click="closeCurlExample">×</button>
        </header>
        <div class="dialog-body agent-curl-body">
          <div class="agent-curl-capabilities" aria-label="示例包含的调用步骤">
            <span><b>1</b> 发起问答</span>
            <i aria-hidden="true"></i>
            <span><b>2</b> 查询状态</span>
            <i aria-hidden="true"></i>
            <span><b>3</b> 停止任务</span>
            <i aria-hidden="true"></i>
            <span><b>4</b> 获取答案</span>
          </div>
          <div class="agent-curl-notice">
            <span class="agent-curl-notice-icon" aria-hidden="true">!</span>
            <p>
              <strong>调用前准备</strong>
              将示例中的 <code>&lt;paste-agent-api-token&gt;</code> 替换为“系统管理 → 用户管理 → token”中生成的令牌。示例统一使用环境变量 <code>AGENT_TOKEN</code>，任务编号会在提交成功后自动从 <code>data.taskId</code> 提取。
            </p>
          </div>
          <div v-if="curlExampleLoading" class="agent-curl-loading">
            <span></span><span></span><span></span>
            正在为该 Agent 生成调用示例…
          </div>
          <div v-else-if="curlExample?.completeExample" class="agent-curl-code-card">
            <div class="agent-curl-code-head">
              <span class="agent-curl-terminal-mark" aria-hidden="true">&gt;_</span>
              <strong>终端调用示例</strong>
              <span>Shell / curl</span>
            </div>
            <pre><code>{{ curlExample.completeExample }}</code></pre>
          </div>
          <p v-if="curlExampleError" class="agent-error">{{ curlExampleError }}</p>
        </div>
        <footer class="agent-curl-footer">
          <span>令牌仅在请求时使用，请勿提交到代码仓库</span>
          <button type="button" class="secondary-button" :disabled="curlExampleLoading" @click="closeCurlExample">关闭</button>
          <button type="button" class="primary-button" :disabled="curlExampleLoading || !curlExample?.completeExample" @click="copyCurlExample">
            {{ curlExampleCopied ? "✓ 已复制到剪贴板" : "复制完整示例" }}
          </button>
        </footer>
      </section>
    </div>

    <div
      v-if="deleteConfirmOpen"
      class="agent-recall-backdrop agent-delete-backdrop"
      @click.self="closeDeleteConfirm"
    >
      <section
        class="agent-recall-dialog agent-delete-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="agent-delete-title"
        aria-describedby="agent-delete-description"
      >
        <header class="agent-recall-header agent-delete-header">
          <span class="agent-recall-icon agent-delete-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none">
              <path d="M4 7h16" />
              <path d="M9 7V4h6v3" />
              <path d="m7 7 1 13h8l1-13" />
              <path d="M10 11v5M14 11v5" />
            </svg>
          </span>
          <div>
            <p>Agent 管理</p>
            <h2 id="agent-delete-title">确认删除 Agent</h2>
          </div>
          <button
            type="button"
            class="agent-recall-close"
            aria-label="关闭"
            title="关闭"
            :disabled="saving"
            @click="closeDeleteConfirm"
          >×</button>
        </header>

        <div class="agent-recall-body agent-delete-body">
          <p id="agent-delete-description">
            确认永久删除
            <strong>「{{ deleteTarget?.name || deleteTarget?.id }}」</strong>？
          </p>
          <div class="agent-recall-note agent-delete-note">
            <span aria-hidden="true">!</span>
            <p>删除后该 Agent 的配置将无法恢复。如需保留，请先导出配置再执行删除。</p>
          </div>
          <p v-if="deleteConfirmError" class="agent-recall-error">{{ deleteConfirmError }}</p>
        </div>

        <footer class="agent-recall-actions">
          <button type="button" class="agent-recall-cancel" :disabled="saving" @click="closeDeleteConfirm">
            取消
          </button>
          <button type="button" class="agent-delete-submit" :disabled="saving" @click="confirmDeleteAgent">
            <span v-if="saving" class="agent-recall-spinner" aria-hidden="true"></span>
            {{ saving ? "正在删除" : "确认删除" }}
          </button>
        </footer>
      </section>
    </div>

    <div
      v-if="recallConfirmOpen"
      class="agent-recall-backdrop"
      @click.self="closeRecallConfirm"
    >
      <section
        class="agent-recall-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="agent-recall-title"
        aria-describedby="agent-recall-description"
      >
        <header class="agent-recall-header">
          <span class="agent-recall-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none">
              <path d="M4 7h11a5 5 0 0 1 0 10H9" />
              <path d="m8 4-4 3 4 3" />
            </svg>
          </span>
          <div>
            <p>能力市场</p>
            <h2 id="agent-recall-title">确认回收能力</h2>
          </div>
          <button
            type="button"
            class="agent-recall-close"
            aria-label="关闭"
            title="关闭"
            :disabled="saving"
            @click="closeRecallConfirm"
          >×</button>
        </header>

        <div class="agent-recall-body">
          <p id="agent-recall-description">
            确认从能力市场回收
            <strong>「{{ recallTarget?.name || recallTarget?.id }}」</strong>？
          </p>
          <div class="agent-recall-note">
            <span aria-hidden="true">i</span>
            <p>回收后该能力将从市场下架，但不会删除 Agent 配置；后续仍可重新发布。</p>
          </div>
          <p v-if="recallConfirmError" class="agent-recall-error">{{ recallConfirmError }}</p>
        </div>

        <footer class="agent-recall-actions">
          <button type="button" class="agent-recall-cancel" :disabled="saving" @click="closeRecallConfirm">
            取消
          </button>
          <button type="button" class="agent-recall-submit" :disabled="saving" @click="confirmRecallAgent">
            <span v-if="saving" class="agent-recall-spinner" aria-hidden="true"></span>
            {{ saving ? "正在回收" : "确认回收" }}
          </button>
        </footer>
      </section>
    </div>

    <div v-if="remoteDialogOpen" class="agent-dialog-backdrop">
      <form class="agent-dialog remote-agent-dialog" @submit.prevent="saveRemoteAgent">
        <header>
          <div><p>Agent Runtime OS · 专有分析</p><h2>接入专有分析 Agent</h2></div>
          <button type="button" class="app-dialog-close" aria-label="关闭" @click="remoteDialogOpen = false">×</button>
        </header>
        <div class="dialog-body remote-dialog-body">
          <p class="remote-intro">将集团或第三方 Agent 作为专业分析算力使用。Runtime 负责准备已授权的知识与业务数据，Agent 负责按要求进行专业推理。</p>
          <button v-if="isPlatformAdmin" type="button" class="remote-advanced-toggle" :aria-expanded="remoteAdvancedOpen" @click="remoteAdvancedOpen = !remoteAdvancedOpen">{{ remoteAdvancedOpen ? '收起高级接入配置' : '高级接入配置' }} {{ remoteAdvancedOpen ? '⌃' : '⌄' }}</button>
          <section v-if="isPlatformAdmin && remoteAdvancedOpen" class="remote-advanced-panel">
            <p>仅供平台管理员配置发布方身份和必要的请求参数。租户、能力及证据授权由 Runtime 自动确定；远端不能直接调用本地 Skill 或数据源。</p>
            <div class="remote-field-grid">
              <label><span>发布方签名 Key ID</span><input v-model.trim="remoteForm.cardKeyId" placeholder="由发布方提供" @input="invalidateRemotePreview"></label>
              <label><span>凭据引用</span><input v-model.trim="remoteForm.credentialRef" placeholder="env:GROUP_AGENT_TOKEN" @input="invalidateRemotePreview"></label>
              <label class="wide-field"><span>发布方验签公钥（PEM）</span><textarea v-model.trim="remoteForm.cardPublicKeyPem" rows="3" placeholder="-----BEGIN PUBLIC KEY-----" @input="invalidateRemotePreview"></textarea></label>
              <label><span>URL 查询参数（每行 名称=值）</span><textarea v-model="remoteForm.requestQueryParameters" rows="2" @input="invalidateRemotePreview"></textarea></label>
              <label><span>A2A 消息参数（JSON 对象）</span><textarea v-model="remoteForm.requestBodyParameters" rows="2" placeholder="{}"></textarea></label>
            </div>
          </section>
          <section class="remote-step">
            <h3><span>1</span> 基本信息</h3>
            <div class="remote-field-grid">
              <label><span>Agent 名称</span><input v-model.trim="remoteForm.displayName" placeholder="例如：集团客户投资分析 Agent"></label>
              <label><span>接入来源</span><select v-model="remoteForm.origin" @change="invalidateRemotePreview"><option value="GROUP">集团内部平台</option><option value="EXTERNAL">第三方平台</option></select></label>
              <label class="wide-field"><span>服务地址</span><input v-model.trim="remoteForm.endpoint" type="url" placeholder="https://agent.example.com/a2a" @input="invalidateRemotePreview"></label>
            </div>
            <p class="remote-hint">测试连接会自动检查服务、身份与协议。首次接入时，请平台管理员配置发布方身份信息。</p>
            <button type="button" class="secondary-button" :disabled="remoteBusy" @click="previewRemoteAgent">{{ remoteBusy ? '正在测试连接…' : '测试连接' }}</button>
            <div v-if="remotePreview" class="remote-connection-status" aria-live="polite">✓ 连接成功　✓ 身份验证通过　✓ 协议兼容</div>
          </section>
          <section class="remote-step">
            <h3><span>2</span> 专业能力</h3>
            <p>请用业务语言描述这个 Agent 擅长的分析任务，每行一项。Runtime 会将其作为对外展示的能力描述，调用所需的技术能力仍从已验证的 Agent Card 获取。</p>
            <textarea v-model.trim="remoteForm.professionalCapabilities" rows="4" maxlength="1000" aria-label="专业能力描述" placeholder="客户投资分析&#10;收益归因&#10;交易行为分析&#10;风险分析"></textarea>
          </section>
          <section class="remote-step">
            <h3><span>3</span> 允许使用的知识</h3>
            <p>选择分析时允许 Runtime 提供的知识。至少选择一个已发布的 Skill；实际可用范围还受调用用户权限约束。</p>
            <div class="remote-grant-grid">
              <section class="remote-grant-section">
                <div class="agent-resource-selector">
                  <div class="agent-resource-selector-copy"><strong>知识文档</strong><span>按知识库中的文档逐项选择。</span></div>
                  <div class="agent-resource-selector-action">
                    <span :class="{ 'is-selected': remoteForm.selectedDocumentIds.length }">{{ remoteForm.selectedDocumentIds.length ? `已选 ${remoteForm.selectedDocumentIds.length} 份` : '未选择文档' }}</span>
                    <button type="button" class="agent-picker-text-button" :aria-expanded="remoteDocumentPickerOpen" @click="remoteDocumentPickerOpen = !remoteDocumentPickerOpen">{{ remoteDocumentPickerOpen ? '收起选择' : '选择文档' }} <span aria-hidden="true">›</span></button>
                  </div>
                </div>
                <div v-if="remoteForm.selectedDocumentIds.length" class="remote-selected-list"><span v-for="id in remoteForm.selectedDocumentIds" :key="id">✓ {{ remoteDocumentLabel(id) }}</span></div>
                <div v-if="remoteDocumentPickerOpen" class="remote-picker-panel">
                  <input v-model.trim="remoteDocSearch" type="search" placeholder="搜索文档名称或 ID">
                  <div class="agent-document-checklist">
                    <label v-for="document in remoteDocumentOptions" :key="document.docId" class="agent-document-check" :class="{ active: remoteForm.selectedDocumentIds.includes(document.docId) }">
                      <input v-model="remoteForm.selectedDocumentIds" type="checkbox" :value="document.docId">
                      <span><strong>{{ document.title || document.fileName || document.docId }}</strong><small>{{ document.category || '未分类' }}</small></span>
                    </label>
                  </div>
                  <small v-if="!remoteDocumentOptions.length">暂无可选文档</small>
                </div>
              </section>
              <section class="remote-grant-section">
                <div class="agent-resource-selector">
                  <div class="agent-resource-selector-copy"><strong>Skills</strong><span>选择已发布的分析 Skill。</span></div>
                  <div class="agent-resource-selector-action">
                    <span :class="{ 'is-selected': remoteForm.selectedSkillIds.length }">{{ remoteForm.selectedSkillIds.length ? `已选 ${remoteForm.selectedSkillIds.length} 个` : '未选择 Skill' }}</span>
                    <button type="button" class="agent-picker-text-button" :aria-expanded="remoteSkillPickerOpen" @click="remoteSkillPickerOpen = !remoteSkillPickerOpen">{{ remoteSkillPickerOpen ? '收起选择' : '选择 Skill' }} <span aria-hidden="true">›</span></button>
                  </div>
                </div>
                <div v-if="remoteForm.selectedSkillIds.length" class="remote-selected-list"><span v-for="id in remoteForm.selectedSkillIds" :key="id">✓ {{ remoteSkillLabel(id) }}</span></div>
                <div v-if="remoteSkillPickerOpen" class="remote-picker-panel">
                  <div class="remote-search-row"><input v-model.trim="remoteSkillSearch" type="search" placeholder="搜索已发布 Skill" @keyup.enter.prevent="searchRemoteSkills"><button type="button" class="secondary-button" @click="searchRemoteSkills">查找</button></div>
                  <div class="agent-document-checklist">
                    <label v-for="skill in remoteSkillOptions" :key="skill.value" class="agent-document-check" :class="{ active: remoteForm.selectedSkillIds.includes(skill.value) }">
                      <input v-model="remoteForm.selectedSkillIds" type="checkbox" :value="skill.value"><span><strong>{{ skill.label || skill.value }}</strong><small>已发布 Skill</small></span>
                    </label>
                  </div>
                  <small v-if="!remoteSkillOptions.length">暂无可选 Skill</small>
                </div>
              </section>
            </div>
            <label class="remote-choice remote-supplement-choice"><input v-model="remoteForm.allowDocumentSupplement" type="checkbox"><span>资料不足时，允许 Runtime 查询其他已授权文档</span></label>
          </section>
          <section class="remote-step">
            <h3><span>4</span> MCP 工具</h3>
            <div class="agent-resource-selector">
              <div class="agent-resource-selector-copy"><strong>已注册 MCP 工具</strong><span>选择 Runtime 为分析准备证据时可编排的工具；实际调用仍受 Skill 和角色授权约束。</span></div>
              <div class="agent-resource-selector-action"><span :class="{ 'is-selected': remoteSelectedToolNames.length }">{{ remoteSelectedToolNames.length ? `已选 ${remoteSelectedToolNames.length} 个工具` : '未选择工具' }}</span><button type="button" class="agent-picker-text-button" @click="openRemoteToolPicker">{{ remoteSelectedToolNames.length ? '调整选择' : '选择工具' }} <span aria-hidden="true">›</span></button></div>
            </div>
            <div v-if="remoteSelectedToolNames.length" class="agent-workflow-builder remote-mcp-workflow">
              <div class="agent-tool-picker-head"><div><strong>MCP 工具编排</strong><span>由 Runtime 按步骤准备证据，远端 Agent 不直接调用工具。</span></div><label class="workflow-enable"><input v-model="remoteForm.autoMcp" type="checkbox"><span>启用</span></label></div>
              <div v-if="remoteForm.autoMcp" class="workflow-strategy">
                <label><span>执行模式</span><select v-model="remoteForm.workflowConfig.executionStrategy.mode"><option value="sequential">顺序执行</option><option value="hybrid">混合执行</option><option value="parallel">并行优先</option></select></label>
                <label><span>最大步骤（上限）</span><input v-model.number="remoteForm.workflowConfig.executionStrategy.maxSteps" type="number" min="1" max="50"></label>
                <label><span>成本预算上限（额度）</span><input v-model.number="remoteForm.workflowConfig.executionStrategy.costBudget" type="number" min="0" max="1000000" step="0.1"></label>
                <label><span>时延预算上限（毫秒）</span><input v-model.number="remoteForm.workflowConfig.executionStrategy.latencyBudgetMs" type="number" min="1000" max="3600000" step="1000"></label>
                <label><span>工具失败重试次数</span><input v-model.number="remoteForm.workflowConfig.executionStrategy.toolRetryAttempts" type="number" min="0" max="5"></label>
                <label class="checkbox-row"><input v-model="remoteForm.workflowConfig.executionStrategy.stopOnError" type="checkbox"><span>失败后停止</span></label>
                <label class="checkbox-row"><input v-model="remoteForm.workflowConfig.executionStrategy.allowParallel" type="checkbox"><span>允许并行</span></label>
              </div>
              <div v-if="remoteForm.autoMcp" class="workflow-step-list">
                <article v-for="(step, index) in remoteWorkflowSteps" :key="step.tool" class="workflow-step-row">
                  <div class="workflow-step-order"><strong>{{ index + 1 }}</strong><div><button type="button" :disabled="index === 0" title="上移" @click="moveRemoteWorkflowStep(index, -1)">↑</button><button type="button" :disabled="index === remoteWorkflowSteps.length - 1" title="下移" @click="moveRemoteWorkflowStep(index, 1)">↓</button></div></div>
                  <div class="workflow-step-main"><header><strong>{{ step.tool }}</strong><label><input v-model="step.required" type="checkbox"><span>必需</span></label></header>
                    <div class="workflow-step-controls"><label><span>确认策略</span><select v-model="step.confirmation"><option value="inherit_policy">继承策略</option><option value="auto_execute">自动执行</option><option value="ask_before_execute">执行前确认</option><option value="deny">禁止执行</option></select></label><label><span>条件表达式</span><input v-model.trim="step.condition" placeholder="例如 asset_total &gt; 1000000"></label></div>
                    <div v-if="remoteSelectedToolNames.length > 1" class="workflow-dependencies"><span>前置依赖</span><div class="workflow-dependency-picker"><select value="" @change="addRemoteWorkflowDependency(step, $event.target.value); $event.target.value = ''"><option value="">选择前置依赖</option><option v-for="name in availableRemoteWorkflowDependencies(step)" :key="`${step.tool}-${name}`" :value="name">{{ name }}</option></select><div v-if="workflowStepDependencies(step).length" class="workflow-dependency-tags"><button v-for="name in workflowStepDependencies(step)" :key="`${step.tool}-${name}-dependency`" type="button" title="移除前置依赖" @click="removeRemoteWorkflowDependency(step, name)"><span>{{ name }}</span><strong>x</strong></button></div></div></div>
                  </div>
                </article>
              </div>
              <span class="remote-hint">工具执行仍受只读策略、Skill 绑定和调用用户权限控制。</span>
            </div>
          </section>
          <section class="remote-step">
            <h3><span>5</span> 默认分析要求</h3>
            <textarea v-model.trim="remoteForm.defaultInstruction" rows="4" maxlength="1000" aria-label="默认分析要求"></textarea>
          </section>
          <p v-if="remoteError" class="agent-error" role="alert">{{ remoteError }}</p>
          <details v-if="remoteDiagnostic && isPlatformAdmin" class="remote-tool-details"><summary>查看诊断详情</summary><p>{{ remoteDiagnostic }}</p></details>
        </div>
        <footer>
          <button type="button" class="secondary-button" :disabled="remoteBusy" @click="remoteDialogOpen = false">取消</button>
          <button type="submit" class="primary-button" :disabled="remoteBusy || !remotePreview">{{ remoteBusy ? '正在保存…' : '保存并启用' }}</button>
        </footer>
      </form>
    </div>

    <div v-if="dialogOpen" class="agent-dialog-backdrop">
      <form class="agent-dialog" @submit.prevent="saveAgent">
        <header>
          <div>
            <p>{{ dialogMode === "create" ? "新增Agent" : "Agent设置" }}</p>
            <h2>{{ dialogMode === "create" ? "创建自研 Agent" : form.name || form.id }}</h2>
          </div>
          <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" :disabled="saving" @click="closeDialog">×</button>
        </header>

        <div class="dialog-body">
          <section v-if="dialogMode === 'create'" class="wide-field">
            <strong>选择算力来源</strong>
            <p>当前创建自研 Agent，可组合 Workflow、Skills 与知识证据；集团或第三方 Agent 通过 A2A 接入，由 Runtime 管理数据边界与补证。</p>
            <button v-if="isPlatformAdmin" type="button" class="secondary-button" @click="dialogOpen = false; openRemoteDialog()">切换到接入专有分析 Agent</button>
          </section>
          <label>
            <span>Agent ID</span>
            <input
              v-model.trim="form.id"
              :disabled="dialogMode === 'edit'"
              pattern="[a-z0-9_-]{2,64}"
              placeholder="industry_research"
              required
            >
          </label>
          <label>
            <span>Agent名称</span>
            <input v-model.trim="form.name" placeholder="行业研究助手" required>
          </label>
          <label>
            <span>运行模式</span>
            <select v-model="form.defaultMode">
              <option value="role_chat">角色问答（不调用 MCP 工具）</option>
              <option value="agent_chat">工具智能体（MCP / API / SQL）</option>
            </select>
            <small v-if="form.defaultMode === 'role_chat'">
              Runtime 将加载角色、会话、响应规则和可选知识上下文，直接调用模型，不进入工具规划与执行链路。
            </small>
            <small v-else>Runtime 将根据当前 Agent 绑定的工具执行规划、调用和证据汇总。</small>
            <small>此处控制普通对话。参与多 Agent 联邦分析时，自研 Agent 支持领域推理与受控自主执行；联邦调用不会直接执行这里绑定的工具，补证操作统一由 Runtime 授权。</small>
          </label>
          <label class="checkbox-row default-agent-row">
            <input v-model="form.defaultAgent" type="checkbox">
            <span>设为默认Agent</span>
          </label>
          <label>
            <span>绑定模型</span>
            <select v-model="form.modelName" :disabled="models.length === 0">
              <option v-if="models.length === 0" value="">后端未返回可用模型</option>
              <option v-for="model in models" :key="model.value" :value="model.value">
                {{ model.label || model.value }}
              </option>
            </select>
            <small>候选项由后端从 defaultChatModel、availableChatModels 和 chatModels 配置合并返回。</small>
          </label>
          <label v-if="form.defaultMode === 'agent_chat'" class="runtime-environment-field">
            <span>运行环境</span>
            <select v-model="form.workflowConfig.runtimeEnvironment">
              <option value="">未指定（跟随资产）</option>
              <option value="DEV">DEV</option>
              <option value="TEST">TEST</option>
              <option value="UAT">UAT</option>
              <option value="PROD">PROD</option>
            </select>
            <small>与 MCP 资产环境一致；设置后作为计划和工具执行的权威环境。</small>
          </label>
          <label>
            <span>标签</span>
            <input v-model="form.skillTags" placeholder="投研, 风控, 财报">
          </label>
          <label class="wide-field">
            <span>业务描述</span>
            <textarea v-model.trim="form.description" rows="2"></textarea>
          </label>
          <label class="wide-field">
            <span>业务场景</span>
            <textarea v-model="form.usageScenarios" rows="3" placeholder="每行一个场景"></textarea>
          </label>
          <label class="wide-field">
            <span>系统提示词</span>
            <textarea v-model.trim="form.systemPrompt" rows="5"></textarea>
          </label>
          <label class="wide-field">
            <span>首次问候</span>
            <textarea v-model.trim="form.firstUseGreeting" rows="2"></textarea>
          </label>
          <label class="wide-field">
            <span>快捷问题</span>
            <textarea v-model="form.quickQuestions" rows="3" placeholder="每行一个问题"></textarea>
          </label>
          <section class="agent-resource-selector wide-field">
            <div class="agent-resource-selector-copy">
              <strong>知识文档</strong>
              <span>统一选择知识文档和已发布的领域技能。</span>
            </div>
            <div class="agent-resource-selector-action">
              <span :class="{ 'is-selected': selectedResourceCount }">
                {{ selectedResourceCount ? `已选 ${selectedResourceCount} 项资源` : "未选择文档" }}
              </span>
              <button type="button" class="agent-picker-text-button" @click="openDocumentPicker">
                {{ selectedResourceCount ? "调整选择" : "选择文档" }}
                <span aria-hidden="true">›</span>
              </button>
            </div>
          </section>
          <section v-if="form.defaultMode === 'agent_chat'" class="default-data-asset-settings wide-field">
            <div class="default-data-asset-heading">
              <strong>数据库资产绑定</strong>
              <span>仅支持绑定数据库资产；启用后，Agent 的数据库检索与执行将固定使用该资产，不会切换到其他数据库。</span>
            </div>
            <label class="checkbox-row">
              <input v-model="form.defaultDataAssetEnabled" type="checkbox">
              <span>启用数据库资产绑定</span>
            </label>
            <label>
              <span>数据库资产名称</span>
              <input
                v-model.trim="form.defaultDataAssetName"
                :disabled="!form.defaultDataAssetEnabled"
                placeholder="例如：客户经营分析数据库"
              >
            </label>
          </section>
          <section v-if="form.defaultMode === 'agent_chat'" class="agent-resource-selector wide-field">
            <div class="agent-resource-selector-copy">
              <strong>已注册 MCP 工具</strong>
              <span>按当前 Agent 的业务范围选择可调用工具。</span>
            </div>
            <div class="agent-resource-selector-action">
              <span :class="{ 'is-selected': selectedToolNames.length }">
                {{ selectedToolNames.length ? `已选 ${selectedToolNames.length} 个工具` : "未选择工具" }}
              </span>
              <button type="button" class="agent-picker-text-button" @click="openToolPicker">
                {{ selectedToolNames.length ? "调整选择" : "选择工具" }}
                <span aria-hidden="true">›</span>
              </button>
            </div>
          </section>

          <section v-if="form.defaultMode === 'agent_chat' && selectedToolNames.length" class="agent-workflow-builder wide-field">
            <div class="agent-tool-picker-head">
              <div>
                <strong>MCP 工具编排</strong>
                <span>按当前 Agent 的勾选工具配置执行顺序、依赖和确认节点</span>
              </div>
              <label class="workflow-enable">
                <input v-model="form.workflowConfig.enabled" type="checkbox">
                <span>启用</span>
              </label>
            </div>
            <div class="workflow-budget-hint">
              智能决策可按任务使用更小预算，但不得超过以下 Agent 配置上限。
            </div>
            <div class="workflow-strategy">
              <label>
                <span>执行模式</span>
                <select v-model="form.workflowConfig.executionStrategy.mode">
                  <option value="sequential">顺序执行</option>
                  <option value="hybrid">混合执行</option>
                  <option value="parallel">并行优先</option>
                </select>
              </label>
              <label>
                <span>最大步骤（上限）</span>
                <input v-model.number="form.workflowConfig.executionStrategy.maxSteps" type="number" min="1" max="50" step="1">
              </label>
              <label>
                <span>成本预算上限（额度）</span>
                <input
                  v-model.number="form.workflowConfig.executionStrategy.costBudget"
                  type="number"
                  min="0"
                  max="1000000"
                  step="0.1"
                >
              </label>
              <label>
                <span>时延预算上限（毫秒）</span>
                <input
                  v-model.number="form.workflowConfig.executionStrategy.latencyBudgetMs"
                  type="number"
                  min="1000"
                  max="3600000"
                  step="1000"
                >
              </label>
              <label>
                <span>工具失败重试次数</span>
                <input
                  v-model.number="form.workflowConfig.executionStrategy.toolRetryAttempts"
                  type="number"
                  min="0"
                  max="5"
                >
              </label>
              <label class="checkbox-row">
                <input v-model="form.workflowConfig.executionStrategy.stopOnError" type="checkbox">
                <span>失败后停止</span>
              </label>
              <label class="checkbox-row">
                <input v-model="form.workflowConfig.executionStrategy.allowParallel" type="checkbox">
                <span>允许并行</span>
              </label>
            </div>
            <div class="workflow-step-list">
              <article v-for="(step, index) in workflowSteps" :key="step.tool" class="workflow-step-row">
                <div class="workflow-step-order">
                  <strong>{{ index + 1 }}</strong>
                  <div>
                    <button type="button" :disabled="index === 0" title="上移" @click="moveWorkflowStep(index, -1)">↑</button>
                    <button type="button" :disabled="index === workflowSteps.length - 1" title="下移" @click="moveWorkflowStep(index, 1)">↓</button>
                  </div>
                </div>
                <div class="workflow-step-main">
                  <header>
                    <strong>{{ step.tool }}</strong>
                    <label>
                      <input v-model="step.required" type="checkbox">
                      <span>必需</span>
                    </label>
                  </header>
                  <details class="workflow-tool-description">
                    <summary>工具说明</summary>
                    <textarea
                      :value="workflowToolDescription(step.tool)"
                      rows="2"
                      placeholder="说明模型何时调用、输入要求、输出如何使用"
                      @input="setWorkflowToolDescription(step.tool, $event.target.value)"
                    ></textarea>
                  </details>
                  <div class="workflow-step-controls">
                    <label>
                      <span>确认策略</span>
                      <select v-model="step.confirmation">
                        <option value="inherit_policy">继承策略</option>
                        <option value="auto_execute">自动执行</option>
                        <option value="ask_before_execute">执行前确认</option>
                        <option value="deny">禁止执行</option>
                      </select>
                    </label>
                    <label>
                      <span>条件表达式</span>
                      <input v-model.trim="step.condition" placeholder="例如 asset_total &gt; 1000000">
                    </label>
                  </div>
                  <div v-if="selectedToolNames.length > 1" class="workflow-dependencies">
                    <span>前置依赖</span>
                    <div class="workflow-dependency-picker">
                      <select
                        value=""
                        @change="addWorkflowDependency(step, $event.target.value); $event.target.value = ''"
                      >
                        <option value="">选择前置依赖</option>
                        <option
                          v-for="toolName in availableWorkflowDependencies(step)"
                          :key="`${step.tool}-${toolName}`"
                          :value="toolName"
                        >
                          {{ toolName }}
                        </option>
                      </select>
                      <div v-if="workflowStepDependencies(step).length" class="workflow-dependency-tags">
                        <button
                          v-for="toolName in workflowStepDependencies(step)"
                          :key="`${step.tool}-${toolName}-dependency`"
                          type="button"
                          title="移除前置依赖"
                          @click="removeWorkflowDependency(step, toolName)"
                        >
                          <span>{{ toolName }}</span>
                          <strong>x</strong>
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
              </article>
            </div>
          </section>

          <section v-if="form.defaultMode === 'agent_chat'" class="routing-settings wide-field">
            <label class="checkbox-row">
              <input v-model="form.routingSettings.smartSelectionEnabled" type="checkbox">
              <span>启用智能工具选择</span>
            </label>
            <label class="checkbox-row">
              <input v-model="form.routingSettings.limitParallelCalls" type="checkbox">
              <span>限制并行调用</span>
            </label>
            <label>
              <span>最大并行数</span>
              <input v-model.number="form.routingSettings.maxParallelCalls" type="number" min="1" max="10">
            </label>
            <label>
              <span>最大相关 MCP 工具数</span>
              <input v-model.number="form.routingSettings.maxRelevantMcpTools" type="number" min="1" max="20">
            </label>
          </section>
        </div>

        <p v-if="dialogError" class="agent-error">{{ dialogError }}</p>

        <footer>
          <button type="button" class="secondary-button" :disabled="saving" @click="closeDialog">取消</button>
          <button type="submit" class="primary-button" :disabled="saving">
            {{ saving ? "保存中" : "保存" }}
          </button>
        </footer>
      </form>

      <div
        v-if="documentPickerOpen"
        class="agent-resource-dialog-backdrop"
        role="presentation"
        @click.self="closeDocumentPicker"
        @keydown.esc="closeDocumentPicker"
      >
        <section
          class="agent-resource-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="agent-document-picker-title"
        >
          <header>
            <div>
              <p>Agent 设置</p>
              <h2 id="agent-document-picker-title">选择文档</h2>
              <span>知识文档与已发布领域技能分开绑定，选择会在保存 Agent 后生效。</span>
            </div>
            <button type="button" class="app-dialog-close" aria-label="关闭文档选择" title="关闭" @click="closeDocumentPicker">×</button>
          </header>

          <div class="agent-resource-dialog-body">
            <div v-if="normalizedDocuments.length" class="agent-document-searchbar">
              <label>
                <span>搜索已有文档</span>
                <input
                  v-model.trim="documentSearchQuery"
                  type="search"
                  placeholder="搜索文档名称、标签、来源或 ID"
                  autofocus
                >
              </label>
              <label>
                <span>业务分类</span>
                <select v-model="documentCategoryFilter">
                  <option v-for="option in documentCategoryOptions" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </option>
                </select>
              </label>
              <label>
                <span>文档类型</span>
                <select v-model="documentTypeFilter">
                  <option v-for="option in documentTypeOptions" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </option>
                </select>
              </label>
            </div>
            <div v-if="normalizedDocuments.length" class="agent-document-batchbar">
              <span>解析中或失败的知识文档不可新绑定；领域技能仅展示已发布内容。</span>
              <strong>{{ documentResultLabel }}</strong>
            </div>
            <div v-if="filteredDocuments.length" class="agent-document-checklist">
              <label
                v-for="document in filteredDocuments"
                :key="document.resourceKey"
                class="agent-document-check"
                :class="{ active: resourceSelected(document), disabled: !documentSelectable(document) }"
                :title="document.title"
              >
                <input
                  type="checkbox"
                  :checked="resourceSelected(document)"
                  :disabled="!documentSelectable(document)"
                  @change="toggleDocument(document)"
                >
                <span>
                  <strong>{{ document.title }}</strong>
                  <small>{{ document.category }} · {{ document.documentType }} · {{ documentStatusLabel(document.lifecycleStatus) }}</small>
                  <em>{{ document.source || document.fileName || document.docId }} · {{ documentUpdatedLabel(document) }}</em>
                </span>
              </label>
            </div>
            <p v-else-if="normalizedDocuments.length" class="agent-tool-empty">没有匹配的文档或领域技能，请调整关键词或筛选条件。</p>
            <p v-else class="agent-tool-empty">暂无可选知识文档或已发布领域技能。</p>
          </div>

          <footer>
            <button
              v-if="selectedResourceCount"
              type="button"
              class="agent-resource-clear-button"
              @click="clearSelectedDocuments"
            >
              清空已选
            </button>
            <span v-else></span>
            <button type="button" class="primary-button" @click="closeDocumentPicker">
              完成（已选 {{ selectedResourceCount }} 项）
            </button>
          </footer>
        </section>
      </div>

    </div>

      <div
        v-if="toolPickerOpen"
        class="agent-resource-dialog-backdrop"
        role="presentation"
        @click.self="closeToolPicker"
        @keydown.esc="closeToolPicker"
      >
        <section
          class="agent-resource-dialog agent-tool-selection-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="agent-tool-picker-title"
        >
          <header>
            <div>
              <p>{{ remoteToolPickerOpen ? '专有分析 Agent' : 'Agent 设置' }}</p>
              <h2 id="agent-tool-picker-title">选择已注册 MCP 工具</h2>
              <span>可按服务类型、分组和关键词快速筛选。</span>
            </div>
            <button type="button" class="app-dialog-close" aria-label="关闭工具选择" title="关闭" @click="closeToolPicker">×</button>
          </header>

          <div class="agent-resource-dialog-body">
            <div v-if="registeredMcpTools.length" class="agent-tool-searchbar">
              <label>
                <span>检索工具</span>
                <input
                  v-model.trim="toolSearchQuery"
                  type="search"
                  placeholder="搜索名称、服务、描述、参数、分类或标签"
                  autofocus
                >
              </label>
              <label>
                <span>后端服务类型</span>
                <select v-model="toolBackendServiceTypeFilter">
                  <option v-for="option in mcpBackendServiceTypeOptions" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </option>
                </select>
              </label>
              <label>
                <span>分组方式</span>
                <select v-model="toolGroupMode">
                  <option v-for="option in mcpToolGroupOptions" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </option>
                </select>
              </label>
              <strong>{{ mcpToolGroupSummary }}</strong>
            </div>
            <div v-if="registeredMcpTools.length && filteredMcpTools.length" class="agent-tool-group-list">
              <section v-for="group in mcpToolGroups" :key="group.key" class="agent-tool-group">
                <header>
                  <div>
                    <strong>{{ group.label }}</strong>
                    <span>{{ group.selectedCount }} / {{ group.tools.length }} 已选 · {{ group.subtitle }}</span>
                  </div>
                  <button type="button" class="secondary-button compact-button" @click="toggleToolGroup(group)">
                    {{ isToolGroupFullySelected(group) ? "取消本组" : "选择本组" }}
                  </button>
                </header>
                <div class="agent-tool-checklist">
                  <label
                    v-for="tool in group.tools"
                    :key="tool.localToolName"
                    class="agent-tool-check"
                    :class="{ active: pickerSelectedToolNames.includes(tool.localToolName) }"
                    :title="applicabilityTooltip(tool)"
                  >
                    <input
                      type="checkbox"
                      :checked="pickerSelectedToolNames.includes(tool.localToolName)"
                      @change="toggleTool(tool.localToolName)"
                    >
                    <span>
                      <strong>{{ tool.chineseAlias || tool.displayName || tool.remoteToolName || tool.localToolName }}</strong>
                      <small v-if="tool.chineseAlias" class="agent-tool-english-name">{{ tool.remoteToolName || tool.localToolName }}</small>
                      <small>
                        {{ tool.serviceName || tool.serviceId || "未归属服务" }}
                        · {{ backendServiceTypesLabel(tool) }}
                      </small>
                      <small v-if="tool.applicabilitySummary" class="agent-tool-applicability">
                        适用范围：{{ tool.applicabilitySummary }}
                      </small>
                      <em v-if="!tool.chineseAlias || tool.localToolName !== tool.remoteToolName">{{ tool.localToolName }}</em>
                    </span>
                  </label>
                </div>
              </section>
            </div>
            <p v-else-if="registeredMcpTools.length" class="agent-tool-empty">没有匹配的 MCP 工具，请调整关键词或筛选条件。</p>
            <p v-else class="agent-tool-empty">请先在 MCP 服务完成服务接入和工具注册。</p>
          </div>

          <footer>
            <button
              v-if="pickerSelectedToolNames.length"
              type="button"
              class="agent-resource-clear-button"
              @click="clearSelectedTools"
            >
              清空已选
            </button>
            <span v-else></span>
            <button type="button" class="primary-button" @click="closeToolPicker">
              完成（已选 {{ pickerSelectedToolNames.length }} 个）
            </button>
          </footer>
        </section>
      </div>

    <div v-if="importDialogOpen" class="agent-dialog-backdrop">
      <form class="agent-dialog agent-import-dialog" @submit.prevent="importAgents">
        <header>
          <div>
            <p>批量导入</p>
            <h2>导入 Agent 配置</h2>
          </div>
          <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" :disabled="importing" @click="closeImportDialog">×</button>
        </header>

        <div class="dialog-body agent-import-body">
          <section class="wide-field agent-import-tools">
            <label class="agent-file-button">
              <input type="file" accept=".json,.csv,.tsv,.xlsx,.xls" @change="handleImportFile">
              <span>{{ importFileName || "选择 JSON / 表格文件" }}</span>
            </label>
            <button type="button" class="secondary-button" :disabled="!importText.trim()" @click="refreshImportPreview">
              解析预览
            </button>
            <label class="checkbox-row">
              <input v-model="importOverwriteExisting" type="checkbox">
              <span>覆盖已有自定义 Agent</span>
            </label>
          </section>

          <label class="wide-field">
            <span>粘贴 JSON、CSV 或 TSV 内容</span>
            <textarea
              v-model="importText"
              rows="9"
              placeholder="支持 agentId/id、agentName/name、model/modelName、tags、businessScenarios、quickQuestions 等字段别名"
            ></textarea>
          </label>

          <section class="wide-field agent-import-preview">
            <div class="agent-tool-picker-head">
              <div>
                <strong>导入预览</strong>
                <span>{{ importPreviewLabel }}</span>
              </div>
              <strong v-if="importItems.length">{{ importItems.length }} 个</strong>
            </div>
            <div v-if="importItems.length" class="agent-import-list">
              <article v-for="agent in previewList(importItems, 8)" :key="agent.id">
                <strong>{{ agent.name }}</strong>
                <span>{{ agent.id }}</span>
                <em>{{ agent.skillTags.join(" / ") || "未设置标签" }}</em>
              </article>
            </div>
            <p v-if="importItems.length > 8" class="agent-tool-empty">仅展示前 8 个，确认导入时会处理全部 Agent。</p>
          </section>

          <section v-if="importResults.length" class="wide-field agent-import-results">
            <strong>导入结果</strong>
            <div>
              <span
                v-for="result in importResults"
                :key="`${result.id}-${result.status}`"
                :class="`is-${result.outcome || 'success'}`"
              >
                {{ result.name || result.id }}：{{ result.status }}
              </span>
            </div>
          </section>
        </div>

        <p v-if="importError" class="agent-error">{{ importError }}</p>

        <footer>
          <button type="button" class="secondary-button" :disabled="importing" @click="closeImportDialog">关闭</button>
          <button type="submit" class="primary-button" :disabled="importing || importItems.length === 0">
            {{ importing ? "导入中" : "确认导入" }}
          </button>
        </footer>
      </form>
    </div>

    <div v-if="importNotice.open" class="agent-dialog-backdrop agent-import-notice-backdrop">
      <section class="agent-import-notice" role="dialog" aria-modal="true" aria-labelledby="agent-import-notice-title">
        <header>
          <div>
            <p>批量导入提示</p>
            <h2 id="agent-import-notice-title">{{ importNotice.title }}</h2>
          </div>
          <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" @click="closeImportNotice">×</button>
        </header>
        <div class="agent-import-notice-body" :class="`is-${importNotice.tone}`">
          <span class="agent-import-notice-icon" aria-hidden="true">
            {{ importNotice.tone === "success" ? "✓" : importNotice.tone === "warning" ? "!" : "×" }}
          </span>
          <div>
            <strong>{{ importNotice.message }}</strong>
            <ul v-if="importNotice.details.length">
              <li v-for="(detail, index) in importNotice.details" :key="`${index}-${detail}`">{{ detail }}</li>
            </ul>
          </div>
        </div>
        <footer>
          <button type="button" class="primary-button" @click="closeImportNotice">知道了</button>
        </footer>
      </section>
    </div>
  </section>
</template>

<script src="../js/views/AgentWorkshopView.js"></script>
