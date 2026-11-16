"""Real native H2 restore and workload isolation acceptance, restricted to the candidate instance."""
import argparse,json,os,subprocess,time,uuid,urllib.request,http.cookiejar
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--state',type=Path,required=True);p.add_argument('--report',type=Path,required=True);p.add_argument('--url',default='http://127.0.0.1:19240');a=p.parse_args()
if a.state.resolve()!=Path('/mnt/disk01/deployment-ops-v1-candidate/state'):raise SystemExit('This acceptance runner only operates on the isolated candidate state directory')
op=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def request(path,data=None):
    with op.open(urllib.request.Request(a.url+'/api'+path,data=json.dumps(data).encode() if data is not None else None,headers={'X-Ops-Request':'1','Content-Type':'application/json'}),timeout=40) as response:return json.load(response)
def login():return request('/login',{'username':'admin','password':os.environ['OPS_TEST_PASSWORD']})
def containers():
    ids=subprocess.check_output(['docker','ps','-q'],text=True).split()
    return subprocess.check_output(['docker','inspect','--format','{{.Id}} {{.State.StartedAt}} {{.State.Running}}',*ids],text=True).splitlines() if ids else []
results={'before':containers()};login();ident=uuid.uuid4().hex
request('/entries',{'name':'restore-retained-'+ident,'url':'http://127.0.0.1:19300','project':'native-acceptance'})
backup=request('/backups',{});results['backup']=backup
request('/entries',{'name':'restore-discarded-'+ident,'url':'http://127.0.0.1:19300','project':'native-acceptance'})
job=request('/platform/maintenance',{'action':'restore','name':backup['name'],'confirmation':'RESTORE:'+backup['name']});results['job']=job
journal=Path(job['journal']);end=time.time()+180
while time.time()<end:
    if journal.exists():
        state=json.loads(journal.read_text())
        if state['status'] in ('succeeded','failed'):results['maintenance']=state;break
    time.sleep(2)
else:raise RuntimeError('Maintenance timed out; inspect the retained journal')
assert results['maintenance']['status']=='succeeded',results['maintenance']
login();entries=request('/entries');names={entry['name'] for entry in entries}
results['retained_entry_restored']='restore-retained-'+ident in names
results['post_backup_entry_absent']='restore-discarded-'+ident not in names
results['h2_health']=request('/health');results['platform_health']=request('/platform/health');results['after']=containers();results['business_unchanged']=results['before']==results['after']
results['passed']=all(results[key] for key in ['retained_entry_restored','post_backup_entry_absent','business_unchanged'])
a.report.write_text(json.dumps(results,indent=2));print(json.dumps({key:value for key,value in results.items() if key not in ['before','after','maintenance']},indent=2));raise SystemExit(0 if results['passed'] else 1)
