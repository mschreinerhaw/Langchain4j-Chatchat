export function extractWebSearchPages(trace) {
  if (!isWebSearchTrace(trace)) {
    return [];
  }
  const output = parseTraceOutput(trace?.output);
  const containers = webSearchContainers(output);
  const referenceUrls = containers
    .map((item) => item?.reference_urls || item?.referenceUrls)
    .find(Array.isArray);
  const candidates = containers
    .flatMap((item) => [
      item?.results,
      item?.items,
      item?.organic_results,
      item?.webPages,
      item?.pageExcerpts,
      item?.evidenceSnippets
    ])
    .filter(Array.isArray)
    .flat();
  const pages = candidates
    .map((item, index) => ({
      rank: item.rank || item.position || index + 1,
      title: item.title || item.name || item.source || item.url || item.link || "引用",
      url: item.url || item.link || item.href || item.sourceUrl || "",
      snippet: shortSnippet(
        item.snippet
          || item.excerpt
          || item.pageExcerpt
          || item.contentExcerpt
          || item.description
          || item.summary
          || item.content
          || item.text
      )
    }))
    .filter((item) => item.url || item.title);
  if (!referenceUrls?.length) {
    return uniquePages(pages).slice(0, 10);
  }
  const pagesByUrl = new Map(pages.filter((page) => page.url).map((page) => [page.url, page]));
  return uniquePages(referenceUrls
    .map((url, index) => {
      const matched = pagesByUrl.get(url) || {};
      return {
        rank: matched.rank || index + 1,
        title: matched.title || url,
        url,
        snippet: matched.snippet || ""
      };
    })
    .filter((item) => item.url || item.title))
    .slice(0, 10);
}

export function extractWebSearchPagesFromTraces(traces = []) {
  if (!Array.isArray(traces)) {
    return [];
  }
  return uniquePages(traces.flatMap((trace) => extractWebSearchPages(trace)));
}

export function extractDocumentSearchPages(trace) {
  if (!isDocumentSearchTrace(trace)) {
    return [];
  }
  const output = parseTraceOutput(trace?.output);
  const candidates = referenceContainers(output)
    .flatMap((item) => [
      item?.results,
      item?.items,
      item?.documents,
      item?.records,
      item?.pageExcerpts,
      item?.evidenceSnippets
    ])
    .filter(Array.isArray)
    .flat();
  return uniqueDocumentPages(candidates
    .map((item, index) => {
      const docId = item.docId || item.documentId || item.id || item.fileId || item.file_id || documentIdFromRef(item.refId) || "";
      return {
        rank: item.rank || item.position || index + 1,
        docId,
        title: documentReferenceTitle(item, docId),
        url: item.url || item.link || item.href || item.sourceUrl || item.detailPath || "",
        snippet: shortSnippet(
          item.excerpt
            || item.contentExcerpt
            || item.pageExcerpt
            || item.snippet
            || item.summary
            || item.description
            || item.content
            || item.text
        )
      };
    })
    .filter((item) => item.docId || item.url || item.title || item.snippet))
    .slice(0, 10);
}

export function extractDocumentSearchPagesFromTraces(traces = []) {
  if (!Array.isArray(traces)) {
    return [];
  }
  return uniqueDocumentPages(traces.flatMap((trace) => extractDocumentSearchPages(trace)));
}

export function documentReferenceTitle(item = {}, docId = "") {
  const citation = item && typeof item.citation === "object" ? item.citation : {};
  const source = item && typeof item.source === "object" ? item.source : {};
  const normalizedDocId = String(docId || "").trim();
  const fileId = String(item.fileId || item.file_id || "").trim();
  const candidates = [
    item.fileName,
    item.file_name,
    item.documentTitle,
    item.document_title,
    item.title,
    item.name,
    item.filename,
    citation.fileName,
    citation.file_name,
    citation.source,
    source.name,
    source.title,
    source.fileName,
    source.file_name,
    typeof item.source === "string" ? item.source : "",
    normalizedDocId
  ];
  const title = candidates
    .map((value) => String(value || "").trim())
    .find((value) => isReadableDocumentTitle(value, normalizedDocId, fileId));
  return title || normalizedDocId || "\u5f15\u7528\u6587\u6863";
}

