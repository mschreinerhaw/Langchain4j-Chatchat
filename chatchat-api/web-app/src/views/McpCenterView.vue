<template>
  <section class="feature-view skill-hub-view mcp-center-view">
    <header class="mcp-tools-head mcp-page-heading"
      ><div><h1>MCP 能力</h1><p>按业务用途查找已发布的工具，在 Agent 中选择后即可使用。</p></div
      ><button class="light-button" :disabled="loading" @click="loadMcpCenter"
        ><RefreshCw :size="17" :class="{ spinning: loading }" aria-hidden="true" /><span>{{
          loading ? "刷新中…" : "刷新"
        }}</span></button
      ></header
    >
    <p v-if="error" class="mcp-error" role="alert">{{ error }}</p>
    <details class="mcp-operations"
      ><summary>连接与运维信息（高级）</summary><p>以下信息用于管理员维护服务连接。</p
      ><p>服务地址：{{ centerEndpoint }}</p
      ><button class="light-button" :disabled="syncing" @click="syncCenter">{{
        syncing ? "同步中…" : "同步连接"
      }}</button
      ><p v-if="syncMessage">{{ syncMessage }}</p
      ><div v-for="service in services" :key="service.id"
        ><strong>{{ service.name }}</strong> · {{ serviceStatusLabel(service) }}</div
      ></details
    >
    <section class="mcp-tools mcp-published-catalog">
      <div class="mcp-tools-head"
        ><div
          ><h2>已发布工具</h2><span>{{ toolFilterSummary }}</span></div
        ></div
      >
      <div class="mcp-tool-filters">
        <label
          ><span>搜索工具</span
          ><div class="mcp-tool-search-field"
            ><Search :size="17" /><input
              v-model="toolSearchQuery"
              type="search"
              placeholder="搜索工具名称或用途" /></div
        ></label>
        <label
          ><span>业务分类</span
          ><select v-model="toolCategoryFilter" aria-label="业务分类"
            ><option v-for="item in toolCategoryOptions" :key="item.value" :value="item.value"
              >{{ item.label }}（{{ item.count }}）</option
            ></select
          ></label
        >
      </div>
      <p v-if="toolLoading" class="mcp-empty" role="status">正在加载已发布工具…</p>
      <div v-else-if="toolTotal" class="mcp-tool-group-list">
        <section v-for="group in pagedToolGroups" :key="group.key" class="mcp-tool-group">
          <header
            ><h3>{{ group.label }}</h3
            ><span class="mcp-group-count">本页 {{ group.tools.length }} 个工具</span></header
          >
          <div class="mcp-tool-grid"
            ><article v-for="tool in group.tools" :key="tool.localToolName" class="mcp-tool-card">
              <div class="mcp-card-topline">
                <span class="mcp-capability-icon" aria-hidden="true"
                  ><Blocks :size="24" :stroke-width="1.7"
                /></span>
                <span class="mcp-published-status"><i aria-hidden="true"></i>已发布</span>
              </div>
              <div class="mcp-card-copy">
                <h3 :title="tool.chineseAlias || tool.displayName">{{
                  tool.chineseAlias || tool.displayName
                }}</h3>
                <p>{{ tool.description || "在授权范围内查询已选模板" }}</p>
              </div>
              <dl
                ><div
                  ><dt>业务分类</dt
                  ><dd class="mcp-category-value" :title="tool.functionalCategory || '待分类'">{{
                    tool.functionalCategory || "待分类"
                  }}</dd></div
                ><div
                  ><dt><Layers3 :size="13" aria-hidden="true" />包含模板</dt
                  ><dd class="mcp-template-value">{{ tool.templateCount }}<span>项</span></dd></div
                ></dl
              >
              <div class="mcp-tool-card-footer"
                ><button type="button" @click="openToolDetail(tool)"
                  ><span>查看用途</span><ArrowUpRight :size="17" aria-hidden="true" /></button
              ></div> </article
          ></div>
        </section>
      </div>
      <p v-else class="mcp-empty">{{
        mcpToolTotal
          ? "没有匹配的工具，请调整分类或关键词。"
          : "暂无可用的已发布工具，请联系管理员发布并分配使用权限。"
      }}</p>
      <AppPagination
        :page="toolPage"
        :page-size="toolPageSize"
        :total="toolTotal"
        :page-count="toolPageCount"
        :disabled="toolLoading"
        aria-label="已发布工具分页"
        @change="setToolPage"
      />
    </section>
    <div v-if="activeTool" class="mcp-tool-detail-backdrop" @click.self="closeToolDetail"
      ><aside
        ref="toolDetailPanel"
        class="mcp-tool-detail-panel"
        role="dialog"
        aria-modal="true"
        aria-label="工具详情与授权模板"
        tabindex="-1"
        @keydown.esc="closeToolDetail"
        ><header
          ><h2>{{ activeTool.chineseAlias || activeTool.displayName }}</h2
          ><div class="mcp-detail-heading-actions"
            ><button
              type="button"
              class="mcp-detail-advanced-entry"
              :aria-expanded="detailAdvanced"
              aria-controls="mcp-tool-call-information"
              @click="detailAdvanced = !detailAdvanced"
              >{{ detailAdvanced ? "返回模板" : "高级信息" }}</button
            ><button type="button" class="mcp-detail-close" @click="closeToolDetail"
              >关闭</button
            ></div
          ></header
        ><div class="mcp-tool-detail-body"
          ><section
            v-if="detailAdvanced"
            id="mcp-tool-call-information"
            class="mcp-tool-detail-section mcp-call-details"
            aria-label="调用信息"
            ><h3>调用信息</h3><p>工具标识：{{ activeTool.localToolName }}</p
            ><p>来源服务：{{ activeTool.serviceName }}</p
            ><h4 v-if="activeToolSchemaText">入参结构</h4
            ><pre v-if="activeToolSchemaText">{{ activeToolSchemaText }}</pre></section
          ><template v-else
            ><section class="mcp-tool-detail-section primary"
              ><h3>工具用途</h3><p>{{ activeTool.description }}</p
              ><p>业务分类：{{ activeTool.functionalCategory || "待分类" }}</p
              ><p>查看此工具支持的模板，具体可用范围以您的当前授权为准。</p></section
            ><section
              class="mcp-authorized-templates"
              aria-label="已授权模板"
              :aria-busy="detailLoading"
            >
              <div class="mcp-template-heading"
                ><h3
                  >已授权模板
                  <span v-if="!detailLoading && !detailError">{{
                    detailTemplates.length
                  }}</span></h3
                >
                <button
                  type="button"
                  class="mcp-detail-close"
                  :disabled="detailLoading"
                  @click="loadDetailTemplates"
                  >刷新模板</button
                ></div
              >
              <p class="mcp-template-hint">仅展示您当前有权访问的模板，展开即可查看内容。</p>
              <label class="mcp-template-search"
                ><Search :size="18" aria-hidden="true" /><input
                  v-model="detailQuery"
                  type="search"
                  placeholder="搜索模板名称或用途"
                  aria-label="搜索授权模板"
              /></label>
              <p v-if="detailLoading" class="mcp-template-state" role="status">正在加载授权模板…</p>
              <div v-else-if="detailError" class="mcp-template-state" role="alert"
                ><p>{{ detailError }}</p
                ><button type="button" class="mcp-detail-close" @click="loadDetailTemplates"
                  >重新加载</button
                ></div
              >
              <p v-else-if="!filteredDetailTemplates.length" class="mcp-template-state">{{
                detailQuery
                  ? "未找到匹配的模板，请调整搜索内容。"
                  : "当前没有可查看的授权模板，请联系管理员配置模板授权。"
              }}</p>
              <div v-else class="mcp-template-list">
                <details
                  v-for="template in pagedDetailTemplates"
                  :key="template.key"
                  class="mcp-authorized-template"
                >
                  <summary
                    ><span
                      ><strong>{{ template.title }}</strong
                      ><span class="mcp-template-description">{{
                        template.description || "查看模板说明与内容"
                      }}</span></span
                    ><span class="mcp-template-expand">查看内容</span></summary
                  >
                  <div class="mcp-template-content"
                    ><p v-if="template.description">{{ template.description }}</p
                    ><pre v-if="template.content">{{ template.content }}</pre
                    ><p v-else class="mcp-template-hint"
                      >此模板按上述用途提供服务，无额外文本内容。</p
                    ></div
                  >
                </details>
                <AppPagination
                  :page="detailPage"
                  :page-size="6"
                  :total="filteredDetailTemplates.length"
                  :page-count="detailPageCount"
                  aria-label="授权模板分页"
                  @change="detailPage = $event"
                />
              </div> </section></template></div></aside
    ></div>
  </section>
</template>
<script src="../js/views/McpCenterView.js"></script>
