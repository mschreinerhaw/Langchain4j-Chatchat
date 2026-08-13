const TABLE_DELIMITER_ROW = /^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$/;
const FENCE_ROW = /^\s*(`{3,}|~{3,})/;

function pipeCells(line = "") {
  const value = String(line || "").trim();
  if (!value.includes("|") || (!value.startsWith("|") && !value.endsWith("|"))) return [];
  let content = value;
  if (content.startsWith("|")) content = content.slice(1);
  if (content.endsWith("|")) content = content.slice(0, -1);
  const cells = content.split(/(?<!\\)\|/).map((cell) => cell.trim());
  return cells.length >= 2 ? cells : [];
}

function isPipeRow(line = "") {
  return pipeCells(line).length >= 2;
}

function legacyTableBlockEnd(lines, start) {
  const columnCount = pipeCells(lines[start]).length;
  if (columnCount < 2 || TABLE_DELIMITER_ROW.test(lines[start] || "")) return start;
  let end = start + 1;
  while (end < lines.length
    && !TABLE_DELIMITER_ROW.test(lines[end] || "")
    && pipeCells(lines[end]).length === columnCount) {
    end += 1;
  }
  return end - start >= 2 ? end : start;
}

function delimiterFor(columnCount) {
  return `| ${Array.from({ length: columnCount }, () => "---").join(" | ")} |`;
}

function normalizedTableSource(source = "") {
  const lines = String(source || "").replace(/\r\n/g, "\n").split("\n");
  const normalized = [];
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
      normalized.push(line);
      continue;
    }
    if (fenced) {
      normalized.push(line);
      continue;
    }

    const canonicalTable = isPipeRow(line) && TABLE_DELIMITER_ROW.test(lines[index + 1] || "");
    const legacyEnd = canonicalTable ? index : legacyTableBlockEnd(lines, index);
    if (!canonicalTable && legacyEnd === index) {
      normalized.push(line);
      continue;
    }

    if ((normalized.at(-1) || "").trim()) normalized.push("");
    normalized.push(line);
    if (canonicalTable) {
      const columnCount = pipeCells(line).length;
      normalized.push(lines[index + 1]);
      let rowIndex = index + 2;
      while (rowIndex < lines.length && pipeCells(lines[rowIndex]).length === columnCount) {
        normalized.push(lines[rowIndex]);
        rowIndex += 1;
      }
      index = rowIndex - 1;
    } else {
      normalized.push(delimiterFor(pipeCells(line).length));
      for (let rowIndex = index + 1; rowIndex < legacyEnd; rowIndex += 1) {
        normalized.push(lines[rowIndex]);
      }
      index = legacyEnd - 1;
    }
  }
  return normalized.join("\n");
}

export function hasMarkdownTable(source = "") {
  const normalized = normalizedTableSource(source);
  const lines = normalized.split("\n");
  let fenced = false;
  let fenceMarker = "";
  return lines.some((line, index) => {
    const fence = line.match(FENCE_ROW)?.[1] || "";
    if (fence) {
      if (!fenced) {
        fenced = true;
        fenceMarker = fence[0];
      } else if (fence[0] === fenceMarker) {
        fenced = false;
        fenceMarker = "";
      }
      return false;
    }
    return !fenced && isPipeRow(line) && TABLE_DELIMITER_ROW.test(lines[index + 1] || "");
  });
}

export function normalizeMarkdownTables(source = "") {
  return normalizedTableSource(source);
}
