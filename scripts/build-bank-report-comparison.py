"""Rebuild the archived bank example from its actual authorized source snapshot."""
import argparse
import hashlib
import importlib.util
import json
import shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('kit',ROOT/'coding-agent-node/skills/report-designer/scripts/reportkit.py');kit=importlib.util.module_from_spec(spec);spec.loader.exec_module(kit)

def main():
    p=argparse.ArgumentParser();p.add_argument('original',type=Path);p.add_argument('output',type=Path);p.add_argument('--echarts',type=Path);a=p.parse_args()
    rows=[json.loads(l) for l in (a.original/'source-1.jsonl').read_text(encoding='utf-8').splitlines()]
    codes=['000001','600036','601166','601398','601939'];rows=[r for r in rows if r.get('quote_code') in codes and r.get('instrument_type')=='EQUITY']
    a.output.mkdir(parents=True,exist_ok=True);kit.dump(a.output/'source.json',rows);shutil.copy2(a.original/'report.html',a.output/'before.html')
    source=dict(id='quotes',file='source.json',dataset='market_quote_daily / archived source-1.jsonl filtered by five equity codes',fields=['quote_code','quote_name','trade_date','close','pe_ratio','source_url','provider'],period='2026-11-10/2026-11-13',basis='交易所归档快照；收盘价复权口径与PE盈余口径未核实',sha256=hashlib.sha256((a.output/'source.json').read_bytes()).hexdigest(),parentSha256=hashlib.sha256((a.original/'source-1.jsonl').read_bytes()).hexdigest())
    source['urls']=sorted({r['source_url'] for r in rows if r.get('source_url') and r.get('trade_date')=='2026-11-13'})
    metrics=[];series=[];dates=['2026-11-10','2026-11-11','2026-11-12','2026-11-13'];coverage=[]
    for code in codes:
        records=sorted([r for r in rows if r['quote_code']==code],key=lambda r:r['trade_date']);name=records[0]['quote_name'];first,last=records[0]['close'],records[-1]['close'];change=last/first-1
        metrics.append(dict(id=code,label=name+' · 快照价格变化',value=change,unit='ratio',sourceIds=['quotes'],formula='last_close/first_close-1',check=dict(op='return',inputs=[first,last]),limitation='仅覆盖'+str(len(records))+'次观测；非总回报，不外推长期收益'))
        bydate={r['trade_date']:r for r in records};series.append(dict(name=name,values=[bydate[d]['close']/first*100 if d in bydate else None for d in dates]));coverage.append(name+': '+str(len(records))+'次观测，最新PE='+('缺失' if records[-1].get('pe_ratio') is None else str(records[-1]['pe_ratio'])))
    model=dict(title='五家银行股票表现对比分析',reportType='stock-comparison',theme='financial-research',period=source['period'],summary='数据只支持11月10日至13日的快照价格比较，不足以形成最近一年收益排序。只有平安银行有PE=5.84，无法判断五家银行中谁估值最低。沪市四家银行缺少11月12日观测，保留断点。',sources=[source],metrics=metrics,sections=[dict(title='核心修正与分析边界',text='删除原报告“最近一年综合表现”排序及“平安银行PE最低/安全边际”判断。未取得完整长期复权价格、分红、可比PE/PB/ROE，不计算长期收益、年化波动或投资价值排名。新闻片段未核对原文、发布日期和完整期间，不能替代行情序列。'),dict(title='样本覆盖',text='；'.join(coverage))],claims=[dict(id='short-window',text='本样本仅3至4次观测，不足以支持长期表现或因果判断。',kind='inference',sourceIds=['quotes']),dict(id='pe-coverage',text='只有平安银行存在最新PE快照值5.84；其余四家缺失，且盈余口径未明确，不做估值排名。',kind='fact',sourceIds=['quotes'])],charts=[dict(id='normalized',purpose='归档价格路径（共同起点=100；非含息收益）',type='line',period=source['period'],unit='指数（起点100）',basis=source['basis']+'；仅描述观测路径，不用于投资排名',sourceIds=['quotes'],labels=dates,series=series)])
    for metric,code in zip(metrics,codes):
        indices=sorted([i for i,r in enumerate(rows) if r['quote_code']==code],key=lambda i:rows[i]['trade_date'])
        metric['check']['sourceInputs']=[dict(sourceId='quotes',path=[i,'close']) for i in [indices[0],indices[-1]]]
    for item,code in zip(series,codes):
        indices={r['trade_date']:i for i,r in enumerate(rows) if r['quote_code']==code}
        item['sourceInputs']=[dict(sourceId='quotes',path=[indices[d],'close']) if d in indices else None for d in dates]
        item['transform']='rebased-100'
    kit.build(model,a.output,a.echarts)
    latest_pe=[]
    for code in codes:
        records=sorted([r for r in rows if r['quote_code']==code],key=lambda r:r['trade_date'])
        latest_pe.append(records[-1].get('pe_ratio'))
    before=dict(model,metrics=[dict(id='before-pe-rank',value=list(range(5)),unit='rank',sourceIds=['quotes'],check=dict(op='rank',inputs=latest_pe))],claims=[dict(id='before-one-year-ranking',text='原报告最近一年综合表现排序',kind='inference',sourceIds=['quotes'],horizon='long-term',observations=max(sum(r['quote_code']==c for r in rows) for c in codes))])
    rejected=kit.validate(before,a.output)
    rejected['analysisMethod']='Original problematic claims encoded as a structured executable regression; not arbitrary prose semantic validation'
    kit.dump(a.output/'before_quality_report.json',rejected)
    kit.dump(a.output/'baseline-review.json',{'originalReportSha256':hashlib.sha256((a.original/'report.html').read_bytes()).hexdigest(),'findings':[{'code':'UNSUPPORTED_LONG_TERM_RANK','evidence':'3-4 observations do not establish one-year performance'},{'code':'PE_COVERAGE_ONE_OF_FIVE','evidence':'Only 000001 has pe_ratio; lowest valuation cannot be established'},{'code':'DROPPED_VALID_OBSERVATION','evidence':'Original code excludes SZSE 2026-11-12; SSE missing date must stay missing'}]})
if __name__=='__main__':main()
