import { fetchIntelligenceProviders, fetchDomainSkillResources, fetchMcpRegisteredTools,
  fetchSkills, runDomainIntelligenceAnalysis } from "../../services/api.js";
import "../../styles/pages/domain-intelligence.css";

export default {
  name: "DomainIntelligenceView",
  data() {
    return {
      loading: false, running: false, error: "", result: null, skillSearch: "",
      providers: [], skills: [], toolCatalog: [], documentsBySkill: {}, advancedOpen: false,
      form: {
        providerId: "", capability: "", skillId: "", skillIds: [], query: "",
        documentIds: [], documentsBySkill: {}, documentTags: [], selectedTools: [], toolArguments: {},
        dataSkillId: "", dataTemplateId: "", dataAssetName: "", dataEnvironment: "",
        dataParameters: "{}", confirmRemoteTransfer: false
      }
    };
  },
  computed: {
    selectedProvider() { return this.providers.find((item) => item.providerId === this.form.providerId) || null; },
    selectableSkills() {
      const provider = this.selectedProvider;
      return provider?.grantRestricted
        ? this.skills.filter((item) => provider.skillIds?.includes(item.value)) : this.skills;
    },
    providerEvidenceSummary() {
      const types = this.selectedProvider?.evidenceTypes || [];
      return [types.includes("DocumentAnalysisEvidence") && "文档知识",
        types.includes("ToolAnalysisEvidence") && "业务数据 / MCP 工具",
        types.includes("StructuredDataEvidence") && "只读数据模板"].filter(Boolean).join("、") || "暂无";
    },
    selectedSkills() { return this.skills.filter((item) => this.form.skillIds.includes(item.value)); },
    availableTools() {
      return this.selectedSkills.flatMap((skill) => {
        const configured = (skill.toolConfigs || []).filter((item) => item?.enabled !== false)
          .map((item) => item.toolName);
        return [...new Set([...(skill.boundMcpToolNames || []), ...configured].filter(Boolean))]
          .filter((name) => !this.selectedProvider?.grantRestricted || this.selectedProvider?.mcpRoleGoverned
            || this.selectedProvider.mcpToolNames?.includes(name))
          .map((toolName) => ({ skillId: skill.value, toolName, key: `${skill.value}::${toolName}` }));
      });
    }
  },
  mounted() { this.loadOptions(); },
  methods: {
    async loadOptions() {
      this.loading = true;
      this.error = "";
      try {
        const [providers, page, tools] = await Promise.all([
          fetchIntelligenceProviders(),
          fetchSkills({ scope: "published", keyword: this.skillSearch.trim(), pageSize: 100 }),
          fetchMcpRegisteredTools().catch(() => [])
        ]);
        this.providers = Array.isArray(providers) ? providers : [];
        const found = Array.isArray(page?.items) ? page.items : [];
        const selected = this.selectedSkills.filter((item) => !found.some((next) => next.value === item.value));
        this.skills = [...selected, ...found];
        this.toolCatalog = Array.isArray(tools) ? tools : [];
      } catch (error) { this.error = error.message || "可用能力加载失败"; }
      finally { this.loading = false; }
    },
    changeProvider() {
      this.form.capability = this.selectedProvider?.capabilities?.[0] || "";
      this.form.skillIds = this.form.skillIds.filter((id) => !this.selectedProvider?.grantRestricted
        || this.selectedProvider.skillIds?.includes(id));
      this.form.skillId = this.form.skillIds[0] || "";
      this.form.selectedTools = [];
      this.form.documentsBySkill = {};
      this.documentsBySkill = {};
      this.result = null;
      if (this.form.skillIds.length) this.changeSkills();
    },
    async changeSkills() {
      this.form.skillIds = this.form.skillIds.slice(0, 4);
      for (const id of this.form.skillIds)
        if (!Array.isArray(this.form.documentsBySkill[id])) this.form.documentsBySkill[id] = [];
      this.form.skillId = this.form.skillIds[0] || "";
      this.form.dataSkillId = this.form.skillIds.includes(this.form.dataSkillId)
        ? this.form.dataSkillId : this.form.skillId;
      this.form.selectedTools = this.form.selectedTools.filter((key) =>
        this.form.skillIds.some((id) => key.startsWith(`${id}::`)));
      this.result = null;
      for (const skill of this.selectedSkills) {
        if (this.documentsBySkill[skill.value]) continue;
        try {
          const documents = await fetchDomainSkillResources({ skillId: skill.value,
            documentIds: skill.boundDocumentIds || [], documentTags: skill.boundDocumentTags || [] });
          this.documentsBySkill = { ...this.documentsBySkill,
            [skill.value]: Array.isArray(documents) ? documents.filter((id) =>
              !this.selectedProvider?.grantRestricted || !this.selectedProvider.documentIds?.length
                || this.selectedProvider.documentIds.includes(id)) : [] };
        } catch (error) { this.error = error.message || `${skill.label || skill.value} 的文档权限加载失败`; }
      }
    },
    toolLabel(name) {
      const tool = this.toolCatalog.find((item) => item.localToolName === name);
      return tool?.chineseAlias || tool?.displayName || name;
    },
    acceptsEvidence(type) { return this.selectedProvider?.evidenceTypes?.includes(type) || false; },
    buildRequest() {
      const { form } = this;
      const skillIds = form.skillIds?.length ? form.skillIds : (form.skillId ? [form.skillId] : []);
      if (!form.query.trim() || !form.providerId || !form.capability || !skillIds.length)
        throw new Error("请填写分析要求并选择算力和至少一个知识 Skill");
      if (skillIds.length > 4 || new Set(skillIds).size !== skillIds.length)
        throw new Error("每次最多选择 4 个不同的 Skill");
      if (!form.confirmRemoteTransfer) throw new Error("请确认发送本次选中的证据");
      const skills = skillIds.map((skillId) => ({ skillId,
        documentIds: form.documentsBySkill?.[skillId] || (skillId === form.skillId ? form.documentIds || [] : []) }));
      const documentCount = skills.reduce((count, item) => count + item.documentIds.length, 0);
      const accepted = this.selectedProvider?.evidenceTypes || [];
      if (documentCount && !accepted.includes("DocumentAnalysisEvidence"))
        throw new Error("所选算力未获准接收文档证据");
      if (form.selectedTools.length && !accepted.includes("ToolAnalysisEvidence"))
        throw new Error("所选算力未获准接收 MCP 工具证据");
      if (form.dataTemplateId.trim() && !accepted.includes("StructuredDataEvidence"))
        throw new Error("所选算力未获准接收结构化数据证据");
      if (form.selectedTools.length > 4) throw new Error("每次最多编排 4 个 MCP 工具");
      const tools = form.selectedTools.map((key) => {
        const split = key.indexOf("::");
        const skillId = split < 0 ? skillIds[0] : key.slice(0, split);
        const toolName = split < 0 ? key : key.slice(split + 2);
        if (!skillIds.includes(skillId)) throw new Error("工具所属 Skill 未选择");
        let argumentsValue;
        try { argumentsValue = JSON.parse(form.toolArguments[key] || "{}"); }
        catch { throw new Error(`${toolName} 的参数不是有效 JSON`); }
        if (!argumentsValue || Array.isArray(argumentsValue) || typeof argumentsValue !== "object")
          throw new Error(`${toolName} 的参数必须是 JSON 对象`);
        return { skillId, toolName, arguments: argumentsValue };
      });
      let dataParameters = {};
      if (form.dataTemplateId.trim()) {
        if (!form.dataAssetName.trim() || !form.dataEnvironment)
          throw new Error("使用数据模板时，请填写资产名称和环境");
        try { dataParameters = JSON.parse(form.dataParameters || "{}"); }
        catch { throw new Error("数据模板参数不是有效 JSON"); }
        if (!dataParameters || Array.isArray(dataParameters) || typeof dataParameters !== "object")
          throw new Error("数据模板参数必须是 JSON 对象");
        if (!skillIds.includes(form.dataSkillId || skillIds[0]))
          throw new Error("数据模板所属 Skill 未选择");
      }
      if (!documentCount && !tools.length && !form.dataTemplateId.trim())
        throw new Error("请至少选择一项文档知识或数据能力");
      return {
        query: form.query.trim(), providerId: form.providerId, capability: form.capability,
        skillId: skillIds[0], skills, documentIds: skills[0].documentIds, documentTags: [],
        tools, dataSkillId: form.dataSkillId || skillIds[0], dataTemplateId: form.dataTemplateId.trim(),
        dataAssetName: form.dataAssetName.trim(), dataEnvironment: form.dataEnvironment,
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
