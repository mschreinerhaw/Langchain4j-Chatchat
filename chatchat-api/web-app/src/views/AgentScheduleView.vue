<template>
  <main class="agent-schedule-view">
    <header>
      <div>
        <p>Agent Scheduler</p>
        <h1>Agent定时调度</h1>
      </div>
      <div class="schedule-head-actions">
        <button type="button" class="light-button" :disabled="loading || scheduleLoading" @click="reload">
          {{ loading || scheduleLoading ? "刷新中" : "刷新" }}
        </button>
        <button type="button" class="primary-button" :disabled="loading" @click="openCreateDialog">
          新建调度
        </button>
      </div>
    </header>

    <section class="schedule-search-panel">
      <label class="field">
        <span>关键词</span>
        <input v-model.trim="filters.keyword" type="search" placeholder="搜索任务名称、问题或Agent" />
      </label>
      <label class="field">
        <span>Agent</span>
        <select v-model="filters.agentId" :disabled="loading || scheduleLoading" @change="loadSchedules">
          <option value="">全部Agent</option>
          <option v-for="agent in agentOptions" :key="agent.id" :value="agent.id">
            {{ agent.name || agent.id }}
          </option>
        </select>
      </label>
      <label class="field">
        <span>状态</span>
        <select v-model="filters.status">
          <option value="">全部状态</option>
          <option value="ACTIVE">ACTIVE</option>
          <option value="PAUSED">PAUSED</option>
          <option value="RUNNING">RUNNING</option>
          <option value="COMPLETED">COMPLETED</option>
          <option value="FAILED">FAILED</option>
          <option value="SCHEDULE_ERROR">调度异常</option>
          <option value="SKIPPED_NON_TRADING_DAY">非交易日已跳过</option>
          <option value="CANCELLED">CANCELLED</option>
          <option value="EXPIRED">EXPIRED</option>
        </select>
      </label>
    </section>

    <p v-if="error && !dialogOpen" class="schedule-error">{{ error }}</p>
    <p v-if="notice && !dialogOpen" class="schedule-notice">{{ notice }}</p>

    <section class="schedule-table-panel">
      <header>
        <div>
          <strong>调度任务</strong>
          <span>{{ filteredSchedules.length }} / {{ schedules.length }} 个</span>
        </div>
      </header>

      <div class="schedule-table">
        <div class="schedule-table-head">
          <span>任务</span>
          <span>Agent</span>
          <span>调度</span>
          <span>下次执行</span>
          <span>状态</span>
          <span>操作</span>
        </div>
        <p v-if="!scheduleLoading && filteredSchedules.length === 0" class="schedule-empty">暂无匹配任务</p>
        <article v-for="schedule in filteredSchedules" :key="schedule.scheduleId || schedule.taskId" class="schedule-table-row">
          <div class="schedule-main-cell">
            <strong>{{ schedule.name || schedule.taskId }}</strong>
            <p>{{ schedule.question }}</p>
            <small v-if="schedule.tradingDayOnly" class="trading-day-tag">仅交易日</small>
          </div>
          <span>{{ scheduleAgentName(schedule) }}</span>
          <span>{{ scheduleTimeLabel(schedule) }}</span>
          <span>{{ formatDateTime(schedule.nextFireTime) }}</span>
          <div class="schedule-status-cell">
            <b :class="scheduleStatusClass(scheduleEffectiveStatus(schedule))">{{ scheduleStatusLabel(schedule) }}</b>
            <small v-if="schedule.lastError" :title="schedule.lastError">{{ schedule.lastError }}</small>
          </div>
          <div class="schedule-row-actions">
            <button type="button" class="light-button" :disabled="saving" @click="toggleSchedule(schedule)">
              {{ isScheduleActive(schedule) ? "停用" : "启用" }}
            </button>
            <button type="button" class="light-button" :disabled="saving" @click="rerunSchedule(schedule)">执行</button>
            <button type="button" class="danger-button" :disabled="saving" @click="removeSchedule(schedule)">删除</button>
          </div>
        </article>
      </div>
    </section>

    <div v-if="dialogOpen" class="schedule-dialog-backdrop" @click.self="closeCreateDialog">
      <form class="schedule-dialog" @submit.prevent="createSchedule">
        <header>
          <div>
            <p>Schedule Form</p>
            <h2>新建Agent调度</h2>
          </div>
          <button type="button" aria-label="关闭" title="关闭" @click="closeCreateDialog">×</button>
        </header>

        <section>
          <div class="section-head">
            <span>1</span>
            <strong>选择Agent</strong>
          </div>
          <label class="field">
            <span>已授权Agent</span>
            <select v-model="form.agentId" :disabled="loading || saving">
              <option value="">请选择Agent</option>
              <option v-for="agent in agentOptions" :key="agent.id" :value="agent.id">
                {{ agent.name || agent.id }}
              </option>
            </select>
          </label>
          <div v-if="selectedAgent" class="agent-snapshot">
            <strong>{{ selectedAgent.name || selectedAgent.id }}</strong>
            <p>{{ selectedAgent.description || "暂无描述" }}</p>
            <span>{{ selectedAgent.marketStatus === "published" ? "已发布" : "未发布" }}</span>
          </div>
        </section>

        <section>
          <div class="section-head">
            <span>2</span>
            <strong>问题</strong>
          </div>
          <label class="field">
            <span>执行问题</span>
            <textarea
              v-model.trim="form.question"
              :disabled="saving"
              rows="6"
              placeholder="请输入定时执行时要发给Agent的问题"
            ></textarea>
          </label>
        </section>

        <section>
          <div class="section-head">
            <span>3</span>
            <strong>调度时间设置</strong>
          </div>
          <div class="schedule-mode-tabs">
            <button
              v-for="mode in scheduleModes"
              :key="mode.value"
              type="button"
              :class="{ active: form.mode === mode.value }"
              :disabled="saving"
              @click="form.mode = mode.value"
            >
              {{ mode.label }}
            </button>
          </div>

          <label class="field">
            <span>任务名称</span>
            <input v-model.trim="form.name" :disabled="saving" placeholder="例如：客户线索日报" />
          </label>

          <div v-if="form.mode === 'once'" class="schedule-time-grid">
            <label class="field">
              <span>执行时间</span>
              <input v-model="form.onceAt" :disabled="saving" type="datetime-local" />
            </label>
          </div>

          <div v-else-if="form.mode === 'daily'" class="schedule-time-grid">
            <label class="field">
              <span>每天时间</span>
              <input v-model="form.dailyTime" :disabled="saving" type="time" />
            </label>
          </div>

          <div v-else-if="form.mode === 'weekly'" class="schedule-time-grid two">
            <label class="field">
              <span>每周</span>
              <select v-model="form.weekday" :disabled="saving">
                <option v-for="day in weekdays" :key="day.value" :value="day.value">{{ day.label }}</option>
              </select>
            </label>
            <label class="field">
              <span>时间</span>
              <input v-model="form.weeklyTime" :disabled="saving" type="time" />
            </label>
          </div>

          <div v-else-if="form.mode === 'interval'" class="schedule-time-grid">
            <label class="field">
              <span>间隔分钟</span>
              <input v-model.number="form.intervalMinutes" :disabled="saving" type="number" min="1" step="1" />
            </label>
          </div>

          <label v-else class="field">
            <span>CRON</span>
            <input v-model.trim="form.cron" :disabled="saving" placeholder="0 30 8 * * ?" />
          </label>

          <div class="schedule-options">
            <label>
              <input v-model="form.enabled" :disabled="saving" type="checkbox" />
              <span>创建后启用</span>
            </label>
            <label>
              <input v-model="form.notifyEnabled" :disabled="saving" type="checkbox" />
              <span>完成后通知</span>
            </label>
            <label title="触发时由MCP交易日接口判断；非交易日自动跳过">
              <input v-model="form.tradingDayOnly" :disabled="saving" type="checkbox" />
              <span>仅交易日执行</span>
            </label>
          </div>
        </section>

        <p v-if="error" class="schedule-error">{{ error }}</p>
        <p v-if="notice" class="schedule-notice">{{ notice }}</p>

        <footer>
          <button type="button" class="light-button" :disabled="saving" @click="closeCreateDialog">取消</button>
          <button type="submit" class="primary-button" :disabled="saving || loading">
            {{ saving ? "创建中" : "创建定时任务" }}
          </button>
        </footer>
      </form>
    </div>
  </main>
</template>

<script src="../js/views/AgentScheduleView.js"></script>
