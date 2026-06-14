<template>
  <section class="feature-view runtime-view">
    <header class="runtime-header">
      <div class="runtime-title">
        <p>Agent Runtime</p>
        <span>面向租户查看任务执行、事件链路与工具治理状态</span>
      </div>
      <div class="runtime-actions">
        <label class="runtime-filter">
          <span>Tenant</span>
          <input v-model.trim="tenantId" type="text" placeholder="tenant-id" @keyup.enter="loadRuntime" />
        </label>
        <button type="button" :disabled="loading" @click="loadRuntime">
          <RefreshCw :size="16" stroke-width="2" />
          <span>{{ loading ? "刷新中" : "刷新" }}</span>
        </button>
      </div>
    </header>

    <p v-if="error" class="runtime-error">{{ error }}</p>

    <div class="runtime-metrics">
      <article v-for="metric in metrics" :key="metric.label">
        <component :is="metric.icon" :size="18" stroke-width="2" />
        <span>{{ metric.label }}</span>
        <strong>{{ metric.value }}</strong>
      </article>
    </div>

    <nav class="runtime-tabs" aria-label="运行时视图">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        :class="{ active: activeTab === tab.key }"
        type="button"
        @click="activateTab(tab.key)"
      >
        <component :is="tab.icon" :size="16" stroke-width="2" />
        <strong>{{ tab.label }}</strong>
        <span>{{ tab.count }}</span>
      </button>
    </nav>

    <section v-if="activeTab === 'tasks'" class="runtime-panel">
      <header>
        <div>
          <p>Task Center</p>
          <h2>任务实例</h2>
        </div>
      </header>

      <div class="runtime-toolbar">
        <label class="runtime-search-field">
          <Search :size="16" stroke-width="2" />
          <input v-model.trim="taskSearchQuery" type="text" placeholder="搜索任务 ID、问题、租户、状态" />
        </label>
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select v-model="statusFilter">
            <option value="">全部状态</option>
            <option v-for="status in statusOptions" :key="status" :value="status">{{ status }}</option>
          </select>
        </label>
      </div>

      <div class="task-table">
        <button
          v-for="task in pagedRows(filteredTasks, 'tasks')"
          :key="task.taskId"
          :class="{ active: selectedTaskId === task.taskId }"
          type="button"
          @click="inspectTask(task)"
        >
          <span class="task-id">{{ shortId(task.taskId) }}</span>
          <span class="task-question">{{ task.question || "未命名任务" }}</span>
          <span class="task-tenant">{{ task.tenantId || "default" }}</span>
          <strong :class="statusClass(task.status)">{{ task.status || "UNKNOWN" }}</strong>
          <time>{{ formatTime(task.updateTime || task.createTime) }}</time>
          <span
            v-if="isActiveTask(task)"
            class="task-kill-action"
            role="button"
            tabindex="0"
            @click.stop="killTask(task)"
            @keydown.enter.stop.prevent="killTask(task)"
          >
            <XCircle :size="14" stroke-width="2.2" />
            {{ isCancellingTask(task) ? "Killing" : "Kill" }}
          </span>
        </button>
        <p v-if="!loading && filteredTasks.length === 0" class="runtime-empty">没有匹配的任务实例</p>
      </div>
      <nav v-if="showRuntimePagination(filteredTasks.length)" class="runtime-pagination" aria-label="浠诲姟鍒嗛〉">
        <span>
          显示 {{ runtimePageStart('tasks', filteredTasks.length) }}-{{ runtimePageEnd('tasks', filteredTasks.length) }}
          条，共 {{ filteredTasks.length }} 条，每页 {{ pageSize }} 条
        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('tasks', filteredTasks.length) <= 1"
            @click="goRuntimePage('tasks', clampedRuntimePage('tasks', filteredTasks.length) - 1, filteredTasks.length)"
          >
            上一页
          </button>
          <button
            v-for="pageNumber in runtimePageButtons('tasks', filteredTasks.length)"
            :key="`tasks-${pageNumber}`"
            type="button"
            :class="{ active: pageNumber === clampedRuntimePage('tasks', filteredTasks.length) }"
            @click="goRuntimePage('tasks', pageNumber, filteredTasks.length)"
          >
            {{ pageNumber }}
          </button>
          <button
            type="button"
            :disabled="clampedRuntimePage('tasks', filteredTasks.length) >= runtimePageCount(filteredTasks.length)"
            @click="goRuntimePage('tasks', clampedRuntimePage('tasks', filteredTasks.length) + 1, filteredTasks.length)"
          >
            下一页
          </button>
        </div>
      </nav>
    </section>

    <section v-else-if="activeTab === 'effects'" class="runtime-panel">
      <header>
        <div>
          <p>Agent Effect</p>
          <h2>鏁堟灉鍒嗘瀽</h2>
        </div>
      </header>

      <div class="runtime-mini-metrics effect-metrics">
        <article v-for="metric in effectMetrics" :key="metric.label">
          <component :is="metric.icon" :size="16" stroke-width="2" />
          <span>{{ metric.label }}</span>
          <strong>{{ metric.value }}</strong>
        </article>
      </div>

      <div class="effect-subtabs" aria-label="鏁堟灉鍒嗘瀽鏄庣粏">
        <button
          type="button"
          :class="{ active: effectActiveTab === 'agents' }"
          @click="effectActiveTab = 'agents'"
        >
          <Activity :size="15" stroke-width="2" />
          <strong>Agent 鑱氬悎</strong>
          <span>{{ agentEffectRows.length }}</span>
        </button>
        <button
          type="button"
          :class="{ active: effectActiveTab === 'lowScores' }"
          @click="effectActiveTab = 'lowScores'"
        >
          <ShieldAlert :size="15" stroke-width="2" />
          <strong>浣庤瘎鍒嗕换鍔</strong>
          <span>{{ lowScoreTasks.length }}</span>
        </button>
      </div>

      <div v-if="reasonMetrics.length > 0" class="reason-metrics">
        <article v-for="reason in pagedRows(reasonMetrics, 'reasonMetrics')" :key="reason.reasonCategory">
          <strong>{{ reason.label }}</strong>
          <span>{{ reason.total }} 鏉</span>
          <small>{{ formatPercent(reason.share) }}</small>
        </article>
      </div>

      <nav v-if="showRuntimePager(reasonMetrics.length)" class="runtime-pagination" aria-label="Effect reason pagination">
        <span>
          鏄剧ず {{ runtimePageStart('reasonMetrics', reasonMetrics.length) }}-{{ runtimePageEnd('reasonMetrics', reasonMetrics.length) }}
          鏉★紝鍏?{{ reasonMetrics.length }} 鏉★紝姣忛〉 {{ pageSize }} 鏉?        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('reasonMetrics', reasonMetrics.length) <= 1"
            @click="goRuntimePage('reasonMetrics', clampedRuntimePage('reasonMetrics', reasonMetrics.length) - 1, reasonMetrics.length)"
          >
            涓婁竴椤?          </button>
          <button
            v-for="pageNumber in runtimePageButtons('reasonMetrics', reasonMetrics.length)"
            :key="`reason-metrics-${pageNumber}`"
            type="button"
            :class="{ active: pageNumber === clampedRuntimePage('reasonMetrics', reasonMetrics.length) }"
            @click="goRuntimePage('reasonMetrics', pageNumber, reasonMetrics.length)"
          >
            {{ pageNumber }}
          </button>
          <button
            type="button"
            :disabled="clampedRuntimePage('reasonMetrics', reasonMetrics.length) >= runtimePageCount(reasonMetrics.length)"
            @click="goRuntimePage('reasonMetrics', clampedRuntimePage('reasonMetrics', reasonMetrics.length) + 1, reasonMetrics.length)"
          >
            涓嬩竴椤?          </button>
        </div>
      </nav>

      <section v-if="effectActiveTab === 'agents'" class="effect-section">
        <header class="subsection-head">
          <strong>Agent 鑱氬悎</strong>
          <span>{{ agentEffectRows.length }} 涓?Agent</span>
        </header>
        <div class="effect-table">
          <article v-for="agent in pagedRows(agentEffectRows, 'agentEffects')" :key="agent.agentId">
            <strong>{{ agent.agentId || "default-agent" }}</strong>
            <span>{{ agent.totalTasks }} 浠诲姟 路 {{ agent.feedbackTasks }} 鍙嶉</span>
            <small>鏈夌敤 {{ formatPercent(agent.usefulRate) }}</small>
            <small>閲囩撼 {{ formatPercent(agent.adoptedRate) }}</small>
            <small>瑙ｅ喅 {{ formatPercent(agent.resolvedRate) }}</small>
            <small>澶辫触 {{ formatPercent(agent.failedRate) }}</small>
          </article>
          <p v-if="agentEffectRows.length === 0" class="runtime-empty">鏆傛棤 Agent 鏁堟灉鏁版嵁</p>
        </div>
        <nav v-if="showRuntimePager(agentEffectRows.length)" class="runtime-pagination" aria-label="Agent effect pagination">
          <span>
            鏄剧ず {{ runtimePageStart('agentEffects', agentEffectRows.length) }}-{{ runtimePageEnd('agentEffects', agentEffectRows.length) }}
            鏉★紝鍏?{{ agentEffectRows.length }} 鏉★紝姣忛〉 {{ pageSize }} 鏉?          </span>
          <div>
            <button
              type="button"
              :disabled="clampedRuntimePage('agentEffects', agentEffectRows.length) <= 1"
              @click="goRuntimePage('agentEffects', clampedRuntimePage('agentEffects', agentEffectRows.length) - 1, agentEffectRows.length)"
            >
              涓婁竴椤?            </button>
            <button
              v-for="pageNumber in runtimePageButtons('agentEffects', agentEffectRows.length)"
              :key="`agent-effects-${pageNumber}`"
              type="button"
              :class="{ active: pageNumber === clampedRuntimePage('agentEffects', agentEffectRows.length) }"
              @click="goRuntimePage('agentEffects', pageNumber, agentEffectRows.length)"
            >
              {{ pageNumber }}
            </button>
            <button
              type="button"
              :disabled="clampedRuntimePage('agentEffects', agentEffectRows.length) >= runtimePageCount(agentEffectRows.length)"
              @click="goRuntimePage('agentEffects', clampedRuntimePage('agentEffects', agentEffectRows.length) + 1, agentEffectRows.length)"
            >
              涓嬩竴椤?            </button>
          </div>
        </nav>
      </section>

      <section v-else class="effect-section">
        <header class="subsection-head">
          <strong>浣庤瘎鍒嗕换鍔</strong>
          <span>{{ lowScoreTasks.length }} 鏉</span>
        </header>
        <div class="low-score-list">
          <button v-for="task in pagedRows(lowScoreTasks, 'lowScores')" :key="task.taskId" type="button" @click="inspectTask(task)">
            <span class="task-id">{{ shortId(task.taskId) }}</span>
            <strong>{{ task.question || "未命名任务" }}</strong>
            <small>
              有用 {{ task.feedbackUseful ? "是" : "否" }} / 采纳 {{ task.feedbackAdopted ? "是" : "否" }} /
              解决 {{ task.feedbackResolved ? "是" : "否" }}
            </small>
            <small v-if="task.feedbackReasonCategory">
              鍘熷洜 {{ formatFeedbackReason(task.feedbackReasonCategory) }}
            </small>
            <p v-if="task.feedbackComment">{{ task.feedbackComment }}</p>
          </button>
          <p v-if="lowScoreTasks.length === 0" class="runtime-empty">鏆傛棤浣庤瘎鍒嗕换鍔</p>
        </div>
        <nav v-if="showRuntimePager(lowScoreTasks.length)" class="runtime-pagination" aria-label="Low score task pagination">
          <span>
            鏄剧ず {{ runtimePageStart('lowScores', lowScoreTasks.length) }}-{{ runtimePageEnd('lowScores', lowScoreTasks.length) }}
            鏉★紝鍏?{{ lowScoreTasks.length }} 鏉★紝姣忛〉 {{ pageSize }} 鏉?          </span>
          <div>
            <button
              type="button"
              :disabled="clampedRuntimePage('lowScores', lowScoreTasks.length) <= 1"
              @click="goRuntimePage('lowScores', clampedRuntimePage('lowScores', lowScoreTasks.length) - 1, lowScoreTasks.length)"
            >
              涓婁竴椤?            </button>
            <button
              v-for="pageNumber in runtimePageButtons('lowScores', lowScoreTasks.length)"
              :key="`low-scores-${pageNumber}`"
              type="button"
              :class="{ active: pageNumber === clampedRuntimePage('lowScores', lowScoreTasks.length) }"
              @click="goRuntimePage('lowScores', pageNumber, lowScoreTasks.length)"
            >
              {{ pageNumber }}
            </button>
            <button
              type="button"
              :disabled="clampedRuntimePage('lowScores', lowScoreTasks.length) >= runtimePageCount(lowScoreTasks.length)"
              @click="goRuntimePage('lowScores', clampedRuntimePage('lowScores', lowScoreTasks.length) + 1, lowScoreTasks.length)"
            >
              涓嬩竴椤?            </button>
          </div>
        </nav>
      </section>
    </section>

    <section v-else-if="activeTab === 'experiences'" class="runtime-panel">
      <header>
        <div>
          <p>Experience Store</p>
          <h2>缁忛獙搴</h2>
        </div>
      </header>

      <div class="experience-subtabs" aria-label="经验库视图">
        <button
          type="button"
          :class="{ active: experienceActiveTab === 'scenarios' }"
          @click="experienceActiveTab = 'scenarios'"
        >
          <strong>鍦烘櫙姒傝</strong>
          <span>{{ experienceScenarios.length }}</span>
        </button>
        <button
          type="button"
          :class="{ active: experienceActiveTab === 'indexes' }"
          @click="experienceActiveTab = 'indexes'"
        >
          <strong>缁撴瀯鍖栫粡楠岀储寮</strong>
          <span>{{ experienceIndexes.length }}</span>
        </button>
        <button
          type="button"
          :class="{ active: experienceActiveTab === 'records' }"
          @click="experienceActiveTab = 'records'"
        >
          <strong>缁忛獙璁板綍</strong>
          <span>{{ experienceItems.length }}</span>
        </button>
      </div>

      <section v-if="experienceActiveTab === 'scenarios'" class="experience-tab-panel">
      <header class="subsection-head experience-subsection-head">
        <strong>鍦烘櫙姒傝</strong>
        <span>{{ experienceScenarios.length }} 鏉</span>
      </header>
      <div class="experience-scenarios">
        <article v-for="scenario in pagedRows(experienceScenarios, 'experienceScenarios')" :key="scenario.scenarioKey || scenario.scenarioName">
          <strong>{{ scenario.scenarioName || scenario.scenarioKey || "-" }}</strong>
          <span>{{ scenario.scenarioKey || "general" }} 路 {{ scenario.total || 0 }} 鏍锋湰</span>
          <small>骞冲潎鍒?{{ scenario.averageScore || 0 }}</small>
        </article>
        <p v-if="experienceScenarios.length === 0" class="runtime-empty">鏆傛棤缁撴瀯鍖栫粡楠岀储寮</p>
      </div>
      <nav v-if="showRuntimePagination(experienceScenarios.length)" class="runtime-pagination" aria-label="Experience scenario pagination">
        <span>
          鏄剧ず {{ runtimePageStart('experienceScenarios', experienceScenarios.length) }}-{{ runtimePageEnd('experienceScenarios', experienceScenarios.length) }}
          鏉★紝鍏?{{ experienceScenarios.length }} 鏉★紝姣忛〉 {{ pageSize }} 鏉?        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('experienceScenarios', experienceScenarios.length) <= 1"
            @click="goRuntimePage('experienceScenarios', clampedRuntimePage('experienceScenarios', experienceScenarios.length) - 1, experienceScenarios.length)"
          >
            涓婁竴椤?          </button>
          <button
            v-for="pageNumber in runtimePageButtons('experienceScenarios', experienceScenarios.length)"
            :key="`experience-scenarios-${pageNumber}`"
            type="button"
            :class="{ active: pageNumber === clampedRuntimePage('experienceScenarios', experienceScenarios.length) }"
            @click="goRuntimePage('experienceScenarios', pageNumber, experienceScenarios.length)"
          >
            {{ pageNumber }}
          </button>
          <button
            type="button"
            :disabled="clampedRuntimePage('experienceScenarios', experienceScenarios.length) >= runtimePageCount(experienceScenarios.length)"
            @click="goRuntimePage('experienceScenarios', clampedRuntimePage('experienceScenarios', experienceScenarios.length) + 1, experienceScenarios.length)"
          >
            涓嬩竴椤?          </button>
        </div>
      </nav>
      </section>

      <section v-else-if="experienceActiveTab === 'indexes'" class="experience-tab-panel">
      <header class="subsection-head experience-subsection-head">
        <strong>缁撴瀯鍖栫粡楠岀储寮</strong>
        <span>{{ experienceIndexes.length }} 鏉</span>
      </header>
      <div class="experience-index-list">
        <article v-for="index in pagedRows(experienceIndexes, 'experienceIndexes')" :key="`index-${index.id}`">
          <header>
            <strong>{{ index.agentId || "default-agent" }}</strong>
            <span>{{ index.scenario }} 路 {{ index.intentType || "general" }}</span>
            <b>{{ formatPercent(index.successRate) }}</b>
          </header>
          <dl>
            <div>
              <dt>宸ュ叿閾</dt>
              <dd>{{ index.toolChain || "-" }}</dd>
            </div>
            <div>
              <dt>鍏抽敭璇</dt>
              <dd>{{ index.keywords || "-" }}</dd>
            </div>
            <div>
              <dt>璁℃暟</dt>
              <dd>
                鏈夌敤 {{ index.usefulCount }} 路 閲囩撼 {{ index.adoptedCount }} 路 瑙ｅ喅 {{ index.resolvedCount }} 路 澶辫触
                {{ index.failedCount }}
              </dd>
            </div>
          </dl>
          <p v-if="index.bestPractice">Best: {{ index.bestPractice }}</p>
          <p v-if="index.avoidPattern">Avoid: {{ index.avoidPattern }}</p>
        </article>
      </div>
      <nav v-if="showRuntimePagination(experienceIndexes.length)" class="runtime-pagination" aria-label="Experience index pagination">
        <span>
          鏄剧ず {{ runtimePageStart('experienceIndexes', experienceIndexes.length) }}-{{ runtimePageEnd('experienceIndexes', experienceIndexes.length) }}
          鏉★紝鍏?{{ experienceIndexes.length }} 鏉★紝姣忛〉 {{ pageSize }} 鏉?        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('experienceIndexes', experienceIndexes.length) <= 1"
            @click="goRuntimePage('experienceIndexes', clampedRuntimePage('experienceIndexes', experienceIndexes.length) - 1, experienceIndexes.length)"
          >
            涓婁竴椤?          </button>
          <button
            v-for="pageNumber in runtimePageButtons('experienceIndexes', experienceIndexes.length)"
            :key="`experience-indexes-${pageNumber}`"
            type="button"
            :class="{ active: pageNumber === clampedRuntimePage('experienceIndexes', experienceIndexes.length) }"
            @click="goRuntimePage('experienceIndexes', pageNumber, experienceIndexes.length)"
          >
            {{ pageNumber }}
          </button>
          <button
            type="button"
            :disabled="clampedRuntimePage('experienceIndexes', experienceIndexes.length) >= runtimePageCount(experienceIndexes.length)"
            @click="goRuntimePage('experienceIndexes', clampedRuntimePage('experienceIndexes', experienceIndexes.length) + 1, experienceIndexes.length)"
          >
            涓嬩竴椤?          </button>
        </div>
      </nav>
      </section>

      <section v-else class="experience-tab-panel">
      <header class="subsection-head experience-subsection-head">
        <strong>缁忛獙璁板綍</strong>
        <span>{{ experienceItems.length }} 鏉</span>
      </header>
      <div class="experience-list">
        <article v-for="experience in pagedRows(experienceItems, 'experiences')" :key="experience.experienceId">
          <header>
            <div>
              <strong>{{ experience.scenarioName || experience.scenarioKey }}</strong>
              <span>{{ experience.agentId || "default-agent" }} 路 {{ shortId(experience.taskId) }}</span>
            </div>
            <b>{{ experience.feedbackScore || 0 }}</b>
          </header>
          <p>{{ experience.attributionSummary || experience.question }}</p>
          <dl>
            <div>
              <dt>褰掑洜</dt>
              <dd>{{ experience.attributionSource || "rule" }}</dd>
            </div>
            <div>
              <dt>鍘熷洜</dt>
              <dd>{{ formatFeedbackReason(experience.feedbackReasonCategory) }}</dd>
            </div>
            <div>
              <dt>鍙嶉</dt>
              <dd>
                有用 {{ experience.feedbackUseful ? "是" : "否" }} / 采纳
                {{ experience.feedbackAdopted ? "是" : "否" }} / 解决
                {{ experience.feedbackResolved ? "是" : "否" }}
              </dd>
            </div>
          </dl>
          <div class="experience-patterns">
            <section>
              <strong>鎴愬姛妯″紡</strong>
              <span v-for="item in experience.successPattern" :key="`success-${experience.experienceId}-${item}`">
                {{ item }}
              </span>
            </section>
            <section>
              <strong>鏀硅繘寤鸿</strong>
              <span
                v-for="item in experience.improvementSuggestions"
                :key="`improve-${experience.experienceId}-${item}`"
              >
                {{ item }}
              </span>
            </section>
          </div>
        </article>
        <p v-if="experienceItems.length === 0" class="runtime-empty">鏆傛棤缁忛獙璁板綍</p>
      </div>
      <nav v-if="showRuntimePagination(experienceItems.length)" class="runtime-pagination" aria-label="Experience item pagination">
        <span>
          鏄剧ず {{ runtimePageStart('experiences', experienceItems.length) }}-{{ runtimePageEnd('experiences', experienceItems.length) }}
          鏉★紝鍏?{{ experienceItems.length }} 鏉★紝姣忛〉 {{ pageSize }} 鏉?        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('experiences', experienceItems.length) <= 1"
            @click="goRuntimePage('experiences', clampedRuntimePage('experiences', experienceItems.length) - 1, experienceItems.length)"
          >
            涓婁竴椤?          </button>
          <button
            v-for="pageNumber in runtimePageButtons('experiences', experienceItems.length)"
            :key="`experiences-${pageNumber}`"
            type="button"
            :class="{ active: pageNumber === clampedRuntimePage('experiences', experienceItems.length) }"
            @click="goRuntimePage('experiences', pageNumber, experienceItems.length)"
          >
            {{ pageNumber }}
          </button>
          <button
            type="button"
            :disabled="clampedRuntimePage('experiences', experienceItems.length) >= runtimePageCount(experienceItems.length)"
            @click="goRuntimePage('experiences', clampedRuntimePage('experiences', experienceItems.length) + 1, experienceItems.length)"
          >
            涓嬩竴椤?          </button>
        </div>
      </nav>
      </section>
    </section>

    <section v-else-if="activeTab === 'events'" class="runtime-panel">
      <header>
        <div>
          <p>Event Store</p>
          <h2>浜嬩欢閾捐矾</h2>
        </div>
        <button type="button" :disabled="!selectedTask || eventsLoading" @click="reloadEvents">
          <Database :size="15" stroke-width="2" />
          <span>{{ eventsLoading ? "读取中" : "读取" }}</span>
        </button>
      </header>

      <div class="runtime-toolbar runtime-toolbar-wide">
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select :value="selectedTaskId" @change="onSelectedTaskChange($event.target.value)">
            <option value="">閫夋嫨浠诲姟</option>
            <option v-for="task in tasks" :key="task.taskId" :value="task.taskId">
              {{ shortId(task.taskId) }} / {{ task.question || "未命名任务" }}
            </option>
          </select>
        </label>
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select v-model="eventTypeFilter">
            <option value="">鍏ㄩ儴浜嬩欢</option>
            <option v-for="type in eventTypeOptions" :key="type" :value="type">{{ type }}</option>
          </select>
        </label>
        <label class="runtime-search-field">
          <Search :size="16" stroke-width="2" />
          <input v-model.trim="eventSearchQuery" type="text" placeholder="搜索事件类型、状态、工具、错误信息" />
        </label>
      </div>

      <div v-if="selectedTaskDisplay" class="selected-task">
        <strong>{{ selectedTaskDisplay.id }}</strong>
        <span>{{ selectedTaskDisplay.subtitle }}</span>
        <p>{{ selectedTaskDisplay.description }}</p>
        <div class="task-feedback">
          <label>
            <input v-model="feedbackDraft.useful" type="checkbox" :disabled="!canRecordFeedback || feedbackSubmitting" />
            鏈夌敤
          </label>
          <label>
            <input v-model="feedbackDraft.adopted" type="checkbox" :disabled="!canRecordFeedback || feedbackSubmitting" />
            宸查噰绾?          </label>
          <label>
            <input v-model="feedbackDraft.resolved" type="checkbox" :disabled="!canRecordFeedback || feedbackSubmitting" />
            宸茶В鍐?          </label>
          <input
            v-model.trim="feedbackDraft.comment"
            type="text"
            :disabled="!canRecordFeedback || feedbackSubmitting"
            maxlength="1000"
            placeholder="鍙嶉澶囨敞"
          />
          <select
            v-model="feedbackDraft.reasonCategory"
            :disabled="!canRecordFeedback || feedbackSubmitting"
            aria-label="鍙嶉鍘熷洜鍒嗙被"
          >
            <option v-for="option in feedbackReasonOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
          <button type="button" :disabled="!canRecordFeedback || feedbackSubmitting" @click="saveTaskFeedback">
            <ShieldCheck :size="14" stroke-width="2" />
            <span>{{ feedbackSubmitting ? "记录中" : "记录反馈" }}</span>
          </button>
        </div>
      </div>

      <div class="event-timeline">
        <article v-for="event in pagedRows(filteredEvents, 'events')" :key="event.eventId">
          <span :class="statusClass(event.status)">{{ event.type }}</span>
          <strong>{{ event.status || "UNKNOWN" }}</strong>
          <time>{{ formatEventTime(event.createTime) }}</time>
        </article>
        <p v-if="!selectedTask" class="runtime-empty">鍏堝湪浠诲姟椤垫垨浠诲姟閫夋嫨鍣ㄤ腑閫変腑涓€涓换鍔</p>
        <p v-else-if="!eventsLoading && filteredEvents.length === 0" class="runtime-empty">娌℃湁鍖归厤鐨勪簨浠惰褰</p>
      </div>
      <nav v-if="showRuntimePager(filteredEvents.length)" class="runtime-pagination" aria-label="Event pagination">
        <span>
          鏄剧ず {{ runtimePageStart('events', filteredEvents.length) }}-{{ runtimePageEnd('events', filteredEvents.length) }}
          鏉★紝鍏?{{ filteredEvents.length }} 鏉★紝姣忛〉 {{ pageSize }} 鏉?        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('events', filteredEvents.length) <= 1"
            @click="goRuntimePage('events', clampedRuntimePage('events', filteredEvents.length) - 1, filteredEvents.length)"
          >
            涓婁竴椤?          </button>
          <button
            v-for="pageNumber in runtimePageButtons('events', filteredEvents.length)"
            :key="`events-${pageNumber}`"
            type="button"
            :class="{ active: pageNumber === clampedRuntimePage('events', filteredEvents.length) }"
            @click="goRuntimePage('events', pageNumber, filteredEvents.length)"
          >
            {{ pageNumber }}
          </button>
          <button
            type="button"
            :disabled="clampedRuntimePage('events', filteredEvents.length) >= runtimePageCount(filteredEvents.length)"
            @click="goRuntimePage('events', clampedRuntimePage('events', filteredEvents.length) + 1, filteredEvents.length)"
          >
            涓嬩竴椤?          </button>
        </div>
      </nav>
    </section>

    <section v-else-if="activeTab === 'tools'" class="runtime-panel">
      <header>
        <div>
          <p>Tool Runtime</p>
          <h2>宸ュ叿娌荤悊</h2>
        </div>
      </header>

      <div class="runtime-toolbar">
        <label class="runtime-search-field">
          <Search :size="16" stroke-width="2" />
          <input v-model.trim="toolSearchQuery" type="text" placeholder="鎼滅储宸ュ叿鍚嶆垨杩愯鎸囨爣" />
        </label>
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select v-model="toolHealthFilter">
            <option value="">鍏ㄩ儴鍋ュ悍搴</option>
            <option value="healthy">{{ formatToolHealth("healthy") }}</option>
            <option value="problem">{{ formatToolHealth("problem") }}</option>
          </select>
        </label>
      </div>

      <div class="runtime-mini-metrics">
        <article v-for="metric in governanceMetrics" :key="metric.label">
          <component :is="metric.icon" :size="16" stroke-width="2" />
          <span>{{ metric.label }}</span>
          <strong>{{ metric.value }}</strong>
        </article>
      </div>

      <div class="tool-table">
        <article v-for="tool in pagedRows(filteredTopTools, 'tools')" :key="tool.toolName">
          <div>
            <strong>{{ tool.toolName }}</strong>
            <small>{{ tool.totalCalls }} 娆¤皟鐢</small>
          </div>
          <span :class="statusClass(toolHealth(tool) === 'problem' ? 'failed' : 'success')">
            {{ toolHealth(tool) === "problem" ? "需关注" : "稳定" }}
          </span>
          <small>
            {{ tool.deniedCalls }} 鎷掔粷 路 {{ tool.rateLimitedCalls }} 闄愭祦 路
            {{ formatDuration(tool.averageDurationMs) }}
          </small>
        </article>
        <p v-if="filteredTopTools.length === 0" class="runtime-empty">娌℃湁鍖归厤鐨勫伐鍏疯繍琛岃褰</p>
      </div>
      <nav v-if="showRuntimePagination(filteredTopTools.length)" class="runtime-pagination" aria-label="Tool runtime pagination">
        <span>
          鏄剧ず {{ runtimePageStart('tools', filteredTopTools.length) }}-{{ runtimePageEnd('tools', filteredTopTools.length) }}
          鏉★紝鍏?{{ filteredTopTools.length }} 鏉★紝姣忛〉 {{ pageSize }} 鏉?        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('tools', filteredTopTools.length) <= 1"
            @click="goRuntimePage('tools', clampedRuntimePage('tools', filteredTopTools.length) - 1, filteredTopTools.length)"
          >
            涓婁竴椤?          </button>
          <button
            v-for="pageNumber in runtimePageButtons('tools', filteredTopTools.length)"
            :key="`tools-${pageNumber}`"
            type="button"
            :class="{ active: pageNumber === clampedRuntimePage('tools', filteredTopTools.length) }"
            @click="goRuntimePage('tools', pageNumber, filteredTopTools.length)"
          >
            {{ pageNumber }}
          </button>
          <button
            type="button"
            :disabled="clampedRuntimePage('tools', filteredTopTools.length) >= runtimePageCount(filteredTopTools.length)"
            @click="goRuntimePage('tools', clampedRuntimePage('tools', filteredTopTools.length) + 1, filteredTopTools.length)"
          >
            涓嬩竴椤?          </button>
        </div>
      </nav>
    </section>

    <section v-else-if="activeTab === 'governance'" class="runtime-panel">
      <header>
        <div>
          <p>Tool Governance</p>
          <h2>宸ュ叿瀹夊叏绛夌骇</h2>
        </div>
      </header>

      <div class="runtime-toolbar">
        <label class="runtime-search-field">
          <Search :size="16" stroke-width="2" />
          <input v-model.trim="governanceSearchQuery" type="text" placeholder="鎼滅储宸ュ叿銆佹湇鍔°€佺瓑绾ф垨绛栫暐" />
        </label>
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select v-model="governanceLevelFilter">
            <option value="">鍏ㄩ儴绛夌骇</option>
            <option v-for="level in governanceLevelOptions" :key="level" :value="level">
              {{ formatRuntimeLevel(level) }}
            </option>
          </select>
        </label>
      </div>

      <div class="governance-table">
        <article v-for="tool in pagedRows(filteredGovernanceTools, 'governance')" :key="tool.toolName">
          <div>
            <strong>{{ tool.displayName || tool.toolName }}</strong>
            <small>{{ tool.toolName }} 路 {{ tool.sourceType }}</small>
          </div>
          <span :class="statusClass(tool.disabled ? 'denied' : tool.confirmationRequired ? 'waiting' : 'success')">
            {{ formatRuntimeLevel(tool.runtimeLevel) }}
          </span>
          <small>{{ formatRuntimeAction(tool.defaultAction) }}</small>
          <small>{{ tool.mcpSynchronized ? "MCP 已同步" : "本地工具" }}</small>
          <small>{{ tool.totalCalls }} 璋冪敤 路 {{ tool.deniedCalls }} 鎷掔粷</small>
        </article>
        <p v-if="filteredGovernanceTools.length === 0" class="runtime-empty">娌℃湁鍖归厤鐨勫伐鍏锋不鐞嗚褰</p>
      </div>
      <nav v-if="showRuntimePager(filteredGovernanceTools.length)" class="runtime-pagination" aria-label="Tool governance pagination">
        <span>
          鏄剧ず {{ runtimePageStart('governance', filteredGovernanceTools.length) }}-{{ runtimePageEnd('governance', filteredGovernanceTools.length) }}
          鏉★紝鍏?{{ filteredGovernanceTools.length }} 鏉★紝姣忛〉 {{ pageSize }} 鏉?        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('governance', filteredGovernanceTools.length) <= 1"
            @click="goRuntimePage('governance', clampedRuntimePage('governance', filteredGovernanceTools.length) - 1, filteredGovernanceTools.length)"
          >
            涓婁竴椤?          </button>
          <button
            v-for="pageNumber in runtimePageButtons('governance', filteredGovernanceTools.length)"
            :key="`governance-${pageNumber}`"
            type="button"
            :class="{ active: pageNumber === clampedRuntimePage('governance', filteredGovernanceTools.length) }"
            @click="goRuntimePage('governance', pageNumber, filteredGovernanceTools.length)"
          >
            {{ pageNumber }}
          </button>
          <button
            type="button"
            :disabled="clampedRuntimePage('governance', filteredGovernanceTools.length) >= runtimePageCount(filteredGovernanceTools.length)"
            @click="goRuntimePage('governance', clampedRuntimePage('governance', filteredGovernanceTools.length) + 1, filteredGovernanceTools.length)"
          >
            涓嬩竴椤?          </button>
        </div>
      </nav>
    </section>

    <section v-else class="runtime-panel">
      <header>
        <div>
          <p>Audit Center</p>
          <h2>宸ュ叿娌荤悊鏃ュ織</h2>
        </div>
      </header>

      <div class="runtime-toolbar">
        <label class="runtime-search-field">
          <Search :size="16" stroke-width="2" />
          <input v-model.trim="auditSearchQuery" type="text" placeholder="鎼滅储宸ュ叿銆佺敤鎴枫€佹ā寮忋€佹湇鍔℃垨閿欒淇℃伅" />
        </label>
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select v-model="auditOutcomeFilter">
            <option value="">鍏ㄩ儴缁撴灉</option>
            <option v-for="outcome in auditOutcomeOptions" :key="outcome" :value="outcome">
              {{ formatOutcome(outcome) }}
            </option>
          </select>
        </label>
      </div>

      <div class="audit-log-list">
        <article v-for="audit in pagedRows(filteredAudits, 'audits')" :key="audit.id">
          <div class="audit-log-head">
            <strong>{{ audit.toolName || "-" }}</strong>
            <span :class="statusClass(audit.outcome)">{{ formatOutcome(audit.outcome) }}</span>
          </div>
          <dl>
            <div>
              <dt>User</dt>
              <dd>{{ audit.userId || "-" }}</dd>
            </div>
            <div>
              <dt>Mode</dt>
              <dd>{{ audit.mode || "-" }}</dd>
            </div>
            <div>
              <dt>Latency</dt>
              <dd>{{ formatDuration(audit.durationMs) }}</dd>
            </div>
            <div>
              <dt>Service</dt>
              <dd>{{ audit.serviceId || "-" }}</dd>
            </div>
          </dl>
          <p v-if="audit.errorMessage">{{ audit.errorMessage }}</p>
          <time>{{ formatAuditTime(audit.createdAt) }}</time>
        </article>
        <p v-if="filteredAudits.length === 0" class="runtime-empty">娌℃湁鍖归厤鐨勬不鐞嗘棩蹇</p>
      </div>
      <nav v-if="showRuntimePagination(filteredAudits.length)" class="runtime-pagination" aria-label="Audit pagination">
        <span>
          鏄剧ず {{ runtimePageStart('audits', filteredAudits.length) }}-{{ runtimePageEnd('audits', filteredAudits.length) }}
          鏉★紝鍏?{{ filteredAudits.length }} 鏉★紝姣忛〉 {{ pageSize }} 鏉?        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('audits', filteredAudits.length) <= 1"
            @click="goRuntimePage('audits', clampedRuntimePage('audits', filteredAudits.length) - 1, filteredAudits.length)"
          >
            涓婁竴椤?          </button>
          <button
            v-for="pageNumber in runtimePageButtons('audits', filteredAudits.length)"
            :key="`audits-${pageNumber}`"
            type="button"
            :class="{ active: pageNumber === clampedRuntimePage('audits', filteredAudits.length) }"
            @click="goRuntimePage('audits', pageNumber, filteredAudits.length)"
          >
            {{ pageNumber }}
          </button>
          <button
            type="button"
            :disabled="clampedRuntimePage('audits', filteredAudits.length) >= runtimePageCount(filteredAudits.length)"
            @click="goRuntimePage('audits', clampedRuntimePage('audits', filteredAudits.length) + 1, filteredAudits.length)"
          >
            涓嬩竴椤?          </button>
        </div>
      </nav>
    </section>
  </section>
</template>

<script src="../js/views/TasksView.js"></script>
