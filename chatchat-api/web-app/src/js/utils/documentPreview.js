const DOCUMENT_TYPE_ALIASES = {
  txt: "text",
  text: "text",
  plain: "text",
  sql: "sql",
  md: "markdown",
  markdown: "markdown",
  csv: "excel",
  xls: "excel",
  xlsx: "excel",
  excel: "excel",
  spreadsheet: "excel",
  doc: "word",
  docx: "word",
  word: "word",
  pdf: "pdf",
  ppt: "presentation",
  pptx: "presentation",
  presentation: "presentation"
};

const SUPPORTED_ONLINE_PREVIEW_TYPES = new Set(["text", "markdown", "excel", "sql"]);

export const UNSUPPORTED_DOCUMENT_PREVIEW_MESSAGE = "仅支持在线查看 TXT、Markdown、Excel 文档";

export function normalizeDocumentType(value = "") {
  const normalized = String(value || "").trim().toLowerCase();
  if (!normalized || normalized === "auto") {
    return "";
  }
  return DOCUMENT_TYPE_ALIASES[normalized] || normalized;
}

export function inferDocumentType(fileName = "") {
  const normalizedName = String(fileName || "").trim().toLowerCase();
  const extension = normalizedName.includes(".") ? normalizedName.split(".").pop() : "";
  return normalizeDocumentType(extension) || "text";
}

export function getDocumentPreviewType(document = {}) {
  const extra = document?.extra && typeof document.extra === "object" ? document.extra : {};
  const explicitType = normalizeDocumentType(document?.documentType || document?.type || extra.documentType);
  if (explicitType) {
    return explicitType;
  }
  return inferDocumentType(
    document?.fileName
    || extra.fileName
    || document?.title
    || document?.targetId
    || document?.docId
    || ""
  );
}

export function isDocumentOnlinePreviewSupported(documentOrType = {}) {
  const type = typeof documentOrType === "string"
    ? normalizeDocumentType(documentOrType)
    : getDocumentPreviewType(documentOrType);
  return SUPPORTED_ONLINE_PREVIEW_TYPES.has(type);
}
