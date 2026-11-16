import json,sys,tempfile,unittest,threading,os
from unittest.mock import patch
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'executor'))
from package_store import authorize_manifest
from runtime_os import RuntimeOsAdapter

class NativeRuntimeSafetyTests(unittest.TestCase):
    def test_control_intent_and_latest_task_are_durable_before_worker_launch(self):
        with tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,{'OPS_NATIVE':'0'}):
            root=Path(directory);(root/'active').mkdir();(root/'active/job.json').write_text(json.dumps({'id':'active','active':True,'environment':'qa','status':'succeeded','runtimeContainer':'old-installer','runtimeOperation':{'command':'install'}}));(root/'env.json').write_text(json.dumps({'activeRun':'active','lastRun':'newer'}))
            class Base:
                engine=None;guard=threading.RLock();busy=False
                def get(self,ident):return json.loads((root/'active/job.json').read_text()) if ident=='active' else {'status':'rolled_back'}
                def previous(self,environment):return json.loads((root/'env.json').read_text())
                def environment(self,environment):return root/'env.json'
                def path(self,ident):return root/ident
                def launch(inner,ident,action):
                    self.assertEqual(inner.previous('qa')['lastRun'],'active');job=inner.get(ident);self.assertEqual(job['requestedControl'],'stop');self.assertNotIn('runtimeContainer',job)
            result=RuntimeOsAdapter(Base()).control('active','stop','admin');self.assertEqual(result['status'],'stopping')
    def test_completed_installer_is_observed_after_restart_without_replay(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            class Store:
                def package(self,digest):return root,{}
            class Base:
                store=Store()
                def __init__(self):self.calls=[]
                def engine(self,method,path,body=None):
                    self.calls.append((method,path))
                    if method!='GET':raise AssertionError('Completed installer was replayed')
                    return {'text':'Completed'} if '/logs?' in path else {'State':{'Running':False,'ExitCode':0}}
                def save(self,job):pass
                def get(self,ident):return {}
            base=Base();adapter=RuntimeOsAdapter(base);adapter.image_map=lambda *args:{};adapter.update_components=lambda *args:None
            job={'id':'job','package':'digest','runtimeContainer':'existing','runtimeOperation':{'package':'digest','command':'install','root':str(root),'project':'owned'}}
            adapter.run(job,{}, {'runtimeRoot':str(root),'project':'owned','parameters':{}},'install')
            self.assertTrue(base.calls);self.assertTrue(all(method=='GET' for method,path in base.calls))
    def test_unknown_running_installer_blocks_a_second_operation(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            class Store:
                def package(self,digest):return root,{}
            class Base:
                store=Store()
                def engine(self,method,path,body=None):
                    if method!='GET':raise AssertionError('A second installer was created')
                    return {'State':{'Running':True,'ExitCode':0}}
            adapter=RuntimeOsAdapter(Base());adapter.image_map=lambda *args:{}
            with self.assertRaisesRegex(ValueError,'still running'):
                adapter.run({'id':'job','package':'digest','runtimeContainer':'legacy'}, {},{'runtimeRoot':str(root),'project':'owned','parameters':{}},'install')
    def test_unsettled_latest_task_blocks_controls_on_previous_active_version(self):
        class Base:
            engine=None
            def previous(self,environment):return {'lastRun':'newer'}
            def get(self,ident):return {'status':'recovery_required'}
        with self.assertRaisesRegex(ValueError,'latest environment task'):
            RuntimeOsAdapter(Base()).require_settled_environment({'environment':'owned'})
    def test_regular_signer_cannot_gain_native_docker_administration(self):
        manifest={'name':'runtime-os','runtimeInstaller':{'protocol':'runtime-os-native/v1'}}
        with self.assertRaises(ValueError):authorize_manifest(manifest,{'allowedPackages':['runtime-os']})
        with self.assertRaises(ValueError):authorize_manifest(manifest,{'allowedPackages':['another-app'],'capabilities':['runtime-os-native/v1']})
        authorize_manifest(manifest,{'allowedPackages':['runtime-os'],'capabilities':['runtime-os-native/v1']})
    def test_image_lookup_failure_does_not_overwrite_release_tags(self):
        class Base:
            def __init__(self):self.calls=[]
            def engine(self,method,path,body=None):
                self.calls.append(method)
                if method=='GET':raise ValueError('Docker backend temporarily unavailable')
        base=Base();adapter=RuntimeOsAdapter(base)
        with self.assertRaises(ValueError):adapter.aliases({'api':{'reference':'runtime-os-api:verified','imageId':'sha256:'+'a'*64}})
        self.assertEqual(base.calls,['GET'])
    def test_native_installer_logs_remove_supplied_and_generated_secrets(self):
        class Base:engine=None
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'installation.json').write_text(json.dumps({'adminPassword':'GeneratedAdmin123','secrets':{'issuerKey':'private-generated-issuer-key'}}))
            result=RuntimeOsAdapter(Base()).redact('ProvidedPassword123 GeneratedAdmin123 private-generated-issuer-key',{'parameters':{'osAdminPassword':'ProvidedPassword123'}},root)
            self.assertNotIn('ProvidedPassword123',result);self.assertNotIn('GeneratedAdmin123',result);self.assertNotIn('private-generated-issuer-key',result)

if __name__=='__main__':unittest.main()
