import MarkdownIt from "markdown-it";

const markdownTableParser = new MarkdownIt({ html: false, linkify: false, typographer: false });
const STRICT_DELIMITER_ROW = /^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$/;
const FENCE_ROW = /^\s*(`{3,}|~{3,})/;
const DELIMITER_CELL = /^:?-+:?$/;

function pipeCells(line = "") {
  const value = String(line || "").trim().replaceAll("｜", "|");
  if (!value.includes("|")) return [];
  let content = value;
  if (content.startsWith("|")) content = content.slice(1);
  if (content.endsWith("|")) content = content.slice(0, -1);

  const cells = [];
  let cell = "";
  let escaped = false;
  let codeMarkerLength = 0;
  for (let index = 0; index < content.length; index += 1) {
    const character = content[index];
    if (escaped) {
      cell += character;
      escaped = false;
      continue;
    }
    if (character === "\\") {
      cell += character;
      escaped = true;
      continue;
    }
    if (character === "`") {
      let markerLength = 1;
      while (content[index + markerLength] === "`") markerLength += 1;
      if (!codeMarkerLength) codeMarkerLength = markerLength;
      else if (codeMarkerLength === markerLength) codeMarkerLength = 0;
      cell += "`".repeat(markerLength);
      index += markerLength - 1;
      continue;
    }
    if (character === "|" && !codeMarkerLength) {
      cells.push(cell.trim());
      cell = "";
      continue;
    }
    if (character === "|" && codeMarkerLength) {
      cell += "\\|";
      continue;
    }
    cell += character;
  }
  cells.push(cell.trim());
  return cells.length >= 2 ? cells : [];
}

function isBoundaryPipeRow(line = "") {
  const value = String(line || "").trim();
  return (value.startsWith("|") || value.startsWith("｜"))
    && (value.endsWith("|") || value.endsWith("｜"));
}

function isDelimiterCells(cells = []) {
  return cells.length >= 2 && cells.every((cell) => DELIMITER_CELL.test(cell.replace(/\s/g, "")));
}

function canonicalTable(source = "") {
  return markdownTableParser.parse(String(source || ""), {})
    .some((token) => token.type === "table_open");
}

function delimiterFor(columnCount, sourceCells = []) {
  const cells = Array.from({ length: columnCount }, (_, index) => {
    const value = String(sourceCells[index] || "").replace(/\s/g, "");
    const left = value.startsWith(":");
    const right = value.endsWith(":");
    return `${left ? ":" : ""}---${right ? ":" : ""}`;
  });
  return `| ${cells.join(" | ")} |`;
}

function normalizedRow(cells, columnCount, header = false) {
  const values = Array.from({ length: columnCount }, (_, index) => {
    const value = cells[index] ?? "";
    return header && !String(value).trim() ? `列 ${index + 1}` : value;
  });
  return `| ${values.join(" | ")} |`;
}

function candidateEnd(lines, start) {
  let end = start;
  while (end < lines.length && pipeCells(lines[end]).length >= 2) end += 1;
  return end;
}

function repairCandidate(lines, start) {
  const end = candidateEnd(lines, start);
  const rows = lines.slice(start, end).map(pipeCells);
  if (rows.length < 2 || !rows[0].length) return null;

  const suppliedDelimiter = isDelimiterCells(rows[1]);
  const dataRows = rows.slice(suppliedDelimiter ? 2 : 1);
  const boundaryRows = lines.slice(start, end).filter(isBoundaryPipeRow).length;
  if (!suppliedDelimiter && (dataRows.length < 1 || boundaryRows !== rows.length)) return null;

  const columnCount = Math.max(...rows.filter((_, index) => index !== 1 || !suppliedDelimiter).map((row) => row.length));
  if (columnCount < 2) return null;
  const repaired = [
    normalizedRow(rows[0], columnCount, true),
    delimiterFor(columnCount, suppliedDelimiter ? rows[1] : []),
    ...dataRows.map((row) => normalizedRow(row, columnCount))
  ].join("\n");
  return canonicalTable(repaired) ? { source: repaired, end } : null;
}

export function recoverMarkdownTables(source = "") {
  const lines = String(source || "").replace(/\r\n?/g, "\n").split("\n");
  const output = [];
  const repairs = [];
  let fenced = false;
  let fenceMarker = "";

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    const fence = line.match(FENCE_ROW)?.[1] || "";
    if (fence) {
      if (!fenced) {
        fenced = true;
        fenceMarker = fence[0];
      } else if (fence[0] === fenceMarker) {
        fenced = false;
        fenceMarker = "";
      }
      output.push(line);
      continue;
    }
    if (fenced) {
      output.push(line);
      continue;
    }

    if (pipeCells(line).length < 2) {
      output.push(line);
      continue;
    }

    const end = candidateEnd(lines, index);
    const original = lines.slice(index, end).join("\n");
    if (STRICT_DELIMITER_ROW.test(lines[index + 1] || "") && canonicalTable(original)) {
      if ((output.at(-1) || "").trim()) output.push("");
      output.push(original);
      index = end - 1;
      continue;
    }

    const repaired = repairCandidate(lines, index);
    if (!repaired) {
      output.push(line);
      continue;
    }
    if ((output.at(-1) || "").trim()) output.push("");
    output.push(repaired.source);
    repairs.push({ startLine: index + 1, endLine: repaired.end, reason: "invalid_markdown_table" });
    index = repaired.end - 1;
  }

  return {
    content: output.join("\n"),
    repaired: repairs.length > 0,
    repairCount: repairs.length,
    repairs
  };
}

export function hasMarkdownTable(source = "") {
  return canonicalTable(recoverMarkdownTables(source).content);
}

export function normalizeMarkdownTables(source = "") {
  return recoverMarkdownTables(source).content;
}
