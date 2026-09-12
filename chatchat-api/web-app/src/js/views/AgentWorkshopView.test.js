import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { fetchPublishedAgentCurlExample, authSession } = vi.hoisted(() => ({
  fetchPublishedAgentCurlExample: vi.fn(),
  authSession: { current: null }
}));

vi.mock("../../services/api.js", () => ({
  createWorkshopAgent: vi.fn(),
  deleteWorkshopAgent: vi.fn(),
  fetchAgentWorkshop: vi.fn(),
  fetchPublishedAgentCurlExample,
  getStoredAuthSession: vi.fn(() => authSession.current),
  publishWorkshopAgent: vi.fn(),
  recallWorkshopAgent: vi.fn(),
  setDefaultWorkshopAgent: vi.fn(),
  updateWorkshopAgent: vi.fn()
}));

import AgentWorkshopView from "./AgentWorkshopView.js";

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
