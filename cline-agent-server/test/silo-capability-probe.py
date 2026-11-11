"""Read-only real Silo capability scope tests; never prints URLs or input bytes.

Usage: python silo-capability-probe.py private-worker-plan.json evidence.json
Positive GET must pass before any denial is counted. No bucket/root key is used.
"""
from pathlib import Path
import hashlib,json,sys,urllib.request,urllib.parse,urllib.error

plan=json.loads(Path(sys.argv[1]).read_text());original=plan['inputs'][0]['url']
client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def request(url,method='GET'):
    req=urllib.request.Request(url,method=method,headers={'Range':'bytes=0-15'} if method=='GET' else {},data=b'' if method=='PUT' else None)
    try:
        with client.open(req,timeout=15) as response:
            body=response.read(17)
            return {'status':response.status,'bodyBytesRead':len(body),'responsePrefixSha256':hashlib.sha256(body).hexdigest()}
    except urllib.error.HTTPError as error:
        error.close();return {'status':error.code}

positive=request(original);results={'source':'Real Silo signed capability transport, no model calls','positiveGet':positive,'capabilitySha256':hashlib.sha256(original.encode()).hexdigest(),'checks':{}}
if positive['status']!=206:
    results['accepted']=False;results['reason']='POSITIVE_CONTROL_FAILED_OR_EXPIRED; denials are not counted as scope proof'
else:
    parsed=urllib.parse.urlsplit(original);path=parsed.path
    pieces=path.split('/');tenant=pieces.index('tenants')+1;pieces[tenant]='0'*64
    foreign=urllib.parse.urlunsplit(parsed._replace(path='/'.join(pieces)))
    other_task=urllib.parse.urlunsplit(parsed._replace(path=path+'-foreign-task'))
    foreign_version=plan['inputs'][1]['objectVersion']
    if foreign_version==plan['inputs'][0]['objectVersion']:raise RuntimeError('Distinct genuine object version required for the scope probe')
    params=urllib.parse.parse_qsl(parsed.query,keep_blank_values=True);params=[(k,foreign_version if k=='versionId' else v) for k,v in params]
    wrong_version=urllib.parse.urlunsplit(parsed._replace(query=urllib.parse.urlencode(params)))
    results['checks']={'otherTenantObject':request(foreign),'otherTaskObject':request(other_task),'otherObjectVersion':request(wrong_version),'writeWithReadCapability':request(original,'PUT')}
    results['accepted']=all(check['status']==403 for check in results['checks'].values())
Path(sys.argv[2]).write_text(json.dumps(results,indent=2));print(json.dumps(results))
raise SystemExit(0 if results['accepted'] else 1)
