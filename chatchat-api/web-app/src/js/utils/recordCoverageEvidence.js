const COVERAGE_HEADING_RE = /^(?:证据\s*[·:\-—]\s*)?全量记录覆盖分析$/i;

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
