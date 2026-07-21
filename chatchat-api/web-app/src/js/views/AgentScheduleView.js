import {
  createAgentSchedule,
  deleteAgentSchedule,
  fetchAgentScheduleNotificationChannels,
  fetchAgentScheduleNotificationHistory,
  fetchAgentSchedules,
  fetchAgentWorkshop,
  pauseAgentSchedule,
  rerunAgentSchedule,
  resumeAgentSchedule,
  saveAgentScheduleNotificationRecipient,
  updateAgentSchedule
} from "../../services/api.js";
import ScheduleTimePicker from "../../components/ScheduleTimePicker.vue";
import "../../styles/pages/agent-schedule.css";

function defaultOnceAt() {
  const date = new Date(Date.now() + 60 * 60 * 1000);
  date.setMinutes(Math.ceil(date.getMinutes() / 5) * 5, 0, 0);
  return toDatetimeLocal(date);
}

function toDatetimeLocal(date) {
  const pad = (value) => String(value).padStart(2, "0");
  return [
    date.getFullYear(),
    pad(date.getMonth() + 1),
    pad(date.getDate())
  ].join("-") + `T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function cronFromTime(time, weekday = "") {
  const [hour = "8", minute = "0"] = String(time || "08:00").split(":");
  if (weekday) {
    return `0 ${Number(minute)} ${Number(hour)} ? * ${weekday}`;
  }
  return `0 ${Number(minute)} ${Number(hour)} * * ?`;
}

function uniqueToolNames(agent) {
  const names = [
    ...(agent?.resolvedToolNames || []),
    ...(agent?.boundMcpToolNames || []),
    ...(agent?.toolConfigs || []).map((config) => config?.toolName)
  ];
  return [...new Set(names.map((name) => String(name || "").trim()).filter(Boolean))];
}

function scheduleId(schedule) {
  return schedule?.scheduleId || schedule?.taskId || "";
}

function emptyForm(agentId = "") {
  return {
    agentId,
    name: "",
    question: "",
    mode: "daily",
    onceAt: defaultOnceAt(),
    dailyTime: "08:00",
    weekday: "MON",
    weeklyTime: "08:00",
    intervalMinutes: 60,
    cron: "0 0 8 * * ?",
    enabled: true,
    notifyEnabled: false,
    notificationChannelId: "",
    tradingDayOnly: false,
    scheduleWindowEnabled: false,
    scheduleWindowStart: "09:00",
    scheduleWindowEnd: "12:00",
    zoneId: "Asia/Shanghai"
  };
}

function scheduleForm(schedule = {}) {
  const form = emptyForm(schedule.agentId || "");
  const triggerType = String(schedule.triggerType || "CRON").toUpperCase();
  const cron = String(schedule.cronExpr || "").trim();
  const dailyMatch = cron.match(/^0\s+(\d{1,2})\s+(\d{1,2})\s+\*\s+\*\s+\?$/i);
  const weeklyMatch = cron.match(/^0\s+(\d{1,2})\s+(\d{1,2})\s+\?\s+\*\s+(MON|TUE|WED|THU|FRI|SAT|SUN)$/i);
  const clock = (hour, minute) => `${String(Number(hour)).padStart(2, "0")}:${String(Number(minute)).padStart(2, "0")}`;
  if (triggerType === "ONCE") {
    form.mode = "once";
    form.onceAt = schedule.nextFireTime ? toDatetimeLocal(new Date(schedule.nextFireTime)) : defaultOnceAt();
  } else if (triggerType === "INTERVAL") {
    form.mode = "interval";
    form.intervalMinutes = Math.max(1, Math.round(Number(schedule.intervalSeconds || 60) / 60));
  } else if (weeklyMatch) {
    form.mode = "weekly";
    form.weeklyTime = clock(weeklyMatch[2], weeklyMatch[1]);
    form.weekday = weeklyMatch[3].toUpperCase();
  } else if (dailyMatch) {
    form.mode = "daily";
    form.dailyTime = clock(dailyMatch[2], dailyMatch[1]);
  } else {
    form.mode = "cron";
    form.cron = cron || form.cron;
  }
  return {
    ...form,
    agentId: schedule.agentId || "",
    name: schedule.name || "",
    question: schedule.question || "",
    enabled: schedule.enabled === true || ["ACTIVE", "RUNNING"].includes(String(schedule.status || "").toUpperCase()),
    notifyEnabled: schedule.notifyEnabled === true,
    notificationChannelId: schedule.notificationChannelId || "",
    tradingDayOnly: schedule.tradingDayOnly === true,
    scheduleWindowEnabled: schedule.scheduleWindowEnabled === true,
    scheduleWindowStart: schedule.scheduleWindowStart || "09:00",
    scheduleWindowEnd: schedule.scheduleWindowEnd || "12:00",
    zoneId: schedule.zoneId || "Asia/Shanghai"
  };
}

export default {
  name: "AgentScheduleView",
  components: { ScheduleTimePicker },
  props: {
    userId: {
      type: String,
      default: "default-user"
    }
  },
  data() {
    return {
      loading: false,
      saving: false,
      scheduleLoading: false,
      scheduleRefreshTimer: null,
      scheduleRefreshing: false,
      dialogOpen: false,
      editingScheduleId: "",
      notificationDialogOpen: false,
      notificationSelectOpen: false,
      notificationLoading: false,
      notificationHistoryDialogOpen: false,
      notificationHistoryLoading: false,
      notificationHistoryError: "",
      notificationHistorySchedule: null,
      notificationHistoryRecords: [],
      notificationHistoryQuery: {
        keyword: "",
        page: 1,
        pageSize: 10,
        total: 0,
        totalPages: 0
      },
      notificationChannels: [],
      notificationRecipientDrafts: {},
      notificationRecipientInputs: {},
      recipientSaving: "",
      pendingNotificationId: "",
      error: "",
      notice: "",
      agents: [],
      schedules: [],
      filters: {
        agentId: "",
        status: "",
        keyword: ""
      },
      form: emptyForm(),
      scheduleModes: [
        { label: "一次", value: "once" },
        { label: "每天", value: "daily" },
        { label: "每周", value: "weekly" },
        { label: "间隔", value: "interval" },
        { label: "CRON", value: "cron" }
      ],
      weekdays: [
        { label: "周一", value: "MON" },
        { label: "周二", value: "TUE" },
        { label: "周三", value: "WED" },
        { label: "周四", value: "THU" },
        { label: "周五", value: "FRI" },
        { label: "周六", value: "SAT" },
        { label: "周日", value: "SUN" }
      ]
    };
  },
  computed: {
    tenantId() {
      return this.userId || "default-user";
    },
    agentOptions() {
      return this.agents
        .filter((agent) => agent?.id && String(agent.marketStatus || "").toLowerCase() === "published")
        .sort((left, right) => {
          const leftPublished = left.marketStatus === "published" ? 0 : 1;
          const rightPublished = right.marketStatus === "published" ? 0 : 1;
          return leftPublished - rightPublished || String(left.name || left.id).localeCompare(String(right.name || right.id), "zh-CN");
        });
    },
    selectedAgent() {
      return this.agentOptions.find((agent) => agent.id === this.form.agentId) || null;
    },
    filteredSchedules() {
      const keyword = this.filters.keyword.trim().toLowerCase();
      const status = this.filters.status.trim().toUpperCase();
      return this.schedules.filter((schedule) => {
        if (status && this.scheduleEffectiveStatus(schedule) !== status) {
          return false;
        }
        if (!keyword) {
          return true;
        }
        const fields = [
          schedule.name,
          schedule.question,
          schedule.agentId,
          this.scheduleAgentName(schedule),
          schedule.status,
          schedule.lastTaskStatus,
          schedule.lastError,
          schedule.cronExpr
        ];
        return fields.some((field) => String(field || "").toLowerCase().includes(keyword));
      });
    }
  },
  watch: {
    "form.agentId": {
      handler() {
        this.syncFormWithAgent();
      }
    }
  },
  mounted() {
    this.reload();
    this.startSchedulePolling();
  },
  beforeUnmount() {
    this.stopSchedulePolling();
  },
  methods: {
    async reload() {
      this.loading = true;
      this.error = "";
      this.notice = "";
      try {
        const payload = await fetchAgentWorkshop({
          page: 1,
          pageSize: 100
        });
        this.agents = Array.isArray(payload?.agents) ? payload.agents : [];
        if (!this.form.agentId && this.agentOptions.length) {
          this.form = emptyForm(this.agentOptions[0].id);
          this.syncFormWithAgent();
        }
        await this.loadSchedules();
      } catch (error) {
        this.error = error.message || "Agent加载失败";
      } finally {
        this.loading = false;
      }
    },
    syncFormWithAgent() {
      if (!this.selectedAgent) {
        return;
      }
      if (!this.form.name) {
        this.form.name = `${this.selectedAgent.name || this.selectedAgent.id} 定时任务`;
      }
      if (!this.form.question && Array.isArray(this.selectedAgent.quickQuestions)) {
        this.form.question = this.selectedAgent.quickQuestions[0] || "";
      }
      this.error = "";
      this.notice = "";
    },
    async loadSchedules() {
      this.scheduleLoading = true;
      this.error = "";
      try {
        const payload = await fetchAgentSchedules({
          tenantId: this.tenantId,
          agentId: this.filters.agentId,
          page: 1,
          pageSize: 100
        });
        this.schedules = Array.isArray(payload) ? payload : [];
      } catch (error) {
        this.error = error.message || "定时任务加载失败";
      } finally {
        this.scheduleLoading = false;
      }
    },
    startSchedulePolling() {
      this.stopSchedulePolling();
      this.scheduleRefreshTimer = window.setInterval(() => this.refreshScheduleStatus(), 5000);
    },
    stopSchedulePolling() {
      if (this.scheduleRefreshTimer) {
        window.clearInterval(this.scheduleRefreshTimer);
        this.scheduleRefreshTimer = null;
      }
    },
    async refreshScheduleStatus() {
      if (this.scheduleRefreshing || this.loading || this.scheduleLoading || this.saving || this.dialogOpen) {
        return;
      }
      this.scheduleRefreshing = true;
      try {
        const payload = await fetchAgentSchedules({
          tenantId: this.tenantId,
          agentId: this.filters.agentId,
          page: 1,
          pageSize: 100
        });
        this.schedules = Array.isArray(payload) ? payload : [];
      } catch (_) {
        // 静默轮询失败时保留当前列表和用户正在查看的提示。
      } finally {
        this.scheduleRefreshing = false;
      }
    },
    async createSchedule(notificationConfirmed = false) {
      this.error = "";
      this.notice = "";
      if (!this.selectedAgent) {
        this.error = "请选择Agent";
        return;
      }
      if (!this.form.question.trim()) {
        this.error = "请填写问题";
        return;
      }
      if (this.form.notifyEnabled && !this.form.notificationChannelId && !notificationConfirmed) {
        await this.openNotificationSelector();
        return;
      }
      if (this.form.notifyEnabled && !this.selectedNotificationChannel()?.bound) {
        this.error = "请选择当前租户已绑定接收人的通知类型";
        await this.openNotificationSelector();
        return;
      }
      const schedulePayload = this.buildSchedulePayload();
      if (!schedulePayload) {
        return;
      }
      this.saving = true;
      try {
        if (this.editingScheduleId) {
          await updateAgentSchedule(this.editingScheduleId, schedulePayload);
          this.notice = "定时任务已保存";
        } else {
          await createAgentSchedule(schedulePayload);
          this.notice = "定时任务已创建";
        }
        this.closeCreateDialog(true);
        await this.loadSchedules();
      } catch (error) {
        this.error = error.message || (this.editingScheduleId ? "定时任务保存失败" : "定时任务创建失败");
      } finally {
        this.saving = false;
      }
    },
    buildSchedulePayload() {
      const agent = this.selectedAgent;
      const question = this.form.question.trim();
      const scheduleWindowEnabled = this.form.mode !== "once" && this.form.scheduleWindowEnabled;
      if (scheduleWindowEnabled) {
        if (!this.form.scheduleWindowStart || !this.form.scheduleWindowEnd) {
          this.error = "请选择允许执行时段的开始和结束时间";
          return null;
        }
        if (this.form.scheduleWindowStart === this.form.scheduleWindowEnd) {
          this.error = "允许执行时段的开始时间和结束时间不能相同";
          return null;
        }
        if (!this.form.zoneId.trim()) {
          this.error = "请填写调度时区";
          return null;
        }
      }
      const payload = {
        tenantId: this.tenantId,
        userId: this.userId || "default-user",
        agentId: agent.id,
        skillId: agent.id,
        query: question,
        mode: agent.defaultMode || "agent_chat",
        systemPrompt: agent.systemPrompt || "",
        modelName: agent.modelName || "",
        availableTools: uniqueToolNames(agent)
      };
      const base = {
        tenantId: this.tenantId,
        userId: this.userId || "default-user",
        agentId: agent.id,
        name: this.form.name.trim() || `${agent.name || agent.id} 定时任务`,
        enabled: this.form.enabled,
        question,
        notifyEnabled: this.form.notifyEnabled,
        notificationChannelId: this.form.notifyEnabled ? this.form.notificationChannelId : null,
        tradingDayOnly: this.form.tradingDayOnly,
        scheduleWindowEnabled,
        scheduleWindowStart: scheduleWindowEnabled ? this.form.scheduleWindowStart : null,
        scheduleWindowEnd: scheduleWindowEnabled ? this.form.scheduleWindowEnd : null,
        zoneId: this.form.zoneId.trim() || "Asia/Shanghai",
        payload
      };
      if (this.form.mode === "once") {
        if (!this.form.onceAt) {
          this.error = "请选择执行时间";
          return null;
        }
        const nextFireTime = new Date(this.form.onceAt);
        if (Number.isNaN(nextFireTime.getTime()) || nextFireTime.getTime() <= Date.now()) {
          this.error = "执行时间需要晚于当前时间";
          return null;
        }
        return {
          ...base,
          triggerType: "ONCE",
          nextFireTime: nextFireTime.toISOString()
        };
      }
      if (this.form.mode === "interval") {
        const minutes = Number(this.form.intervalMinutes);
        if (!Number.isFinite(minutes) || minutes < 1) {
          this.error = "间隔分钟需要大于0";
          return null;
        }
        return {
          ...base,
          triggerType: "INTERVAL",
          intervalSeconds: Math.round(minutes * 60)
        };
      }
      return {
        ...base,
        triggerType: "CRON",
        cron: this.resolveCron()
      };
    },
    resolveCron() {
      if (this.form.mode === "weekly") {
        return cronFromTime(this.form.weeklyTime, this.form.weekday);
      }
      if (this.form.mode === "cron") {
        return this.form.cron.trim();
      }
      return cronFromTime(this.form.dailyTime);
    },
    setScheduleMode(mode) {
      this.form.mode = mode;
      if (mode === "once") {
        this.form.scheduleWindowEnabled = false;
      }
    },
    openCreateDialog() {
      if (!this.agentOptions.length) {
        this.error = "暂无已发布且已授权的Agent，不能创建调度";
        return;
      }
      const defaultAgentId = this.filters.agentId || this.form.agentId || this.agentOptions[0]?.id || "";
      this.editingScheduleId = "";
      this.form = emptyForm(defaultAgentId);
      this.syncFormWithAgent();
      this.error = "";
      this.notice = "";
      this.dialogOpen = true;
    },
    async openEditDialog(schedule) {
      const id = scheduleId(schedule);
      if (!id || this.isScheduleRunning(schedule)) {
        return;
      }
      this.editingScheduleId = id;
      this.form = scheduleForm(schedule);
      this.error = "";
      this.notice = "";
      this.dialogOpen = true;
      if (this.form.notifyEnabled) {
        await this.loadNotificationChannels();
      }
    },
    async loadNotificationChannels() {
      this.notificationLoading = true;
      try {
        const payload = await fetchAgentScheduleNotificationChannels();
        this.notificationChannels = Array.isArray(payload) ? payload : [];
        this.notificationRecipientDrafts = this.notificationChannels.reduce((drafts, channel) => {
          drafts[channel.channel] = this.parseNotificationRecipients(channel.receiver);
          return drafts;
        }, {});
        this.notificationRecipientInputs = this.notificationChannels.reduce((inputs, channel) => {
          inputs[channel.channel] = "";
          return inputs;
        }, {});
      } catch (error) {
        this.notificationChannels = [];
        this.error = error.message || "MCP通知类型加载失败";
      } finally {
        this.notificationLoading = false;
      }
    },
    async openNotificationOverview() {
      this.error = "";
      this.notificationDialogOpen = true;
      await this.loadNotificationChannels();
    },
    async openNotificationHistory(schedule) {
      this.notificationHistorySchedule = schedule;
      this.notificationHistoryDialogOpen = true;
      this.notificationHistoryError = "";
      this.notificationHistoryRecords = [];
      this.notificationHistoryQuery = {
        keyword: "",
        page: 1,
        pageSize: 10,
        total: 0,
        totalPages: 0
      };
      await this.loadNotificationHistory();
    },
    closeNotificationHistory() {
      if (!this.notificationHistoryLoading) {
        this.notificationHistoryDialogOpen = false;
        this.notificationHistorySchedule = null;
      }
    },
    async searchNotificationHistory() {
      this.notificationHistoryQuery.page = 1;
      await this.loadNotificationHistory();
    },
    async changeNotificationHistoryPage(page) {
      const totalPages = Math.max(1, this.notificationHistoryQuery.totalPages || 1);
      const target = Math.min(totalPages, Math.max(1, page));
      if (target === this.notificationHistoryQuery.page) {
        return;
      }
      this.notificationHistoryQuery.page = target;
      await this.loadNotificationHistory();
    },
    async loadNotificationHistory() {
      const id = scheduleId(this.notificationHistorySchedule);
      if (!id) {
        return;
      }
      this.notificationHistoryLoading = true;
      this.notificationHistoryError = "";
      try {
        const payload = await fetchAgentScheduleNotificationHistory(id, {
          tenantId: this.tenantId,
          keyword: this.notificationHistoryQuery.keyword.trim(),
          page: this.notificationHistoryQuery.page,
          pageSize: 10
        });
        this.notificationHistoryRecords = Array.isArray(payload?.records) ? payload.records : [];
        this.notificationHistoryQuery.total = Number(payload?.total || 0);
        this.notificationHistoryQuery.page = Number(payload?.page || 1);
        this.notificationHistoryQuery.pageSize = Number(payload?.pageSize || 10);
        this.notificationHistoryQuery.totalPages = Number(payload?.totalPages || 0);
      } catch (error) {
        this.notificationHistoryRecords = [];
        this.notificationHistoryError = error.message || "通知历史加载失败";
      } finally {
        this.notificationHistoryLoading = false;
      }
    },
    notificationHistoryStatusLabel(status) {
      const labels = {
        SUCCESS: "发送成功",
        FAILED: "发送失败",
        SKIPPED: "已跳过"
      };
      return labels[String(status || "").toUpperCase()] || status || "-";
    },
    async openNotificationSelector() {
      this.error = "";
      this.pendingNotificationId = this.form.notificationChannelId || "";
      this.notificationSelectOpen = true;
      await this.loadNotificationChannels();
    },
    closeNotificationSelector() {
      if (!this.saving) {
        this.notificationSelectOpen = false;
      }
    },
    async confirmNotificationAndCreate() {
      if (!this.pendingNotificationId) {
        this.error = "请选择通知类型";
        return;
      }
      this.form.notificationChannelId = this.pendingNotificationId;
      this.notificationSelectOpen = false;
      await this.createSchedule(true);
    },
    async saveNotificationRecipientsAndClose() {
      const channels = this.notificationChannels.filter((channel) => channel.recipientAware);
      channels.forEach((channel) => this.addNotificationRecipients(channel.channel));
      const missingChannel = channels.find(
        (channel) => !(this.notificationRecipientDrafts[channel.channel] || []).length
      );
      if (missingChannel) {
        this.error = `请填写${this.channelTypeLabel(missingChannel.channel)}接收人`;
        return;
      }
      if (!channels.length) {
        this.error = "暂无可保存接收人的通知方式";
        return;
      }
      this.recipientSaving = "all";
      this.error = "";
      try {
        for (const channel of channels) {
          const receiver = this.notificationRecipientDrafts[channel.channel].join(",");
          await saveAgentScheduleNotificationRecipient(channel.channel, receiver);
        }
        this.notice = "通知接收人已保存";
        this.notificationDialogOpen = false;
      } catch (error) {
        this.error = error.message || "接收人保存失败";
      } finally {
        this.recipientSaving = "";
      }
    },
    boundNotificationChannels() {
      return this.notificationChannels.filter((channel) => channel.bound && channel.recipientAware);
    },
    parseNotificationRecipients(value) {
      const recipients = Array.isArray(value) ? value : String(value || "").split(/[,，;；\n]+/);
      const seen = new Set();
      return recipients.reduce((result, item) => {
        const recipient = String(item || "").trim();
        const key = recipient.toLocaleLowerCase();
        if (recipient && !seen.has(key)) {
          seen.add(key);
          result.push(recipient);
        }
        return result;
      }, []);
    },
    addNotificationRecipients(channel) {
      const existing = this.notificationRecipientDrafts[channel] || [];
      const input = this.notificationRecipientInputs[channel] || "";
      this.notificationRecipientDrafts[channel] = this.parseNotificationRecipients([...existing, ...this.parseNotificationRecipients(input)]);
      this.notificationRecipientInputs[channel] = "";
    },
    removeNotificationRecipient(channel, receiver) {
      this.notificationRecipientDrafts[channel] = (this.notificationRecipientDrafts[channel] || [])
        .filter((item) => item !== receiver);
    },
    handleRecipientKeydown(event, channel) {
      if (event.key === "Enter" || event.key === "," || event.key === "，") {
        event.preventDefault();
        this.addNotificationRecipients(channel);
      }
    },
    recipientPlaceholder(channel) {
      const placeholders = {
        EMAIL: "输入邮箱后按回车或逗号添加",
        SMS: "输入手机号后按回车或逗号添加",
        DINGTALK: "输入钉钉账号或手机号后添加",
        WECHAT_WORK: "输入企业微信账号后添加"
      };
      return placeholders[channel] || "请输入接收人";
    },
    selectedNotificationChannel() {
      return this.notificationChannels.find((channel) => channel.id === this.form.notificationChannelId) || null;
    },
    closeCreateDialog(force = false) {
      if (this.saving && !force) {
        return;
      }
      this.dialogOpen = false;
      this.editingScheduleId = "";
      this.error = "";
    },
    async toggleSchedule(schedule) {
      const id = scheduleId(schedule);
      if (!id) {
        return;
      }
      this.saving = true;
      this.error = "";
      try {
        if (this.isScheduleActive(schedule)) {
          await pauseAgentSchedule(id, this.tenantId);
        } else {
          await resumeAgentSchedule(id, this.tenantId);
        }
        await this.loadSchedules();
      } catch (error) {
        this.error = error.message || "任务状态更新失败";
      } finally {
        this.saving = false;
      }
    },
    async rerunSchedule(schedule) {
      const id = scheduleId(schedule);
      if (!id) {
        return;
      }
      this.saving = true;
      this.error = "";
      try {
        const result = await rerunAgentSchedule(id, this.tenantId);
        schedule.running = result?.status === "RUNNING" || result?.status === "SCHEDULED";
        if (schedule.running) {
          schedule.lastTaskStatus = "RUNNING";
        }
        await this.loadSchedules();
        if (result?.status === "TRADING_DAY_CHECK_FAILED") {
          this.error = result.errorMessage || "交易日判断失败";
          this.notice = "";
        } else if (result?.status === "SKIPPED_NON_TRADING_DAY") {
          this.notice = "当前不是交易日，本次调度已跳过";
        } else {
          this.notice = "已提交立即执行";
        }
      } catch (error) {
        this.error = error.message || "立即执行失败";
      } finally {
        this.saving = false;
      }
    },
    async removeSchedule(schedule) {
      const id = scheduleId(schedule);
      if (!id || !window.confirm(`确认删除定时任务“${schedule.name || id}”？`)) {
        return;
      }
      this.saving = true;
      this.error = "";
      try {
        await deleteAgentSchedule(id, this.tenantId);
        await this.loadSchedules();
      } catch (error) {
        this.error = error.message || "删除定时任务失败";
      } finally {
        this.saving = false;
      }
    },
    isScheduleActive(schedule) {
      return schedule?.enabled || schedule?.status === "ACTIVE" || schedule?.status === "RUNNING";
    },
    isScheduleRunning(schedule) {
      return schedule?.running === true || String(schedule?.status || "").toUpperCase() === "RUNNING";
    },
    scheduleTimeLabel(schedule) {
      if (schedule?.triggerType === "INTERVAL") {
        return `每 ${Math.round((schedule.intervalSeconds || 0) / 60)} 分钟`;
      }
      if (schedule?.triggerType === "ONCE") {
        return "单次执行";
      }
      return schedule?.cronExpr || "-";
    },
    scheduleWindowLabel(schedule) {
      if (!schedule?.scheduleWindowEnabled) {
        return "";
      }
      const start = schedule.scheduleWindowStart || "--:--";
      const end = schedule.scheduleWindowEnd || "--:--";
      const zoneId = schedule.zoneId || "Asia/Shanghai";
      return `允许 ${start}–${end} · ${zoneId}`;
    },
    scheduleAgentName(schedule) {
      const agent = this.agentOptions.find((item) => item.id === schedule?.agentId);
      return agent?.name || schedule?.agentId || "-";
    },
    notificationTypeLabel(schedule) {
      if (!schedule?.notifyEnabled || !schedule?.notificationChannelId) {
        return "无";
      }
      return schedule.notificationChannelName || this.channelTypeLabel(schedule.notificationChannelType);
    },
    channelTypeLabel(channel) {
      const labels = {
        EMAIL: "邮件",
        SMS: "短信",
        WECHAT_WORK: "企业微信",
        DINGTALK: "钉钉"
      };
      return labels[String(channel || "").toUpperCase()] || channel || "无";
    },
    formatDateTime(value) {
      if (!value) {
        return "-";
      }
      const date = new Date(value);
      if (Number.isNaN(date.getTime())) {
        return "-";
      }
      return date.toLocaleString("zh-CN", { hour12: false });
    },
    scheduleStatusClass(status) {
      return String(status || "").toLowerCase().replace(/_/g, "-");
    },
    scheduleEffectiveStatus(schedule) {
      if (this.isScheduleRunning(schedule)) {
        return "RUNNING";
      }
      if (schedule?.lastTaskStatus === "TRADING_DAY_CHECK_FAILED") {
        return "SCHEDULE_ERROR";
      }
      if (schedule?.lastTaskStatus === "SKIPPED_NON_TRADING_DAY") {
        return "SKIPPED_NON_TRADING_DAY";
      }
      if (schedule?.lastTaskStatus === "AGENT_AUTHORIZATION_DENIED") {
        return "AGENT_AUTHORIZATION_DENIED";
      }
      if (schedule?.lastTaskStatus === "AGENT_UNPUBLISHED") {
        return "AGENT_UNPUBLISHED";
      }
      return String(schedule?.status || "").toUpperCase();
    },
    scheduleStatusLabel(schedule) {
      const status = this.scheduleEffectiveStatus(schedule);
      if (status === "SCHEDULE_ERROR") {
        return "调度异常";
      }
      if (status === "SKIPPED_NON_TRADING_DAY") {
        return "非交易日已跳过";
      }
      if (status === "AGENT_AUTHORIZATION_DENIED") {
        return "Agent未授权";
      }
      if (status === "AGENT_UNPUBLISHED") {
        return "Agent未发布";
      }
      return status || "-";
    }
  }
};
