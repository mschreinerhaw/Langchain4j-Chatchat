import { parseChartNumber } from './chartDatasetTypes.js';

/** Stable per-table identity shared by report charts and the interactive chart editor. */
export function reportTableSlot(title, columns = []) {
  let hash = 2166136261;
  for (const char of JSON.stringify([title, columns])) hash = Math.imul(hash ^ char.charCodeAt(0), 16777619);
  return `table:${(hash >>> 0).toString(16)}`;
}

export function applyReportChartPreference(spec, preference = {}) {
  const columns = spec?.dataset?.columns || [];
  const dataset = { ...spec.dataset };
  if (columns.includes(preference.xKey)) dataset.xKey = preference.xKey;
  if (columns.includes(preference.yKey)) {
    const existing = dataset.series?.find(series => series.yKey === preference.yKey);
    const unit = dataset.rows.every(row => String(row[preference.yKey] ?? '').trim().endsWith('%')) ? '%' : (existing?.unit || '');
    dataset.series = [{ ...existing, name: preference.yKey, yKey: preference.yKey, unit }];
    dataset.rows = dataset.rows.map(row => ({ ...row, [preference.yKey]: parseChartNumber(row[preference.yKey]) }));
  }
  if (columns.includes(preference.groupKey) && !['pie', 'scatter'].includes(preference.chartType || spec.chartType)) {
    const yKey = dataset.series?.[0]?.yKey;
    const groups = [...new Set(dataset.rows.map(row => String(row[preference.groupKey] ?? '')))];
    const grouped = new Map();
    for (const row of dataset.rows) {
      const value = parseChartNumber(row[yKey]);
      if (value === null) continue;
      const key = row[dataset.xKey];
      const target = grouped.get(key) || { [dataset.xKey]: key };
      const groupKey = `group:${groups.indexOf(String(row[preference.groupKey] ?? ''))}`;
      target[groupKey] = (target[groupKey] || 0) + value;
      grouped.set(key, target);
    }
    dataset.series = groups.map((name, index) => ({ name, yKey: `group:${index}` }));
    dataset.rows = [...grouped.values()];
  }
  return { ...spec, dataset };
}
