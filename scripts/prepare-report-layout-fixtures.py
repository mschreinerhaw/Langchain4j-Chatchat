"""Deterministic controlled business calculations for report layout acceptance.

No customer data, model call, network or third-party Python package. Layouts are
authored acceptance cases, not evidence that a live model selected these layouts.
"""
from decimal import Decimal, localcontext
import hashlib
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
DESTINATION=ROOT/'chatchat-api/web-app/src/js/utils/fixtures/report-layout-cases.json'

def case(key,title,labels,values,kind,order,summary,metric,checks):
    reference='fixture:'+key
    raw={'reference':reference,'labels':labels,'values':values}
    digest=hashlib.sha256(json.dumps(raw,sort_keys=True).encode()).hexdigest()
    report={'schemaVersion':'python_visual_report.v1','status':'SUCCESS','title':title,'summary':summary,
        'presentation':{'strategy':'auto','template':'briefing','density':'comfortable','autoCharts':True,'autoImages':True},
        'sections':[{'id':'finding','title':'主要结论','narrative':summary}],
        'metrics':[{**metric,'id':'key','dataRefs':[reference]}] if metric else [],
        'tables':[{'id':'detail','title':'原始分析结果','columns':['对象','数值'],'rows':list(map(list,zip(labels,values))),
            'dataRefs':[reference],'scope':'受控验收样本'}] if values else [],
        'charts':[{'id':'plot','title':title,'kind':'plotly','chartType':kind,'artifact':{'fileId':'fixture-'+key,'size':128,'mediaType':'application/json'},'dataRefs':[reference]}] if kind else [],
        'dataSources':[reference],'limitations':['仅覆盖受控验收样本，不代表真实客户或当前市场。']}
    refs={'section':'finding','chart':'plot','table':'detail','metric':'key'}
    report['layout']={'schemaVersion':'report_layout.v1','blocks':[
        {'id':'b'+str(index),'type':block,**({'ref':refs[block]} if block in refs else {}),
         'span':'half' if block=='metric' else 'full',**({'emphasis':'important'} if index==0 else {})}
        for index,block in enumerate(order)]}
    figure=None
    if kind:
        trace={'type':'scatter' if kind=='line' else kind}
        if kind=='pie':trace.update(labels=labels,values=[float(Decimal(value)) for value in values])
        else:trace.update(x=labels,y=[float(Decimal(value)) for value in values])
        if kind=='line':trace['mode']='lines+markers'
        figure={'data':[trace],'layout':{'template':None}}
    return {'id':key,'input':raw,'inputSha256':digest,'checks':checks,'report':report,'figure':figure}

def generate():
    with localcontext() as precision:
        precision.prec=50
        prices=['10.00','11.25','10.50'];change=(Decimal(prices[-1])/Decimal(prices[0])-1)*100
        assert change==Decimal('5.00') and len(prices)-1==2
        stocks=case('stocks','股票行情分析',['2026-01-01','2026-01-02','2026-01-03'],prices,'line',
            ['section','chart','summary','table','metric'],'样本首尾价格从10.00变为10.50，跨两个相邻区间变化5.00%，不代表未来收益。',
            {'label':'最后观测价格','value':prices[-1],'unit':'','calculation':'last observed price'},['first_to_last_return=5.00%','intervals=2'])
        trades=['20','30','50'];total=sum(map(Decimal,trades));share=max(map(Decimal,trades))/total*100
        assert total==100 and share==50
        preferences=case('preferences','客户交易偏好分析',['股票','债券','基金'],trades,'pie',
            ['chart','section','table','summary','metric'],'样本共100笔交易，其中基金50笔，占50%；样本偏好不能外推为长期客户特征。',
            {'label':'基金交易占比','value':format(share,'.2f'),'unit':'%','calculation':'50 / 100 * 100'},['total_trades=100','fund_share=50.00%'])
        costs=[Decimal('100.00'),Decimal('100.00')];market=[Decimal('87.50'),Decimal('108.75')]
        profits=[value-cost for value,cost in zip(market,costs)];net=sum(profits);assert net==Decimal('-3.75') and sum(market)==Decimal('196.25')
        assets=case('assets','客户资产及盈亏分析',['资产A','资产B'],list(map(str,profits)),'bar',
            ['metric','section','chart','table','summary'],'样本资产A亏损12.50、资产B盈利8.75，合计净亏损3.75；未含费用和税项。',
            {'label':'净盈亏','value':str(net),'unit':'','calculation':'sum(market_value - cost)'},['net_profit=-3.75','market_total=196.25'])
        assets['input']['costs']=list(map(str,costs));assets['input']['marketValues']=list(map(str,market))
        revenues=['9007199254740993.01','9007199254741000.02'];difference=Decimal(revenues[1])-Decimal(revenues[0]);assert difference==Decimal('7.01')
        financials=case('financials','企业财务数据分析',['第一季度','第二季度'],revenues,None,
            ['table','section','metric','summary'],'第二季度样本收入较第一季度增加7.01。保留原始小数，不将大整数转换为浮点图表。',
            {'label':'第二季度收入','value':revenues[-1],'unit':'','calculation':'second quarter reported amount'},['revenue_delta=7.01','exact_decimal_preserved'])
        news=case('news','金融市场资讯分析',[],[],None,['section','summary'],
            '样本仅提供两条政策方向与一条市场讨论的定性描述，没有行情或财务观测值，不能据此推断价格涨跌。',None,['no_quantitative_chart','qualitative_scope_disclosed'])
        news['report']['status']='PARTIAL';news['report']['sections'][0]['title']='资讯证据与边界'
        news['input']['articles']=[{'topic':'policy','text':'受控样本：政策支持长期资金。'},{'topic':'policy','text':'受控样本：加强信息披露。'},{'topic':'market','text':'受控样本：参与者讨论市场风险。'}]
        empty=case('insufficient','数据不足的报告',[],[],None,['summary','section'],
            '未提供必要的业务数据，无法计算趋势、偏好或盈亏；请补充基础记录和计算口径。',None,['no_metric','no_chart','no_invented_values'])
        empty['report']['status']='NO_DATA';empty['report']['sections'][0]['title']='数据范围'
        results=[stocks,preferences,assets,financials,news,empty]
        assert not news['report']['charts'] and news['report']['status']=='PARTIAL'
        assert not empty['report']['metrics'] and not empty['report']['charts'] and not empty['report']['tables']
        assert json.loads(json.dumps(financials))['report']['metrics'][0]['value']=='9007199254741000.02'
        explanations=[
            '三次观测先上升后回落，窗口很短，不能据此识别长期趋势。',
            '占比按交易笔数计算，没有成交金额或持仓规模，不能据此判断资金偏好。',
            '净额掩盖了两个资产方向相反的变动，需分别核对；未计费用和税项。',
            '金额较大而季度差额较小，使用精确表格展示，避免浮点图形抹去小数差异。',
            '这些文字材料不足以作为交易信号，也没有可用于时序判断的事件日期。',
            '需要补充原始记录、时间范围、字段定义和计算口径后再作量化分析。'
        ]
        for result,explanation in zip(results,explanations):result['report']['sections'][0]['narrative']=explanation
        for result in results:
            result['inputSha256']=hashlib.sha256(json.dumps(result['input'],ensure_ascii=False,sort_keys=True).encode()).hexdigest()
        return results

if __name__=='__main__':
    results=generate();DESTINATION.write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps({'scope':'CONTROLLED_SAMPLES_NOT_CUSTOMER_DATA','cases':len(results),'checks':sum(len(value['checks']) for value in results)}))
