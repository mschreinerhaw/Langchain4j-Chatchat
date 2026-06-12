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
  cancelAgentTask,
  fetchAgentEffectAnalytics,
  fetchAgentExperiences,
  fetchAgentRuntimeSummary,
  fetchAgentRuntimeToolAudits,
  fetchAgentTaskEvents,
  fetchToolGovernance,
  submitAgentTaskFeedback,
  updateConversationHistoryStatus
} from "../../services/api";
import { notifyAgentTaskCancelled } from "../utils/agentTaskEvents";

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
      effectActiveTab: "agents",
      taskSearchQuery: "",
      statusFilter: "",
      eventSearchQuery: "",
      eventTypeFilter: "",
      toolSearchQuery: "",
      toolHealthFilter: "",
      governanceSearchQuery: "",
      governanceLevelFilter: "",
      auditSearchQuery: "",
      auditOutcomeFilter: "",
      summary: null,
      effectAnalytics: null,
      experienceSummary: null,
      toolGovernance: null,
      recentToolAudits: [],
      selectedTask: null,
      selectedEvents: [],
      cancellingTaskIds: {},
      feedbackSubmitting: false,
      feedbackDraft: {
        useful: false,
        adopted: false,
        resolved: false,
        comment: "",
        reasonCategory: ""
      },
      feedbackReasonOptions: [
        { value: "", label: "选择原因" },
        { value: "answer_correct", label: "答案正确" },
        { value: "steps_clear", label: "步骤清晰" },
        { value: "tool_result_accurate", label: "工具结果准确" },
        { value: "environment_mismatch", label: "环境不匹配" },
        { value: "answer_incomplete", label: "回答不完整" },
        { value: "tool_call_error", label: "工具调用错误" },
        { value: "knowledge_outdated", label: "知识库内容过期" },
        { value: "other", label: "其他" }
      ]
    };
  },
  computed: {
    tabs() {
      return [
        { key: "tasks", label: "任务", icon: Layers, count: this.tasks.length },
        { key: "effects", label: "效果", icon: Activity, count: this.lowScoreTasks.length },
        { key: "experiences", label: "经验", icon: GitBranch, count: this.experienceItems.length },
        { key: "events", label: "事件", icon: Database, count: this.filteredEvents.length },
        { key: "tools", label: "工具", icon: ShieldAlert, count: this.filteredTopTools.length },
        { key: "governance", label: "治理", icon: ShieldCheck, count: this.filteredGovernanceTools.length },
        { key: "audits", label: "审计", icon: ShieldCheck, count: this.filteredAudits.length }
      ];
    },
    tasks() {
      return Array.isArray(this.summary?.latestTasks) ? this.summary.latestTasks : [];
    },
    topTools() {
      return Array.isArray(this.summary?.toolRuntime?.topTools) ? this.summary.toolRuntime.topTools : [];
    },
    lowScoreTasks() {
      return Array.isArray(this.effectAnalytics?.lowScoreTasks) ? this.effectAnalytics.lowScoreTasks : [];
    },
    agentEffectRows() {
      return Array.isArray(this.effectAnalytics?.agents) ? this.effectAnalytics.agents : [];
    },
    toolGovernanceTools() {
      return Array.isArray(this.toolGovernance?.tools) ? this.toolGovernance.tools : [];
    },
    reasonMetrics() {
      return Array.isArray(this.effectAnalytics?.reasonMetrics) ? this.effectAnalytics.reasonMetrics : [];
    },
    experienceItems() {
      return Array.isArray(this.experienceSummary?.experiences) ? this.experienceSummary.experiences : [];
    },
    experienceIndexes() {
      return Array.isArray(this.experienceSummary?.indexes) ? this.experienceSummary.indexes : [];
    },
    experienceScenarios() {
      return Array.isArray(this.experienceSummary?.scenarios) ? this.experienceSummary.scenarios : [];
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
    governanceLevelOptions() {
      return [...new Set(this.toolGovernanceTools.map((tool) => String(tool.runtimeLevel || "").toLowerCase()).filter(Boolean))];
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
    effectMetrics() {
      const analytics = this.effectAnalytics || {};
      return [
        { label: "反馈样本", value: analytics.feedbackTasks || 0, icon: Database },
        { label: "有用率", value: this.formatPercent(analytics.usefulRate), icon: ShieldCheck },
        { label: "采纳率", value: this.formatPercent(analytics.adoptedRate), icon: Activity },
        { label: "解决率", value: this.formatPercent(analytics.resolvedRate), icon: GitBranch },
        { label: "失败率", value: this.formatPercent(analytics.failedRate), icon: XCircle },
        { label: "低评分", value: this.lowScoreTasks.length, icon: ShieldAlert }
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
    filteredGovernanceTools() {
      return this.toolGovernanceTools.filter((tool) => {
        const level = String(tool.runtimeLevel || "").toLowerCase();
        const matchesLevel = !this.governanceLevelFilter || level === this.governanceLevelFilter;
        const matchesQuery = this.matchesQuery(
          [
            tool.toolName,
            tool.displayName,
            tool.sourceType,
            tool.serviceId,
            tool.serviceName,
            tool.runtimeLevel,
            tool.defaultAction,
            tool.riskLevel,
            tool.operationType
          ],
          this.governanceSearchQuery
        );
        return matchesLevel && matchesQuery;
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
    },
    canRecordFeedback() {
      const normalized = String(this.selectedTask?.status || "").toUpperCase();
      return ["SUCCESS", "FAILED", "CANCELLED"].includes(normalized);
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
        const [summary, audits, effects, experiences, governance] = await Promise.all([
          fetchAgentRuntimeSummary({
            tenantId: this.tenantId,
            latestLimit: 20
          }),
          fetchAgentRuntimeToolAudits({
            tenantId: this.tenantId,
            limit: 40
          }),
          fetchAgentEffectAnalytics({
            tenantId: this.tenantId,
            lowScoreLimit: 12
          }),
          fetchAgentExperiences({
            tenantId: this.tenantId,
            limit: 20
          }),
          fetchToolGovernance({
            tenantId: this.tenantId
          })
        ]);
        this.summary = summary;
        this.effectAnalytics = effects;
        this.experienceSummary = experiences;
        this.toolGovernance = governance;
        this.recentToolAudits = Array.isArray(audits) ? audits : [];
        const pendingTaskId = sessionStorage.getItem("chatchat.runtime.selectedTaskId") || "";
        const pendingTask = pendingTaskId ? this.tasks.find((task) => task.taskId === pendingTaskId) : null;
        if (pendingTask) {
          sessionStorage.removeItem("chatchat.runtime.selectedTaskId");
          await this.selectTask(pendingTask);
          this.activeTab = "events";
        } else if (!this.selectedTask && this.tasks.length > 0) {
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
      this.syncFeedbackDraft(task);
      await this.reloadEvents();
    },
    syncFeedbackDraft(task) {
      this.feedbackDraft = {
        useful: !!task?.feedbackUseful,
        adopted: !!task?.feedbackAdopted,
        resolved: !!task?.feedbackResolved,
        comment: task?.feedbackComment || "",
        reasonCategory: task?.feedbackReasonCategory || ""
      };
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
    isActiveTask(task) {
      const normalized = String(task?.status || "").toUpperCase();
      return ["PENDING", "RUNNING", "WAIT_TOOL", "WAIT_MODEL", "WAIT_CONFIRMATION"].includes(normalized);
    },
    isCancellingTask(task) {
      return !!this.cancellingTaskIds[task?.taskId];
    },
    async killTask(task) {
      if (!task?.taskId || !this.isActiveTask(task) || this.isCancellingTask(task)) {
        return;
      }
      this.cancellingTaskIds = {
        ...this.cancellingTaskIds,
        [task.taskId]: true
      };
      try {
        const cancelledTask = await cancelAgentTask(task.taskId, task.tenantId || this.tenantId);
        const cancellation = {
          ...task,
          ...(cancelledTask || {}),
          status: "CANCELLED"
        };
        notifyAgentTaskCancelled(cancellation);
        await this.persistCancelledConversation(cancellation);
        await this.loadRuntime();
        if (this.selectedTask?.taskId === task.taskId) {
          const refreshed = this.tasks.find((item) => item.taskId === task.taskId);
          this.selectedTask = refreshed || this.selectedTask;
          await this.reloadEvents();
        }
      } catch (error) {
        this.error = error.message || "停止任务失败";
      } finally {
        const next = { ...this.cancellingTaskIds };
        delete next[task.taskId];
        this.cancellingTaskIds = next;
      }
    },
    async saveTaskFeedback() {
      if (!this.selectedTask?.taskId || !this.canRecordFeedback || this.feedbackSubmitting) {
        return;
      }
      this.feedbackSubmitting = true;
      this.error = "";
      try {
        const updated = await submitAgentTaskFeedback(this.selectedTask.taskId, this.selectedTask.tenantId || this.tenantId, {
          tenantId: this.selectedTask.tenantId || this.tenantId,
          userId: this.selectedTask.userId || this.userId,
          useful: this.feedbackDraft.useful,
          adopted: this.feedbackDraft.adopted,
          resolved: this.feedbackDraft.resolved,
          comment: this.feedbackDraft.comment,
          reasonCategory: this.feedbackDraft.reasonCategory
        });
        this.selectedTask = {
          ...this.selectedTask,
          ...(updated || {})
        };
        this.syncFeedbackDraft(this.selectedTask);
        await this.reloadEvents();
        await this.loadRuntime();
      } catch (error) {
        this.error = error.message || "记录任务反馈失败";
      } finally {
        this.feedbackSubmitting = false;
      }
    },
    async persistCancelledConversation(task) {
      const historyId = task?.sessionId || task?.conversationId || "";
      if (!historyId) {
        return;
      }
      try {
        await updateConversationHistoryStatus(task.userId || task.tenantId || this.tenantId, historyId, {
          conversationId: historyId,
          status: "cancelled"
        });
      } catch (error) {
        // Runtime cancellation already succeeded; history status will refresh on the next save/load cycle.
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
    formatPercent(value) {
      const number = Number(value || 0);
      return `${number.toFixed(number % 1 === 0 ? 0 : 1)}%`;
    },
    formatRuntimeLevel(value) {
      const normalized = String(value || "").toLowerCase();
      return (
        {
          readonly: "只读",
          suggestion: "建议型",
          confirm_required: "需确认",
          forbidden: "禁用"
        }[normalized] || "只读"
      );
    },
    formatRuntimeAction(value) {
      const normalized = String(value || "").toLowerCase();
      return (
        {
          auto_execute: "自动执行",
          ask_before_execute: "执行前确认",
          deny: "拒绝执行"
        }[normalized] || "自动执行"
      );
    },
    formatFeedbackReason(value) {
      return this.feedbackReasonOptions.find((item) => item.value === value)?.label || "其他";
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
