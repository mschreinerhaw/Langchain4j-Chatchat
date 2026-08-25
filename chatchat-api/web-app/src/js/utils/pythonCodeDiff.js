function sourceLines(value) {
  const normalized = String(value || "").replace(/\r\n?/g, "\n");
  return normalized ? normalized.split("\n") : [];
}

function changedMiddle(before, after, maxCells) {
  if (!before.length) return after.map((content) => ({ type: "added", content }));
  if (!after.length) return before.map((content) => ({ type: "deleted", content }));
  if (before.length * after.length > maxCells) {
    return [
      ...before.map((content) => ({ type: "deleted", content })),
      ...after.map((content) => ({ type: "added", content }))
    ];
  }

  const matrix = Array.from({ length: before.length + 1 }, () => new Uint32Array(after.length + 1));
  for (let oldIndex = before.length - 1; oldIndex >= 0; oldIndex -= 1) {
    for (let newIndex = after.length - 1; newIndex >= 0; newIndex -= 1) {
      matrix[oldIndex][newIndex] = before[oldIndex] === after[newIndex]
        ? matrix[oldIndex + 1][newIndex + 1] + 1
        : Math.max(matrix[oldIndex + 1][newIndex], matrix[oldIndex][newIndex + 1]);
    }
  }

  const operations = [];
  let oldIndex = 0;
  let newIndex = 0;
  while (oldIndex < before.length && newIndex < after.length) {
    if (before[oldIndex] === after[newIndex]) {
      operations.push({ type: "context", content: before[oldIndex] });
      oldIndex += 1;
      newIndex += 1;
    } else if (matrix[oldIndex + 1][newIndex] >= matrix[oldIndex][newIndex + 1]) {
      operations.push({ type: "deleted", content: before[oldIndex] });
      oldIndex += 1;
    } else {
      operations.push({ type: "added", content: after[newIndex] });
      newIndex += 1;
    }
  }
  while (oldIndex < before.length) operations.push({ type: "deleted", content: before[oldIndex++] });
  while (newIndex < after.length) operations.push({ type: "added", content: after[newIndex++] });
  return operations;
}

function numberOperations(operations) {
  let oldLine = 1;
  let newLine = 1;
  return operations.map((operation) => {
    const numbered = {
      ...operation,
      oldLine: operation.type === "added" ? null : oldLine,
      newLine: operation.type === "deleted" ? null : newLine
    };
    if (operation.type !== "added") oldLine += 1;
    if (operation.type !== "deleted") newLine += 1;
    return numbered;
  });
}

function compactContext(operations, contextSize) {
  const changedIndexes = operations
    .map((operation, index) => operation.type === "context" ? -1 : index)
    .filter((index) => index >= 0);
  if (!changedIndexes.length) return operations.slice(0, Math.max(1, contextSize * 2));

  const visible = new Set();
  changedIndexes.forEach((index) => {
    for (let candidate = Math.max(0, index - contextSize);
      candidate <= Math.min(operations.length - 1, index + contextSize);
      candidate += 1) {
      visible.add(candidate);
    }
  });

  const compacted = [];
  let omitted = 0;
  operations.forEach((operation, index) => {
    if (!visible.has(index)) {
      omitted += 1;
      return;
    }
    if (omitted) {
      compacted.push({ type: "omitted", count: omitted, content: `… 已折叠 ${omitted} 行未变更代码 …` });
      omitted = 0;
    }
    compacted.push(operation);
  });
  if (omitted) compacted.push({ type: "omitted", count: omitted, content: `… 已折叠 ${omitted} 行未变更代码 …` });
  return compacted;
}

export function buildPythonLineDiff(beforeSource, afterSource, options = {}) {
  const before = sourceLines(beforeSource);
  const after = sourceLines(afterSource);
  const maxCells = Math.max(10_000, Number(options.maxCells) || 400_000);
  const contextSize = Math.max(0, Number(options.contextSize) || 3);

  let prefix = 0;
  while (prefix < before.length && prefix < after.length && before[prefix] === after[prefix]) prefix += 1;
  let suffix = 0;
  while (suffix < before.length - prefix
    && suffix < after.length - prefix
    && before[before.length - 1 - suffix] === after[after.length - 1 - suffix]) suffix += 1;

  const operations = [
    ...before.slice(0, prefix).map((content) => ({ type: "context", content })),
    ...changedMiddle(
      before.slice(prefix, before.length - suffix),
      after.slice(prefix, after.length - suffix),
      maxCells
    ),
    ...before.slice(before.length - suffix).map((content) => ({ type: "context", content }))
  ];
  const numbered = numberOperations(operations);
  const additions = numbered.filter((line) => line.type === "added").length;
  const deletions = numbered.filter((line) => line.type === "deleted").length;
  return {
    additions,
    deletions,
    changed: additions > 0 || deletions > 0,
    lines: compactContext(numbered, contextSize)
  };
}
