import MarkdownIt from "markdown-it";

const parser = new MarkdownIt({ html: false });

// Only top-level, complete v2 declarations are rendered. Code examples and malformed blocks stay visible.
export function splitReportVisualizations(markdown = "") {
  const source = String(markdown ?? "");
  const lines = source.replace(/\r\n?/g, "\n").split("\n");
  const parts = [];
  let cursor = 0;
  let count = 0;
  for (const token of parser.parse(source, {})) {
    if (token.type !== "fence" || token.level !== 0 || token.info.trim().toLowerCase() !== "json"
      || !token.map || count >= 6 || token.content.length > 40000) continue;
    const [start, end] = token.map;
    const closing = lines[end - 1]?.trim() || "";
    if (closing[0] !== token.markup[0] || closing.length < token.markup.length
      || [...closing].some((char) => char !== token.markup[0])) continue;
    try {
      const spec = JSON.parse(token.content)?.visualizationSpec;
      if (spec?.schemaVersion !== "visualization_spec.v2"
        || !["bar", "line", "pie", "scatter", "kpi"].includes(spec.chartType)
        || !Array.isArray(spec.dataset?.rows) || !spec.dataset.rows.length
        || spec.dataset.rows.length > 120 || !spec.dataset.xKey
        || !Array.isArray(spec.dataset.series) || !spec.dataset.series.length) continue;
      if (start > cursor) parts.push({ type: "markdown", content: lines.slice(cursor, start).join("\n") });
      parts.push({ type: "visualization", spec });
      cursor = end;
      count++;
    } catch {
      // Local formatting failure never removes prose or unrelated JSON.
    }
  }
  if (!count) return [{ type: "markdown", content: source }];
  if (cursor < lines.length) parts.push({ type: "markdown", content: lines.slice(cursor).join("\n") });
  return parts;
}
