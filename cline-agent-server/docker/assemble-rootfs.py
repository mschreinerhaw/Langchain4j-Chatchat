"""Copy only explicit OS tools and ELF dependencies; no SDK/application layers in final scratch image."""
import pathlib,sys,shutil,subprocess,re,os,json,hashlib
dest=pathlib.Path(sys.argv[1]);runtime=pathlib.Path(sys.argv[2]);dest.mkdir(parents=True,exist_ok=True)
copied={};files=[]
def copy_binary(source,target=None):
 source=pathlib.Path(source);target=target or str(source);actual=source.resolve();out=dest/target.lstrip('/')
 if out.exists():return
 out.parent.mkdir(parents=True,exist_ok=True)
 if str(actual) in copied:os.link(copied[str(actual)],out);return
 shutil.copy2(actual,out);copied[str(actual)]=out
 files.append({'path':target,'sha256':hashlib.sha256(out.read_bytes()).hexdigest()})
 if actual.read_bytes()[:4]!=b'\x7fELF':return
 result=subprocess.run(['ldd',str(actual)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
 if 'not found' in result.stdout:raise RuntimeError('Unresolved dynamic library: '+result.stdout)
 for lib in re.findall(r'(?:=>\s+|^\s*)(/[^\s]+)',result.stdout,re.M):copy_binary(lib)
tools='node bash sh git env cat ls pwd mkdir rmdir rm cp mv ln chmod touch stat readlink realpath head tail wc cut sort tr sed grep awk find xargs tee seq date sleep timeout uname id tar gzip du test printf'.split()
for tool in tools:
 path=shutil.which(tool)
 if not path:raise RuntimeError('Required OS tool missing: '+tool)
 copy_binary(path)
for name in ['sh','bash']:copy_binary(shutil.which(name),'/bin/'+name)
gitexec=pathlib.Path(subprocess.check_output(['git','--exec-path'],text=True).strip())
for p in gitexec.iterdir():
 if p.is_file():copy_binary(p)
for p in runtime.rglob('*.node'):
 result=subprocess.check_output(['ldd',str(p)],text=True)
 if 'not found' in result:raise RuntimeError(result)
 for lib in re.findall(r'(?:=>\s+|^\s*)(/[^\s]+)',result,re.M):copy_binary(lib)
for name in ['/etc/os-release','/etc/ssl/certs/ca-certificates.crt']:
 copy_binary(name)
for path,text in {'/etc/passwd':'root:x:0:0:root:/root:/bin/sh\ncline:x:1000:1000:Cline:/home/cline:/bin/sh\n','/etc/group':'root:x:0:\ncline:x:1000:\n','/etc/nsswitch.conf':'hosts: files dns\npasswd: files\ngroup: files\n','/etc/gitconfig':'[init]\n\tdefaultBranch = main\n'}.items():
 p=dest/path.lstrip('/');p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
for name in ['tmp','proc','dev','run','home/cline','poc/state']:(dest/name).mkdir(parents=True,exist_ok=True)
os.chmod(dest/'tmp',0o1777);os.chown(dest/'home/cline',1000,1000);os.chown(dest/'poc/state',1000,1000)
p=dest/'usr/share/cline-server/os-files.json';p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps({'policy':'Explicit OS executables, ELF libraries, system CA only; no SDK or application directories','files':files},indent=2))
print(json.dumps({'copiedOSFiles':len(files),'node':subprocess.check_output(['node','--version'],text=True).strip()}))
