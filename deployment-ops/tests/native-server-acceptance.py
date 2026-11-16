"""Real Linux bootstrap isolation check. Requires root; never stops business services.

This checks the bootstrap only, not the V1 application deployment acceptance.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time
import secrets

p = argparse.ArgumentParser()
p.add_argument('--jar', type=Path, required=True)
p.add_argument('--java', required=True)
p.add_argument('--work', type=Path, required=True)
p.add_argument('--report', type=Path, required=True)
a = p.parse_args()
a.work.mkdir(parents=True,exist_ok=True)
password=secrets.token_hex(24)
unit = 'deployment-ops-bootstrap-test-' + str(int(time.time()))
results = {}

def run(*args,input=None):
    result = subprocess.run(args, input=input,text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
    return {'exit': result.returncode, 'output': result.stdout}

def inventory():
    ids = run('docker', 'ps', '-q')['output'].split()
    return run('docker', 'inspect', '--format', '{{.Id}} {{.State.StartedAt}} {{.State.Running}}', *ids) if ids else {'exit': 0, 'output': ''}

def health():
    for attempt in range(30):
        pid = run('systemctl', 'show', unit, '-p', 'MainPID', '--value')['output'].strip()
        if pid and pid != '0':
            result = run('nsenter', '-t', pid, '-n', 'curl', '-fsS', 'http://127.0.0.1:19240/actuator/health')
            if result['exit'] == 0:
                return result
        time.sleep(1)
    return {'exit': 1, 'output': 'Health check timed out'}

def authenticated_bootstrap(create=False):
    pid=run('systemctl','show',unit,'-p','MainPID','--value')['output'].strip()
    program='''import sys,json,urllib.request,http.cookiejar
config=json.load(sys.stdin)
op=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def req(path,data=None):
 with op.open(urllib.request.Request('http://127.0.0.1:19240/api'+path,data=json.dumps(data).encode() if data is not None else None,headers={'Content-Type':'application/json','X-Ops-Request':'1'}),timeout=10) as response:return json.load(response)
login=req('/login',{'username':'admin','password':config['password']})
if config['create']:req('/entries',{'name':config['entry'],'url':'http://127.0.0.1:19399'})
assert any(entry['name']==config['entry'] for entry in req('/entries'))
inventory=req('/inventory');assert inventory and all('error' in node for node in inventory)
assert req('/me')['role']=='admin'
print(json.dumps({'login':login['role'],'persistentEntry':True,'dockerUnavailable':True,'businessNetworkUnavailable':True,'apiHealth':req('/health')}))
'''
    return run('nsenter','-t',pid,'-n','python3','-c',program,input=json.dumps({'password':password,'create':create,'entry':unit}))

results['before'] = inventory()
try:
    results['start'] = run('systemd-run', '--unit=' + unit,
        '--property=PrivateNetwork=yes', '--property=InaccessiblePaths=/var/run/docker.sock',
        '--property=ProtectSystem=strict', '--property=ReadWritePaths=' + str(a.work),
        '--setenv=OPS_DATA_DIR=' + str(a.work / 'data'), '--setenv=OPS_PORT=19240',
        '--setenv=ADMIN_PASSWORD='+password,'--setenv=OPS_DB_PASSWORD='+password,'--setenv=EXECUTOR_TOKEN='+secrets.token_hex(32),
        a.java, '-Djava.io.tmpdir=' + str(a.work), '-jar', str(a.jar))
    results['health'] = health()
    results['bootstrap_with_dependencies_unavailable']=authenticated_bootstrap(True)
    results['restart'] = run('systemctl', 'restart', unit)
    results['health_after_restart'] = health()
    results['authenticated_state_after_restart']=authenticated_bootstrap()
    guard=unit+'-missing-mount'
    bind='/run/'+guard+'.jar'
    results['mount_guard_launch']=run('systemd-run','--unit='+guard,'--property=TemporaryFileSystem=/mnt:ro','--property=BindReadOnlyPaths='+str(a.jar)+':'+bind,a.java,'-jar',bind)
    time.sleep(2)
    results['mount_guard_result']=run('systemctl','show',guard,'-p','ExecMainStatus','--value')
    results['mount_guard_journal']=run('journalctl','-u',guard,'--no-pager')
    results['mount_guard_passed']=results['mount_guard_result']['output'].strip()=='1' and 'refusing startup without system-disk fallback' in results['mount_guard_journal']['output']
    results['host_mount_unchanged']=run('findmnt','/mnt/disk01')
finally:
    results['stop'] = run('systemctl', 'stop', unit)
    results['after'] = inventory()
    results['business_unchanged'] = results['before'] == results['after']
    results['h2_exists'] = (a.work / 'data/deployment-ops.mv.db').is_file()
    results['journal'] = run('journalctl', '-u', unit, '--no-pager')
    results['passed'] = all(results.get(key, {}).get('exit') == 0 for key in ['start', 'health', 'restart', 'health_after_restart', 'stop','bootstrap_with_dependencies_unavailable','authenticated_state_after_restart']) and results['business_unchanged'] and results['h2_exists'] and results.get('mount_guard_passed',False)
    a.report.write_text(json.dumps(results, indent=2))
print(json.dumps({k: v for k, v in results.items() if k not in ['before', 'after', 'journal']}, indent=2))
raise SystemExit(0 if results['passed'] else 1)
