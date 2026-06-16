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
  fetchAgentTaskPlanDag,
  fetchAgentTaskPlanVersions,
  fetchToolGovernance,
  submitAgentTaskFeedback,
  updateConversationHistoryStatus
} from "../../services/api";
import { notifyAgentTaskCancelled } from "../utils/agentTaskEvents";

const DEFAULT_RUNTIME_PAGE_SIZE = 10;

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
      planLoading: false,
      error: "",
      tenantId: this.userId || "",
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
      selectedPlanDag: null,
      selectedPlanVersions: [],
      planZoom: 1,
      planPanX: 0,
      planPanY: 0,
      planDragActive: false,
      planDragStart: null,
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
        { value: "", label: "Select reason" },
        { value: "answer_correct", label: "Answer correct" },
        { value: "steps_clear", label: "Steps clear" },
        { value: "tool_result_accurate", label: "Tool result accurate" },
        { value: "environment_mismatch", label: "Environment mismatch" },
        { value: "answer_incomplete", label: "Answer incomplete" },
        { value: "tool_call_error", label: "Tool call error" },
        { value: "knowledge_outdated", label: "Knowledge outdated" },
        { value: "other", label: "Other" }
      ]
    };
  },
  computed: {
    tabs() {
      return [
        { key: "tasks", label: "Tasks", icon: ListFilter, count: this.tasks.length },
        { key: "effects", label: "Effects", icon: Activity, count: this.lowScoreTasks.length },
        { key: "experiences", label: "Experience", icon: GitBranch, count: this.experienceItems.length },
        { key: "events", label: "Events", icon: Database, count: this.filteredEvents.length },
        { key: "plan", label: "Plan DAG", icon: GitBranch, count: this.planNodes.length },
        { key: "tools", label: "Tools", icon: ShieldAlert, count: this.filteredTopTools.length },
        { key: "governance", label: "Governance", icon: ShieldCheck, count: this.filteredGovernanceTools.length },
        { key: "audits", label: "Audits", icon: ShieldCheck, count: this.filteredAudits.length }
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
        { label: "Total Tasks", value: summary.totalTasks || 0, icon: Layers },
        { label: "Running", value: summary.activeTasks || 0, icon: Activity },
        { label: "Queue Depth", value: summary.queueDepth || 0, icon: GitBranch },
        { label: "Workers", value: summary.activeWorkerCount || 0, icon: TimerReset },
        { label: "Tool Calls", value: toolRuntime.totalCalls || 0, icon: Database },
        { label: "Failed Tasks", value: summary.failedTasks || 0, icon: XCircle }
      ];
    },
    governanceMetrics() {
      const counts = this.filteredAudits.reduce((acc, item) => {
        const outcome = String(item.outcome || "").toLowerCase();
        acc[outcome] = (acc[outcome] || 0) + 1;
        return acc;
      }, {});
      return [
        { label: "Passed", value: counts.success || 0, icon: ShieldCheck },
        { label: "Denied", value: counts.denied || 0, icon: ShieldX },
        {
          label: "Protected",
          value: (counts.rate_limited || 0) + (counts.circuit_open || 0),
          icon: ShieldAlert
        }
      ];
    },
    effectMetrics() {
      const analytics = this.effectAnalytics || {};
      return [
        { label: "Feedback Samples", value: analytics.feedbackTasks || 0, icon: Database },
        { label: "Useful Rate", value: this.formatPercent(analytics.usefulRate), icon: ShieldCheck },
        { label: "Adoption Rate", value: this.formatPercent(analytics.adoptedRate), icon: Activity },
        { label: "Resolution Rate", value: this.formatPercent(analytics.resolvedRate), icon: GitBranch },
        { label: "Failure Rate", value: this.formatPercent(analytics.failedRate), icon: XCircle },
        { label: "Low Scores", value: this.lowScoreTasks.length, icon: ShieldAlert }
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
        title: this.selectedTask.question || "Untitled task",
        subtitle: this.selectedTask.agentId || "default-agent",
        description:
          this.selectedTask.answerSummary || this.selectedTask.errorMessage || this.selectedTask.question || ""
      };
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
        return {
          ...node,
          id,
          x: 36 + level * 380,
          y: 36 + laneIndex * 168,
          width: 310,
          height: 118,
          labelText: this.compactPlanText(node.label || node.toolName || node.actionType || id, 36),
          fullLabelText: node.label || node.toolName || node.actionType || id,
          actionText: this.formatPlanAction(node.actionType),
          toolText: this.compactPlanText(node.toolName || node.actionType || "step", 34),
          statusText: node.status || (node.success === true ? "success" : node.success === false ? "failed" : "planned"),
          detailText: node.errorMessage || node.outputPreview || ""
        };
      });
    },
    planEdgeViews() {
      const nodesById = this.planNodeViews.reduce((acc, node) => {
        acc[node.id] = node;
        return acc;
      }, {});
      return this.planEdges
        .map((edge, index) => {
          const sourceId = edge.source || edge.from || `step-${edge.fromStepId}`;
          const targetId = edge.target || edge.to || `step-${edge.toStepId}`;
          const source = nodesById[sourceId];
          const target = nodesById[targetId];
          if (!source || !target) {
            return null;
          }
          const sx = source.x + source.width;
          const sy = source.y + source.height / 2;
          const tx = target.x;
          const ty = target.y + target.height / 2;
          const curve = Math.max(48, (tx - sx) / 2);
          return {
            id: edge.id || `edge-${index}`,
            label: this.compactPlanText(edge.label || edge.kind || edge.type || "", 24),
            hasLabel: !!(edge.label || edge.kind || edge.type),
            x: (sx + tx) / 2,
            y: (sy + ty) / 2 - 14,
            path: `M ${sx} ${sy} C ${sx + curve} ${sy}, ${tx - curve} ${ty}, ${tx} ${ty}`
          };
        })
        .filter(Boolean);
    },
    planDagSize() {
      const width = Math.max(1040, ...this.planNodeViews.map((node) => node.x + node.width + 44));
      const height = Math.max(500, ...this.planNodeViews.map((node) => node.y + node.height + 44));
      return { width, height };
    },
    planDagViewBox() {
      const width = this.planDagSize.width / this.planZoom;
      const height = this.planDagSize.height / this.planZoom;
      return `${this.planPanX} ${this.planPanY} ${width} ${height}`;
    },
    planZoomLabel() {
      return `${Math.round(this.planZoom * 100)}%`;
    },
    latestPlanVersionLabel() {
      if (!this.selectedPlanDag?.version) {
        return "No snapshot";
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
  },
  watch: {
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
    async loadRuntime() {
      await this.loadLegacyRuntime();
    },
    async loadLegacyRuntime() {
      this.loading = true;
      this.error = "";
      try {
        const [summary, audits, effects, experiences, governance] = await Promise.all([
          fetchAgentRuntimeSummary({
            tenantId: this.tenantId,
            latestLimit: 100
          }),
          fetchAgentRuntimeToolAudits({
            tenantId: this.tenantId,
            limit: 100
          }),
          fetchAgentEffectAnalytics({
            tenantId: this.tenantId,
            lowScoreLimit: 100
          }),
          fetchAgentExperiences({
            tenantId: this.tenantId,
            limit: 100
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
        this.legacyRuntimeLoaded = true;
        this.clampRuntimePages();
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
        this.error = error.message || "Failed to load runtime monitoring.";
      } finally {
        this.loading = false;
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
      this.syncFeedbackDraft(task);
      this.resetRuntimePage("events");
      this.selectedPlanDag = null;
      this.selectedPlanVersions = [];
      this.resetPlanDagView();
      await this.reloadEvents();
      if (this.activeTab === "plan") {
        await this.loadPlanDag();
      }
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
        this.runtimePages = {
          ...this.runtimePages,
          events: this.clampedRuntimePage("events", this.filteredEvents.length)
        };
      } catch (error) {
        this.error = error.message || "Failed to load event chain.";
        this.selectedEvents = [];
      } finally {
        this.eventsLoading = false;
      }
    },
    async loadPlanDag() {
      if (!this.selectedTask?.taskId) {
        this.selectedPlanDag = null;
        this.selectedPlanVersions = [];
        return;
      }
      this.planLoading = true;
      this.error = "";
      try {
        const tenantId = this.selectedTask.tenantId || this.tenantId;
        const [dag, versions] = await Promise.all([
          fetchAgentTaskPlanDag(this.selectedTask.taskId, tenantId),
          fetchAgentTaskPlanVersions(this.selectedTask.taskId, tenantId)
        ]);
        this.selectedPlanVersions = Array.isArray(versions) ? versions : [];
        const latestVersion = this.selectedPlanVersions[this.selectedPlanVersions.length - 1];
        this.selectedPlanDag = dag || this.planPayloadFromRecord(latestVersion);
        this.resetPlanDagView();
      } catch (error) {
        this.error = error.message || "Failed to load plan DAG.";
        this.selectedPlanDag = null;
        this.selectedPlanVersions = [];
      } finally {
        this.planLoading = false;
      }
    },
    selectPlanVersion(version) {
      const payload = this.planPayloadFromRecord(version);
      if (payload) {
        this.selectedPlanDag = payload;
        this.resetPlanDagView();
      }
    },
    zoomPlanDag(factor, anchor = null) {
      const nextZoom = Math.min(3.5, Math.max(0.35, this.planZoom * factor));
      if (Math.abs(nextZoom - this.planZoom) < 0.001) {
        return;
      }
      const currentWidth = this.planDagSize.width / this.planZoom;
      const currentHeight = this.planDagSize.height / this.planZoom;
      const anchorX = anchor?.x ?? this.planPanX + currentWidth / 2;
      const anchorY = anchor?.y ?? this.planPanY + currentHeight / 2;
      const ratioX = currentWidth === 0 ? 0.5 : (anchorX - this.planPanX) / currentWidth;
      const ratioY = currentHeight === 0 ? 0.5 : (anchorY - this.planPanY) / currentHeight;
      const nextWidth = this.planDagSize.width / nextZoom;
      const nextHeight = this.planDagSize.height / nextZoom;
      this.planZoom = nextZoom;
      this.planPanX = anchorX - ratioX * nextWidth;
      this.planPanY = anchorY - ratioY * nextHeight;
    },
    resetPlanDagView() {
      this.planZoom = 1;
      this.planPanX = 0;
      this.planPanY = 0;
      this.planDragActive = false;
      this.planDragStart = null;
    },
    handlePlanDagWheel(event) {
      if (!this.planNodes.length) {
        return;
      }
      event.preventDefault();
      const rect = event.currentTarget.getBoundingClientRect();
      const viewWidth = this.planDagSize.width / this.planZoom;
      const viewHeight = this.planDagSize.height / this.planZoom;
      const x = this.planPanX + ((event.clientX - rect.left) / rect.width) * viewWidth;
      const y = this.planPanY + ((event.clientY - rect.top) / rect.height) * viewHeight;
      this.zoomPlanDag(event.deltaY < 0 ? 1.12 : 0.88, { x, y });
    },
    startPlanDagPan(event) {
      if (event.button !== undefined && event.button !== 0) {
        return;
      }
      event.currentTarget.setPointerCapture?.(event.pointerId);
      this.planDragActive = true;
      this.planDragStart = {
        clientX: event.clientX,
        clientY: event.clientY,
        panX: this.planPanX,
        panY: this.planPanY
      };
    },
    movePlanDagPan(event) {
      if (!this.planDragActive || !this.planDragStart) {
        return;
      }
      const rect = event.currentTarget.getBoundingClientRect();
      const viewWidth = this.planDagSize.width / this.planZoom;
      const viewHeight = this.planDagSize.height / this.planZoom;
      const dx = ((event.clientX - this.planDragStart.clientX) / rect.width) * viewWidth;
      const dy = ((event.clientY - this.planDragStart.clientY) / rect.height) * viewHeight;
      this.planPanX = this.planDragStart.panX - dx;
      this.planPanY = this.planDragStart.panY - dy;
    },
    stopPlanDagPan(event) {
      if (this.planDragActive) {
        event?.currentTarget?.releasePointerCapture?.(event.pointerId);
      }
      this.planDragActive = false;
      this.planDragStart = null;
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
    downloadPlanDagSvg() {
      const svg = this.$refs.planDagSvg;
      if (!svg || !this.planNodes.length) {
        return;
      }
      const clone = svg.cloneNode(true);
      clone.setAttribute("xmlns", "http://www.w3.org/2000/svg");
      clone.setAttribute("viewBox", `0 0 ${this.planDagSize.width} ${this.planDagSize.height}`);
      clone.setAttribute("width", String(this.planDagSize.width));
      clone.setAttribute("height", String(this.planDagSize.height));
      const style = document.createElementNS("http://www.w3.org/2000/svg", "style");
      style.textContent = `
        .plan-dag-edges path{fill:none;stroke:#667085;stroke-width:2.4}
        .plan-dag-edges marker path{fill:#667085}
        .plan-dag-edge-label rect{fill:#fbfcff;stroke:#d7e0ef;stroke-width:1}
        .plan-dag-edge-label text{fill:#344054;font-size:12px;font-weight:800;dominant-baseline:middle;text-anchor:middle}
        .plan-dag-node rect{fill:#fff;stroke:rgba(47,124,246,.46);stroke-width:2}
        .plan-dag-node.mcp-tool rect,.plan-dag-node.tool-call rect{stroke:rgba(47,124,246,.56);fill:#f8fbff}
        .plan-dag-node.final-answer rect{stroke:rgba(244,166,41,.48);fill:#fffdf7}
        .plan-dag-node.runtime rect{stroke:rgba(102,112,133,.42);fill:#f8fafc}
        .plan-dag-node.failed rect{stroke:rgba(239,79,95,.62);fill:#fff7f8}
        .plan-dag-node.success rect{stroke:rgba(32,178,107,.52);fill:#f7fffa}
        .plan-dag-node text{fill:#5d6b82;font-family:Arial,sans-serif;font-size:14px;font-weight:800}
        .plan-dag-node .plan-dag-node-title{fill:#111827;font-size:16px;font-weight:900}
        .plan-dag-node .plan-dag-node-meta{fill:#7a8799;font-size:13px}
      `;
      clone.insertBefore(style, clone.firstChild);
      this.downloadText(
        `${this.planDownloadName()}.svg`,
        new XMLSerializer().serializeToString(clone),
        "image/svg+xml"
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
        this.error = error.message || "Failed to stop task.";
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
        this.error = error.message || "Failed to save task feedback.";
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
      return new Intl.DateTimeFormat("en-US", {
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
      return new Intl.DateTimeFormat("en-US", {
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit"
      }).format(new Date(value));
    },
    formatAuditTime(value) {
      if (!value) {
        return "-";
      }
      return new Intl.DateTimeFormat("en-US", {
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
          readonly: "Read-only",
          suggestion: "Suggestion",
          confirm_required: "Confirmation required",
          forbidden: "Forbidden"
        }[normalized] || "Read-only"
      );
    },
    formatRuntimeAction(value) {
      const normalized = String(value || "").toLowerCase();
      return (
        {
          auto_execute: "Auto execute",
          ask_before_execute: "Ask before execute",
          deny: "Deny"
        }[normalized] || "Auto execute"
      );
    },
    formatFeedbackReason(value) {
      return this.feedbackReasonOptions.find((item) => item.value === value)?.label || "Other";
    },
    formatOutcome(value) {
      const normalized = String(value || "").toLowerCase();
      return (
        {
          success: "Passed",
          denied: "Denied",
          failed: "Failed",
          rate_limited: "Rate limited",
          circuit_open: "Circuit open"
        }[normalized] || (normalized ? normalized.replaceAll("_", " ") : "Unknown")
      );
    },
    formatToolHealth(value) {
      return (
        {
          healthy: "Stable",
          problem: "Problem"
        }[value] || "All"
      );
    },
    formatPlanAction(value) {
      return String(value || "step").replaceAll("_", " ");
    },
    compactPlanText(value, maxLength = 24) {
      const text = String(value || "").replace(/\s+/g, " ").trim();
      if (text.length <= maxLength) {
        return text || "-";
      }
      return `${text.slice(0, Math.max(4, maxLength - 1))}...`;
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
