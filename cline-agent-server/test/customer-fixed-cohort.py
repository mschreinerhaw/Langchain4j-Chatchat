"""Run the remaining predeclared independent customer repetitions, no retries.

Each case has a new Session, workspace, SQL schema and task scope. Agent repair
stays in its original Session. This driver never writes an analysis program,
changes a model request, declares overall acceptance or cleans resources.
"""
from pathlib import Path
import argparse,concurrent.futures,hashlib,json,os,re,socket,subprocess,time

p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--cohort-name',default='customer-fixed-cohort-20261111');p.add_argument('--native-first-new',type=int,default=9);p.add_argument('--cli-first-new',type=int,default=5);p.add_argument('--repetitions',type=int,default=4);p.add_argument('--new-samples-only',action='store_true');p.add_argument('--java-release-sha256');p.add_argument('--java-archive',type=Path);p.add_argument('--restricted-executor-config',type=Path);a=p.parse_args();os.umask(0o077)
if not re.fullmatch(r'customer-fixed-cohort-[a-z0-9-]+',a.cohort_name):raise ValueError('Explicit bounded cohort directory required')
if not 1<=a.repetitions<=5 or min(a.native_first_new,a.cli_first_new)<1 or max(a.native_first_new,a.cli_first_new)+a.repetitions>101:raise ValueError('Explicit bounded sample indices required')
native_indices=range(a.native_first_new,a.native_first_new+a.repetitions);cli_indices=range(a.cli_first_new,a.cli_first_new+a.repetitions)
cohort=a.root/a.cohort_name;cohort.mkdir(mode=0o700)
sources=['customer-fixed-cohort.py','native-silo-live.py','native_silo_control.py','live_model_audit.py','resource_registry.py','official-acp-silo-live.py','official_acp_client.py','official-cli-entrypoint.py','configure-official-cli.py','customer-objective.txt','prepare-silo-comparison-case.py']
if a.restricted_executor_config:sources+=['native_restricted_control.py']
hashes={name:hashlib.sha256((a.root/name).read_bytes()).hexdigest() for name in sources}
executor_hash=hashlib.sha256(a.restricted_executor_config.read_bytes()).hexdigest() if a.restricted_executor_config else None
java_archive_hash=hashlib.sha256(a.java_archive.read_bytes()).hexdigest() if a.java_archive else None
if a.java_archive and java_archive_hash!=a.java_release_sha256:raise RuntimeError('Declared Java archive digest differs')
frozen=cohort/'harness';frozen.mkdir()
for name in sources:(frozen/name).write_bytes((a.root/name).read_bytes())
model_hash=hashlib.sha256((a.root/'private/settings.json').read_bytes()).hexdigest()
native=json.loads((a.root/'runs/customer-silo-8/intent.json').read_text());cli=json.loads((a.root/'runs/official-cli-customer-4/intent.json').read_text())
if native['prompt']!=cli['prompt'] or native['model']!=cli['model']:raise RuntimeError('Reference tasks differ')
for case in ['customer-silo-8','official-cli-customer-4']:
 if not (a.root/'runs'/case/'summary.json').exists():raise RuntimeError('Reference task still running')
plan={'schemaVersion':'fixed_customer_comparison_cohort.v1','nativeCases':([] if a.new_samples_only else ['customer-silo-8'])+['customer-silo-'+str(i) for i in native_indices],'officialCliCases':([] if a.new_samples_only else ['official-cli-customer-4'])+['official-cli-customer-'+str(i) for i in cli_indices],
 'nativeImageId':native['imageId'],'officialCliImageId':cli['imageId'],'sourceHashes':hashes,'modelSettingsSha256':model_hash,'promptSha256':hashlib.sha256(native['prompt'].encode()).hexdigest(),
 'commonReasoningEffort':'low','commonOutputTokenPolicy':'provider-default','referenceNativeFixture':'customer-fixture-8','referenceCliFixture':'official-cli-fixture-4',
 'plannedNewSessions':2*a.repetitions,'declaredNewCohortVerifierSha256':a.java_release_sha256,'restrictedExecutorConfigSha256':executor_hash,'nativeCoreNetworkMode':'none' if executor_hash else 'direct-audit-bridge','toolComparison':'Functional equivalence; actual network, permission, workspace and ACP differences must be retained in the report','automaticRetryOrFailedTaskRedispatch':False,'overallBusinessAcceptance':'REQUIRES_INDEPENDENT_ALL_CASE_REVIEW','applicationHttpSpiAcceptance':False,'cleanupAuthorizedByThisDriver':False}
