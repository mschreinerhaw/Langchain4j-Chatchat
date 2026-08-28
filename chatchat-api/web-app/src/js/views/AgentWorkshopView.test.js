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

  it("loads the complete question, status and answer curl example", async () => {
    fetchPublishedAgentCurlExample.mockResolvedValue({
      completeExample: "submit\nstatus\nanswer"
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
