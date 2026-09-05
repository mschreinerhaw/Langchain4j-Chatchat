// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick } from 'vue';
import AnalyticalReport from '../../components/AnalyticalReport.vue';
import ChatMessageList from '../components/ChatMessageList.js';

vi.mock('../../components/VisualizationRenderer.vue', () => ({ default: {
  props: ['spec'], setup: (props) => () => h('div', { class: 'test-chart' }, JSON.stringify(props.spec.dataset.rows))
} }));
let app;
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; });
const block = {
  id: 'F1', section: 'CORE', question: '规模分布', observation: '头部与尾部存在差异',
  interpretation: '需要分层比较', implication: '避免仅使用平均值', confidence: 'HIGH', caveats: ['存量不是流量'],
  evidence: [{ artifactId: 'fact-1', sourceScope: 'dataset-a', text: 'Source fact', recordRefs: ['d.records[1]'], supportingValues: ['20.500'] }],
  data: { title: '规模', metric: '30.625', metricUnit: '万份', unit: '万份', scope: '已返回样本',
    rows: [{ entity: '005678', value: '20.500' }, { entity: '001234', value: '10.125' }] },
  presentation: { primaryConclusion: true, primaryPresentation: 'CHART', showDataTable: true,
    showKeyMetrics: true, validationStatus: 'VERIFIED_DATA_BOUND' },
};
async function mount(blocks) {
  const root = document.createElement('div'); document.body.append(root);
  app = createApp(AnalyticalReport, { report: { schemaVersion: 'analytical_report.v1', decisionQuestion: '分析问题', blocks } });
  app.mount(root); await nextTick(); return root;
}

describe('structured analytical report', () => {
  it('renders graph, exact data, judgment, implication and evidence together', async () => {
    const root = await mount([{ ...block, visualization: { type: 'chart', dataset: { rows: block.data.rows } } }]);
    expect(root.querySelectorAll('.analytical-insight')).toHaveLength(1);
    expect(root.querySelector('.test-chart').textContent).toContain('20.500');
    expect(root.querySelector('tbody').textContent).toContain('00567820.500');
    expect(root.textContent).toContain('避免仅使用平均值');
    expect(root.querySelector('.insight-evidence').textContent).toContain('d.records[1]');
    expect(root.querySelectorAll('.insight-executive li')).toHaveLength(1);
  });

  it('does not show unbound data as an executive judgment or graph', async () => {
    const root = await mount([{ ...block, presentation: { ...block.presentation, validationStatus: 'INSUFFICIENT_DATA' },
      visualization: { type: 'chart', dataset: { rows: block.data.rows } } }]);
    expect(root.querySelector('.test-chart')).toBeNull();
    expect(root.querySelector('table')).toBeNull();
    expect(root.querySelector('.insight-executive')).toBeNull();
    expect(root.textContent).toContain('待补充可验证数据');
  });

  it('renders model prose as text, not executable markup', async () => {
    const root = await mount([{ ...block, observation: '<img src=x onerror=alert(1)>' }]);
    expect(root.querySelector('img')).toBeNull();
    expect(root.textContent).toContain('<img src=x onerror=alert(1)>');
  });

  it('supports the non-externalized chat response path', () => {
    const report = { schemaVersion: 'analytical_report.v1', blocks: [block] };
    expect(ChatMessageList.methods.messageAnalyticalReport.call({ extractUiResponse: () => ({ analyticalReport: report }) }, {})).toBe(report);
  });
});
