"""Read-only proof of the reviewed 1.3 adoption on its explicitly named stage host."""
import argparse,hashlib,json,pathlib,subprocess,urllib.request

p=argparse.ArgumentParser();p.add_argument('--release-root',type=pathlib.Path,required=True);p.add_argument('--plan',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
def docker(*args):return subprocess.check_output(['docker',*args],text=True)
def inspect(*names):return json.loads(docker('inspect',*names))
before=json.loads((a.release_root/'legacy-data-before.json').read_text());plan=json.loads(a.plan.read_text())
assert plan['stageRoot']=='/opt/runtime-os-docker-stage'
original=inspect(*[c['name'] for c in before['containers']])
for old,new in zip(before['containers'],original):
 assert new['Id']==old['id'] and new['State']['Running'] and new['State']['StartedAt']==old['startedAt']
 assert sorted(new['Mounts'],key=lambda m:m['Destination'])==sorted(old['mounts'],key=lambda m:m['Destination'])
for file,sha in plan['sourceHashes'].items():assert hashlib.sha256(pathlib.Path(file).read_bytes()).hexdigest()==sha,'Original configuration changed: '+file
project='ops-env-runtime-stage-v13'
ids=docker('ps','-q','--filter','label=com.docker.compose.project='+project).split();components=inspect(*ids)
assert len(components)==17
for c in components:
 role=c['Config']['Labels']['deployment-ops.component'];expected=plan['compose']['services'][role]
 assert c['Image']==expected['image'] and c['State']['Running'] and c['State'].get('Health',{}).get('Status') in (None,'healthy')
 for mount in expected.get('volumes',[]):
  if not isinstance(mount,dict) or mount.get('type')!='bind':continue
  source=mount['source'].replace('${INSTALL_ROOT}','/mnt/disk01/ops/r/6462-stage-v13')
  assert any(m['Source']==source and m['Destination']==mount['target'] and m['RW']==(not mount.get('read_only',False)) for m in c['Mounts'])
 for option in expected.get('security_opt',[]):
  security=c['HostConfig']['SecurityOpt'];assert option!='seccomp=unconfined'
  if option.startswith('seccomp=/install/generated/'):
   filename=option.rsplit('/',1)[1];profile=json.loads(plan['generatedText'][filename])
   actual=next(s[len('seccomp='):] for s in security if s.startswith('seccomp='))
   assert json.loads(actual)==profile
  else:assert option in security
env=dict(v.split('=',1) for v in original[0]['Config']['Env'] if '=' in v);user=env.get('POSTGRES_USER','postgres')
def sql(db,query):return docker('exec','postgresql18','psql','-U',user,'-d',db,'-v','ON_ERROR_STOP=1','-Atc',query).strip()
preserved={}
for db,tables in {'runtime_os_acceptance_api':['sys_user','platform_model_config','workspace_registry','runtime_user_workspace'],'runtime_os_acceptance_mcp':['mcp_admin_user']}.items():
 baseline=before['databases'][db]['tableCounts'];count=int(sql(db,"select count(*) from information_schema.tables where table_schema='public' and table_type='BASE TABLE'"));assert count==len(baseline)
 preserved[db]={'tables':count,'stableTableCounts':{}}
 for table in tables:
  actual=int(sql(db,'select count(*) from '+table));assert actual==baseline[table],db+'.'+table+' count changed'
  preserved[db]['stableTableCounts'][table]=actual
with urllib.request.urlopen('http://127.0.0.1:18100/version.json',timeout=10) as r:version=json.load(r)
assert version['release']=='1.3' and version['existingStageDataReused']
root=pathlib.Path('/mnt/disk01/ops/r/6462-stage-v13');assert not (root/'seccomp_profile.json').exists(),'Final package must not rely on temporary candidate repair'
assert json.loads((root/'installation.json').read_text())['status']=='READY'
verification=json.loads((root/'verification.json').read_text());assert verification['status']=='PASSED' and verification['nativeCoreVerified'] and verification['pythonRuntimeVerified']
result={'passed':True,'version':version,'existingStageRoot':plan['stageRoot'],'originalContainersUnchanged':[{'name':c['Name'],'id':c['Id'],'startedAt':c['State']['StartedAt']} for c in original],'databases':preserved,'configurationHashesUnchanged':len(plan['sourceHashes']),'signedPlanSha256':hashlib.sha256(a.plan.read_bytes()).hexdigest(),'managedComponents':[{'name':c['Name'],'imageId':c['Image'],'status':c['State']['Status'],'health':c['State'].get('Health',{}).get('Status')} for c in components],'verifiedActualMounts':True,'originalSeccompEnforced':True,'candidateRepairNotRequired':True,'runtimeVerification':verification}
a.output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'passed':True,'managedComponents':len(components),'databases':preserved,'originalContainersUnchanged':len(original)}))
