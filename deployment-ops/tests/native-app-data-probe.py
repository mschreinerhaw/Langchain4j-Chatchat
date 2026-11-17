"""Own-data persistence probe in the new acceptance Runtime OS database only."""
import argparse,json,re,subprocess,uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('action',choices=['create','check','cleanup']);a=p.parse_args()
name='ops-env-runtime-release-v1-postgresql';item=json.loads(subprocess.check_output(['docker','inspect',name]))[0];assert item['Config']['Labels']['com.docker.compose.project']=='ops-env-runtime-release-v1' and item['State']['Running']
root=Path('/mnt/disk01/deployment-ops-v1-release/state');job=json.loads((root/'state/installations/d511e6d4714840fbba14c4bc631adace/job.json').read_text());assert job['status']=='succeeded'
private=json.loads((root/'state/installations/d511e6d4714840fbba14c4bc631adace/private.json').read_text());runtime=Path(private['runtimeRoot']);assert runtime.parent==Path('/mnt/disk01/ops/r')
assert any(m['Type']=='bind' and (Path(m['Source'])==runtime/'data/postgresql') for m in item['Mounts'])
file=Path('/mnt/disk01/deployment-ops-v1-validation/application-data-marker.json')
def sql(command):return subprocess.check_output(['docker','exec',name,'psql','-U','postgres','-d','live_runtime_api','-v','ON_ERROR_STOP=1','-Atc',command],text=True).strip()
if a.action=='create':
    assert not file.exists();assert not sql("select coalesce(to_regclass('public.ops_deployment_acceptance_probe')::text,'')")
    ident=uuid.uuid4().hex;sql("CREATE TABLE ops_deployment_acceptance_probe(id VARCHAR(32) PRIMARY KEY); INSERT INTO ops_deployment_acceptance_probe VALUES('"+ident+"')");record={'id':ident,'project':'ops-env-runtime-release-v1','runtimeRoot':str(runtime),'createdOwnProbeTable':True};file.write_text(json.dumps(record,indent=2));print(json.dumps({'created':True,'ownDatabase':True}))
else:
    record=json.loads(file.read_text());assert re.fullmatch('[a-f0-9]{32}',record['id']) and record['runtimeRoot']==str(runtime)
    assert sql("select id from ops_deployment_acceptance_probe where id='"+record['id']+"'")==record['id'];print(json.dumps({'passed':True,'businessDatabaseRecordRetained':True,'project':record['project']}))
    if a.action=='cleanup':
        assert record['createdOwnProbeTable'] and sql('select count(*) from ops_deployment_acceptance_probe')=='1';sql('DROP TABLE ops_deployment_acceptance_probe');record['ownProbeTableRemoved']=True;file.write_text(json.dumps(record,indent=2))
