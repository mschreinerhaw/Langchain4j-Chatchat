"""Freeze the real B fallback's exact bytes for C/D and the paired benchmark.

Uses the existing owner asset upload API. Does not change source data, grants or
search settings. All C/D requests must still be unsubmitted when this is run.
"""
from pathlib import Path
import hashlib
import json
import os
import urllib.request
import uuid


def main():
    os.umask(0o077)
    source=Path('/var/tmp/runtime-cline-acceptance-20261107-B2')
    ui=Path('/var/tmp/runtime-cline-acceptance-20261107-ui')
    assert not (ui/'acceptance-frozen-inputs-private.json').exists()
    roots=[Path('/var/tmp/runtime-cline-acceptance-20261107-'+key) for key in ['C','D']]
    assert all(not (root/'autonomous-search-intent-private.json').exists() for root in roots)
    token=json.loads((source/'auth-private.json').read_text())['token']
    proof=json.loads((source/'autonomous-day-proof.json').read_text());assert proof['status']=='COMPLETED'
    files=proof['daySnapshotReceipts'][0]['calculationInput']['fileLayout']['files']
    shared=Path('/opt/runtime-os-docker-stage/mcp/data/python-user-data').resolve()
    frozen=[]
    for file in files:
        identity,name=file['path'].rsplit('/',2)[-2:]
        paths=list(shared.rglob(identity+'/'+name));assert len(paths)==1 and paths[0].resolve().is_relative_to(shared)
        raw=paths[0].read_bytes();assert hashlib.sha256(raw).hexdigest()==file['sha256']
        boundary='acceptance-'+uuid.uuid4().hex
        body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()+raw+f'\r\n--{boundary}--\r\n'.encode()
        request=urllib.request.Request('http://127.0.0.1:18180/api/v1/data-science/user-assets',data=body,
            headers={'Authorization':'Bearer '+token,'Content-Type':'multipart/form-data; boundary='+boundary})
        with urllib.request.urlopen(request,timeout=60) as response:reply=json.load(response)
        assert reply['code'] in [200,201] and reply['data']['sha256']==file['sha256']
        frozen.append({'fileName':name,'sha256':file['sha256'],'assetId':reply['data']['id'],'bytes':len(raw)})
    prepared=json.loads((source/'scenario-prepared-private.json').read_text())
    entry=next((item,identity) for item,identity in zip(prepared['inputFiles'],prepared['inputAssetIds'])
               if item['fileName']=='market-producer-declarations.json')
    frozen.append({**entry[0],'assetId':entry[1]})
    question=('分析华是科技（301218，深交所）2026-11-02已收集的当日行情，'
        '在同源、同市场、同证券类型的有效样本中计算涨跌幅排名、样本数和并列范围，并提供完整报告和有意义的离线图表。'
        '附件是同一份真实冻结日快照及其manifest/schema和生产者字段声明。验证文件Hash及行数，按实际字段和单位计算，不能把传输行数当行情行数。'
        '价格货币未知则保持未知，成交量万单位不能改成万手。明确单日历史快照的覆盖及趋势分析限制，不外推实时价格。')
    definition={'originTaskId':proof['taskId'],'inputFiles':frozen,'question':question}
    (ui/'acceptance-frozen-inputs-private.json').write_text(json.dumps(definition,ensure_ascii=False,indent=2))
    for root in roots:
        scenario=json.loads((root/'acceptance-scenario-private.json').read_text())
        scenario['fileIds']=[item['assetId'] for item in frozen]
        scenario['instruction']=scenario['instruction'].replace('历史行情走势（按真实独立日期去重，不外推实时行情）',
            '2026-11-02当日已收集行情及同源同市场同类型样本排名（当前只有单日快照，不能推断多日走势或实时价）')
        (root/'acceptance-scenario-private.json').write_text(json.dumps(scenario,ensure_ascii=False,indent=2))
        (root/'acceptance-frozen-inputs-private.json').write_text(json.dumps(definition,ensure_ascii=False,indent=2))
    print(json.dumps({'frozenFiles':frozen,'originTaskId':proof['taskId'],'unsubmittedScenarios':['C','D'],
                     'policy':'Same underlying real day snapshot; official announcement is intentionally absent'}))


if __name__=='__main__':main()
