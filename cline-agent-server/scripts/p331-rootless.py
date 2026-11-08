#!/usr/bin/env python3
"""Create only this prototype's Unix user and rootless daemon. No host Docker config edits."""
import pathlib,subprocess,json,os,pwd,time
R=pathlib.Path('/opt/cline-server-p33-stage1-20261108');USER='cline-p331'
R.mkdir(exist_ok=True);(R/'evidence').mkdir(exist_ok=True)
def run(*a,**kw):
 p=subprocess.run(a,**kw)
 if p.returncode:raise RuntimeError('Prototype command failed: '+a[0]+' (arguments withheld)')
 return p
try:account=pwd.getpwnam(USER)
except KeyError:
 run('useradd','--create-home','--home-dir',str(R/'daemon-home'),'--shell','/sbin/nologin',USER);account=pwd.getpwnam(USER)
if pathlib.Path(account.pw_dir)!=R/'daemon-home':raise RuntimeError('Unexpected pre-existing daemon account')
for name in ('run','daemon-data','daemon-exec','executor-control','worker-mounts','executor-bridge','config','control-state','templates'):
 p=R/name;p.mkdir(exist_ok=True);os.chown(p,account.pw_uid,account.pw_gid)
os.chmod(R/'run',0o700)
base=next(int(line.split(':')[1]) for line in pathlib.Path('/etc/subuid').read_text().splitlines() if line.startswith(USER+':'))
gidbase=next(int(line.split(':')[1]) for line in pathlib.Path('/etc/subgid').read_text().splitlines() if line.startswith(USER+':'))
info={'daemonUser':USER,'daemonUid':account.pw_uid,'daemonGid':account.pw_gid,'subUidStart':base,'subGidStart':gidbase,'workerHostUid':base+999,'workerHostGid':gidbase+999,'socket':str(R/'run/docker.sock')}
(R/'evidence/rootless-account.json').write_text(json.dumps(info,indent=2))
sock=R/'run/docker.sock'
def docker_info():
 p=subprocess.run(['docker','-H','unix://'+str(sock),'info','--format','{{json .}}'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
 data=json.loads(p.stdout) if p.returncode==0 else None
 return data if data and data.get('ID') and data.get('SecurityOptions') else None
if not docker_info():
 env=dict(os.environ,HOME=account.pw_dir,XDG_RUNTIME_DIR=str(R/'run'),PATH='/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin',DOCKERD_ROOTLESS_ROOTLESSKIT_NET='slirp4netns',DOCKERD_ROOTLESS_ROOTLESSKIT_FLAGS='-p 127.0.0.1:18331:18331/tcp')
 command=['/usr/bin/dockerd-rootless.sh','--host=unix://'+str(sock),'--data-root='+str(R/'daemon-data'),'--exec-root='+str(R/'daemon-exec'),'--pidfile='+str(R/'run/dockerd.pid'),'--storage-driver=vfs','--iptables=false','--bridge=none','--ip-masq=false','--ip-forward=false','--userland-proxy=false']
 def drop_identity():os.setgroups([]);os.setgid(account.pw_gid);os.setuid(account.pw_uid)
 with (R/'evidence/rootless-daemon.log').open('ab') as log:subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,start_new_session=True,env=env,preexec_fn=drop_identity)
 for n in range(50):
  if docker_info():break
  time.sleep(1)
data=docker_info()
if not data:raise RuntimeError('Rootless daemon not ready; see rootless-daemon.log')
assert 'name=rootless' in data.get('SecurityOptions',[]),'Refusing rootful fallback'
(R/'evidence/rootless-info.json').write_text(json.dumps(data,indent=2))
print(json.dumps({'daemonId':data['ID'],'rootless':True,'cgroupDriver':data['CgroupDriver'],'memoryLimit':data.get('MemoryLimit'),'cpuCfsQuota':data.get('CpuCfsQuota'),'pidsLimit':data.get('PidsLimit')}))
