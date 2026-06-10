import "../../styles/pages/skill-hub.css";
import {
  Activity,
  Database,
  GitBranch,
  Layers,
  ListFilter,
  RefreshCw,
  Search,
  ShieldAlert,
  ShieldCheck,
  ShieldX,
  TimerReset,
  XCircle
} from "@lucide/vue";
import {
  fetchAgentRuntimeSummary,
  fetchAgentRuntimeToolAudits,
  fetchAgentTaskEvents
} from "../../services/api";

export default {
  name: "TasksView",
  components: {
    Activity,
    Database,
    GitBranch,
    Layers,
    ListFilter,
    RefreshCw,
    Search,
    ShieldAlert,
    ShieldCheck,
    ShieldX,
    TimerReset,
    XCircle
  },
  props: {
    userId: {
      type: String,
      default: "default-user"
    }
  },
  data() {
    return {
      loading: false,
      eventsLoading: false,
      error: "",
      tenantId: this.userId || "",
      activeTab: "tasks",
      taskSearchQuery: "",
      statusFilter: "",
      eventSearchQuery: "",
      eventTypeFilter: "",
      toolSearchQuery: "",
      toolHealthFilter: "",
      auditSearchQuery: "",
      auditOutcomeFilter: "",
      summary: null,
      recentToolAudits: [],
      selectedTask: null,
      selectedEvents: []
    };
  },
  computed: {
    tabs() {
      return [
        { key: "tasks", label: "任务", icon: Layers, count: this.tasks.length },
        { key: "events", label: "事件", icon: Database, count: this.filteredEvents.length },
        { key: "tools", label: "工具", icon: ShieldAlert, count: this.filteredTopTools.length },
        { key: "audits", label: "审计", icon: ShieldCheck, count: this.filteredAudits.length }
      ];
    },
    tasks() {
      return Array.isArray(this.summary?.latestTasks) ? this.summary.latestTasks : [];
    },
    topTools() {
      return Array.isArray(this.summary?.toolRuntime?.topTools) ? this.summary.toolRuntime.topTools : [];
    },
    statusOptions() {
      const fromSummary = Array.isArray(this.summary?.statuses)
        ? this.summary.statuses.map((item) => item.status)
        : [];
      const fromTasks = this.tasks.map((task) => task.status).filter(Boolean);
      return [...new Set([...fromSummary, ...fromTasks].map((status) => String(status).toUpperCase()))];
    },
    eventTypeOptions() {
      return [...new Set(this.selectedEvents.map((event) => String(event.type || "").toUpperCase()).filter(Boolean))];
    },
    auditOutcomeOptions() {
      return [
        ...new Set(this.recentToolAudits.map((item) => String(item.outcome || "").toLowerCase()).filter(Boolean))
      ];
    },
    metrics() {
      const summary = this.summary || {};
      const toolRuntime = summary.toolRuntime || {};
      return [
        { label: "任务总量", value: summary.totalTasks || 0, icon: Layers },
        { label: "运行中", value: summary.activeTasks || 0, icon: Activity },
        { label: "队列深度", value: summary.queueDepth || 0, icon: GitBranch },
        { label: "工作线程", value: summary.activeWorkerCount || 0, icon: TimerReset },
        { label: "工具调用", value: toolRuntime.totalCalls || 0, icon: Database },
        { label: "失败任务", value: summary.failedTasks || 0, icon: XCircle }
      ];
    },
    governanceMetrics() {
      const counts = this.filteredAudits.reduce((acc, item) => {
        const outcome = String(item.outcome || "").toLowerCase();
        acc[outcome] = (acc[outcome] || 0) + 1;
        return acc;
      }, {});
      return [
        { label: "通过", value: counts.success || 0, icon: ShieldCheck },
        { label: "拒绝", value: counts.denied || 0, icon: ShieldX },
        {
          label: "保护中",
          value: (counts.rate_limited || 0) + (counts.circuit_open || 0),
          icon: ShieldAlert
        }
      ];
    },
    filteredTasks() {
      return this.tasks.filter((task) => {
        const matchesStatus = !this.statusFilter || String(task.status || "").toUpperCase() === this.statusFilter;
        const matchesQuery = this.matchesQuery(
          [task.taskId, task.question, task.tenantId, task.agentId, task.status],
          this.taskSearchQuery
        );
        return matchesStatus && matchesQuery;
      });
    },
    filteredEvents() {
      return this.selectedEvents.filter((event) => {
        const matchesType = !this.eventTypeFilter || String(event.type || "").toUpperCase() === this.eventTypeFilter;
        const matchesQuery = this.matchesQuery(
          [event.eventId, event.type, event.status, event.toolName, event.payload, event.errorCode],
          this.eventSearchQuery
        );
        return matchesType && matchesQuery;
      });
    },
    filteredTopTools() {
      return this.topTools.filter((tool) => {
        const health = this.toolHealth(tool);
        const matchesHealth = !this.toolHealthFilter || health === this.toolHealthFilter;
        const matchesQuery = this.matchesQuery(
          [
            tool.toolName,
            tool.totalCalls,
            tool.successCalls,
            tool.failedCalls,
            tool.deniedCalls,
            tool.rateLimitedCalls,
            tool.circuitOpenRejects
          ],
          this.toolSearchQuery
        );
        return matchesHealth && matchesQuery;
      });
    },
    filteredAudits() {
      return this.recentToolAudits.filter((item) => {
        const matchesOutcome =
          !this.auditOutcomeFilter || String(item.outcome || "").toLowerCase() === this.auditOutcomeFilter;
        const matchesQuery = this.matchesQuery(
          [item.toolName, item.userId, item.mode, item.serviceId, item.errorCode, item.errorMessage],
          this.auditSearchQuery
        );
        return matchesOutcome && matchesQuery;
      });
    },
    selectedTaskId() {
      return this.selectedTask?.taskId || "";
    },
    selectedTaskDisplay() {
      if (!this.selectedTask) {
        return null;
      }
      return {
        id: this.shortId(this.selectedTask.taskId),
        title: this.selectedTask.question || "未命名任务",
        subtitle: this.selectedTask.agentId || "default-agent",
        description:
          this.selectedTask.answerSummary || this.selectedTask.errorMessage || this.selectedTask.question || ""
      };
    }
  },
  mounted() {
    this.loadRuntime();
  },
  methods: {
    async loadRuntime() {
      this.loading = true;
      this.error = "";
      try {
        const [summary, audits] = await Promise.all([
          fetchAgentRuntimeSummary({
            tenantId: this.tenantId,
            latestLimit: 20
          }),
          fetchAgentRuntimeToolAudits({
            tenantId: this.tenantId,
            limit: 40
          })
        ]);
        this.summary = summary;
        this.recentToolAudits = Array.isArray(audits) ? audits : [];
        if (!this.selectedTask && this.tasks.length > 0) {
          await this.selectTask(this.tasks[0]);
        } else if (this.selectedTask && !this.tasks.some((task) => task.taskId === this.selectedTask.taskId)) {
          this.selectedTask = null;
          this.selectedEvents = [];
        }
      } catch (error) {
        this.error = error.message || "加载运行监控失败";
      } finally {
        this.loading = false;
      }
    },
    activateTab(key) {
      this.activeTab = key;
    },
    async inspectTask(task) {
      await this.selectTask(task);
      this.activeTab = "events";
    },
    async selectTask(task) {
      this.selectedTask = task;
      await this.reloadEvents();
    },
    async reloadEvents() {
      if (!this.selectedTask?.taskId) {
        this.selectedEvents = [];
        return;
      }
      this.eventsLoading = true;
      try {
        const events = await fetchAgentTaskEvents(
          this.selectedTask.taskId,
          120,
          this.selectedTask.tenantId || this.tenantId
        );
        this.selectedEvents = Array.isArray(events) ? events : [];
      } catch (error) {
        this.error = error.message || "读取事件链路失败";
        this.selectedEvents = [];
      } finally {
        this.eventsLoading = false;
      }
    },
    onSelectedTaskChange(taskId) {
      const task = this.tasks.find((item) => item.taskId === taskId);
      if (task) {
        this.selectTask(task);
      }
    },
    shortId(value) {
      if (!value) {
        return "-";
      }
      return String(value).slice(0, 8);
    },
    formatTime(value) {
      if (!value) {
        return "-";
      }
      return new Intl.DateTimeFormat("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit"
      }).format(new Date(value));
    },
    formatEventTime(value) {
      if (!value) {
        return "-";
      }
      return new Intl.DateTimeFormat("zh-CN", {
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit"
      }).format(new Date(value));
    },
    formatAuditTime(value) {
      if (!value) {
        return "-";
      }
      return new Intl.DateTimeFormat("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit"
      }).format(new Date(value));
    },
    formatDuration(value) {
      if (value === null || value === undefined || value === "") {
        return "-";
      }
      return `${value} ms`;
    },
    formatOutcome(value) {
      const normalized = String(value || "").toLowerCase();
      return (
        {
          success: "通过",
          denied: "拒绝",
          failed: "失败",
          rate_limited: "限流",
          circuit_open: "熔断"
        }[normalized] || (normalized ? normalized.replaceAll("_", " ") : "未知")
      );
    },
    formatToolHealth(value) {
      return (
        {
          healthy: "稳定",
          problem: "异常"
        }[value] || "全部"
      );
    },
    toolHealth(tool) {
      if (!tool) {
        return "healthy";
      }
      return tool.failedCalls > 0 || tool.deniedCalls > 0 || tool.rateLimitedCalls > 0 || tool.circuitOpenRejects > 0
        ? "problem"
        : "healthy";
    },
    matchesQuery(values, query) {
      const keyword = String(query || "").trim().toLowerCase();
      if (!keyword) {
        return true;
      }
      return values.some((value) => String(value ?? "").toLowerCase().includes(keyword));
    },
    statusClass(status) {
      const normalized = String(status || "").toLowerCase();
      return {
        success: normalized === "success",
        failed: normalized === "failed",
        running: normalized === "running",
        pending: normalized === "pending",
        waiting: normalized.startsWith("wait"),
        cancelled: normalized === "cancelled",
        denied: normalized === "denied",
        rateLimited: normalized === "rate_limited",
        circuitOpen: normalized === "circuit_open"
      };
    }
  }
};
