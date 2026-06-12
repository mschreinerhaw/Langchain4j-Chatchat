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
          v-for="task in filteredTasks"
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
    </section>

    <section v-else-if="activeTab === 'effects'" class="runtime-panel">
      <header>
        <div>
          <p>Agent Effect</p>
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

      <div class="effect-subtabs" aria-label="效果分析明细">
        <button
          type="button"
          :class="{ active: effectActiveTab === 'agents' }"
          @click="effectActiveTab = 'agents'"
        >
          <Activity :size="15" stroke-width="2" />
          <strong>Agent 聚合</strong>
          <span>{{ agentEffectRows.length }}</span>
        </button>
        <button
          type="button"
          :class="{ active: effectActiveTab === 'lowScores' }"
          @click="effectActiveTab = 'lowScores'"
        >
          <ShieldAlert :size="15" stroke-width="2" />
          <strong>低评分任务</strong>
          <span>{{ lowScoreTasks.length }}</span>
        </button>
      </div>

      <div v-if="reasonMetrics.length > 0" class="reason-metrics">
        <article v-for="reason in reasonMetrics" :key="reason.reasonCategory">
          <strong>{{ reason.label }}</strong>
          <span>{{ reason.total }} 条</span>
          <small>{{ formatPercent(reason.share) }}</small>
        </article>
      </div>

      <section v-if="effectActiveTab === 'agents'" class="effect-section">
        <header class="subsection-head">
          <strong>Agent 聚合</strong>
          <span>{{ agentEffectRows.length }} 个 Agent</span>
        </header>
        <div class="effect-table">
          <article v-for="agent in agentEffectRows" :key="agent.agentId">
            <strong>{{ agent.agentId || "default-agent" }}</strong>
            <span>{{ agent.totalTasks }} 任务 · {{ agent.feedbackTasks }} 反馈</span>
            <small>有用 {{ formatPercent(agent.usefulRate) }}</small>
            <small>采纳 {{ formatPercent(agent.adoptedRate) }}</small>
            <small>解决 {{ formatPercent(agent.resolvedRate) }}</small>
            <small>失败 {{ formatPercent(agent.failedRate) }}</small>
          </article>
          <p v-if="agentEffectRows.length === 0" class="runtime-empty">暂无 Agent 效果数据</p>
        </div>
      </section>

      <section v-else class="effect-section">
        <header class="subsection-head">
          <strong>低评分任务</strong>
          <span>{{ lowScoreTasks.length }} 条</span>
        </header>
        <div class="low-score-list">
          <button v-for="task in lowScoreTasks" :key="task.taskId" type="button" @click="inspectTask(task)">
            <span class="task-id">{{ shortId(task.taskId) }}</span>
            <strong>{{ task.question || "未命名任务" }}</strong>
            <small>
              有用 {{ task.feedbackUseful ? "是" : "否" }} · 采纳 {{ task.feedbackAdopted ? "是" : "否" }} ·
              解决 {{ task.feedbackResolved ? "是" : "否" }}
            </small>
            <small v-if="task.feedbackReasonCategory">
              原因 {{ formatFeedbackReason(task.feedbackReasonCategory) }}
            </small>
            <p v-if="task.feedbackComment">{{ task.feedbackComment }}</p>
          </button>
          <p v-if="lowScoreTasks.length === 0" class="runtime-empty">暂无低评分任务</p>
        </div>
      </section>
    </section>

    <section v-else-if="activeTab === 'experiences'" class="runtime-panel">
      <header>
        <div>
          <p>Experience Store</p>
          <h2>经验库</h2>
        </div>
      </header>

      <div class="experience-scenarios">
        <article v-for="index in experienceIndexes" :key="index.id">
          <strong>{{ index.scenario }}</strong>
          <span>{{ index.intentType || "general" }} · {{ index.sampleCount }} 样本</span>
          <small>成功率 {{ formatPercent(index.successRate) }}</small>
        </article>
        <p v-if="experienceIndexes.length === 0" class="runtime-empty">暂无结构化经验索引</p>
      </div>

      <div class="experience-index-list">
        <article v-for="index in experienceIndexes" :key="`index-${index.id}`">
          <header>
            <strong>{{ index.agentId || "default-agent" }}</strong>
            <span>{{ index.scenario }} · {{ index.intentType || "general" }}</span>
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
              <dt>计数</dt>
              <dd>
                有用 {{ index.usefulCount }} · 采纳 {{ index.adoptedCount }} · 解决 {{ index.resolvedCount }} · 失败
                {{ index.failedCount }}
              </dd>
            </div>
          </dl>
          <p v-if="index.bestPractice">Best: {{ index.bestPractice }}</p>
          <p v-if="index.avoidPattern">Avoid: {{ index.avoidPattern }}</p>
        </article>
      </div>

      <div class="experience-list">
        <article v-for="experience in experienceItems" :key="experience.experienceId">
          <header>
            <div>
              <strong>{{ experience.scenarioName || experience.scenarioKey }}</strong>
              <span>{{ experience.agentId || "default-agent" }} · {{ shortId(experience.taskId) }}</span>
            </div>
            <b>{{ experience.feedbackScore || 0 }}</b>
          </header>
          <p>{{ experience.attributionSummary || experience.question }}</p>
          <dl>
            <div>
              <dt>归因</dt>
              <dd>{{ experience.attributionSource || "rule" }}</dd>
            </div>
            <div>
              <dt>原因</dt>
              <dd>{{ formatFeedbackReason(experience.feedbackReasonCategory) }}</dd>
            </div>
            <div>
              <dt>反馈</dt>
              <dd>
                有用 {{ experience.feedbackUseful ? "是" : "否" }} · 采纳
                {{ experience.feedbackAdopted ? "是" : "否" }} · 解决
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
        <p v-if="experienceItems.length === 0" class="runtime-empty">暂无经验记录</p>
      </div>
    </section>

    <section v-else-if="activeTab === 'events'" class="runtime-panel">
      <header>
        <div>
          <p>Event Store</p>
          <h2>事件链路</h2>
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
            <option value="">选择任务</option>
            <option v-for="task in tasks" :key="task.taskId" :value="task.taskId">
              {{ shortId(task.taskId) }} · {{ task.question || "未命名任务" }}
            </option>
          </select>
        </label>
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select v-model="eventTypeFilter">
            <option value="">全部事件</option>
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
            有用
          </label>
          <label>
            <input v-model="feedbackDraft.adopted" type="checkbox" :disabled="!canRecordFeedback || feedbackSubmitting" />
            已采纳
          </label>
          <label>
            <input v-model="feedbackDraft.resolved" type="checkbox" :disabled="!canRecordFeedback || feedbackSubmitting" />
            已解决
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
            <span>{{ feedbackSubmitting ? "记录中" : "记录反馈" }}</span>
          </button>
        </div>
      </div>

      <div class="event-timeline">
        <article v-for="event in filteredEvents" :key="event.eventId">
          <span :class="statusClass(event.status)">{{ event.type }}</span>
          <strong>{{ event.status || "UNKNOWN" }}</strong>
          <time>{{ formatEventTime(event.createTime) }}</time>
        </article>
        <p v-if="!selectedTask" class="runtime-empty">先在任务页或任务选择器中选中一个任务</p>
        <p v-else-if="!eventsLoading && filteredEvents.length === 0" class="runtime-empty">没有匹配的事件记录</p>
      </div>
    </section>

    <section v-else-if="activeTab === 'tools'" class="runtime-panel">
      <header>
        <div>
          <p>Tool Runtime</p>
          <h2>工具治理</h2>
        </div>
      </header>

      <div class="runtime-toolbar">
        <label class="runtime-search-field">
          <Search :size="16" stroke-width="2" />
          <input v-model.trim="toolSearchQuery" type="text" placeholder="搜索工具名或运行指标" />
        </label>
        <label class="runtime-select-field">
          <ListFilter :size="15" stroke-width="2" />
          <select v-model="toolHealthFilter">
            <option value="">全部健康度</option>
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
        <article v-for="tool in filteredTopTools" :key="tool.toolName">
          <div>
            <strong>{{ tool.toolName }}</strong>
            <small>{{ tool.totalCalls }} 次调用</small>
          </div>
          <span :class="statusClass(toolHealth(tool) === 'problem' ? 'failed' : 'success')">
            {{ toolHealth(tool) === "problem" ? "需关注" : "稳定" }}
          </span>
          <small>
            {{ tool.deniedCalls }} 拒绝 · {{ tool.rateLimitedCalls }} 限流 ·
            {{ formatDuration(tool.averageDurationMs) }}
          </small>
        </article>
        <p v-if="filteredTopTools.length === 0" class="runtime-empty">没有匹配的工具运行记录</p>
      </div>
    </section>

    <section v-else-if="activeTab === 'governance'" class="runtime-panel">
      <header>
        <div>
          <p>Tool Governance</p>
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
        <article v-for="tool in filteredGovernanceTools" :key="tool.toolName">
          <div>
            <strong>{{ tool.displayName || tool.toolName }}</strong>
            <small>{{ tool.toolName }} · {{ tool.sourceType }}</small>
          </div>
          <span :class="statusClass(tool.disabled ? 'denied' : tool.confirmationRequired ? 'waiting' : 'success')">
            {{ formatRuntimeLevel(tool.runtimeLevel) }}
          </span>
          <small>{{ formatRuntimeAction(tool.defaultAction) }}</small>
          <small>{{ tool.mcpSynchronized ? "MCP 已同步" : "本地工具" }}</small>
          <small>{{ tool.totalCalls }} 调用 · {{ tool.deniedCalls }} 拒绝</small>
        </article>
        <p v-if="filteredGovernanceTools.length === 0" class="runtime-empty">没有匹配的工具治理记录</p>
      </div>
    </section>

    <section v-else class="runtime-panel">
      <header>
        <div>
          <p>Audit Center</p>
          <h2>工具治理日志</h2>
        </div>
      </header>

      <div class="runtime-toolbar">
        <label class="runtime-search-field">
          <Search :size="16" stroke-width="2" />
          <input v-model.trim="auditSearchQuery" type="text" placeholder="搜索工具、用户、模式、服务或错误信息" />
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
        <article v-for="audit in filteredAudits" :key="audit.id">
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
        <p v-if="filteredAudits.length === 0" class="runtime-empty">没有匹配的治理日志</p>
      </div>
    </section>
  </section>
</template>

<script src="../js/views/TasksView.js"></script>
