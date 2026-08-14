import { describe, expect, it } from "vitest";
import {
  MAX_DOCUMENT_UPLOAD_BYTES,
  STANDARD_DOCUMENT_UPLOAD_BYTES,
  validateDocumentUploadSelection
} from "./documentUploadPolicy.js";

function file(name, size) {
  return { name, size };
}

describe("document upload policy", () => {
  it("allows one document between 5MB and 55MB", () => {
    expect(validateDocumentUploadSelection([
      file("large.pdf", STANDARD_DOCUMENT_UPLOAD_BYTES + 1)
    ])).toEqual({ valid: true, message: "" });
  });

  it("rejects a large document in a multi-file selection", () => {
    const result = validateDocumentUploadSelection([
      file("large.docx", STANDARD_DOCUMENT_UPLOAD_BYTES + 1),
      file("small.txt", 1024)
    ]);

    expect(result.valid).toBe(false);
    expect(result.message).toContain("仅支持单文件上传");
  });

  it("rejects a document larger than 55MB", () => {
    const result = validateDocumentUploadSelection([
      file("too-large.pdf", MAX_DOCUMENT_UPLOAD_BYTES + 1)
    ]);

    expect(result.valid).toBe(false);
    expect(result.message).toContain("55MB");
  });
});
