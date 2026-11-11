"""Collect a shareable summary of opt-in synthetic/cancellation acceptance.

Raw prompts, source, provider messages, credentials and S3 capabilities remain
on the authorized host. No result is upgraded to formal business acceptance.
"""
from pathlib import Path
import argparse, collections, hashlib, importlib.util, json, re, subprocess

p=argparse.ArgumentParser();p.add_argument('--deployment',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
a=p.parse_args();root=a.deployment.resolve()
deployment=json.loads((root/'deployment.json').read_text());executor=json.loads((root/'private/executor.json').read_text())
spec=importlib.util.spec_from_file_location('observation',Path(__file__).resolve().parent.parent/'test/native_execution_observation.py')
observation=importlib.util.module_from_spec(spec);spec.loader.exec_module(observation)
engine=['docker','--host','unix://'+executor['dockerSocket']]
cases=[]
for folder in sorted((root/'acceptance').iterdir()):
    if not folder.is_dir() or not re.fullmatch(r'(?:generic|cancel|native-generic)[A-Za-z0-9_-]*',folder.name):continue
    intent=json.loads((folder/'intent.json').read_text());grant=intent.get('grant',intent.get('contract'))
    if not grant or not re.fullmatch(r'[A-Za-z0-9_-]{1,96}',grant['sessionId']):raise RuntimeError('Invalid acceptance session')
    state_file=next((folder/name for name in ['final-state.json','sealed-state.json','state.json'] if (folder/name).exists()),None)
    state=json.loads(state_file.read_text()) if state_file else {}
    item={'case':folder.name,'sessionId':grant['sessionId'],'formalBusinessAccepted':False}
    for name,key in [('failure.json','failure'),('independent-local-verification.json','independentLocalVerification'),('cancel-verification.json','cancellationVerification')]:
        if (folder/name).exists():item[key]=json.loads((folder/name).read_text())
    if (folder/'delivery_manifest.json').exists():
        manifest=json.loads((folder/'delivery_manifest.json').read_text());item['artifactTypes']=dict(collections.Counter(f['type'] for f in manifest['artifacts']))
        item['artifactInventory']=[{key:f[key] for key in ['path','type','size','sha256']} for f in manifest['artifacts']]
    worker=state.get('workerId')
    if worker:
        if not re.fullmatch(r'[a-f0-9-]{36}',worker):raise RuntimeError('Unexpected Worker identity')
        registry=json.loads((root/'state/workers'/worker/'registry.json').read_text())
        native=registry.get('nativeSessionId');journal=root/'workers'/worker/'state'/native/'events.jsonl'
        events=[json.loads(line) for line in journal.read_text().splitlines() if line]
        item['nativeJournalSha256']=hashlib.sha256(journal.read_bytes()).hexdigest()
        item['nativeEvents']=dict(collections.Counter(e['eventType'] for e in events))
        item['pythonRepairObservation']=observation.observe_python_repair(events)
        info=json.loads(subprocess.check_output(engine+['inspect',registry['containerId']]))[0]
        if info['Config']['Labels'].get('cline.workerId')!=worker or info['Config']['Labels'].get('cline.executor')!=executor['instanceId']:
            raise RuntimeError('Acceptance container ownership differs')
        host=info['HostConfig'];workspace=[b for b in host['Binds'] if b.endswith(':/poc/project:rw')]
        item['container']={'containerId':info['Id'],'imageId':info['Image'],'running':info['State']['Running'],
            'startedAt':info['State']['StartedAt'],'finishedAt':info['State']['FinishedAt'],
            'memoryBytes':host['Memory'],'nanoCpus':host['NanoCpus'],'pidsLimit':host['PidsLimit'],'network':host['NetworkMode'],
            'readOnlyRoot':host['ReadonlyRootfs'],'user':info['Config']['User'],
            'privileged':host['Privileged'],'capDrop':host['CapDrop'],
            'inputReadOnly':any(b.endswith(':/poc/project/input:ro') for b in host['Binds']),
            'workspaceBind':workspace,'noDockerSocketMount':not any('docker.sock' in b for b in host['Binds'])}
    model=root/'runs'/grant['sessionId']/'model';requests=list(model.glob('*-request.json'));receipts=[]
    for file in model.glob('*-receipt.json'):
        if re.fullmatch(r'[0-9]+-receipt.json',file.name):receipts.append(json.loads(file.read_text()))
    usages=[r.get('usage') for r in receipts];complete=len(receipts)==len(requests) and bool(requests) and all(isinstance(u,dict) and isinstance(u.get('total_tokens'),int) for u in usages)
    item['providerMetering']={'requestFiles':len(requests),'receiptFiles':len(receipts),
        'tokens':sum(u['total_tokens'] for u in usages) if complete else None,
        'observedTokenSubtotal':sum(u.get('total_tokens',0) for u in usages if isinstance(u,dict)),
        'meteringComplete':complete,'cost':None,'automaticRepairs':None}
    cases.append(item)
proofs=[c['container'] for c in cases if 'container' in c]
binds=[b for c in proofs for b in c['workspaceBind']]
record={'schemaVersion':'native_s3_acceptance_summary.v1','officialSourceCommit':deployment['officialSourceCommit'],
    'imageId':deployment['imageId'],'registryRepoDigests':deployment['repoDigests'],
    'oci':json.loads((root/'oci-image/export-evidence.json').read_text()) if (root/'oci-image/export-evidence.json').exists() else None,
    'productionDefaultChanged':False,'realSiloAcceptance':False,'applicationHttpSpiAcceptance':False,
    'cases':cases,'isolation':{'distinctWorkspaceMounts':bool(proofs) and len(binds)==len(proofs) and len(set(binds))==len(binds),
        'allPinnedImages':bool(proofs) and all(c['imageId']==deployment['imageId'] for c in proofs),
        'allNetworkNone':bool(proofs) and all(c['network']=='none' for c in proofs),
        'allInputReadOnly':bool(proofs) and all(c['inputReadOnly'] for c in proofs),
        'allResourceLimitsEnforced':bool(proofs) and all(c['memoryBytes']==3221225472 and c['nanoCpus']==2000000000 and c['pidsLimit']==256 for c in proofs)}}
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(record,ensure_ascii=False,indent=2))
print(json.dumps({'cases':len(cases),'imageId':record['imageId'],'isolation':record['isolation'],'output':str(a.output)}))
