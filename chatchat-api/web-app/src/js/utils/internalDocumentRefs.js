const INTERNAL_DOCUMENT_REF_PATTERN = /(?:[（(]\s*)?doc:\/\/[^\s<>()\[\]{}，。；;]+(?:\s*[）)])?\s*[:：]?/gi;
const INTERNAL_RECORD_RANGE_PATTERN = /\brecords?\s*\[\s*\d+\s*(?:(?:\.{2,3}|…|—|–|-)\s*\d+)?\s*\]\s*[:：]?/gi;

export function isInternalDocumentRef(value = "") {
  return /^doc:\/\//i.test(String(value || "").trim());
}

export function stripInternalDocumentRefs(value = "") {
  return String(value || "")
    .replace(INTERNAL_DOCUMENT_REF_PATTERN, " ")
    .replace(INTERNAL_RECORD_RANGE_PATTERN, " ")
    .replace(/[ \t]+([，。；;、])/g, "$1")
    .replace(/[ \t]{2,}/g, " ")
    .replace(/^\s*[-–—·:：|]+\s*$/gm, "")
    .trim();
}

export function stripInternalDocumentRefsFromHtml(value = "") {
  if (typeof DOMParser === "undefined") return stripInternalDocumentRefs(value);
  const document = new DOMParser().parseFromString(String(value || ""), "text/html");
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  const nodes = [];
  while (walker.nextNode()) nodes.push(walker.currentNode);
  nodes.forEach((node) => {
    node.nodeValue = stripInternalDocumentRefs(node.nodeValue || "");
  });
  document.querySelectorAll("a[href]").forEach((link) => {
    if (isInternalDocumentRef(link.getAttribute("href"))) link.replaceWith(...link.childNodes);
  });
  document.querySelectorAll("p, li, span, small").forEach((node) => {
    if (!String(node.textContent || "").trim() && !node.querySelector("img, table, pre, code")) node.remove();
  });
  return document.body.innerHTML;
}
