"""Build isolated gateway upgrade/failure media recipes from an accepted native recipe.

These are acceptance fixtures, never production version releases. No live
configuration, credentials, application databases or data volumes are accessed.
"""
import argparse, copy, json, re, subprocess
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--recipe',type=Path,required=True)
p.add_argument('--archives',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
if a.output.exists():raise SystemExit('Use a new immutable fixture directory')
original=json.loads((a.recipe/'manifest.json').read_text())
mapping=json.loads((a.recipe/'assets/runtime-images.json').read_text())
archives=json.loads(a.archives.read_text())
base=mapping['gateway']['reference']
if not re.fullmatch('[A-Za-z0-9][A-Za-z0-9._/:@-]{0,250}',base):raise SystemExit('Invalid gateway reference')
previous=original['version']
for kind,version in [('good','1.3.1-upgrade-qa'),('bad','1.3.2-failure-qa')]:
    root=a.output/kind;context=root/'gateway';context.mkdir(parents=True);(root/'assets').mkdir()
    tag='ops-fresh93-gateway-'+kind+':20261118'
    dockerfile='FROM '+base+'\nLABEL deployment-ops.qa="fresh-native-recovery-acceptance"\n'
    if kind=='bad':dockerfile+='ENTRYPOINT ["/bin/sh", "-c", "echo Intentional isolated gateway failure; exit 42"]\nCMD []\n'
    (context/'Dockerfile').write_text(dockerfile)
    subprocess.run(['docker','build','--network=none','--pull=false','-t',tag,str(context)],check=True)
    identity=json.loads(subprocess.check_output(['docker','image','inspect',tag]))[0]['Id']
    values=copy.deepcopy(mapping);values['gateway']={'reference':tag,'imageId':identity}
    (root/'assets/runtime-images.json').write_text(json.dumps(values,indent=2))
    (root/'assets/runtime-source.json').write_bytes((a.recipe/'assets/runtime-source.json').read_bytes())
    manifest=copy.deepcopy(original)
    manifest.update(version=version,upgradeFrom=[previous],dataCompatibility='backward-compatible',description='QA ONLY: isolated real gateway '+('readiness failure and automatic rollback' if kind=='bad' else 'image replacement with unchanged application code'))
    # Keep existing archive member numbers stable for verified reflink reuse.
    manifest['images'].pop('gateway')
    manifest['images']['gateway']={'source':tag}
    (root/'manifest.json').write_text(json.dumps(manifest,indent=2))
    archive=root/'gateway.tar';subprocess.run(['docker','save','-o',str(archive),tag],check=True)
    archive_map=copy.deepcopy(archives);archive_map['gateway']=str(archive)
    (root/'archive-map.json').write_text(json.dumps(archive_map,indent=2))
    print(json.dumps({'kind':kind,'version':version,'image':identity,'recipe':str(root)}),flush=True)
    previous=version
