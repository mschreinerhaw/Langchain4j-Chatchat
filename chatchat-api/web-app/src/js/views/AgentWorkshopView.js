import {
  createWorkshopAgent,
  deleteWorkshopAgent,
  fetchAgentWorkshop,
  publishWorkshopAgent,
  recallWorkshopAgent,
  setDefaultWorkshopAgent,
  updateWorkshopAgent
} from "../../services/api.js";
import * as XLSX from "xlsx";
import "../../styles/pages/skill-hub.css";

function uniqueList(values) {
  return [...new Set(values.map((value) => String(value || "").trim()).filter(Boolean))];
}

function parseList(value) {
  if (Array.isArray(value)) {
    return uniqueList(value);
  }
  return uniqueList(String(value || "").split(/[\n,，]/));
}

function joinList(values) {
  return Array.isArray(values) ? values.join("\n") : "";
}

function normalizeFieldKey(value) {
  return String(value || "")
    .trim()
    .toLowerCase()
    .replace(/[\s_\-:/\\（）()【】[\].]/g, "");
}

function fieldValue(row, aliases) {
  if (!row || typeof row !== "object") {
    return "";
  }
  for (const alias of aliases) {
    if (Object.prototype.hasOwnProperty.call(row, alias)) {
      return row[alias];
    }
  }
  const normalized = new Map(
    Object.entries(row).map(([key, value]) => [normalizeFieldKey(key), value])
  );
  for (const alias of aliases) {
    const value = normalized.get(normalizeFieldKey(alias));
    if (value !== undefined) {
      return value;
    }
  }
  return "";
}

function textValue(value) {
  if (value === null || value === undefined) {
    return "";
  }
  if (typeof value === "object") {
    return JSON.stringify(value);
  }
  return String(value).trim();
}

function booleanValue(value) {
  if (typeof value === "boolean") {
    return value;
  }
  const normalized = textValue(value).toLowerCase();
  return ["true", "1", "yes", "y", "default", "默认", "是"].includes(normalized);
}

function listValue(value) {
  if (Array.isArray(value)) {
    return uniqueList(value);
  }
  const raw = textValue(value);
  if (!raw) {
    return [];
  }
  if ((raw.startsWith("[") && raw.endsWith("]")) || (raw.startsWith("{") && raw.endsWith("}"))) {
    try {
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? uniqueList(parsed) : uniqueList(Object.values(parsed));
    } catch (error) {
      // Fall through to delimiter parsing.
    }
  }
  return uniqueList(raw.split(/[\n,，;；、|]/));
}

function normalizeAgentId(value) {
  return textValue(value)
    .toLowerCase()
    .replace(/\s+/g, "_")
    .replace(/[^a-z0-9_-]/g, "");
}

function defaultRoutingSettings() {
  return {
    smartSelectionEnabled: true,
    limitParallelCalls: true,
    maxParallelCalls: 3,
    maxRelevantMcpTools: 3
  };
}

function defaultWorkflowConfig() {
  return {
    enabled: true,
    runtimeEnvironment: "",
    workflow: "",
    executionStrategy: {
      mode: "sequential",
      stopOnError: true,
      maxSteps: 6,
      allowParallel: false
    },
    steps: [],
    toolDependencies: {},
    parallelSteps: []
  };
}

function normalizeRuntimeEnvironment(value) {
  const environment = String(value || "").trim().toUpperCase();
  return ["DEV", "TEST", "UAT", "PROD"].includes(environment) ? environment : "";
}

function defaultDataAssetForm() {
  return {
    defaultDataAssetEnabled: false,
    defaultDataAssetName: ""
  };
}

function defaultAssetSelectionPolicy() {
  return {
    assetSelectionMinRelevanceScore: 1,
    assetFallbackWhenEmpty: false,
    assetFallbackWhenInvalid: false
  };
}

function emptyForm() {
  return {
    id: "",
    name: "",
    description: "",
    usageScenarios: "",
    skillTags: "",
    defaultMode: "",
    modelName: "",
    systemPrompt: "",
    firstUseGreeting: "",
    preferredToolPrefixes: "",
    boundMcpServiceIds: "",
    boundMcpToolNames: "",
    toolConfigs: [],
    routingSettings: defaultRoutingSettings(),
    workflowConfig: defaultWorkflowConfig(),
    ...defaultDataAssetForm(),
    ...defaultAssetSelectionPolicy(),
    quickQuestions: "",
    marketStatus: "draft",
    defaultAgent: false
  };
}

