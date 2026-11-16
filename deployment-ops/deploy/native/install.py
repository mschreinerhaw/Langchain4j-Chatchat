"""Install a native release without replacing existing directories or business services."""
import argparse,grp,os,pwd,re,secrets,subprocess
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--name',default='deployment-ops')
p.add_argument('--state',type=Path,default=Path('/mnt/disk01/deployment-ops'))
p.add_argument('--java',default='java')
p.add_argument('--port',type=int,default=19240)
p.add_argument('--executor-port',type=int,default=19242)
p.add_argument('--bind',default='127.0.0.1')
p.add_argument('--enable-writes',action='store_true')
a=p.parse_args()
if os.geteuid()!=0:raise SystemExit('Run installer as root')
if not re.fullmatch(r'deployment-ops(?:-[a-z0-9-]+)?',a.name):raise SystemExit('Invalid service name')
mount=Path('/mnt/disk01')
home=Path(__file__).resolve().parents[2]
state=a.state.resolve()
if not mount.is_mount() or mount.resolve()!=mount:raise SystemExit('Required real mount /mnt/disk01 unavailable')
if mount not in state.parents or mount not in home.parents:raise SystemExit('Release and state must resolve below /mnt/disk01')
if any(re.search(r'[^A-Za-z0-9_./:-]',str(value)) for value in (state,home,a.java,a.bind)):raise SystemExit('Unsupported path or address characters')
if not (1024<=a.port<=65535 and 1024<=a.executor_port<=65535 and a.port!=a.executor_port):raise SystemExit('Invalid ports')
version=subprocess.run([a.java,'-version'],capture_output=True,text=True,check=True).stderr
major=re.search(r'version "(\d+)',version)
if not major or int(major[1])<17:raise SystemExit('Java 17+ required')
if not (home/'lib/deployment-ops.jar').is_file():raise SystemExit('Build lib/deployment-ops.jar first')
config=state/'manager.env'
if not config.exists() and len(os.environ.get('ADMIN_PASSWORD',''))<6:raise SystemExit('Supply ADMIN_PASSWORD (6+ characters) for initialization')
try:account=pwd.getpwnam('deployment-ops')
except KeyError:
    subprocess.run(['useradd','--system','--user-group','--home-dir','/nonexistent','--shell','/usr/sbin/nologin','deployment-ops'],check=True)
    account=pwd.getpwnam('deployment-ops')
state.mkdir(parents=True,exist_ok=True)
os.chmod(state,0o750);os.chown(state,0,account.pw_gid)
for name in ['data','logs','tmp','backups','upgrades']:
    folder=state/name;folder.mkdir(exist_ok=True);os.chmod(folder,0o750);os.chown(folder,account.pw_uid,account.pw_gid)
for name in ['state','packages','trust','apps']:
    folder=state/name;folder.mkdir(exist_ok=True);os.chmod(folder,0o700)
if not config.exists():
    values={'OPS_HOME':home,'OPS_DATA_MOUNT':mount,'OPS_DATA_DIR':state/'data','OPS_TMP_DIR':state/'tmp',
        'OPS_STATE':state/'state','OPS_PACKAGES':state/'packages','OPS_TRUST':state/'trust','OPS_APP_DATA':state/'apps',
        'OPS_BIND':a.bind,'OPS_PORT':a.port,'OPS_EXECUTOR_PORT':a.executor_port,
        'OPS_EXECUTOR_URL':'http://127.0.0.1:'+str(a.executor_port),'JAVA_BIN':a.java,
        'OPS_DB_PASSWORD':secrets.token_hex(32),'EXECUTOR_TOKEN':secrets.token_hex(32),
        'ADMIN_PASSWORD':os.environ['ADMIN_PASSWORD'],'OPS_READ_ONLY':'0' if a.enable_writes else '1'}
    values.update(OPS_PLATFORM_SERVICE=a.name,OPS_CONFIG_FILE=config)
    if any('\n' in str(value) or '\r' in str(value) or '\0' in str(value) for value in values.values()):raise SystemExit('Configuration contains forbidden control characters')
    # systemd EnvironmentFile quoting, never shell evaluation.
    text=''.join(key+'="'+str(value).replace('\\','\\\\').replace('"','\\"')+'"\n' for key,value in values.items())
    with config.open('x') as output:output.write(text)
    os.chmod(config,0o640);os.chown(config,0,account.pw_gid)
else:
    existing=config.read_text()
    additions={'OPS_PLATFORM_SERVICE':a.name,'OPS_CONFIG_FILE':str(config)}
    with config.open('a') as output:
        for key,value in additions.items():
            if not any(line.startswith(key+'=') for line in existing.splitlines()):output.write(key+'="'+value+'"\n')
for suffix,filename in [('', 'deployment-ops.service'),('-executor','deployment-ops-executor.service')]:
    target=Path('/etc/systemd/system')/(a.name+suffix+'.service')
    content=(home/'deploy/native'/filename).read_text().replace('/mnt/disk01/deployment-ops',str(state)).replace('/opt/deployment-ops',str(home)).replace('/etc/deployment-ops/manager.env',str(config))
    if target.exists() and target.read_text()!=content:raise SystemExit('Existing service differs; use upgrade workflow: '+str(target))
    target.write_text(content)
subprocess.run(['systemctl','daemon-reload'],check=True)
subprocess.run(['systemctl','enable','--now',a.name+'-executor.service',a.name+'.service'],check=True)
print('Installed '+a.name+' at http://'+a.bind+':'+str(a.port)+'; existing credentials retained')