export function displayUrl(url) {
  if (!url) {
    return "";
  }
  try {
    const parsed = new URL(url);
    return parsed.hostname.replace(/^www\./, "");
  } catch (error) {
    return url;
  }
}

function webSearchContainers(output) {
  return referenceContainers(output);
}

function referenceContainers(output) {
  const containers = [];
  const visit = (value, depth = 0) => {
    if (!value || typeof value !== "object" || depth > 3) {
      return;
    }
    containers.push(value);
    [
      value.data,
      value.result,
      value.structuredContent,
      value.structured_content,
      value.payload
    ].forEach((item) => visit(item, depth + 1));
  };
  visit(output);
  return containers;
}

function uniquePages(pages) {
  const seen = new Set();
  return pages.filter((page) => {
    const key = page.url || `${page.rank}:${page.title}`;
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });
}

function uniqueDocumentPages(pages) {
  const seen = new Set();
  return pages.filter((page) => {
    const key = page.docId || page.url || `${page.rank}:${page.title}:${page.snippet}`;
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });
}

function shortSnippet(value) {
  const normalized = String(value || "").replace(/\s+/g, " ").trim();
  if (!normalized) {
    return "";
  }
  return normalized.length > 120 ? `${normalized.slice(0, 120)}...` : normalized;
}

function documentIdFromRef(refId) {
  const match = String(refId || "").match(/^doc:\/\/([^#]+)/i);
  return match ? match[1] : "";
}

function isReadableDocumentTitle(value, docId, fileId) {
  if (!value) {
    return false;
  }
  if (value === docId || value === fileId) {
    return false;
  }
  if (/^doc:\/\//i.test(value) || /^https?:\/\//i.test(value)) {
    return false;
  }
  if (/^\d{8}_[a-f0-9]{6,}$/i.test(value)) {
    return false;
  }
  return true;
}

function isWebSearchTrace(trace) {
  const name = String(trace?.toolName || trace?.displayName || "").toLowerCase();
  const extendedName = String([
    trace?.toolName,
    trace?.displayName,
    trace?.serviceName,
    trace?.serviceId
  ].filter(Boolean).join(" ")).toLowerCase();
  if (
    extendedName.includes("search_web")
    || extendedName.includes("browser")
    || extendedName.includes("serp")
    || extendedName.includes("tavily")
    || extendedName.includes("bing")
    || extendedName.includes("google")
    || extendedName.includes("网页")
    || extendedName.includes("联网搜索")
    || traceOutputHasWebPages(trace)
  ) {
    return true;
  }
  return name.includes("web_search") || name.includes("web search") || name.includes("联网搜索");
}

function isDocumentSearchTrace(trace) {
  const name = String(trace?.toolName || trace?.displayName || "").toLowerCase();
  return name.includes("document_search") || name.includes("document search") || name.includes("文档检索");
}

function parseTraceOutput(output) {
  if (!output) {
    return {};
  }
  if (typeof output === "object") {
    return output;
  }
  try {
    return JSON.parse(output);
  } catch (error) {
    return {};
  }
}

function traceOutputHasWebPages(trace) {
  const containers = referenceContainers(parseTraceOutput(trace?.output));
  return containers.some((item) => [
    item?.results,
    item?.items,
    item?.organic_results,
    item?.webPages,
    item?.pageExcerpts,
    item?.evidenceSnippets
  ].some((value) => Array.isArray(value) && value.some(hasWebUrl)));
}

function hasWebUrl(item) {
  const value = item?.url || item?.link || item?.href || item?.sourceUrl;
  return /^https?:\/\//i.test(String(value || ""));
}

