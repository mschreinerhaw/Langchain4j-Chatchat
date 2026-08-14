import { describe, expect, it } from "vitest";
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
});
