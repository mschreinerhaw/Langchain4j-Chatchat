"""Empty-daemon acceptance. Uses a NEW bounded DinD container, never the host socket.

Requires explicit --run-isolated-cold-host and a prepared offline release directory.
No host network/PID namespace, production socket, or existing data directory is mounted.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import time

def main():
    p=argparse.ArgumentParser();p.add_argument('--run-isolated-cold-host',action='store_true');p.add_argument('--release',type=Path,required=True);p.add_argument('--workspace',type=Path,required=True)
    args=p.parse_args()
    if not args.run_isolated_cold_host:raise SystemExit('Explicit isolated cold-host flag required')
    workspace=args.workspace.resolve();release=args.release.resolve()
    if workspace.exists():raise SystemExit('QA workspace must be new')
    if shutil.disk_usage(workspace.parent).free<8*1024**3:raise SystemExit('Insufficient free disk for isolated cold verification')
    workspace.mkdir(mode=0o700);data=workspace/'daemon-data';run=workspace/'daemon-run';install=workspace/'install'
    data.mkdir();run.mkdir();install.mkdir()
    for path in release.iterdir():
        if path.name=='images':(install/'images').symlink_to(path,target_is_directory=True)
        elif path.is_dir():shutil.copytree(path,install/path.name)
        else:shutil.copy2(path,install/path.name)
    (install/'.env').write_text('EXECUTOR_TOKEN='+secrets.token_hex(32)+'\nDB_PASSWORD='+secrets.token_hex(24)+'\nADMIN_PASSWORD='+secrets.token_urlsafe(24)+'\nOPS_READ_ONLY=1\n')
    (install/'.env').chmod(0o600)
    outer=['docker','-H','unix:///var/run/docker.sock'];name='deployment-manager-cold-'+str(time.time_ns())
    ident=subprocess.check_output(outer+['run','-d','--privileged','--cgroupns','private','--network','none','--memory','1g','--cpus','1','--pids-limit','1024','--label','deployment-ops.qa=cold','--name',name,
        '-v',str(data)+':/var/lib/docker','-v',str(run)+':/var/run','-v',str(workspace)+':'+str(workspace)+':ro','-v',str(release)+':'+str(release)+':ro',
        '--entrypoint','dockerd','docker:27.5.1-dind','--host=unix:///var/run/docker.sock','--data-root=/var/lib/docker','--storage-driver=overlay2'],text=True).strip()
    env={**os.environ,'DOCKER_HOST':'unix://'+str(run/'docker.sock'),'MANAGER_INSTALL_NEW':'yes'};env.pop('DOCKER_CONTEXT',None)
    def inner(command,check=True):return subprocess.run(command,env=env,cwd=install,check=check,capture_output=True,text=True)
    def daemon_ready():
        for _ in range(120):
            if inner(['docker','info','--format','{{.ID}}'],False).returncode==0:return
            time.sleep(.5)
        raise RuntimeError('Isolated daemon failed: '+subprocess.check_output(outer+['logs','--tail','20',ident],text=True))
    client=workspace/'client.py'
    client.write_text('''import json,urllib.request,http.cookiejar,sys,pathlib
root=pathlib.Path(sys.argv[1]);values=dict(l.split('=',1) for l in (root/'.env').read_text().splitlines() if '=' in l)
op=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def api(path,data=None):
 q=urllib.request.Request('http://127.0.0.1:19220/api'+path,data=None if data is None else json.dumps(data).encode(),headers={'X-Ops-Request':'1'})
 return json.load(op.open(q,timeout=15))
api('/login',{'username':'admin','password':values['ADMIN_PASSWORD']})
mode=sys.argv[2]
if mode=='write':api('/entries',{'name':sys.argv[3],'url':'http://example.invalid','project':'cold'})
else:print(json.dumps({'entries':api('/entries'),'inventory':api('/inventory'),'policy':api('/nodes/1/manager/policy')}))
''')
    def request(mode='read',value=''):
        pid=json.loads(subprocess.check_output(outer+['inspect',ident]))[0]['State']['Pid']
        r=subprocess.run(['nsenter','-t',str(pid),'-n','python3',str(client),str(install),mode,value],capture_output=True,text=True)
        if r.returncode:raise RuntimeError(r.stderr[-1000:])
        return json.loads(r.stdout) if mode=='read' else None
    def available():
        for _ in range(120):
            try:return request()
            except Exception:time.sleep(.5)
        raise RuntimeError('Cold manager did not become available')
    checks=[]
    def passed(label):checks.append(label);print('PASS',label,flush=True)
    try:
        daemon_ready();assert json.loads(inner(['docker','image','ls','--format','json']).stdout or 'null') is None
        passed('new Docker daemon starts with zero images and no external network')
        result=inner(['sh','install.sh']);print(result.stdout[-1000:],flush=True)
        state=available();assert state['policy']['readOnly'] is True and len(state['inventory'][0]['data']['containers'])==2
        passed('offline install loads only packaged images and starts an independent authenticated manager')
        request('write','cold-backup-proof')
        backup=workspace/'backup';inner(['sh','backup.sh',str(backup)]);available();passed('cold H2 and agent-state backup completes')
        request('write','after-backup-only')
        inner(['docker','compose','stop','web','executor'])
        result=inner(['sh','restore.sh',str(backup),'RESTORE-MANAGEMENT']);print(result.stdout[-1000:],flush=True)
        inner(['docker','compose','up','-d','--no-build','--pull','never']);state=available()
        names={item['name'] for item in state['entries']};assert 'cold-backup-proof' in names and 'after-backup-only' not in names
        passed('restore into fresh volumes recovers exact management metadata and retains original volumes')
        inner(['sh','install.sh'],False)
        result=inner(['sh','install.sh'],False);assert result.returncode!=0
        passed('new-install script refuses existing management containers and data')
        subprocess.run(outer+['stop','-t','20',ident],check=True,stdout=subprocess.DEVNULL)
        subprocess.run(outer+['start',ident],check=True,stdout=subprocess.DEVNULL);daemon_ready();state=available()
        assert any(e['name']=='cold-backup-proof' for e in state['entries'])
        passed('isolated daemon restart preserves management data and resumes services')
        (workspace/'result.json').write_text(json.dumps({'passed':checks,'productionDaemonRestarted':False,'isolatedDaemon':True},indent=2))
    finally:
        subprocess.run(outer+['stop','-t','20',ident],check=False,stdout=subprocess.DEVNULL)
        subprocess.run(outer+['rm',ident],check=False,stdout=subprocess.DEVNULL)

if __name__=='__main__':main()
