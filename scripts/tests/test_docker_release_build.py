import importlib.util
import io
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

REPO = Path(__file__).resolve().parents[2]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result)
    return result


build = module('docker_release_build', REPO / 'scripts/build-docker-release.py')
deploy = module('docker_release_deploy', REPO / 'deploy/docker/release.py')


class DockerReleaseBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def jar(self, files):
        path = self.root / 'app.jar'
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('BOOT-INF/lib/third-party.jar', b'library')
            for name, value in files.items(): archive.writestr(name, value)
        return path

    def test_both_frontend_entrypoints_require_real_nonempty_assets(self):
        for prefix in ('BOOT-INF/classes/static/', 'BOOT-INF/classes/static/admin/'):
            files = {prefix + 'index.html': '<script src="./assets/main.js"></script>'}
            with self.assertRaisesRegex(ValueError, 'Missing frontend'):
                build.verify_jar(self.jar(files), prefix)
            files[prefix + 'assets/main.js'] = b''
            with self.assertRaisesRegex(ValueError, 'Missing frontend'):
                build.verify_jar(self.jar(files), prefix)
            files[prefix + 'assets/main.js'] = b'console.log("ready")'
            build.verify_jar(self.jar(files), prefix)

    def test_nested_first_party_secret_files_block_packaging(self):
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, 'w') as archive:
            archive.writestr('config/internal-credential.key', 'private')
        with self.assertRaisesRegex(ValueError, 'Deployment secret'):
            build.verify_jar(self.jar({'BOOT-INF/lib/chatchat-common.jar': nested.getvalue()}))

    def test_cli_must_be_linux_amd64_not_a_windows_or_arm_binary(self):
        path = self.root / 'docker'
        for data in (b'MZ' + bytes(100), b'\x7fELF\x02\x01' + bytes(58)):
            path.write_bytes(data)
            with self.assertRaisesRegex(ValueError, 'ELF'): build.validate_linux_cli(path)
        header = bytearray(64); header[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<H', header, 18, 62); path.write_bytes(header)
        build.validate_linux_cli(path)

    def test_failed_command_records_log_and_returns_failure(self):
        runner = build.Runner(self.root)
        with self.assertRaisesRegex(RuntimeError, 'exit 7'):
            runner.run([sys.executable, '-c', 'print("original failure");raise SystemExit(7)'], cwd=self.root)
        self.assertIn('original failure', runner.log.read_text(encoding='utf-8'))

    def test_captured_json_is_not_corrupted_by_stderr_warnings(self):
        value = build.Runner(self.root).run([sys.executable, '-c',
            'import sys,json;print("warning",file=sys.stderr);print(json.dumps({"value":1}))'], cwd=self.root, capture=True)
        self.assertEqual(json.loads(value), {'value': 1})

    def test_plan_does_not_create_output_or_call_docker(self):
        output = self.root / 'planned-release'
        result = subprocess.run([sys.executable, REPO / 'scripts/build-docker-release.py',
                                 '--tag', 'test-r1', '--output-dir', output, '--plan'], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        plan = json.loads(result.stdout)
        self.assertEqual(len(plan['images']), 11)
        self.assertFalse(plan['deploysRunningServices'])
        self.assertFalse(output.exists())

    def test_output_inside_maven_clean_target_is_rejected(self):
        result = subprocess.run([sys.executable, REPO / 'scripts/build-docker-release.py',
            '--output-dir', REPO / 'target/unsafe-release', '--plan'], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Maven target', result.stderr)

    def test_source_snapshot_preserves_original_target_and_uncommitted_source(self):
        original = self.root / 'repository'; original.mkdir()
        (original / 'target').mkdir(); (original / 'target/keep.txt').write_text('release evidence')
        (original / 'pom.xml').write_text('current uncommitted build configuration')
        output = self.root / 'release'; output.mkdir()
        runner = build.Runner(output)
        with patch.object(build, 'ROOT', original), patch.object(runner, 'run', return_value='pom.xml\0'):
            snapshot, fingerprint = build.snapshot_source(runner, output)
        self.assertEqual((snapshot / 'pom.xml').read_text(), (original / 'pom.xml').read_text())
        self.assertTrue((original / 'target/keep.txt').is_file())
        self.assertNotEqual(snapshot.resolve(), original.resolve())
        self.assertEqual(len(fingerprint), 64)
        with self.assertRaisesRegex(ValueError, 'isolated'):
            build.prepare_sources(runner, output, build.parser().parse_args([]), original)

    @unittest.skipIf(os.name == 'nt', 'Deployment initialization runs on the Linux target')
    def test_init_pins_images_and_refuses_overwriting_existing_site(self):
        release = self.root / 'release'; source = release / 'deploy/docker'; source.mkdir(parents=True)
        (source / 'application').mkdir()
        for name in ['runtime-deployment.full.example.json', 'runtime-deployment.example.json', 'application/deployment.env.example']:
            (source / name).write_bytes((REPO / 'deploy/docker' / name).read_bytes())
        args = build.parser().parse_args([])
        images = build.image_names('test', args)
        records = {name: {'tag': tag, 'imageId': 'sha256:' + name} for name, tag in images.items()}
        (release / 'release-manifest.json').write_text(json.dumps({'images': records}))
        install = self.root / 'installation'
        deploy.initialize(release, install, 'full')
        spec = json.loads((install / 'config/site.json').read_text())
        self.assertEqual(spec['image_ids']['mcp'], 'sha256:mcp')
        self.assertEqual(spec['application_compose'], str(source / 'application/compose.yaml'))
        env = (install / 'config/deployment.env').read_text()
        self.assertIn('JDBC_DRIVERS_DIR=' + str(install) + '/lib/drivers', env)
        self.assertIn('TEMPORAL_IMAGE=' + records['temporal']['tag'], env)
        with self.assertRaisesRegex(ValueError, 'already exist'):
            deploy.initialize(release, install, 'full')

    def checksums(self):
        (self.root / 'images.tar').write_bytes(b'archive fixture')
        (self.root / 'release-manifest.json').write_text('{}')
        (self.root / 'deploy.py').write_text('entrypoint')
        files = ['images.tar', 'release-manifest.json', 'deploy.py']
        (self.root / 'SHA256SUMS').write_text(''.join(deploy.digest(self.root / name) + '  ' + name + '\n' for name in files))

    def test_packaging_only_copies_approved_assets_and_never_local_env_files(self):
        output = self.root / 'output'; (output / 'work').mkdir(parents=True)
        source = self.root / 'source'; directory = source / 'deploy/docker'; directory.mkdir(parents=True)
        for name, value in {'compose.yaml': 'services: {}', 'credentials.env': 'PRIVATE=value',
                            'untracked-site.json': '{"private":"value"}', 'release.py': '# entrypoint',
                            'LOCAL-BUILD.zh-CN.md': 'Build guide'}.items():
            (directory / name).write_text(value)
        build.write_json(output / 'work/deployment-assets.json', ['deploy/docker/compose.yaml', 'deploy/docker/credentials.env'])
        runner = build.Runner(output)
        def docker_save(argv, **kwargs):
            self.assertEqual(argv[:2], ['docker', 'save'])
            Path(argv[argv.index('-o') + 1]).write_bytes(b'exported image')
        with patch.object(runner, 'run', side_effect=docker_save):
            package = build.package_assets(runner, output, {'gateway': 'nginx:test'}, source)
        self.assertTrue((package / 'deploy/docker/compose.yaml').is_file())
        self.assertFalse((package / 'deploy/docker/credentials.env').exists())
        self.assertFalse((package / 'deploy/docker/untracked-site.json').exists())
        self.assertEqual((package / 'images.tar').read_bytes(), b'exported image')

    def test_tampered_bundle_is_rejected_before_docker_load(self):
        self.checksums(); deploy.verify(self.root)
        (self.root / 'images.tar').write_bytes(b'tampered')
        with patch.object(deploy.subprocess, 'run') as execute:
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'): deploy.load(self.root)
            execute.assert_not_called()

    def test_checksum_paths_cannot_escape_the_release(self):
        (self.root / 'SHA256SUMS').write_text('0' * 64 + '  ../outside\n')
        with self.assertRaisesRegex(ValueError, 'Invalid or missing'): deploy.verify(self.root)

    def test_loaded_image_id_must_match_the_release(self):
        self.checksums()
        data = {'images': {'api': {'tag': 'api:test', 'imageId': 'sha256:approved'}}}
        (self.root / 'release-manifest.json').write_text(json.dumps(data))
        (self.root / 'SHA256SUMS').write_text(''.join(deploy.digest(self.root / name) + '  ' + name + '\n'
            for name in ['images.tar', 'release-manifest.json', 'deploy.py']))
        actual = [{'Id': 'sha256:changed', 'Os': 'linux', 'Architecture': 'amd64'}]
        with patch.object(deploy.subprocess, 'run'), patch.object(deploy.subprocess, 'check_output', return_value=json.dumps(actual)):
            with self.assertRaisesRegex(ValueError, 'identity/platform'): deploy.load(self.root)


if __name__ == '__main__': unittest.main()
