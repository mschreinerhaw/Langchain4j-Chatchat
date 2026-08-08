export function parseChartNumber(value) {
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : null;
  }
  const text = String(value ?? "").trim();
  if (!text) {
    return null;
  }
  const parsed = Number(text.replace(/,/g, ""));
  return Number.isFinite(parsed) ? parsed : null;
}

export function isNumericChartColumn(rows = [], column = "") {
  if (!column || !Array.isArray(rows)) {
    return false;
  }
  const values = rows
    .map((row) => row?.[column])
    .filter((value) => {
      const text = String(value ?? "").trim();
      return text && !/^(?:-|—|–|无|暂无|N\/?A|null)$/i.test(text);
    });
  return values.length > 0 && values.every((value) => parseChartNumber(value) !== null);
}

export function selectChartMetricKey(columns = [], rows = [], xKey = "", requestedKey = "") {
  if (requestedKey && requestedKey !== xKey && isNumericChartColumn(rows, requestedKey)) {
    return requestedKey;
  }
  return columns.find((column) => column !== xKey && isNumericChartColumn(rows, column))
    || requestedKey
    || columns.find((column) => column !== xKey)
    || xKey
    || "";
}

export function coerceChartMetricRows(rows = [], metricKey = "") {
  if (!isNumericChartColumn(rows, metricKey)) {
    return rows.map((row) => ({ ...row }));
  }
  return rows.map((row) => {
    const parsed = parseChartNumber(row?.[metricKey]);
    return parsed === null ? { ...row } : { ...row, [metricKey]: parsed };
  });
}
