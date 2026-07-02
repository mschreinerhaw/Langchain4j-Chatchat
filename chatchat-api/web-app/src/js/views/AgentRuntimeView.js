import "../../styles/pages/agent-runtime.css";
import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  CircleDot,
  Clock3,
  Database,
  GitBranch,
  ListFilter,
  PauseCircle,
  PlayCircle,
  Plus,
  RefreshCw,
  Save,
  Search,
  SlidersHorizontal,
  SquareStack,
  Trash2,
  XCircle,
  Zap
} from "@lucide/vue";
import {
  cancelGenericAgentRun,
  deleteChunkTypeRule,
  deleteExpandRule,
  deleteIntentRule,
  fetchRetrievalRules,
  fetchGenericAgentRunTimeline,
  fetchGenericAgentRunTrace,
  fetchGenericAgentRuntimeSnapshot,
  fetchGenericAgentRuns,
  refreshRetrievalRules,
  activateRetrievalRuleVersion,
  publishRetrievalRules,
  publishRetrievalRuleType,
  saveChunkTypeRule,
  saveExpandRule,
  saveIntentRule,
  streamGenericAgentRunEvents
} from "../../services/api";

const TERMINAL_STATUSES = new Set(["COMPLETED", "FAILED", "CANCELLED"]);
const AUTO_REFRESH_MS = 10000;

