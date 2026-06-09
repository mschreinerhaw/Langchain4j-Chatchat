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
      title: item.title || item.name || item.source || item.url || item.link || "网页",
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

function shortSnippet(value) {
  const normalized = String(value || "").replace(/\s+/g, " ").trim();
  if (!normalized) {
    return "";
  }
  return normalized.length > 120 ? `${normalized.slice(0, 120)}...` : normalized;
}

function isWebSearchTrace(trace) {
  const name = String(trace?.toolName || trace?.displayName || "").toLowerCase();
  return name.includes("web_search") || name.includes("web search") || name.includes("联网搜索");
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
