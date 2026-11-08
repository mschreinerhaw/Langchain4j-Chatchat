"""Actual UID1000 OS/network/UDS/MCP isolation probes; no mock executor results."""
import pathlib,json,subprocess,sys,urllib.request,urllib.error,base64,hmac,hashlib,time
r=pathlib.Path(sys.argv[1]);cfg=json.loads((r/'control/config.json').read_text());out=r/'evidence/live';contracts=json.loads((out/'contracts.json').read_text());base='http://127.0.0.1:'+str(cfg['port'])
def sign(c):
 b=base64.urlsafe_b64encode(json.dumps(c,separators=(',',':')).encode()).rstrip(b'=');return b.decode()+'.'+base64.urlsafe_b64encode(hmac.new(cfg['signingKey'].encode(),b,hashlib.sha256).digest()).rstrip(b'=').decode()
def api(c,path,value=None,expected=200):
 req=urllib.request.Request(base+path,None if value is None else json.dumps(value).encode(),{'Authorization':'Bearer '+sign(c),'Content-Type':'application/json','Accept':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=30) as response:code=response.status;data=json.load(response)
 except urllib.error.HTTPError as response:code=response.code;data=json.load(response)
 assert code==expected,(path,code,data)
 return data
records={}
for name in ['a','b']:
 records[name]=next(json.loads(p.read_text()) for p in (r/'control/workers').glob('*/registry.json') if json.loads(p.read_text())['contract']['sessionId']==contracts[name]['sessionId'])
source=r'''
const fs=require('fs'),crypto=require('crypto'),p=JSON.parse(process.argv[1]),c=JSON.parse(fs.readFileSync('/run/cline-poc.json'));
const hash=s=>crypto.createHash('sha256').update(s).digest('hex');const denied={};
for(const name of [p.foreignFile,p.foreignState,p.foreignSocket,'/var/run/docker.sock','/control/config.json']){try{fs.readFileSync(name);denied[name]='UNEXPECTED_READ';}catch(e){denied[name]=e.code;}}
fs.symlinkSync(p.foreignFile,'/poc/project/foreign-link');try{fs.readFileSync('/poc/project/foreign-link');denied.symlink='UNEXPECTED_READ';}catch(e){denied.symlink=e.code;}finally{fs.unlinkSync('/poc/project/foreign-link');}
async function request(path,body){const r=await fetch('http://127.0.0.1:18329'+path,{method:'POST',headers:{Authorization:'Bearer '+c.apiKey,'content-type':'application/json'},body:JSON.stringify(body),signal:AbortSignal.timeout(5000)});return {status:r.status,body:await r.text()};}
const net=[];for(const url of [p.upstreamMCP,'http://172.17.0.1:18101','http://172.17.0.1:18100','https://example.org']){try{const r=await fetch(url,{signal:AbortSignal.timeout(1200)});net.push({url,status:r.status});}catch(e){net.push({url,blocked:true,error:e.cause?.code??e.name});}}
const foreign=await request('/mcp',{jsonrpc:'2.0',id:2,method:'tools/call',params:{name:'read_dataset',arguments:{datasetId:p.foreignDataset}}});
const own=await request('/mcp',{jsonrpc:'2.0',id:3,method:'tools/call',params:{name:'read_dataset',arguments:{datasetId:p.ownDataset}}});
const model=await request('/model/v1/chat/completions',{model:'unauthorized-model',messages:[]});
const route=await request('/foreign-broker/'+p.foreignWorker,{jsonrpc:'2.0',id:4,method:'tools/list'});
console.log(JSON.stringify({uid:process.getuid(),networkInterfaces:fs.readdirSync('/sys/class/net'),denied,network:net,foreignMCP:foreign,ownMCP:own,otherModel:model.status,foreignBroker:route.status,ownFileSha256:hash(fs.readFileSync('/poc/project/tenant-private.txt'))}));
'''
try:
 all_results={}
 fixture_info=json.loads(subprocess.check_output(['docker','inspect','cline-p3-data']))[0];ip=fixture_info['NetworkSettings']['Networks']['cline-p3-control']['IPAddress']
 for name,other in [('a','b'),('b','a')]:
  current=records[name];foreign=records[other];path=r/'control/workers'/foreign['workerId']
  data={'foreignFile':str(path/'workspace/tenant-private.txt'),'foreignState':str(path/'state'),'foreignSocket':str(path/'bridge/worker.sock'),'foreignWorker':foreign['workerId'],'upstreamMCP':'http://'+ip+':18401/mcp','ownDataset':'dataset-'+name,'foreignDataset':'dataset-'+other}
  command=['docker','exec','--user','1000:1000',current['containerId'],'node','--input-type=commonjs','-e','(async()=>{'+source+'})().catch(e=>{console.error(e);process.exit(1)})',json.dumps(data)]
  p=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT);(out/name/'adversarial.log').write_bytes(p.stdout);assert p.returncode==0,p.stdout.decode()
  proof=json.loads(p.stdout);assert proof['uid']==1000 and proof['networkInterfaces']==['lo'];assert all(value!='UNEXPECTED_READ' for value in proof['denied'].values());assert all(value.get('blocked') for value in proof['network']);assert proof['foreignMCP']['status']==403 and proof['ownMCP']['status']==200 and proof['otherModel']==403 and proof['foreignBroker']==404
  expected=json.loads((r/'mcp'/('dataset-'+name+'.json')).read_text());not_allowed=json.loads((r/'mcp'/('dataset-'+other+'.json')).read_text());assert expected['marker'] in proof['ownMCP']['body'] and not_allowed['marker'] not in json.dumps(proof)
  all_results[name]=proof
  # A decision for a foreign resolver never reaches its owner's native worker.
  foreign_events=next((path/'state').glob('*/events.jsonl'));events=[json.loads(line) for line in foreign_events.read_text().splitlines()];permission=next(e['payload']['id'] for e in events if e['eventType']=='permission.requested')
  api(contracts[name],'/v1/permissions/'+permission+'/respond',{'decision':'approve'},404)
 (out/'adversarial-summary.json').write_text(json.dumps({'status':'PASS','probes':all_results,'crossTenantPermissionDenied':True,'scope':'Ordinary worker UID1000; no host root or kernel exploit; actual filesystem/network and file-backed MCP fixture'},indent=2));print('Adversarial OS/network/UDS/MCP/permission isolation PASS',flush=True)
except BaseException:
 import traceback
 (out/'adversarial-failure.json').write_text(json.dumps({'status':'FAIL','traceback':traceback.format_exc()},indent=2));raise
