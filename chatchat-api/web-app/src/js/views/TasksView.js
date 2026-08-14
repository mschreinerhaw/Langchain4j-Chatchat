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
import { defineAsyncComponent } from "vue";
import {
  cancelAgentTask,
  fetchAgentEffectAnalytics,
  fetchAgentExperiences,
  fetchAgentRuntimeSummary,
  fetchAgentRuntimeToolAudits,
  fetchAgentTaskEvents,
  fetchAgentTaskPlanDag,
  fetchAgentTaskPlanVersions,
  fetchToolGovernance,
  submitAgentTaskFeedback,
  updateConversationHistoryStatus
} from "../../services/api";
import { notifyAgentTaskCancelled } from "../utils/agentTaskEvents";
const PlanDagGraph = defineAsyncComponent(() => import("../../components/PlanDagGraph.vue"));

const DEFAULT_RUNTIME_PAGE_SIZE = 10;
const RUNTIME_REFRESH_INTERVAL_MS = 5000;
const PLAN_NODE_WIDTH = 310;
const PLAN_NODE_HEIGHT = 118;
const PLAN_NODE_HORIZONTAL_PADDING = 40;

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
    XCircle,
    PlanDagGraph
  },
  props: {
    userId: {
      type: String,
      default: "default-user"
    },
    tenantId: {
      type: String,
      default: ""
    },
    tenantName: {
      type: String,
      default: ""
    }
  },
  data() {
    return {
      loading: false,
      eventsLoading: false,
      planLoading: false,
      error: "",
      runtimeTenantId: this.tenantId || this.userId || "",
      activeTab: "tasks",
      effectActiveTab: "agents",
      experienceActiveTab: "scenarios",
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
      runtimeRefreshTimer: null,
      runtimeRefreshing: false,
      pageSize: DEFAULT_RUNTIME_PAGE_SIZE,
      runtimePages: {
        tasks: 1,
        agentEffects: 1,
        lowScores: 1,
        reasonMetrics: 1,
        experienceScenarios: 1,
        experienceIndexes: 1,
        experiences: 1,
        events: 1,
        tools: 1,
        governance: 1,
        audits: 1
      },
      summary: null,
      effectAnalytics: null,
      experienceSummary: null,
      toolGovernance: null,
      legacyRuntimeLoaded: false,
      recentToolAudits: [],
      selectedTask: null,
      selectedEvents: [],
      selectedEventId: "",
      selectedPlanDag: null,
      planLoadedTaskId: "",
      selectedPlanVersions: [],
      selectedPlanNodeId: "",
      planTaskDetailsOpen: false,
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
        { value: "answer_incomplete", label: "答案不完整" },
        { value: "tool_call_error", label: "工具调用错误" },
        { value: "knowledge_outdated", label: "知识过期" },
        { value: "other", label: "其他" }
      ]
    };
  },
  computed: {
    runtimeTenantName() {
      return this.tenantName || "默认租户";
    },
    runtimeTenantFieldWidth() {
      const name = String(this.runtimeTenantName || "");
      const textWidth = Array.from(name).reduce(
        (width, character) => width + (/[^\u0000-\u00ff]/.test(character) ? 14 : 7.5),
        0
      );
      return `${Math.max(96, Math.min(280, Math.ceil(textWidth + 24)))}px`;
    },
    tabs() {
      return [
        { key: "tasks", label: "任务", icon: ListFilter, count: this.tasks.length },
        { key: "effects", label: "效果", icon: Activity, count: this.lowScoreTasks.length },
        { key: "experiences", label: "经验", icon: GitBranch, count: this.experienceItems.length },
        { key: "events", label: "事件", icon: Database, count: this.filteredEvents.length },
        { key: "plan", label: "计划图", icon: GitBranch, count: this.planNodes.length },
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
        { label: "任务总数", value: summary.totalTasks || 0, icon: Layers },
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
          label: "保护拦截",
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
        { label: "低分任务", value: this.lowScoreTasks.length, icon: ShieldAlert }
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
        subtitle: this.selectedTask.agentId || "默认智能体",
        description:
          this.selectedTask.answerSummary || this.selectedTask.errorMessage || this.selectedTask.question || ""
      };
    },
    selectedEventDetail() {
      if (!this.selectedEventId) {
        return null;
      }
      return this.selectedEvents.find((event) => event.eventId === this.selectedEventId) || null;
    },
    planTaskPreview() {
      const content =
        this.selectedTask?.question
        || this.selectedTask?.answerSummary
        || this.selectedTask?.errorMessage
        || "未命名任务";
      return this.compactPlanText(content, 120);
    },
    planNodes() {
      return Array.isArray(this.selectedPlanDag?.nodes) ? this.selectedPlanDag.nodes : [];
    },
    planEdges() {
      return Array.isArray(this.selectedPlanDag?.edges) ? this.selectedPlanDag.edges : [];
    },
    planNodeViews() {
      const nodes = this.planNodes;
      if (nodes.length === 0) {
        return [];
      }
      const incoming = this.planEdges.reduce((acc, edge) => {
        const target = edge.target || edge.to || `step-${edge.toStepId}`;
        const source = edge.source || edge.from || `step-${edge.fromStepId}`;
        if (target && source) {
          acc[target] = [...(acc[target] || []), source];
        }
        return acc;
      }, {});
      const byId = nodes.reduce((acc, node) => {
        acc[node.id || `step-${node.stepId}`] = node;
        return acc;
      }, {});
      const levelCache = {};
      const resolveLevel = (nodeId, seen = new Set()) => {
        if (levelCache[nodeId] !== undefined) {
          return levelCache[nodeId];
        }
        if (seen.has(nodeId)) {
          return 0;
        }
        seen.add(nodeId);
        const parents = (incoming[nodeId] || []).filter((parentId) => byId[parentId]);
        const level = parents.length === 0 ? 0 : Math.max(...parents.map((parentId) => resolveLevel(parentId, seen))) + 1;
        levelCache[nodeId] = level;
        return level;
      };
      const lanes = {};
      return nodes.map((node, index) => {
        const id = node.id || `step-${node.stepId || index + 1}`;
        const level = resolveLevel(id);
        const laneIndex = lanes[level] || 0;
        lanes[level] = laneIndex + 1;
        const actionText = this.formatPlanAction(node.actionType);
        const labelValue = node.label || node.toolName || (node.actionType ? actionText : id);
        const labelLines = this.compactPlanTextLinesForWidth(
          labelValue,
          PLAN_NODE_WIDTH - PLAN_NODE_HORIZONTAL_PADDING,
          2,
          16,
          900
        );
        const hasWrappedLabel = labelLines.length > 1;
        const displayStatus = this.resolvePlanNodeStatus(node);
        return {
          ...node,
          id,
          x: 36 + level * 380,
          y: 36 + laneIndex * 168,
          width: PLAN_NODE_WIDTH,
          height: PLAN_NODE_HEIGHT,
          labelLines,
          labelText: labelLines.join(" "),
          fullLabelText: labelValue,
          actionText,
          toolText: this.compactPlanTextForWidth(
            node.toolName || actionText,
            PLAN_NODE_WIDTH - PLAN_NODE_HORIZONTAL_PADDING,
            14,
            800
          ),
          toolY: hasWrappedLabel ? 60 : 48,
          metaText: this.compactPlanTextForWidth(
            `#${node.stepId || id} · ${actionText}`,
            PLAN_NODE_WIDTH - PLAN_NODE_HORIZONTAL_PADDING,
            13,
            800
          ),
          metaY: hasWrappedLabel ? 84 : 80,
          statusText: displayStatus,
          statusLabel: this.formatTaskStatus(displayStatus),
          detailText: node.errorMessage || node.outputPreview || ""
        };
      });
    },
    latestPlanVersionLabel() {
      if (!this.selectedPlanDag?.version) {
        return "无快照";
      }
      return `v${this.selectedPlanDag.version}`;
    },
    canRecordFeedback() {
      const normalized = String(this.selectedTask?.status || "").toUpperCase();
      return ["SUCCESS", "FAILED", "CANCELLED"].includes(normalized);
    }
  },
  mounted() {
    this.loadRuntime();
    this.startRuntimePolling();
  },
  beforeUnmount() {
    this.stopRuntimePolling();
  },
  watch: {
    tenantId(value) {
      const nextTenantId = value || this.userId || "";
      if (nextTenantId && nextTenantId !== this.runtimeTenantId) {
        this.runtimeTenantId = nextTenantId;
        this.loadRuntime({ silent: true });
      }
    },
    userId(value) {
      if (!this.runtimeTenantId && value) {
        this.runtimeTenantId = value;
      }
    },
    taskSearchQuery() {
      this.resetRuntimePage("tasks");
    },
    statusFilter() {
      this.resetRuntimePage("tasks");
    },
    eventSearchQuery() {
      this.resetRuntimePage("events");
    },
    eventTypeFilter() {
      this.resetRuntimePage("events");
    },
    toolSearchQuery() {
      this.resetRuntimePage("tools");
    },
    toolHealthFilter() {
      this.resetRuntimePage("tools");
    },
    governanceSearchQuery() {
      this.resetRuntimePage("governance");
    },
    governanceLevelFilter() {
      this.resetRuntimePage("governance");
    },
    auditSearchQuery() {
      this.resetRuntimePage("audits");
    },
    auditOutcomeFilter() {
      this.resetRuntimePage("audits");
    },
    effectActiveTab(value) {
      this.resetRuntimePage(value === "agents" ? "agentEffects" : "lowScores");
    },
    reasonMetrics() {
      this.resetRuntimePage("reasonMetrics");
    },
    experienceScenarios() {
      this.resetRuntimePage("experienceScenarios");
    },
    experienceIndexes() {
      this.resetRuntimePage("experienceIndexes");
    },
    experienceItems() {
      this.resetRuntimePage("experiences");
    }
  },
  methods: {
    tenantLabel(tenantId) {
      if (!tenantId || String(tenantId) === String(this.runtimeTenantId)) {
        return this.runtimeTenantName;
      }
      return tenantId;
    },
    async loadRuntime(options = {}) {
      await this.loadLegacyRuntime(options);
    },
    startRuntimePolling() {
      this.stopRuntimePolling();
      this.runtimeRefreshTimer = window.setInterval(() => {
        this.refreshRuntimeSnapshot();
      }, RUNTIME_REFRESH_INTERVAL_MS);
    },
    stopRuntimePolling() {
      if (this.runtimeRefreshTimer) {
        window.clearInterval(this.runtimeRefreshTimer);
        this.runtimeRefreshTimer = null;
      }
    },
    async refreshRuntimeSnapshot() {
      if (this.runtimeRefreshing || this.loading) {
        return;
      }
      this.runtimeRefreshing = true;
      try {
        await this.loadRuntime({ silent: true });
        if (this.selectedTask?.taskId && (this.activeTab === "events" || this.isActiveTask(this.selectedTask))) {
          await this.reloadEvents({ silent: true });
        }
        if (this.selectedTask?.taskId && this.activeTab === "plan" && this.isActiveTask(this.selectedTask)) {
          await this.loadPlanDag({ silent: true });
        }
      } finally {
        this.runtimeRefreshing = false;
      }
    },
    async loadLegacyRuntime(options = {}) {
      const silent = !!options.silent;
      if (!silent) {
        this.loading = true;
        this.error = "";
      }
      try {
        const selectedTaskId = this.selectedTask?.taskId || "";
        const [summary, audits, effects, experiences, governance] = await Promise.all([
          fetchAgentRuntimeSummary({
            tenantId: this.runtimeTenantId,
            latestLimit: 100
          }),
          fetchAgentRuntimeToolAudits({
            tenantId: this.runtimeTenantId,
            limit: 100
          }),
          fetchAgentEffectAnalytics({
            tenantId: this.runtimeTenantId,
            lowScoreLimit: 100
          }),
          fetchAgentExperiences({
            tenantId: this.runtimeTenantId,
            limit: 100
          }),
          fetchToolGovernance({
            tenantId: this.runtimeTenantId
          })
        ]);
        this.summary = summary;
        this.effectAnalytics = effects;
        this.experienceSummary = experiences;
        this.toolGovernance = governance;
        this.recentToolAudits = Array.isArray(audits) ? audits : [];
        this.legacyRuntimeLoaded = true;
        this.clampRuntimePages();
        const pendingTaskId = sessionStorage.getItem("chatchat.runtime.selectedTaskId") || "";
        const pendingTask = pendingTaskId ? this.tasks.find((task) => task.taskId === pendingTaskId) : null;
        if (pendingTask) {
          sessionStorage.removeItem("chatchat.runtime.selectedTaskId");
          await this.selectTask(pendingTask);
          this.activeTab = "events";
        } else if (selectedTaskId) {
          const refreshedTask = this.tasks.find((task) => task.taskId === selectedTaskId);
          if (refreshedTask) {
            this.selectedTask = refreshedTask;
            this.syncFeedbackDraft(refreshedTask);
            if (this.planLoadedTaskId !== refreshedTask.taskId) {
              await this.loadPlanDag({ silent: true });
            }
          } else {
            this.selectedTask = null;
            this.selectedEvents = [];
            this.selectedPlanDag = null;
            this.selectedPlanVersions = [];
            this.planLoadedTaskId = "";
          }
        } else if (!this.selectedTask && this.tasks.length > 0) {
          await this.selectTask(this.tasks[0]);
        } else if (this.selectedTask && !this.tasks.some((task) => task.taskId === this.selectedTask.taskId)) {
          this.selectedTask = null;
          this.selectedEvents = [];
          this.selectedPlanDag = null;
          this.selectedPlanVersions = [];
          this.planLoadedTaskId = "";
        }
      } catch (error) {
        if (!silent) {
          this.error = error.message || "加载运行监控失败。";
        }
      } finally {
        if (!silent) {
          this.loading = false;
        }
      }
    },
    activateTab(key) {
      this.activeTab = key;
      this.error = "";
      if (!this.legacyRuntimeLoaded) {
        this.loadLegacyRuntime();
      }
      if (key === "plan") {
        this.loadPlanDag();
      }
    },
    async inspectTask(task) {
      await this.selectTask(task);
      this.activeTab = "events";
    },
    async selectTask(task) {
      this.selectedTask = task;
      this.selectedEventId = "";
      this.syncFeedbackDraft(task);
      this.resetRuntimePage("events");
      this.selectedPlanDag = null;
      this.selectedPlanVersions = [];
      this.planLoadedTaskId = "";
      this.selectedPlanNodeId = "";
      this.planTaskDetailsOpen = false;
      await Promise.all([
        this.reloadEvents(),
        this.loadPlanDag({ silent: this.activeTab !== "plan" })
      ]);
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
    async reloadEvents(options = {}) {
      if (!this.selectedTask?.taskId) {
        this.selectedEvents = [];
        return;
      }
      const silent = !!options.silent;
      if (!silent) {
        this.eventsLoading = true;
      }
      try {
        const events = await fetchAgentTaskEvents(
          this.selectedTask.taskId,
          120,
          this.selectedTask.tenantId || this.runtimeTenantId
        );
        this.selectedEvents = Array.isArray(events) ? events : [];
        if (this.selectedEventId && !this.selectedEvents.some((event) => event.eventId === this.selectedEventId)) {
          this.selectedEventId = "";
        }
        this.runtimePages = {
          ...this.runtimePages,
          events: this.clampedRuntimePage("events", this.filteredEvents.length)
        };
      } catch (error) {
        if (!silent) {
          this.error = error.message || "加载事件链路失败。";
          this.selectedEvents = [];
        }
      } finally {
        if (!silent) {
          this.eventsLoading = false;
        }
      }
    },
    async loadPlanDag(options = {}) {
      if (!this.selectedTask?.taskId) {
        this.selectedPlanDag = null;
        this.selectedPlanVersions = [];
        this.planLoadedTaskId = "";
        return;
      }
      const requestedTaskId = this.selectedTask.taskId;
      const requestedTenantId = this.selectedTask.tenantId || this.runtimeTenantId;
      const silent = !!options.silent;
      if (!silent) {
        this.planLoading = true;
        this.error = "";
      }
      try {
        const [dag, versions] = await Promise.all([
          fetchAgentTaskPlanDag(requestedTaskId, requestedTenantId),
          fetchAgentTaskPlanVersions(requestedTaskId, requestedTenantId)
        ]);
        if (this.selectedTask?.taskId !== requestedTaskId) {
          return;
        }
        this.selectedPlanVersions = Array.isArray(versions) ? versions : [];
        const latestVersion = this.selectedPlanVersions[this.selectedPlanVersions.length - 1];
        this.selectedPlanDag = dag || this.planPayloadFromRecord(latestVersion);
        this.planLoadedTaskId = requestedTaskId;
        this.selectedPlanNodeId = "";
      } catch (error) {
        if (!silent && this.selectedTask?.taskId === requestedTaskId) {
          this.error = error.message || "加载计划图失败。";
          this.selectedPlanDag = null;
          this.selectedPlanVersions = [];
          this.planLoadedTaskId = "";
        }
      } finally {
        if (!silent) {
          this.planLoading = false;
        }
      }
    },
    selectPlanVersion(version) {
      const payload = this.planPayloadFromRecord(version);
      if (payload) {
        this.selectedPlanDag = payload;
        this.selectedPlanNodeId = "";
      }
    },
    focusPlanNode(nodeId) {
      this.selectedPlanNodeId = String(nodeId || "");
      this.$nextTick(() => {
        this.$refs.planDagGraph?.selectNode?.(this.selectedPlanNodeId);
        this.$refs.planDagCanvas?.scrollIntoView({ behavior: "smooth", block: "nearest" });
      });
    },
    handlePlanNodeSelect(nodeId) {
      this.selectedPlanNodeId = String(nodeId || "");
      this.$nextTick(() => {
        const cards = Array.from(this.$refs.planNodeList?.children || []);
        const selectedCard = cards.find((card) => card.dataset.planNodeId === this.selectedPlanNodeId);
        selectedCard?.scrollIntoView({ behavior: "smooth", block: "nearest", inline: "nearest" });
      });
    },
    downloadPlanDagJson() {
      if (!this.selectedPlanDag) {
        return;
      }
      this.downloadText(
        `${this.planDownloadName()}.json`,
        JSON.stringify(this.selectedPlanDag, null, 2),
        "application/json"
      );
    },
    planDownloadName() {
      const task = this.shortId(this.selectedPlanDag?.taskId || this.selectedTask?.taskId || "task");
      const version = this.selectedPlanDag?.version ? `v${this.selectedPlanDag.version}` : "snapshot";
      return `plan-dag-${task}-${version}`;
    },
    downloadText(filename, content, type) {
      const blob = new Blob([content], { type });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    },
    planPayloadFromRecord(record) {
      if (!record) {
        return null;
      }
      const dag = record.dag && typeof record.dag === "object" ? record.dag : this.parsePlanJson(record.dagJson);
      return {
        tenantId: record.tenantId,
        taskId: record.taskId,
        planId: record.planId,
        version: record.version,
        status: record.status,
        createdAt: record.createdAt,
        updatedAt: record.updatedAt,
        nodes: Array.isArray(dag?.nodes) ? dag.nodes : [],
        edges: Array.isArray(dag?.edges) ? dag.edges : [],
        summary: dag?.summary || {}
      };
    },
    parsePlanJson(value) {
      if (!value || typeof value !== "string") {
        return {};
      }
      try {
        return JSON.parse(value);
      } catch (error) {
        return {};
      }
    },
    onSelectedTaskChange(taskId) {
      const task = this.tasks.find((item) => item.taskId === taskId);
      if (task) {
        this.selectTask(task);
      }
    },
    pagedRows(rows, pageKey) {
      const items = Array.isArray(rows) ? rows : [];
      const page = this.clampedRuntimePage(pageKey, items.length);
      const start = (page - 1) * this.pageSize;
      return items.slice(start, start + this.pageSize);
    },
    runtimePageCount(total) {
      return Math.max(1, Math.ceil((Number(total) || 0) / this.pageSize));
    },
    clampedRuntimePage(pageKey, total) {
      const page = Number(this.runtimePages[pageKey]) || 1;
      return Math.min(Math.max(1, page), this.runtimePageCount(total));
    },
    runtimePageStart(pageKey, total) {
      if (!total) {
        return 0;
      }
      return (this.clampedRuntimePage(pageKey, total) - 1) * this.pageSize + 1;
    },
    runtimePageEnd(pageKey, total) {
      return Math.min(this.clampedRuntimePage(pageKey, total) * this.pageSize, total);
    },
    runtimePageButtons(pageKey, total) {
      const pageCount = this.runtimePageCount(total);
      const current = this.clampedRuntimePage(pageKey, total);
      const start = Math.max(1, Math.min(current - 2, pageCount - 4));
      const end = Math.min(pageCount, start + 4);
      return Array.from({ length: end - start + 1 }, (_, index) => start + index);
    },
    showRuntimePagination(total) {
      return Number(total) > this.pageSize;
    },
    showRuntimePager(total) {
      return Number(total) > 0;
    },
    goRuntimePage(pageKey, page, total) {
      this.runtimePages = {
        ...this.runtimePages,
        [pageKey]: Math.min(Math.max(1, Number(page) || 1), this.runtimePageCount(total))
      };
    },
    resetRuntimePage(pageKey) {
      this.runtimePages = {
        ...this.runtimePages,
        [pageKey]: 1
      };
    },
    clampRuntimePages() {
      this.runtimePages = {
        ...this.runtimePages,
        tasks: this.clampedRuntimePage("tasks", this.filteredTasks.length),
        agentEffects: this.clampedRuntimePage("agentEffects", this.agentEffectRows.length),
        lowScores: this.clampedRuntimePage("lowScores", this.lowScoreTasks.length),
        reasonMetrics: this.clampedRuntimePage("reasonMetrics", this.reasonMetrics.length),
        experienceScenarios: this.clampedRuntimePage("experienceScenarios", this.experienceScenarios.length),
        experienceIndexes: this.clampedRuntimePage("experienceIndexes", this.experienceIndexes.length),
        experiences: this.clampedRuntimePage("experiences", this.experienceItems.length),
        events: this.clampedRuntimePage("events", this.filteredEvents.length),
        tools: this.clampedRuntimePage("tools", this.filteredTopTools.length),
        governance: this.clampedRuntimePage("governance", this.filteredGovernanceTools.length),
        audits: this.clampedRuntimePage("audits", this.filteredAudits.length)
      };
    },
    isActiveTask(task) {
      const normalized = String(task?.status || "").toUpperCase();
      return ["PENDING", "RUNNING", "WAIT_TOOL", "WAIT_MODEL", "WAIT_CONFIRMATION", "WAITING_CONFIRM"].includes(normalized);
    },
    resolvePlanNodeStatus(node) {
      const snapshotStatus = String(
        node?.status || (node?.success === true ? "SUCCESS" : node?.success === false ? "FAILED" : "PLANNED")
      ).toUpperCase();
      if (!this.isActiveTask(this.selectedTask)) {
        return ["RUNNING", "EXECUTING", "WAITING", "WAIT_TOOL", "WAIT_MODEL", "PENDING"].includes(snapshotStatus)
          ? "COMPLETED"
          : snapshotStatus;
      }
      const event = this.latestPlanNodeEvent(node);
      return event ? this.planStatusFromEvent(event, snapshotStatus) : snapshotStatus;
    },
    latestPlanNodeEvent(node) {
      const nodeStepId = String(node?.stepId ?? "");
      const nodeToolName = String(node?.toolName || "").trim().toLowerCase();
      const matches = (Array.isArray(this.selectedEvents) ? this.selectedEvents : []).filter((event) => {
        const payload = this.parseEventPayload(event?.payload);
        const eventStepId = String(payload?.stepId ?? payload?.step ?? "");
        const eventToolName = String(event?.toolName || payload?.toolName || "").trim().toLowerCase();
        if (nodeStepId && eventStepId) {
          return nodeStepId === eventStepId;
        }
        return !!nodeToolName && nodeToolName === eventToolName;
      });
      return matches.reduce((latest, event) => {
        if (!latest) {
          return event;
        }
        const eventOrder = Number(event?.createTime || 0) * 1000 + Number(event?.sequence || 0);
        const latestOrder = Number(latest?.createTime || 0) * 1000 + Number(latest?.sequence || 0);
        return eventOrder >= latestOrder ? event : latest;
      }, null);
    },
    parseEventPayload(payload) {
      if (payload && typeof payload === "object") {
        return payload;
      }
      if (!payload || typeof payload !== "string") {
        return {};
      }
      try {
        const parsed = JSON.parse(payload);
        return parsed && typeof parsed === "object" ? parsed : {};
      } catch (error) {
        return {};
      }
    },
    planStatusFromEvent(event, fallback = "PLANNED") {
      const type = String(event?.type || "").toUpperCase();
      const status = String(event?.status || "").toUpperCase();
      const payload = this.parseEventPayload(event?.payload);
      if (status === "FAILED" || event?.errorCode || payload?.success === false || type === "FAILED") {
        return "FAILED";
      }
      if (type === "TOOL_RESULT" || type === "MODEL_RESULT" || type === "FINISHED" || status === "SUCCESS") {
        return "COMPLETED";
      }
      if (["TOOL_CALL", "MODEL_CALL", "STARTED", "THINK", "PLAN", "STATUS"].includes(type)
        || ["RUNNING", "WAIT_TOOL", "WAIT_MODEL"].includes(status)) {
        return "RUNNING";
      }
      return fallback;
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
        const cancelledTask = await cancelAgentTask(task.taskId, task.tenantId || this.runtimeTenantId);
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
        this.error = error.message || "停止任务失败。";
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
        const updated = await submitAgentTaskFeedback(this.selectedTask.taskId, this.selectedTask.tenantId || this.runtimeTenantId, {
          tenantId: this.selectedTask.tenantId || this.runtimeTenantId,
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
        this.error = error.message || "保存任务反馈失败。";
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
        await updateConversationHistoryStatus(task.userId || this.userId, historyId, {
          tenantId: task.tenantId || this.runtimeTenantId,
          conversationId: historyId,
          status: "cancelled"
        });
      } catch (error) {
        // 任务停止已成功，历史状态会在下一次保存或加载时刷新。
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
    eventDetailHint(event) {
      return this.compactPlanText(
        event?.toolName || event?.errorMessage || event?.errorCode || "点击查看事件详情",
        72
      );
    },
    openEventDetail(event) {
      this.selectedEventId = event?.eventId || "";
    },
    closeEventDetail() {
      this.selectedEventId = "";
    },
    formatEventPayload(payload) {
      if (payload === null || payload === undefined || payload === "") {
        return "暂无事件载荷";
      }
      if (typeof payload === "string") {
        try {
          return JSON.stringify(JSON.parse(payload), null, 2);
        } catch (error) {
          return payload;
        }
      }
      try {
        return JSON.stringify(payload, null, 2);
      } catch (error) {
        return String(payload);
      }
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
      return `${value} 毫秒`;
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
          suggestion: "建议执行",
          confirm_required: "需要确认",
          forbidden: "禁止"
        }[normalized] || "只读"
      );
    },
    formatRuntimeAction(value) {
      const normalized = String(value || "").toLowerCase();
      return (
        {
          auto_execute: "自动执行",
          ask_before_execute: "执行前确认",
          deny: "拒绝"
        }[normalized] || "自动执行"
      );
    },
    formatFeedbackReason(value) {
      return this.feedbackReasonOptions.find((item) => item.value === value)?.label || "其他";
    },
    formatTaskStatus(value) {
      const normalized = String(value || "").toUpperCase();
      return (
        {
          PENDING: "等待中",
          RUNNING: "运行中",
          WAIT_TOOL: "等待工具",
          WAIT_MODEL: "等待模型",
          WAIT_CONFIRMATION: "等待确认",
          SUCCESS: "成功",
          COMPLETED: "完成",
          FAILED: "失败",
          CANCELLED: "已取消",
          CANCELED: "已取消",
          DENIED: "已拒绝",
          RATE_LIMITED: "已限流",
          CIRCUIT_OPEN: "熔断",
          GENERATED: "已生成",
          PLANNED: "已计划",
          OK: "正常"
        }[normalized] || (normalized ? normalized.replaceAll("_", " ") : "未知")
      );
    },
    formatEventType(value) {
      const normalized = String(value || "").toUpperCase();
      return (
        {
          CREATED: "已创建",
          STARTED: "已启动",
          PLANNED: "已计划",
          TOOL_CALL: "工具调用",
          TOOL_RESULT: "工具结果",
          MODEL_CALL: "模型调用",
          MODEL_RESULT: "模型结果",
          RETRIEVAL: "检索",
          MCP_TOOL: "MCP 工具",
          MEMORY: "记忆",
          FEEDBACK: "反馈",
          FINISHED: "已完成",
          FAILED: "失败",
          CANCELLED: "已取消",
          CANCELED: "已取消"
        }[normalized] || (normalized ? normalized.replaceAll("_", " ") : "未知事件")
      );
    },
    formatAttributionSource(value) {
      const normalized = String(value || "").toLowerCase();
      return (
        {
          rule: "规则",
          user_feedback: "用户反馈",
          tool_result: "工具结果",
          model_review: "模型评审",
          manual: "人工标注"
        }[normalized] || (normalized ? normalized.replaceAll("_", " ") : "规则")
      );
    },
    formatAuditMode(value) {
      const normalized = String(value || "").toLowerCase();
      return (
        {
          auto_execute: "自动执行",
          ask_before_execute: "执行前确认",
          confirm_required: "需要确认",
          deny: "拒绝",
          readonly: "只读",
          suggestion: "建议执行"
        }[normalized] || (normalized ? normalized.replaceAll("_", " ") : "-")
      );
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
    formatPlanAction(value) {
      const normalized = String(value || "step").toLowerCase();
      return (
        {
          step: "步骤",
          mcp_tool: "MCP 工具",
          tool_call: "工具调用",
          model_call: "模型调用",
          runtime: "运行态",
          final_answer: "最终回答",
          reviewer: "评审",
          planner: "规划"
        }[normalized] || normalized.replaceAll("_", " ")
      );
    },
    compactPlanText(value, maxLength = 24) {
      const text = String(value || "").replace(/\s+/g, " ").trim();
      if (text.length <= maxLength) {
        return text || "-";
      }
      return `${text.slice(0, Math.max(4, maxLength - 1))}...`;
    },
    compactPlanTextForWidth(value, maxWidth, fontSize = 14, fontWeight = 700) {
      const text = String(value || "").replace(/\s+/g, " ").trim() || "-";
      const ellipsis = "...";
      if (this.estimatedPlanTextWidth(text, fontSize, fontWeight) <= maxWidth) {
        return text;
      }
      const chars = Array.from(text);
      let result = "";
      for (const char of chars) {
        const next = `${result}${char}`;
        if (this.estimatedPlanTextWidth(`${next}${ellipsis}`, fontSize, fontWeight) > maxWidth) {
          break;
        }
        result = next;
      }
      return `${result || chars[0] || ""}${ellipsis}`;
    },
    compactPlanTextLinesForWidth(value, maxWidth, maxLines = 2, fontSize = 14, fontWeight = 700) {
      const text = String(value || "").replace(/\s+/g, " ").trim() || "-";
      if (maxLines <= 1 || this.estimatedPlanTextWidth(text, fontSize, fontWeight) <= maxWidth) {
        return [this.compactPlanTextForWidth(text, maxWidth, fontSize, fontWeight)];
      }
      const lines = [];
      let remaining = text;
      while (remaining && lines.length < maxLines) {
        const isLastLine = lines.length === maxLines - 1;
        const line = this.takePlanTextLineForWidth(remaining, maxWidth, fontSize, fontWeight, isLastLine);
        lines.push(line);
        if (isLastLine || line.endsWith("...")) {
          break;
        }
        remaining = remaining.slice(Array.from(line).length).replace(/^[_\-\s.]+/, "");
      }
      return lines.length ? lines : ["-"];
    },
    takePlanTextLineForWidth(text, maxWidth, fontSize = 14, fontWeight = 700, withEllipsis = false) {
      const chars = Array.from(text);
      let result = "";
      let lastBreakBeforeOverflow = -1;
      for (let index = 0; index < chars.length; index += 1) {
        if (/[\s._:/\\|-]/.test(chars[index])) {
          lastBreakBeforeOverflow = index;
        }
        const next = `${result}${chars[index]}`;
        const suffix = withEllipsis && index < chars.length - 1 ? "..." : "";
        if (this.estimatedPlanTextWidth(`${next}${suffix}`, fontSize, fontWeight) > maxWidth) {
          if (!withEllipsis && lastBreakBeforeOverflow > 0 && lastBreakBeforeOverflow < index) {
            return chars.slice(0, lastBreakBeforeOverflow + 1).join("").replace(/[\s._:/\\|-]+$/, "");
          }
          return withEllipsis ? `${result || chars[0] || ""}...` : (result || chars[0] || "");
        }
        result = next;
      }
      return result;
    },
    estimatedPlanTextWidth(text, fontSize = 14, fontWeight = 700) {
      const weightScale = Number(fontWeight) >= 800 ? 1.08 : 1;
      return Array.from(String(text || "")).reduce((width, char) => {
        if (/[\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af]/.test(char)) {
          return width + fontSize;
        }
        if (/[A-Z0-9]/.test(char)) {
          return width + fontSize * 0.64 * weightScale;
        }
        if (/[a-z]/.test(char)) {
          return width + fontSize * 0.56 * weightScale;
        }
        if (/[._:/\\|-]/.test(char)) {
          return width + fontSize * 0.36;
        }
        if (/\s/.test(char)) {
          return width + fontSize * 0.32;
        }
        return width + fontSize * 0.5;
      }, 0);
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
