"""Two real turns on one official CLI ACP process; not business acceptance."""
from pathlib import Path
import argparse, http.server, json, os, secrets, subprocess, threading, time
import live_model_audit as audit
from official_acp_client import OfficialAcpClient
from resource_registry import register_container

p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--image',required=True)
p.add_argument('--case',default='official-acp-parameter-probe-1');a=p.parse_args()
if not a.image.startswith('sha256:') or not a.case.replace('-','').isalnum():raise ValueError('Pinned image and unique case required')
os.umask(0o077);folder=a.root/'runs'/a.case;folder.mkdir(mode=0o700)
for name in ['workspace','state']:
    path=folder/name;path.mkdir();os.chown(path,10001,10001);path.chmod(0o700)
profile=json.loads((a.root/'private/settings.json').read_text());profile['proxyToken']=secrets.token_hex(32)
audit.configure(a.root,profile);audit.active[a.case]=time.monotonic()
config={'protocol':'acp','outputTokenPolicy':'provider-default','workspace':'/workspace/probe',
    'apiKey':profile['proxyToken'],'model':profile['model'],'baseUrl':f'http://cline-model-audit:18560/{a.case}/v1'}
private=folder/'connection.private.json';private.write_text(json.dumps(config));os.chown(private,10001,10001);private.chmod(0o400)
def docker(*args,timeout=60):
    result=subprocess.run(['docker',*args],text=True,capture_output=True,timeout=timeout)
    if result.returncode:raise RuntimeError(result.stderr[-1000:])
    return result.stdout.strip()
proxy=http.server.ThreadingHTTPServer(('0.0.0.0',18560),audit.AuditProxy)
threading.Thread(target=proxy.serve_forever,daemon=True).start();container=None;process=None;session=None;replies=[];terminal='PROBE_FAILED'
with (folder/'acp.jsonl').open('w') as journal,(folder/'cli.stderr.log').open('w') as stderr:
    try:
        name='coding-node-'+a.case
        mounts=['-v',str(folder/'workspace')+':/workspace/probe','-v',str(folder/'state')+':/state','-v',str(private)+':/run/connection.json:ro']
        for file in ['official-cli-entrypoint.py','configure-official-cli.py']:mounts+=['-v',str(a.root/file)+':/run/'+file+':ro']
        container=docker('create','-i','--name',name,'--label','runtime-os.coding.acceptance='+a.case,'--init',
            '--network','cline-parquet-isolation','--add-host','cline-model-audit:host-gateway','--read-only','--cap-drop','ALL',
            '--security-opt','no-new-privileges','--memory','3g','--cpus','2','--pids-limit','256','--tmpfs','/tmp:rw,nosuid,nodev,size=128m',
            '-e','HOME=/state','-e','NO_PROXY=cline-model-audit,127.0.0.1,localhost','-e','no_proxy=cline-model-audit,127.0.0.1,localhost',
            '--entrypoint','python',*mounts,a.image,'/run/official-cli-entrypoint.py')
        inspection=json.loads(docker('inspect',container))[0];register_container(a.root,inspection,name,'Real official CLI persistent ACP parameter probe; no analysis acceptance',a.case)
        process=subprocess.Popen(['docker','start','-a','-i',container],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=stderr,text=True,encoding='utf-8',bufsize=1)
        client=OfficialAcpClient(process,journal,[])
        initialized=client.call('initialize',{'protocolVersion':1,'clientCapabilities':{'fs':{'readTextFile':False,'writeTextFile':False},'terminal':False},'clientInfo':{'name':'runtime-os-acceptance-probe','version':'1'}},60)
        (folder/'initialize.json').write_text(json.dumps(initialized))
        session=client.call('session/new',{'cwd':'/workspace/probe','mcpServers':[]},60)['sessionId']
        (folder/'session.json').write_text(json.dumps({'sessionId':session,'newSessionCount':1,'processRestarted':False}))
        selected=client.call('session/set_config_option',{'sessionId':session,'configId':'model','value':profile['model']},30)
        (folder/'selected-model.json').write_text(json.dumps(selected))
        if not any(option.get('id')=='model' and option.get('currentValue')==profile['model'] for option in selected.get('configOptions',[])):
            raise RuntimeError('Official Session did not confirm the fixed model; no prompt dispatched')
        for token in ['READY','SECOND_READY']:
            replies.append(client.call('session/prompt',{'sessionId':session,'prompt':[{'type':'text','text':'这是一条配置连通性探针。仅回复 '+token+'，不要读取、创建文件或调用工具。'}]},120))
        terminal='TWO_TURNS_COMPLETED' if all(r.get('stopReason')=='end_turn' for r in replies) else 'UNEXPECTED_OFFICIAL_STOP_REASON'
    except Exception as error:(folder/'failure.json').write_text(json.dumps({'error':repr(error)}))
    finally:
        if process:
            try:process.stdin.close()
            except Exception:pass
        if container:
            docker('stop','-t','5',container)
            (folder/'container-final.json').write_text(json.dumps({'id':container,'state':json.loads(docker('inspect',container))[0]['State']}))
        if process:
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.terminate()
        proxy.shutdown();audit.active.pop(a.case,None)
requests=[json.loads(path.read_text()) for path in sorted((folder/'model').glob('*-request.json'))]
parameters=[{k:v for k,v in request.items() if k not in ['messages','tools']} for request in requests]
native=json.loads((a.root/'runs/native-provider-default-parameter-probe-1/summary.json').read_text())['wireParameters'][0]
summary={'case':a.case,'terminal':terminal,'sessionId':session,'promptResponses':replies,'modelRequests':len(requests),'wireParameters':parameters,
    'matchesNativeDefaultProbe':bool(parameters) and all(p==native for p in parameters),'countsAsBusinessAcceptance':False,'imageId':a.image}
(folder/'summary.json').write_text(json.dumps(summary,indent=2));print(json.dumps(summary))
