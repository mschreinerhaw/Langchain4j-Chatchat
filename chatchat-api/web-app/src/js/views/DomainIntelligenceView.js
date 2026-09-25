import {
  fetchDomainIntelligenceProviders,
  fetchDomainSkillResources,
  fetchMcpRegisteredTools,
  fetchSkills,
  runDomainIntelligenceAnalysis
} from "../../services/api.js";
import "../../styles/pages/domain-intelligence.css";

export default {
  name: "DomainIntelligenceView",
  data() {
    return {
      loading: false, running: false, error: "", result: null, skillSearch: "",
      providers: [], skills: [], toolCatalog: [], authorizedDocuments: [], advancedOpen: false,
      form: {
        providerId: "", capability: "", skillId: "", query: "",
        documentIds: [], documentTags: [], selectedTools: [], toolArguments: {},
        dataTemplateId: "", dataAssetName: "", dataEnvironment: "", dataParameters: "{}",
        confirmRemoteTransfer: false
      }
    };
  },
  computed: {
    selectedProvider() { return this.providers.find((item) => item.providerId === this.form.providerId) || null; },
    selectedSkill() { return this.skills.find((item) => item.value === this.form.skillId) || null; },
    availableTools() {
      const skill = this.selectedSkill;
      const configured = (skill?.toolConfigs || []).filter((item) => item?.enabled !== false)
        .map((item) => item.toolName);
      return [...new Set([...(skill?.boundMcpToolNames || []), ...configured].filter(Boolean))];
    },
    availableDocuments() { return this.authorizedDocuments; }
  },
  mounted() { this.loadOptions(); },
  methods: {
    async loadOptions() {
      this.loading = true;
      this.error = "";
      try {
        const [providers, page, tools] = await Promise.all([
          fetchDomainIntelligenceProviders(), fetchSkills({ scope: "published", keyword: this.skillSearch.trim(), pageSize: 100 }),
          fetchMcpRegisteredTools().catch(() => [])
        ]);
        this.providers = Array.isArray(providers) ? providers : [];
        const currentSkill = this.selectedSkill;
        const found = Array.isArray(page?.items) ? page.items : [];
        this.skills = currentSkill && !found.some((item) => item.value === currentSkill.value)
          ? [currentSkill, ...found] : found;
        this.toolCatalog = Array.isArray(tools) ? tools : [];
      } catch (error) { this.error = error.message || "可用能力加载失败"; }
      finally { this.loading = false; }
    },
    changeProvider() {
      this.form.capability = this.selectedProvider?.capabilities?.[0] || "";
      this.form.documentIds = [];
      this.form.documentTags = [];
      this.form.selectedTools = [];
      this.result = null;
    },
    async changeSkill() {
      this.form.documentIds = [];
      this.form.documentTags = [];
      this.form.selectedTools = [];
      this.form.toolArguments = {};
      this.authorizedDocuments = [];
      this.result = null;
      if (!this.selectedSkill) return;
      const selectedSkill = this.selectedSkill;
      try {
        const documents = await fetchDomainSkillResources({ skillId: selectedSkill.value,
          documentIds: selectedSkill.boundDocumentIds || [],
          documentTags: selectedSkill.boundDocumentTags || [] });
        if (this.form.skillId === selectedSkill.value)
          this.authorizedDocuments = Array.isArray(documents) ? documents : [];
      } catch (error) {
        if (this.form.skillId === selectedSkill.value)
          this.error = error.message || "该 Skill 的文档权限加载失败";
      }
    },
    toolLabel(name) {
      const tool = this.toolCatalog.find((item) => item.localToolName === name);
      return tool?.chineseAlias || tool?.displayName || name;
    },
    acceptsEvidence(type) {
      return Array.isArray(this.selectedProvider?.evidenceTypes)
        && this.selectedProvider.evidenceTypes.includes(type);
    },
    buildRequest() {
      if (!this.form.query.trim() || !this.form.providerId || !this.form.capability || !this.form.skillId)
        throw new Error("请先填写分析要求并选择专有算力和知识 Skill");
      if (!this.form.confirmRemoteTransfer)
        throw new Error("请确认将本次选中的证据发送给所选专有模型");
      const accepted = this.selectedProvider?.evidenceTypes || [];
      if (this.form.documentIds.length
        && !accepted.includes("DocumentAnalysisEvidence"))
        throw new Error("所选专有模型尚未获准接收文档证据");
      if (this.form.selectedTools.length && !accepted.includes("ToolAnalysisEvidence"))
        throw new Error("所选专有模型尚未获准接收 MCP 工具证据");
      if (this.form.dataTemplateId.trim() && !accepted.includes("StructuredDataEvidence"))
        throw new Error("所选专有模型尚未获准接收结构化数据证据");
      if (this.form.selectedTools.length > 4) throw new Error("每次最多编排 4 个 MCP 工具");
      const tools = this.form.selectedTools.map((toolName) => {
        let argumentsValue;
        try { argumentsValue = JSON.parse(this.form.toolArguments[toolName] || "{}"); }
        catch { throw new Error(`${toolName} 的参数不是有效 JSON`); }
        if (!argumentsValue || Array.isArray(argumentsValue) || typeof argumentsValue !== "object")
          throw new Error(`${toolName} 的参数必须是 JSON 对象`);
        return { toolName, arguments: argumentsValue };
      });
      let dataParameters = {};
      if (this.form.dataTemplateId.trim()) {
        if (!this.form.dataAssetName.trim() || !this.form.dataEnvironment)
          throw new Error("使用数据模板时，请填写资产名称和环境");
        try { dataParameters = JSON.parse(this.form.dataParameters || "{}"); }
        catch { throw new Error("数据模板参数不是有效 JSON"); }
        if (!dataParameters || Array.isArray(dataParameters) || typeof dataParameters !== "object")
          throw new Error("数据模板参数必须是 JSON 对象");
      }
      if (!this.form.documentIds.length && !tools.length
        && !this.form.dataTemplateId.trim())
        throw new Error("请至少选择一项文档知识或数据能力");
      return {
        query: this.form.query.trim(), providerId: this.form.providerId,
        capability: this.form.capability, skillId: this.form.skillId,
        documentIds: this.form.documentIds, documentTags: [],
        tools, dataTemplateId: this.form.dataTemplateId.trim(),
        dataAssetName: this.form.dataAssetName.trim(), dataEnvironment: this.form.dataEnvironment,
        dataParameters, confirmRemoteTransfer: true
      };
    },
    async run() {
      this.error = "";
      this.result = null;
      let request;
      try { request = this.buildRequest(); }
      catch (error) { this.error = error.message; return; }
      this.running = true;
      try { this.result = await runDomainIntelligenceAnalysis(request); }
      catch (error) { this.error = error.message || "分析执行失败"; }
      finally { this.running = false; }
    }
  }
};
