import unittest,tempfile,json,sys,threading,time,copy,subprocess,os,base64
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'executor'))
from package_store import validate_manifest,verify_signature,member_name,PackageStore,atomic
from installations import Installer,parameters,render

def manifest():
 return {'schemaVersion':1,'name':'test-os','version':'1.0','platform':'linux/amd64','parameters':{'password':{'secret':True,'generate':True,'required':True}},'images':{'app':{'id':'sha256:'+'a'*64,'archive':'images/app.tar','size':1}},'volumes':{'data':{}},'components':[{'id':'database','image':'app','health':{'type':'tcp','port':5432}},{'id':'init','kind':'job','image':'app','dependsOn':['database'],'retrySafe':True},{'id':'app','image':'app','dependsOn':['init'],'environment':{'PASSWORD':'${password}'},'health':{'type':'http','port':8080}}]}
class Engine:
 def __init__(self):self.containers={};self.calls=[];self.counter=0
 def __call__(self,method,path,body=None):
  self.calls.append((method,path));value=json.loads(body) if body else {}
  if path=='/info':return {'Architecture':'x86_64','OSType':'linux','NCPU':8,'MemTotal':1024**4}
  if path.startswith('/images/'):return {'Id':'sha256:'+'a'*64}
  if path.startswith('/containers/json'):return []
  if path=='/containers/self/json':return {'NetworkSettings':{'Networks':{'ops-env-customer':{}}}}
  if path.startswith('/networks/') and method=='GET':raise ValueError('No such network')
  if path=='/networks/create':return {'Id':'network'}
  if path.startswith('/volumes/'):
   if method=='GET':raise ValueError('No such volume')
   return value
  if path.startswith('/containers/create'):
   self.counter+=1;ident=str(self.counter)*64;name=path.split('name=')[1];self.containers[ident]={'Id':ident,'Name':'/'+name,'Config':value,'State':{'Status':'created','Running':False,'ExitCode':0}};return {'Id':ident}
  if path.startswith('/containers/'):
   ident=path.split('/')[2];c=next((c for c in self.containers.values() if c['Id']==ident or c['Name']=='/'+ident),None)
   if c is None:raise ValueError('No such container')
   if path.endswith('/json'):return copy.deepcopy(c)
   if '/start' in path:
    kind=c['Config']['Labels']['deployment-ops.component'];c['State']={'Status':'exited' if kind=='init' else 'running','Running':kind!='init','ExitCode':0};return {}
   if '/stop' in path:c['State']['Running']=False;c['State']['Status']='exited';return {}
   if '/rename' in path:c['Name']='/'+path.split('name=')[1];return {}
  return {}
class Store:
 def __init__(self,folder,m):self.folder=folder;self.m=m;atomic(folder/'receipt.json',{'checksums':{'manifest.json':'0'*64,'images/app.tar':'0'*64}})
 def package(self,digest):return self.folder,copy.deepcopy(self.m)
 def free(self,need):pass
class ControlledInstaller(Installer):
 def probe_ports(self,ports):pass
 def health(self,c,ident,network,job):
  self.order.append(c['id'])
  if self.fail==c['id']:raise ValueError('Deliberate gate failure')
class InstallationTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.m=manifest();self.engine=Engine();self.store=Store(self.root/'package',self.m);self.runner=ControlledInstaller(self.root/'state',self.store,self.engine,lambda *a:None,threading.Lock(),'self');self.runner.order=[];self.runner.fail=None
 def tearDown(self):
  for _ in range(100):
   if not self.runner.busy:break
   time.sleep(.02)
  self.temp.cleanup()
 def start(self):
  spec={'environment':'customer','package':'b'*64,'parameters':{}};review=self.runner.preflight(spec);self.assertTrue(review['ok'],review);spec['reviewToken']=review['reviewToken'];job=self.runner.submit(spec,'admin')
  for _ in range(200):
   if not self.runner.busy:return self.runner.get(job['id'])
   time.sleep(.01)
  self.fail('Worker did not finish')
 def test_signed_schema_rejects_cycles_and_host_bind(self):
  m=manifest();m['components'][0]['dependsOn']=['app']
  with self.assertRaises(ValueError):validate_manifest(m,{'images/app.tar':'0'*64})
  m=manifest();m['components'][0]['mounts']=[{'volume':'data','target':'/var/run/docker.sock','type':'bind'}]
  with self.assertRaises(ValueError):validate_manifest(m,{'images/app.tar':'0'*64})
 def test_dependency_order_secret_plan_and_persistence(self):
  job=self.start();self.assertEqual(job['status'],'succeeded');self.assertEqual(self.runner.order,['database','init','app']);self.assertNotIn('password',job['parameters'])
  private=json.loads((self.runner.path(job['id'])/'private.json').read_text());secret=private['parameters']['password'];self.assertNotIn(secret,json.dumps(job));self.assertEqual(self.engine.containers[job['steps'][-1]['container']]['Config']['Env'],['PASSWORD='+secret])
  replacement=ControlledInstaller(self.root/'state',self.store,self.engine,lambda *a:None,threading.Lock(),'self');self.assertEqual(replacement.get(job['id'])['status'],'succeeded')
 def test_failed_install_preserves_volumes_and_resumes(self):
  self.runner.fail='app';job=self.start();self.assertEqual(job['status'],'failed');self.assertEqual(job['steps'][-1]['status'],'failed');self.assertFalse(any(method=='DELETE' and path.startswith('/volumes/') for method,path in self.engine.calls))
  self.runner.fail=None;self.runner.resume(job['id'])
  for _ in range(200):
   if not self.runner.busy:break
   time.sleep(.01)
  self.assertEqual(self.runner.get(job['id'])['status'],'succeeded');self.assertEqual(self.runner.order.count('init'),1)
 def test_upgrade_capacity_credits_resident_memory_but_not_file_cache(self):
  from unittest.mock import patch
  for c in self.m['components']:c['memoryBytes']=10000
  job=self.start();self.assertEqual(job['status'],'succeeded');self.m['requirements']={'memoryBytes':1024**4+10000}
  spec={'environment':'customer','package':job['package'],'parameters':{}}
  with patch('installations.Path') as path:
   path.return_value.exists.return_value=False
   self.runner.engine=lambda method,url,body=None: {'memory_stats':{'usage':30000,'stats':{'rss':20000}}} if '/stats?' in url else self.engine(method,url,body)
   self.assertTrue(self.runner.preflight(spec)['ok'])
   self.runner.engine=lambda method,url,body=None: {'memory_stats':{'usage':30000,'stats':{'inactive_file':30000}}} if '/stats?' in url else self.engine(method,url,body)
   self.assertFalse(self.runner.preflight(spec)['ok'])
 def test_failed_new_attempt_restores_reused_stopped_services(self):
  self.runner.fail='app';first=self.start();second=self.start();self.assertNotEqual(first['id'],second['id']);self.assertEqual(second['status'],'failed');self.assertFalse(any(c['State']['Running'] for c in self.engine.containers.values()))
 def test_resume_recovers_creation_ownership_after_journal_gap(self):
  self.runner.fail='app';job=self.start();job['steps'][-1].pop('createdByRun',None);atomic(self.runner.path(job['id'])/'job.json',job)
  self.runner.resume(job['id'])
  while self.runner.busy:time.sleep(.01)
  recovered=self.runner.get(job['id']);self.assertEqual(recovered['status'],'failed');self.assertTrue(recovered['steps'][-1]['createdByRun']);self.assertFalse(self.engine.containers[recovered['steps'][-1]['container']]['State']['Running'])
 def test_executor_restart_marks_inflight_for_reconciliation(self):
  ident='c'*32;atomic(self.root/'state'/ident/'job.json',{'id':ident,'status':'running'})
  replacement=ControlledInstaller(self.root/'state',self.store,self.engine,lambda *a:None,threading.Lock(),'self');self.assertEqual(replacement.get(ident)['status'],'interrupted')
 def test_unknown_parameters_and_unsafe_paths(self):
  with self.assertRaises(ValueError):parameters({}, {'malicious':'value'})
  for name in ['../escape','/root/file','a/../../b','a//b']:
   with self.assertRaises(ValueError):member_name(name)
 def test_template_is_not_an_expression_interpreter(self):
  self.assertEqual(render('jdbc:${host}',{'host':'database'}),'jdbc:database')
  with self.assertRaises(ValueError):render('${missing}',{})
 def test_upload_offset_is_durable_and_bounded(self):
  store=PackageStore(self.root/'uploads',self.root/'trust',self.engine,lambda f:None);store.reserve=0
  meta=store.create_upload({'size':6,'sha256':'a'*64});store.chunk(meta['id'],0,b'abc')
  restored=PackageStore(self.root/'uploads',self.root/'trust',self.engine,lambda f:None);restored.reserve=0
  self.assertEqual(restored.upload_status(meta['id'])['offset'],3)
  with self.assertRaises(ValueError):restored.chunk(meta['id'],0,b'abc')
  self.assertEqual(restored.chunk(meta['id'],3,b'def')['offset'],6)
 def test_interrupted_import_reuses_completed_upload(self):
  store=PackageStore(self.root/'uploads',self.root/'trust',self.engine,lambda f:None);store.reserve=0
  meta=store.create_upload({'size':3,'sha256':'a'*64});store.chunk(meta['id'],0,b'abc');meta=store.upload_status(meta['id']);meta['status']='verifying';atomic(store.upload_file(meta['id']).with_suffix('.json'),meta)
  restored=PackageStore(self.root/'uploads',self.root/'trust',self.engine,lambda f:None)
  self.assertEqual(restored.upload_status(meta['id'])['status'],'uploading');self.assertEqual(restored.upload_status(meta['id'])['offset'],3)
 def test_same_release_retains_secrets_and_rejects_rotation(self):
  job=self.start();review=self.runner.preflight({'package':job['package'],'environment':'customer','parameters':{}});self.assertTrue(review['ok'],review)
  rejected=self.runner.preflight({'package':job['package'],'environment':'customer','parameters':{'password':'different'}});self.assertFalse(rejected['ok']);self.assertIn('rotation',rejected['checks'][-1]['detail'])
 def test_power_keeps_data_and_starts_in_dependency_order(self):
  job=self.start();self.runner.control(job['id'],'stop','admin')
  while self.runner.busy:time.sleep(.01)
  self.assertEqual(self.runner.get(job['id'])['status'],'stopped');self.runner.order=[];self.runner.control(job['id'],'start','admin')
  while self.runner.busy:time.sleep(.01)
  self.assertEqual(self.runner.get(job['id'])['status'],'succeeded');self.assertEqual(self.runner.order,['database','app']);self.assertFalse(any(method=='DELETE' and path.startswith('/volumes/') for method,path in self.engine.calls))
 def test_upgrade_requires_declared_compatibility(self):
  job=self.start();self.store.m['version']='2.0';review=self.runner.preflight({'package':'d'*64,'environment':'customer','parameters':{}});self.assertFalse(review['ok'])
