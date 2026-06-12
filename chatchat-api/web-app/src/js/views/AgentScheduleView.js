import {
  createAgentSchedule,
  deleteAgentSchedule,
  fetchAgentSchedules,
  fetchAgentWorkshop,
  pauseAgentSchedule,
  rerunAgentSchedule,
  resumeAgentSchedule
} from "../../services/api.js";
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
    notifyEnabled: false
  };
}

export default {
  name: "AgentScheduleView",
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
      dialogOpen: false,
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
        .filter((agent) => agent?.id)
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
        if (status && String(schedule.status || "").toUpperCase() !== status) {
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
    async createSchedule() {
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
      const schedulePayload = this.buildSchedulePayload();
      if (!schedulePayload) {
        return;
      }
      this.saving = true;
      try {
        await createAgentSchedule(schedulePayload);
        this.notice = "定时任务已创建";
        this.closeCreateDialog(true);
        await this.loadSchedules();
      } catch (error) {
        this.error = error.message || "定时任务创建失败";
      } finally {
        this.saving = false;
      }
    },
    buildSchedulePayload() {
      const agent = this.selectedAgent;
      const question = this.form.question.trim();
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
    openCreateDialog() {
      const defaultAgentId = this.filters.agentId || this.form.agentId || this.agentOptions[0]?.id || "";
      this.form = emptyForm(defaultAgentId);
      this.syncFormWithAgent();
      this.error = "";
      this.notice = "";
      this.dialogOpen = true;
    },
    closeCreateDialog(force = false) {
      if (this.saving && !force) {
        return;
      }
      this.dialogOpen = false;
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
        await rerunAgentSchedule(id, this.tenantId);
        this.notice = "已提交立即执行";
        await this.loadSchedules();
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
    scheduleTimeLabel(schedule) {
      if (schedule?.triggerType === "INTERVAL") {
        return `每 ${Math.round((schedule.intervalSeconds || 0) / 60)} 分钟`;
      }
      if (schedule?.triggerType === "ONCE") {
        return "单次执行";
      }
      return schedule?.cronExpr || "-";
    },
    scheduleAgentName(schedule) {
      const agent = this.agentOptions.find((item) => item.id === schedule?.agentId);
      return agent?.name || schedule?.agentId || "-";
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
    }
  }
};
