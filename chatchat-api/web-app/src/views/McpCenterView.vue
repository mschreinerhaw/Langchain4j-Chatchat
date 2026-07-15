<template>
  <section class="feature-view skill-hub-view mcp-center-view">
    <header class="mcp-header">
      <div>
        <p>MCP服务</p>
      </div>
    </header>

    <section class="mcp-summary">
      <article>
        <span>中心端点</span>
        <strong>{{ centerEndpoint }}</strong>
      </article>
      <article>
        <span>服务配置</span>
        <strong>{{ services.length }}</strong>
      </article>
      <article>
        <span>已启用</span>
        <strong>{{ enabledServices.length }}</strong>
      </article>
      <article>
        <span>已注册工具</span>
        <strong>{{ syncedMcpToolCards.length }}</strong>
      </article>
    </section>

    <p v-if="error" class="mcp-error">{{ error }}</p>
    <p v-else-if="syncMessage" class="mcp-message">{{ syncMessage }}</p>
    <p v-else-if="loading && services.length === 0" class="mcp-empty">正在加载 MCP 配置...</p>

    <div v-if="services.length" class="feature-grid compact">
      <article v-for="service in services" :key="service.id" class="feature-card mcp-service-card">
        <div class="mcp-card-head">
          <span>{{ serviceBadge(service) }}</span>
          <div>
            <h2>{{ service.name }}</h2>
            <small>{{ protocolLabel(service) }}</small>
          </div>
          <strong :class="{ off: !service.enabled }">{{ serviceStatusLabel(service) }}</strong>
        </div>
        <p>{{ service.baseUrl }}</p>
        <dl>
          <div>
            <dt>工具数</dt>
            <dd>{{ serviceToolCount(service) }}</dd>
          </div>
          <div>
            <dt>超时</dt>
            <dd>{{ service.timeoutMs }}ms</dd>
          </div>
          <div>
            <dt>更新</dt>
            <dd>{{ formatTime(service.updatedAt) }}</dd>
          </div>
        </dl>
      </article>
    </div>

    <p v-else-if="!loading && !error" class="mcp-empty">暂无 MCP 服务配置，请先同步中心。</p>

    <section class="mcp-tools">
      <div class="mcp-tools-head">
        <div>
          <h2>已注册工具</h2>
          <span>{{ toolFilterSummary }}</span>
        </div>
        <div class="mcp-actions">
          <button type="button" :disabled="loading" title="刷新 MCP 数据" @click="loadMcpCenter">
            <RefreshCw :size="17" />
            <span>{{ loading ? "刷新中" : "刷新" }}</span>
          </button>
          <button type="button" class="primary-action" :disabled="syncing" title="从独立 MCP服务同步" @click="syncCenter">
            <DownloadCloud :size="17" />
            <span>{{ syncing ? "同步中" : "同步中心" }}</span>
          </button>
        </div>
      </div>

      <div class="mcp-tool-filters">
        <label>
          <span>检索工具</span>
          <input
            v-model.trim="toolSearchQuery"
            type="search"
            placeholder="搜索名称、服务、描述、参数或标签"
          >
        </label>
        <label>
          <span>服务</span>
          <select v-model="toolServiceFilter">
            <option v-for="service in toolServiceOptions" :key="service.value" :value="service.value">
              {{ service.label }}（{{ service.count }}）
            </option>
          </select>
        </label>
        <label>
          <span>分组</span>
          <select v-model="toolGroupMode">
            <option v-for="option in toolGroupOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>
      </div>

      <template v-if="toolTotal">
        <div class="mcp-tool-group-list">
          <section v-for="group in pagedToolGroups" :key="group.key" class="mcp-tool-group">
            <header>
              <div>
                <h3>{{ group.label }}</h3>
                <span>{{ group.tools.length }} 个工具 · {{ group.subtitle }}</span>
              </div>
            </header>
            <div class="mcp-tool-grid">
              <article v-for="tool in group.tools" :key="tool.localToolName" class="mcp-tool-card">
                <div class="mcp-tool-card-head">
                  <span>{{ toolBadge(tool) }}</span>
                  <div>
                    <h3>{{ tool.displayName }}</h3>
                    <small>{{ tool.localToolName }}</small>
                  </div>
                  <strong>MCP工具</strong>
                </div>
                <p>{{ tool.description }}</p>
                <dl>
                  <div>
                    <dt>所属服务</dt>
                    <dd>{{ tool.serviceName || "-" }}</dd>
                  </div>
                  <div>
                    <dt>输出</dt>
                    <dd>{{ tool.outputType || "-" }}</dd>
                  </div>
                  <div>
                    <dt>参数</dt>
                    <dd>{{ tool.parameterCount || 0 }} 个</dd>
                  </div>
                </dl>
                <div class="mcp-tool-card-meta">
                  <div v-if="toolMetaLabel(tool) || tool.remoteToolName" class="mcp-tool-state">
                    <span v-if="toolMetaLabel(tool)">{{ toolMetaLabel(tool) }}</span>
                    <span v-if="tool.remoteToolName">远端：{{ tool.remoteToolName }}</span>
                  </div>
                  <div v-if="previewList([...(tool.categories || []), ...(tool.tags || [])], 5).length" class="mcp-tool-tags">
                    <span v-for="tag in previewList([...(tool.categories || []), ...(tool.tags || [])], 5)" :key="`${tool.localToolName}-${tag}`">
                      {{ tag }}
                    </span>
                  </div>
                </div>
                <div class="mcp-tool-card-footer">
                  <button type="button" @click="openToolDetail(tool)">查看详情</button>
                </div>
              </article>
            </div>
          </section>
        </div>

        <div class="mcp-tool-pagination">
          <span>显示 {{ toolPageStart }}-{{ toolPageEnd }} 条，共 {{ toolTotal }} 条</span>
          <div>
            <button type="button" :disabled="toolPage <= 1" @click="previousToolPage">上一页</button>
            <strong>第 {{ toolPage }} / {{ toolPageCount }} 页</strong>
            <button type="button" :disabled="toolPage >= toolPageCount" @click="nextToolPage">下一页</button>
          </div>
        </div>
      </template>
      <p v-else>{{ mcpToolTotal ? "没有匹配的工具，请换一个关键词。" : "暂无已注册 MCP 工具，请先同步中心。" }}</p>
    </section>

    <div v-if="activeTool" class="mcp-tool-detail-backdrop">
      <aside class="mcp-tool-detail-panel">
        <header>
          <div>
            <p>工具详情</p>
            <h2>{{ activeTool.displayName || activeTool.localToolName }}</h2>
            <span>{{ activeTool.localToolName }}</span>
          </div>
          <button type="button" class="app-dialog-close" aria-label="关闭详情" title="关闭详情" @click="closeToolDetail">×</button>
        </header>

        <div class="mcp-tool-detail-body">
          <section class="mcp-tool-detail-section primary">
            <h3>用途</h3>
            <p>{{ activeTool.description || "暂无工具说明。" }}</p>
            <div class="mcp-tool-detail-badges">
              <span v-if="toolMetaLabel(activeTool)">{{ toolMetaLabel(activeTool) }}</span>
              <span v-if="toolSchemaSummary(activeTool)">{{ toolSchemaSummary(activeTool) }}</span>
              <span v-if="activeTool.remoteToolName">远端：{{ activeTool.remoteToolName }}</span>
            </div>
          </section>

          <section class="mcp-tool-detail-section">
            <h3>基础信息</h3>
            <dl class="mcp-tool-detail-list">
              <div v-for="row in toolDetailRows(activeTool)" :key="row[0]">
                <dt>{{ row[0] }}</dt>
                <dd>{{ row[1] }}</dd>
              </div>
            </dl>
          </section>

          <section v-if="previewList([...(activeTool.categories || []), ...(activeTool.tags || [])], 20).length" class="mcp-tool-detail-section">
            <h3>分类标签</h3>
            <div class="mcp-tool-detail-tags">
              <span v-for="tag in previewList([...(activeTool.categories || []), ...(activeTool.tags || [])], 20)" :key="`${activeTool.localToolName}-detail-${tag}`">
                {{ tag }}
              </span>
            </div>
          </section>

          <section class="mcp-tool-detail-section">
            <h3>参数</h3>
            <div v-if="activeTool.parameters?.length" class="mcp-tool-parameter-list">
              <article v-for="parameter in activeTool.parameters" :key="parameter.name">
                <div>
                  <strong>{{ parameter.name }}</strong>
                  <span>{{ parameter.required ? "必填" : "可选" }}</span>
                </div>
                <small>{{ parameter.type || "-" }}</small>
                <p>{{ parameter.description || "暂无参数说明。" }}</p>
              </article>
            </div>
            <p v-else class="mcp-tool-detail-empty">暂无结构化参数说明。</p>
          </section>

          <section v-if="activeToolSchemaText" class="mcp-tool-detail-section">
            <h3>原始入参结构</h3>
            <pre>{{ activeToolSchemaText }}</pre>
          </section>
        </div>
      </aside>
    </div>
  </section>
</template>

<script src="../js/views/McpCenterView.js"></script>
