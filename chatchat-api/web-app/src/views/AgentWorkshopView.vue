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
        <strong>{{ summary.agentCount || 0 }}</strong>
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
          <span>{{ agentTotal }} / {{ summary.agentCount || 0 }} 个</span>
        </div>
        <div class="agent-light-actions">
          <button type="button" class="light-button primary-light" @click="openCreateDialog">新增Agent</button>
          <button type="button" class="light-button" @click="openImportDialog">批量导入</button>
          <button type="button" class="light-button" :disabled="filteredAgents.length === 0" @click="exportAgentsAsJson">
            导出JSON
          </button>
          <button type="button" class="light-button" :disabled="filteredAgents.length === 0" @click="exportAgentsAsTable">
            导出表格
          </button>
          <button type="button" class="light-button" :disabled="loading" @click="loadWorkshop">
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
    <p v-else-if="(summary.agentCount || 0) === 0" class="agent-empty">暂无Agent配置，请先新增一个。</p>
    <p v-else-if="agentTotal === 0" class="agent-empty">没有匹配的Agent，请换一个关键词。</p>

    <div v-else class="feature-grid">
      <article v-for="agent in paginatedAgents" :key="agent.id" class="feature-card agent-card">
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

        <div v-if="agent.defaultAgent || agent.skillTags?.length" class="agent-tags">
          <span v-if="agent.defaultAgent" class="agent-default-tag">默认Agent</span>
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

    <div v-if="dialogOpen" class="agent-dialog-backdrop" @click.self="closeDialog">
      <form class="agent-dialog" @submit.prevent="saveAgent">
        <header>
          <div>
            <p>{{ dialogMode === "create" ? "新增Agent" : "Agent设置" }}</p>
            <h2>{{ dialogMode === "create" ? "创建业务智能体" : form.name || form.id }}</h2>
          </div>
          <button type="button" class="dialog-close" :disabled="saving" @click="closeDialog">×</button>
        </header>

        <div class="dialog-body">
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
            <span>默认模式</span>
            <select v-model="form.defaultMode">
              <option value="agent_chat">agent_chat</option>
              <option value="llm_chat">llm_chat</option>
              <option value="knowledge_chat">knowledge_chat</option>
            </select>
          </label>
          <label class="checkbox-row default-agent-row">
            <input v-model="form.defaultAgent" type="checkbox">
            <span>设为默认Agent</span>
          </label>
          <label>
            <span>绑定模型</span>
            <select v-model="form.modelName">
              <option v-if="!models.length" value="">默认模型</option>
              <option v-for="model in models" :key="model.value" :value="model.value">
                {{ model.label || model.value }}
              </option>
            </select>
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
          <section class="agent-tool-picker wide-field">
            <div class="agent-tool-picker-head">
              <div>
                <strong>已注册MCP工具</strong>
                <span>{{ mcpToolResultLabel }}</span>
              </div>
              <button
                v-if="registeredMcpTools.length"
                type="button"
                class="secondary-button compact-button"
                @click="clearSelectedTools"
              >
                清空
              </button>
            </div>
            <div v-if="registeredMcpTools.length" class="agent-tool-searchbar">
              <label>
                <span>检索工具</span>
                <input
                  v-model.trim="toolSearchQuery"
                  type="search"
                  placeholder="搜索名称、服务、描述、参数、分类或标签"
                >
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
                    :class="{ active: selectedToolNames.includes(tool.localToolName) }"
                    :title="tool.localToolName"
                  >
                    <input
                      type="checkbox"
                      :checked="selectedToolNames.includes(tool.localToolName)"
                      @change="toggleTool(tool.localToolName)"
                    >
                    <span>
                      <strong>{{ tool.displayName || tool.remoteToolName || tool.localToolName }}</strong>
                      <small>{{ tool.serviceName || tool.serviceId || "未归属服务" }}</small>
                      <em>{{ tool.localToolName }}</em>
                    </span>
                  </label>
                </div>
              </section>
            </div>
            <p v-else-if="registeredMcpTools.length" class="agent-tool-empty">没有匹配的MCP工具，请换一个关键词或分组方式。</p>
            <p v-else class="agent-tool-empty">请先在 MCP服务 完成服务接入和工具注册。</p>
          </section>

          <section v-if="selectedToolNames.length" class="agent-workflow-builder wide-field">
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
                <span>最大步骤</span>
                <input v-model.number="form.workflowConfig.executionStrategy.maxSteps" type="number" min="0" max="50">
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
                        <option value="">继承策略</option>
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

          <section class="agent-document-picker wide-field">
            <div class="agent-tool-picker-head">
              <div>
                <strong>知识库文档</strong>
                <span>{{ documentResultLabel }}</span>
              </div>
              <button
                v-if="documents.length"
                type="button"
                class="secondary-button compact-button"
                @click="clearSelectedDocuments"
              >
                清空
              </button>
            </div>
            <div v-if="documents.length" class="agent-document-searchbar">
              <label>
                <span>检索文档</span>
                <input
                  v-model.trim="documentSearchQuery"
                  type="search"
                  placeholder="按标题、来源、ID、分类或标签"
                >
              </label>
              <label>
                <span>分类</span>
                <select v-model="documentCategoryFilter">
                  <option v-for="option in documentCategoryOptions" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </option>
                </select>
              </label>
              <button
                v-if="hasActiveDocumentFilters"
                type="button"
                class="secondary-button compact-button"
                @click="resetDocumentFilters"
              >
                重置
              </button>
            </div>
            <div v-if="documents.length && filteredDocuments.length" class="agent-document-batchbar">
              <label class="agent-document-select-current">
                <input
                  type="checkbox"
                  :checked="isFilteredDocumentsFullySelected"
                  @change="toggleFilteredDocuments"
                >
                <span>{{ documentBatchActionLabel }}</span>
              </label>
              <strong>当前筛选 {{ selectedFilteredDocumentCount }} / {{ filteredDocuments.length }} 已选</strong>
            </div>
            <div v-if="documents.length && visibleDocuments.length" class="agent-document-checklist">
              <label
                v-for="document in visibleDocuments"
                :key="document.docId"
                class="agent-document-check"
                :class="{ active: selectedDocumentIds.includes(document.docId) }"
                :title="document.title"
              >
                <input
                  type="checkbox"
                  :checked="selectedDocumentIds.includes(document.docId)"
                  @change="toggleDocument(document.docId)"
                >
                <span>
                  <strong>{{ document.title }}</strong>
                  <small>{{ document.source }} · {{ document.category || "未分类" }} · {{ document.date }}</small>
                  <em>{{ document.docId }}</em>
                </span>
              </label>
            </div>
            <p v-else-if="documents.length" class="agent-tool-empty">没有匹配的知识库文档，请调整分类或关键词。</p>
            <p v-else class="agent-tool-empty">请先在知识库上传或创建可检索文档。</p>
          </section>

          <section class="routing-settings wide-field">
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
    </div>

    <div v-if="importDialogOpen" class="agent-dialog-backdrop" @click.self="closeImportDialog">
      <form class="agent-dialog agent-import-dialog" @submit.prevent="importAgents">
        <header>
          <div>
            <p>批量导入</p>
            <h2>导入 Agent 配置</h2>
          </div>
          <button type="button" class="dialog-close" :disabled="importing" @click="closeImportDialog">×</button>
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
              @blur="importText.trim() && refreshImportPreview()"
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
              <span v-for="result in importResults" :key="`${result.id}-${result.status}`">
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
  </section>
</template>

<script src="../js/views/AgentWorkshopView.js"></script>