export default {
  name: "AgentWorkshopView",
  data() {
    return {
      summary: {},
      agents: [],
      agentCategories: [],
      agentTotal: 0,
      agentPageCount: 1,
      selectedAgentIds: [],
      selectedAgentTemplates: {},
      availableTools: [],
      registeredMcpTools: [],
      models: [],
      documents: [],
      loading: false,
      saving: false,
      dialogOpen: false,
      dialogMode: "create",
      activeAgent: null,
      form: emptyForm(),
      importDialogOpen: false,
      importText: "",
      importFileName: "",
      importItems: [],
      importResults: [],
      importing: false,
      importOverwriteExisting: true,
      searchQuery: "",
      agentCategoryFilter: "all",
      agentStatusFilter: "all",
      agentModelFilter: "all",
      agentPage: 1,
      agentPageSize: 6,
      toolSearchQuery: "",
      toolBackendServiceTypeFilter: "all",
      toolGroupMode: "service",
      error: "",
      dialogError: "",
      importError: ""
    };
  },
  computed: {
    filteredAgents() {
      return this.agents;
    },
    agentCategoryOptions() {
      const tags = uniqueList(this.agentCategories)
        .sort((left, right) => left.localeCompare(right, "zh-CN"));
      return [
        { value: "all", label: "全部分类" },
        ...tags.map((tag) => ({ value: tag, label: tag }))
      ];
    },
    agentStatusOptions() {
      return [
        { value: "all", label: "全部状态" },
        { value: "published", label: "已发布" },
        { value: "unpublished", label: "未发布" },
        { value: "default", label: "默认Agent" },
        { value: "custom", label: "自定义" },
        { value: "builtin", label: "内置" }
      ];
    },
    agentModelOptions() {
      const models = uniqueList([
        ...this.models.map((model) => model?.value),
        ...this.agents.map((agent) => agent?.modelName || this.defaultModelName())
      ]).sort((left, right) => left.localeCompare(right));
      return [
        { value: "all", label: "全部模型" },
        ...models.map((model) => ({ value: model, label: model }))
      ];
    },
    hasActiveAgentFilters() {
      return this.agentCategoryFilter !== "all"
        || this.agentStatusFilter !== "all"
        || this.agentModelFilter !== "all";
    },
    totalAgentPages() {
      return this.agentPageCount;
    },
    paginatedAgents() {
      return this.agents;
    },
    selectedAgentCount() {
      return this.selectedAgentIds.length;
    },
    agentPageButtons() {
      const total = this.totalAgentPages;
      const current = Math.min(Math.max(1, this.agentPage), total);
      const start = Math.max(1, Math.min(current - 2, total - 4));
      const end = Math.min(total, start + 4);
      return Array.from({ length: end - start + 1 }, (_, index) => start + index);
    },
    selectedToolNames() {
      return parseList(this.form.boundMcpToolNames);
    },
    normalizedMcpTools() {
      return this.registeredMcpTools
        .filter((tool) => tool?.localToolName)
        .map((tool) => {
          const applicability = tool.applicability && typeof tool.applicability === "object"
            ? tool.applicability
            : {};
          const declaredBackendTypes = Array.isArray(applicability.backendServiceTypes)
            ? applicability.backendServiceTypes.map((type) => String(type || "").trim().toLowerCase()).filter(Boolean)
            : [];
          const legacyBackendType = String(tool.backendServiceType || "").trim().toLowerCase();
          const backendServiceTypes = uniqueList([
            ...declaredBackendTypes,
            ...(legacyBackendType ? [legacyBackendType] : [])
          ]);
          return {
            ...tool,
            localToolName: String(tool.localToolName),
            serviceName: tool.serviceName || "",
            serviceId: tool.serviceId || "",
            displayName: tool.displayName || "",
            remoteToolName: tool.remoteToolName || "",
            description: tool.description || "",
            backendServiceType: legacyBackendType || backendServiceTypes[0] || "",
            backendServiceTypes,
            outputType: tool.outputType || "",
            category: tool.category || "",
            categories: Array.isArray(tool.categories) ? tool.categories.filter(Boolean) : [],
            tags: Array.isArray(tool.tags) ? tool.tags.filter(Boolean) : [],
            parameters: Array.isArray(tool.parameters) ? tool.parameters : [],
            applicability,
            applicabilitySummary: applicability.summary || applicability.scopeLabel || ""
          };
        });
    },
    filteredMcpTools() {
      const keyword = this.toolSearchQuery.trim().toLowerCase();
      return this.normalizedMcpTools.filter((tool) => {
        const typeMatches = this.toolBackendServiceTypeFilter === "all"
          || (this.toolBackendServiceTypeFilter === "undeclared" && !tool.backendServiceTypes.length)
          || tool.backendServiceTypes.includes(this.toolBackendServiceTypeFilter);
        const keywordMatches = !keyword || this.toolSearchText(tool).includes(keyword);
        return typeMatches && keywordMatches;
      });
    },
    mcpToolGroups() {
      const selected = new Set(this.selectedToolNames);
      const groups = new Map();
      this.filteredMcpTools.forEach((tool) => {
        const group = this.resolveToolGroup(tool);
        if (!groups.has(group.key)) {
          groups.set(group.key, {
            ...group,
            tools: []
          });
        }
        groups.get(group.key).tools.push(tool);
      });
      return [...groups.values()]
        .map((group) => ({
          ...group,
          selectedCount: group.tools.filter((tool) => selected.has(tool.localToolName)).length,
          tools: this.sortMcpTools(group.tools, selected)
        }))
        .sort((left, right) => {
          const leftSelected = left.selectedCount > 0 ? 0 : 1;
          const rightSelected = right.selectedCount > 0 ? 0 : 1;
          if (leftSelected !== rightSelected) {
            return leftSelected - rightSelected;
          }
          return left.label.localeCompare(right.label);
        });
    },
    visibleMcpTools() {
      return this.mcpToolGroups.flatMap((group) => group.tools);
    },
    workflowSteps() {
      return Array.isArray(this.form.workflowConfig?.steps) ? this.form.workflowConfig.steps : [];
    },
    workflowSelectedToolSet() {
      return new Set(this.selectedToolNames);
    },
    toolGroupModeLabel() {
      if (this.toolGroupMode === "category") {
        return "分类";
      }
      if (this.toolGroupMode === "tag") {
        return "标签";
      }
      return "服务";
    },
    mcpToolResultLabel() {
      if (!this.registeredMcpTools.length) {
        return "后端暂无已注册工具";
      }
      const filteredCount = this.filteredMcpTools.length;
      const totalCount = this.normalizedMcpTools.length;
      return `已勾选 ${this.selectedToolNames.length} / ${totalCount}，当前 ${filteredCount} 个`;
    },
    mcpToolGroupSummary() {
      if (!this.filteredMcpTools.length) {
        return this.toolSearchQuery || this.toolBackendServiceTypeFilter !== "all"
          ? "没有匹配工具"
          : "暂无工具";
      }
      return `${this.mcpToolGroups.length} 个${this.toolGroupModeLabel}分组`;
    },
    mcpToolGroupOptions() {
      return [
        { value: "service", label: "按服务" },
        { value: "category", label: "按分类" },
        { value: "tag", label: "按标签" }
      ];
    },
    mcpBackendServiceTypeOptions() {
      const types = uniqueList(this.normalizedMcpTools
        .flatMap((tool) => tool.backendServiceTypes)
        .filter(Boolean))
        .sort((left, right) => this.backendServiceTypeLabel(left).localeCompare(this.backendServiceTypeLabel(right), "zh-CN"));
      const options = [
        { value: "all", label: "全部后端类型" },
        ...types.map((type) => ({ value: type, label: this.backendServiceTypeLabel(type) }))
      ];
      if (this.normalizedMcpTools.some((tool) => !tool.backendServiceTypes.length)) {
        options.push({ value: "undeclared", label: "未声明类型" });
      }
      return options;
    },
    importPreviewLabel() {
      if (this.importItems.length) {
        return `已解析 ${this.importItems.length} 个 Agent`;
      }
      return "支持 JSON、CSV、TSV、XLSX，字段名可使用中英文或业务别名";
    }
  },
  watch: {
    searchQuery() {
      this.agentPage = 1;
      this.loadWorkshop();
    },
    agentCategoryFilter() {
      this.agentPage = 1;
      this.loadWorkshop();
    },
    agentStatusFilter() {
      this.agentPage = 1;
      this.loadWorkshop();
    },
    agentModelFilter() {
      this.agentPage = 1;
      this.loadWorkshop();
    }
  },
  mounted() {
    this.loadWorkshop();
  },
  methods: {
    async loadWorkshop() {
      this.loading = true;
      this.error = "";
      try {
        const payload = await fetchAgentWorkshop({
          keyword: this.searchQuery.trim(),
          category: this.agentCategoryFilter,
          status: this.agentStatusFilter,
          model: this.agentModelFilter,
          page: this.agentPage,
          pageSize: this.agentPageSize
        });
        this.summary = payload?.summary || {};
        this.agents = Array.isArray(payload?.agents) ? payload.agents : [];
        this.refreshSelectedAgentTemplates();
        this.agentCategories = Array.isArray(payload?.agentCategories) ? payload.agentCategories : [];
        this.agentTotal = payload?.page?.total ?? this.agents.length;
        this.agentPage = payload?.page?.page || this.agentPage;
        this.agentPageSize = payload?.page?.pageSize || this.agentPageSize;
        this.agentPageCount = payload?.page?.totalPages || 1;
        this.availableTools = Array.isArray(payload?.availableTools) ? payload.availableTools : [];
        this.registeredMcpTools = Array.isArray(payload?.registeredMcpTools) ? payload.registeredMcpTools : [];
        this.models = Array.isArray(payload?.models) ? payload.models : [];
        this.documents = Array.isArray(payload?.documents) ? payload.documents : [];
        this.normalizeAgentFilters();
      } catch (error) {
        this.error = error.message || "Agent管理加载失败";
      } finally {
        this.loading = false;
      }
    },
    sortMcpTools(tools, selected) {
      return tools.sort((left, right) => {
        const leftSelected = selected.has(left.localToolName) ? 0 : 1;
        const rightSelected = selected.has(right.localToolName) ? 0 : 1;
        if (leftSelected !== rightSelected) {
          return leftSelected - rightSelected;
        }
        return left.localToolName.localeCompare(right.localToolName);
      });
    },
    resolveToolGroup(tool) {
      if (this.toolGroupMode === "category") {
        const category = tool.categories[0] || "未分类";
        return {
          key: `category:${category}`,
          label: category,
          subtitle: "分类"
        };
      }
      if (this.toolGroupMode === "tag") {
        const tag = tool.tags[0] || "未打标签";
        return {
          key: `tag:${tag}`,
          label: tag,
          subtitle: "标签"
        };
      }
      const serviceName = tool.serviceName || tool.serviceId || "未归属服务";
      return {
        key: `service:${tool.serviceId || serviceName}`,
        label: serviceName,
        subtitle: tool.serviceId && tool.serviceName ? tool.serviceId : "服务"
      };
    },
    toolSearchText(tool) {
      const fields = [
        tool?.localToolName,
        tool?.displayName,
        tool?.remoteToolName,
        tool?.description,
        tool?.serviceId,
        tool?.serviceName,
        tool?.backendServiceType,
        tool?.outputType,
        tool?.applicability?.scopeId,
        tool?.applicability?.scopeLabel,
        tool?.applicability?.summary,
        ...(Array.isArray(tool?.applicability?.backendServiceTypes) ? tool.applicability.backendServiceTypes : []),
        ...(Array.isArray(tool?.applicability?.useWhen) ? tool.applicability.useWhen : []),
        ...(Array.isArray(tool?.applicability?.notFor) ? tool.applicability.notFor : []),
        ...(tool?.categories || []),
        ...(tool?.tags || []),
        ...(tool?.parameters || []).flatMap((parameter) => [
          parameter?.name,
          parameter?.type,
          parameter?.description
        ])
      ];
      return fields.map((field) => String(field || "").toLowerCase()).join(" ");
    },
    backendServiceTypeLabel(type) {
      const normalized = String(type || "").trim().toLowerCase();
      const labels = {
        sql_datasource: "SQL 数据源",
        database_query: "数据库业务查询",
        ssh_host: "SSH 主机",
        http_endpoint: "HTTP 端点",
        api_service: "API 服务",
        web: "Web 服务",
        data: "数据服务"
      };
      return labels[normalized] || String(type || "未声明类型");
    },
    backendServiceTypesLabel(tool) {
      const types = Array.isArray(tool?.backendServiceTypes) ? tool.backendServiceTypes : [];
      return types.length ? types.map((type) => this.backendServiceTypeLabel(type)).join(" / ") : "未声明类型";
    },
    applicabilityTooltip(tool) {
      const applicability = tool?.applicability || {};
      const lines = [tool?.localToolName];
      if (applicability.summary || applicability.scopeLabel) {
        lines.push(`适用范围：${applicability.summary || applicability.scopeLabel}`);
      }
      if (Array.isArray(applicability.useWhen) && applicability.useWhen.length) {
        lines.push(`适合：${applicability.useWhen.join("；")}`);
      }
      if (Array.isArray(applicability.notFor) && applicability.notFor.length) {
        lines.push(`不适合：${applicability.notFor.join("；")}`);
      }
      return lines.filter(Boolean).join("\n");
    },
    documentSearchText(document) {
      const fields = [
        document?.docId,
        document?.title,
        document?.source,
        document?.category || "未分类",
        document?.date,
        document?.documentType,
        document?.fileName,
        document?.version,
        ...(document?.tags || [])
      ];
      return fields.map((field) => String(field || "").toLowerCase()).join(" ");
    },
    agentBadge(agent) {
      return String(agent?.name || agent?.id || "A").slice(0, 1).toUpperCase();
    },
    previewList(values, limit) {
      return Array.isArray(values) ? values.slice(0, limit) : [];
    },
    toolCountLabel(agent) {
      if (!agent?.resolvedToolCount) {
        return "0 个";
      }
      return `${agent.resolvedToolCount} 个`;
    },
    documentCountLabel(agent) {
      const count = Number(agent?.boundDocumentCount ?? (agent?.boundDocumentIds || []).length ?? 0);
      return `${Number.isFinite(count) ? count : 0} 个`;
    },
    agentRuntimeEnvironmentLabel(agent) {
      return normalizeRuntimeEnvironment(agent?.workflowConfig?.runtimeEnvironment) || "跟随资产";
    },
    agentSearchText(agent) {
      const fields = [
        agent?.id,
        agent?.name,
        agent?.description,
        agent?.status,
        agent?.marketStatus,
        agent?.marketStatusLabel,
        agent?.defaultAgent ? "default agent" : "",
        agent?.defaultMode,
        agent?.modelName,
        agent?.defaultDataAsset?.assetId,
        agent?.defaultDataAsset?.assetName,
        agent?.defaultDataAsset?.assetType,
        agent?.defaultDataAsset?.warehouseId,
        ...(agent?.usageScenarios || []),
        ...(agent?.skillTags || []),
        ...(agent?.quickQuestions || []),
        ...(agent?.preferredToolPrefixes || []),
        ...(agent?.boundMcpServiceIds || []),
        ...(agent?.boundMcpToolNames || []),
        ...(agent?.resolvedToolNames || [])
      ];
      return fields.map((field) => String(field || "").toLowerCase()).join(" ");
    },
    openCreateDialog() {
      this.dialogMode = "create";
      this.activeAgent = null;
      this.form = {
        ...emptyForm(),
        modelName: this.defaultModelName()
      };
      this.dialogError = "";
      this.dialogOpen = true;
    },
    resetAgentFilters() {
      this.agentCategoryFilter = "all";
      this.agentStatusFilter = "all";
      this.agentModelFilter = "all";
      this.agentPage = 1;
    },
    normalizeAgentFilters() {
      if (!this.agentCategoryOptions.some((option) => option.value === this.agentCategoryFilter)) {
        this.agentCategoryFilter = "all";
      }
      if (!this.agentModelOptions.some((option) => option.value === this.agentModelFilter)) {
        this.agentModelFilter = "all";
      }
      this.clampAgentPage();
    },
    clampAgentPage() {
      if (this.agentPage > this.totalAgentPages) {
        this.agentPage = this.totalAgentPages;
      }
      if (this.agentPage < 1) {
        this.agentPage = 1;
      }
    },
    goAgentPage(page) {
      this.agentPage = Math.min(Math.max(1, Number(page) || 1), this.totalAgentPages);
      this.loadWorkshop();
    },
    openImportDialog() {
      this.importDialogOpen = true;
      this.importText = "";
      this.importFileName = "";
      this.importItems = [];
      this.importResults = [];
      this.importError = "";
      this.importOverwriteExisting = true;
    },
    closeImportDialog() {
      if (this.importing) {
        return;
      }
      this.importDialogOpen = false;
      this.importText = "";
      this.importFileName = "";
      this.importItems = [];
      this.importResults = [];
      this.importError = "";
    },
    openEditDialog(agent) {
      this.dialogMode = "edit";
      this.activeAgent = agent;
      this.form = this.agentToForm(agent);
      this.dialogError = "";
      this.dialogOpen = true;
    },
    closeDialog() {
      if (this.saving) {
        return;
      }
      this.dialogOpen = false;
      this.dialogError = "";
      this.activeAgent = null;
      this.form = emptyForm();
    },
    agentToForm(agent) {
      return {
        id: agent?.id || "",
        name: agent?.name || "",
        description: agent?.description || "",
        usageScenarios: joinList(agent?.usageScenarios),
        skillTags: joinList(agent?.skillTags),
        defaultMode: agent?.defaultMode || "",
        modelName: agent?.modelName || this.defaultModelName(),
        systemPrompt: agent?.systemPrompt || "",
        firstUseGreeting: agent?.firstUseGreeting || "",
        preferredToolPrefixes: joinList(agent?.preferredToolPrefixes),
        boundMcpServiceIds: joinList(agent?.boundMcpServiceIds),
        boundMcpToolNames: joinList(agent?.boundMcpToolNames),
        toolConfigs: Array.isArray(agent?.toolConfigs) ? agent.toolConfigs : [],
        routingSettings: {
          ...defaultRoutingSettings(),
          ...(agent?.routingSettings || {})
        },
        workflowConfig: this.normalizeWorkflowConfig(agent?.workflowConfig, parseList(agent?.boundMcpToolNames)),
        ...this.defaultDataAssetToForm(agent?.defaultDataAsset),
        ...this.assetSelectionPolicyToForm(agent?.assetSelectionPolicy),
        quickQuestions: joinList(agent?.quickQuestions),
        marketStatus: agent?.marketStatus || "draft",
        defaultAgent: !!agent?.defaultAgent
      };
    },
    formToPayload() {
      const registeredToolNames = new Set(this.registeredMcpTools.map((tool) => tool?.localToolName).filter(Boolean));
      const selectedToolNames = parseList(this.form.boundMcpToolNames)
        .filter((toolName) => registeredToolNames.has(toolName));
      const maxParallelCalls = Number(this.form.routingSettings.maxParallelCalls) || 3;
      const maxRelevantMcpTools = Number(this.form.routingSettings.maxRelevantMcpTools) || 3;
      const workflowConfig = this.normalizeWorkflowConfig(this.form.workflowConfig, selectedToolNames);
      const defaultDataAsset = this.defaultDataAssetFromForm();
      this.form.workflowConfig = workflowConfig;
      return {
        id: this.form.id,
        name: this.form.name,
        description: this.form.description,
        usageScenarios: parseList(this.form.usageScenarios),
        skillTags: parseList(this.form.skillTags),
        defaultMode: this.form.defaultMode,
        modelName: this.form.modelName || this.defaultModelName(),
        systemPrompt: this.form.systemPrompt,
        firstUseGreeting: this.form.firstUseGreeting,
        preferredToolPrefixes: [],
        boundMcpServiceIds: [],
        boundMcpToolNames: selectedToolNames,
        boundDocumentIds: [],
        boundDocumentTags: [],
        toolConfigs: this.buildToolConfigs(selectedToolNames),
        routingSettings: {
          smartSelectionEnabled: !!this.form.routingSettings.smartSelectionEnabled,
          limitParallelCalls: !!this.form.routingSettings.limitParallelCalls,
          maxParallelCalls: Math.max(1, Math.min(10, maxParallelCalls)),
          maxRelevantMcpTools: Math.max(1, Math.min(20, maxRelevantMcpTools))
        },
        workflowConfig,
        defaultDataAsset: defaultDataAsset.enabled ? defaultDataAsset : null,
        assetSelectionPolicy: this.assetSelectionPolicyFromForm(),
        quickQuestions: parseList(this.form.quickQuestions),
        marketStatus: this.form.marketStatus || "draft",
        defaultAgent: !!this.form.defaultAgent
      };
    },
    defaultDataAssetToForm(value) {
      const asset = this.normalizeDefaultDataAsset(value, false);
      return {
        defaultDataAssetEnabled: !!asset.enabled,
        defaultDataAssetName: asset.assetName
      };
    },
    assetSelectionPolicyToForm(value) {
      const policy = this.normalizeAssetSelectionPolicy(value);
      return {
        assetSelectionMinRelevanceScore: policy.minRelevanceScore,
        assetFallbackWhenEmpty: policy.fallbackWhenEmpty,
        assetFallbackWhenInvalid: policy.fallbackWhenInvalid
      };
    },
    defaultDataAssetFromForm() {
      return this.normalizeDefaultDataAsset({
        assetName: this.form.defaultDataAssetName,
        enabled: this.form.defaultDataAssetEnabled
      }, false);
    },
    assetSelectionPolicyFromForm() {
      return this.normalizeAssetSelectionPolicy({
        strategy: "BOUND_ASSET_ONLY",
        minRelevanceScore: 1,
        fallbackWhenEmpty: false,
        fallbackWhenInvalid: false
      });
    },
    normalizeDefaultDataAsset(value, enabledFallback = false) {
      const source = value && typeof value === "object" ? value : {};
      const assetId = textValue(source.assetId ?? source.id ?? source.asset_id);
      const assetName = textValue(source.assetName ?? source.name ?? source.asset_name);
      const warehouseId = textValue(source.warehouseId ?? source.warehouse_id ?? source.catalogId);
      return {
        assetId,
        assetName,
        assetType: "DATABASE",
        warehouseId,
        enabled: source.enabled === undefined ? enabledFallback : booleanValue(source.enabled)
      };
    },
    normalizeAssetSelectionPolicy(value) {
      return {
        strategy: "BOUND_ASSET_ONLY",
        minRelevanceScore: 1,
        fallbackWhenEmpty: false,
        fallbackWhenInvalid: false
      };
    },
    normalizeImportedAgent(row, index) {
      const id = normalizeAgentId(fieldValue(row, [
        "id", "agentId", "agent_id", "Agent ID", "AgentID", "智能体ID", "Agent编号", "编号"
      ]));
      const name = textValue(fieldValue(row, [
        "name", "agentName", "agent_name", "label", "Agent名称", "名称", "智能体名称"
      ]));
      const defaultMode = textValue(fieldValue(row, [
        "defaultMode", "mode", "默认模式", "模式"
      ]));
      const modelName = textValue(fieldValue(row, [
        "modelName", "model", "模型", "使用模型", "绑定模型"
      ]));
      const runtimeEnvironment = normalizeRuntimeEnvironment(fieldValue(row, [
        "runtimeEnvironment", "environment", "env", "运行环境", "环境"
      ]));
      const defaultAsset = this.normalizeDefaultDataAsset({
        assetId: fieldValue(row, [
          "defaultDataAssetId", "defaultAssetId", "assetId", "默认数据资产ID", "默认资产ID", "资产ID"
        ]),
        assetName: fieldValue(row, [
          "defaultDataAssetName", "defaultAssetName", "assetName", "默认数据资产", "默认数据资产名称", "默认资产名称", "资产名称"
        ]),
        assetType: fieldValue(row, [
          "defaultDataAssetType", "defaultAssetType", "assetType", "默认资产类型", "资产类型"
        ]),
        warehouseId: fieldValue(row, [
          "warehouseId", "defaultWarehouseId", "默认仓库ID", "仓库ID"
        ]),
        enabled: fieldValue(row, [
          "defaultDataAssetEnabled", "defaultAssetEnabled", "启用默认数据资产", "默认资产启用"
        ])
      });
      defaultAsset.enabled = !!(defaultAsset.assetId || defaultAsset.assetName)
        && (textValue(fieldValue(row, [
          "defaultDataAssetEnabled", "defaultAssetEnabled", "启用默认数据资产", "默认资产启用"
        ])) ? defaultAsset.enabled : true);
      const payload = {
        id,
        name,
        description: textValue(fieldValue(row, [
          "description", "businessDescription", "desc", "业务描述", "描述"
        ])),
        usageScenarios: listValue(fieldValue(row, [
          "usageScenarios", "businessScenarios", "scenarios", "业务场景", "使用场景", "场景"
        ])),
        skillTags: listValue(fieldValue(row, [
          "skillTags", "tags", "tag", "标签", "分类标签"
        ])),
        defaultMode: defaultMode && defaultMode !== "default" ? defaultMode : "agent_chat",
        modelName: modelName && modelName !== "default" ? modelName : this.defaultModelName(),
        systemPrompt: textValue(fieldValue(row, [
          "systemPrompt", "prompt", "system", "系统提示词", "提示词"
        ])),
        firstUseGreeting: textValue(fieldValue(row, [
          "firstUseGreeting", "welcomeMessage", "welcome", "greeting", "首次问候", "欢迎语"
        ])),
        preferredToolPrefixes: listValue(fieldValue(row, [
          "preferredToolPrefixes", "toolPrefixes", "工具前缀"
        ])),
        boundMcpServiceIds: listValue(fieldValue(row, [
          "boundMcpServiceIds", "mcpServiceIds", "serviceIds", "MCP服务", "绑定服务"
        ])),
        boundMcpToolNames: listValue(fieldValue(row, [
          "boundMcpToolNames", "mcpTools", "tools", "toolNames", "MCP工具", "绑定工具", "工具"
        ])),
        boundDocumentIds: [],
        boundDocumentTags: [],
        toolConfigs: Array.isArray(row?.toolConfigs) ? row.toolConfigs : [],
        routingSettings: {
          ...defaultRoutingSettings(),
          ...(row?.routingSettings && typeof row.routingSettings === "object" ? row.routingSettings : {})
        },
        workflowConfig: {
          ...defaultWorkflowConfig(),
          runtimeEnvironment
        },
        defaultDataAsset: defaultAsset.enabled ? defaultAsset : null,
        assetSelectionPolicy: {
          strategy: "BOUND_ASSET_ONLY",
          minRelevanceScore: 1,
          fallbackWhenEmpty: false,
          fallbackWhenInvalid: false
        },
        quickQuestions: listValue(fieldValue(row, [
          "quickQuestions", "questions", "quickPrompts", "快捷问题", "推荐问题", "示例问题"
        ])),
        marketStatus: textValue(fieldValue(row, [
          "marketStatus", "status", "发布状态", "状态"
        ])) || "draft",
        defaultAgent: booleanValue(fieldValue(row, [
          "defaultAgent", "default_agent", "isDefault", "default", "默认Agent", "是否默认"
        ]))
      };
      if (!payload.id || !/^[a-z0-9_-]{2,64}$/.test(payload.id)) {
        throw new Error(`第 ${index + 1} 行缺少有效 Agent ID，ID 需为 2-64 位小写字母、数字、下划线或短横线。`);
      }
      if (!payload.name) {
        throw new Error(`第 ${index + 1} 行缺少 Agent 名称。`);
      }
      return payload;
    },
    normalizeImportRows(rows) {
      return rows
        .filter((row) => row && typeof row === "object" && Object.values(row).some((value) => textValue(value)))
        .map((row, index) => this.normalizeImportedAgent(row, index));
    },
    parseImportTextContent(text) {
      const content = String(text || "").trim();
      if (!content) {
        return [];
      }
      if (content.startsWith("[") || content.startsWith("{")) {
        const parsed = JSON.parse(content);
        const rows = Array.isArray(parsed) ? parsed : (Array.isArray(parsed.agents) ? parsed.agents : [parsed]);
        return this.normalizeImportRows(rows);
      }
      const workbook = XLSX.read(content, { type: "string", raw: false });
      const firstSheet = workbook.Sheets[workbook.SheetNames[0]];
      return this.normalizeImportRows(XLSX.utils.sheet_to_json(firstSheet, { defval: "" }));
    },
    async handleImportFile(event) {
      const file = event.target.files?.[0];
      event.target.value = "";
      if (!file) {
        return;
      }
      this.importError = "";
      this.importResults = [];
      this.importFileName = file.name;
      try {
        const lowerName = file.name.toLowerCase();
        if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
          const buffer = await file.arrayBuffer();
          const workbook = XLSX.read(buffer, { type: "array", raw: false });
          const firstSheet = workbook.Sheets[workbook.SheetNames[0]];
          this.importItems = this.normalizeImportRows(XLSX.utils.sheet_to_json(firstSheet, { defval: "" }));
          this.importText = "";
          return;
        }
        this.importText = await file.text();
        this.refreshImportPreview();
      } catch (error) {
        this.importItems = [];
        this.importError = error.message || "文件解析失败";
      }
    },
    refreshImportPreview() {
      this.importError = "";
      this.importResults = [];
      try {
        this.importItems = this.parseImportTextContent(this.importText);
        if (!this.importItems.length) {
          this.importError = "没有解析到可导入的 Agent。";
        }
      } catch (error) {
        this.importItems = [];
        this.importError = error.message || "导入内容解析失败";
      }
    },
    async importAgents() {
      this.importError = "";
      if (!this.importItems.length) {
        this.refreshImportPreview();
      }
      if (!this.importItems.length) {
        return;
      }
      this.importing = true;
      this.importResults = [];
      const existingById = new Map(this.agents.map((agent) => [agent.id, agent]));
      try {
        for (const agent of this.importItems) {
          try {
            const existing = existingById.get(agent.id);
            if (existing?.builtin) {
              this.importResults.push({ id: agent.id, name: agent.name, status: "跳过内置Agent" });
              continue;
            }
            if (existing && !this.importOverwriteExisting) {
              this.importResults.push({ id: agent.id, name: agent.name, status: "已存在，已跳过" });
              continue;
            }
            if (existing) {
              await updateWorkshopAgent(agent.id, agent);
              this.importResults.push({ id: agent.id, name: agent.name, status: "已覆盖" });
            } else {
              await createWorkshopAgent(agent);
              existingById.set(agent.id, agent);
              this.importResults.push({ id: agent.id, name: agent.name, status: "已新增" });
            }
          } catch (error) {
            this.importResults.push({
              id: agent.id,
              name: agent.name,
              status: error.message || "导入失败"
            });
          }
        }
        await this.loadWorkshop();
      } catch (error) {
        this.importError = error.message || "批量导入失败";
      } finally {
        this.importing = false;
      }
    },
    isAgentSelectedForExport(agent) {
      return !!agent?.id && this.selectedAgentIds.includes(agent.id);
    },
    setAgentExportSelection(agent, selected) {
      const id = String(agent?.id || "").trim();
      if (!id) {
        return;
      }
      const ids = new Set(this.selectedAgentIds);
      const templates = { ...this.selectedAgentTemplates };
      const templateKey = `agent:${id}`;
      if (selected) {
        ids.add(id);
        templates[templateKey] = agent;
      } else {
        ids.delete(id);
        delete templates[templateKey];
      }
      this.selectedAgentIds = [...ids];
      this.selectedAgentTemplates = templates;
    },
    refreshSelectedAgentTemplates() {
      if (!this.selectedAgentIds.length) {
        return;
      }
      const templates = { ...this.selectedAgentTemplates };
      this.agents.forEach((agent) => {
        if (agent?.id && this.selectedAgentIds.includes(agent.id)) {
          templates[`agent:${agent.id}`] = agent;
        }
      });
      this.selectedAgentTemplates = templates;
    },
    clearAgentExportSelection() {
      this.selectedAgentIds = [];
      this.selectedAgentTemplates = {};
    },
    selectedAgentsForExport() {
      return this.selectedAgentIds
        .map((id) => this.selectedAgentTemplates[`agent:${id}`])
        .filter((agent) => agent?.id);
    },
    exportAgentRows() {
      return this.selectedAgentsForExport().map((agent) => ({
        agentId: agent.id,
        agentName: agent.name,
        mode: agent.defaultMode || "agent_chat",
        model: agent.modelName || this.defaultModelName() || "default",
        runtimeEnvironment: normalizeRuntimeEnvironment(agent.workflowConfig?.runtimeEnvironment),
        tags: agent.skillTags || [],
        businessDescription: agent.description || "",
        businessScenarios: agent.usageScenarios || [],
        systemPrompt: agent.systemPrompt || "",
        welcomeMessage: agent.firstUseGreeting || "",
        quickQuestions: agent.quickQuestions || [],
        boundMcpToolNames: agent.boundMcpToolNames || [],
        boundDocumentIds: agent.boundDocumentIds || [],
        boundDocumentTags: agent.boundDocumentTags || [],
        marketStatus: agent.marketStatus || "draft",
        defaultAgent: !!agent.defaultAgent
      }));
    },
    exportAgentsAsJson() {
      const rows = this.exportAgentRows();
      if (!rows.length) {
        this.error = "请先勾选需要导出的 Agent 模板。";
        return;
      }
      this.downloadFile(
        "agent-workshop-export.json",
        JSON.stringify(rows, null, 2),
        "application/json;charset=utf-8"
      );
    },
    exportAgentsAsTable() {
      const rows = this.exportAgentRows().map((agent) => ({
        ...agent,
        tags: joinList(agent.tags),
        businessScenarios: joinList(agent.businessScenarios),
        quickQuestions: joinList(agent.quickQuestions),
        boundMcpToolNames: joinList(agent.boundMcpToolNames),
        boundDocumentIds: joinList(agent.boundDocumentIds),
        boundDocumentTags: joinList(agent.boundDocumentTags)
      }));
      if (!rows.length) {
        this.error = "请先勾选需要导出的 Agent 模板。";
        return;
      }
      const workbook = XLSX.utils.book_new();
      const worksheet = XLSX.utils.json_to_sheet(rows);
      XLSX.utils.book_append_sheet(workbook, worksheet, "Agents");
      XLSX.writeFile(workbook, "agent-workshop-export.xlsx");
    },
    downloadFile(fileName, content, mimeType) {
      const blob = new Blob([content], { type: mimeType });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = fileName;
      link.click();
      URL.revokeObjectURL(url);
    },
    registeredMcpTool(toolName) {
      return this.registeredMcpTools.find((tool) => tool?.localToolName === toolName) || {};
    },
    ensureToolConfig(toolName) {
      if (!toolName) {
        return null;
      }
      if (!Array.isArray(this.form.toolConfigs)) {
        this.form.toolConfigs = [];
      }
      let config = this.form.toolConfigs.find((item) => item?.toolName === toolName);
      if (config) {
        return config;
      }
      const registered = this.registeredMcpTool(toolName);
      config = {
        toolName,
        displayName: registered.remoteToolName || registered.displayName || toolName,
        serviceId: registered.serviceId || "",
        description: registered.description || "",
        callWeight: 5,
        enabled: true
      };
      this.form.toolConfigs.push(config);
      return config;
    },
    workflowToolDescription(toolName) {
      const existing = Array.isArray(this.form.toolConfigs)
        ? this.form.toolConfigs.find((config) => config?.toolName === toolName)
        : null;
      if (existing && Object.prototype.hasOwnProperty.call(existing, "description")) {
        return existing.description || "";
      }
      return this.registeredMcpTool(toolName).description || "";
    },
    setWorkflowToolDescription(toolName, value) {
      const config = this.ensureToolConfig(toolName);
      if (!config) {
        return;
      }
      config.description = String(value || "");
    },
    buildToolConfigs(selectedToolNames) {
      const existingConfigs = Array.isArray(this.form.toolConfigs) ? this.form.toolConfigs : [];
      const selected = new Set(selectedToolNames);
      const byName = new Map(existingConfigs.filter((config) => config?.toolName).map((config) => [config.toolName, config]));
      return selectedToolNames.map((toolName) => {
        const existing = byName.get(toolName) || {};
        const registered = this.registeredMcpTool(toolName);
        return {
          ...existing,
          toolName,
          displayName: existing.displayName || registered.remoteToolName || registered.displayName || toolName,
          serviceId: existing.serviceId || registered.serviceId || "",
          description: Object.prototype.hasOwnProperty.call(existing, "description")
            ? (existing.description || "")
            : (registered.description || ""),
          callWeight: existing.callWeight ?? 5,
          enabled: selected.has(toolName)
        };
      });
    },
    normalizeWorkflowConfig(config, selectedToolNames = this.selectedToolNames) {
      const selected = uniqueList(selectedToolNames);
      const base = {
        ...defaultWorkflowConfig(),
        ...(config && typeof config === "object" ? config : {})
      };
      base.executionStrategy = {
        ...defaultWorkflowConfig().executionStrategy,
        ...(base.executionStrategy || {})
      };
      base.executionStrategy.maxSteps = Math.max(0, Math.min(50, Number(base.executionStrategy.maxSteps) || selected.length || 6));
      const existingSteps = Array.isArray(base.steps) ? base.steps : [];
      const byTool = new Map(existingSteps
        .filter((step) => step?.tool || step?.toolName)
        .map((step) => [String(step.tool || step.toolName), step]));
      const selectedSet = new Set(selected);
      const steps = selected.map((toolName, index) => {
        const existing = byTool.get(toolName) || {};
        const confirmation = existing.confirmation && String(existing.confirmation).trim()
          ? String(existing.confirmation).trim()
          : "auto_execute";
        return {
          step: index + 1,
          tool: toolName,
          required: existing.required !== false,
          condition: existing.condition || "",
          confirmation,
          dependsOn: uniqueList(Array.isArray(existing.dependsOn) ? existing.dependsOn : [])
            .filter((dependency) => selectedSet.has(dependency) && dependency !== toolName)
        };
      });
      const toolDependencies = {};
      steps.forEach((step) => {
        if (step.dependsOn.length) {
          toolDependencies[step.tool] = { dependsOn: step.dependsOn };
        }
      });
      const parallelSteps = uniqueList(base.parallelSteps || []).filter((toolName) => selectedSet.has(toolName));
      return {
        enabled: base.enabled !== false,
        runtimeEnvironment: normalizeRuntimeEnvironment(base.runtimeEnvironment),
        workflow: base.workflow || (this.form?.id ? `${this.form.id}_workflow` : "agent_workflow"),
        executionStrategy: base.executionStrategy,
        steps,
        toolDependencies,
        parallelSteps
      };
    },
    syncWorkflowSteps(selectedToolNames = this.selectedToolNames) {
      this.form.workflowConfig = this.normalizeWorkflowConfig(this.form.workflowConfig, selectedToolNames);
    },
    moveWorkflowStep(index, delta) {
      const steps = [...this.workflowSteps];
      const nextIndex = index + delta;
      if (nextIndex < 0 || nextIndex >= steps.length) {
        return;
      }
      const [step] = steps.splice(index, 1);
      steps.splice(nextIndex, 0, step);
      this.form.workflowConfig.steps = steps.map((item, order) => ({ ...item, step: order + 1 }));
      this.form.boundMcpToolNames = this.form.workflowConfig.steps.map((item) => item.tool).join("\n");
      this.syncWorkflowSteps();
    },
    workflowStepDependencies(step) {
      return Array.isArray(step?.dependsOn) ? step.dependsOn : [];
    },
    availableWorkflowDependencies(step) {
      const selected = new Set(this.workflowStepDependencies(step));
      return this.selectedToolNames.filter((toolName) => toolName !== step?.tool && !selected.has(toolName));
    },
    addWorkflowDependency(step, toolName) {
      if (!step || !toolName || toolName === step.tool) {
        return;
      }
      step.dependsOn = uniqueList([...this.workflowStepDependencies(step), toolName]);
      this.syncWorkflowSteps();
    },
    removeWorkflowDependency(step, toolName) {
      if (!step || !toolName) {
        return;
      }
      step.dependsOn = this.workflowStepDependencies(step).filter((value) => value !== toolName);
      this.syncWorkflowSteps();
    },
    setWorkflowDependencies(step, selectedOptions) {
      if (!step) {
        return;
      }
      const values = Array.from(selectedOptions || [])
        .map((option) => option.value)
        .filter((toolName) => toolName && toolName !== step.tool);
      step.dependsOn = uniqueList(values);
      this.syncWorkflowSteps();
    },
    async saveAgent() {
      this.dialogError = "";
      if (!this.form.id || !/^[a-z0-9_-]{2,64}$/.test(this.form.id)) {
        this.dialogError = "Agent ID 仅支持小写字母、数字、下划线和短横线，长度 2-64。";
        return;
      }
      if (!this.form.name) {
        this.dialogError = "请填写Agent名称。";
        return;
      }

      this.saving = true;
      try {
        const payload = this.formToPayload();
        const saved = this.dialogMode === "create"
          ? await createWorkshopAgent(payload)
          : await updateWorkshopAgent(this.activeAgent.id, payload);
        const requestedEnvironment = normalizeRuntimeEnvironment(payload.workflowConfig?.runtimeEnvironment);
        const persistedEnvironment = normalizeRuntimeEnvironment(saved?.workflowConfig?.runtimeEnvironment);
        if (requestedEnvironment !== persistedEnvironment) {
          throw new Error(
            `Agent 保存后环境回读不一致：提交 ${requestedEnvironment || "未指定"}，返回 ${persistedEnvironment || "未指定"}`
          );
        }
        this.closeAfterSave();
        await this.loadWorkshop();
      } catch (error) {
        this.dialogError = error.message || "Agent保存失败";
      } finally {
        this.saving = false;
      }
    },
    closeAfterSave() {
      this.dialogOpen = false;
      this.dialogError = "";
      this.activeAgent = null;
      this.form = emptyForm();
    },
    async removeAgent(agent) {
      if (!agent?.id || agent.builtin || agent.defaultAgent) {
        return;
      }
      if (!window.confirm(`确认删除Agent「${agent.name || agent.id}」？`)) {
        return;
      }
      this.saving = true;
      this.error = "";
      try {
        await deleteWorkshopAgent(agent.id);
        this.setAgentExportSelection(agent, false);
        await this.loadWorkshop();
      } catch (error) {
        this.error = error.message || "Agent删除失败";
      } finally {
        this.saving = false;
      }
    },
    async setDefaultAgent(agent) {
      if (!agent?.id || agent.defaultAgent) {
        return;
      }
      this.saving = true;
      this.error = "";
      try {
        await setDefaultWorkshopAgent(agent.id);
        await this.loadWorkshop();
      } catch (error) {
        this.error = error.message || "默认Agent设置失败";
      } finally {
        this.saving = false;
      }
    },
    async publishAgent(agent) {
      if (!agent?.id || agent.marketStatus === "published") {
        return;
      }
      this.saving = true;
      this.error = "";
      try {
        await publishWorkshopAgent(agent.id);
        await this.loadWorkshop();
      } catch (error) {
        this.error = error.message || "能力发布失败";
      } finally {
        this.saving = false;
      }
    },
    async recallAgent(agent) {
      if (!agent?.id || agent.marketStatus !== "published") {
        return;
      }
      if (!window.confirm(`确认从能力市场回收「${agent.name || agent.id}」？`)) {
        return;
      }
      this.saving = true;
      this.error = "";
      try {
        await recallWorkshopAgent(agent.id);
        await this.loadWorkshop();
      } catch (error) {
        this.error = error.message || "能力回收失败";
      } finally {
        this.saving = false;
      }
    },
    toggleTool(toolName) {
      const selected = new Set(this.selectedToolNames);
      if (selected.has(toolName)) {
        selected.delete(toolName);
      } else {
        selected.add(toolName);
      }
      this.form.boundMcpToolNames = [...selected].sort().join("\n");
      this.syncWorkflowSteps([...selected].sort());
    },
    clearSelectedTools() {
      this.form.boundMcpToolNames = "";
      this.syncWorkflowSteps([]);
    },
    isToolGroupFullySelected(group) {
      return group?.tools?.length && group.tools.every((tool) => this.selectedToolNames.includes(tool.localToolName));
    },
    toggleToolGroup(group) {
      if (!group?.tools?.length) {
        return;
      }
      const selected = new Set(this.selectedToolNames);
      const allSelected = group.tools.every((tool) => selected.has(tool.localToolName));
      group.tools.forEach((tool) => {
        if (allSelected) {
          selected.delete(tool.localToolName);
        } else {
          selected.add(tool.localToolName);
        }
      });
      this.form.boundMcpToolNames = [...selected].sort().join("\n");
      this.syncWorkflowSteps([...selected].sort());
    },
    defaultModelName() {
      return this.models.find((model) => model?.value)?.value || "";
    }
  }
};
