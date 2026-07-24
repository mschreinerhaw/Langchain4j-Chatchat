<template>
  <section class="feature-view runtime-view">
    <header class="runtime-header">
      <div class="runtime-title">
        <p>智能体运行监控</p>
        <span>按租户监控任务执行、事件链路和工具治理。</span>
      </div>
      <div class="runtime-actions">
        <label class="runtime-filter">
          <span>租户</span>
          <input v-model.trim="runtimeTenantId" type="text" placeholder="租户编号" @keyup.enter="loadRuntime" />
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

    <nav class="runtime-tabs" aria-label="运行监控视图">
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
          <p>任务中心</p>
          <h2>任务实例</h2>
        </div>
      </header>

      <div class="runtime-toolbar">
        <label class="runtime-search-field">
          <Search :size="16" stroke-width="2" />
          <input v-model.trim="taskSearchQuery" type="text" placeholder="搜索任务编号、问题、租户或状态" />
        </label>
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select v-model="statusFilter">
            <option value="">全部状态</option>
            <option v-for="status in statusOptions" :key="status" :value="status">{{ formatTaskStatus(status) }}</option>
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
          <span class="task-tenant">{{ task.tenantId || "默认" }}</span>
          <strong :class="statusClass(task.status)">{{ formatTaskStatus(task.status) }}</strong>
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
            {{ isCancellingTask(task) ? "停止中" : "停止" }}
          </span>
        </button>
        <p v-if="!loading && filteredTasks.length === 0" class="runtime-empty">没有匹配的任务实例。</p>
      </div>
      <nav v-if="showRuntimePagination(filteredTasks.length)" class="runtime-pagination" aria-label="任务分页">
        <span>
          显示第 {{ runtimePageStart('tasks', filteredTasks.length) }}-{{ runtimePageEnd('tasks', filteredTasks.length) }} 条，
          共 {{ filteredTasks.length }} 条，每页 {{ pageSize }} 条
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
          <p>智能体效果</p>
          <h2>效果分析</h2>
        </div>
      </header>

      <div class="runtime-mini-metrics effect-metrics">
        <article v-for="metric in effectMetrics" :key="metric.label">
          <component :is="metric.icon" :size="16" stroke-width="2" />
          <span>{{ metric.label }}</span>
          <strong>{{ metric.value }}</strong>
        </article>
      </div>

      <div class="effect-subtabs" aria-label="效果分析详情">
        <button
          type="button"
          :class="{ active: effectActiveTab === 'agents' }"
          @click="effectActiveTab = 'agents'"
        >
          <Activity :size="15" stroke-width="2" />
          <strong>智能体汇总</strong>
          <span>{{ agentEffectRows.length }}</span>
        </button>
        <button
          type="button"
          :class="{ active: effectActiveTab === 'lowScores' }"
          @click="effectActiveTab = 'lowScores'"
        >
          <ShieldAlert :size="15" stroke-width="2" />
          <strong>低分任务</strong>
          <span>{{ lowScoreTasks.length }}</span>
        </button>
      </div>

      <div v-if="reasonMetrics.length > 0" class="reason-metrics">
        <article v-for="reason in pagedRows(reasonMetrics, 'reasonMetrics')" :key="reason.reasonCategory">
          <strong>{{ reason.label }}</strong>
          <span>{{ reason.total }} 条</span>
          <small>{{ formatPercent(reason.share) }}</small>
        </article>
      </div>

      <nav v-if="showRuntimePager(reasonMetrics.length)" class="runtime-pagination" aria-label="效果原因分页">
        <span>
          显示第 {{ runtimePageStart('reasonMetrics', reasonMetrics.length) }}-{{ runtimePageEnd('reasonMetrics', reasonMetrics.length) }} 条，
          共 {{ reasonMetrics.length }} 条，每页 {{ pageSize }} 条
        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('reasonMetrics', reasonMetrics.length) <= 1"
            @click="goRuntimePage('reasonMetrics', clampedRuntimePage('reasonMetrics', reasonMetrics.length) - 1, reasonMetrics.length)"
          >
            上一页
          </button>
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
            下一页
          </button>
        </div>
      </nav>

      <section v-if="effectActiveTab === 'agents'" class="effect-section">
        <header class="subsection-head">
          <strong>智能体汇总</strong>
          <span>{{ agentEffectRows.length }} 个智能体</span>
        </header>
        <div class="effect-table">
          <article v-for="agent in pagedRows(agentEffectRows, 'agentEffects')" :key="agent.agentId">
            <strong>{{ agent.agentId || "默认智能体" }}</strong>
            <span>{{ agent.totalTasks }} 个任务 / {{ agent.feedbackTasks }} 条反馈</span>
            <small>有用 {{ formatPercent(agent.usefulRate) }}</small>
            <small>采纳 {{ formatPercent(agent.adoptedRate) }}</small>
            <small>解决 {{ formatPercent(agent.resolvedRate) }}</small>
            <small>失败 {{ formatPercent(agent.failedRate) }}</small>
          </article>
          <p v-if="agentEffectRows.length === 0" class="runtime-empty">暂无智能体效果数据。</p>
        </div>
        <nav v-if="showRuntimePager(agentEffectRows.length)" class="runtime-pagination" aria-label="智能体效果分页">
          <span>
            显示第 {{ runtimePageStart('agentEffects', agentEffectRows.length) }}-{{ runtimePageEnd('agentEffects', agentEffectRows.length) }} 条，
            共 {{ agentEffectRows.length }} 条，每页 {{ pageSize }} 条
          </span>
          <div>
            <button
              type="button"
              :disabled="clampedRuntimePage('agentEffects', agentEffectRows.length) <= 1"
              @click="goRuntimePage('agentEffects', clampedRuntimePage('agentEffects', agentEffectRows.length) - 1, agentEffectRows.length)"
            >
              上一页
            </button>
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
              下一页
            </button>
          </div>
        </nav>
      </section>

      <section v-else class="effect-section">
        <header class="subsection-head">
          <strong>低分任务</strong>
          <span>{{ lowScoreTasks.length }} 条</span>
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
              原因 {{ formatFeedbackReason(task.feedbackReasonCategory) }}
            </small>
            <p v-if="task.feedbackComment">{{ task.feedbackComment }}</p>
          </button>
          <p v-if="lowScoreTasks.length === 0" class="runtime-empty">暂无低分任务。</p>
        </div>
        <nav v-if="showRuntimePager(lowScoreTasks.length)" class="runtime-pagination" aria-label="低分任务分页">
          <span>
            显示第 {{ runtimePageStart('lowScores', lowScoreTasks.length) }}-{{ runtimePageEnd('lowScores', lowScoreTasks.length) }} 条，
            共 {{ lowScoreTasks.length }} 条，每页 {{ pageSize }} 条
          </span>
          <div>
            <button
              type="button"
              :disabled="clampedRuntimePage('lowScores', lowScoreTasks.length) <= 1"
              @click="goRuntimePage('lowScores', clampedRuntimePage('lowScores', lowScoreTasks.length) - 1, lowScoreTasks.length)"
            >
              上一页
            </button>
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
              下一页
            </button>
          </div>
        </nav>
      </section>
    </section>

    <section v-else-if="activeTab === 'experiences'" class="runtime-panel">
      <header>
        <div>
          <p>经验库</p>
          <h2>经验库</h2>
        </div>
      </header>

      <div class="experience-subtabs" aria-label="经验库视图">
        <button
          type="button"
          :class="{ active: experienceActiveTab === 'scenarios' }"
          @click="experienceActiveTab = 'scenarios'"
        >
          <strong>场景概览</strong>
          <span>{{ experienceScenarios.length }}</span>
        </button>
        <button
          type="button"
          :class="{ active: experienceActiveTab === 'indexes' }"
          @click="experienceActiveTab = 'indexes'"
        >
          <strong>结构化索引</strong>
          <span>{{ experienceIndexes.length }}</span>
        </button>
        <button
          type="button"
          :class="{ active: experienceActiveTab === 'records' }"
          @click="experienceActiveTab = 'records'"
        >
          <strong>经验记录</strong>
          <span>{{ experienceItems.length }}</span>
        </button>
      </div>

      <section v-if="experienceActiveTab === 'scenarios'" class="experience-tab-panel">
      <header class="subsection-head experience-subsection-head">
        <strong>场景概览</strong>
        <span>{{ experienceScenarios.length }} 条</span>
      </header>
      <div class="experience-scenarios">
        <article v-for="scenario in pagedRows(experienceScenarios, 'experienceScenarios')" :key="scenario.scenarioKey || scenario.scenarioName">
          <strong>{{ scenario.scenarioName || scenario.scenarioKey || "-" }}</strong>
          <span>{{ scenario.scenarioKey || "通用" }} / {{ scenario.total || 0 }} 个样本</span>
          <small>平均分 {{ scenario.averageScore || 0 }}</small>
        </article>
        <p v-if="experienceScenarios.length === 0" class="runtime-empty">暂无结构化经验索引。</p>
      </div>
      <nav v-if="showRuntimePagination(experienceScenarios.length)" class="runtime-pagination" aria-label="经验场景分页">
        <span>
          显示第 {{ runtimePageStart('experienceScenarios', experienceScenarios.length) }}-{{ runtimePageEnd('experienceScenarios', experienceScenarios.length) }} 条，
          共 {{ experienceScenarios.length }} 条，每页 {{ pageSize }} 条
        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('experienceScenarios', experienceScenarios.length) <= 1"
            @click="goRuntimePage('experienceScenarios', clampedRuntimePage('experienceScenarios', experienceScenarios.length) - 1, experienceScenarios.length)"
          >
            上一页
          </button>
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
            下一页
          </button>
        </div>
      </nav>
      </section>

      <section v-else-if="experienceActiveTab === 'indexes'" class="experience-tab-panel">
      <header class="subsection-head experience-subsection-head">
        <strong>结构化索引</strong>
        <span>{{ experienceIndexes.length }} 条</span>
      </header>
      <div class="experience-index-list">
        <article v-for="index in pagedRows(experienceIndexes, 'experienceIndexes')" :key="`index-${index.id}`">
          <header>
            <strong>{{ index.agentId || "默认智能体" }}</strong>
            <span>{{ index.scenario }} / {{ index.intentType || "通用" }}</span>
            <b>{{ formatPercent(index.successRate) }}</b>
          </header>
          <dl>
            <div>
              <dt>工具链</dt>
              <dd>{{ index.toolChain || "-" }}</dd>
            </div>
            <div>
              <dt>关键词</dt>
              <dd>{{ index.keywords || "-" }}</dd>
            </div>
            <div>
              <dt>统计</dt>
              <dd>
                有用 {{ index.usefulCount }} / 采纳 {{ index.adoptedCount }} / 解决 {{ index.resolvedCount }} / 失败
                {{ index.failedCount }}
              </dd>
            </div>
          </dl>
          <p v-if="index.bestPractice">最佳实践：{{ index.bestPractice }}</p>
          <p v-if="index.avoidPattern">规避模式：{{ index.avoidPattern }}</p>
        </article>
      </div>
      <nav v-if="showRuntimePagination(experienceIndexes.length)" class="runtime-pagination" aria-label="经验索引分页">
        <span>
          显示第 {{ runtimePageStart('experienceIndexes', experienceIndexes.length) }}-{{ runtimePageEnd('experienceIndexes', experienceIndexes.length) }} 条，
          共 {{ experienceIndexes.length }} 条，每页 {{ pageSize }} 条
        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('experienceIndexes', experienceIndexes.length) <= 1"
            @click="goRuntimePage('experienceIndexes', clampedRuntimePage('experienceIndexes', experienceIndexes.length) - 1, experienceIndexes.length)"
          >
            上一页
          </button>
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
            下一页
          </button>
        </div>
      </nav>
      </section>

      <section v-else class="experience-tab-panel">
      <header class="subsection-head experience-subsection-head">
        <strong>经验记录</strong>
        <span>{{ experienceItems.length }} 条</span>
      </header>
      <div class="experience-list">
        <article v-for="experience in pagedRows(experienceItems, 'experiences')" :key="experience.experienceId">
          <header>
            <div>
              <strong>{{ experience.scenarioName || experience.scenarioKey }}</strong>
              <span>{{ experience.agentId || "默认智能体" }} / {{ shortId(experience.taskId) }}</span>
            </div>
            <b>{{ experience.feedbackScore || 0 }}</b>
          </header>
          <p>{{ experience.attributionSummary || experience.question }}</p>
          <dl>
            <div>
              <dt>归因</dt>
              <dd>{{ formatAttributionSource(experience.attributionSource) }}</dd>
            </div>
            <div>
              <dt>原因</dt>
              <dd>{{ formatFeedbackReason(experience.feedbackReasonCategory) }}</dd>
            </div>
            <div>
              <dt>反馈</dt>
              <dd>
                有用 {{ experience.feedbackUseful ? "是" : "否" }} / 采纳
                {{ experience.feedbackAdopted ? "是" : "否" }} / 解决
                {{ experience.feedbackResolved ? "是" : "否" }}
              </dd>
            </div>
          </dl>
          <div class="experience-patterns">
            <section>
              <strong>成功模式</strong>
              <span v-for="item in experience.successPattern" :key="`success-${experience.experienceId}-${item}`">
                {{ item }}
              </span>
            </section>
            <section>
              <strong>改进建议</strong>
              <span
                v-for="item in experience.improvementSuggestions"
                :key="`improve-${experience.experienceId}-${item}`"
              >
                {{ item }}
              </span>
            </section>
          </div>
        </article>
        <p v-if="experienceItems.length === 0" class="runtime-empty">暂无经验记录。</p>
      </div>
      <nav v-if="showRuntimePagination(experienceItems.length)" class="runtime-pagination" aria-label="经验记录分页">
        <span>
          显示第 {{ runtimePageStart('experiences', experienceItems.length) }}-{{ runtimePageEnd('experiences', experienceItems.length) }} 条，
          共 {{ experienceItems.length }} 条，每页 {{ pageSize }} 条
        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('experiences', experienceItems.length) <= 1"
            @click="goRuntimePage('experiences', clampedRuntimePage('experiences', experienceItems.length) - 1, experienceItems.length)"
          >
            上一页
          </button>
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
            下一页
          </button>
        </div>
      </nav>
      </section>
    </section>

    <section v-else-if="activeTab === 'events'" class="runtime-panel">
      <header>
        <div>
          <p>事件库</p>
          <h2>事件链路</h2>
        </div>
        <button type="button" :disabled="!selectedTask || eventsLoading" @click="reloadEvents">
          <Database :size="15" stroke-width="2" />
          <span>{{ eventsLoading ? "加载中" : "加载" }}</span>
        </button>
      </header>

      <div class="runtime-toolbar runtime-toolbar-wide">
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select :value="selectedTaskId" @change="onSelectedTaskChange($event.target.value)">
            <option value="">选择任务</option>
            <option v-for="task in tasks" :key="task.taskId" :value="task.taskId">
              {{ shortId(task.taskId) }} / {{ task.question || "未命名任务" }}
            </option>
          </select>
        </label>
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select v-model="eventTypeFilter">
            <option value="">全部事件</option>
            <option v-for="type in eventTypeOptions" :key="type" :value="type">{{ formatEventType(type) }}</option>
          </select>
        </label>
        <label class="runtime-search-field">
          <Search :size="16" stroke-width="2" />
          <input v-model.trim="eventSearchQuery" type="text" placeholder="搜索事件类型、状态、工具或错误" />
        </label>
      </div>

      <div v-if="selectedTaskDisplay" class="selected-task">
        <strong>{{ selectedTaskDisplay.id }}</strong>
        <span>{{ selectedTaskDisplay.subtitle }}</span>
        <p>{{ selectedTaskDisplay.description }}</p>
        <div class="task-feedback">
          <label>
            <input v-model="feedbackDraft.useful" type="checkbox" :disabled="!canRecordFeedback || feedbackSubmitting" />
            有用
          </label>
          <label>
            <input v-model="feedbackDraft.adopted" type="checkbox" :disabled="!canRecordFeedback || feedbackSubmitting" />
            采纳
          </label>
          <label>
            <input v-model="feedbackDraft.resolved" type="checkbox" :disabled="!canRecordFeedback || feedbackSubmitting" />
            解决
          </label>
          <input
            v-model.trim="feedbackDraft.comment"
            type="text"
            :disabled="!canRecordFeedback || feedbackSubmitting"
            maxlength="1000"
            placeholder="反馈备注"
          />
          <select
            v-model="feedbackDraft.reasonCategory"
            :disabled="!canRecordFeedback || feedbackSubmitting"
            aria-label="反馈原因分类"
          >
            <option v-for="option in feedbackReasonOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
          <button type="button" :disabled="!canRecordFeedback || feedbackSubmitting" @click="saveTaskFeedback">
            <ShieldCheck :size="14" stroke-width="2" />
            <span>{{ feedbackSubmitting ? "保存中" : "保存反馈" }}</span>
          </button>
        </div>
      </div>

      <div class="event-timeline">
        <article v-for="event in pagedRows(filteredEvents, 'events')" :key="event.eventId">
          <span :class="statusClass(event.status)">{{ formatEventType(event.type) }}</span>
          <strong>{{ formatTaskStatus(event.status) }}</strong>
          <time>{{ formatEventTime(event.createTime) }}</time>
        </article>
        <p v-if="!selectedTask" class="runtime-empty">请先在任务页或任务选择器中选择任务。</p>
        <p v-else-if="!eventsLoading && filteredEvents.length === 0" class="runtime-empty">没有匹配的事件记录。</p>
      </div>
      <nav v-if="showRuntimePager(filteredEvents.length)" class="runtime-pagination" aria-label="事件分页">
        <span>
          显示第 {{ runtimePageStart('events', filteredEvents.length) }}-{{ runtimePageEnd('events', filteredEvents.length) }} 条，
          共 {{ filteredEvents.length }} 条，每页 {{ pageSize }} 条
        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('events', filteredEvents.length) <= 1"
            @click="goRuntimePage('events', clampedRuntimePage('events', filteredEvents.length) - 1, filteredEvents.length)"
          >
            上一页
          </button>
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
            下一页
          </button>
        </div>
      </nav>
    </section>

    <section v-else-if="activeTab === 'plan'" class="runtime-panel">
      <header>
        <div>
          <p>解读计划</p>
          <h2>计划图</h2>
        </div>
        <button type="button" :disabled="!selectedTask || planLoading" @click="loadPlanDag">
          <GitBranch :size="15" stroke-width="2" />
          <span>{{ planLoading ? "加载中" : "加载" }}</span>
        </button>
      </header>

      <div class="runtime-toolbar runtime-toolbar-wide">
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select :value="selectedTaskId" @change="onSelectedTaskChange($event.target.value)">
            <option value="">选择任务</option>
            <option v-for="task in tasks" :key="task.taskId" :value="task.taskId">
              {{ shortId(task.taskId) }} / {{ task.question || "未命名任务" }}
            </option>
          </select>
        </label>
        <span class="runtime-pill">{{ latestPlanVersionLabel }}</span>
        <span class="runtime-pill">{{ planNodes.length }} 个节点</span>
        <span class="runtime-pill">{{ planEdges.length }} 条边</span>
      </div>

      <div v-if="selectedTaskDisplay" class="selected-task">
        <strong>{{ selectedTaskDisplay.id }}</strong>
        <span>{{ selectedTaskDisplay.subtitle }}</span>
        <p>{{ selectedTaskDisplay.description }}</p>
      </div>

      <div v-if="selectedTask && planNodes.length > 0" class="plan-dag-layout">
        <aside class="plan-dag-side">
          <div>
            <span>快照</span>
            <strong>{{ selectedPlanDag.planId || "-" }}</strong>
            <small>{{ formatTaskStatus(selectedPlanDag.status || "GENERATED") }} · {{ formatTime(selectedPlanDag.updatedAt) }}</small>
          </div>
          <div>
            <span>版本</span>
            <button
              v-for="version in selectedPlanVersions"
              :key="`${version.planId}-${version.version}`"
              type="button"
              class="plan-version-chip"
              :class="{ active: version.version === selectedPlanDag?.version }"
              @click="selectPlanVersion(version)"
            >
              v{{ version.version }} · {{ formatTime(version.updatedAt) }}
            </button>
          </div>
        </aside>

        <div class="plan-dag-canvas">
          <div v-show="planControlsVisible" class="plan-dag-controls">
            <button type="button" @click="zoomPlanDag(0.8)">-</button>
            <span>{{ planZoomLabel }}</span>
            <button type="button" @click="zoomPlanDag(1.25)">+</button>
            <button type="button" @click="resetPlanDagView">重置</button>
            <button type="button" @click="downloadPlanDagSvg">导出 SVG</button>
            <button type="button" @click="downloadPlanDagJson">导出 JSON</button>
          </div>
          <svg
            ref="planDagSvg"
            class="plan-dag-svg"
            :class="{ dragging: planDragActive }"
            :viewBox="planDagViewBox"
            preserveAspectRatio="xMinYMin meet"
            role="img"
            aria-label="解读计划图"
            @click="togglePlanDagControls"
            @wheel="handlePlanDagWheel"
            @pointerdown="startPlanDagPan"
            @pointermove="movePlanDagPan"
            @pointerup="stopPlanDagPan"
            @pointercancel="stopPlanDagPan"
            @pointerleave="stopPlanDagPan"
          >
            <defs>
              <marker id="plan-dag-arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto" markerUnits="strokeWidth">
                <path d="M0,0 L0,6 L8,3 z" />
              </marker>
            </defs>
            <g class="plan-dag-edges">
              <path v-for="edge in planEdgeViews" :key="edge.id" :d="edge.path" marker-end="url(#plan-dag-arrow)" />
              <g v-for="edge in planEdgeViews.filter((item) => item.hasLabel)" :key="`${edge.id}-label`" class="plan-dag-edge-label">
                <title>{{ edge.fullLabel }}</title>
                <rect :x="edge.x - edge.labelWidth / 2" :y="edge.y - 15" :width="edge.labelWidth" height="26" rx="7" />
                <text :x="edge.x" :y="edge.y">{{ edge.label }}</text>
              </g>
            </g>
            <g
              v-for="node in planNodeViews"
              :key="node.id"
              class="plan-dag-node"
              :class="[String(node.actionType || '').replaceAll('_', '-'), String(node.statusText || '').toLowerCase(), String(node.kind || '').toLowerCase()]"
              :transform="`translate(${node.x}, ${node.y})`"
            >
              <rect :width="node.width" :height="node.height" rx="8" />
              <title>{{ node.fullLabelText }} · {{ node.toolName || node.actionText }}</title>
              <svg
                class="plan-dag-node-textbox"
                x="20"
                y="14"
                :width="node.width - 40"
                :height="node.height - 28"
                overflow="hidden"
              >
                <text x="0" y="16" class="plan-dag-node-title">
                  <tspan
                    v-for="(line, lineIndex) in node.labelLines"
                    :key="`${node.id}-label-${lineIndex}`"
                    x="0"
                    :dy="lineIndex === 0 ? 0 : 20"
                  >
                    {{ line }}
                  </tspan>
                </text>
                <text x="0" :y="node.toolY">{{ node.toolText }}</text>
                <text x="0" :y="node.metaY" class="plan-dag-node-meta">{{ node.metaText }}</text>
              </svg>
            </g>
          </svg>
        </div>

        <div class="plan-dag-node-list">
          <article v-for="node in planNodeViews" :key="`${node.id}-detail`" :class="String(node.statusText || '').toLowerCase()">
            <span>{{ node.statusLabel }}</span>
            <div>
              <strong>{{ node.fullLabelText }}</strong>
              <small>{{ node.toolName || node.actionText }} · {{ formatDuration(node.durationMs) }}</small>
              <p v-if="node.detailText">{{ node.detailText }}</p>
            </div>
          </article>
        </div>
      </div>
      <p v-if="!selectedTask" class="runtime-empty">请先在任务页或任务选择器中选择任务。</p>
      <p v-else-if="!planLoading && planNodes.length === 0" class="runtime-empty">当前任务暂无解读计划图快照。</p>
    </section>

    <section v-else-if="activeTab === 'tools'" class="runtime-panel">
      <header>
        <div>
          <p>工具运行态</p>
          <h2>工具运行态</h2>
        </div>
      </header>

      <div class="runtime-toolbar">
        <label class="runtime-search-field">
          <Search :size="16" stroke-width="2" />
          <input v-model.trim="toolSearchQuery" type="text" placeholder="搜索工具名称或运行指标" />
        </label>
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select v-model="toolHealthFilter">
            <option value="">全部健康状态</option>
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
            <small>{{ tool.totalCalls }} 次调用</small>
          </div>
          <span :class="statusClass(toolHealth(tool) === 'problem' ? 'failed' : 'success')">
            {{ toolHealth(tool) === "problem" ? "需要关注" : "稳定" }}
          </span>
          <small>
            {{ tool.deniedCalls }} 次拒绝 / {{ tool.rateLimitedCalls }} 次限流 /
            {{ formatDuration(tool.averageDurationMs) }}
          </small>
        </article>
        <p v-if="filteredTopTools.length === 0" class="runtime-empty">没有匹配的工具运行记录。</p>
      </div>
      <nav v-if="showRuntimePagination(filteredTopTools.length)" class="runtime-pagination" aria-label="工具运行态分页">
        <span>
          显示第 {{ runtimePageStart('tools', filteredTopTools.length) }}-{{ runtimePageEnd('tools', filteredTopTools.length) }} 条，
          共 {{ filteredTopTools.length }} 条，每页 {{ pageSize }} 条
        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('tools', filteredTopTools.length) <= 1"
            @click="goRuntimePage('tools', clampedRuntimePage('tools', filteredTopTools.length) - 1, filteredTopTools.length)"
          >
            上一页
          </button>
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
            下一页
          </button>
        </div>
      </nav>
    </section>

    <section v-else-if="activeTab === 'governance'" class="runtime-panel">
      <header>
        <div>
          <p>工具治理</p>
          <h2>工具安全等级</h2>
        </div>
      </header>

      <div class="runtime-toolbar">
        <label class="runtime-search-field">
          <Search :size="16" stroke-width="2" />
          <input v-model.trim="governanceSearchQuery" type="text" placeholder="搜索工具、服务、等级或策略" />
        </label>
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select v-model="governanceLevelFilter">
            <option value="">全部等级</option>
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
            <small>{{ tool.toolName }} / {{ tool.sourceType }}</small>
          </div>
          <span :class="statusClass(tool.disabled ? 'denied' : tool.confirmationRequired ? 'waiting' : 'success')">
            {{ formatRuntimeLevel(tool.runtimeLevel) }}
          </span>
          <small>{{ formatRuntimeAction(tool.defaultAction) }}</small>
          <small>{{ tool.mcpSynchronized ? "MCP 已同步" : "本地工具" }}</small>
          <small>{{ tool.totalCalls }} 次调用 / {{ tool.deniedCalls }} 次拒绝</small>
        </article>
        <p v-if="filteredGovernanceTools.length === 0" class="runtime-empty">没有匹配的工具治理记录。</p>
      </div>
      <nav v-if="showRuntimePager(filteredGovernanceTools.length)" class="runtime-pagination" aria-label="工具治理分页">
        <span>
          显示第 {{ runtimePageStart('governance', filteredGovernanceTools.length) }}-{{ runtimePageEnd('governance', filteredGovernanceTools.length) }} 条，
          共 {{ filteredGovernanceTools.length }} 条，每页 {{ pageSize }} 条
        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('governance', filteredGovernanceTools.length) <= 1"
            @click="goRuntimePage('governance', clampedRuntimePage('governance', filteredGovernanceTools.length) - 1, filteredGovernanceTools.length)"
          >
            上一页
          </button>
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
            下一页
          </button>
        </div>
      </nav>
    </section>

    <section v-else class="runtime-panel">
      <header>
        <div>
          <p>审计中心</p>
          <h2>工具治理日志</h2>
        </div>
      </header>

      <div class="runtime-toolbar">
        <label class="runtime-search-field">
          <Search :size="16" stroke-width="2" />
          <input v-model.trim="auditSearchQuery" type="text" placeholder="搜索工具、用户、模式、服务或错误" />
        </label>
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select v-model="auditOutcomeFilter">
            <option value="">全部结果</option>
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
              <dt>用户</dt>
              <dd>{{ audit.userId || "-" }}</dd>
            </div>
            <div>
              <dt>模式</dt>
              <dd>{{ formatAuditMode(audit.mode) }}</dd>
            </div>
            <div>
              <dt>耗时</dt>
              <dd>{{ formatDuration(audit.durationMs) }}</dd>
            </div>
            <div>
              <dt>服务</dt>
              <dd>{{ audit.serviceId || "-" }}</dd>
            </div>
          </dl>
          <p v-if="audit.errorMessage">{{ audit.errorMessage }}</p>
          <time>{{ formatAuditTime(audit.createdAt) }}</time>
        </article>
        <p v-if="filteredAudits.length === 0" class="runtime-empty">没有匹配的治理日志。</p>
      </div>
      <nav v-if="showRuntimePagination(filteredAudits.length)" class="runtime-pagination" aria-label="审计分页">
        <span>
          显示第 {{ runtimePageStart('audits', filteredAudits.length) }}-{{ runtimePageEnd('audits', filteredAudits.length) }} 条，
          共 {{ filteredAudits.length }} 条，每页 {{ pageSize }} 条
        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('audits', filteredAudits.length) <= 1"
            @click="goRuntimePage('audits', clampedRuntimePage('audits', filteredAudits.length) - 1, filteredAudits.length)"
          >
            上一页
          </button>
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
            下一页
          </button>
        </div>
      </nav>
    </section>
  </section>
</template>

<script src="../js/views/TasksView.js"></script>
