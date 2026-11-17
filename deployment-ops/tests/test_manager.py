import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'executor'))
from manager import Manager,container_metrics

CID='a'*64
IMAGE='sha256:'+'b'*64


class Engine:
    def __init__(self):
        self.writes=[]
        self.running=False
        self.refs=[]
        self.children=[]
    def __call__(self,method,path,body=None):
        if method!='GET':
            self.writes.append((method,path,body))
            return {'Id':CID}
        if path=='/containers/'+CID+'/json':
            return {'Id':CID,'Name':'/external-test','Image':IMAGE,'State':{'Running':self.running,'Status':'running' if self.running else 'exited'},'Config':{'Env':['PASSWORD=do-not-leak'],'Labels':{}},'Mounts':[{'Name':'persistent-data'}]}
        if path.startswith('/images/') and path.endswith('/json') and path!='/images/json':
            return {'Id':IMAGE,'RepoTags':['fixture:v1']}
        if path=='/containers/json?all=1':return self.refs+self.children
        if path=='/images/json':return [{'Id':IMAGE,'RepoTags':['fixture:v1']}]
        if path.startswith('/networks/'):return {'Name':'bridge','Driver':'bridge'}
        if path=='/volumes':return {'Volumes':[]}
        return {}


class ManagerTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.engine=Engine()
        self.m=Manager(self.temp.name,self.engine,read_only=False)
    def finish(self,job):
        for _ in range(100):
            value=self.m.job(job['id'])
            if value['status'] not in ('PENDING','RUNNING'):return value
            time.sleep(.01)
        self.fail('job did not finish')
    def submit(self,spec):
        plan=self.m.plan(spec,'admin')
        request={'planId':plan['id'],'confirmation':plan['target'],'idempotencyKey':'unique-test-key-12345'}
        return self.m.submit(request,'admin'),request
    def test_discovery_and_plan_never_write_and_hide_environment(self):
        plan=self.m.plan({'action':'container.delete','id':CID},'admin')
        self.assertNotIn('do-not-leak',json.dumps(plan))
        self.assertEqual(self.engine.writes,[])
        self.assertEqual(plan['snapshot']['mounts'][0]['Name'],'persistent-data')
    def test_default_read_only_blocks_execution(self):
        with patch.dict(os.environ,{},clear=True):m=Manager(Path(self.temp.name)/'readonly',self.engine)
        p=m.plan({'action':'container.start','id':CID},'admin')
        with self.assertRaisesRegex(ValueError,'read-only'):m.submit({'planId':p['id'],'confirmation':p['target'],'idempotencyKey':'readonly-key-1234'},'admin')
        self.assertFalse(self.engine.writes)
    def test_native_instance_cannot_operate_another_instances_container(self):
        original=self.m.inspect_container
        def inspected(ident):
            item=original(ident);item['Config']['Labels']={'deployment-ops.manager':'true','deployment-ops.owner':'another-instance'};return item
        self.m.inspect_container=inspected
        with patch.dict(os.environ,{'OPS_STRICT_OWNERSHIP':'1'}):
            with self.assertRaisesRegex(ValueError,'discovery-only'):self.m.plan({'action':'container.stop','id':CID},'admin')
        self.assertFalse(self.engine.writes)
    def test_actor_target_and_snapshot_are_bound_to_approval(self):
        p=self.m.plan({'action':'container.start','id':CID},'admin')
        data={'planId':p['id'],'confirmation':'wrong','idempotencyKey':'bound-test-key-12'}
        with self.assertRaisesRegex(ValueError,'confirmation'):self.m.submit(data,'admin')
        data['confirmation']=p['target']
        with self.assertRaisesRegex(ValueError,'expired'):self.m.submit(data,'viewer')
        self.engine.running=True
        with self.assertRaisesRegex(ValueError,'changed'):self.m.submit(data,'admin')
        self.assertFalse(self.engine.writes)
    def test_idempotent_delete_preserves_volumes_and_never_forces(self):
        job,data=self.submit({'action':'container.delete','id':CID})
        self.assertEqual(self.finish(job)['status'],'SUCCESS')
        self.assertEqual(self.m.submit(data,'admin')['id'],job['id'])
        self.assertEqual(len(self.engine.writes),1)
        self.assertIn('force=false&v=false',self.engine.writes[0][1])
        with self.assertRaisesRegex(ValueError,'conflict'):self.m.submit({**data,'confirmation':'other'},'admin')
    def test_running_delete_and_image_references_are_protected(self):
        self.engine.running=True
        with self.assertRaisesRegex(ValueError,'Stop'):self.m.plan({'action':'container.delete','id':CID},'admin')
        self.engine.refs=[{'Id':CID,'ImageID':IMAGE,'Names':['/stopped-container']}]
        with self.assertRaisesRegex(ValueError,'referenced'):self.m.plan({'action':'image.delete','id':IMAGE},'admin')
        self.assertFalse(self.engine.writes)
    def test_network_namespace_owner_cannot_stop_with_dependents(self):
        self.engine.children=[{'Id':'c'*64,'HostConfig':{'NetworkMode':'container:'+CID}}]
        with self.assertRaisesRegex(ValueError,'Dependent'):self.m.plan({'action':'container.restart','id':CID},'admin')
    def test_control_plane_and_signed_installation_resources_are_protected(self):
        original=self.m.docker
        for labels in [{'deployment-ops.control-plane':'true'},{'deployment-ops.installation':'true'}]:
            def managed(method,path,body=None):
                item=original(method,path,body)
                if method=='GET' and path=='/containers/'+CID+'/json':item['Config']['Labels']=labels
                return item
            self.m.docker=managed
            with self.assertRaises(ValueError):self.m.plan({'action':'container.stop','id':CID},'admin')
        self.assertFalse(self.engine.writes)
    def test_creation_is_local_restricted_and_does_not_start_implicitly(self):
        job,_=self.submit({'action':'container.create','name':'ops-test','image':IMAGE,'command':['python','app.py'],'environment':{'PASSWORD':'private'}})
        self.assertEqual(self.finish(job)['status'],'SUCCESS')
        config=json.loads(self.engine.writes[0][2]);self.assertEqual(config['Image'],IMAGE)
        self.assertEqual(config['HostConfig']['CapDrop'],['ALL'])
        self.assertNotIn('private',json.dumps(self.m.jobs()))
        self.assertEqual(len(self.engine.writes),1)
        with self.assertRaisesRegex(ValueError,'Unsupported'):self.m.plan({'action':'container.create','name':'ops-bad','image':IMAGE,'privileged':True},'admin')
        with self.assertRaisesRegex(ValueError,'host paths'):self.m.plan({'action':'container.create','name':'ops-bad','image':IMAGE,'volumes':[{'source':'/','target':'/host'}]},'admin')
    def test_pull_is_allowlisted_and_existing_tags_cannot_be_replaced(self):
        with self.assertRaisesRegex(ValueError,'allowlisted'):self.m.plan({'action':'image.pull','reference':'untrusted.invalid/image:tag'},'admin')
        with self.assertRaisesRegex(ValueError,'already exists'):self.m.plan({'action':'image.tag','id':IMAGE,'repository':'fixture','tag':'v1'},'admin')
    def test_restart_marks_unknown_without_replaying_writes(self):
        with self.m.db() as db:db.execute("INSERT INTO jobs VALUES('job','admin','key','hash','RUNNING','container.start','test',1,1,NULL,NULL)")
        m=Manager(self.temp.name,self.engine,read_only=False)
        self.assertEqual(m.job('job')['status'],'UNKNOWN');self.assertFalse(self.engine.writes)
    def test_lost_docker_response_is_unknown_not_success(self):
        original=self.m.docker
        def uncertain(method,path,body=None):
            if method!='GET':raise OSError('socket interrupted')
            return original(method,path,body)
        self.m.docker=uncertain
        job,_=self.submit({'action':'container.start','id':CID})
        self.assertEqual(self.finish(job)['status'],'UNKNOWN')
    def test_start_acknowledged_then_entrypoint_failure_is_not_success(self):
        original=self.m.docker
        def exited(method,path,body=None):
            item=original(method,path,body)
            if method=='GET' and path=='/containers/'+CID+'/json':item['State']['ExitCode']=127
            return item
        self.m.docker=exited
        job,_=self.submit({'action':'container.start','id':CID})
        self.assertEqual(self.finish(job)['status'],'FAILED')
    def test_history_retention_and_metric_scope(self):
        with self.m.db() as db:db.execute('INSERT INTO samples(at,resource,payload) VALUES(1,\'host\',\'{}\')')
        self.m.sample('host',{'available':False,'cpuPercent':None})
        self.assertEqual(len(self.m.history()),1)
        self.assertIsNone(container_metrics({})['cpuPercent'])
        self.assertEqual(container_metrics({})['scope'],'container')
    def test_cancel_pending_never_cancels_running_docker_writes(self):
        with self.m.db() as db:db.execute("INSERT INTO jobs VALUES('pending','admin','key','hash','PENDING','container.start','test',1,1,NULL,NULL)")
        self.assertEqual(self.m.cancel('pending')['status'],'CANCELLED')
        self.m.run('pending',{'action':'container.start','id':CID},'unused')
        self.assertFalse(self.engine.writes)


if __name__=='__main__':unittest.main()
