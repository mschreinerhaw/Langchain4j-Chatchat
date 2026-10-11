import MarkdownIt from "markdown-it";
import { renderableReportBlock, reportVisualizationLimits, reportRendererTypes } from './visualizationCapabilities.js';

const parser = new MarkdownIt({ html: false });
// Recognized declarations never fall back to raw JSON. Binding and verification remain Runtime's responsibility.
const object = value => value && typeof value === 'object' && !Array.isArray(value);
function presentationDeclaration(value) {
  if (!object(value)) return null;
  if (object(value.reportBlock)) return value.reportBlock;
  if (object(value.visualizationSpec)) return value.visualizationSpec;
  if (typeof value.datasetRef === 'string' && typeof value.chartType === 'string'
      && (typeof value.id === 'string' || typeof value.title === 'string')) return value;
  return null;
}
function summaryPart(block, message = '当前展示缺少可用的已校验数据，暂无法生成图表或表格。') {
  return { type: 'visualization-summary', block: {
    title: typeof block.title === 'string' ? block.title : '数据展示',
    conclusion: typeof block.conclusion === 'string' ? block.conclusion : '',
    reason: typeof block.reason === 'string' ? block.reason : ''
  }, message };
}
export function splitReportVisualizations(markdown = "") {
  const source = String(markdown ?? "");
  const lines = source.replace(/\r\n?/g, "\n").split("\n");
  const parts = [];
  let cursor = 0;
  let count = 0;
  const tokens = parser.parse(source, {});
  for (let index = 0; index < tokens.length; index++) {
    const token = tokens[index];
    const language = token.info?.trim().toLowerCase() || '';
    const bare = token.type === 'paragraph_open' && token.level === 0;
    if ((!bare && (token.type !== "fence" || token.level !== 0
      || !['json', 'text', '', 'json:report-block', 'json:visualization'].includes(language)))
      || !token.map) continue;
    const [start, end] = token.map;
    const closing = lines[end - 1]?.trim() || "";
    if (!bare && (closing[0] !== token.markup[0] || closing.length < token.markup.length
      || [...closing].some((char) => char !== token.markup[0]))) continue;
    try {
      const declaration = JSON.parse(bare ? lines.slice(start, end).join('\n') : token.content);
      const recognized = presentationDeclaration(declaration);
      if (!recognized) continue;
      const appendSummary = (message) => {
        if (start > cursor) parts.push({ type: 'markdown', content: lines.slice(cursor, start).join('\n') });
        parts.push(summaryPart(recognized, message)); cursor = end; count++;
      };
      if (count >= reportVisualizationLimits.maxBlocks || (token.content || '').length > 40000) {
        appendSummary('该展示超出当前页面的图表展示限制，已保留内容摘要。'); continue;
      }
      const block = declaration?.reportBlock;
      if (block) {
        if (!renderableReportBlock(block)) { appendSummary(); continue; }
        if (start > cursor) parts.push({ type: 'markdown', content: lines.slice(cursor, start).join('\n') });
        parts.push({ type: 'visualization', spec: block.visualizationSpec, block, slot: `block:${block.id}` });
        cursor = end; count++; continue;
      }
      const spec = declaration?.visualizationSpec;
      if (spec?.schemaVersion !== "visualization_spec.v2"
        || !reportRendererTypes.has(spec.chartType)
        || !Array.isArray(spec.dataset?.rows) || !spec.dataset.rows.length
        || spec.dataset.rows.length > reportVisualizationLimits.maxRows || (spec.chartType !== 'table' && !spec.dataset.xKey)
        || (spec.chartType !== 'table' && (!Array.isArray(spec.dataset.series) || !spec.dataset.series.length))) {
        appendSummary(); continue;
      }
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
