import unittest,importlib.util,os,tempfile,json,sys
from unittest.mock import patch
os.environ['EXECUTOR_TOKEN']='test-token'
_tmp=tempfile.TemporaryDirectory();os.environ['OPS_STATE']=_tmp.name
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'executor'))
spec=importlib.util.spec_from_file_location('agent',Path(__file__).resolve().parents[1]/'executor/agent.py');a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)
class SafetyTests(unittest.TestCase):
 def test_reject_invalid_id(self):
  for s in ['../../etc/passwd','abc;id','--privileged','name','a'*65]:
   with self.assertRaises(ValueError):a.cid(s)
 def test_protect_system(self):
  with patch.object(a,'docker',return_value={'Config':{'Labels':{'com.docker.compose.project':'deployment-ops'}}}):
   with self.assertRaises(ValueError):a.protected('a'*64)
 def test_manifest_forbids_host_mounts(self):
  with self.assertRaises(ValueError):a.release({'application':'demo','volumes':['/:/host']})
 def test_unowned_container_not_changed(self):
  calls=[]
  def engine(method,path,body=None):
   calls.append(method)
   return {'Id':'a'*64,'Config':{'Labels':{}}}
  with patch.object(a,'docker',side_effect=engine):
   with self.assertRaises(ValueError):a.release({'application':'demo','image':'local:v1','ports':{},'healthPort':80,'healthPath':'/'})
  self.assertNotIn('POST',calls)
 def test_failed_release_restores_previous(self):
  calls=[]
  def engine(method,path,body=None):
   calls.append((method,path))
   if path.startswith('/images/'):return {}
   if path=='/containers/ops-app-demo/json':return {'Id':'a'*64,'Config':{'Labels':{'deployment-ops.managed':'true'}}}
   if path.startswith('/containers/create'):return {'Id':'b'*64}
   if path.endswith('/json'):return {'State':{'Running':False}}
   return {}
  with patch.object(a,'docker',side_effect=engine),patch.object(a.time,'sleep'):
   with self.assertRaises(ValueError):a.release({'application':'demo','image':'local:v1','ports':{},'healthPort':80,'healthPath':'/'})
  self.assertIn(('POST','/containers/'+'a'*64+'/start'),calls)
  records=[json.loads(p.read_text()) for p in a.STATE.glob('*.json')]
  self.assertTrue(any(r['status']=='rolled-back' for r in records))
 def test_executor_authentication_and_allowlist(self):
  import threading,urllib.request,urllib.error
  server=a.ThreadingHTTPServer(('127.0.0.1',0),a.Handler)
  worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
  try:
   url='http://127.0.0.1:'+str(server.server_port)
   for token,status in [('wrong',401),('test-token',404)]:
    with self.assertRaises(urllib.error.HTTPError) as context:
     urllib.request.build_opener(urllib.request.ProxyHandler({})).open(urllib.request.Request(url+'/shell',headers={'Authorization':'Bearer '+token}),timeout=2)
    self.assertEqual(context.exception.code,status)
    context.exception.read();context.exception.close()
  finally:server.shutdown();server.server_close()
 def test_pending_release_recovers_on_restart(self):
  file=a.STATE/'12345678901234567890.json'
  file.write_text(json.dumps({'id':'12345678901234567890','application':'restart','status':'pending','previous':'c'*64,'container':'d'*64}))
  with patch.object(a,'docker',return_value={}) as engine:a.recover_pending()
  self.assertEqual(json.loads(file.read_text())['status'],'interrupted-restored')
  self.assertTrue(any(call.args[1]=='/containers/'+'c'*64+'/start' for call in engine.call_args_list))
 def test_startup_reconciliation_does_not_change_workloads(self):
  file=a.STATE/'12345678901234567891.json'
  file.write_text(json.dumps({'id':'12345678901234567891','status':'pending','previous':'c'*64,'container':'d'*64}))
  with patch.object(a,'docker') as engine:a.mark_pending_unknown()
  engine.assert_not_called()
  self.assertEqual(json.loads(file.read_text())['status'],'unknown')
if __name__=='__main__':unittest.main()
