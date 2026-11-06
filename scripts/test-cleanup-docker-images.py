"""Exercise the real shell entry against a fake Docker CLI; never use a daemon."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('cleanup_docker_images.sh')
FAKE = r'''import json,os,pathlib,sys
p=pathlib.Path(os.environ['DOCKER_FIXTURE']);v=json.loads(p.read_text());a=sys.argv[1:]
v['calls'].append(a)
def save():p.write_text(json.dumps(v))
def done(value=''):save();print(value);sys.exit(0)
if a==['info']:done('{}')
if a[:2]==['ps','-aq']:
 v['psReads']=v.get('psReads',0)+1
 if v['psReads']>1:
  if v.get('failRefresh'):save();sys.exit(1)
  if v.get('lateReference'):v['containers']=[{'Id':'late','Image':v['lateReference']}]
  if v.get('lateConfig'):pathlib.Path(os.environ['CONFIG_ENV']).write_text('APP_IMAGE='+v['lateConfig'])
 done('\n'.join(c['Id'] for c in v['containers']))
if a[:3]==['image','ls','-aq']:done('\n'.join(i['Id'] for i in v['images']))
if a[:2]==['container','inspect']:done(json.dumps([c for c in v['containers'] if c['Id'] in a[2:]]))
if a[:2]==['image','inspect']:done(json.dumps([i for i in v['images'] if i['Id'] in a[2:]]))
if a[:2]==['image','rm'] and len(a)==3:
 v['removed'].append(a[2]);done()
save();print('Unexpected Docker mutation/command',file=sys.stderr);sys.exit(90)
'''


def image(identity, tags, date='2000-01-01T00:00:00Z', labels=None):
    return {'Id': identity, 'RepoTags': tags, 'RepoDigests': [], 'Created': date,
            'Config': {'Labels': labels or {}}}


class CleanupTest(unittest.TestCase):
    def run_script(self, images, containers=None, execute=False, config='', extra=None, missing=False, **options):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'config').mkdir()
            (root / 'compose.yaml').write_text('{"services":{}}')
            (root / 'config/deployment.env').write_text(config)
            if missing:
                (root / 'compose.yaml').unlink()
            fixture = root / 'fixture.json'
            fixture.write_text(json.dumps(dict(images=images, containers=containers or [], calls=[], removed=[], **options)))
            fake = root / 'docker'
            fake.write_text('#!' + sys.executable + '\n' + FAKE)
            fake.chmod(0o755)
            env = dict(os.environ, PATH=str(root)+os.pathsep+os.environ['PATH'], DOCKER_FIXTURE=str(fixture),
                       CONFIG_ENV=str(root / 'config/deployment.env'))
            result = subprocess.run(['bash', str(SCRIPT), '--execute' if execute else '--dry-run',
                                     '--config-dir', str(root), *(extra or [])], env=env, text=True, capture_output=True)
            return result, json.loads(fixture.read_text())

    def apps(self):
        return [image('old', ['runtime-os-api:old']),
                image('new1', ['runtime-os-api:new1'], '2098-01-01T00:00:00Z'),
                image('new2', ['runtime-os-api:new2'], '2099-01-01T00:00:00Z')]

    def test_unused_runtimes_bases_infrastructure_and_unknown_aliases_survive(self):
        tags = ['chatchat-python-runtime:3.11-v1.2-fonts-r2-20261022', 'python:3.11-alpine',
                'runtime-os-api-runtime:17-sqlglot-20261024', 'runtime-os-node-base:22',
                'postgres:18', 'registry:2', 'runtime-os-api:sdk-reuse-baseline-old',
                'runtime-os-api:offline-parent-deadbeef', 'runtime-os-api:rollback-old']
        values = self.apps() + [image('critical'+str(i), [t]) for i,t in enumerate(tags)]
        values.append(image('aliased', ['runtime-os-api:old-alias', 'python:critical-alias']))
        result, state = self.run_script(values, execute=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(state['removed'], ['old'])

    def test_stopped_container_and_configuration_references_survive(self):
        values = self.apps() + [image('configured', ['runtime-os-mcp:configured'])]
        result, state = self.run_script(values, containers=[{'Id':'rollback','Image':'old'}],
                                        execute=True, config='MCP_IMAGE=runtime-os-mcp:configured')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(state['removed'], [])
        self.assertFalse(any(call[:2] == ['container','rm'] for call in state['calls']))

    def test_default_preview_performs_no_mutations(self):
        result, state = self.run_script(self.apps())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('[候选]', result.stdout)
        self.assertEqual(state['removed'], [])
        self.assertFalse(any('rm' in call or 'prune' in call for call in state['calls']))

    def test_new_container_reference_and_new_config_are_rechecked(self):
        for options in [{'lateReference':'old'}, {'lateConfig':'runtime-os-api:old'}]:
            with self.subTest(options=options):
                result, state = self.run_script(self.apps(), execute=True, **options)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(state['removed'], [])

    def test_missing_configuration_or_failed_reference_refresh_aborts(self):
        for options in [{'missing':True}, {'failRefresh':True}]:
            with self.subTest(options=options):
                result, state = self.run_script(self.apps(), execute=True, **options)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(state['removed'], [])

    def test_untagged_unknown_protected_label_and_recent_images_survive(self):
        values = self.apps() + [image('unknown', []), image('label', ['runtime-os-news:old'], labels={'com.chatchat.cleanup.protect':'true'}),
                               image('recent', ['runtime-os-api:recent'], '2097-01-01T00:00:00Z')]
        result, state = self.run_script(values, execute=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(state['removed'], ['old'])

    def test_explicit_missing_keep_file_aborts(self):
        result, state = self.run_script(self.apps(), execute=True, extra=['--keep-file','/nonexistent/cleanup-keep-file'])
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(state['removed'], [])


if __name__ == '__main__':
    unittest.main()
