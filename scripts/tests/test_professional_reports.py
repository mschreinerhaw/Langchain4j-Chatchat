import importlib.util
import hashlib
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('reportkit', ROOT/'coding-agent-node/skills/report-designer/scripts/reportkit.py')
kit=importlib.util.module_from_spec(spec);spec.loader.exec_module(kit)

class ProfessionalReportsTest(unittest.TestCase):
    def fixture(self, root):
        (root/'source.json').write_text('[10,12]',encoding='utf-8')
        return dict(title='测试 <script>bad()</script>',reportType='stock-comparison',period='2026-11-01/2026-11-02',summary='仅两次观测，不支持长期判断',sources=[dict(id='s1',file='source.json',dataset='synthetic',fields=['price'],period='two observations',basis='unadjusted synthetic',sha256=hashlib.sha256((root/'source.json').read_bytes()).hexdigest())],metrics=[dict(id='r',label='区间收益',value=.2,unit='ratio',sourceIds=['s1'],formula='last/first-1',check=dict(op='return',inputs=[10,12]))],claims=[dict(id='c1',text='测试区间上涨20%',kind='calculation',sourceIds=['s1'],metricId='r')],charts=[dict(id='price',purpose='短期观测比较',type='line',period='two observations',unit='CNY',basis='synthetic',sourceIds=['s1'],labels=['day1','day2'],series=[dict(name='A',values=[10,None])])])

    def test_bundle_escapes_content_and_preserves_missing(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);m=self.fixture(root);q=kit.build(m,root)
            self.assertEqual(q['errors'],[])
            self.assertEqual(q['status'],'INCOMPLETE')
            self.assertIn('null',(root/'charts/chart-0.json').read_text(encoding='utf-8'))
            self.assertNotIn('<script>bad()', (root/'report.html').read_text(encoding='utf-8'))
            for f in ['report.html','report_manifest.json','evidence.json','quality_report.json']:self.assertTrue((root/f).is_file())

    def test_wrong_pe_rank_short_horizon_and_source_tamper_fail(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);m=self.fixture(root)
            m['metrics'].append(dict(id='pe',value=[0,1],unit='rank',sourceIds=['s1'],check=dict(op='rank',inputs=[8,5])))
            m['claims'][0].update(horizon='long-term',observations=2)
            (root/'source.json').write_text('tampered')
            q=kit.validate(m,root)
            self.assertEqual(q['status'],'FAILED')
            for text in ['ranking mismatch','long-term','hash mismatch']:self.assertTrue(any(text in e for e in q['errors']))

    def test_incomparable_and_missing_inputs(self):
        for op,inputs in [('return',[None,1]),('return',[-1,2]),('return',[1]),('rank',[8,-1]),('ratio',[1,0])]:
            with self.assertRaises((ValueError,ZeroDivisionError)):kit.calculate(op,inputs)

    def test_correct_math_on_fabricated_inputs_and_zero_filled_chart_fail(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);m=self.fixture(root)
            metric=m['metrics'][0];metric['value']=98.9
            metric['check'].update(inputs=[10,999],sourceInputs=[dict(sourceId='s1',path=[0]),dict(sourceId='s1',path=[1])])
            series=m['charts'][0]['series'][0];series.update(values=[10,0],sourceInputs=[dict(sourceId='s1',path=[0]),None])
            q=kit.validate(m,root)
            self.assertTrue(any('source cell mismatch' in e for e in q['errors']))
            self.assertTrue(any('chart differs from source cells' in e for e in q['errors']))

    def test_coordinate_charts_compare_nested_source_values(self):
        self.assertTrue(kit.equivalent([[0,1,2],[1,0,None]],[[0,1,2],[1,0,None]]))
        self.assertFalse(kit.equivalent([[0,1,None]],[[0,1,0]]))

if __name__=='__main__':unittest.main()
