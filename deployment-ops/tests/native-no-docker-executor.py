"""Actual native executor startup/host sampling with an absent and inaccessible Docker socket."""
import json,secrets,socket,subprocess,time,urllib.request,urllib.error
from pathlib import Path
home=Path('/mnt/disk01/deployment-ops-v1-release/releases/1.0.0-rc.4');work=Path('/mnt/disk01/deployment-ops-v1-validation/no-docker-executor');work.mkdir()
for name in ['state','packages','trust','apps','data','tmp']:(work/name).mkdir()
unit='deployment-ops-no-docker-'+str(time.time_ns());token=secrets.token_hex(32)
with socket.socket() as available:available.bind(('127.0.0.1',0));port=available.getsockname()[1]
values={'OPS_NATIVE':'1','OPS_STATE':str(work/'state'),'OPS_PACKAGES':str(work/'packages'),'OPS_TRUST':str(work/'trust'),'OPS_APP_DATA':str(work/'apps'),'OPS_DATA_DIR':str(work/'data'),'OPS_RUNTIME_ROOT':'/mnt/disk01/ops/r','OPS_READ_ONLY':'1','OPS_STRICT_OWNERSHIP':'1','OPS_EXECUTOR_BIND':'127.0.0.1','OPS_EXECUTOR_PORT':str(port),'EXECUTOR_TOKEN':token,'DOCKER_SOCKET':'/run/'+unit+'.sock','OPS_HOST_PROC':'/proc','OPS_HOST_ROOT':'/mnt/disk01','TMPDIR':str(work/'tmp'),'PYTHONDONTWRITEBYTECODE':'1'}
assert not Path(values['DOCKER_SOCKET']).exists()
command=['systemd-run','--unit='+unit,'--property=InaccessiblePaths=/var/run/docker.sock','--property=ProtectSystem=strict','--property=ReadWritePaths='+str(work)]
command+=['--setenv='+key+'='+value for key,value in values.items()];command+=['python3',str(home/'executor/agent.py')]
op=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def get(path):
    with op.open(urllib.request.Request('http://127.0.0.1:'+str(port)+path,headers={'Authorization':'Bearer '+token}),timeout=5) as response:return json.load(response)
try:
    subprocess.run(command,check=True,capture_output=True)
    for _ in range(40):
        try:
            policy=get('/manager/policy');host=get('/manager/host')
            if host.get('available'):break
        except Exception:pass
        time.sleep(1)
    else:raise RuntimeError('Native executor did not start and sample the real host without Docker')
    unavailable=False
    try:get('/inventory')
    except urllib.error.HTTPError as error:unavailable=error.code==400
    assert unavailable and policy['readOnly'] and host['scope']=='host'
    result={'passed':True,'executorStarted':True,'realHostMetricsAvailable':True,'dockerUnavailable':True,'readOnly':True,'method':'absent configured Unix socket plus systemd inaccessible real socket'};Path('/mnt/disk01/deployment-ops-v1-validation/no-docker-executor.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
finally:subprocess.run(['systemctl','stop',unit],check=True,capture_output=True)
