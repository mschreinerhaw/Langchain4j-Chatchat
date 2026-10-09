const COVERAGE_HEADING_RE = /^(?:证据\s*[·:\-—]\s*)?全量记录覆盖分析$/i;

export function collapseQueryResultDetailsHtml(html = "") {
  if (typeof DOMParser === "undefined") return html;
  const doc = new DOMParser().parseFromString(String(html || ""), "text/html");
  doc.querySelectorAll("h1,h2,h3,h4,h5,h6").forEach((heading) => {
    if (!/^(?:查询结果明细|数据明细|原始数据明细)$/.test(heading.textContent.trim())
      || heading.closest(".query-result-evidence")) return;
    const level = Number(heading.tagName.slice(1));
    const details = doc.createElement("details");
    details.className = "query-result-evidence";
    details.dataset.reportTitle = heading.textContent.trim();
    const summary = doc.createElement("summary");
    summary.textContent = "事实核对 · 查询明细";
    const body = doc.createElement("div");
    body.className = "query-result-evidence-body";
    let next = heading.nextElementSibling;
    heading.replaceWith(details);
    details.append(summary, body);
    while (next && !(/^H[1-6]$/.test(next.tagName) && Number(next.tagName.slice(1)) <= level)) {
      const sibling = next.nextElementSibling;
      body.append(next);
      next = sibling;
    }
  });
  return doc.body.innerHTML;
}

function headingText(value = "") {
  return String(value)
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;|&#160;/gi, " ")
    .replace(/&middot;|&#183;/gi, "·")
    .replace(/\s+/g, " ")
    .trim();
}

/**
 * Turns the backend's record-coverage appendix into subordinate, opt-in evidence.
 * The answer itself stays prominent while the complete audit trail remains available.
 */
export function collapseRecordCoverageEvidenceHtml(html = "") {
  let source = String(html || "");
  const headingPattern = /<h([1-6])(?:\s[^>]*)?>([\s\S]*?)<\/h\1>/gi;
  const headings = [...source.matchAll(headingPattern)];
  const sections = headings
    .map((heading, index) => {
      if (!COVERAGE_HEADING_RE.test(headingText(heading[2]))) {
        return null;
      }
      const level = Number(heading[1]);
      const nextBoundary = headings
        .slice(index + 1)
        .find((candidate) => Number(candidate[1]) <= level);
      const end = nextBoundary?.index ?? source.length;
      return {
        start: heading.index,
        end,
        body: source.slice(heading.index + heading[0].length, end).trim()
      };
    })
    .filter(Boolean);

  [...sections].reverse().forEach((section) => {
    const collapsed = [
      '<details class="record-coverage-evidence">',
      '<summary><span>证据 · 全量记录覆盖分析</span><small>点击查看</small></summary>',
      `<div class="record-coverage-evidence-body">${section.body}</div>`,
      '</details>'
    ].join("");
    source = `${source.slice(0, section.start)}${collapsed}${source.slice(section.end)}`;
  });

  return source;
}

const TOOL_EVIDENCE_HEADING_RE = /^(?:证据\s*[·:\-—]\s*)?工具(?:执行|调用|运行)?(?:证据|证明|链路|结果)$/i;

/**
 * Artifact-backed history messages bypass ChatMessageList's richer evidence-card
 * formatter. Keep their tool appendix opt-in as well, without changing content.
 */
export function collapseToolExecutionEvidenceHtml(html = "") {
  let source = String(html || "");
  const headingPattern = /<h([1-6])(?:\s[^>]*)?>([\s\S]*?)<\/h\1>/gi;
  const headings = [...source.matchAll(headingPattern)];
  const sections = headings
    .map((heading, index) => {
      if (!TOOL_EVIDENCE_HEADING_RE.test(headingText(heading[2]))) {
        return null;
      }
      const level = Number(heading[1]);
      const nextBoundary = headings
        .slice(index + 1)
        .find((candidate) => Number(candidate[1]) <= level);
      const end = nextBoundary?.index ?? source.length;
      return {
        start: heading.index,
        end,
        body: source.slice(heading.index + heading[0].length, end).trim()
      };
    })
    .filter(Boolean);

  [...sections].reverse().forEach((section) => {
    const itemCount = (section.body.match(/<li(?:\s[^>]*)?>/gi) || []).length;
    const summaryMeta = itemCount ? `${itemCount} 条` : "点击查看";
    const collapsed = [
      '<details class="tool-evidence-details">',
      `<summary><span>证据 · 工具执行证据</span><small>${summaryMeta}</small></summary>`,
      `<div class="tool-evidence-body">${section.body}</div>`,
      '</details>'
    ].join("");
    source = `${source.slice(0, section.start)}${collapsed}${source.slice(section.end)}`;
  });

  return source;
}
