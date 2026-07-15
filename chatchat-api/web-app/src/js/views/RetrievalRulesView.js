import "../../styles/pages/agent-runtime.css";
import {
  AlertTriangle,
  Box,
  CheckCircle,
  ChevronLeft,
  ChevronRight,
  Clock,
  FileText,
  GripVertical,
  Info,
  Layers,
  Pencil,
  Plus,
  RefreshCw,
  Search,
  Send,
  SlidersHorizontal,
  Target,
  Trash2,
  X
} from "@lucide/vue";
import {
  activateRetrievalRuleVersion,
  deleteChunkTypeRule,
  deleteExpandRule,
  deleteIntentRule,
  deleteSemanticLexiconEntry,
  fetchRetrievalRules,
  publishRetrievalRules,
  publishRetrievalRuleType,
  refreshRetrievalRules,
  saveChunkTypeRule,
  saveExpandRule,
  saveIntentRule,
  saveSemanticLexiconEntry
} from "../../services/api";

export default {
  name: "RetrievalRulesView",
  components: {
    AlertTriangle,
    Box,
    CheckCircle,
    ChevronLeft,
    ChevronRight,
    Clock,
    FileText,
    GripVertical,
    Info,
    Layers,
    Pencil,
    Plus,
    RefreshCw,
    Search,
    Send,
    SlidersHorizontal,
    Target,
    Trash2,
    X
  },
  data() {
    return {
      rulesLoading: false,
      rulesError: "",
      activeRuleTab: "intent",
      ruleSearch: "",
      statusFilter: "all",
      priorityFilter: "all",
      currentPage: 1,
      pageSize: 10,
      ruleDialogOpen: false,
      ruleDialogKind: "intent",
      retrievalRules: {
        intentRules: [],
        chunkTypeRules: [],
        expandRules: [],
        semanticLexiconEntries: [],
        versions: [],
        activeVersions: {},
        refreshedAt: 0
      },
      ruleForms: {
        intent: this.emptyIntentRule(),
        chunk: this.emptyChunkTypeRule(),
        expand: this.emptyExpandRule(),
        lexicon: this.emptySemanticLexiconEntry()
      }
    };
  },
  computed: {
    ruleTabs() {
      return [
        { key: "intent", label: "意图规则" },
        { key: "expand", label: "扩展规则" },
        { key: "chunk", label: "分片规则" },
        { key: "lexicon", label: "语义词库" }
      ];
    },
    activeRuleLabel() {
      return this.ruleTabs.find((tab) => tab.key === this.activeRuleTab)?.label || "规则";
    },
    activeIntentVersion() {
      return this.retrievalRules.activeVersions?.intentVersion || 1;
    },
    activeChunkVersion() {
      return this.retrievalRules.activeVersions?.chunkVersion || 1;
    },
    activeExpandVersion() {
      return this.retrievalRules.activeVersions?.expandVersion || 1;
    },
    activeLexiconVersion() {
      return this.retrievalRules.activeVersions?.lexiconVersion || 1;
    },
    lastPublishedAt() {
      const timestamps = (this.retrievalRules.versions || [])
        .map((version) => version.publishedAt || version.createdAt || version.updatedAt)
        .filter(Boolean)
        .map(Number)
        .filter((value) => !Number.isNaN(value));
      const latest = timestamps.length ? Math.max(...timestamps) : this.retrievalRules.refreshedAt;
      return this.formatDateTime(latest);
    },
    allRules() {
      return [
        ...this.retrievalRules.intentRules,
        ...this.retrievalRules.expandRules,
        ...this.retrievalRules.chunkTypeRules,
        ...this.retrievalRules.semanticLexiconEntries
      ];
    },
    enabledCount() {
      return this.allRules.filter((rule) => rule.enabled !== false).length;
    },
    draftCount() {
      return this.allRules.length - this.enabledCount;
    },
    overviewCards() {
      return [
        {
          key: "total",
          label: "规则总数",
          value: this.allRules.length,
          enabled: this.enabledCount,
          draft: this.draftCount,
          icon: Layers,
          tone: "blue"
        },
        {
          key: "intent",
          label: "意图规则",
          value: this.retrievalRules.intentRules.length,
          enabled: this.countEnabled(this.retrievalRules.intentRules),
          draft: this.countDraft(this.retrievalRules.intentRules),
          icon: Target,
          tone: "indigo"
        },
        {
          key: "expand",
          label: "扩展规则",
          value: this.retrievalRules.expandRules.length,
          enabled: this.countEnabled(this.retrievalRules.expandRules),
          draft: this.countDraft(this.retrievalRules.expandRules),
          icon: SlidersHorizontal,
          tone: "green"
        },
        {
          key: "chunk",
          label: "分片规则",
          value: this.retrievalRules.chunkTypeRules.length,
          enabled: this.countEnabled(this.retrievalRules.chunkTypeRules),
          draft: this.countDraft(this.retrievalRules.chunkTypeRules),
          icon: Box,
          tone: "purple"
        },
        {
          key: "lexicon",
          label: "词库条目",
          value: this.retrievalRules.semanticLexiconEntries.length,
          enabled: this.countEnabled(this.retrievalRules.semanticLexiconEntries),
          draft: this.countDraft(this.retrievalRules.semanticLexiconEntries),
          icon: FileText,
          tone: "blue"
        }
      ];
    },
    ruleRows() {
      if (this.activeRuleTab === "expand") {
        return this.retrievalRules.expandRules.map((rule, index) => this.expandRuleRow(rule, index));
      }
      if (this.activeRuleTab === "chunk") {
        return this.retrievalRules.chunkTypeRules.map((rule, index) => this.chunkRuleRow(rule, index));
      }
      if (this.activeRuleTab === "lexicon") {
        return this.retrievalRules.semanticLexiconEntries.map((rule, index) => this.lexiconRuleRow(rule, index));
      }
      return this.retrievalRules.intentRules.map((rule, index) => this.intentRuleRow(rule, index));
    },
    filteredRuleRows() {
      const query = this.ruleSearch.toLowerCase();
      return this.ruleRows
        .filter((row) => {
          const haystack = `${row.title} ${row.detail} ${row.statusText}`.toLowerCase();
          return !query || haystack.includes(query);
        })
        .filter((row) => {
          if (this.statusFilter === "enabled") {
            return row.enabled;
          }
          if (this.statusFilter === "draft") {
            return !row.enabled;
          }
          return true;
        })
        .filter((row) => {
          if (this.priorityFilter === "high") {
            return row.priority >= 80;
          }
          if (this.priorityFilter === "medium") {
            return row.priority >= 40 && row.priority < 80;
          }
          if (this.priorityFilter === "low") {
            return row.priority < 40;
          }
          return true;
        })
        .sort((a, b) => b.score - a.score || a.title.localeCompare(b.title));
    },
    totalPages() {
      return Math.max(1, Math.ceil(this.filteredRuleRows.length / this.pageSize));
    },
    pagedRuleRows() {
      const page = Math.min(this.currentPage, this.totalPages);
      const start = (page - 1) * this.pageSize;
      return this.filteredRuleRows.slice(start, start + this.pageSize);
    },
    paginationStart() {
      if (!this.filteredRuleRows.length) {
        return 0;
      }
      return (Math.min(this.currentPage, this.totalPages) - 1) * this.pageSize + 1;
    },
    paginationEnd() {
      if (!this.filteredRuleRows.length) {
        return 0;
      }
      return Math.min(this.paginationStart + this.pagedRuleRows.length - 1, this.filteredRuleRows.length);
    },
    ruleHealthItems() {
      return [
        {
          key: "enabled",
          label: "已启用",
          value: this.enabledCount,
          icon: CheckCircle,
          tone: "green"
        },
        {
          key: "draft",
          label: "草稿",
          value: this.draftCount,
          icon: FileText,
          tone: "blue"
        },
        {
          key: "missing",
          label: "缺少关键词",
          value: this.missingKeywordCount,
          icon: AlertTriangle,
          tone: "orange"
        },
        {
          key: "review",
          label: "需要复核",
          value: this.needsReviewCount,
          icon: Info,
          tone: "purple"
        }
      ];
    },
    missingKeywordCount() {
      return [
        ...this.retrievalRules.intentRules.filter((rule) => !String(rule.keywords || rule.regex || "").trim()),
        ...this.retrievalRules.chunkTypeRules.filter((rule) => !String(rule.keywords || rule.pattern || "").trim()),
        ...this.retrievalRules.expandRules.filter((rule) => !String(rule.expandWords || "").trim()),
        ...this.retrievalRules.semanticLexiconEntries.filter((rule) => !String(rule.term || rule.mappedTerm || rule.aliases || "").trim())
      ].length;
    },
    needsReviewCount() {
      return this.allRules.filter((rule) => Number(rule.weight || 1) <= 0 || Number(rule.priority || 0) < 0).length;
    },
    topIntentSignals() {
      const rows = this.retrievalRules.intentRules
        .map((rule, index) => {
          const row = this.intentRuleRow(rule, index);
          return {
            key: row.key,
            label: rule.name || rule.intent || row.title,
            value: row.score * 17 + 100,
            ratio: 10
          };
        })
        .sort((a, b) => b.value - a.value)
        .slice(0, 4);
      return this.withRatios(rows);
    },
    topExpansionSignals() {
      const rows = this.retrievalRules.expandRules
        .map((rule, index) => {
          const row = this.expandRuleRow(rule, index);
          return {
            key: row.key,
            label: `${rule.sourceWord || "全局"} -> ${this.splitRuleWords(rule.expandWords).slice(0, 2).join(", ") || "空"}`,
            value: row.score * 13 + 80,
            ratio: 10
          };
        })
        .sort((a, b) => b.value - a.value)
        .slice(0, 4);
      return this.withRatios(rows);
    },
    currentRuleForm() {
      return this.ruleForms[this.ruleDialogKind] || this.ruleForms.intent;
    },
    ruleDialogTitle() {
      const labels = {
        intent: "意图规则",
        chunk: "分片规则",
        expand: "扩展规则",
        lexicon: "语义词库"
      };
      return `${this.currentRuleForm?.id ? "编辑" : "创建"}${labels[this.ruleDialogKind] || "规则"}`;
    }
  },
  watch: {
    activeRuleTab() {
      this.currentPage = 1;
    },
    ruleSearch() {
      this.currentPage = 1;
    },
    statusFilter() {
      this.currentPage = 1;
    },
    priorityFilter() {
      this.currentPage = 1;
    },
    pageSize() {
      this.currentPage = 1;
    },
    totalPages(value) {
      if (this.currentPage > value) {
        this.currentPage = value;
      }
    }
  },
  mounted() {
    this.loadRetrievalRules();
  },
  methods: {
    async loadRetrievalRules() {
      this.rulesLoading = true;
      this.rulesError = "";
      try {
        const rules = await fetchRetrievalRules();
        this.retrievalRules = this.normalizeRules(rules);
      } catch (error) {
        this.rulesError = error.message || "加载检索规则失败。";
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
        this.rulesError = error.message || "刷新检索规则失败。";
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
        this.rulesError = error.message || "发布检索规则失败。";
      } finally {
        this.rulesLoading = false;
      }
    },
    async publishRuleType(type) {
      this.rulesLoading = true;
      this.rulesError = "";
      try {
        const rules = await publishRetrievalRuleType(type);
        this.retrievalRules = this.normalizeRules(rules);
      } catch (error) {
        this.rulesError = error.message || "发布检索规则版本失败。";
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
        this.rulesError = error.message || "激活检索规则版本失败。";
      } finally {
        this.rulesLoading = false;
      }
    },
    async saveRule(kind) {
      this.rulesLoading = true;
      this.rulesError = "";
      try {
        if (kind === "intent") {
          await saveIntentRule(this.rulePayload(this.ruleForms.intent, ["intent"]));
          this.ruleForms.intent = this.emptyIntentRule();
        } else if (kind === "chunk") {
          await saveChunkTypeRule(this.rulePayload(this.ruleForms.chunk, ["chunkType"]));
          this.ruleForms.chunk = this.emptyChunkTypeRule();
        } else if (kind === "lexicon") {
          await saveSemanticLexiconEntry(this.rulePayload(this.ruleForms.lexicon, ["term"]));
          this.ruleForms.lexicon = this.emptySemanticLexiconEntry();
        } else {
          await saveExpandRule(this.rulePayload(this.ruleForms.expand, ["expandWords"]));
          this.ruleForms.expand = this.emptyExpandRule();
        }
        await this.loadRetrievalRules();
        this.closeRuleDialog();
      } catch (error) {
        this.rulesError = error.message || "保存检索规则失败。";
      } finally {
        this.rulesLoading = false;
      }
    },
    async deleteRule(kind, rule) {
      if (kind === "lexicon" && rule?.builtin) {
        this.rulesError = "默认语义词库条目不能删除。";
        return;
      }
      if (!rule?.id || !window.confirm("确定删除这条检索规则吗？")) {
        return;
      }
      this.rulesLoading = true;
      this.rulesError = "";
      try {
        if (kind === "intent") {
          await deleteIntentRule(rule.id);
        } else if (kind === "chunk") {
          await deleteChunkTypeRule(rule.id);
        } else if (kind === "lexicon") {
          await deleteSemanticLexiconEntry(rule.id);
        } else {
          await deleteExpandRule(rule.id);
        }
        await this.loadRetrievalRules();
      } catch (error) {
        this.rulesError = error.message || "删除检索规则失败。";
      } finally {
        this.rulesLoading = false;
      }
    },
    setActiveTab(tab) {
      this.activeRuleTab = tab;
    },
    resetRuleFilters() {
      this.ruleSearch = "";
      this.statusFilter = "all";
      this.priorityFilter = "all";
    },
    openCreateRule() {
      this.ruleDialogKind = this.activeRuleTab;
      this.resetRuleForm(this.ruleDialogKind);
      this.ruleDialogOpen = true;
    },
    openEditRule(kind, rule) {
      this.ruleDialogKind = kind;
      this.resetRuleForm(kind);
      this.editRule(kind, rule);
      this.ruleDialogOpen = true;
    },
    closeRuleDialog() {
      this.ruleDialogOpen = false;
    },
    editRule(kind, rule) {
      if (kind === "intent") {
        this.ruleForms.intent = { ...this.emptyIntentRule(), ...rule };
      } else if (kind === "chunk") {
        this.ruleForms.chunk = { ...this.emptyChunkTypeRule(), ...rule };
      } else if (kind === "lexicon") {
        this.ruleForms.lexicon = { ...this.emptySemanticLexiconEntry(), ...rule };
      } else {
        this.ruleForms.expand = { ...this.emptyExpandRule(), ...rule };
      }
    },
    resetRuleForm(kind) {
      if (kind === "intent") {
        this.ruleForms.intent = this.emptyIntentRule();
      } else if (kind === "chunk") {
        this.ruleForms.chunk = this.emptyChunkTypeRule();
      } else if (kind === "lexicon") {
        this.ruleForms.lexicon = this.emptySemanticLexiconEntry();
      } else {
        this.ruleForms.expand = this.emptyExpandRule();
      }
    },
    normalizeRules(rules) {
      return {
        intentRules: Array.isArray(rules?.intentRules) ? rules.intentRules : [],
        chunkTypeRules: Array.isArray(rules?.chunkTypeRules) ? rules.chunkTypeRules : [],
        expandRules: Array.isArray(rules?.expandRules) ? rules.expandRules : [],
        semanticLexiconEntries: Array.isArray(rules?.semanticLexiconEntries) ? rules.semanticLexiconEntries : [],
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
          throw new Error(`${this.formatRuleFieldLabel(field)}不能为空。`);
        }
      });
      return payload;
    },
    formatRuleFieldLabel(field) {
      return (
        {
          intent: "意图",
          chunkType: "分片类型",
          term: "术语",
          expandWords: "扩展词"
        }[field] || field
      );
    },
    intentRuleRow(rule, index) {
      const words = this.splitRuleWords(`${rule.keywords || ""},${rule.regex || ""}`);
      const title = rule.name || `${rule.intent || "通用"}意图`;
      return this.baseRuleRow(rule, index, {
        kind: "intent",
        title,
        detailLabel: "关键词",
        detail: words.join(", ") || "无关键词",
        keywordCount: words.length
      });
    },
    expandRuleRow(rule, index) {
      const words = this.splitRuleWords(rule.expandWords);
      const title = rule.sourceWord ? `${rule.sourceWord}扩展` : "全局扩展";
      return this.baseRuleRow(rule, index, {
        kind: "expand",
        title,
        detailLabel: "扩展词",
        detail: words.join(", ") || "无扩展词",
        keywordCount: words.length
      });
    },
    chunkRuleRow(rule, index) {
      const words = this.splitRuleWords(`${rule.keywords || ""},${rule.pattern || ""}`);
      const title = `${rule.chunkType || "通用"}分片`;
      return this.baseRuleRow(rule, index, {
        kind: "chunk",
        title,
        detailLabel: "关键词",
        detail: words.join(", ") || "无关键词",
        keywordCount: words.length
      });
    },
    lexiconRuleRow(rule, index) {
      const words = this.splitRuleWords(`${rule.mappedTerm || ""},${rule.aliases || ""}`);
      const mapped = rule.mappedTerm ? ` -> ${rule.mappedTerm}` : "";
      const taxonomy = [rule.domain, rule.category].filter(Boolean).join(" / ");
      return this.baseRuleRow(rule, index, {
        kind: "lexicon",
        title: `${rule.term || "术语"}${mapped}`,
        detailLabel: taxonomy || "词库",
        detail: words.join(", ") || "无别名",
        keywordCount: words.length,
        statusText: rule.builtin ? "内置" : undefined,
        statusClass: rule.builtin ? "builtin" : undefined
      });
    },
    baseRuleRow(rule, index, config) {
      const priority = Number(rule.priority || 0);
      const weight = Number(rule.weight || 1);
      const enabled = rule.enabled !== false;
      return {
        key: `${config.kind}-${rule.id || index}`,
        kind: config.kind,
        raw: rule,
        title: config.title,
        detailLabel: config.detailLabel,
        detail: config.detail,
        priority,
        weight,
        enabled,
        statusText: config.statusText || (enabled ? "已启用" : "草稿"),
        statusClass: config.statusClass || (enabled ? "enabled" : "draft"),
        createdAt: rule.createdAt || rule.updatedAt || rule.publishedAt || 0,
        score: this.ruleScore(rule, config.keywordCount),
        rawIndex: index
      };
    },
    splitRuleWords(value) {
      return String(value || "")
        .split(/[,，;；\n\r\t ]+/)
        .map((word) => word.trim())
        .filter(Boolean)
        .slice(0, 16);
    },
    ruleScore(rule, keywordCount = 0) {
      const enabled = rule.enabled === false ? 0 : 12;
      return enabled + Number(rule.weight || 1) * 4 + Number(rule.priority || 0) + keywordCount * 2;
    },
    withRatios(rows) {
      const max = rows[0]?.value || 1;
      return rows.map((row) => ({
        ...row,
        ratio: Math.max(8, Math.round((row.value / max) * 100))
      }));
    },
    countEnabled(rules) {
      return rules.filter((rule) => rule.enabled !== false).length;
    },
    countDraft(rules) {
      return rules.length - this.countEnabled(rules);
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
    emptySemanticLexiconEntry() {
      return {
        id: null,
        term: "",
        mappedTerm: "",
        aliases: "",
        language: "zh",
        category: "指标",
        domain: "数据",
        weight: 1,
        priority: 100,
        builtin: false,
        enabled: true
      };
    },
    formatDateTime(value) {
      if (!value) {
        return "-";
      }
      const date = new Date(Number(value));
      if (Number.isNaN(date.getTime())) {
        return "-";
      }
      return date.toLocaleString("zh-CN");
    },
    formatCount(value) {
      return Number(value || 0).toLocaleString("zh-CN");
    }
  }
};
