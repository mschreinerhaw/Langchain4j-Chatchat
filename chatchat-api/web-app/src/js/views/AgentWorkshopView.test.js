import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { parse as parseVueTemplate } from "@vue/compiler-dom";

const { fetchPublishedAgentCurlExample, authSession } =
  vi.hoisted(() => ({
    fetchPublishedAgentCurlExample: vi.fn(),
    authSession: { current: null },
  }));

vi.mock("../../services/api.js", () => ({
  createWorkshopAgent: vi.fn(),
  deleteWorkshopAgent: vi.fn(),
  fetchAgentWorkshop: vi.fn(),
  fetchSkills: vi.fn(),
  fetchPublishedAgentCurlExample,
  getStoredAuthSession: vi.fn(() => authSession.current),
  publishWorkshopAgent: vi.fn(),
  recallWorkshopAgent: vi.fn(),
  setDefaultWorkshopAgent: vi.fn(),
  updateWorkshopAgent: vi.fn(),
}));

import AgentWorkshopView from "./AgentWorkshopView.js";

describe("AgentWorkshopView query service management", () => {
  it("preserves each Agent's builtin Skill disable policy while normalizing workflow tools", () => {
    const context = { form: { id: "agent-a" } };
    const a = AgentWorkshopView.methods.normalizeWorkflowConfig.call(context,
      { disabledBuiltinSkillIds: ["builtin:python-data-analysis"] }, []);
    const b = AgentWorkshopView.methods.normalizeWorkflowConfig.call({ form: { id: "agent-b" } }, {}, []);
    expect(a.disabledBuiltinSkillIds).toEqual(["builtin:python-data-analysis"]);
    expect(b.disabledBuiltinSkillIds).toEqual([]);
  });
  it("retains the local service editor and removes professional compute registration", () => {
    const template = readFileSync(new URL("../../views/AgentWorkshopView.vue", import.meta.url), "utf8");
    expect(() => parseVueTemplate(template)).not.toThrow();
    expect(template).toContain("新增Agent");
    expect(template).not.toMatch(/接入专有分析|联合分析|remoteDialogOpen|analyze-with-agent/);
    expect(AgentWorkshopView.methods.openCreateDialog).toBeTypeOf("function");
    expect(AgentWorkshopView.methods.openRemoteDialog).toBeUndefined();
  });

});

