"""Integration against the real bundled H2 engine, with no database service."""
import unittest, tempfile, shutil, subprocess, os, socket, time, json
from pathlib import Path
from urllib import request, error
from http.cookiejar import CookieJar

ROOT = Path(__file__).resolve().parents[1]
class EmbeddedDatabaseTests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.temp=tempfile.TemporaryDirectory();cls.home=Path(cls.temp.name)
  shutil.copy2(ROOT/'backend/schema.sql',cls.home/'schema.sql')
  (cls.home/'web').mkdir();(cls.home/'web/index.html').write_text('Offline bootstrap',encoding='ascii')
  (cls.home/'classes').mkdir()
  compile=subprocess.run(['javac','-encoding','UTF-8','-cp',str(ROOT/'backend/lib/*'),'-d',str(cls.home/'classes'),*map(str,(ROOT/'backend/src').glob('*.java'))],capture_output=True,text=True)
  if compile.returncode:raise RuntimeError(compile.stderr)
  with socket.socket() as s:s.bind(('127.0.0.1',0));cls.port=s.getsockname()[1]
  cls.env={**os.environ,'APP_HOME':str(cls.home),'OPS_HTTP_PORT':str(cls.port),'DB_URL':'jdbc:h2:file:'+str(cls.home/'database').replace('\\','/')+';MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000;WRITE_DELAY=0','DB_PASSWORD':'local-test-secret','ADMIN_PASSWORD':'test-password','EXECUTOR_TOKEN':'test-token'}
  cls.base='http://127.0.0.1:'+str(cls.port)+'/api'
  cls.start()
 @classmethod
 def start(cls):
  cls.output=open(cls.home/'server.log','a',encoding='utf-8')
  cls.proc=subprocess.Popen(['java','-cp',str(cls.home/'classes')+os.pathsep+str(ROOT/'backend/lib/*'),'Main'],env=cls.env,stdout=cls.output,stderr=subprocess.STDOUT)
  for _ in range(100):
   if cls.proc.poll() is not None:break
   try:
    with request.build_opener(request.ProxyHandler({})).open(cls.base+'/health',timeout=1) as response:
     if json.load(response)['database']=='H2 embedded':return
   except Exception:time.sleep(.1)
  cls.stop();raise RuntimeError((cls.home/'server.log').read_text())
 @classmethod
 def stop(cls):
  if cls.proc.poll() is None:cls.proc.terminate();cls.proc.wait(timeout=15)
  cls.output.close()
 @classmethod
 def tearDownClass(cls):cls.stop();cls.temp.cleanup()
 def setUp(self):self.op=request.build_opener(request.ProxyHandler({}),request.HTTPCookieProcessor(CookieJar()))
 def api(self,path,data=None):
  payload=json.dumps(data).encode() if data is not None else None
  with self.op.open(request.Request(self.base+path,data=payload,headers={'X-Ops-Request':'1'}),timeout=10) as response:return json.load(response)
 def login(self,name='admin',password='test-password'):return self.api('/login',{'username':name,'password':password})
 def test_authentication_roles_and_timestamp_serialization(self):
  self.assertEqual(self.login()['role'],'admin')
  self.api('/users',{'username':'viewer','password':'viewer-password','role':'viewer'})
  self.api('/users',{'username':'viewer','password':'updated-password','role':'viewer'})
  self.api('/logout',{})
  self.assertEqual(self.login('viewer','updated-password')['role'],'viewer')
  self.assertEqual(self.api('/me')['role'],'viewer')
  with self.assertRaises(error.HTTPError) as caught:self.api('/entries',{'name':'denied','url':'http://localhost'})
  self.assertEqual(caught.exception.code,403);caught.exception.close()
  self.api('/logout',{});self.login()
  rows=self.api('/audit');self.assertTrue(rows);self.assertIn('T',rows[0]['created_at']);self.assertTrue(rows[0]['created_at'].endswith('Z'))
 def test_failed_login_window_query(self):
  with self.assertRaises(error.HTTPError) as caught:self.login(password='wrong')
  self.assertEqual(caught.exception.code,401);caught.exception.close()
 def test_z_disk_persistence_after_process_restart(self):
  self.login();self.api('/entries',{'name':'Persisted service','project':'bootstrap','url':'http://localhost:19300'})
  before=len(self.api('/audit'));type(self).stop();type(self).start();self.op=request.build_opener(request.ProxyHandler({}),request.HTTPCookieProcessor(CookieJar()));self.login()
  self.assertTrue(any(row['name']=='Persisted service' for row in self.api('/entries')))
  self.assertGreaterEqual(len(self.api('/audit')),before)
  self.assertTrue((self.home/'database.mv.db').is_file())
if __name__=='__main__':unittest.main()
