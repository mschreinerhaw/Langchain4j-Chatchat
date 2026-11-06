"""Read bounded, authorized real market sources; never modifies data or synthesizes empty rows.

The saved fixture is private. Public summaries contain parameters, timestamps and hashes,
not source bodies or authentication. An unavailable precondition is not a passing scenario.
"""
from pathlib import Path
import datetime
import hashlib
import json
import sys
import time
import urllib.error
import urllib.request


def main():
    root=Path(sys.argv[1]).resolve();root.mkdir(mode=0o700,exist_ok=True)
    assert not (root/'source-capture-intent.json').exists(),'Never silently replace captured observations'
    token=json.loads(Path(sys.argv[2]).read_text())['token']
    requests=[
        ('E01','market_get_history',{'dataset':'market_quote_daily','filters':{'quote_code':'SZSE:301218'},'start_date':'2026-11-02','end_date':'2026-11-02','history_mode':'daily','limit':5}),
        ('E02','market_get_day_snapshot',{'dataset':'market_quote_daily','source_id':38,'trade_date':'2026-11-02'}),
        ('E04','market_get_history',{'dataset':'market_quote_daily','start_date':'2099-01-01','end_date':'2099-01-01','history_mode':'daily','limit':1}),
        ('E05','market_get_history',{'dataset':'market_quote_daily','filters':{'quote_code':'301218'},'start_date':'2026-11-02','end_date':'2026-11-02','history_mode':'daily','limit':1}),
    ]
    (root/'source-capture-intent.json').write_text(json.dumps({'requests':requests,'readOnly':True,'authorizationExpanded':False}))
    fixtures=[];summary=[]
    for case,tool,args in requests:
        started=datetime.datetime.now(datetime.timezone.utc).isoformat();clock=time.monotonic()
        req=urllib.request.Request('http://127.0.0.1:18101/api/v1/resource-exploration/read',
            data=json.dumps({'tool':tool,'arguments':args}).encode(),
            headers={'Content-Type':'application/json','Authorization':'Bearer '+token})
        with urllib.request.urlopen(req,timeout=60) as stream:raw=stream.read();status=stream.status
        envelope=json.loads(raw);assert envelope['code']==200
        body=envelope['data'];body.pop('authority',None);body.pop('license_status',None)
        row={'case':case,'tool':tool,'query':args,'startedAt':started,'elapsedMs':round((time.monotonic()-clock)*1000),
             'httpStatus':status,'responseSha256':hashlib.sha256(raw).hexdigest(),'returnedRowCount':body.get('count'),
             'sourceStatus':body.get('status'),'complete':body.get('complete'),'requiredFields':['official_listing_date'] if case=='E05' else []}
        fixtures.append(dict(row,response=body));summary.append(row)
    assert fixtures[0]['response']['count']==0,'Exact miss precondition did not occur'
    day=fixtures[1]['response'];assert day['complete'] is True and day['count']==len(day['items'])
    matches=[row for row in day['items'] if str(row.get('quote_code'))=='301218']
    assert len(matches)==1,'Real target must exist in the same-day file'
    summary[1]['targetMatched']=True;summary[1]['targetCode']='301218'
    private=root/'source-fixtures-private.json';private.write_text(json.dumps(fixtures,ensure_ascii=False));private.chmod(0o600)
    public={'readOnly':True,'authorizationExpanded':False,'cases':summary,
        'fixtureSha256':hashlib.sha256(private.read_bytes()).hexdigest(),
        'allLocalSourcesEmptyPrecondition':False,'reason':'Actual authorized source38/day has valid data; production records were not removed',
        'scope':'Real source reads; does not by itself certify Cline recovery, report or archive delivery'}
    (root/'source-capture-proof.json').write_text(json.dumps(public,indent=2));print(json.dumps(public))


if __name__=='__main__':main()
