"""Make isolated real image-upgrade and mid-deployment failure fixtures from reviewed offline images."""
import copy,json,re,subprocess
from pathlib import Path
publisher=Path('/mnt/disk01/ops-publisher-20261116');source=publisher/'runtime-1.0.0';original=json.loads((source/'manifest.json').read_text());mapping=json.loads((source/'assets/runtime-images.json').read_text())
def image(reference):return json.loads(subprocess.check_output(['docker','image','inspect',reference]))[0]
def build(context,tag,text):
    (context/'Dockerfile').write_text(text)
    subprocess.run(['docker','build','--pull=false','--network','none','-t',tag,str(context)],check=True)
    return image(tag)['Id']
base_gateway=mapping['gateway']['reference'];base_installer=original['images']['installer']['source']
assert all(re.fullmatch('[A-Za-z0-9][A-Za-z0-9._/:@-]{0,250}',value) for value in [base_gateway,base_installer])
for kind,version,previous in [('good','1.0.1-image-qa',original['version']),('bad','1.0.2-image-failure-qa','1.0.1-image-qa')]:
    root=publisher/('runtime-gateway-'+kind);root.mkdir();(root/'assets').mkdir();gateway=root/'gateway-build';gateway.mkdir();installer=root/'installer-build';installer.mkdir()
    tag='ops-runtime-gateway-'+kind+'-qa:20261117';installer_tag='ops-runtime-installer-'+kind+'-qa:20261117'
    text='FROM '+base_gateway+'\nLABEL deployment-ops.qa="native-gateway-image-acceptance"\n'
    if kind=='bad':text+='ENTRYPOINT ["/bin/sh", "-c", "echo Intentional isolated gateway failure; exit 42"]\nCMD []\n'
    gateway_id=build(gateway,tag,text);assert gateway_id!=image(base_gateway)['Id']
    values=copy.deepcopy(mapping);values['gateway']={'reference':tag,'imageId':gateway_id};(installer/'images.json').write_text(json.dumps(values,indent=2));(root/'assets/runtime-images.json').write_text(json.dumps(values,indent=2));(root/'assets/runtime-source.json').write_bytes((source/'assets/runtime-source.json').read_bytes())
    installer_id=build(installer,installer_tag,'FROM '+base_installer+'\nCOPY images.json /opt/product/images.json\nLABEL deployment-ops.qa="native-gateway-image-acceptance"\n')
    manifest=copy.deepcopy(original);manifest.update(version=version,upgradeFrom=[previous],dataCompatibility='backward-compatible',description='QA ONLY: real gateway image identity replacement'+(' and intentional gateway exit 42' if kind=='bad' else ' with unchanged reviewed application code')+'; retire after acceptance.')
    manifest['images']['gateway']={'source':tag};manifest['images']['installer']={'source':installer_tag};(root/'manifest.json').write_text(json.dumps(manifest,indent=2));archive=root/'changed-images.tar'
    subprocess.run(['docker','save','-o',str(archive),tag,installer_tag],check=True)
    (root/'archive-map.json').write_text(json.dumps({role:str(archive if role in ('gateway','installer') else publisher/'native-images.tar') for role in manifest['images']}))
    print(json.dumps({'version':version,'manifest':str(root/'manifest.json'),'gatewayImage':gateway_id,'installerImage':installer_id,'archiveMap':str(root/'archive-map.json')}),flush=True)
