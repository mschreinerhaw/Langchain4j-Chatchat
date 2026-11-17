"""Restart only the fresh acceptance platform during its first real Runtime OS deployment."""
import argparse,json,subprocess,time,urllib.request
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--report',type=Path,required=True);a=p.parse_args()
state=Path('/mnt/disk01/deployment-ops-v1-release/state');environment='runtime-release-v1';service='deployment-ops-v1-release'
def inspect(ids):return json.loads(subprocess.check_output(['docker','inspect',*ids])) if ids else []
def signature(item):return (item['Id'],item['State']['StartedAt'],item['State']['Running'])
baseline=[line.split() for line in Path('/mnt/disk01/deployment-ops-v1-validation/before-qa-control.txt').read_text().splitlines() if ' /ops-env-runtime-native-v1-' not in line]
external=[(row[0],row[2],row[3]=='true') for row in baseline];assert all(expected==signature(actual) for expected,actual in zip(external,inspect([r[0] for r in external])))
end=time.monotonic()+1200
while time.monotonic()<end:
    jobs=[json.loads(f.read_text()) for f in (state/'state/installations').glob('*/job.json')]
    candidates=[j for j in jobs if j['environment']==environment and j['status']=='running' and j.get('runtimeOperation') and j.get('runtimeContainer')]
    ids=subprocess.check_output(['docker','ps','-q','--filter','label=com.docker.compose.project=ops-env-'+environment],text=True).split()
    if candidates and len(ids)>=5:job=candidates[0];break
    time.sleep(2)
else:raise RuntimeError('No scoped active native deployment reached the restart acceptance window')
before=inspect(ids);installer=inspect([job['runtimeContainer']])[0];assert installer['State']['Running'];result={'job':job['id'],'installer':job['runtimeContainer'],'componentsBefore':[signature(c) for c in before]}
subprocess.run(['systemctl','restart',service,service+'-executor'],check=True,timeout=90)
for _ in range(60):
    try:
        with urllib.request.urlopen('http://127.0.0.1:19240/api/health',timeout=2) as response:
            if json.load(response)['status']=='UP':break
    except Exception:pass
    time.sleep(1)
else:raise RuntimeError('Platform failed to recover after restart')
after=inspect([c['Id'] for c in before]);assert [signature(c) for c in before]==[signature(c) for c in after],'A business component was restarted by platform restart'
current=inspect([job['runtimeContainer']])[0];assert current['State']['Running'] or current['State']['ExitCode']==0
assert external==[signature(c) for c in inspect([r[0] for r in external])],'An original workload changed'
record=json.loads((state/'state/installations'/job['id']/'job.json').read_text());assert record['runtimeContainer']==job['runtimeContainer']
result.update(passed=True,existingComponentsUnchanged=True,originalWorkloadsUnchanged=len(external),sameInstallerRetained=True,recoveredTaskStatus=record['status']);a.report.write_text(json.dumps(result,indent=2));print(json.dumps(result))
