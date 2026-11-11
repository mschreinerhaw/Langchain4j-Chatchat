"""Read-only Java export for offline replay; does not change the task outcome.

Uses a pinned, trusted acceptance-driver archive for the export action. The
original model run keeps its original Java command, image and verifier records.
"""
from pathlib import Path
import argparse,hashlib,json,re,subprocess,zipfile
from resource_registry import register_container
from owned_core_observation import observe_stopped

p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--case',required=True);p.add_argument('--fixture',type=Path,required=True);p.add_argument('--java-archive',type=Path,required=True);p.add_argument('--sha256',required=True);p.add_argument('--official-cli',action='store_true');a=p.parse_args()
if not a.case.replace('-','').isalnum() or not re.fullmatch('[a-f0-9]{64}',a.sha256):raise ValueError('Explicit case and pinned driver hash required')
if hashlib.sha256(a.java_archive.read_bytes()).hexdigest()!=a.sha256:raise RuntimeError('Java archive does not match pinned SHA-256')
summary=json.loads((a.root/'runs'/a.case/'summary.json').read_text())
if summary.get('terminal')!=('CLI_JAVA_VALIDATED_PENDING_RECEIPT_AND_REPORT_REVIEW' if a.official_cli else 'BUSINESS_COMPLETED'):raise RuntimeError('No independently validated terminal fixture delivery to export')
observed=observe_stopped(a.root,a.root/'runs'/a.case)
for source in [Path(__file__),Path(__file__).with_name('owned_core_observation.py')]:
    raw=source.read_bytes();digest=hashlib.sha256(raw).hexdigest();archive=a.root/'runs'/a.case/'harness/independent'/digest/source.name
    archive.parent.mkdir(parents=True,exist_ok=True)
    if not archive.exists():archive.write_bytes(raw);archive.chmod(0o400)
classes=a.root/('java-release-'+a.sha256[:16])/'classes'
if not classes.exists():
    classes.mkdir(parents=True)
    with zipfile.ZipFile(a.java_archive) as archive:
        for member in archive.infolist():
            target=(classes/member.filename).resolve()
            if not target.is_relative_to(classes.resolve()) or '\\' in member.filename:raise RuntimeError('Unsafe Java archive member')
        archive.extractall(classes)
    for path in classes.rglob('*'):
        if path.is_file():path.chmod(0o444)
    for path in sorted((p for p in classes.rglob('*') if p.is_dir()),reverse=True):path.chmod(0o555)
    classes.chmod(0o555)
command=json.loads((a.root/'private'/(a.fixture.name+'-java-command.json')).read_text())
matches={part for value in command for part in value.split(':') if part.startswith(str(a.root/'java-release-')) and part.endswith('/classes')}
if len(matches)!=1:raise RuntimeError('Original immutable Java overlay must be explicit and unique')
original=matches.pop();command=[part.replace(original,str(classes)) for part in command if part!='--rm'];command[1]='create'
name='coding-node-java-silo-export-'+a.case;command[command.index('--name')+1]=name;command[-2]='export-cli-replay-snapshot' if a.official_cli else 'export-replay-snapshot';command[2:2]=['--label','runtime-os.coding.acceptance='+a.case+'-independent-replay']
created=subprocess.run(command,text=True,capture_output=True,timeout=30)
if created.returncode:raise RuntimeError('Export create failed; do not replay an uncertain create')
ident=created.stdout.strip();info=json.loads(subprocess.check_output(['docker','inspect',ident],text=True))[0]
register_container(a.root,info,name,'Read-only export of immutable Silo snapshot and selected code; derived outputs excluded from replay workspace',a.case+'-independent-replay')
log=a.root/'runs'/a.case/'registered-silo-replay-export.log'
with log.open('x') as output:result=subprocess.run(['docker','start','-a',ident],stdout=output,stderr=subprocess.STDOUT,timeout=180)
proof={'case':a.case,'containerId':ident,'exitCode':result.returncode,'exportDriverArchiveSha256':a.sha256,'originalModelRunJavaOverlayUnchanged':original,'taskOutcomeModified':False}
(log.parent/'registered-silo-replay-export-provenance.json').write_text(json.dumps(proof,indent=2))
if result.returncode:raise RuntimeError('Silo export failed; retain full log and container')
print(json.dumps(proof))
