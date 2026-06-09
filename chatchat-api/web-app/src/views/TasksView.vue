<template>
  <section class="feature-view runtime-view">
    <header class="runtime-header">
      <div class="runtime-title">
        <p>Agent Runtime</p>
        <h1>运行控制台</h1>
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
        </button>
        <p v-if="!loading && filteredTasks.length === 0" class="runtime-empty">没有匹配的任务实例</p>
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
