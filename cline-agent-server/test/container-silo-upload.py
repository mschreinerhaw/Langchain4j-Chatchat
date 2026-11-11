"""Upload a sealed real-model fixture through the container gateway to real Silo.

Controller-only test: credentials are never mounted into a Cline Worker. This
checks storage integrity; it does not stand in for Java business acceptance.
"""
import argparse, base64, hashlib, hmac, json, math, urllib.request, urllib.error
from pathlib import Path
import botocore.session
from botocore.config import Config

p=argparse.ArgumentParser();p.add_argument('--stage',type=Path,required=True)
p.add_argument('--deployment',type=Path,required=True);p.add_argument('--case',required=True)
p.add_argument('--gateway-config',type=Path,help='Explicit candidate gateway authority; storage credentials still come from the existing stage')
a=p.parse_args();folder=a.deployment/'acceptance'/a.case
cfg=json.loads((a.gateway_config or a.stage/'config/native-node/gateway.json').read_text())
grant=json.loads((folder/'intent.json').read_text())['grant']
raw=base64.urlsafe_b64encode(json.dumps(grant,separators=(',',':')).encode()).decode().rstrip('=')
token=raw+'.'+base64.urlsafe_b64encode(hmac.new(cfg['signingKey'].encode(),raw.encode(),hashlib.sha256).digest()).decode().rstrip('=')
def request(action,value):
    req=urllib.request.Request('http://127.0.0.1:'+str(cfg['port'])+'/v1/sessions/'+grant['sessionId']+'/'+action,
        data=json.dumps(value).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(req,timeout=120) as res:return json.load(res)
    except urllib.error.HTTPError as error:
        receipt={'status':error.code,'receipt':json.loads(error.read())}
        (folder/'silo-upload-failure.json').write_text(json.dumps(receipt,indent=2))
        raise RuntimeError('Gateway rejected storage operation; inspect private failure receipt') from None
client=botocore.session.get_session().create_client('s3',endpoint_url=grant['s3']['origin'],region_name='us-east-1',
    aws_access_key_id=(a.stage/'config/secrets/asset-access-key').read_text().strip(),
    aws_secret_access_key=(a.stage/'config/secrets/asset-secret-key').read_text().strip(),
    config=Config(signature_version='s3v4',s3={'addressing_style':'path'}))
manifest=json.loads((folder/'delivery_manifest.json').read_text())
prefix=grant['s3']['outputPrefix'].lstrip('/');bucket,prefix=prefix.split('/',1)
def signed(key):return client.generate_presigned_url('put_object',Params={'Bucket':bucket,'Key':key},ExpiresIn=900)
plan={'manifestSha256':hashlib.sha256(json.dumps(manifest,separators=(',',':'),ensure_ascii=False).encode()).hexdigest(),
    'artifacts':[],'manifestUrl':signed(prefix+'delivery_manifest.json'),'evidenceUrl':signed(prefix+'native-events.json')}
keys={}
for artifact in manifest['artifacts']:
    parts=[]
    for index in range(max(1,math.ceil(artifact['size']/(8*1024*1024)))):
        key=prefix+'files/'+artifact['path']+'/part-'+str(index)
        parts.append({'url':signed(key)});keys[(artifact['path'],index)]=key
    plan['artifacts'].append({'path':artifact['path'],'parts':parts})
receipt=request('upload',plan)
checks=[]
for item in receipt['artifacts']:
    sha=hashlib.sha256();size=0
    for part in item['parts']:
        response=client.get_object(Bucket=bucket,Key=keys[(item['path'],part['index'])],VersionId=part['objectVersion'])
        data=response['Body'].read();response['Body'].close()
        if len(data)!=part['size'] or hashlib.sha256(data).hexdigest()!=part['sha256']:raise RuntimeError('Stored part integrity mismatch')
        sha.update(data);size+=len(data)
    checks.append({'path':item['path'],'size':size,'sha256':sha.hexdigest(),'passed':size==item['size'] and sha.hexdigest()==item['sha256']})
result={'schemaVersion':'container_silo_upload_verification.v1','realSilo':True,'realModel':True,
    'allStoredHashesMatch':all(c['passed'] for c in checks),'artifacts':checks,
    'receipt':receipt,'applicationHttpSpiAcceptance':False,'businessAcceptance':False}
(folder/'real-silo-upload-verification.json').write_text(json.dumps(result,indent=2))
print(json.dumps({'realSilo':True,'storedArtifacts':len(checks),'allStoredHashesMatch':result['allStoredHashesMatch']}))
if not result['allStoredHashesMatch']:raise SystemExit(1)
