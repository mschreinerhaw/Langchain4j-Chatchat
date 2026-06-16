<template>
  <section class="feature-view runtime-view">
    <header class="runtime-header">
      <div class="runtime-title">
        <p>Agent Runtime</p>
        <span>Monitor task execution, event chains, and tool governance by tenant.</span>
      </div>
      <div class="runtime-actions">
        <label class="runtime-filter">
          <span>Tenant</span>
          <input v-model.trim="tenantId" type="text" placeholder="tenant-id" @keyup.enter="loadRuntime" />
        </label>
        <button type="button" :disabled="loading" @click="loadRuntime">
          <RefreshCw :size="16" stroke-width="2" />
          <span>{{ loading ? "Refreshing" : "Refresh" }}</span>
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

    <nav class="runtime-tabs" aria-label="Runtime views">
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
          <h2>Task Instances</h2>
        </div>
      </header>

      <div class="runtime-toolbar">
        <label class="runtime-search-field">
          <Search :size="16" stroke-width="2" />
          <input v-model.trim="taskSearchQuery" type="text" placeholder="Search task ID, question, tenant, or status" />
        </label>
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select v-model="statusFilter">
            <option value="">All statuses</option>
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
          <span class="task-question">{{ task.question || "Untitled task" }}</span>
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
        <p v-if="!loading && filteredTasks.length === 0" class="runtime-empty">No matching task instances.</p>
      </div>
      <nav v-if="showRuntimePagination(filteredTasks.length)" class="runtime-pagination" aria-label="Task pagination">
        <span>
          Showing {{ runtimePageStart('tasks', filteredTasks.length) }}-{{ runtimePageEnd('tasks', filteredTasks.length) }}
          of {{ filteredTasks.length }}, {{ pageSize }} per page
        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('tasks', filteredTasks.length) <= 1"
            @click="goRuntimePage('tasks', clampedRuntimePage('tasks', filteredTasks.length) - 1, filteredTasks.length)"
          >
            Previous
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
            Next
          </button>
        </div>
      </nav>
    </section>

    <section v-else-if="activeTab === 'effects'" class="runtime-panel">
      <header>
        <div>
          <p>Agent Effect</p>
          <h2>Effect Analytics</h2>
        </div>
      </header>

      <div class="runtime-mini-metrics effect-metrics">
        <article v-for="metric in effectMetrics" :key="metric.label">
          <component :is="metric.icon" :size="16" stroke-width="2" />
          <span>{{ metric.label }}</span>
          <strong>{{ metric.value }}</strong>
        </article>
      </div>

      <div class="effect-subtabs" aria-label="Effect analytics details">
        <button
          type="button"
          :class="{ active: effectActiveTab === 'agents' }"
          @click="effectActiveTab = 'agents'"
        >
          <Activity :size="15" stroke-width="2" />
          <strong>Agent Rollup</strong>
          <span>{{ agentEffectRows.length }}</span>
        </button>
        <button
          type="button"
          :class="{ active: effectActiveTab === 'lowScores' }"
          @click="effectActiveTab = 'lowScores'"
        >
          <ShieldAlert :size="15" stroke-width="2" />
          <strong>Low-score Tasks</strong>
          <span>{{ lowScoreTasks.length }}</span>
        </button>
      </div>

      <div v-if="reasonMetrics.length > 0" class="reason-metrics">
        <article v-for="reason in pagedRows(reasonMetrics, 'reasonMetrics')" :key="reason.reasonCategory">
          <strong>{{ reason.label }}</strong>
          <span>{{ reason.total }} items</span>
          <small>{{ formatPercent(reason.share) }}</small>
        </article>
      </div>

      <nav v-if="showRuntimePager(reasonMetrics.length)" class="runtime-pagination" aria-label="Effect reason pagination">
        <span>
          Showing {{ runtimePageStart('reasonMetrics', reasonMetrics.length) }}-{{ runtimePageEnd('reasonMetrics', reasonMetrics.length) }}
          of {{ reasonMetrics.length }}, {{ pageSize }} per page
        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('reasonMetrics', reasonMetrics.length) <= 1"
            @click="goRuntimePage('reasonMetrics', clampedRuntimePage('reasonMetrics', reasonMetrics.length) - 1, reasonMetrics.length)"
          >
            Previous
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
            Next
          </button>
        </div>
      </nav>

      <section v-if="effectActiveTab === 'agents'" class="effect-section">
        <header class="subsection-head">
          <strong>Agent Rollup</strong>
          <span>{{ agentEffectRows.length }} agents</span>
        </header>
        <div class="effect-table">
          <article v-for="agent in pagedRows(agentEffectRows, 'agentEffects')" :key="agent.agentId">
            <strong>{{ agent.agentId || "default-agent" }}</strong>
            <span>{{ agent.totalTasks }} tasks / {{ agent.feedbackTasks }} feedback</span>
            <small>Useful {{ formatPercent(agent.usefulRate) }}</small>
            <small>Adopted {{ formatPercent(agent.adoptedRate) }}</small>
            <small>Resolved {{ formatPercent(agent.resolvedRate) }}</small>
            <small>Failed {{ formatPercent(agent.failedRate) }}</small>
          </article>
          <p v-if="agentEffectRows.length === 0" class="runtime-empty">No agent effect data yet.</p>
        </div>
        <nav v-if="showRuntimePager(agentEffectRows.length)" class="runtime-pagination" aria-label="Agent effect pagination">
          <span>
            Showing {{ runtimePageStart('agentEffects', agentEffectRows.length) }}-{{ runtimePageEnd('agentEffects', agentEffectRows.length) }}
            of {{ agentEffectRows.length }}, {{ pageSize }} per page
          </span>
          <div>
            <button
              type="button"
              :disabled="clampedRuntimePage('agentEffects', agentEffectRows.length) <= 1"
              @click="goRuntimePage('agentEffects', clampedRuntimePage('agentEffects', agentEffectRows.length) - 1, agentEffectRows.length)"
            >
              Previous
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
              Next
            </button>
          </div>
        </nav>
      </section>

      <section v-else class="effect-section">
        <header class="subsection-head">
          <strong>Low-score Tasks</strong>
          <span>{{ lowScoreTasks.length }} items</span>
        </header>
        <div class="low-score-list">
          <button v-for="task in pagedRows(lowScoreTasks, 'lowScores')" :key="task.taskId" type="button" @click="inspectTask(task)">
            <span class="task-id">{{ shortId(task.taskId) }}</span>
            <strong>{{ task.question || "Untitled task" }}</strong>
            <small>
              Useful {{ task.feedbackUseful ? "Yes" : "No" }} / Adopted {{ task.feedbackAdopted ? "Yes" : "No" }} /
              Resolved {{ task.feedbackResolved ? "Yes" : "No" }}
            </small>
            <small v-if="task.feedbackReasonCategory">
              Reason {{ formatFeedbackReason(task.feedbackReasonCategory) }}
            </small>
            <p v-if="task.feedbackComment">{{ task.feedbackComment }}</p>
          </button>
          <p v-if="lowScoreTasks.length === 0" class="runtime-empty">No low-score tasks yet.</p>
        </div>
        <nav v-if="showRuntimePager(lowScoreTasks.length)" class="runtime-pagination" aria-label="Low score task pagination">
          <span>
            Showing {{ runtimePageStart('lowScores', lowScoreTasks.length) }}-{{ runtimePageEnd('lowScores', lowScoreTasks.length) }}
            of {{ lowScoreTasks.length }}, {{ pageSize }} per page
          </span>
          <div>
            <button
              type="button"
              :disabled="clampedRuntimePage('lowScores', lowScoreTasks.length) <= 1"
              @click="goRuntimePage('lowScores', clampedRuntimePage('lowScores', lowScoreTasks.length) - 1, lowScoreTasks.length)"
            >
              Previous
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
              Next
            </button>
          </div>
        </nav>
      </section>
    </section>

    <section v-else-if="activeTab === 'experiences'" class="runtime-panel">
      <header>
        <div>
          <p>Experience Store</p>
          <h2>Experience Store</h2>
        </div>
      </header>

      <div class="experience-subtabs" aria-label="Experience store views">
        <button
          type="button"
          :class="{ active: experienceActiveTab === 'scenarios' }"
          @click="experienceActiveTab = 'scenarios'"
        >
          <strong>Scenario Overview</strong>
          <span>{{ experienceScenarios.length }}</span>
        </button>
        <button
          type="button"
          :class="{ active: experienceActiveTab === 'indexes' }"
          @click="experienceActiveTab = 'indexes'"
        >
          <strong>Structured Indexes</strong>
          <span>{{ experienceIndexes.length }}</span>
        </button>
        <button
          type="button"
          :class="{ active: experienceActiveTab === 'records' }"
          @click="experienceActiveTab = 'records'"
        >
          <strong>Experience Records</strong>
          <span>{{ experienceItems.length }}</span>
        </button>
      </div>

      <section v-if="experienceActiveTab === 'scenarios'" class="experience-tab-panel">
      <header class="subsection-head experience-subsection-head">
        <strong>Scenario Overview</strong>
        <span>{{ experienceScenarios.length }} items</span>
      </header>
      <div class="experience-scenarios">
        <article v-for="scenario in pagedRows(experienceScenarios, 'experienceScenarios')" :key="scenario.scenarioKey || scenario.scenarioName">
          <strong>{{ scenario.scenarioName || scenario.scenarioKey || "-" }}</strong>
          <span>{{ scenario.scenarioKey || "general" }} / {{ scenario.total || 0 }} samples</span>
          <small>Average score {{ scenario.averageScore || 0 }}</small>
        </article>
        <p v-if="experienceScenarios.length === 0" class="runtime-empty">No structured experience indexes yet.</p>
      </div>
      <nav v-if="showRuntimePagination(experienceScenarios.length)" class="runtime-pagination" aria-label="Experience scenario pagination">
        <span>
          Showing {{ runtimePageStart('experienceScenarios', experienceScenarios.length) }}-{{ runtimePageEnd('experienceScenarios', experienceScenarios.length) }}
          of {{ experienceScenarios.length }}, {{ pageSize }} per page
        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('experienceScenarios', experienceScenarios.length) <= 1"
            @click="goRuntimePage('experienceScenarios', clampedRuntimePage('experienceScenarios', experienceScenarios.length) - 1, experienceScenarios.length)"
          >
            Previous
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
            Next
          </button>
        </div>
      </nav>
      </section>

      <section v-else-if="experienceActiveTab === 'indexes'" class="experience-tab-panel">
      <header class="subsection-head experience-subsection-head">
        <strong>Structured Indexes</strong>
        <span>{{ experienceIndexes.length }} items</span>
      </header>
      <div class="experience-index-list">
        <article v-for="index in pagedRows(experienceIndexes, 'experienceIndexes')" :key="`index-${index.id}`">
          <header>
            <strong>{{ index.agentId || "default-agent" }}</strong>
            <span>{{ index.scenario }} / {{ index.intentType || "general" }}</span>
            <b>{{ formatPercent(index.successRate) }}</b>
          </header>
          <dl>
            <div>
              <dt>Tool Chain</dt>
              <dd>{{ index.toolChain || "-" }}</dd>
            </div>
            <div>
              <dt>Keywords</dt>
              <dd>{{ index.keywords || "-" }}</dd>
            </div>
            <div>
              <dt>Counts</dt>
              <dd>
                Useful {{ index.usefulCount }} / Adopted {{ index.adoptedCount }} / Resolved {{ index.resolvedCount }} / Failed
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
          Showing {{ runtimePageStart('experienceIndexes', experienceIndexes.length) }}-{{ runtimePageEnd('experienceIndexes', experienceIndexes.length) }}
          of {{ experienceIndexes.length }}, {{ pageSize }} per page
        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('experienceIndexes', experienceIndexes.length) <= 1"
            @click="goRuntimePage('experienceIndexes', clampedRuntimePage('experienceIndexes', experienceIndexes.length) - 1, experienceIndexes.length)"
          >
            Previous
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
            Next
          </button>
        </div>
      </nav>
      </section>

      <section v-else class="experience-tab-panel">
      <header class="subsection-head experience-subsection-head">
        <strong>Experience Records</strong>
        <span>{{ experienceItems.length }} items</span>
      </header>
      <div class="experience-list">
        <article v-for="experience in pagedRows(experienceItems, 'experiences')" :key="experience.experienceId">
          <header>
            <div>
              <strong>{{ experience.scenarioName || experience.scenarioKey }}</strong>
              <span>{{ experience.agentId || "default-agent" }} / {{ shortId(experience.taskId) }}</span>
            </div>
            <b>{{ experience.feedbackScore || 0 }}</b>
          </header>
          <p>{{ experience.attributionSummary || experience.question }}</p>
          <dl>
            <div>
              <dt>Attribution</dt>
              <dd>{{ experience.attributionSource || "rule" }}</dd>
            </div>
            <div>
              <dt>Reason</dt>
              <dd>{{ formatFeedbackReason(experience.feedbackReasonCategory) }}</dd>
            </div>
            <div>
              <dt>Feedback</dt>
              <dd>
                Useful {{ experience.feedbackUseful ? "Yes" : "No" }} / Adopted
                {{ experience.feedbackAdopted ? "Yes" : "No" }} / Resolved
                {{ experience.feedbackResolved ? "Yes" : "No" }}
              </dd>
            </div>
          </dl>
          <div class="experience-patterns">
            <section>
              <strong>Success Patterns</strong>
              <span v-for="item in experience.successPattern" :key="`success-${experience.experienceId}-${item}`">
                {{ item }}
              </span>
            </section>
            <section>
              <strong>Improvement Suggestions</strong>
              <span
                v-for="item in experience.improvementSuggestions"
                :key="`improve-${experience.experienceId}-${item}`"
              >
                {{ item }}
              </span>
            </section>
          </div>
        </article>
        <p v-if="experienceItems.length === 0" class="runtime-empty">No experience records yet.</p>
      </div>
      <nav v-if="showRuntimePagination(experienceItems.length)" class="runtime-pagination" aria-label="Experience item pagination">
        <span>
          Showing {{ runtimePageStart('experiences', experienceItems.length) }}-{{ runtimePageEnd('experiences', experienceItems.length) }}
          of {{ experienceItems.length }}, {{ pageSize }} per page
        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('experiences', experienceItems.length) <= 1"
            @click="goRuntimePage('experiences', clampedRuntimePage('experiences', experienceItems.length) - 1, experienceItems.length)"
          >
            Previous
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
            Next
          </button>
        </div>
      </nav>
      </section>
    </section>

    <section v-else-if="activeTab === 'events'" class="runtime-panel">
      <header>
        <div>
          <p>Event Store</p>
          <h2>Event Chain</h2>
        </div>
        <button type="button" :disabled="!selectedTask || eventsLoading" @click="reloadEvents">
          <Database :size="15" stroke-width="2" />
          <span>{{ eventsLoading ? "Loading" : "Load" }}</span>
        </button>
      </header>

      <div class="runtime-toolbar runtime-toolbar-wide">
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select :value="selectedTaskId" @change="onSelectedTaskChange($event.target.value)">
            <option value="">Select task</option>
            <option v-for="task in tasks" :key="task.taskId" :value="task.taskId">
              {{ shortId(task.taskId) }} / {{ task.question || "Untitled task" }}
            </option>
          </select>
        </label>
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select v-model="eventTypeFilter">
            <option value="">All events</option>
            <option v-for="type in eventTypeOptions" :key="type" :value="type">{{ type }}</option>
          </select>
        </label>
        <label class="runtime-search-field">
          <Search :size="16" stroke-width="2" />
          <input v-model.trim="eventSearchQuery" type="text" placeholder="Search event type, status, tool, or error" />
        </label>
      </div>

      <div v-if="selectedTaskDisplay" class="selected-task">
        <strong>{{ selectedTaskDisplay.id }}</strong>
        <span>{{ selectedTaskDisplay.subtitle }}</span>
        <p>{{ selectedTaskDisplay.description }}</p>
        <div class="task-feedback">
          <label>
            <input v-model="feedbackDraft.useful" type="checkbox" :disabled="!canRecordFeedback || feedbackSubmitting" />
            Useful
          </label>
          <label>
            <input v-model="feedbackDraft.adopted" type="checkbox" :disabled="!canRecordFeedback || feedbackSubmitting" />
            Adopted
          </label>
          <label>
            <input v-model="feedbackDraft.resolved" type="checkbox" :disabled="!canRecordFeedback || feedbackSubmitting" />
            Resolved
          </label>
          <input
            v-model.trim="feedbackDraft.comment"
            type="text"
            :disabled="!canRecordFeedback || feedbackSubmitting"
            maxlength="1000"
            placeholder="Feedback notes"
          />
          <select
            v-model="feedbackDraft.reasonCategory"
            :disabled="!canRecordFeedback || feedbackSubmitting"
            aria-label="Feedback reason category"
          >
            <option v-for="option in feedbackReasonOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
          <button type="button" :disabled="!canRecordFeedback || feedbackSubmitting" @click="saveTaskFeedback">
            <ShieldCheck :size="14" stroke-width="2" />
            <span>{{ feedbackSubmitting ? "Saving" : "Save feedback" }}</span>
          </button>
        </div>
      </div>

      <div class="event-timeline">
        <article v-for="event in pagedRows(filteredEvents, 'events')" :key="event.eventId">
          <span :class="statusClass(event.status)">{{ event.type }}</span>
          <strong>{{ event.status || "UNKNOWN" }}</strong>
          <time>{{ formatEventTime(event.createTime) }}</time>
        </article>
        <p v-if="!selectedTask" class="runtime-empty">Select a task from the task page or task selector first.</p>
        <p v-else-if="!eventsLoading && filteredEvents.length === 0" class="runtime-empty">No matching event records.</p>
      </div>
      <nav v-if="showRuntimePager(filteredEvents.length)" class="runtime-pagination" aria-label="Event pagination">
        <span>
          Showing {{ runtimePageStart('events', filteredEvents.length) }}-{{ runtimePageEnd('events', filteredEvents.length) }}
          of {{ filteredEvents.length }}, {{ pageSize }} per page
        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('events', filteredEvents.length) <= 1"
            @click="goRuntimePage('events', clampedRuntimePage('events', filteredEvents.length) - 1, filteredEvents.length)"
          >
            Previous
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
            Next
          </button>
        </div>
      </nav>
    </section>

    <section v-else-if="activeTab === 'plan'" class="runtime-panel">
      <header>
        <div>
          <p>Interpretation Plan</p>
          <h2>Plan DAG</h2>
        </div>
        <button type="button" :disabled="!selectedTask || planLoading" @click="loadPlanDag">
          <GitBranch :size="15" stroke-width="2" />
          <span>{{ planLoading ? "Loading" : "Load" }}</span>
        </button>
      </header>

      <div class="runtime-toolbar runtime-toolbar-wide">
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select :value="selectedTaskId" @change="onSelectedTaskChange($event.target.value)">
            <option value="">Select task</option>
            <option v-for="task in tasks" :key="task.taskId" :value="task.taskId">
              {{ shortId(task.taskId) }} / {{ task.question || "Untitled task" }}
            </option>
          </select>
        </label>
        <span class="runtime-pill">{{ latestPlanVersionLabel }}</span>
        <span class="runtime-pill">{{ planNodes.length }} nodes</span>
        <span class="runtime-pill">{{ planEdges.length }} edges</span>
      </div>

      <div v-if="selectedTaskDisplay" class="selected-task">
        <strong>{{ selectedTaskDisplay.id }}</strong>
        <span>{{ selectedTaskDisplay.subtitle }}</span>
        <p>{{ selectedTaskDisplay.description }}</p>
      </div>

      <div v-if="selectedTask && planNodes.length > 0" class="plan-dag-layout">
        <aside class="plan-dag-side">
          <div>
            <span>Snapshot</span>
            <strong>{{ selectedPlanDag.planId || "-" }}</strong>
            <small>{{ selectedPlanDag.status || "GENERATED" }} · {{ formatTime(selectedPlanDag.updatedAt) }}</small>
          </div>
          <div>
            <span>Versions</span>
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
          <div class="plan-dag-controls">
            <button type="button" @click="zoomPlanDag(0.8)">-</button>
            <span>{{ planZoomLabel }}</span>
            <button type="button" @click="zoomPlanDag(1.25)">+</button>
            <button type="button" @click="resetPlanDagView">Reset</button>
            <button type="button" @click="downloadPlanDagSvg">SVG</button>
            <button type="button" @click="downloadPlanDagJson">JSON</button>
          </div>
          <svg
            ref="planDagSvg"
            class="plan-dag-svg"
            :class="{ dragging: planDragActive }"
            :viewBox="planDagViewBox"
            preserveAspectRatio="xMinYMin meet"
            role="img"
            aria-label="Interpretation plan DAG"
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
                <rect :x="edge.x - 58" :y="edge.y - 15" width="116" height="24" rx="6" />
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
              <text x="20" y="30" class="plan-dag-node-title">{{ node.labelText }}</text>
              <text x="20" y="62">{{ node.toolText }}</text>
              <text x="20" y="94" class="plan-dag-node-meta">#{{ node.stepId || node.id }} · {{ node.actionText }}</text>
            </g>
          </svg>
        </div>

        <div class="plan-dag-node-list">
          <article v-for="node in planNodeViews" :key="`${node.id}-detail`" :class="String(node.statusText || '').toLowerCase()">
            <span>{{ node.statusText }}</span>
            <div>
              <strong>{{ node.fullLabelText }}</strong>
              <small>{{ node.toolName || node.actionText }} · {{ formatDuration(node.durationMs) }}</small>
              <p v-if="node.detailText">{{ node.detailText }}</p>
            </div>
          </article>
        </div>
      </div>
      <p v-if="!selectedTask" class="runtime-empty">Select a task from the task page or task selector first.</p>
      <p v-else-if="!planLoading && planNodes.length === 0" class="runtime-empty">No InterpretationPlan DAG snapshot for this task.</p>
    </section>

    <section v-else-if="activeTab === 'tools'" class="runtime-panel">
      <header>
        <div>
          <p>Tool Runtime</p>
          <h2>Tool Runtime</h2>
        </div>
      </header>

      <div class="runtime-toolbar">
        <label class="runtime-search-field">
          <Search :size="16" stroke-width="2" />
          <input v-model.trim="toolSearchQuery" type="text" placeholder="Search tool name or runtime metrics" />
        </label>
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select v-model="toolHealthFilter">
            <option value="">All health states</option>
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
            <small>{{ tool.totalCalls }} calls</small>
          </div>
          <span :class="statusClass(toolHealth(tool) === 'problem' ? 'failed' : 'success')">
            {{ toolHealth(tool) === "problem" ? "Needs attention" : "Stable" }}
          </span>
          <small>
            {{ tool.deniedCalls }} denied / {{ tool.rateLimitedCalls }} rate limited /
            {{ formatDuration(tool.averageDurationMs) }}
          </small>
        </article>
        <p v-if="filteredTopTools.length === 0" class="runtime-empty">No matching tool runtime records.</p>
      </div>
      <nav v-if="showRuntimePagination(filteredTopTools.length)" class="runtime-pagination" aria-label="Tool runtime pagination">
        <span>
          Showing {{ runtimePageStart('tools', filteredTopTools.length) }}-{{ runtimePageEnd('tools', filteredTopTools.length) }}
          of {{ filteredTopTools.length }}, {{ pageSize }} per page
        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('tools', filteredTopTools.length) <= 1"
            @click="goRuntimePage('tools', clampedRuntimePage('tools', filteredTopTools.length) - 1, filteredTopTools.length)"
          >
            Previous
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
            Next
          </button>
        </div>
      </nav>
    </section>

    <section v-else-if="activeTab === 'governance'" class="runtime-panel">
      <header>
        <div>
          <p>Tool Governance</p>
          <h2>Tool Security Levels</h2>
        </div>
      </header>

      <div class="runtime-toolbar">
        <label class="runtime-search-field">
          <Search :size="16" stroke-width="2" />
          <input v-model.trim="governanceSearchQuery" type="text" placeholder="Search tool, service, level, or policy" />
        </label>
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select v-model="governanceLevelFilter">
            <option value="">All levels</option>
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
          <small>{{ tool.mcpSynchronized ? "MCP synced" : "Local tool" }}</small>
          <small>{{ tool.totalCalls }} calls / {{ tool.deniedCalls }} denied</small>
        </article>
        <p v-if="filteredGovernanceTools.length === 0" class="runtime-empty">No matching tool governance records.</p>
      </div>
      <nav v-if="showRuntimePager(filteredGovernanceTools.length)" class="runtime-pagination" aria-label="Tool governance pagination">
        <span>
          Showing {{ runtimePageStart('governance', filteredGovernanceTools.length) }}-{{ runtimePageEnd('governance', filteredGovernanceTools.length) }}
          of {{ filteredGovernanceTools.length }}, {{ pageSize }} per page
        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('governance', filteredGovernanceTools.length) <= 1"
            @click="goRuntimePage('governance', clampedRuntimePage('governance', filteredGovernanceTools.length) - 1, filteredGovernanceTools.length)"
          >
            Previous
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
            Next
          </button>
        </div>
      </nav>
    </section>

    <section v-else class="runtime-panel">
      <header>
        <div>
          <p>Audit Center</p>
          <h2>Tool Governance Logs</h2>
        </div>
      </header>

      <div class="runtime-toolbar">
        <label class="runtime-search-field">
          <Search :size="16" stroke-width="2" />
          <input v-model.trim="auditSearchQuery" type="text" placeholder="Search tool, user, mode, service, or error" />
        </label>
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select v-model="auditOutcomeFilter">
            <option value="">All outcomes</option>
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
        <p v-if="filteredAudits.length === 0" class="runtime-empty">No matching governance logs.</p>
      </div>
      <nav v-if="showRuntimePagination(filteredAudits.length)" class="runtime-pagination" aria-label="Audit pagination">
        <span>
          Showing {{ runtimePageStart('audits', filteredAudits.length) }}-{{ runtimePageEnd('audits', filteredAudits.length) }}
          of {{ filteredAudits.length }}, {{ pageSize }} per page
        </span>
        <div>
          <button
            type="button"
            :disabled="clampedRuntimePage('audits', filteredAudits.length) <= 1"
            @click="goRuntimePage('audits', clampedRuntimePage('audits', filteredAudits.length) - 1, filteredAudits.length)"
          >
            Previous
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
            Next
          </button>
        </div>
      </nav>
    </section>
  </section>
</template>

<script src="../js/views/TasksView.js"></script>
