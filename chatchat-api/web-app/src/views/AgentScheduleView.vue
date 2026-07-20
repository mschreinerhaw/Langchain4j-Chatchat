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
        <button type="button" class="light-button" :disabled="notificationLoading" @click="openNotificationOverview">
          {{ notificationLoading ? "加载中" : "通知方式" }}
        </button>
        <button type="button" class="primary-button" :disabled="loading || agentOptions.length === 0" title="只有已发布且已授权的Agent可以创建调度" @click="openCreateDialog">
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
          <option value="AGENT_AUTHORIZATION_DENIED">Agent未授权</option>
          <option value="AGENT_UNPUBLISHED">Agent未发布</option>
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
          <span>通知类型</span>
          <span>状态</span>
          <span>操作</span>
        </div>
        <p v-if="!scheduleLoading && filteredSchedules.length === 0" class="schedule-empty">暂无匹配任务</p>
        <article v-for="schedule in filteredSchedules" :key="schedule.scheduleId || schedule.taskId" class="schedule-table-row">
          <div class="schedule-main-cell">
            <strong>{{ schedule.name || schedule.taskId }}</strong>
            <p>{{ schedule.question }}</p>
            <small v-if="schedule.tradingDayOnly" class="trading-day-tag">仅交易日</small>
            <small v-if="schedule.scheduleWindowEnabled" class="schedule-window-tag">
              {{ scheduleWindowLabel(schedule) }}
            </small>
          </div>
          <span>{{ scheduleAgentName(schedule) }}</span>
          <span>{{ scheduleTimeLabel(schedule) }}</span>
          <span>{{ formatDateTime(schedule.nextFireTime) }}</span>
          <span>{{ notificationTypeLabel(schedule) }}</span>
          <div class="schedule-status-cell">
            <b :class="scheduleStatusClass(scheduleEffectiveStatus(schedule))">
              <i v-if="isScheduleRunning(schedule)" class="schedule-spinner" aria-hidden="true"></i>
              {{ scheduleStatusLabel(schedule) }}
            </b>
            <small v-if="schedule.lastError" :title="schedule.lastError">{{ schedule.lastError }}</small>
          </div>
          <div class="schedule-row-actions">
            <button type="button" class="text-button" @click="openNotificationHistory(schedule)">详情</button>
            <button type="button" class="light-button" :disabled="saving || isScheduleRunning(schedule)" @click="openEditDialog(schedule)">
              编辑
            </button>
            <button type="button" class="light-button" :disabled="saving" @click="toggleSchedule(schedule)">
              {{ isScheduleActive(schedule) ? "停用" : "启用" }}
            </button>
            <button type="button" class="light-button" :disabled="saving || isScheduleRunning(schedule)" @click="rerunSchedule(schedule)">
              <i v-if="isScheduleRunning(schedule)" class="schedule-spinner" aria-hidden="true"></i>
              {{ isScheduleRunning(schedule) ? "运行中" : "执行" }}
            </button>
            <button type="button" class="danger-button" :disabled="saving" @click="removeSchedule(schedule)">删除</button>
          </div>
        </article>
      </div>
    </section>

    <div v-if="dialogOpen" class="schedule-dialog-backdrop">
      <form class="schedule-dialog" @submit.prevent="createSchedule">
        <header>
          <div>
            <p>Schedule Form</p>
            <h2>{{ editingScheduleId ? "编辑Agent调度" : "新建Agent调度" }}</h2>
          </div>
          <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" @click="closeCreateDialog">×</button>
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
          <p v-if="agentOptions.length === 0" class="schedule-error">暂无已发布且已授权的 Agent，不能创建调度。</p>
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
              @click="setScheduleMode(mode.value)"
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

          <div v-else-if="form.mode === 'daily'" class="schedule-time-grid schedule-primary-time-card">
            <div class="schedule-time-copy">
              <strong>每天执行</strong>
              <small>Agent 将在每天的固定时间自动触发</small>
            </div>
            <label class="field schedule-clock-field">
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

          <div
            v-if="form.mode !== 'once'"
            class="schedule-window-panel"
            :class="{ enabled: form.scheduleWindowEnabled }"
          >
            <div class="schedule-window-top">
              <label class="schedule-window-head">
                <input v-model="form.scheduleWindowEnabled" :disabled="saving" type="checkbox" />
                <span>
                  <strong>限制每日允许执行时段</strong>
                  <small>只在指定时间范围内执行自动调度</small>
                </span>
              </label>
              <span v-if="form.scheduleWindowEnabled" class="schedule-window-summary">
                {{ form.scheduleWindowStart }} → {{ form.scheduleWindowEnd }}
              </span>
            </div>
            <div v-if="form.scheduleWindowEnabled" class="schedule-window-grid">
              <label class="field schedule-clock-field">
                <span>开始时间</span>
                <input v-model="form.scheduleWindowStart" :disabled="saving" type="time" />
              </label>
              <span class="schedule-window-arrow" aria-hidden="true">→</span>
              <label class="field schedule-clock-field">
                <span>结束时间</span>
                <input v-model="form.scheduleWindowEnd" :disabled="saving" type="time" />
              </label>
              <label class="field schedule-zone-field">
                <span>调度时区</span>
                <input v-model.trim="form.zoneId" :disabled="saving" placeholder="Asia/Shanghai" />
              </label>
            </div>
            <small>仅限制自动调度，立即执行不受影响；开始时间包含、结束时间不包含，支持 22:00–02:00 跨夜时段。</small>
          </div>

          <div class="schedule-options">
            <label>
              <input v-model="form.enabled" :disabled="saving" type="checkbox" />
              <span>{{ editingScheduleId ? "保存后启用" : "创建后启用" }}</span>
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
          <div v-if="form.notifyEnabled" class="notification-selection-summary">
            <div>
              <strong>通知类型</strong>
              <span v-if="selectedNotificationChannel()">
                {{ selectedNotificationChannel().title || channelTypeLabel(selectedNotificationChannel().channel) }}
              </span>
              <span v-else>保存调度时选择</span>
            </div>
            <button type="button" class="light-button" :disabled="saving" @click="openNotificationSelector">
              {{ selectedNotificationChannel() ? "重新选择" : "选择通知方式" }}
            </button>
          </div>
        </section>

        <p v-if="error" class="schedule-error">{{ error }}</p>
        <p v-if="notice" class="schedule-notice">{{ notice }}</p>

        <footer>
          <button type="button" class="light-button" :disabled="saving" @click="closeCreateDialog">取消</button>
          <button type="submit" class="primary-button" :disabled="saving || loading">
            {{ saving ? (editingScheduleId ? "保存中" : "创建中") : (editingScheduleId ? "保存修改" : "创建定时任务") }}
          </button>
        </footer>
      </form>
    </div>

    <div v-if="notificationHistoryDialogOpen" class="schedule-dialog-backdrop notification-history-backdrop">
      <div class="schedule-dialog notification-history-dialog">
        <header>
          <div>
            <p>Notification History</p>
            <h2>{{ notificationHistorySchedule?.name || "调度任务" }} · 通知历史</h2>
          </div>
          <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" @click="closeNotificationHistory">×</button>
        </header>

        <div class="notification-history-search">
          <label class="field">
            <span>检索记录</span>
            <input
              v-model.trim="notificationHistoryQuery.keyword"
              type="search"
              placeholder="搜索通知类型、接收人、状态、任务ID或错误信息"
              @keyup.enter="searchNotificationHistory"
            />
          </label>
          <button type="button" class="primary-button" :disabled="notificationHistoryLoading" @click="searchNotificationHistory">
            {{ notificationHistoryLoading ? "查询中" : "查询" }}
          </button>
        </div>

        <p v-if="notificationHistoryError" class="schedule-error">{{ notificationHistoryError }}</p>
        <div class="notification-history-table-wrap">
          <div class="notification-history-table">
            <div class="notification-history-head">
              <span>发送时间</span>
              <span>通知类型</span>
              <span>接收人</span>
              <span>结果</span>
              <span>关联任务</span>
              <span>说明</span>
            </div>
            <p v-if="notificationHistoryLoading" class="schedule-empty">正在加载通知历史…</p>
            <p v-else-if="notificationHistoryRecords.length === 0" class="schedule-empty">暂无匹配的通知历史</p>
            <template v-else>
              <div v-for="record in notificationHistoryRecords" :key="record.runId" class="notification-history-row">
                <span>{{ formatDateTime(record.sentAt) }}</span>
                <span>{{ record.channelName || channelTypeLabel(record.channelType) }}</span>
                <span :title="record.receiver">{{ record.receiver || "-" }}</span>
                <b :class="String(record.status || '').toLowerCase()">{{ notificationHistoryStatusLabel(record.status) }}</b>
                <span :title="record.taskId">{{ record.taskId || "-" }}</span>
                <span class="notification-history-error" :title="record.errorMessage">{{ record.errorMessage || "-" }}</span>
              </div>
            </template>
          </div>
        </div>

        <footer class="notification-history-footer">
          <span>共 {{ notificationHistoryQuery.total }} 条，每页 10 条</span>
          <div>
            <button
              type="button"
              class="light-button"
              :disabled="notificationHistoryLoading || notificationHistoryQuery.page <= 1"
              @click="changeNotificationHistoryPage(notificationHistoryQuery.page - 1)"
            >上一页</button>
            <span>第 {{ notificationHistoryQuery.page }} / {{ Math.max(1, notificationHistoryQuery.totalPages) }} 页</span>
            <button
              type="button"
              class="light-button"
              :disabled="notificationHistoryLoading || notificationHistoryQuery.page >= Math.max(1, notificationHistoryQuery.totalPages)"
              @click="changeNotificationHistoryPage(notificationHistoryQuery.page + 1)"
            >下一页</button>
          </div>
        </footer>
      </div>
    </div>

    <div v-if="notificationDialogOpen" class="schedule-dialog-backdrop">
      <div class="schedule-dialog notification-dialog">
        <header>
          <div>
            <p>MCP Notification</p>
            <h2>通知方式与租户接收人</h2>
          </div>
          <button type="button" aria-label="关闭" title="关闭" @click="notificationDialogOpen = false">×</button>
        </header>
        <p class="notification-readonly-tip">通知通道由 MCP 服务端维护且只读；接收人保存在 API 端，并严格按当前租户隔离。</p>
        <p v-if="notificationLoading" class="schedule-empty">正在加载通知方式…</p>
        <p v-else-if="error" class="schedule-error">{{ error }}</p>
        <p v-else-if="notificationChannels.length === 0" class="schedule-empty">MCP 端暂无已启用的通知方式</p>
        <div v-else class="notification-option-list">
          <article v-for="channel in notificationChannels" :key="channel.id" class="notification-option-card">
            <div>
              <strong>{{ channel.title || channelTypeLabel(channel.channel) }}</strong>
              <span>{{ channelTypeLabel(channel.channel) }} · {{ channel.deliveryMode || "-" }}</span>
            </div>
            <p>{{ channel.description || "暂无说明" }}</p>
            <small>工具：{{ channel.toolName || "-" }}</small>
            <small v-if="!channel.recipientAware" class="notification-channel-warning">
              当前 MCP URL/请求模板未使用 &#123;&#123;receiver&#125;&#125;，为防止跨租户串发，该通道暂不能用于调度。
            </small>
            <div class="tenant-recipient-editor">
              <label class="field">
                <span>{{ channelTypeLabel(channel.channel) }}接收人</span>
                <input
                  v-model.trim="notificationRecipientDrafts[channel.channel]"
                  :disabled="recipientSaving === channel.channel"
                  :placeholder="recipientPlaceholder(channel.channel)"
                />
              </label>
              <div>
                <button type="button" class="light-button" :disabled="recipientSaving === channel.channel" @click="saveNotificationRecipient(channel)">
                  {{ recipientSaving === channel.channel ? "保存中" : "保存绑定" }}
                </button>
                <button v-if="channel.bound" type="button" class="danger-button" :disabled="recipientSaving === channel.channel" @click="deleteNotificationRecipient(channel)">
                  解绑
                </button>
              </div>
            </div>
          </article>
        </div>
        <footer>
          <button type="button" class="primary-button" @click="notificationDialogOpen = false">关闭</button>
        </footer>
      </div>
    </div>

    <div v-if="notificationSelectOpen" class="schedule-dialog-backdrop notification-select-backdrop">
      <div class="schedule-dialog notification-dialog">
        <header>
          <div>
            <p>Complete Notification</p>
            <h2>选择完成后的通知类型</h2>
          </div>
          <button type="button" aria-label="关闭" title="关闭" @click="closeNotificationSelector">×</button>
        </header>
        <p class="notification-readonly-tip">只能选择 MCP 端已启用、且当前租户已经绑定接收人的通知方式。</p>
        <p v-if="notificationLoading" class="schedule-empty">正在加载通知方式…</p>
        <p v-else-if="boundNotificationChannels().length === 0" class="schedule-error">当前租户暂无可用通知方式，请先点击“通知方式”绑定接收人。</p>
        <div v-else class="notification-option-list selectable">
          <label v-for="channel in boundNotificationChannels()" :key="channel.id" class="notification-option-card">
            <input v-model="pendingNotificationId" type="radio" name="notificationChannel" :value="channel.id" />
            <div>
              <strong>{{ channel.title || channelTypeLabel(channel.channel) }}</strong>
              <span>{{ channelTypeLabel(channel.channel) }} · {{ channel.deliveryMode || "-" }}</span>
              <p>{{ channel.description || "暂无说明" }}</p>
              <small>接收人：{{ channel.receiver }}</small>
            </div>
          </label>
        </div>
        <p v-if="error" class="schedule-error">{{ error }}</p>
        <footer>
          <button type="button" class="light-button" :disabled="saving" @click="closeNotificationSelector">取消</button>
          <button type="button" class="primary-button" :disabled="saving || notificationLoading || !boundNotificationChannels().length" @click="confirmNotificationAndCreate">
            {{ saving ? "创建中" : "确认并创建" }}
          </button>
        </footer>
      </div>
    </div>
  </main>
</template>

<script src="../js/views/AgentScheduleView.js"></script>
