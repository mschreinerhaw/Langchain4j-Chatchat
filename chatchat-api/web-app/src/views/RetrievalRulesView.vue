<template>
  <section class="feature-view keyword-rule-center">
    <header class="keyword-rule-hero">
      <div class="keyword-rule-title">
        <h1>关键词规则中心</h1>
        <div class="keyword-rule-version">
          <span>当前版本</span>
          <strong class="version-pill intent">意图 v{{ activeIntentVersion }}</strong>
          <strong class="version-pill chunk">分片 v{{ activeChunkVersion }}</strong>
          <strong class="version-pill expand">扩展 v{{ activeExpandVersion }}</strong>
          <strong class="version-pill lexicon">词库 v{{ activeLexiconVersion }}</strong>
        </div>
        <p>最近发布：{{ lastPublishedAt }}</p>
      </div>

      <div class="keyword-rule-actions">
        <button type="button" class="primary" title="创建规则" @click="openCreateRule">
          <Plus :size="18" stroke-width="2" />
          <span>创建规则</span>
        </button>
        <button type="button" :disabled="rulesLoading" title="发布全部规则" @click="publishAllRules">
          <Send :size="16" stroke-width="2" />
          <span>发布全部</span>
        </button>
        <button type="button" :disabled="rulesLoading" title="刷新规则缓存" @click="refreshRules">
          <RefreshCw :size="16" stroke-width="2" />
          <span>刷新</span>
        </button>
      </div>
    </header>

    <p v-if="rulesError" class="agent-runtime-error">{{ rulesError }}</p>

    <section class="keyword-rule-workspace" aria-label="规则工作区">
      <div class="keyword-rule-main">
        <section class="keyword-rule-metrics" aria-label="规则概览">
          <article v-for="card in overviewCards" :key="card.key" class="keyword-rule-metric">
            <span :class="['metric-icon', card.tone]">
              <component :is="card.icon" :size="20" stroke-width="2.2" />
            </span>
            <div>
              <p>{{ card.label }}</p>
              <strong>{{ card.value }}</strong>
              <small>已启用 {{ card.enabled }} <i></i> 草稿 {{ card.draft }}</small>
            </div>
          </article>
        </section>

        <article class="keyword-rule-board">
          <nav class="keyword-rule-tabs" aria-label="规则分类">
            <button
              v-for="tab in ruleTabs"
              :key="tab.key"
              type="button"
              :class="{ active: activeRuleTab === tab.key }"
              @click="setActiveTab(tab.key)"
            >
              {{ tab.label }}
            </button>
          </nav>

          <div class="keyword-rule-toolbar">
            <label class="keyword-rule-search">
              <Search :size="16" stroke-width="2" />
              <input v-model.trim="ruleSearch" type="search" placeholder="搜索规则..." />
            </label>

            <select v-model="statusFilter" title="按状态筛选">
              <option value="all">全部状态</option>
              <option value="enabled">已启用</option>
              <option value="draft">草稿</option>
            </select>

            <select v-model="priorityFilter" title="按优先级筛选">
              <option value="all">优先级</option>
              <option value="high">高优先级</option>
              <option value="medium">中优先级</option>
              <option value="low">低优先级</option>
            </select>

            <button type="button" title="重置筛选" @click="resetRuleFilters">
              <SlidersHorizontal :size="16" stroke-width="2" />
            </button>
          </div>

          <div class="keyword-rule-list">
            <article v-for="row in pagedRuleRows" :key="row.key" class="keyword-rule-row">
              <GripVertical class="row-grip" :size="16" stroke-width="2" />
              <div class="row-main">
                <h2>{{ row.title }}</h2>
                <p>{{ row.detailLabel }}: <span>{{ row.detail }}</span></p>
                <small>优先级：{{ row.priority }} <i></i> 创建时间：{{ formatDateTime(row.createdAt) }}</small>
              </div>
              <span :class="['row-status', row.statusClass]">
                {{ row.statusText }}
              </span>
              <div class="row-actions">
              <button type="button" title="编辑规则" @click="openEditRule(row.kind, row.raw)">
                <Pencil :size="16" stroke-width="2" />
              </button>
              <button
                type="button"
                :disabled="row.kind === 'lexicon' && row.raw.builtin"
                :title="row.kind === 'lexicon' && row.raw.builtin ? '默认词库条目不能删除' : '删除规则'"
                @click="deleteRule(row.kind, row.raw)"
              >
                <Trash2 :size="16" stroke-width="2" />
              </button>
            </div>
            </article>

            <p v-if="!rulesLoading && pagedRuleRows.length === 0" class="agent-runtime-empty">
              没有找到{{ activeRuleLabel }}。
            </p>
          </div>

          <footer class="keyword-rule-pagination">
            <span>显示第 {{ paginationStart }}-{{ paginationEnd }} 条，共 {{ filteredRuleRows.length }} 条</span>
            <div>
              <button type="button" title="上一页" :disabled="currentPage <= 1" @click="currentPage -= 1">
                <ChevronLeft :size="16" stroke-width="2" />
              </button>
              <strong>{{ currentPage }}</strong>
              <button type="button" title="下一页" :disabled="currentPage >= totalPages" @click="currentPage += 1">
                <ChevronRight :size="16" stroke-width="2" />
              </button>
              <select v-model.number="pageSize" title="每页条数">
                <option :value="10">10 条/页</option>
                <option :value="20">20 条/页</option>
                <option :value="50">50 条/页</option>
              </select>
            </div>
          </footer>
        </article>
      </div>

      <aside class="keyword-rule-side">
        <article class="keyword-rule-panel">
          <header>
            <h2>规则健康度 <Info :size="13" stroke-width="2" /></h2>
          </header>
          <div class="health-list">
            <div v-for="item in ruleHealthItems" :key="item.key">
              <span :class="['health-icon', item.tone]">
                <component :is="item.icon" :size="15" stroke-width="2.2" />
              </span>
              <p>{{ item.label }}</p>
              <strong>{{ item.value }}</strong>
            </div>
          </div>
          <footer>
            <Clock :size="14" stroke-width="2" />
            <span>最近发布</span>
            <strong>{{ lastPublishedAt }}</strong>
          </footer>
        </article>

        <article class="keyword-rule-panel">
          <header>
            <h2>高频意图信号 <Info :size="13" stroke-width="2" /></h2>
            <button type="button" @click="setActiveTab('intent')">查看全部</button>
          </header>
          <div class="signal-list">
            <div v-for="item in topIntentSignals" :key="item.key" class="signal-row">
              <span>{{ item.label }}</span>
              <i><b :style="{ width: item.ratio + '%' }"></b></i>
              <strong>{{ formatCount(item.value) }}</strong>
            </div>
            <p v-if="!topIntentSignals.length" class="agent-runtime-empty">暂无意图信号。</p>
          </div>
        </article>

        <article class="keyword-rule-panel">
          <header>
            <h2>高频扩展信号 <Info :size="13" stroke-width="2" /></h2>
            <button type="button" @click="setActiveTab('expand')">查看全部</button>
          </header>
          <div class="signal-list">
            <div v-for="item in topExpansionSignals" :key="item.key" class="signal-row">
              <span>{{ item.label }}</span>
              <i><b :style="{ width: item.ratio + '%' }"></b></i>
              <strong>{{ formatCount(item.value) }}</strong>
            </div>
            <p v-if="!topExpansionSignals.length" class="agent-runtime-empty">暂无扩展信号。</p>
          </div>
        </article>
      </aside>
    </section>

    <div v-if="ruleDialogOpen" class="keyword-rule-modal-backdrop" @click.self="closeRuleDialog">
      <form class="keyword-rule-modal" @submit.prevent="saveRule(ruleDialogKind)">
        <header>
          <div>
            <p>规则编辑器</p>
            <h2>{{ ruleDialogTitle }}</h2>
          </div>
          <button type="button" title="关闭" @click="closeRuleDialog">
            <X :size="18" stroke-width="2" />
          </button>
        </header>

        <section class="keyword-rule-modal-body">
          <label>
            <span>规则类型</span>
            <select v-model="ruleDialogKind" :disabled="!!currentRuleForm.id" @change="resetRuleForm(ruleDialogKind)">
              <option value="intent">意图规则</option>
              <option value="expand">扩展规则</option>
              <option value="chunk">分片规则</option>
              <option value="lexicon">语义词库</option>
            </select>
          </label>

          <template v-if="ruleDialogKind === 'intent'">
            <label>
              <span>意图</span>
              <input v-model.trim="ruleForms.intent.intent" type="text" placeholder="故障排查" />
            </label>
            <label>
              <span>名称</span>
              <input v-model.trim="ruleForms.intent.name" type="text" placeholder="登录失败" />
            </label>
            <label class="wide">
              <span>关键词</span>
              <textarea v-model.trim="ruleForms.intent.keywords" rows="4" placeholder="错误, 失败, 超时"></textarea>
            </label>
            <label class="wide">
              <span>正则表达式</span>
              <input v-model.trim="ruleForms.intent.regex" type="text" placeholder=".*(error|exception|fail).*" />
            </label>
          </template>

          <template v-else-if="ruleDialogKind === 'chunk'">
            <label>
              <span>分片类型</span>
              <input v-model.trim="ruleForms.chunk.chunkType" type="text" placeholder="故障排查" />
            </label>
            <label class="wide">
              <span>关键词</span>
              <textarea v-model.trim="ruleForms.chunk.keywords" rows="4" placeholder="故障, 重试, 根因"></textarea>
            </label>
            <label class="wide">
              <span>正则表达式</span>
              <input v-model.trim="ruleForms.chunk.pattern" type="text" placeholder="\\b(ERROR|WARN)\\b" />
            </label>
          </template>

          <template v-else-if="ruleDialogKind === 'lexicon'">
            <label>
              <span>术语</span>
              <input v-model.trim="ruleForms.lexicon.term" type="text" placeholder="营收" />
            </label>
            <label>
              <span>映射术语</span>
              <input v-model.trim="ruleForms.lexicon.mappedTerm" type="text" placeholder="收入" />
            </label>
            <label>
              <span>语言</span>
              <select v-model="ruleForms.lexicon.language">
                <option value="zh">中文</option>
                <option value="en">英文</option>
                <option value="bilingual">双语</option>
              </select>
            </label>
            <label>
              <span>领域</span>
              <input v-model.trim="ruleForms.lexicon.domain" type="text" placeholder="金融" />
            </label>
            <label>
              <span>分类</span>
              <input v-model.trim="ruleForms.lexicon.category" type="text" placeholder="指标" />
            </label>
            <label class="wide">
              <span>别名</span>
              <textarea v-model.trim="ruleForms.lexicon.aliases" rows="4" placeholder="收入, 销售额, 营业额"></textarea>
            </label>
          </template>

          <template v-else>
            <label>
              <span>意图</span>
              <input v-model.trim="ruleForms.expand.intent" type="text" placeholder="可选" />
            </label>
            <label>
              <span>源词</span>
              <input v-model.trim="ruleForms.expand.sourceWord" type="text" placeholder="登录" />
            </label>
            <label class="wide">
              <span>扩展词</span>
              <textarea v-model.trim="ruleForms.expand.expandWords" rows="4" placeholder="认证, 登录, 访问"></textarea>
            </label>
          </template>

          <label>
            <span>权重</span>
            <input v-model.number="currentRuleForm.weight" type="number" min="1" />
          </label>
          <label>
            <span>优先级</span>
            <input v-model.number="currentRuleForm.priority" type="number" />
          </label>
          <label class="keyword-rule-check">
            <input v-model="currentRuleForm.enabled" type="checkbox" />
            <span>启用</span>
          </label>
        </section>

        <footer>
          <button type="button" title="取消" @click="closeRuleDialog">取消</button>
          <button type="submit" class="primary" :disabled="rulesLoading" title="保存规则">
            <Save :size="16" stroke-width="2" />
            <span>{{ currentRuleForm.id ? "更新规则" : "创建规则" }}</span>
          </button>
        </footer>
      </form>
    </div>
  </section>
</template>

<script src="../js/views/RetrievalRulesView.js"></script>
