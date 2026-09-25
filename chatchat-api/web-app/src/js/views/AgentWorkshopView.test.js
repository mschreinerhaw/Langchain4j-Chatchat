import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";

const { fetchPublishedAgentCurlExample, discoverRemoteAgent, registerRemoteAgent, authSession } = vi.hoisted(() => ({
  fetchPublishedAgentCurlExample: vi.fn(),
  discoverRemoteAgent: vi.fn(),
  registerRemoteAgent: vi.fn(),
  authSession: { current: null }
}));

vi.mock("../../services/api.js", () => ({
  createWorkshopAgent: vi.fn(),
  discoverRemoteAgent,
  registerRemoteAgent,
  deleteWorkshopAgent: vi.fn(),
  fetchAgentWorkshop: vi.fn(),
  fetchRegisteredAgents: vi.fn(),
  fetchSkills: vi.fn(),
  fetchPublishedAgentCurlExample,
  getStoredAuthSession: vi.fn(() => authSession.current),
  publishWorkshopAgent: vi.fn(),
  recallWorkshopAgent: vi.fn(),
  setDefaultWorkshopAgent: vi.fn(),
  updateWorkshopAgent: vi.fn()
}));

import AgentWorkshopView from "./AgentWorkshopView.js";

describe("AgentWorkshopView remote compute registration", () => {
  it("stores user-written expertise without using data-category checkboxes as MCP grants", () => {
    const remoteForm = {
      agentId: "group.research", displayName: "Research Agent", endpoint: "https://group.example/a2a",
      origin: "GROUP", capabilities: "finance.research.v1", selectedCapabilities: ["finance.research.v1"],
      professionalCapabilities: "Portfolio analysis\nRisk attribution", selectedDocumentIds: [],
      selectedSkillIds: ["research-skill"], autoMcp: true, allowDocumentSupplement: false,
      allowDataSupplement: false, tenantIds: "tenant-1", dataDomains: "",
      supportedExecutionModes: ["DOMAIN_INFERENCE"], cardKeyId: "kid", cardPublicKeyPem: "pem",
      credentialRef: "", requestQueryParameters: "", requestBodyParameters: ""
    };
    const descriptor = AgentWorkshopView.methods.remoteDescriptor.call({ remoteForm, remotePreview: { version: "v1" } });
    expect(descriptor.capabilities).toEqual([{ namespace: "finance", name: "research", version: "v1" }]);
    expect(descriptor.metadata.professionalCapabilities).toEqual(["Portfolio analysis", "Risk attribution"]);
    expect(descriptor.metadata.analysisGrants).toMatchObject({ mcpToolNames: [], mcpRoleGoverned: true });
    expect(descriptor.allowedEvidenceTypes).toContain("ToolAnalysisEvidence");
  });

  it("does not expose runtime identifiers or evidence codes in the connection form", () => {
    const template = readFileSync(new URL("../../views/AgentWorkshopView.vue", import.meta.url), "utf8");
    const registration = template.split('<form class="agent-dialog remote-agent-dialog"')[1]
      .split('<div v-if="dialogOpen"')[0];
    for (const label of ["允许使用的租户编号", "Agent 标识", "业务能力 ID", "允许的数据域代码",
      "证据类型代码", "补证 Skill 类型", "路由优先级", "SLA 延迟阈值"])
      expect(registration).not.toContain(label);
    expect(registration).toContain("发布方验签公钥");
    expect(registration).toContain("URL 查询参数");
    expect(registration).toContain('v-model.trim="remoteForm.professionalCapabilities"');
    expect(registration).not.toContain('v-model="remoteForm.selectedDataCapabilities"');
    expect(registration).not.toContain('v-model="remoteForm.selectedMcpToolNames"');
  });

  it("uses only checked capabilities even when the verified Card lists more", () => {
    const descriptor = AgentWorkshopView.methods.remoteDescriptor.call({
      remotePreview: { version: "v1" },
      remoteSelectedToolNames: () => [],
      remoteForm: {
        agentId: "group.test", endpoint: "https://group.example/a2a", origin: "GROUP",
        capabilities: "finance.risk.v1", selectedCapabilities: [],
        selectedDocumentIds: [], selectedSkillIds: [], selectedMcpToolNames: [],
        supportedExecutionModes: ["DOMAIN_INFERENCE"], tenantIds: "tenant-1",
        dataDomains: "", cardKeyId: "kid", cardPublicKeyPem: "pem", credentialRef: "",
        requestQueryParameters: "", requestBodyParameters: ""
      }
    });
    expect(descriptor.capabilities).toEqual([]);
  });

  it("saves from the single-page form after connection and authorization", async () => {
    registerRemoteAgent.mockResolvedValueOnce({});
    const remoteForm = { tenantIds: "tenant-1", selectedCapabilities: ["finance.risk.v1"],
      selectedSkillIds: ["investment-skill"], professionalCapabilities: "Risk analysis" };
    const context = { remoteForm, remotePreview: { name: "Risk Agent" }, remoteError: "",
      remoteBusy: false, remoteDialogOpen: true, remoteDescriptor: () => ({ agentId: "group.risk" }),
      loadRemoteAgents: vi.fn() };
    await AgentWorkshopView.methods.saveRemoteAgent.call(context);
    expect(registerRemoteAgent).toHaveBeenCalledWith({ agentId: "group.risk" });
    expect(context.remoteDialogOpen).toBe(false);
  });

  it("persists selected business grants without exposing protocol choices in the main flow", () => {
    const remoteForm = {
      agentId: "group.investment", displayName: "客户投资分析", endpoint: "https://group.example/a2a",
      origin: "GROUP", capabilities: "finance.portfolio.v1\nfinance.risk.v1",
      selectedCapabilities: ["finance.risk.v1"], selectedDocumentIds: ["doc-1"],
      selectedSkillIds: ["investment-skill"], selectedDataCapabilities: ["position"],
      selectedMcpToolNames: [], autoMcp: true, allowDataSupplement: false,
      allowDocumentSupplement: false, allowMcpSupplement: true,
      defaultInstruction: "只根据证据分析", tenantIds: "tenant-1", dataDomains: "",
      supportedExecutionModes: ["DOMAIN_INFERENCE"], supplementSkillTypes: "",
      structuredSupplement: "", cardKeyId: "kid", cardPublicKeyPem: "pem",
      credentialRef: "", requestQueryParameters: "", requestBodyParameters: "",
      priority: 50, slaLatencyMs: 10000
    };
    const descriptor = AgentWorkshopView.methods.remoteDescriptor.call({ remoteForm,
      remotePreview: { version: "v1" }, remoteSelectedToolNames: () => ["position_query"] });
    expect(descriptor.capabilities).toEqual([{ namespace: "finance", name: "risk", version: "v1" }]);
    expect(descriptor.allowedEvidenceTypes).toEqual(["DocumentAnalysisEvidence", "ToolAnalysisEvidence"]);
    expect(descriptor.metadata.analysisGrants).toMatchObject({ skillIds: ["investment-skill"],
      documentIds: ["doc-1"], mcpToolNames: ["position_query"], defaultInstruction: "只根据证据分析" });
  });
  it("keeps A2A credentials as references and exposes explicit evidence grants", () => {
    const descriptor = AgentWorkshopView.methods.remoteDescriptor.call({
      remotePreview: { version: "v2" },
      remoteForm: {
        agentId: "group.research", endpoint: "https://group.example/a2a", origin: "GROUP",
        capabilities: "finance.research.v1", tenantIds: "tenant-1",
        dataDomains: "market", evidenceTypes: "DocumentAnalysisEvidence",
        supportedExecutionModes: ["DOMAIN_INFERENCE", "AGENTIC_EXECUTION"],
        supplementSkillTypes: "RULE_LOOKUP", structuredSupplement: "STRUCTURED_DATA", cardKeyId: "group-key",
        cardPublicKeyPem: "public-pem", credentialRef: "env:GROUP_TOKEN",
        priority: 50, slaLatencyMs: 10000, maxAttempts: 2
      }
    });
    expect(descriptor.protocol).toBe("A2A_HTTP_JSON");
    expect(descriptor.capabilities).toEqual([{ namespace: "finance", name: "research", version: "v1" }]);
    expect(descriptor.metadata.allowedTenantIds).toEqual(["tenant-1"]);
    expect(descriptor.metadata.supplementSkillTypes).toEqual(["RULE_LOOKUP"]);
    expect(descriptor.metadata.supplementCapabilities).toEqual(["STRUCTURED_DATA"]);
    expect(descriptor.metadata.supportedExecutionModes).toEqual(["DOMAIN_INFERENCE", "AGENTIC_EXECUTION"]);
    expect(descriptor.metadata.requireSignedCard).toBe(true);
    expect(descriptor.credentialRef).toBe("env:GROUP_TOKEN");
  });

  it("passes fixed URL and message parameters without treating them as credentials", () => {
    const descriptor = AgentWorkshopView.methods.remoteDescriptor.call({
      remotePreview: null,
      remoteForm: {
        agentId: "", endpoint: "https://group.example/a2a", origin: "GROUP",
        capabilities: "", tenantIds: "tenant-1", dataDomains: "", evidenceTypes: "",
        supportedExecutionModes: ["DOMAIN_INFERENCE"], supplementSkillTypes: "",
        structuredSupplement: "", cardKeyId: "kid", cardPublicKeyPem: "pem",
        credentialRef: "", requestQueryParameters: "region=north east\nchannel=research",
        requestBodyParameters: '{"businessUnit":"research","year":2026}',
        priority: 50, slaLatencyMs: 10000, maxAttempts: 2
      }
    });
    expect(descriptor.metadata.requestQueryParameters).toEqual({ region: "north east", channel: "research" });
    expect(descriptor.metadata.requestBodyParameters).toEqual({ businessUnit: "research", year: 2026 });
    expect(descriptor.capabilities).toEqual([]);
    expect(descriptor.agentId).toBe("preview.remote-agent");
  });

  it("rejects secret-like fixed URL parameter names", () => {
    const remoteForm = {
      agentId: "", endpoint: "https://group.example/a2a", origin: "GROUP", capabilities: "",
      supportedExecutionModes: ["DOMAIN_INFERENCE"], cardKeyId: "kid", cardPublicKeyPem: "pem",
      credentialRef: "", requestQueryParameters: "authToken=plaintext", requestBodyParameters: ""
    };
    expect(() => AgentWorkshopView.methods.remoteDescriptor.call({ remoteForm }))
      .toThrow(/敏感凭据/);
  });

  it("fills agent identity and capability IDs from a verified Card", async () => {
    discoverRemoteAgent.mockResolvedValueOnce({
      name: "Industry Research", version: "v2", skills: ["finance.industry.v1"]
    });
    const remoteForm = {
      agentId: "", endpoint: "https://group.example/a2a", origin: "GROUP",
      cardKeyId: "kid", cardPublicKeyPem: "pem", capabilities: ""
    };
    const context = {
      remoteForm, remoteError: "", remoteBusy: false, remotePreview: null,
      remoteDescriptor: () => ({ agentId: "preview.remote-agent" })
    };
    await AgentWorkshopView.methods.previewRemoteAgent.call(context);
    expect(remoteForm.agentId).toBe("group.industry-research");
    expect(remoteForm.capabilities).toBe("finance.industry.v1");
    expect(context.remotePreview.version).toBe("v2");
  });
});