describe("AgentWorkshopView MCP Chinese aliases", () => {
  it('filters published tool choices by business classification instead of backend type',()=>{
    const rows=[{category:'客户服务',backendServiceTypes:['sql'],description:'查询客户'},{category:'数据核验',backendServiceTypes:['sql'],description:'检查差异'}];
    const filtered=AgentWorkshopView.computed.filteredMcpTools.call({toolSearchQuery:'',toolBackendServiceTypeFilter:'all',toolBusinessCategoryFilter:'客户服务',normalizedMcpTools:rows,toolSearchText:()=>''});
    expect(filtered).toEqual([rows[0]]);
  });
  it("keeps the English tool identity while exposing the Chinese alias for search and hover", () => {
    const tools = AgentWorkshopView.computed.normalizedMcpTools.call({
      registeredMcpTools: [
        { localToolName: "mcp_demo_search", remoteToolName: "search", chineseAlias: "检索资料" },
      ],
    });
    expect(tools[0]).toMatchObject({
      localToolName: "mcp_demo_search",
      remoteToolName: "search",
      chineseAlias: "检索资料",
    });
    expect(AgentWorkshopView.methods.toolSearchText.call({}, tools[0])).toContain("检索资料");
    expect(AgentWorkshopView.methods.applicabilityTooltip.call({}, tools[0])).toContain(
      "英文名称：search"
    );
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

  it("shows the API action only with the persisted registry permission", () => {
    authSession.current = {
      user: { username: "operator", permissionCodes: ["system:agent-registry:manage"] },
    };
    expect(AgentWorkshopView.computed.isPlatformAdmin()).toBe(true);

    authSession.current = { user: { username: "admin", permissionCodes: [] } };
    expect(AgentWorkshopView.computed.isPlatformAdmin()).toBe(false);
  });

  it("loads the complete submit, status, stop and answer curl example", async () => {
    fetchPublishedAgentCurlExample.mockResolvedValue({
      completeExample: "submit\nstatus\nstop\nanswer",
    });
    const context = {
      isPlatformAdmin: true,
      curlExampleOpen: false,
      curlExampleLoading: false,
      curlExampleError: "",
      curlExample: null,
    };

    await AgentWorkshopView.methods.openCurlExample.call(context, {
      id: "finance-agent",
      marketStatus: "published",
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
      remove: vi.fn(),
    };
    const execCommand = vi.fn(() => true);
    vi.stubGlobal("navigator", {
      clipboard: { writeText: vi.fn().mockRejectedValue(new Error("denied")) },
    });
    vi.stubGlobal("document", {
      body: { appendChild: vi.fn() },
      createElement: vi.fn(() => textarea),
      execCommand,
    });
    const context = {
      curlExample: { completeExample: "curl --request POST" },
      curlExampleError: "previous error",
      curlExampleCopied: false,
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
      boundDomainSkillIds: ["skill-risk"],
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
          tags: ["期权"],
        },
        {
          docId: "doc-risk",
          title: "客户风险制度",
          category: "风险管理",
          documentType: "DOCX",
          lifecycleStatus: "INDEXED",
        },
      ],
      selectedDocumentIds: [],
      documentSearchQuery: "期权",
      documentCategoryFilter: "两融业务人员",
      documentTypeFilter: "PDF",
      documentSearchText: AgentWorkshopView.methods.documentSearchText,
    };
    context.normalizedDocuments = AgentWorkshopView.computed.normalizedDocuments.call(context);
    context.normalizedKnowledgeDocuments =
      AgentWorkshopView.computed.normalizedKnowledgeDocuments.call(context);

    const result = AgentWorkshopView.computed.filteredDocuments.call(context);

    expect(result.map((document) => document.docId)).toEqual(["doc-options"]);
  });

  it("adds and removes a bound document without affecting runtime mode", () => {
    const context = {
      form: { defaultMode: "role_chat", boundDocumentIds: [] },
    };
    Object.defineProperty(context, "selectedDocumentIds", {
      get: () => AgentWorkshopView.computed.selectedDocumentIds.call(context),
    });

    AgentWorkshopView.methods.toggleDocument.call(context, "doc-options");
    expect(context.form.boundDocumentIds).toEqual(["doc-options"]);
    expect(context.form.defaultMode).toBe("role_chat");

    AgentWorkshopView.methods.toggleDocument.call(context, "doc-options");
    expect(context.form.boundDocumentIds).toEqual([]);
  });

  it("paginates large document catalogs and supports selecting the current page", () => {
    const context = {
      normalizedKnowledgeDocuments: Array.from({ length: 105 }, (_, index) => ({
        docId: `doc-${index + 1}`,
        title: `文档 ${index + 1}`,
        resourceKind: "knowledge_document",
        lifecycleStatus: "INDEXED",
      })),
      documentSearchQuery: "",
      documentCategoryFilter: "all",
      documentTypeFilter: "all",
      documentPickerPage: 3,
      resourcePickerPageSize: 20,
      form: { boundDocumentIds: [] },
      documentSearchText: AgentWorkshopView.methods.documentSearchText,
      documentSelectable: AgentWorkshopView.methods.documentSelectable,
    };
    Object.defineProperty(context, "selectedDocumentIds", {
      get: () => AgentWorkshopView.computed.selectedDocumentIds.call(context),
    });
    context.filteredDocuments = AgentWorkshopView.computed.filteredDocuments.call(context);
    context.pagedDocuments = AgentWorkshopView.computed.pagedDocuments.call(context);

    expect(context.filteredDocuments).toHaveLength(105);
    expect(context.pagedDocuments[0].docId).toBe("doc-41");
    expect(AgentWorkshopView.computed.documentPickerPageCount.call(context)).toBe(6);

    AgentWorkshopView.methods.toggleDocumentPage.call(context, true);
    expect(context.form.boundDocumentIds).toHaveLength(20);
    expect(context.form.boundDocumentIds).toContain("doc-41");
    expect(context.form.boundDocumentIds).toContain("doc-60");
  });

  it("filters published domain skills separately and stores them as skill bindings", () => {
    const context = {
      documents: [
        { docId: "doc-1", title: "普通研报", documentType: "PDF", lifecycleStatus: "INDEXED" },
      ],
      domainSkills: [
        { id: "skill-risk", name: "风险识别", category: "合规风控", description: "识别风险事项" },
      ],
      form: { boundDocumentIds: [], boundDomainSkillIds: [] },
      skillSearchQuery: "",
      skillCategoryFilter: "all",
      documentSearchText: AgentWorkshopView.methods.documentSearchText,
    };
    Object.defineProperty(context, "selectedDocumentIds", {
      get: () => AgentWorkshopView.computed.selectedDocumentIds.call(context),
    });
    Object.defineProperty(context, "selectedDomainSkillIds", {
      get: () => AgentWorkshopView.computed.selectedDomainSkillIds.call(context),
    });
    context.normalizedDocuments = AgentWorkshopView.computed.normalizedDocuments.call(context);
    context.normalizedDomainSkills =
      AgentWorkshopView.computed.normalizedDomainSkills.call(context);

    const result = AgentWorkshopView.computed.filteredDomainSkills.call(context);
    expect(result).toHaveLength(1);
    expect(result[0]).toMatchObject({
      docId: "skill-risk",
      documentType: "领域技能",
      lifecycleStatus: "PUBLISHED",
    });

    AgentWorkshopView.methods.toggleDocument.call(context, result[0]);
    expect(context.form.boundDomainSkillIds).toEqual(["skill-risk"]);
    expect(context.form.boundDocumentIds).toEqual([]);
  });
});

describe("AgentWorkshopView resource picker dialogs", () => {
  it("opens only one lightweight picker at a time and closes it independently", () => {
    const context = {
      documentPickerOpen: false,
      skillPickerOpen: false,
      documentPickerPage: 2,
      skillPickerPage: 2,
      toolPickerOpen: false,
    };

    AgentWorkshopView.methods.openDocumentPicker.call(context);
    expect(context.documentPickerOpen).toBe(true);
    expect(context.toolPickerOpen).toBe(false);

    AgentWorkshopView.methods.openSkillPicker.call(context);
    expect(context.documentPickerOpen).toBe(false);
    expect(context.skillPickerOpen).toBe(true);
    expect(context.skillPickerPage).toBe(1);

    AgentWorkshopView.methods.openToolPicker.call(context);
    expect(context.documentPickerOpen).toBe(false);
    expect(context.skillPickerOpen).toBe(false);
    expect(context.toolPickerOpen).toBe(true);

    AgentWorkshopView.methods.closeToolPicker.call(context);
    expect(context.toolPickerOpen).toBe(false);
  });
});
