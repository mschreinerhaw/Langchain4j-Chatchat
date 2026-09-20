import { describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({ importSearchDocumentFromUrl: vi.fn() }));
vi.mock("../../services/api.js", async (importOriginal) => ({
  ...(await importOriginal()),
  importSearchDocumentFromUrl: api.importSearchDocumentFromUrl
}));
import AiSearchView from "./AiSearchView.js";

function context() {
  return {
    uploadError: "",
    uploadForm: {
      file: null,
      files: [],
      title: "",
      documentType: "auto"
    }
  };
}

describe("AiSearchView document upload limits", () => {
  it("accepts one document between 5MB and 55MB", () => {
    const view = context();
    const file = { name: "manual.pdf", size: 20 * 1024 * 1024 };

    AiSearchView.methods.handleFileChange.call(view, { target: { files: [file], value: "manual.pdf" } });

    expect(view.uploadError).toBe("");
    expect(view.uploadForm.file).toBe(file);
    expect(view.uploadForm.documentType).toBe("pdf");
  });

  it("requires a document larger than 5MB to be uploaded alone", () => {
    const view = context();
    const target = {
      files: [
        { name: "large.pdf", size: 20 * 1024 * 1024 },
        { name: "small.txt", size: 1024 }
      ],
      value: "selection"
    };

    AiSearchView.methods.handleFileChange.call(view, { target });

    expect(view.uploadError).toContain("仅支持单文件上传");
    expect(view.uploadForm.files).toEqual([]);
    expect(target.value).toBe("");
  });

  it("syncs a document from an internal HTTP endpoint with advanced parameters", async () => {
    api.importSearchDocumentFromUrl.mockResolvedValue({ docId: "remote-1", title: "内网研报" });
    const view = {
      uploadMode: "url", uploadUrl: " http://10.20.30.40/report ", uploadAdvancedOpen: true,
      uploadHttpMethod: "POST", uploadQueryParams: '{"version":2}',
      uploadHeaders: '{"Authorization":"Bearer token"}', uploadRequestBody: '{"id":1}',
      uploadAllowPrivateNetwork: true, uploadError: "", uploadNotice: "", uploading: false,
      uploadForm: {
        file: null, files: [], title: "内网研报", source: "投研内网", date: "2026-09-20",
        tags: "研报", documentType: "auto", categoryMode: "existing", category: "行业研究", newCategory: ""
      },
      effectiveTenantId: "tenant-a", userId: "analyst", documentUploadRequestId: "",
      documentUploadController: null, resolveUploadCategory: () => "行业研究",
      recordDocumentActivity: vi.fn(), resetUploadForm: vi.fn()
    };

    await AiSearchView.methods.uploadDocument.call(view);

    expect(api.importSearchDocumentFromUrl).toHaveBeenCalledWith(expect.objectContaining({
      url: "http://10.20.30.40/report",
      category: "行业研究",
      request: {
        method: "POST", queryParams: { version: "2" }, headers: { Authorization: "Bearer token" },
        body: '{"id":1}', allowPrivateNetwork: true
      }
    }), expect.objectContaining({ uploadRequestId: expect.stringMatching(/^upload-/) }));
    expect(view.recordDocumentActivity).toHaveBeenCalledWith(expect.objectContaining({ docId: "remote-1" }), "VIEW");
    expect(view.uploadNotice).toContain("网络同步");
    expect(view.resetUploadForm).toHaveBeenCalledOnce();
  });
});
