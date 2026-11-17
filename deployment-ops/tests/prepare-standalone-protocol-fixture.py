"""Create a small signed protocol QA release, using only the labeled QA image.

No production image or credential is modified. Private QA signer stays on disk.
"""
import hashlib,json,os,subprocess,zipfile,sys
from pathlib import Path
assert os.geteuid()==0
base=Path('/mnt/disk01/deployment-ops-v1-validation');root=base/'standalone-protocol-media';root.mkdir(mode=0o700,exist_ok=True)
image=json.loads(subprocess.check_output(['docker','image','inspect','ops-offline-import-qa:20261117']))[0]
assert image['Config']['Labels'].get('deployment-ops.qa')=='offline-image-acceptance-20261117'
libs=root/'libs';libs.mkdir(exist_ok=True)
with zipfile.ZipFile(base/'final-alert-build/build/boot-runtime.zip') as z:
 for item in z.infolist():
  if item.filename.startswith('BOOT-INF/lib/jackson-'):(libs/Path(item.filename).name).write_bytes(z.read(item))
java='/opt/jdk-17.0.5/bin/java';cp='/mnt/disk01/ops-publisher-20261116/classes:'+str(libs/'*');key=root/'private.der';pub=root/'qa-jar-protocol.json'
def run(*args):subprocess.run(args,check=True,capture_output=True)
if not key.exists():run(java,'-cp',cp,'PackageSigner','keygen',str(key),str(pub),'qa-jar-protocol')
trust=base/'jar-entry-state/standalone/trust/qa-jar-protocol.json'
if not trust.exists():trust.write_bytes(pub.read_bytes());os.chmod(trust,0o600)
manifest={'schemaVersion':1,'name':'java-protocol-qa','version':'1.0.0','platform':'linux/amd64','parameters':{'username':{'type':'string','label':'Business username','required':True,'pattern':'[a-z]{4,16}'},'password':{'type':'string','label':'Business password','required':True,'secret':True,'pattern':'[A-Za-z0-9!]{12,64}'}},'images':{'service':{'id':image['Id'],'archive':'images/all.tar','size':image['Size']}},'components':[{'id':'initialize','kind':'job','image':'service','entrypoint':[],'command':['sh','-c','echo protocol-initialization-complete'],'retrySafe':True},{'id':'service','image':'service','dependsOn':['initialize'],'entrypoint':[],'command':['redis-server','--aclfile','/etc/redis/qa.acl'],'files':[{'source':'assets/users.acl','target':'/etc/redis/qa.acl','template':True,'mode':'0600'}],'health':{'type':'tcp','port':6379}}]}
sys.path.insert(0,str(base/'jar-entry-source/executor'));from package_store import validate_manifest
validate_manifest(manifest,{'images/all.tar','assets/users.acl'})
payload={'manifest.json':json.dumps(manifest).encode(),'assets/users.acl':b'user default off\nuser ${username} on >${password} ~* &* +@all\n'}
tar=Path('/mnt/disk01/ops-publisher-20261116/qa-offline-image/image.tar')
checks={n:hashlib.sha256(v).hexdigest() for n,v in payload.items()}
with tar.open('rb') as f:checks['images/all.tar']=hashlib.file_digest(f,'sha256').hexdigest()
inventory=root/'checksums.json';inventory.write_text(json.dumps(checks,sort_keys=True));signature=root/'signature.json';run(java,'-cp',cp,'PackageSigner','sign',str(key),str(inventory),str(signature),'qa-jar-protocol')
archive=root/'java-protocol-qa.ops.zip';assert not archive.exists()
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
 for n,v in payload.items():z.writestr(n,v)
 z.write(tar,'images/all.tar');z.write(inventory,'checksums.json');z.write(signature,'signature.json')
with archive.open('rb') as f:digest=hashlib.file_digest(f,'sha256').hexdigest()
(root/'media.json').write_text(json.dumps({'file':str(archive),'sha256':digest}));print(json.dumps({'passed':True,'sha256':digest,'mediaBytes':archive.stat().st_size,'privateSignerNotDistributed':True}))
