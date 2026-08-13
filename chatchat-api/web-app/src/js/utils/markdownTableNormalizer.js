const TABLE_DELIMITER_ROW = /^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$/;

function isPipeRow(line = "") {
  const value = String(line || "").trim();
  return value.includes("|") && (value.match(/\|/g) || []).length >= 2;
}

export function hasMarkdownTable(source = "") {
  const lines = String(source || "").replace(/\r\n/g, "\n").split("\n");
  return lines.some((line, index) => isPipeRow(line) && TABLE_DELIMITER_ROW.test(lines[index + 1] || ""));
}

export function normalizeMarkdownTables(source = "") {
  const lines = String(source || "").replace(/\r\n/g, "\n").split("\n");
  const normalized = [];
  lines.forEach((line, index) => {
    const startsTable = isPipeRow(line) && TABLE_DELIMITER_ROW.test(lines[index + 1] || "");
    const previous = normalized.at(-1) || "";
    if (startsTable && previous.trim() && isPipeRow(previous)) {
      // The previous row can only be table content if a delimiter was already emitted.
      const alreadyInsideTable = normalized.some((candidate, candidateIndex) =>
        candidateIndex >= Math.max(0, normalized.length - 3) && TABLE_DELIMITER_ROW.test(candidate));
      if (!alreadyInsideTable) normalized.push("");
    } else if (startsTable && previous.trim()) {
      normalized.push("");
    }
    normalized.push(line);
  });
  return normalized.join("\n");
}