describe("AgentWorkshopView MCP Chinese aliases", () => {
  it("keeps the English tool identity while exposing the Chinese alias for search and hover", () => {
    const tools = AgentWorkshopView.computed.normalizedMcpTools.call({
      registeredMcpTools: [{ localToolName: "mcp_demo_search", remoteToolName: "search", chineseAlias: "检索资料" }]
    });
    expect(tools[0]).toMatchObject({
      localToolName: "mcp_demo_search", remoteToolName: "search", chineseAlias: "检索资料"
    });
    expect(AgentWorkshopView.methods.toolSearchText.call({}, tools[0])).toContain("检索资料");
    expect(AgentWorkshopView.methods.applicabilityTooltip.call({}, tools[0])).toContain("英文名称：search");
  });
});

describe("AgentWorkshopView published Agent curl access", () => {
  beforeEach(() => {
    authSession.current = null;
    fetchPublishedAgentCurlExample.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows the API action only for the admin account", () => {
    authSession.current = { user: { username: "admin" } };
    expect(AgentWorkshopView.computed.isPlatformAdmin()).toBe(true);

    authSession.current = { user: { username: "analyst" } };
    expect(AgentWorkshopView.computed.isPlatformAdmin()).toBe(false);
  });

  it("loads the complete submit, status, stop and answer curl example", async () => {
    fetchPublishedAgentCurlExample.mockResolvedValue({
      completeExample: "submit\nstatus\nstop\nanswer"
    });
    const context = {
      isPlatformAdmin: true,
      curlExampleOpen: false,
      curlExampleLoading: false,
      curlExampleError: "",
      curlExample: null
    };

    await AgentWorkshopView.methods.openCurlExample.call(context, {
      id: "finance-agent",
      marketStatus: "published"
    });

    expect(fetchPublishedAgentCurlExample).toHaveBeenCalledWith("finance-agent");
    expect(context.curlExampleOpen).toBe(true);
    expect(context.curlExampleLoading).toBe(false);
    expect(context.curlExample.completeExample).toContain("status");
    expect(context.curlExample.completeExample).toContain("stop");
    expect(context.curlExample.completeExample).toContain("answer");
  });

  it("falls back to the compatible copy path when Clipboard API is denied", async () => {
    const textarea = {
      value: "",
      style: {},
      setAttribute: vi.fn(),
      focus: vi.fn(),
      select: vi.fn(),
      setSelectionRange: vi.fn(),
      remove: vi.fn()
    };
    const execCommand = vi.fn(() => true);
    vi.stubGlobal("navigator", {
      clipboard: { writeText: vi.fn().mockRejectedValue(new Error("denied")) }
    });
    vi.stubGlobal("document", {
      body: { appendChild: vi.fn() },
      createElement: vi.fn(() => textarea),
      execCommand
    });
    const context = {
      curlExample: { completeExample: "curl --request POST" },
      curlExampleError: "previous error",
      curlExampleCopied: false
    };

    await AgentWorkshopView.methods.copyCurlExample.call(context);

    expect(execCommand).toHaveBeenCalledWith("copy");
    expect(context.curlExampleError).toBe("");
    expect(context.curlExampleCopied).toBe(true);
    expect(textarea.remove).toHaveBeenCalledOnce();
  });
});

