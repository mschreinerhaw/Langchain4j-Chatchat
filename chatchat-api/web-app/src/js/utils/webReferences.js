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
    .map((item, index) => {
      const evidence = item?.evidence && typeof item.evidence === "object" ? item.evidence : {};
      return {
      rank: item.rank || item.position || index + 1,
      title: item.title || item.name || evidence.title || item.sourceName || item.source || item.url || item.link || "引用",
      publisher: item.publisher || item.siteName || item.sourceName || evidence.publisher || evidence.siteName || evidence.sourceName || "",
      publishDate: item.publishDate || item.publishedAt || item.publishTime || item.publish_time || evidence.publishDate || evidence.publishedAt || evidence.publishTime || "",
      url: item.url || item.link || item.href || item.sourceUrl || item.source_url || evidence.url || evidence.sourceUrl || "",
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
    };
    })
    .filter((item) => item.url || item.title);
  if (!referenceUrls?.length) {
    return uniquePages(pages).slice(0, 12);
  }
  const pagesByUrl = new Map(pages.filter((page) => page.url).map((page) => [page.url, page]));
  return uniquePages(referenceUrls
    .map((url, index) => {
      const matched = pagesByUrl.get(url) || {};
      return {
        rank: index + 1,
        title: matched.title || url,
        publisher: matched.publisher || "",
        publishDate: matched.publishDate || "",
        url,
        snippet: matched.snippet || ""
      };
    })
    .filter((item) => item.url || item.title))
    .slice(0, 12);
}

export function extractWebSearchPagesFromTraces(traces = []) {
  if (!Array.isArray(traces)) {
    return [];
  }
  return uniquePages(traces.flatMap((trace) => extractWebSearchPages(trace)));
}

export function extractWebCitationPages(citations = []) {
  if (!Array.isArray(citations)) {
    return [];
  }
  return uniquePages(citations
    .map((item, index) => {
      const sourceRef = item?.sourceRef || item?.refId || "";
      const source = item?.source || "";
      const url = firstSafeWebUrl(
        item?.url,
        item?.link,
        item?.href,
        item?.sourceUrl,
        item?.source_url,
        sourceRef,
        source
      );
      const publisher = cleanSourceLabel(
        item?.publisher || item?.siteName || item?.sourceName || item?.organization || (url ? source : ""),
        url
      );
      const title = cleanSourceLabel(
        item?.title || item?.sourceTitle || item?.name || (url ? "" : source),
        url
      );
      return {
        isWeb: Boolean(url) || /^web:\/\//i.test(String(sourceRef || source || "")),
        rank: Number(item?.rank) || index + 1,
        title: title || publisher || displayUrl(url) || `来源 ${index + 1}`,
        publisher,
        url,
        publishDate: item?.publishDate || item?.publishedAt || item?.publish_time || "",
        accessedAt: item?.accessedAt || item?.accessTime || "",
        snippet: shortSnippet(
          item?.snippet || item?.text || item?.summary || item?.description || item?.content
        )
      };
    })
    .filter((item) => item.isWeb)
    .map(({ isWeb, ...item }) => item));
}

/**
 * Replaces internal evidence labels such as [网页3] with readable source tags.
 * Stored answers keep their original labels for evidence auditing.
 */
export function inlineWebCitationLinks(content, pages = []) {
  const text = String(content || "");
  if (!text || !Array.isArray(pages)) {
    return { content: text, citationUrls: [] };
  }
  const marker = /(?:\[(?:网页|網頁|source|ref|citation)\s*(\d+)\]|【(?:网页|網頁)\s*(\d+)】|\b(?:source|ref|citation)\s*(\d+)\b)(?:\(\s*<?(https?:\/\/[^)\s>]+)>?(?:\s+["'][^)]*["'])?\s*\))?/gi;
  const citationUrls = [];
  let output = "";
  let cursor = 0;
  let match;
  while ((match = marker.exec(text)) !== null) {
    output += text.slice(cursor, match.index);
    cursor = marker.lastIndex;
    const rank = Number(match[1] || match[2] || match[3]);
    const page = pages.find((item) => Number(item?.rank) === rank) || pages[rank - 1] || {};
    const url = safeWebUrl(page.url) || safeWebUrl(match[4]);
    const sourceLabel = page.publisher || page.title || displayUrl(url) || `来源 ${rank}`;
    const label = escapeMarkdownLinkText(sourceLabel);
    if (url) {
      citationUrls.push(url);
      const title = escapeMarkdownTitle(page.title || sourceLabel);
      output += `[${label}](<${url}> "${title}") `;
    } else {
      output += `${label} `;
    }
  }
  output += text.slice(cursor);
  output = output.replace(/(\]\(<https?:\/\/[^)]+\))\s+([,.;:!?，。；：！？])/g, "$1$2");
  return { content: output, citationUrls: [...new Set(citationUrls)] };
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
    .map((value) => cleanInternalDocumentRefs(value))
    .find((value) => isReadableDocumentTitle(value, normalizedDocId, fileId));
  return title || "\u5f15\u7528\u6587\u6863";
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

function safeWebUrl(value) {
  try {
    const parsed = new URL(String(value || "").trim());
    return ["http:", "https:"].includes(parsed.protocol) ? parsed.href : "";
  } catch (error) {
    return "";
  }
}

function firstSafeWebUrl(...values) {
  return values.map((value) => safeWebUrl(value)).find(Boolean) || "";
}

function cleanSourceLabel(value, url = "") {
  const label = String(value || "").replace(/\s+/g, " ").trim();
  if (!label || /^https?:\/\//i.test(label) || label === url) {
    return "";
  }
  return label.length > 36 ? `${label.slice(0, 36)}…` : label;
}

function escapeMarkdownLinkText(value) {
  return String(value || "").replace(/\\/g, "\\\\").replace(/\[/g, "\\[").replace(/\]/g, "\\]");
}

function escapeMarkdownTitle(value) {
  return String(value || "").replace(/"/g, "&quot;");
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
  const normalized = cleanInternalDocumentRefs(value).replace(/\s+/g, " ").trim();
  if (!normalized) {
    return "";
  }
  return normalized.length > 120 ? `${normalized.slice(0, 120)}...` : normalized;
}

function cleanInternalDocumentRefs(value) {
  return String(value || "")
    .replace(/[\uFF08(]?\s*doc:\/\/[^\s\uFF09)\]}\>\uFF0C\u3002\uFF1B;]+[\uFF09)]?\s*[:\uFF1A]?/gi, "")
    .trim();
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

