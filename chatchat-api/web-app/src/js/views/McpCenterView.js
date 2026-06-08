import { DownloadCloud, RefreshCw } from "@lucide/vue";
import {
  fetchMcpCenterStatus,
  fetchMcpRegisteredTools,
  fetchMcpServices,
  fetchMcpToolCards,
  syncMcpCenter
} from "../../services/api";
import "../../styles/pages/skill-hub.css";

export default {
  name: "McpCenterView",
  components: {
    DownloadCloud,
    RefreshCw
  },
  data() {
    return {
      centerStatus: null,
      services: [],
      tools: [],
      toolCards: [],
      toolTotal: 0,
      mcpToolTotal: 0,
      toolPageTotal: 1,
      filteredToolGroupCountValue: 0,
      toolServiceOptionsData: [],
      toolSearchQuery: "",
      toolServiceFilter: "all",
      toolGroupMode: "service",
      activeTool: null,
      toolPage: 1,
      toolPageSize: 6,
      loading: false,
      syncing: false,
      error: "",
      syncMessage: ""
    };
  },
  computed: {
    enabledServices() {
      return this.services.filter((service) => service.enabled);
    },
    disabledServices() {
      return this.services.filter((service) => !service.enabled);
    },
    centerEndpoint() {
      return this.centerStatus?.standaloneMcpEndpoint || "-";
    },
    syncedMcpToolCards() {
      return this.toolCards;
    },
    toolServiceOptions() {
      return this.toolServiceOptionsData.length
        ? this.toolServiceOptionsData
        : [{ value: "all", label: "全部服务", count: this.mcpToolTotal }];
    },
    toolGroupOptions() {
      return [
        { value: "service", label: "按服务" },
        { value: "category", label: "按分类" },
        { value: "tag", label: "按标签" }
      ];
    },
    filteredToolCards() {
      return this.toolCards;
    },
    filteredToolGroupCount() {
      return this.filteredToolGroupCountValue;
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
    pagedToolCards() {
      return this.filteredToolCards;
    },
    pagedToolGroups() {
      const groups = new Map();
      this.pagedToolCards.forEach((tool) => {
        const group = this.resolveToolGroup(tool);
        if (!groups.has(group.key)) {
          groups.set(group.key, {
            ...group,
            tools: []
          });
        }
        groups.get(group.key).tools.push(tool);
      });
      return [...groups.values()].sort((left, right) => left.label.localeCompare(right.label));
    },
    toolFilterSummary() {
      if (!this.syncedMcpToolCards.length) {
        return "暂无已注册工具";
      }
      return `${this.toolTotal} / ${this.mcpToolTotal} 个 · ${this.filteredToolGroupCount} 个${this.toolGroupModeLabel}分组 · 每页 ${this.toolPageSize} 条`;
    },
    toolPageCount() {
      return this.toolPageTotal;
    },
    toolPageStart() {
      if (!this.filteredToolCards.length) {
        return 0;
      }
      return (this.toolPage - 1) * this.toolPageSize + 1;
    },
    toolPageEnd() {
      return Math.min(this.toolPage * this.toolPageSize, this.toolTotal);
    },
    activeToolSchemaText() {
      if (!this.activeTool?.inputSchema || !Object.keys(this.activeTool.inputSchema).length) {
        return "";
      }
      return JSON.stringify(this.activeTool.inputSchema, null, 2);
    }
  },
  mounted() {
    this.loadMcpCenter();
  },
  watch: {
    toolSearchQuery() {
      this.toolPage = 1;
      this.loadToolCards();
    },
    toolServiceFilter() {
      this.toolPage = 1;
      this.loadToolCards();
    },
    toolGroupMode() {
      this.toolPage = 1;
      this.loadToolCards();
    }
  },
  methods: {
    async loadMcpCenter() {
      this.loading = true;
      this.error = "";
      try {
        const [centerStatus, services, tools, toolCards] = await Promise.all([
          fetchMcpCenterStatus(),
          fetchMcpServices(),
          fetchMcpRegisteredTools(),
          fetchMcpToolCards(this.toolCardQuery())
        ]);
        this.centerStatus = centerStatus;
        this.services = Array.isArray(services) ? services : [];
        this.tools = Array.isArray(tools) ? tools : [];
        this.applyToolPage(toolCards);
      } catch (error) {
        this.error = error.message || "MCP 数据加载失败";
      } finally {
        this.loading = false;
      }
    },
    async syncCenter() {
      this.syncing = true;
      this.error = "";
      this.syncMessage = "";
      try {
        const result = await syncMcpCenter();
        const imported = result?.importedCount || 0;
        const errors = Array.isArray(result?.errors) ? result.errors : [];
        this.syncMessage = errors.length
          ? `已同步 ${imported} 个服务，${errors.length} 个服务失败`
          : `已同步 ${imported} 个服务`;
        await this.loadMcpCenter();
      } catch (error) {
        this.error = error.message || "MCP 中心同步失败";
      } finally {
        this.syncing = false;
      }
    },
    async loadToolCards() {
      try {
        this.applyToolPage(await fetchMcpToolCards(this.toolCardQuery()));
      } catch (error) {
        this.error = error.message || "MCP 工具加载失败";
      }
    },
    toolCardQuery() {
      return {
        keyword: this.toolSearchQuery.trim(),
        service: this.toolServiceFilter,
        sourceType: "mcp",
        groupMode: this.toolGroupMode,
        page: this.toolPage,
        pageSize: this.toolPageSize
      };
    },
    applyToolPage(payload) {
      if (Array.isArray(payload)) {
        this.toolCards = payload.filter((tool) => tool?.sourceType === "mcp");
        this.toolTotal = this.toolCards.length;
        this.mcpToolTotal = this.toolCards.length;
        this.toolPageTotal = Math.max(1, Math.ceil(this.toolTotal / this.toolPageSize));
        this.filteredToolGroupCountValue = new Set(this.toolCards.map((tool) => this.resolveToolGroup(tool).key)).size;
        this.toolServiceOptionsData = [];
        return;
      }
      this.toolCards = Array.isArray(payload?.tools) ? payload.tools : [];
      this.toolTotal = payload?.total || 0;
      this.toolPage = payload?.page || this.toolPage;
      this.toolPageSize = payload?.pageSize || this.toolPageSize;
      this.toolPageTotal = payload?.totalPages || 1;
      this.filteredToolGroupCountValue = payload?.filteredGroupCount || 0;
      this.toolServiceOptionsData = Array.isArray(payload?.serviceOptions) ? payload.serviceOptions : [];
      this.mcpToolTotal = this.toolServiceOptionsData.find((option) => option.value === "all")?.count || this.toolTotal;
    },
    serviceBadge(service) {
      return (service.name || "M").slice(0, 1).toUpperCase();
    },
    serviceStatusLabel(service) {
      return service.enabled ? "已启用" : "已停用";
    },
    protocolLabel(service) {
      const protocol = service.protocol || "legacy_http";
      if (protocol === "mcp_streamable_http") {
        return "Streamable HTTP";
      }
      if (protocol === "mcp_stdio_proxy") {
        return "Stdio Proxy";
      }
      if (protocol === "mcp_legacy_sse") {
        return "Legacy SSE";
      }
      return protocol;
    },
    serviceToolCount(service) {
      const cardCount = this.syncedMcpToolCards.filter((tool) => tool.serviceId === service.id).length;
      if (cardCount > 0) {
        return cardCount;
      }
      return this.tools.filter((tool) => tool.serviceId === service.id).length;
    },
    resolveToolGroup(tool) {
      if (this.toolGroupMode === "category") {
        const category = Array.isArray(tool?.categories) && tool.categories.length ? tool.categories[0] : "未分类";
        return {
          key: `category:${category}`,
          label: category,
          subtitle: "分类"
        };
      }
      if (this.toolGroupMode === "tag") {
        const tag = Array.isArray(tool?.tags) && tool.tags.length ? tool.tags[0] : "未打标签";
        return {
          key: `tag:${tag}`,
          label: tag,
          subtitle: "标签"
        };
      }
      const serviceName = tool?.serviceName || tool?.serviceId || "未归属服务";
      return {
        key: `service:${tool?.serviceId || serviceName}`,
        label: serviceName,
        subtitle: tool?.serviceId && tool?.serviceName ? tool.serviceId : "服务"
      };
    },
    toolSearchText(tool) {
      const fields = [
        tool?.localToolName,
        tool?.displayName,
        tool?.description,
        tool?.serviceId,
        tool?.serviceName,
        tool?.remoteToolName,
        tool?.outputType,
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
    toolBadge(tool) {
      return String(tool?.displayName || tool?.localToolName || "M").slice(0, 1).toUpperCase();
    },
    toolMetaLabel(tool) {
      const labels = [];
      if (tool?.agentCompatible) {
        labels.push("Agent可用");
      }
      if (tool?.requiresAuth) {
        labels.push("需授权");
      }
      if (tool?.rateLimited) {
        labels.push("限流");
      }
      return labels.join(" / ");
    },
    toolDetailRows(tool) {
      return [
        ["本地工具名", tool?.localToolName || "-"],
        ["远端工具名", tool?.remoteToolName || "-"],
        ["所属服务", tool?.serviceName || "-"],
        ["服务ID", tool?.serviceId || "-"],
        ["输出类型", tool?.outputType || "-"],
        ["超时", tool?.timeoutMillis ? `${tool.timeoutMillis}ms` : "-"],
        ["Agent可用", tool?.agentCompatible ? "是" : "否"],
        ["需要授权", tool?.requiresAuth ? "是" : "否"],
        ["限流", tool?.rateLimited ? "是" : "否"]
      ];
    },
    toolSchemaSummary(tool) {
      const schema = tool?.inputSchema;
      if (!schema || typeof schema !== "object" || !Object.keys(schema).length) {
        return "";
      }
      const properties = schema.properties && typeof schema.properties === "object"
        ? Object.keys(schema.properties)
        : [];
      const required = Array.isArray(schema.required) ? schema.required : [];
      if (!properties.length && !required.length) {
        return "已同步原始入参结构";
      }
      const propertyText = properties.length ? `${properties.length} 个字段` : "无字段声明";
      const requiredText = required.length ? `${required.length} 个必填` : "无必填字段";
      return `${propertyText}，${requiredText}`;
    },
    openToolDetail(tool) {
      this.activeTool = tool || null;
    },
    closeToolDetail() {
      this.activeTool = null;
    },
    previewList(values, limit) {
      return Array.isArray(values) ? values.slice(0, limit) : [];
    },
    setToolPage(page) {
      const target = Number(page) || 1;
      this.toolPage = Math.max(1, Math.min(this.toolPageCount, target));
      this.loadToolCards();
    },
    nextToolPage() {
      this.setToolPage(this.toolPage + 1);
    },
    previousToolPage() {
      this.setToolPage(this.toolPage - 1);
    },
    clampToolPage() {
      if (this.toolPage > this.toolPageCount) {
        this.toolPage = this.toolPageCount;
      }
      if (this.toolPage < 1) {
        this.toolPage = 1;
      }
    },
    formatTime(value) {
      if (!value) {
        return "-";
      }
      return new Date(value).toLocaleString();
    }
  }
};
