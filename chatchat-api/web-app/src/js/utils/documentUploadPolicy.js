export const STANDARD_DOCUMENT_UPLOAD_BYTES = 5 * 1024 * 1024;
export const MAX_DOCUMENT_UPLOAD_BYTES = 55 * 1024 * 1024;

export function validateDocumentUploadSelection(files) {
  const selected = Array.from(files || []).filter(Boolean);
  const oversized = selected.find((file) => Number(file?.size || 0) > MAX_DOCUMENT_UPLOAD_BYTES);
  if (oversized) {
    return {
      valid: false,
      message: `文件不能超过 55MB：${oversized.name || "未命名文件"}`
    };
  }

  const largeFile = selected.find((file) => Number(file?.size || 0) > STANDARD_DOCUMENT_UPLOAD_BYTES);
  if (selected.length > 1 && largeFile) {
    return {
      valid: false,
      message: `超过 5MB 的文档仅支持单文件上传：${largeFile.name || "未命名文件"}`
    };
  }

  return { valid: true, message: "" };
}
