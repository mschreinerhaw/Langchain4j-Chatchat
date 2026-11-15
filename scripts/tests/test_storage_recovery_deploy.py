import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('storage_deploy', ROOT/'scripts/deploy-artifact-storage-recovery.py')
deployment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deployment)


class SharedNamespaceRecoveryTest(unittest.TestCase):
    def check(self, stale=False):
        with tempfile.TemporaryDirectory() as directory:
            compose=Path(directory)/'compose.json'
            roles=['temporal','worker1','node1']
            compose.write_text(json.dumps({'services':{
                **{r:dict(container_name=r,network_mode='service:temporal-control-api') for r in roles},
                'executor1':dict(container_name='executor1',network_mode='host')}}))
            calls=[]
            def run(*args):
                calls.append(args)
                if args[:2]==('docker','inspect'):
                    owner=args[2]=='runtime-native-temporal-api'
                    value=dict(Id='new-api',State=dict(Pid=42,Running=True),HostConfig=dict(NetworkMode='container:'+('old-api' if stale and args[2]=='node1' else 'new-api')))
                    return json.dumps([value])
                return ''
            with patch.object(deployment,'run',side_effect=run):
                if stale:
                    with self.assertRaisesRegex(RuntimeError,'follower is stale: node1'):
                        deployment.refresh_shared_namespace(compose,['docker','compose'])
                else:
                    result=deployment.refresh_shared_namespace(compose,['docker','compose'])
                    self.assertEqual(result['refreshedServices'],roles)
                    updates=[c for c in calls if c[:3]==('docker','compose','up')]
                    self.assertEqual(updates[0][-1],'temporal')
                    self.assertEqual(updates[1][-2:],('worker1','node1'))
                    self.assertNotIn('executor1',updates[1])

    def test_temporal_and_every_shared_follower_rebind_after_api_replacement(self):
        self.check()

    def test_a_stale_namespace_fails_release_validation(self):
        self.check(stale=True)


if __name__=='__main__':
    unittest.main()
