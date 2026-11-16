import contextlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from types import SimpleNamespace

spec=importlib.util.spec_from_file_location('manage',Path(__file__).resolve().parents[1]/'manage.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class OperationsTest(unittest.TestCase):
    def test_same_owner_id_can_still_have_an_obsolete_network_namespace(self):
        child={'HostConfig':{'NetworkMode':'container:owner'},'State':{'Running':True,'Pid':20}}
        owner={'State':{'Running':True,'Pid':10}}
        inspected=SimpleNamespace(returncode=0,stdout=json.dumps([owner]))
        with patch.object(m.subprocess,'run',return_value=inspected),patch.object(m.os,'stat',side_effect=[SimpleNamespace(st_ino=1),SimpleNamespace(st_ino=2)]):
            self.assertTrue(m.stale_network_namespace(child))
        with patch.object(m.subprocess,'run',return_value=inspected),patch.object(m.os,'stat',return_value=SimpleNamespace(st_ino=2)):
            self.assertFalse(m.stale_network_namespace(child))

    def test_start_recreates_running_child_with_stale_namespace(self):
        with patch.object(m,'order',return_value=['worker1']),patch.object(m,'container',return_value={'State':{'Running':True}}),patch.object(m,'stale_network_namespace',return_value=True),patch.object(m,'execute') as execute,patch.object(m,'ready') as ready,contextlib.redirect_stdout(io.StringIO()):
            m.start_roles(['worker1'])
        self.assertIn('--force-recreate',execute.call_args.args[0])
        self.assertEqual(execute.call_args.args[0][-1],'worker1')
        ready.assert_called_once_with('worker1')

    def test_all_roles_are_unique_and_dependencies_start_first(self):
        ordered=m.order(m.GROUPS['all'])
        self.assertEqual(len(ordered),20)
        self.assertEqual(len({m.ROLES[r][0] for r in ordered}),20)
        for r in ordered:
            for dep in m.ROLES[r][1]:self.assertLess(ordered.index(dep),ordered.index(r))
        self.assertLess(ordered.index('control-api'),ordered.index('temporal'))
        self.assertLess(ordered.index('temporal'),ordered.index('worker1'))

    def test_api_start_includes_both_workers_and_their_runtime(self):
        roles=m.order(m.GROUPS['api'])
        self.assertTrue({'postgresql','mcp','control-api','temporal','node1','node2','worker1','worker2'}<=set(roles))
        self.assertNotIn('nginx',roles)

    def test_parent_stop_includes_namespace_children(self):
        roles=m.dependents(['control-api'])
        self.assertTrue({'temporal','node1','node2','worker1','worker2','studio','nginx'}<=roles)
        self.assertNotIn('postgresql',roles)

    def test_start_all_dry_run_does_not_execute_any_commands(self):
        with patch.object(m,'container',return_value=None),patch.object(m,'execute') as execute,contextlib.redirect_stdout(io.StringIO()):
            m.start_roles(m.GROUPS['all'],dry=True)
        self.assertEqual(execute.call_count,20)
        self.assertTrue(all(c.args[1] is True for c in execute.call_args_list))

    def setup_release(self,directory):
        root=Path(directory);stage=root/'stage';stage.mkdir()
        original={'services':{s:{'image':'previous:image'} for s in ['temporal-control-api','worker1','worker2']}}
        config=root/'native.json';config.write_text(json.dumps(original))
        gateway={'services':{'gateway':{'volumes':[{'target':'/srv/task-lifecycle-ui','source':'/previous/ui'}]}}}
        (stage/'compose.gateway-ui.json').write_text(json.dumps(gateway))
        (stage/'config').mkdir()
        (stage/'config/public-gateway.conf').write_text('location = /version.json { return 200 \'{"release":"previous"}\'; }')
        roles=dict(m.ROLES)
        for r in m.GROUPS['api']:
            a=list(roles[r]);a[2]=config;roles[r]=tuple(a)
        return root,stage,config,roles

    def test_deploy_dry_run_preserves_configuration_and_creates_no_release(self):
        with tempfile.TemporaryDirectory() as tmp:
            root,stage,config,roles=self.setup_release(tmp);before=config.read_bytes()
            with patch.object(m,'ROOT',root),patch.object(m,'STAGE',stage),patch.object(m,'ROLES',roles),patch.object(m,'image_exists'),patch.object(m,'stop_roles') as stop,contextlib.redirect_stdout(io.StringIO()):
                m.deploy('api','new:image',dry=True)
            self.assertEqual(config.read_bytes(),before);stop.assert_not_called()
            self.assertFalse((root/'private/releases').exists())

    def test_failed_start_restores_backend_and_frontend_configuration(self):
        with tempfile.TemporaryDirectory() as tmp:
            root,stage,config,roles=self.setup_release(tmp);before=config.read_bytes()
            gateway=stage/'compose.gateway-ui.json';before_gateway=gateway.read_bytes()
            ui=root/'candidate-ui';ui.mkdir()
            with patch.object(m,'ROOT',root),patch.object(m,'STAGE',stage),patch.object(m,'ROLES',roles),patch.object(m,'image_exists'),patch.object(m,'require_idle'),patch.object(m,'prepare_frontend',return_value=ui),patch.object(m,'container',return_value={'State':{'Running':True}}),patch.object(m,'stop_roles'),patch.object(m,'execute') as execute,patch.object(m.subprocess,'check_output',return_value='[{"Id":"new-image-id"}]'),patch.object(m,'start_roles',side_effect=[RuntimeError('candidate failed'),None]),contextlib.redirect_stdout(io.StringIO()),contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(RuntimeError):m.deploy('api','new:image')
            self.assertEqual(config.read_bytes(),before)
            self.assertEqual(gateway.read_bytes(),before_gateway)
            removed=[c.args[0][-1] for c in execute.call_args_list if c.args[0][:2]==['docker','rm']]
            self.assertNotIn('postgresql18',removed)
            self.assertNotIn('runtime-os-app-stage-mcp-1',removed)
            record=next((root/'private/releases').glob('*/release.json'))
            self.assertEqual(json.loads(record.read_text())['status'],'FAILED_ROLLED_BACK')

    def test_incomplete_frontend_is_rejected_before_activation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);jar=root/'bad.jar'
            with zipfile.ZipFile(jar,'w') as z:z.writestr('BOOT-INF/classes/static/index.html','<script src="/assets/missing.js"></script>')
            release=root/'release';release.mkdir()
            def fake_run(cmd,**kwargs):
                if cmd[1]=='cp':shutil.copyfile(jar,cmd[-1])
            with patch.object(m.subprocess,'check_output',return_value='candidate\n'),patch.object(m.subprocess,'run',side_effect=fake_run) as run:
                with self.assertRaisesRegex(RuntimeError,'incomplete'):m.prepare_frontend('new:image',release)
            self.assertTrue(any(c.args[0][:2]==['docker','rm'] for c in run.call_args_list))

if __name__=='__main__':unittest.main()
