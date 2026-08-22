export function parsePythonExecutionParameters(value) {
  const parsed = JSON.parse(String(value || "{}").trim() || "{}");
  if (parsed === null || Array.isArray(parsed) || typeof parsed !== "object")
    throw new Error("执行参数必须是 JSON 对象，例如 {\"limit\": 100}");
  return parsed;
}

export function formatPythonSource(value) {
  const lines = String(value || "").replace(/\r\n?/g, "\n").split("\n");
  const formatted = [];
  let blankLines = 0;
  for (const original of lines) {
    const leading = original.match(/^[\t ]*/)?.[0] || "";
    const normalizedLeading = leading.replace(/\t/g, "    ");
    const line = `${normalizedLeading}${original.slice(leading.length)}`.replace(/[\t ]+$/g, "");
    if (!line) {
      blankLines += 1;
      if (blankLines <= 2) formatted.push("");
    } else {
      blankLines = 0;
      formatted.push(line);
    }
  }
  while (formatted.length && !formatted.at(-1)) formatted.pop();
  return `${formatted.join("\n")}\n`;
}

export function calculateBottomPanelMaximum(mainHeight) {
  const fixedRowsHeight = 39 + 29 + 24 + 9;
  const minimumEditorHeight = 120;
  const availableHeight = Number(mainHeight) - fixedRowsHeight - minimumEditorHeight;
  return Math.max(100, Math.min(520, Number.isFinite(availableHeight) ? availableHeight : 100));
}