(cohort/'plan.json').write_text(json.dumps(plan,indent=2))

def branch(kind,indices,reference,image):
    journal=cohort/(kind+'-progress.jsonl')
    with journal.open('x') as out:
        for index in indices:
            if any(hashlib.sha256((a.root/name).read_bytes()).hexdigest()!=digest for name,digest in hashes.items()) or hashlib.sha256((a.root/'private/settings.json').read_bytes()).hexdigest()!=model_hash:raise RuntimeError('Frozen source/model settings changed; stop further dispatch')
            if a.restricted_executor_config and hashlib.sha256(a.restricted_executor_config.read_bytes()).hexdigest()!=executor_hash:raise RuntimeError('Frozen Executor configuration changed')
            if a.java_archive and hashlib.sha256(a.java_archive.read_bytes()).hexdigest()!=java_archive_hash:raise RuntimeError('Frozen Java verifier archive changed')
            available=int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemAvailable:')))*1024
            if available<4*1024**3:raise RuntimeError('Insufficient memory headroom; no dispatch or production service changes')
            for port in ([18530,18531] if kind=='native' else [18540]):
                with socket.socket() as sock:
                    if sock.connect_ex(('127.0.0.1',port))==0:raise RuntimeError('Dedicated test port is occupied; do not stop its owner')
            case=('customer-silo-' if kind=='native' else 'official-cli-customer-')+str(index)
            fixture=('customer-fixture-' if kind=='native' else 'official-cli-fixture-')+str(index)
            with (cohort/(case+'-preparation.log')).open('x') as log:
                preparation=['python3',str(a.root/'prepare-silo-comparison-case.py'),'--root',str(a.root),'--kind',kind,'--index',str(index),'--reference-index',str(reference)]
                if a.java_release_sha256:preparation+=['--java-release-sha256',a.java_release_sha256]
                if a.java_archive:preparation+=['--java-archive',str(a.java_archive)]
                prepared=subprocess.run(preparation,stdout=log,stderr=subprocess.STDOUT,timeout=240)
            if prepared.returncode:raise RuntimeError('Preparation failed; no uncertain task restart: '+case)
            args=['python3','-u',str(a.root/('native-silo-live.py' if kind=='native' else 'official-acp-silo-live.py')),'--root',str(a.root),'--fixture',str(a.root/fixture),'--case',case,'--image',image,'--prompt',str(a.root/'customer-objective.txt'),'--delivery-selection']
            if kind=='native':args+=['--runtime-feedback','--output-token-policy','provider-default']
            if kind=='native' and a.restricted_executor_config:args+=['--restricted-executor-config',str(a.restricted_executor_config)]
            out.write(json.dumps({'case':case,'event':'PLANNED_INDEPENDENT_SESSION_START','time':time.time(),'imageId':image})+'\n');out.flush()
            with (cohort/(case+'.log')).open('x') as log:
                run=subprocess.Popen(args,stdout=log,stderr=subprocess.STDOUT)
                try:run.wait(timeout=1500)
                except subprocess.TimeoutExpired:
                    # Let the owning driver finish its bounded stop/finality
                    # path. Killing it here could orphan a real Core container.
                    out.write(json.dumps({'case':case,'event':'DRIVER_DEADLINE_UNRESOLVED','pid':run.pid,'automaticKillOrRedispatch':False,'time':time.time()})+'\n');out.flush()
                    raise RuntimeError('Driver still owns an unresolved case; no further dispatch: '+case)
            summary=a.root/'runs'/case/'summary.json'
            out.write(json.dumps({'case':case,'event':'DRIVER_FINISHED','time':time.time(),'processExitCode':run.returncode,'summary':json.loads(summary.read_text()) if summary.exists() else None})+'\n');out.flush()
            if not summary.exists():raise RuntimeError('No terminal driver evidence; stop further dispatch, retain environment: '+case)
            # Continue the predeclared independent samples, including failures.
            # Never retry or replace a failed case to improve the pass rate.
    return kind

errors=[]
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    pending=[pool.submit(branch,'native',native_indices,8,native['imageId']),pool.submit(branch,'cli',cli_indices,4,cli['imageId'])]
    for future in concurrent.futures.as_completed(pending):
        try:future.result()
        except Exception as error:errors.append(repr(error))
(cohort/'driver-finished.json').write_text(json.dumps({'errors':errors,'taskAcceptanceInferred':False,'resourcesCleaned':False},indent=2))
print(json.dumps({'cohortDriverFinished':True,'errors':errors,'overallAcceptance':'PENDING_INDEPENDENT_REVIEW','cleanupPerformed':False}))
