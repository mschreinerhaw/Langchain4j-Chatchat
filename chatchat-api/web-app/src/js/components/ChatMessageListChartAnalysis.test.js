import { describe, it, expect, vi } from 'vitest';
import mixin from './ChatMessageListChartAnalysis.js';
import { parseChartNumber, selectChartMetricKey } from '../utils/chartDatasetTypes.js';

function editor(preferences = {}) {
  const context = { ...mixin.data(), visualizationPreference: (_, slot) => preferences[slot],
    handleVisualizationPreference: vi.fn(), stopChartAnalysisDrag: vi.fn() };
  for (const [name, method] of Object.entries(mixin.methods)) {
    if (name !== 'stopChartAnalysisDrag') context[name] = method.bind(context);
  }
  return context;
}
const payload = encodeURIComponent(JSON.stringify({ title: 'Conclusion', slot: 'table:sample',
  columns: ['period', 'value', 'segment'], rows: [{ period: 'A', value: 5, segment: 'X' }], dataRole: 'raw_data' }));

describe('report chart choices', () => {
  it('respects selected metrics and percentage units without substituting another measure', () => {
    const rows = [{ count: 20, change: '-1.5%' }, { count: 10, change: '2%' }];
    expect(selectChartMetricKey(['count', 'change'], rows, 'count', 'change')).toBe('change');
    expect(parseChartNumber(rows[0].change)).toBe(-1.5);
    const context = editor();
    expect(context.chartAnalysisSeries({ rows }, 'line', 'count', 'change')[0].unit).toBe('%');
    const scatter = context.chartAnalysisRows({ rows: [{ x: 1, y: null }, { x: 2, y: 3 }] }, 'scatter', 'x', 'y');
    expect(scatter).toEqual([{ x: 2, y: 3 }]);
  });
  it('saves axes, grouping, columns and chart type and restores them on reopen', () => {
    const context = editor();
    const message = { id: 'answer' };
    context.openChartAnalysisModal(payload, message);
    expect(context.chartAnalysisActiveDataset().chartType).toBe('table');
    context.updateChartAnalysisDataset({ chartType: 'line', xKey: 'segment', yKey: 'value',
      groupKey: 'period', selectedColumns: ['segment', 'value'] });
    const saved = context.handleVisualizationPreference.mock.calls[0][1];
    expect(saved).toMatchObject({ slot: 'table:sample', preference: { view: 'graph', chartType: 'line',
      xKey: 'segment', yKey: 'value', groupKey: 'period', selectedColumns: ['segment', 'value'] } });
    context.closeChartAnalysisModal();
    context.openChartAnalysisModal(payload, message);
    expect(context.chartAnalysisActiveDataset()).toMatchObject({ chartType: 'line', xKey: 'segment', groupKey: 'period', selectedColumns: ['segment', 'value'] });
    const reloaded = editor({ 'table:sample': saved.preference });
    reloaded.openChartAnalysisModal(payload, message);
    expect(reloaded.chartAnalysisActiveDataset()).toMatchObject({ chartType: 'line', xKey: 'segment', groupKey: 'period' });
  });
});