class SignatureTests(unittest.TestCase):
 def test_java_rsa_signature_verified_by_executor_and_tamper_rejected(self):
  with tempfile.TemporaryDirectory() as temp:
   path=Path(temp);classes=path/'classes';classes.mkdir();classpath=str(ROOT/'backend/lib/*')
   subprocess.run(['javac','-encoding','UTF-8','-cp',classpath,'-d',str(classes),*map(str,(ROOT/'backend/src').glob('*.java'))],check=True,capture_output=True)
   cp=str(classes)+os.pathsep+classpath
   subprocess.run(['java','-cp',cp,'PackageSigner','keygen',str(path/'private.der'),str(path/'public.json'),'test-key'],check=True,capture_output=True)
   payload=b'{"manifest.json":"verified"}';(path/'checksums.json').write_bytes(payload)
   subprocess.run(['java','-cp',cp,'PackageSigner','sign',str(path/'private.der'),str(path/'checksums.json'),str(path/'signature.json'),'test-key'],check=True,capture_output=True)
   key=json.loads((path/'public.json').read_text());signature=json.loads((path/'signature.json').read_text())['signature'];verify_signature(payload,signature,key)
   with self.assertRaises(ValueError):verify_signature(payload+b'altered',signature,key)
if __name__=='__main__':unittest.main()
