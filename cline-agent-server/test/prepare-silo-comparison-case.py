"""Prepare one new authorized fixture for a predeclared comparison repetition.

Keeps the reference's Java bytecode, original snapshots and authorization owner.
Creates only an explicitly registered schema in the owned acceptance database.
This is not an application HTTP authorization or a model task implementation.
"""
from pathlib import Path
import argparse,fcntl,hashlib,json,os,re,subprocess
from native_silo_control import SiloControl
from resource_registry import register_container

p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--kind',choices=['native','cli'],required=True);p.add_argument('--index',type=int,required=True);p.add_argument('--reference-index',type=int,required=True);p.add_argument('--java-release-sha256');p.add_argument('--java-archive',type=Path);a=p.parse_args()
if not 1<=a.index<=100 or not 1<=a.reference_index<=100:raise ValueError('Bounded explicit independent repetition index required')
prefix='customer-fixture-' if a.kind=='native' else 'official-cli-fixture-'
case=('customer-silo-' if a.kind=='native' else 'official-cli-customer-')+str(a.index)
fixture=a.root/(prefix+str(a.index));reference=a.root/(prefix+str(a.reference_index))
if fixture.exists() or (a.root/'runs'/case).exists():raise RuntimeError('Refusing to recreate or redispatch an existing case')
config=json.loads((a.root/'private'/(reference.name+'.json')).read_text())
command=json.loads((a.root/'private'/(reference.name+'-java-command.json')).read_text())
reference_command=list(command)
if a.java_release_sha256:
 if not re.fullmatch(r'[0-9a-f]{64}',a.java_release_sha256):raise ValueError('Explicit immutable verifier digest required')
 archive=a.java_archive or a.root/'native-silo-driver-v6.zip'
 if hashlib.sha256(archive.read_bytes()).hexdigest()!=a.java_release_sha256:raise RuntimeError('Verifier archive digest differs from declared cohort')
 classes=a.root/('java-release-'+a.java_release_sha256[:16])/'classes'
 if not classes.is_dir():raise RuntimeError('Verified immutable Java release must already exist')
 old={part for value in command for part in value.split(':') if part.startswith(str(a.root/'java-release-')) and part.endswith('/classes')}
 if len(old)!=1:raise RuntimeError('Ambiguous reference Java overlay')
 old_classes=old.pop()
 command=[value.replace(old_classes,str(classes)) for value in command]
proof=a.root/'evidence/postgres-acceptance-space.json';record=json.loads(proof.read_text())
if not re.fullmatch(r'coding_node_acceptance_[0-9_]+',record['database']):raise RuntimeError('Owned acceptance database required')
schema=('customer_fixture_' if a.kind=='native' else 'official_cli_fixture_')+str(a.index)
info=json.loads(subprocess.check_output(['docker','inspect',record['postgresContainerId']],text=True))[0]
if not info['State']['Running']:raise RuntimeError('Shared SQL service unavailable; do not start/stop it')
user=(a.root/'private/postgres-acceptance.user').read_text().strip()
def sql(statement):
 result=subprocess.run(['docker','exec','-i',info['Id'],'psql','-X','-U',user,'-d',record['database'],'-v','ON_ERROR_STOP=1','-At'],input=statement,text=True,capture_output=True,timeout=30)
 if result.returncode:raise RuntimeError('Owned schema operation failed: '+result.stderr[-400:])
 return result.stdout.strip()
with (proof.parent/'postgres-schema-registration.lock').open('a') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX);record=json.loads(proof.read_text())
 if schema in record['registeredSchemas'] or sql("select count(*) from pg_namespace where nspname='"+schema+"';")!='0':raise RuntimeError('Existing SQL namespace cannot be claimed')
 sql('CREATE SCHEMA "'+schema+'" AUTHORIZATION "'+user.replace('"','""')+'";')
 record['registeredSchemas'].append(schema);temporary=proof.with_suffix('.tmp');temporary.write_text(json.dumps(record,indent=2));os.replace(temporary,proof)
fixture.mkdir(mode=0o700);preparation='prepare-'+case;(a.root/'runs'/preparation).mkdir(mode=0o700)
config.update(root=str(fixture),taskId=('coding-node-customer-' if a.kind=='native' else 'coding-node-cli-customer-')+str(a.index),runId=('coding-node-customer-20261111-' if a.kind=='native' else 'coding-node-cli-customer-20261111-')+str(a.index),jdbcUrl='jdbc:postgresql://127.0.0.1:'+str(record['hostPort'])+'/'+record['database']+'?currentSchema='+schema)
private=a.root/'private'/(fixture.name+'.json');private.write_text(json.dumps(config));private.chmod(0o600)
command[-1]=str(private);specific=a.root/'private'/(fixture.name+'-java-command.json');specific.write_text(json.dumps(command));specific.chmod(0o600)
def register(ident,name,purpose):register_container(a.root,json.loads(subprocess.check_output(['docker','inspect',ident],text=True))[0],name,purpose+'; fixed independent repetition preparation',preparation)
control=SiloControl(a.root,fixture,preparation,'unused-no-agent',register);state=control.java('prepare','init')
def inputs(plan):return [(Path(i['path']).name,i['size'],i['sha256']) for i in plan['inputs']]
actual=inputs(json.loads((fixture/'worker-plan.private.json').read_text()));expected=inputs(json.loads((reference/'worker-plan.private.json').read_text()))
if actual!=expected:raise RuntimeError('Original snapshot hashes differ; no model dispatch authorized by this test plan')
(a.root/'evidence'/('prepared-'+case+'.json')).write_text(json.dumps({'case':case,'reference':reference.name,'registeredSchema':schema,'originalInputsIdentical':True,'javaCommandReferenceUnchangedExceptPrivateConfig':command[:-1]==reference_command[:-1],'declaredNewCohortVerifierSha256':a.java_release_sha256,'referenceFixtureModified':False,'inputFileHashes':actual,'preparationOnly':True},indent=2))
print(json.dumps({'case':case,'prepared':state['status']=='RUNNING','sameOriginalInputHashes':True,'schema':schema}))
