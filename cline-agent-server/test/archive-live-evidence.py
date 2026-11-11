"""Archive a stopped live case through the existing Java/UserAssetService/Silo.

An archive is evidence, never a successful Native delivery. Provider settings,
capability plans, connection secrets and mutable control databases are excluded.
Original logs, failed source versions and receipts remain on the test host.
"""
from pathlib import Path
import argparse,hashlib,io,json,tarfile,subprocess
from native_silo_control import SiloControl
from resource_registry import register_container
from owned_core_observation import observe_stopped

p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--fixture',type=Path,required=True);p.add_argument('--case',required=True);p.add_argument('--revision');a=p.parse_args()
if not a.case.replace('-','').isalnum():raise ValueError('Explicit case required')
if a.revision is not None and (not a.revision.replace('-','').isalnum() or len(a.revision)>32):raise ValueError('Explicit bounded archive revision required')
folder=a.root/'runs'/a.case
if not (folder/'summary.json').is_file():raise RuntimeError('A completed driver summary is required; never archive a running case as final')
final=json.loads((folder/'container-final.json').read_text())
ident=final.get('id');observed=observe_stopped(a.root,folder)
output=a.fixture/'evidence-archives';output.mkdir(exist_ok=True)
suffix='-'+a.revision if a.revision else '';archive=output/(a.case+suffix+'.tar.gz')
if archive.exists():raise RuntimeError('Refuse to overwrite existing evidence archive')
allowed_dirs={'model','versions','delivery-versions','harness','control-states'}
allowed_private={'business-validation.private.json','independent-business-review.private.json','java-state.private.json','silo-artifacts.private.json','silo-source-review-v3.private.json',
    'execution-observation-v2.private.json','source-dataflow-review.private.json','python-execution-review.private.json',
    'source-fidelity-v4-recheck.private.json','source-fidelity-v5-recheck.private.json','source-fidelity-v6-recheck.private.json'}
excluded={'connection.private.json','config.private.json','profile.private.json','download-plan.private.json','upload-plan.private.json'}
files=[]
for path in sorted(folder.rglob('*')):
    relative=path.relative_to(folder)
    if path.is_symlink() or not path.is_file() or 'workspace' in relative.parts or path.suffix=='.db':continue
    if relative.parts[0] not in allowed_dirs and len(relative.parts)!=1:continue
    if path.name in excluded or '.private.' in path.name and path.name not in allowed_private:continue
    if any(piece.endswith('-verifier-copy') for piece in relative.parts):continue
    raw=path.read_bytes();files.append((relative,raw))
manifest={'schemaVersion':'coding_node_test_evidence_archive.v1','case':a.case,'archiveRevision':a.revision,'nativeContainerId':ident,'stoppedConfirmed':True,
    'acceptanceInferredFromArchive':False,'credentialAndCapabilityPlansExcluded':True,
    'files':[{'path':str(relative),'size':len(raw),'sha256':hashlib.sha256(raw).hexdigest()} for relative,raw in files]}
with archive.open('xb') as output_file,tarfile.open(fileobj=output_file,mode='w:gz') as tar:
    for relative,raw in files:
        entry=tarfile.TarInfo(str(relative));entry.size=len(raw);entry.mode=0o400;tar.addfile(entry,io.BytesIO(raw))
    raw=json.dumps(manifest,ensure_ascii=False,indent=2).encode();entry=tarfile.TarInfo('ARCHIVE_FILE_HASHES.json');entry.size=len(raw);entry.mode=0o400;tar.addfile(entry,io.BytesIO(raw))
digest=hashlib.sha256(archive.read_bytes()).hexdigest()
request={'case':a.case,'fileName':archive.name,'sha256':digest}
(a.fixture/'evidence-archive-request.private.json').write_text(json.dumps(request))
def register(container,name,purpose):
    info=json.loads(subprocess.check_output(['docker','inspect',container],text=True))[0]
    register_container(a.root,info,name,purpose+'; raw evidence only, no completion','evidence-archive-'+a.case)
control=SiloControl(a.root,a.fixture,a.case,'unused-no-core',register)
control.java('archive-test-evidence','raw-archive'+suffix)
receipt=json.loads((a.fixture/'evidence-archive-receipt.private.json').read_text())
if receipt.get('readbackVerified') is not True or receipt['readbackSha256']!=digest:raise RuntimeError('Silo archive readback not proven')
receipt.update(archiveFileCount=len(files),archiveFileManifestSha256=hashlib.sha256(json.dumps(manifest,ensure_ascii=False,indent=2).encode()).hexdigest(),localArchive=str(archive))
(folder/('raw-evidence-silo-archive'+suffix+'.private.json')).write_text(json.dumps(receipt,ensure_ascii=False,indent=2))
print(json.dumps({'case':a.case,'files':len(files),'size':archive.stat().st_size,'sha256':digest,'siloReadbackVerified':True,'runtimeCompletionRegistered':False}))
