<template>
  <section class="feature-view keyword-rule-center">
    <header class="keyword-rule-hero">
      <div class="keyword-rule-title">
        <h1>Keyword Rule Center</h1>
        <div class="keyword-rule-version">
          <span>Active Version</span>
          <strong class="version-pill intent">Intent v{{ activeIntentVersion }}</strong>
          <strong class="version-pill chunk">Chunk v{{ activeChunkVersion }}</strong>
          <strong class="version-pill expand">Expand v{{ activeExpandVersion }}</strong>
          <strong class="version-pill lexicon">Lexicon v{{ activeLexiconVersion }}</strong>
        </div>
        <p>Last published: {{ lastPublishedAt }}</p>
      </div>

      <div class="keyword-rule-actions">
        <button type="button" class="primary" title="Create rule" @click="openCreateRule">
          <Plus :size="18" stroke-width="2" />
          <span>Create Rule</span>
        </button>
        <button type="button" :disabled="rulesLoading" title="Publish all rules" @click="publishAllRules">
          <Send :size="16" stroke-width="2" />
          <span>Publish All</span>
        </button>
        <button type="button" :disabled="rulesLoading" title="Refresh rule cache" @click="refreshRules">
          <RefreshCw :size="16" stroke-width="2" />
          <span>Refresh</span>
        </button>
      </div>
    </header>

    <p v-if="rulesError" class="agent-runtime-error">{{ rulesError }}</p>

    <section class="keyword-rule-workspace" aria-label="Rule workspace">
      <div class="keyword-rule-main">
        <section class="keyword-rule-metrics" aria-label="Rule overview">
          <article v-for="card in overviewCards" :key="card.key" class="keyword-rule-metric">
            <span :class="['metric-icon', card.tone]">
              <component :is="card.icon" :size="20" stroke-width="2.2" />
            </span>
            <div>
              <p>{{ card.label }}</p>
              <strong>{{ card.value }}</strong>
              <small>Enabled {{ card.enabled }} <i></i> Draft {{ card.draft }}</small>
            </div>
          </article>
        </section>

        <article class="keyword-rule-board">
          <nav class="keyword-rule-tabs" aria-label="Rule categories">
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
              <input v-model.trim="ruleSearch" type="search" placeholder="Search rules..." />
            </label>

            <select v-model="statusFilter" title="Filter by status">
              <option value="all">All Status</option>
              <option value="enabled">Enabled</option>
              <option value="draft">Draft</option>
            </select>

            <select v-model="priorityFilter" title="Filter by priority">
              <option value="all">Priority</option>
              <option value="high">High Priority</option>
              <option value="medium">Medium Priority</option>
              <option value="low">Low Priority</option>
            </select>

            <button type="button" title="Reset filters" @click="resetRuleFilters">
              <SlidersHorizontal :size="16" stroke-width="2" />
            </button>
          </div>

          <div class="keyword-rule-list">
            <article v-for="row in pagedRuleRows" :key="row.key" class="keyword-rule-row">
              <GripVertical class="row-grip" :size="16" stroke-width="2" />
              <div class="row-main">
                <h2>{{ row.title }}</h2>
                <p>{{ row.detailLabel }}: <span>{{ row.detail }}</span></p>
                <small>Priority: {{ row.priority }} <i></i> Created: {{ formatDateTime(row.createdAt) }}</small>
              </div>
              <span :class="['row-status', row.statusClass]">
                {{ row.statusText }}
              </span>
              <div class="row-actions">
              <button type="button" title="Edit rule" @click="openEditRule(row.kind, row.raw)">
                <Pencil :size="16" stroke-width="2" />
              </button>
              <button
                type="button"
                :disabled="row.kind === 'lexicon' && row.raw.builtin"
                :title="row.kind === 'lexicon' && row.raw.builtin ? 'Default lexicon entries cannot be deleted' : 'Delete rule'"
                @click="deleteRule(row.kind, row.raw)"
              >
                <Trash2 :size="16" stroke-width="2" />
              </button>
            </div>
            </article>

            <p v-if="!rulesLoading && pagedRuleRows.length === 0" class="agent-runtime-empty">
              No {{ activeRuleLabel.toLowerCase() }} found.
            </p>
          </div>

          <footer class="keyword-rule-pagination">
            <span>Showing {{ paginationStart }}-{{ paginationEnd }} of {{ filteredRuleRows.length }}</span>
            <div>
              <button type="button" title="Previous page" :disabled="currentPage <= 1" @click="currentPage -= 1">
                <ChevronLeft :size="16" stroke-width="2" />
              </button>
              <strong>{{ currentPage }}</strong>
              <button type="button" title="Next page" :disabled="currentPage >= totalPages" @click="currentPage += 1">
                <ChevronRight :size="16" stroke-width="2" />
              </button>
              <select v-model.number="pageSize" title="Page size">
                <option :value="10">10 / page</option>
                <option :value="20">20 / page</option>
                <option :value="50">50 / page</option>
              </select>
            </div>
          </footer>
        </article>
      </div>

      <aside class="keyword-rule-side">
        <article class="keyword-rule-panel">
          <header>
            <h2>Rule Health <Info :size="13" stroke-width="2" /></h2>
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
            <span>Last Publish</span>
            <strong>{{ lastPublishedAt }}</strong>
          </footer>
        </article>

        <article class="keyword-rule-panel">
          <header>
            <h2>Top Intent Signals <Info :size="13" stroke-width="2" /></h2>
            <button type="button" @click="setActiveTab('intent')">View All</button>
          </header>
          <div class="signal-list">
            <div v-for="item in topIntentSignals" :key="item.key" class="signal-row">
              <span>{{ item.label }}</span>
              <i><b :style="{ width: item.ratio + '%' }"></b></i>
              <strong>{{ formatCount(item.value) }}</strong>
            </div>
            <p v-if="!topIntentSignals.length" class="agent-runtime-empty">No intent signal yet.</p>
          </div>
        </article>

        <article class="keyword-rule-panel">
          <header>
            <h2>Top Expansion Signals <Info :size="13" stroke-width="2" /></h2>
            <button type="button" @click="setActiveTab('expand')">View All</button>
          </header>
          <div class="signal-list">
            <div v-for="item in topExpansionSignals" :key="item.key" class="signal-row">
              <span>{{ item.label }}</span>
              <i><b :style="{ width: item.ratio + '%' }"></b></i>
              <strong>{{ formatCount(item.value) }}</strong>
            </div>
            <p v-if="!topExpansionSignals.length" class="agent-runtime-empty">No expansion signal yet.</p>
          </div>
        </article>
      </aside>
    </section>

    <div v-if="ruleDialogOpen" class="keyword-rule-modal-backdrop" @click.self="closeRuleDialog">
      <form class="keyword-rule-modal" @submit.prevent="saveRule(ruleDialogKind)">
        <header>
          <div>
            <p>Rule Editor</p>
            <h2>{{ ruleDialogTitle }}</h2>
          </div>
          <button type="button" title="Close" @click="closeRuleDialog">
            <X :size="18" stroke-width="2" />
          </button>
        </header>

        <section class="keyword-rule-modal-body">
          <label>
            <span>Rule Type</span>
            <select v-model="ruleDialogKind" :disabled="!!currentRuleForm.id" @change="resetRuleForm(ruleDialogKind)">
              <option value="intent">Intent Rule</option>
              <option value="expand">Expansion Rule</option>
              <option value="chunk">Chunk Rule</option>
              <option value="lexicon">Semantic Lexicon</option>
            </select>
          </label>

          <template v-if="ruleDialogKind === 'intent'">
            <label>
              <span>Intent</span>
              <input v-model.trim="ruleForms.intent.intent" type="text" placeholder="TROUBLESHOOTING" />
            </label>
            <label>
              <span>Name</span>
              <input v-model.trim="ruleForms.intent.name" type="text" placeholder="Login failures" />
            </label>
            <label class="wide">
              <span>Keywords</span>
              <textarea v-model.trim="ruleForms.intent.keywords" rows="4" placeholder="error, failure, timeout"></textarea>
            </label>
            <label class="wide">
              <span>Regex</span>
              <input v-model.trim="ruleForms.intent.regex" type="text" placeholder=".*(error|exception|fail).*" />
            </label>
          </template>

          <template v-else-if="ruleDialogKind === 'chunk'">
            <label>
              <span>Chunk Type</span>
              <input v-model.trim="ruleForms.chunk.chunkType" type="text" placeholder="troubleshooting" />
            </label>
            <label class="wide">
              <span>Keywords</span>
              <textarea v-model.trim="ruleForms.chunk.keywords" rows="4" placeholder="incident, retry, root cause"></textarea>
            </label>
            <label class="wide">
              <span>Regex</span>
              <input v-model.trim="ruleForms.chunk.pattern" type="text" placeholder="\\b(ERROR|WARN)\\b" />
            </label>
          </template>

          <template v-else-if="ruleDialogKind === 'lexicon'">
            <label>
              <span>Term</span>
              <input v-model.trim="ruleForms.lexicon.term" type="text" placeholder="营收" />
            </label>
            <label>
              <span>Mapped Term</span>
              <input v-model.trim="ruleForms.lexicon.mappedTerm" type="text" placeholder="revenue" />
            </label>
            <label>
              <span>Language</span>
              <select v-model="ruleForms.lexicon.language">
                <option value="zh">Chinese</option>
                <option value="en">English</option>
                <option value="bilingual">Bilingual</option>
              </select>
            </label>
            <label>
              <span>Domain</span>
              <input v-model.trim="ruleForms.lexicon.domain" type="text" placeholder="finance" />
            </label>
            <label>
              <span>Category</span>
              <input v-model.trim="ruleForms.lexicon.category" type="text" placeholder="metric" />
            </label>
            <label class="wide">
              <span>Aliases</span>
              <textarea v-model.trim="ruleForms.lexicon.aliases" rows="4" placeholder="收入, sales, turnover"></textarea>
            </label>
          </template>

          <template v-else>
            <label>
              <span>Intent</span>
              <input v-model.trim="ruleForms.expand.intent" type="text" placeholder="optional" />
            </label>
            <label>
              <span>Source Word</span>
              <input v-model.trim="ruleForms.expand.sourceWord" type="text" placeholder="login" />
            </label>
            <label class="wide">
              <span>Expand Words</span>
              <textarea v-model.trim="ruleForms.expand.expandWords" rows="4" placeholder="auth, signin, access"></textarea>
            </label>
          </template>

          <label>
            <span>Weight</span>
            <input v-model.number="currentRuleForm.weight" type="number" min="1" />
          </label>
          <label>
            <span>Priority</span>
            <input v-model.number="currentRuleForm.priority" type="number" />
          </label>
          <label class="keyword-rule-check">
            <input v-model="currentRuleForm.enabled" type="checkbox" />
            <span>Enabled</span>
          </label>
        </section>

        <footer>
          <button type="button" title="Cancel" @click="closeRuleDialog">Cancel</button>
          <button type="submit" class="primary" :disabled="rulesLoading" title="Save rule">
            <Save :size="16" stroke-width="2" />
            <span>{{ currentRuleForm.id ? "Update Rule" : "Create Rule" }}</span>
          </button>
        </footer>
      </form>
    </div>
  </section>
</template>

<script src="../js/views/RetrievalRulesView.js"></script>