export default {
  name: "AgentRuntimeView",
  components: {
    Activity,
    AlertTriangle,
    CheckCircle2,
    ChevronLeft,
    ChevronRight,
    CircleDot,
    Clock3,
    Database,
    GitBranch,
    ListFilter,
    PauseCircle,
    PlayCircle,
    Plus,
    RefreshCw,
    Save,
    Search,
    SlidersHorizontal,
    SquareStack,
    Trash2,
    XCircle,
    Zap
  },
  props: {
    embedded: {
      type: Boolean,
      default: false
    },
    userId: {
      type: String,
      default: ""
    }
  },
  data() {
    return {
      loading: false,
      timelineLoading: false,
      error: "",
      snapshot: null,
      runs: [],
      selectedRunId: "",
      timeline: {
        run: null,
        events: [],
        steps: [],
        observations: [],
        trace: null
      },
      filters: {
        status: "",
        tenantId: "",
        userId: this.userId || "",
        conversationId: "",
        keyword: "",
        limit: 10,
        offset: 0
      },
      activeDetailTab: "timeline",
      autoRefresh: this.embedded,
      refreshTimer: null,
      cancellingRunIds: {},
      streamActive: false,
      streamController: null,
      streamCursor: 0,
      streamState: {
        kind: "",
        message: ""
      },
      rulesLoading: false,
      rulesError: "",
      ruleTab: "intent",
      retrievalRules: {
        intentRules: [],
        chunkTypeRules: [],
        expandRules: [],
        versions: [],
        activeVersions: {},
        refreshedAt: 0
      },
      ruleForms: {
        intent: this.emptyIntentRule(),
        chunk: this.emptyChunkTypeRule(),
        expand: this.emptyExpandRule()
      }
    };
  },
  computed: {
    statusOptions() {
      return ["PENDING", "RUNNING", "WAITING_CONFIRMATION", "COMPLETED", "FAILED", "CANCELLED"];
    },
    selectedRun() {
      return this.timeline.run || this.runs.find((run) => run.runId === this.selectedRunId) || null;
    },
    events() {
      return Array.isArray(this.timeline.events) ? this.timeline.events : [];
    },
    steps() {
      return Array.isArray(this.timeline.steps) ? this.timeline.steps : [];
    },
    observations() {
      return Array.isArray(this.timeline.observations) ? this.timeline.observations : [];
    },
    trace() {
      return this.timeline.trace || null;
    },
    filteredRuns() {
      const keyword = this.filters.keyword.trim().toLowerCase();
      if (!keyword) {
        return this.runs;
      }
      return this.runs.filter((run) => {
        const request = run.request || {};
        const fields = [
          run.runId,
          run.status,
          run.errorMessage,
          request.query,
          request.tenantId,
          request.userId,
          request.conversationId,
          ...(Array.isArray(request.availableTools) ? request.availableTools : [])
        ];
        return fields.some((field) => String(field || "").toLowerCase().includes(keyword));
      });
    },
    canPageBack() {
      return this.filters.offset > 0;
    },
    metrics() {
      const snapshot = this.snapshot || {};
      return [
        { label: "Total", value: snapshot.totalRuns || 0, icon: SquareStack },
        { label: "Active", value: snapshot.activeRuns || 0, icon: Activity },
        { label: "Pending", value: snapshot.pendingRuns || 0, icon: Clock3 },
        { label: "Waiting", value: snapshot.waitingConfirmationRuns || 0, icon: AlertTriangle },
        { label: "Completed", value: snapshot.completedRuns || 0, icon: CheckCircle2 },
        { label: "Failed", value: snapshot.failedRuns || 0, icon: XCircle },
        { label: "Cancelled", value: snapshot.cancelledRuns || 0, icon: CircleDot },
        { label: "Avg", value: this.formatDuration(snapshot.averageDurationMs || 0), icon: Zap }
      ];
    },
    detailTabs() {
      return [
        { key: "timeline", label: "Timeline", icon: GitBranch, count: this.timelineItems.length },
        {
          key: "trace",
          label: "Trace",
          icon: Activity,
          count: this.trace ? (this.trace.evidence?.length || 0) + (this.trace.toolCalls?.length || 0) : 0
        },
        { key: "events", label: "Events", icon: Database, count: this.events.length },
        { key: "steps", label: "Steps", icon: SquareStack, count: this.steps.length },
        { key: "observations", label: "Observations", icon: CircleDot, count: this.observations.length }
      ];
    },
    ruleTabs() {
      return [
        {
          key: "intent",
          label: "Intent",
          type: "intent",
          count: this.retrievalRules.intentRules.length,
          active: this.retrievalRules.activeVersions?.intentVersion || 1
        },
        {
          key: "chunk",
          label: "Chunk Type",
          type: "chunk",
          count: this.retrievalRules.chunkTypeRules.length,
          active: this.retrievalRules.activeVersions?.chunkVersion || 1
        },
        {
          key: "expand",
          label: "Expansion",
          type: "expand",
          count: this.retrievalRules.expandRules.length,
          active: this.retrievalRules.activeVersions?.expandVersion || 1
        }
      ];
    },
    currentRuleType() {
      return this.ruleTabs.find((tab) => tab.key === this.ruleTab)?.type || "intent";
    },
    currentRuleActiveVersion() {
      return this.ruleTabs.find((tab) => tab.key === this.ruleTab)?.active || 1;
    },
    currentRuleVersions() {
      return (this.retrievalRules.versions || []).filter((version) => version.type === this.currentRuleType);
    },
    timelineItems() {
      const eventItems = this.events.map((event) => ({
        key: `event-${event.eventId || `${event.type}-${event.createdAt}`}`,
        kind: "event",
        at: event.createdAt || 0,
        title: event.type || "EVENT",
        text: event.message || "-",
        payload: event.payload
      }));
      const stepItems = this.steps.map((step) => ({
        key: `step-${step.step}`,
        kind: "step",
        at: step.plannedAt || 0,
        title: `Step ${step.step} ${step.action || ""}`.trim(),
        text: step.reason || step.answerPreview || step.resolvedToolName || step.toolName || "-",
        payload: step.executionPlan
      }));
      const observationItems = this.observations.map((observation, index) => ({
        key: `observation-${index}`,
        kind: "observation",
        at: this.observationTime(observation),
        title: observation.type || "OBSERVATION",
        text: [observation.source, observation.content].filter(Boolean).join(" - ") || "-",
        payload: observation.metadata
      }));
      return [...eventItems, ...stepItems, ...observationItems].sort((left, right) => {
        if (right.at !== left.at) {
          return right.at - left.at;
        }
        return left.key.localeCompare(right.key);
      });
    }
  },
  watch: {
    autoRefresh(value) {
      if (value) {
        this.startAutoRefresh();
      } else {
        this.stopAutoRefresh();
      }
    }
  },
  mounted() {
    this.loadRuntime();
    if (!this.embedded) {
      this.loadRetrievalRules();
    }
    if (this.autoRefresh) {
      this.startAutoRefresh();
    }
  },
  activated() {
    if (this.autoRefresh) {
      this.startAutoRefresh();
    }
  },
  deactivated() {
    this.stopAutoRefresh();
    this.stopStream();
  },
  beforeUnmount() {
    this.stopAutoRefresh();
    this.stopStream();
  },
  methods: {
    async loadRuntime() {
      this.loading = true;
      this.error = "";
      try {
        const [snapshot, runs] = await Promise.all([
          fetchGenericAgentRuntimeSnapshot(),
          fetchGenericAgentRuns({
            status: this.filters.status,
            tenantId: this.filters.tenantId,
            userId: this.filters.userId,
            conversationId: this.filters.conversationId,
            limit: this.filters.limit,
            offset: this.filters.offset
          })
        ]);
        this.snapshot = snapshot || {};
        this.runs = Array.isArray(runs) ? runs : [];
        if (!this.selectedRunId || !this.runs.some((run) => run.runId === this.selectedRunId)) {
          this.selectedRunId = this.runs[0]?.runId || "";
        }
        if (this.selectedRunId) {
          await this.loadTimeline(this.selectedRunId);
        } else {
          this.timeline = { run: null, events: [], steps: [], observations: [], trace: null };
        }
      } catch (error) {
        this.error = error.message || "Failed to load Agent Runtime.";
      } finally {
        this.loading = false;
      }
    },
    async loadRetrievalRules() {
      this.rulesLoading = true;
      this.rulesError = "";
      try {
        const rules = await fetchRetrievalRules();
        this.retrievalRules = this.normalizeRules(rules);
      } catch (error) {
        this.rulesError = error.message || "Failed to load retrieval rules.";
      } finally {
        this.rulesLoading = false;
      }
    },
    async refreshRules() {
      this.rulesLoading = true;
      this.rulesError = "";
      try {
        const rules = await refreshRetrievalRules();
        this.retrievalRules = this.normalizeRules(rules);
      } catch (error) {
        this.rulesError = error.message || "Failed to refresh retrieval rules.";
      } finally {
        this.rulesLoading = false;
      }
    },
    async publishAllRules() {
      this.rulesLoading = true;
      this.rulesError = "";
      try {
        const rules = await publishRetrievalRules();
        this.retrievalRules = this.normalizeRules(rules);
      } catch (error) {
        this.rulesError = error.message || "Failed to publish retrieval rules.";
      } finally {
        this.rulesLoading = false;
      }
    },
    async publishCurrentRules() {
      this.rulesLoading = true;
      this.rulesError = "";
      try {
        const rules = await publishRetrievalRuleType(this.currentRuleType);
        this.retrievalRules = this.normalizeRules(rules);
      } catch (error) {
        this.rulesError = error.message || "Failed to publish retrieval rule version.";
      } finally {
        this.rulesLoading = false;
      }
    },
    async activateRuleVersion(version) {
      if (!version?.version) {
        return;
      }
      this.rulesLoading = true;
      this.rulesError = "";
      try {
        const rules = await activateRetrievalRuleVersion(version.type, version.version);
        this.retrievalRules = this.normalizeRules(rules);
      } catch (error) {
        this.rulesError = error.message || "Failed to activate retrieval rule version.";
      } finally {
        this.rulesLoading = false;
      }
    },
    async saveRule(kind) {
      this.rulesLoading = true;
      this.rulesError = "";
      try {
        if (kind === "intent") {
          await saveIntentRule(this.rulePayload(this.ruleForms.intent, ["intent", "keywords"]));
          this.ruleForms.intent = this.emptyIntentRule();
        } else if (kind === "chunk") {
          await saveChunkTypeRule(this.rulePayload(this.ruleForms.chunk, ["chunkType"]));
          this.ruleForms.chunk = this.emptyChunkTypeRule();
        } else {
          await saveExpandRule(this.rulePayload(this.ruleForms.expand, ["expandWords"]));
          this.ruleForms.expand = this.emptyExpandRule();
        }
        await this.loadRetrievalRules();
      } catch (error) {
        this.rulesError = error.message || "Failed to save retrieval rule.";
      } finally {
        this.rulesLoading = false;
      }
    },
    async deleteRule(kind, rule) {
      if (!rule?.id || !window.confirm("Delete this retrieval rule?")) {
        return;
      }
      this.rulesLoading = true;
      this.rulesError = "";
      try {
        if (kind === "intent") {
          await deleteIntentRule(rule.id);
        } else if (kind === "chunk") {
          await deleteChunkTypeRule(rule.id);
        } else {
          await deleteExpandRule(rule.id);
        }
        await this.loadRetrievalRules();
      } catch (error) {
        this.rulesError = error.message || "Failed to delete retrieval rule.";
      } finally {
        this.rulesLoading = false;
      }
    },
    editRule(kind, rule) {
      if (kind === "intent") {
        this.ruleForms.intent = { ...this.emptyIntentRule(), ...rule };
      } else if (kind === "chunk") {
        this.ruleForms.chunk = { ...this.emptyChunkTypeRule(), ...rule };
      } else {
        this.ruleForms.expand = { ...this.emptyExpandRule(), ...rule };
      }
    },
    resetRuleForm(kind) {
      if (kind === "intent") {
        this.ruleForms.intent = this.emptyIntentRule();
      } else if (kind === "chunk") {
        this.ruleForms.chunk = this.emptyChunkTypeRule();
      } else {
        this.ruleForms.expand = this.emptyExpandRule();
      }
    },
    normalizeRules(rules) {
      return {
        intentRules: Array.isArray(rules?.intentRules) ? rules.intentRules : [],
        chunkTypeRules: Array.isArray(rules?.chunkTypeRules) ? rules.chunkTypeRules : [],
        expandRules: Array.isArray(rules?.expandRules) ? rules.expandRules : [],
        versions: Array.isArray(rules?.versions) ? rules.versions : [],
        activeVersions: rules?.activeVersions || {},
        refreshedAt: rules?.refreshedAt || 0
      };
    },
    rulePayload(form, requiredFields) {
      const payload = {
        ...form,
        weight: Number(form.weight || 1),
        priority: Number(form.priority || 0),
        enabled: !!form.enabled
      };
      requiredFields.forEach((field) => {
        if (!String(payload[field] || "").trim()) {
          throw new Error(`${field} is required.`);
        }
      });
      return payload;
    },
    emptyIntentRule() {
      return {
        id: null,
        intent: "",
        name: "",
        keywords: "",
        regex: "",
        weight: 1,
        priority: 0,
        enabled: true
      };
    },
    emptyChunkTypeRule() {
      return {
        id: null,
        chunkType: "",
        keywords: "",
        pattern: "",
        weight: 1,
        priority: 0,
        enabled: true
      };
    },
    emptyExpandRule() {
      return {
        id: null,
        intent: "",
        sourceWord: "",
        expandWords: "",
        weight: 1,
        priority: 0,
        enabled: true
      };
    },
    async loadTimeline(runId) {
      if (!runId) {
        return;
      }
      this.timelineLoading = true;
      try {
        const [timeline, trace] = await Promise.all([
          fetchGenericAgentRunTimeline(runId, {
            eventLimit: 200,
            stepLimit: 200,
            observationLimit: 200
          }),
          fetchGenericAgentRunTrace(runId)
        ]);
        this.timeline = {
          run: timeline?.run || null,
          events: Array.isArray(timeline?.events) ? timeline.events : [],
          steps: Array.isArray(timeline?.steps) ? timeline.steps : [],
          observations: Array.isArray(timeline?.observations) ? timeline.observations : [],
          trace: trace || null
        };
        this.streamCursor = this.events.reduce((cursor, event) => Math.max(cursor, event.createdAt || 0), 0);
      } catch (error) {
        this.error = error.message || "Failed to load run timeline.";
      } finally {
        this.timelineLoading = false;
      }
    },
    applyFilters() {
      this.filters.offset = 0;
      this.stopStream();
      this.loadRuntime();
    },
    pageBack() {
      if (!this.canPageBack) {
        return;
      }
      this.filters.offset = Math.max(0, this.filters.offset - this.filters.limit);
      this.loadRuntime();
    },
    pageForward() {
      this.filters.offset += this.filters.limit;
      this.loadRuntime();
    },
    selectRun(run) {
      if (!run?.runId || run.runId === this.selectedRunId) {
        return;
      }
      this.stopStream();
      this.selectedRunId = run.runId;
      this.activeDetailTab = "timeline";
      this.loadTimeline(run.runId);
    },
    async cancelRun(run) {
      if (!run?.runId || !this.isActiveRun(run) || this.isCancelling(run)) {
        return;
      }
      this.cancellingRunIds = { ...this.cancellingRunIds, [run.runId]: true };
      try {
        const cancelled = await cancelGenericAgentRun(run.runId);
        this.timeline = { ...this.timeline, run: cancelled || this.timeline.run };
        await this.loadRuntime();
      } catch (error) {
        this.error = error.message || "Failed to cancel run.";
      } finally {
        const next = { ...this.cancellingRunIds };
        delete next[run.runId];
        this.cancellingRunIds = next;
      }
    },
    toggleStream() {
      if (this.streamActive) {
        this.stopStream();
        return;
      }
      this.startStream();
    },
    startStream() {
      if (!this.selectedRunId || this.streamActive) {
        return;
      }
      const controller = new AbortController();
      this.streamController = controller;
      this.streamActive = true;
      this.streamState = { kind: "active", message: "Event stream connected." };
      streamGenericAgentRunEvents(
        this.selectedRunId,
        {
          afterCreatedAt: this.streamCursor,
          limit: 100,
          pollIntervalMs: 1000
        },
        {
          signal: controller.signal,
          start: (payload) => {
            this.streamState = {
              kind: "active",
              message: `Streaming ${payload?.runId || this.selectedRunId} from cursor ${payload?.cursor || 0}.`
            };
          },
          event: (event) => this.appendEvent(event),
          heartbeat: (payload) => {
            this.streamState = {
              kind: "active",
              message: `Heartbeat ${payload?.status || ""} at ${this.formatTime(payload?.timestamp)}.`
            };
          },
          done: (payload) => {
            this.streamState = {
              kind: "done",
              message: `Stream completed with status ${payload?.status || "done"}.`
            };
            this.streamActive = false;
            this.streamController = null;
            this.loadRuntime();
          },
          timeout: () => {
            this.streamState = { kind: "done", message: "Stream timed out." };
            this.streamActive = false;
            this.streamController = null;
          },
          error: (payload) => {
            this.streamState = { kind: "error", message: payload?.message || "Stream error." };
            this.streamActive = false;
            this.streamController = null;
          }
        }
      ).catch((error) => {
        if (controller.signal.aborted) {
          return;
        }
        this.streamState = { kind: "error", message: error.message || "Stream error." };
        this.streamActive = false;
        this.streamController = null;
      });
    },
    stopStream() {
      if (this.streamController) {
        this.streamController.abort();
      }
      this.streamController = null;
      this.streamActive = false;
    },
    appendEvent(event) {
      if (!event || event.runId !== this.selectedRunId) {
        return;
      }
      const exists = this.events.some((item) => {
        if (event.eventId && item.eventId) {
          return event.eventId === item.eventId;
        }
        return item.type === event.type && item.createdAt === event.createdAt && item.message === event.message;
      });
      if (exists) {
        return;
      }
      const events = [...this.events, event].sort((left, right) => (left.createdAt || 0) - (right.createdAt || 0));
      this.timeline = { ...this.timeline, events };
      this.streamCursor = Math.max(this.streamCursor, event.createdAt || 0);
    },
    startAutoRefresh() {
      this.stopAutoRefresh();
      this.refreshTimer = window.setInterval(() => {
        if (!this.streamActive) {
          this.loadRuntime();
        }
      }, AUTO_REFRESH_MS);
    },
    stopAutoRefresh() {
      if (this.refreshTimer) {
        window.clearInterval(this.refreshTimer);
        this.refreshTimer = null;
      }
    },
    isActiveRun(run) {
      const status = String(run?.status || "").toUpperCase();
      return !!status && !TERMINAL_STATUSES.has(status);
    },
    isCancelling(run) {
      return !!run?.runId && !!this.cancellingRunIds[run.runId];
    },
    statusClass(status) {
      return `status-${String(status || "unknown").toLowerCase().replace(/_/g, "-")}`;
    },
    updatedAt(run) {
      if (!run) {
        return 0;
      }
      if (run.finishedAt) {
        return run.finishedAt;
      }
      const events = Array.isArray(run.events) ? run.events : [];
      return events.length ? events[events.length - 1].createdAt : run.startedAt;
    },
    durationMs(run) {
      if (!run?.startedAt) {
        return 0;
      }
      return Math.max(0, (run.finishedAt || Date.now()) - run.startedAt);
    },
    observationTime(observation) {
      const metadata = observation?.metadata || {};
      const value = metadata.createdAt || metadata.timestamp || metadata.observedAt || metadata.plannedAt;
      const numeric = Number(value);
      return Number.isFinite(numeric) ? numeric : 0;
    },
    hasPayload(value) {
      return value && typeof value === "object" && Object.keys(value).length > 0;
    },
    formatJson(value) {
      try {
        return JSON.stringify(value, null, 2);
      } catch (error) {
        return String(value);
      }
    },
    formatTime(value) {
      if (!value) {
        return "-";
      }
      const date = new Date(Number(value));
      if (Number.isNaN(date.getTime())) {
        return "-";
      }
      return date.toLocaleString();
    },
    formatDuration(value) {
      const ms = Number(value || 0);
      if (ms < 1000) {
        return `${Math.max(0, Math.round(ms))}ms`;
      }
      if (ms < 60000) {
        return `${(ms / 1000).toFixed(1)}s`;
      }
      return `${Math.floor(ms / 60000)}m ${Math.round((ms % 60000) / 1000)}s`;
    },
    shortId(value) {
      const text = String(value || "");
      if (text.length <= 14) {
        return text || "-";
      }
      return `${text.slice(0, 8)}...${text.slice(-4)}`;
    }
  }
};
