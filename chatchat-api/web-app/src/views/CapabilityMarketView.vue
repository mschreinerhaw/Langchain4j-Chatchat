<template>
  <section class="feature-view capability-market-view">
    <header class="market-header">
      <div>
        <p>能力广场</p>
      </div>
    </header>

    <section class="market-toolbar">
      <label class="search-box">
        <span>检索能力</span>
        <input
          v-model.trim="searchQuery"
          type="search"
          placeholder="搜索能力名称、业务场景、标签或工具"
        >
      </label>
      <div class="toolbar-summary">
        <strong>{{ skillTotal }}</strong>
        <span>/ {{ allSkillCount }} 个能力</span>
      </div>
    </section>

    <section class="business-category-strip">
      <button
        v-for="category in categoryOptions"
        :key="category.value"
        type="button"
        :class="{ active: categoryFilter === category.value }"
        @click="selectCategory(category.value)"
      >
        <span>{{ category.label }}</span>
        <strong>{{ category.count }}</strong>
      </button>
    </section>

    <p v-if="error" class="market-error">{{ error }}</p>
    <p v-else-if="loading && allSkillCount === 0" class="market-empty">正在加载后端能力配置...</p>
    <p v-else-if="allSkillCount === 0" class="market-empty">暂无已发布能力，请先在 Agent工坊 发布能力。</p>
    <p v-else-if="skillTotal === 0" class="market-empty">没有匹配的能力，请换一个关键词。</p>

    <div v-else class="feature-grid">
      <article v-for="skill in pagedSkills" :key="skill.value" class="feature-card market-agent-card">
        <div class="card-head">
          <div class="skill-identity">
            <span>{{ skillBadge(skill) }}</span>
            <div>
              <h2>{{ skill.label }}</h2>
              <small>{{ businessTypeLabel(skill) }}</small>
            </div>
          </div>
          <span class="card-kind">已发布</span>
        </div>
        <p class="business-summary">{{ skill.description || "暂无业务说明，请在设置中补充这个能力的适用范围。" }}</p>

        <section v-if="businessScenarios(skill).length" class="business-block">
          <strong>适用业务场景</strong>
          <ul>
            <li v-for="scenario in businessScenarios(skill)" :key="`${skill.value}-${scenario}`">{{ scenario }}</li>
          </ul>
        </section>

        <div v-if="skill.skillTags?.length" class="skill-tags">
          <span v-for="tag in skill.skillTags" :key="`${skill.value}-${tag}`">{{ tag }}</span>
        </div>
        <dl class="skill-meta">
          <div>
            <dt>模式</dt>
            <dd>{{ skill.defaultMode || "-" }}</dd>
          </div>
          <div>
            <dt>模型</dt>
            <dd>{{ skill.modelName || "默认模型" }}</dd>
          </div>
          <div>
            <dt>工具</dt>
            <dd>{{ toolCountLabel(skill) }}</dd>
          </div>
          <div>
            <dt>服务</dt>
            <dd>{{ serviceCountLabel(skill) }}</dd>
          </div>
        </dl>
      </article>
    </div>

    <nav class="market-pagination" aria-label="能力分页">
      <span>{{ skillTotal ? `显示 ${pageStart}-${pageEnd} 个，共 ${skillTotal} 个` : `暂无匹配能力，每页 ${pageSize} 个` }}</span>
      <div>
        <button type="button" :disabled="page <= 1" @click="goPage(page - 1)">上一页</button>
        <button
          v-for="pageNumber in pageButtons"
          :key="pageNumber"
          type="button"
          :class="{ active: pageNumber === page }"
          @click="goPage(pageNumber)"
        >
          {{ pageNumber }}
        </button>
        <button type="button" :disabled="page >= pageCount" @click="goPage(page + 1)">下一页</button>
      </div>
    </nav>

    <div v-if="settingsOpen" class="skill-dialog-backdrop" @click.self="closeSettingsDialog">
      <form class="skill-dialog" @submit.prevent="saveSettings">
        <header>
          <div>
            <p>{{ dialogMode === "create" ? "新增能力" : "能力设置" }}</p>
            <h2>{{ dialogMode === "create" ? "创建后端 Skill" : form.label || form.value }}</h2>
          </div>
          <button type="button" class="dialog-close" :disabled="saving" @click="closeSettingsDialog">×</button>
        </header>

        <div class="dialog-body">
          <label>
            <span>能力 ID</span>
            <input
              v-model.trim="form.value"
              :disabled="dialogMode === 'edit'"
              placeholder="例如 industry_research"
              pattern="[a-z0-9_-]{2,64}"
              required
            >
          </label>
          <label>
            <span>能力名称</span>
            <input v-model.trim="form.label" placeholder="例如 行业分析" required>
          </label>
          <label class="wide-field">
            <span>能力描述</span>
            <textarea v-model.trim="form.description" rows="2" placeholder="一句话说明能力适用范围"></textarea>
          </label>
          <label>
            <span>默认模式</span>
            <select v-model="form.defaultMode">
              <option value="agent_chat">agent_chat</option>
              <option value="llm_chat">llm_chat</option>
              <option value="knowledge_chat">knowledge_chat</option>
            </select>
          </label>
          <label>
            <span>技能标签</span>
            <input v-model="form.skillTags" placeholder="投研, 风控, 财报">
          </label>
          <label class="wide-field">
            <span>使用场景</span>
            <textarea v-model="form.usageScenarios" rows="2" placeholder="每行一个场景"></textarea>
          </label>
          <label class="wide-field">
            <span>首次问候</span>
            <textarea v-model.trim="form.firstUseGreeting" rows="2"></textarea>
          </label>
          <label class="wide-field">
            <span>系统提示词</span>
            <textarea v-model.trim="form.systemPrompt" rows="5"></textarea>
          </label>
          <label class="wide-field">
            <span>快捷问题</span>
            <textarea v-model="form.quickQuestions" rows="3" placeholder="每行一个快捷问题"></textarea>
          </label>
          <label>
            <span>工具前缀</span>
            <input
              v-model="form.preferredToolPrefixes"
              :disabled="activeSkill?.builtin"
              placeholder="工具前缀"
            >
          </label>
          <label>
            <span>MCP 服务 ID</span>
            <input v-model="form.boundMcpServiceIds" placeholder="server-a, server-b">
          </label>
          <label class="wide-field">
            <span>绑定工具名</span>
            <textarea v-model="form.boundMcpToolNames" rows="2" placeholder="每行一个后端工具名"></textarea>
          </label>

          <section class="tool-picker wide-field">
            <div>
              <strong>可用工具</strong>
              <span>{{ tools.length ? `共 ${tools.length} 个` : "后端暂无工具列表" }}</span>
            </div>
            <div v-if="tools.length" class="tool-chips">
              <button
                v-for="tool in visibleTools"
                :key="tool"
                type="button"
                :class="{ active: selectedToolNames.includes(tool) }"
                @click="toggleTool(tool)"
              >
                {{ tool }}
              </button>
            </div>
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
          </section>

          <section v-if="dialogMode === 'edit'" class="version-panel wide-field">
            <div>
              <strong>配置版本</strong>
              <span>{{ versionLoading ? "加载中..." : `${versions.length} 条` }}</span>
            </div>
            <div v-if="versions.length" class="version-list">
              <button
                v-for="version in versions"
                :key="version.id"
                type="button"
                :disabled="saving"
                @click="rollbackVersion(version)"
              >
                {{ version.action }} · {{ formatVersionTime(version.createdAt) }}
              </button>
            </div>
          </section>
        </div>

        <p v-if="dialogError" class="market-error">{{ dialogError }}</p>

        <footer>
          <button type="button" class="secondary-button" :disabled="saving" @click="closeSettingsDialog">取消</button>
          <button type="submit" class="primary-button" :disabled="saving">
            {{ saving ? "保存中" : "保存设置" }}
          </button>
        </footer>
      </form>
    </div>
  </section>
</template>

<script src="../js/views/CapabilityMarketView.js"></script>
