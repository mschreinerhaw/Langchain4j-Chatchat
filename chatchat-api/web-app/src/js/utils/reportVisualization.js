import MarkdownIt from "markdown-it";
import { renderableReportBlock } from './visualizationCapabilities.js';

const parser = new MarkdownIt({ html: false });
// Only top-level, complete v2 declarations are rendered. Code examples and malformed blocks stay visible.
export function splitReportVisualizations(markdown = "") {
  const source = String(markdown ?? "");
  const lines = source.replace(/\r\n?/g, "\n").split("\n");
  const parts = [];
  let cursor = 0;
  let count = 0;
  const tokens = parser.parse(source, {});
  for (let index = 0; index < tokens.length; index++) {
    const token = tokens[index];
    if (token.type !== "fence" || token.level !== 0 || token.info.trim().toLowerCase() !== "json"
      || !token.map || count >= 6 || token.content.length > 40000) continue;
    const [start, end] = token.map;
    const closing = lines[end - 1]?.trim() || "";
    if (closing[0] !== token.markup[0] || closing.length < token.markup.length
      || [...closing].some((char) => char !== token.markup[0])) continue;
    try {
      const declaration = JSON.parse(token.content);
      const block = declaration?.reportBlock;
      if (block) {
        if (!renderableReportBlock(block)) continue;
        if (start > cursor) parts.push({ type: 'markdown', content: lines.slice(cursor, start).join('\n') });
        parts.push({ type: 'visualization', spec: block.visualizationSpec, block, slot: `block:${block.id}` });
        cursor = end; count++; continue;
      }
      const spec = declaration?.visualizationSpec;
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
