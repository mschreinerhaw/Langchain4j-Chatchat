"""Predeclared synthetic/Core and real cancellation cohorts; never retry cases.

These combined exercises are NOT A-H separate business acceptance or S3 success.
"""
from pathlib import Path
import argparse, concurrent.futures, hashlib, json, subprocess, time

p=argparse.ArgumentParser();p.add_argument('--deployment',type=Path,required=True);p.add_argument('--repetitions',type=int,default=5)
p.add_argument('--cohort-name',default='cohort');p.add_argument('--case-prefix',default='');p.add_argument('--kind',choices=['both','generic','cancel'],default='both')
a=p.parse_args();source=Path(__file__).resolve().parent
if not all(not v or v.replace('-','').isalnum() and v.isascii() for v in [a.cohort_name,a.case_prefix]):raise RuntimeError('Explicit cohort identifiers required')
folder=a.deployment/a.cohort_name;folder.mkdir(exist_ok=False)
deployment=json.loads((a.deployment/'deployment.json').read_text())
sources={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [source/'generic-s3-live.py',source/'generic-s3-cancel-live.py',source/'native_execution_observation.py']}
settings_hash=hashlib.sha256((a.deployment/'private/gateway.json').read_bytes()).hexdigest()
cases={kind:[kind+('-'+a.case_prefix if a.case_prefix else '')+'-fixed-'+str(i) for i in range(1,a.repetitions+1)] for kind in (['generic','cancel'] if a.kind=='both' else [a.kind])}
(folder/'plan.json').write_text(json.dumps({'imageId':deployment['imageId'],'cases':cases,'sources':sources,
    'gatewayConfigurationSha256':settings_hash,'repetitions':a.repetitions,'failedCasesReplaced':False,
    'applicationHttpSpiAcceptance':False,'realSiloAcceptance':False,'separateEightScenarioAcceptance':False},indent=2))
def branch(kind):
    results=[]
    for case in cases[kind]:
        if settings_hash!=hashlib.sha256((a.deployment/'private/gateway.json').read_bytes()).hexdigest():raise RuntimeError('Frozen gateway policy changed')
        for filename,digest in sources.items():
            if digest!=hashlib.sha256((source/filename).read_bytes()).hexdigest():raise RuntimeError('Frozen harness changed')
        available=int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemAvailable:')))*1024
        if available<4*1024**3:raise RuntimeError('Insufficient memory; stop dispatch, preserve unexecuted cases')
        command=['python3','-u',str(source/('generic-s3-live.py' if kind=='generic' else 'generic-s3-cancel-live.py')),'--deployment',str(a.deployment),'--case',case]
        if kind=='generic':command+=['--runtime-feedback']
        with (folder/(case+'.log')).open('xb') as log:
            process=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT)
            try:exit_code=process.wait(timeout=720)
            except subprocess.TimeoutExpired:
                results.append({'case':case,'status':'OWNER_DRIVER_UNRESOLVED','pid':process.pid});break
        results.append({'case':case,'exitCode':exit_code,'finishedAt':time.time()})
        (folder/(kind+'-progress.json')).write_text(json.dumps(results,indent=2))
    return kind,results
errors=[];completed={}
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    for pending in concurrent.futures.as_completed([pool.submit(branch,kind) for kind in cases]):
        try:kind,results=pending.result();completed[kind]=results
        except Exception as error:errors.append(str(error))
(folder/'finished.json').write_text(json.dumps({'completed':completed,'errors':errors,'formalBusinessAcceptance':False},indent=2))
