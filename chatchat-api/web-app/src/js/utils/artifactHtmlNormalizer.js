import { enhanceResultTables } from "./resultTableEnhancer.js";
import { stripInternalDocumentRefsFromHtml } from "./internalDocumentRefs.js";
import { hasMarkdownTable, normalizeMarkdownTables } from "./markdownTableNormalizer.js";

export function sanitizeArtifactHtml(value = "") {
  if (typeof DOMParser === "undefined") return "";
  const document = new DOMParser().parseFromString(String(value || ""), "text/html");
  document.querySelectorAll("script, iframe, object, embed, base, meta, form").forEach((node) => node.remove());
  document.querySelectorAll("*").forEach((node) => {
    [...node.attributes].forEach((attribute) => {
      const name = attribute.name.toLowerCase();
      const content = String(attribute.value || "");
      if (name.startsWith("on") || /javascript\s*:/i.test(content)) node.removeAttribute(attribute.name);
    });
  });
  return document.body.innerHTML;
}

export function repairEmbeddedMarkdownTables(value = "", renderMarkdown = (source) => source) {
  if (typeof DOMParser === "undefined") return value;
  const document = new DOMParser().parseFromString(String(value || ""), "text/html");
  document.querySelectorAll("p").forEach((paragraph) => {
    const withLineBreaks = paragraph.innerHTML.replace(/<br\s*\/?>/gi, "\n");
    const scratch = document.createElement("div");
    scratch.innerHTML = withLineBreaks;
    const source = String(scratch.textContent || "").trim();
    if (!hasMarkdownTable(source)) return;
    const replacement = document.createElement("div");
    replacement.innerHTML = renderMarkdown(normalizeMarkdownTables(source));
    paragraph.replaceWith(...replacement.childNodes);
  });
  return document.body.innerHTML;
}

export function normalizeArtifactHtml(value = "", renderMarkdown = (source) => source) {
  const sanitized = sanitizeArtifactHtml(value);
  const repaired = repairEmbeddedMarkdownTables(sanitized, renderMarkdown);
  return enhanceResultTables(stripInternalDocumentRefsFromHtml(repaired));
}