describe("AgentWorkshopView knowledge document selection", () => {
  it("counts published domain skills as bound knowledge resources", () => {
    const label = AgentWorkshopView.methods.documentCountLabel({
      boundDocumentIds: ["doc-1"],
      boundDomainSkillIds: ["skill-risk"]
    });

    expect(label).toBe("2 个");
  });

  it("filters existing documents by keyword, category and type", () => {
    const context = {
      documents: [
        {
          docId: "doc-options",
          title: "股票期权业务指南",
          category: "两融业务人员",
          documentType: "PDF",
          lifecycleStatus: "INDEXED",
          tags: ["期权"]
        },
        {
          docId: "doc-risk",
          title: "客户风险制度",
          category: "风险管理",
          documentType: "DOCX",
          lifecycleStatus: "INDEXED"
        }
      ],
      selectedDocumentIds: [],
      documentSearchQuery: "期权",
      documentCategoryFilter: "两融业务人员",
      documentTypeFilter: "PDF",
      documentSearchText: AgentWorkshopView.methods.documentSearchText
    };
    context.normalizedDocuments = AgentWorkshopView.computed.normalizedDocuments.call(context);

    const result = AgentWorkshopView.computed.filteredDocuments.call(context);

    expect(result.map((document) => document.docId)).toEqual(["doc-options"]);
  });

  it("adds and removes a bound document without affecting runtime mode", () => {
    const context = {
      form: { defaultMode: "role_chat", boundDocumentIds: [] }
    };
    Object.defineProperty(context, "selectedDocumentIds", {
      get: () => AgentWorkshopView.computed.selectedDocumentIds.call(context)
    });

    AgentWorkshopView.methods.toggleDocument.call(context, "doc-options");
    expect(context.form.boundDocumentIds).toEqual(["doc-options"]);
    expect(context.form.defaultMode).toBe("role_chat");

    AgentWorkshopView.methods.toggleDocument.call(context, "doc-options");
    expect(context.form.boundDocumentIds).toEqual([]);
  });

  it("filters published domain skills separately and stores them as skill bindings", () => {
    const context = {
      documents: [{ docId: "doc-1", title: "普通研报", documentType: "PDF", lifecycleStatus: "INDEXED" }],
      domainSkills: [{ id: "skill-risk", name: "风险识别", category: "合规风控", description: "识别风险事项" }],
      form: { boundDocumentIds: [], boundDomainSkillIds: [] },
      documentSearchQuery: "",
      documentCategoryFilter: "all",
      documentTypeFilter: "领域技能",
      documentSearchText: AgentWorkshopView.methods.documentSearchText
    };
    Object.defineProperty(context, "selectedDocumentIds", {
      get: () => AgentWorkshopView.computed.selectedDocumentIds.call(context)
    });
    Object.defineProperty(context, "selectedDomainSkillIds", {
      get: () => AgentWorkshopView.computed.selectedDomainSkillIds.call(context)
    });
    context.normalizedDocuments = AgentWorkshopView.computed.normalizedDocuments.call(context);

    const result = AgentWorkshopView.computed.filteredDocuments.call(context);
    const typeOptions = AgentWorkshopView.computed.documentTypeOptions.call(context);
    expect(result).toHaveLength(1);
    expect(result[0]).toMatchObject({ docId: "skill-risk", documentType: "领域技能", lifecycleStatus: "PUBLISHED" });
    expect(typeOptions).toContainEqual({ value: "领域技能", label: "领域技能（仅已发布）" });

    AgentWorkshopView.methods.toggleDocument.call(context, result[0]);
    expect(context.form.boundDomainSkillIds).toEqual(["skill-risk"]);
    expect(context.form.boundDocumentIds).toEqual([]);
  });
});

describe("AgentWorkshopView resource picker dialogs", () => {
  it("opens only one lightweight picker at a time and closes it independently", () => {
    const context = {
      documentPickerOpen: false,
      toolPickerOpen: false
    };

    AgentWorkshopView.methods.openDocumentPicker.call(context);
    expect(context.documentPickerOpen).toBe(true);
    expect(context.toolPickerOpen).toBe(false);

    AgentWorkshopView.methods.openToolPicker.call(context);
    expect(context.documentPickerOpen).toBe(false);
    expect(context.toolPickerOpen).toBe(true);

    AgentWorkshopView.methods.closeToolPicker.call(context);
    expect(context.toolPickerOpen).toBe(false);
  });
});
