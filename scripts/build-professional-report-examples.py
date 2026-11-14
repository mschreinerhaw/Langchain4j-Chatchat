"""Explicit synthetic regression examples, never substitutes for bank production data."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('reportkit',ROOT/'coding-agent-node/skills/report-designer/scripts/reportkit.py')
kit=importlib.util.module_from_spec(spec);spec.loader.exec_module(kit)

def main():
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);p.add_argument('--echarts',type=Path);args=p.parse_args()
    for name,title,labels,values,sections in [
        ('bank-regression','五家银行股票对比 · 合成回归样例',['测试银行A','测试银行B','测试银行C','测试银行D','测试银行E'],[8,5,None,7,6],[{'title':'收益与估值口径','text':'仅有短期测试观测，不支持长期收益或投资价值判断。PE 按同口径正值升序为 B、E、D、A；C 缺失，不参与排名。'}]),
        ('client-assets','客户资产分析 · 合成泛化样例',['现金','股票','基金'],[20,30,50],[{'title':'资产核对与集中度','text':'合成资产合计100万元。基金占50%；未提供风险等级及现金流，不推断风险适配性，不计算账户投资收益。'}]),
        ('operations','企业运营分析 · 合成泛化样例',['一月','二月','三月'],[100,120,110],[{'title':'经营趋势与局限','text':'测试收入先增后降。没有成本、同期数据及季节性信息，不推断利润变化或增长原因。'}])]:
        out=args.output/name;out.mkdir(parents=True,exist_ok=True)
        source={'labels':labels,'values':values,'dataStatus':'SYNTHETIC_TEST_ONLY'}
        kit.dump(out/'source.json',source)
        model=dict(title=title,reportType=name,period='SYNTHETIC / 2026-Q1',theme='financial-research' if name=='bank-regression' else 'corporate-blue',summary='本报告使用明确标注的合成测试数据验证布局与检查程序，不构成真实业务或投资结论。'+sections[0]['text'],metrics=[],sections=sections,sources=[dict(id='fixture',file='source.json',dataset=name+' synthetic fixture',fields=['labels','values'],period='SYNTHETIC / 2026-Q1',basis='SYNTHETIC_TEST_ONLY',sha256=hashlib.sha256((out/'source.json').read_bytes()).hexdigest())],claims=[dict(id='scope',text=sections[0]['text'],kind='inference',sourceIds=['fixture'])],charts=[dict(id='comparison',purpose='同口径测试PE比较' if name=='bank-regression' else '资产分布' if name=='client-assets' else '月度测试收入',type='bar' if name!='operations' else 'line',period='SYNTHETIC / 2026-Q1',unit='倍' if name=='bank-regression' else '万元',basis='SYNTHETIC_TEST_ONLY',sourceIds=['fixture'],labels=labels,series=[dict(name='合成测试值',values=values)])])
        if name=='bank-regression':model['metrics']=[dict(id='missing-pe',label='测试银行C PE',value=None,unit='倍',sourceIds=['fixture'],limitation='源数据缺失；不补零、不参与排名')]
        else:model['metrics']=[dict(id='total',label='测试资产合计' if name=='client-assets' else '测试季度收入',value=sum(values),unit='万元',sourceIds=['fixture'],formula='sum(values)',check=dict(op='sum',inputs=values))]
        for metric in model['metrics']:
            if metric.get('check'):metric['check']['sourceInputs']=[dict(sourceId='fixture',path=['values',i]) for i in range(len(values))]
        model['charts'][0]['series'][0]['sourceInputs']=[dict(sourceId='fixture',path=['values',i]) for i in range(len(values))]
        kit.build(model,out,args.echarts)
        (out/'before.html').write_text('<!doctype html><meta charset="utf-8"><h1>合成基线，仅用于版式比较</h1><pre>'+json.dumps(source,ensure_ascii=False)+'</pre>',encoding='utf-8')
    print('Generated three explicitly synthetic report bundles')

if __name__=='__main__':main()
