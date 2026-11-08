#!/usr/bin/env python3
import pathlib,json,subprocess,os
R=pathlib.Path('/opt/cline-server-p33-stage1-20261108');a=json.loads((R/'evidence/rootless-account.json').read_text());cfg=json.loads((R/'config/executor.json').read_text());D=['docker','-H','unix://'+a['socket']]
image=json.loads(subprocess.check_output([*D,'inspect','cline-p331-executor']))[0]['Image'];results=[]
for name,change,expected in [('strict-resources',{'allowUnenforcedPrototype':False},'Per-Worker cgroup resource enforcement unavailable'),('wrong-daemon',{'expectedDaemonId':'not-the-dedicated-daemon'},'Wrong daemon or rootful daemon')]:
 p=R/'config'/(name+'.json');p.write_text(json.dumps({**cfg,**change}));os.chown(p,a['daemonUid'],a['daemonGid']);p.chmod(0o400)
 result=subprocess.run([*D,'run','--rm','--network','none','--read-only','--user','0:0','--mount',f'type=bind,src={p},dst=/run/executor.json,readonly','--mount',f'type=bind,src={a["socket"]},dst=/run/rootless-docker.sock','-e','CLINE_EXECUTOR_CONFIG=/run/executor.json','--entrypoint','node',image,'/poc/service/src/worker-executor.mjs'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=20)
 (R/'evidence'/(name+'.log')).write_text(result.stdout);results.append({'case':name,'pass':result.returncode!=0 and expected in result.stdout,'exit':result.returncode})
hostnet=os.readlink('/proc/self/ns/net');code="const f=require('fs');console.log(JSON.stringify({uid:process.getuid(),uidMap:f.readFileSync('/proc/self/uid_map','utf8'),network:f.readlinkSync('/proc/self/ns/net')}))"
namespaces={n:json.loads(subprocess.check_output([*D,'exec',n,'node','-e',code])) for n in ('cline-p331-gateway','cline-p331-executor')}
results.append({'case':'Gateway-host-network-is-rootless-namespace','pass':namespaces['cline-p331-gateway']['network']!=hostnet,'hostNetwork':hostnet,'containers':namespaces})
(R/'evidence/startup-guards.json').write_text(json.dumps(results,indent=2));print(json.dumps(results,indent=2))
if not all(x['pass'] for x in results):raise SystemExit(1)
