import "../../styles/pages/evidence-debugger.css";
import { RefreshCw, Search } from "@lucide/vue";
import { debugDocumentDecision } from "../../services/api";

export default {
  name: "EvidenceDebuggerView",
  components: {
    RefreshCw,
    Search
  },
  props: {
    userId: {
      type: String,
      default: ""
    }
  },
  data() {
    return {
      loading: false,
      error: "",
      activeAction: "",
      selectedTraceKey: "",
      form: {
        query: "",
        topK: 8,
        debug: true
      },
      result: null
    };
  },
  computed: {
    reasoning() {
      return this.result?.reasoning || {};
    },
    decision() {
      return this.result?.decision || {};
    },
    trace() {
      return this.decision?.trace || {};
    },
    graph() {
      return this.reasoning?.graph || {};
    },
    graphEdges() {
      return (this.graph?.edges || []).map((edge, index) => ({
        ...edge,
        key: `${edge.sourceNodeId || "source"}-${edge.targetNodeId || "target"}-${index}`
      }));
    },
    evidenceNodes() {
      return (this.graph?.nodes || []).map((node, index) => ({
        ...node,
        nodeId: node.nodeId || `node-${index}`
      }));
    },
    reasoningSteps() {
      return (this.reasoning?.reasoningChain || []).map((step, index) => ({
        ...step,
        key: `${step.step || "step"}-${index}`
      }));
    },
    traceSteps() {
      return (this.trace?.steps || []).map((step, index) => ({
        ...step,
        key: `${step.ruleId || "rule"}-${index}`
      }));
    },
    selectedTrace() {
      return this.traceSteps.find((step) => step.key === this.selectedTraceKey)
        || this.traceSteps.find((step) => step.matched)
        || this.traceSteps[0]
        || null;
    },
    decisionClass() {
      const action = String(this.decision?.action || "").toLowerCase();
      return {
        answer: action === "answer",
        expand: action === "expand",
        clarify: action === "clarify",
        review: action === "review_required",
        refuse: action === "refuse"
      };
    }
  },
  watch: {
    traceSteps() {
      this.selectedTraceKey = this.traceSteps.find((step) => step.matched)?.key || this.traceSteps[0]?.key || "";
    }
  },
  methods: {
    async runDecisionDebug(action = "run") {
      if (!this.form.query || this.loading) {
        return;
      }
      this.loading = true;
      this.activeAction = action === "refresh" ? "refresh" : "run";
      this.error = "";
      try {
        this.result = await debugDocumentDecision({
          query: this.form.query,
          topK: Math.max(1, Math.min(Number(this.form.topK) || 8, 20)),
          userId: this.userId,
          debug: this.form.debug
        });
      } catch (error) {
        this.error = error?.message || "证据决策调试失败";
      } finally {
        this.loading = false;
        this.activeAction = "";
      }
    },
    percent(value) {
      const numeric = Number(value);
      if (!Number.isFinite(numeric)) {
        return "-";
      }
      return `${Math.round(Math.max(0, Math.min(numeric, 1)) * 100)}%`;
    },
    nodeClass(node) {
      return {
        define: node.type === "DEFINE",
        support: node.type === "SUPPORT",
        example: node.type === "EXAMPLE",
        contradict: node.type === "CONTRADICT"
      };
    },
    factEntries(step) {
      return Object.entries(step?.facts || {}).map(([key, value]) => ({
        key,
        value: Array.isArray(value) ? value.join(", ") : String(value)
      }));
    }
  }
};
