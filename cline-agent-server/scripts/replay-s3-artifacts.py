"""Replay declared synthetic source/AUX artifacts using the actual native command.

No analysis program or missing output is supplied by this independent verifier.
Fresh offline containers receive no previous report, result, chart or Core state.
"""
from pathlib import Path, PurePosixPath
import argparse, hashlib, json, os, re, shutil, subprocess

p=argparse.ArgumentParser();p.add_argument('--deployment',type=Path,required=True);p.add_argument('--case-prefix',required=True);p.add_argument('--attempt',default='replay')
a=p.parse_args();root=a.deployment.resolve();os.umask(0o077)
if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',a.attempt):raise RuntimeError('Explicit replay attempt identifier required')
deployment=json.loads((root/'deployment.json').read_text());executor=json.loads((root/'private/executor.json').read_text())
engine=['docker','--host','unix://'+executor['dockerSocket']]
summaries=[]
for folder in sorted((root/'acceptance').glob(a.case_prefix+'*')):
    verified=folder/'independent-local-verification.json'
    if not verified.exists() or not json.loads(verified.read_text())['passed']:continue
    manifest=json.loads((folder/'delivery_manifest.json').read_text());state=json.loads((folder/'sealed-state.json').read_text())
    worker=root/'workers'/state['workerId'];workspace=worker/'workspace'
    registry=json.loads((root/'state/workers'/state['workerId']/'registry.json').read_text())
    events=[json.loads(line) for line in (worker/'state'/registry['nativeSessionId']/'events.jsonl').read_text().splitlines() if line]
    commands=[];declared={PurePosixPath(f['path']).name for f in manifest['artifacts'] if f['type']=='SOURCE' and f['path'].endswith('.py')}
    modules={f['path'].removesuffix('.py').replace('/','.') for f in manifest['artifacts'] if f['type']=='SOURCE' and f['path'].endswith('.py')}
    for event in events:
        if event['eventType'] not in ['tool.completed','tool.failed'] or event.get('payload',{}).get('toolName')!='run_commands':continue
        output=event['payload'].get('output',[]);output=output if isinstance(output,list) else [output]
        for receipt in output:
            command=receipt.get('query','');result=receipt.get('result','')
            invocation=re.search(r'\bpython(?:3(?:\.11)?)?\s+(?:-u\s+)?(?:(?P<file>[^\s;&]+\.py)\b|-m\s+(?P<module>[^\s;&]+))',command)
            if not invocation:continue
            if invocation['file'] and PurePosixPath(invocation['file'].strip('"\'')).name not in declared:continue
            if invocation['module'] and invocation['module'] not in modules and invocation['module']+'.__main__' not in modules:continue
            if (receipt.get('success') is True
                    and 'Traceback (most recent call last)' not in result and not re.search(r'EXIT_CODE=[1-9]|exited with code [1-9]',result)):
                commands.append({'eventId':event['eventId'],'command':command})
    if not commands:raise RuntimeError('No original successful Python program invocation: '+folder.name)
    replay=root/a.attempt/folder.name;replay.mkdir(parents=True,exist_ok=False);fresh=replay/'workspace';fresh.mkdir()
    for artifact in manifest['artifacts']:
        if artifact['type'] not in ['SOURCE','TEST','AUXILIARY']:continue
        relative=PurePosixPath(artifact['path'])
        if relative.is_absolute() or '..' in relative.parts:raise RuntimeError('Invalid declared source path')
        source=workspace/artifact['path'];data=source.read_bytes()
        if len(data)!=artifact['size'] or hashlib.sha256(data).hexdigest()!=artifact['sha256']:raise RuntimeError('Source changed after sealing')
        target=fresh/artifact['path'];target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
    for path in [fresh,*fresh.rglob('*')]:os.chown(path,10001,10001)
    chosen=commands[-1];(replay/'original-native-command.json').write_text(json.dumps(chosen,indent=2))
    name='cs3-'+a.attempt+'-'+folder.name
    command=engine+['create','--name',name,'--label','cline.acceptance='+folder.name,
        '--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges',
        '--memory','3g','--cpus','2','--pids-limit','256','--tmpfs','/tmp:rw,nosuid,nodev,size=128m',
        '-e','PYTHONDONTWRITEBYTECODE=1','-e','MPLCONFIGDIR=/tmp/matplotlib',
        '-v',str(fresh)+':/poc/project:rw','-w','/poc/project',deployment['imageId'],'bash','-lc',chosen['command']]
    ident=subprocess.check_output(command,text=True).strip()
    result=subprocess.run(engine+['start','-a',ident],text=True,capture_output=True,timeout=120)
    (replay/'stdout.txt').write_text(result.stdout);(replay/'stderr.txt').write_text(result.stderr)
    info=json.loads(subprocess.check_output(engine+['inspect',ident]))[0];outputs=[]
    for artifact in manifest['artifacts']:
        if artifact['type'] not in ['REPORT','RESULT','CHART']:continue
        target=fresh/artifact['path'];actual=hashlib.sha256(target.read_bytes()).hexdigest() if target.is_file() else None
        outputs.append({'path':artifact['path'],'type':artifact['type'],'expectedSha256':artifact['sha256'],
            'actualSha256':actual,'byteIdentical':actual==artifact['sha256']})
    proof={'case':folder.name,'imageId':info['Image'],'containerId':ident,'running':info['State']['Running'],
        'exitCode':info['State']['ExitCode'],'originalCommandEventId':chosen['eventId'],'network':info['HostConfig']['NetworkMode'],
        'noOriginalOutputCopied':True,'noCoreStateCopied':True,'outputs':outputs,
        'passed':info['State']['ExitCode']==0 and not info['State']['Running'] and bool(outputs) and all(o['byteIdentical'] for o in outputs),
        'realSiloAcceptance':False,'applicationHttpSpiAcceptance':False,
        'verifierSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    (replay/'verification.json').write_text(json.dumps(proof,indent=2));summaries.append(proof)
(root/'evidence'/('independent-source-'+a.attempt+'.json')).write_text(json.dumps(summaries,indent=2))
print(json.dumps({'replays':len(summaries),'passed':sum(s['passed'] for s in summaries),'formalBusinessAcceptance':False}))
