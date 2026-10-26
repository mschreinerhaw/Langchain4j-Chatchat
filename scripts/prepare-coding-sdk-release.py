"""Assemble candidate images from running services, preserving unrelated archive bytes.

Preparation only: does not start containers, edit live configuration or switch traffic.
The input bundle contains checksummed class overlays and the existing Runtime modules.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile


def sha(body):
    return hashlib.sha256(body).hexdigest()


MODULE_FAMILIES = {
    'common': ['com/chatchat/common/runtime/coding/CodingRuntimeContext',
               'com/chatchat/common/runtime/coding/ExecutionFeedback',
               'com/chatchat/common/runtime/event/RuntimeEventLog'],
    'agents': ['com/chatchat/agents/model/ConfigurableChatModelFactory',
               'com/chatchat/agents/model/RuntimeStreamingChatModel',
               *['com/chatchat/agents/orchestration/model/' + name for name in
                 ['AgentChatModelResolver', 'CapacityGovernedChatModel', 'DeadlineAwareChatModel',
                  'MeteredChatModel', 'ToolExecutionModels']],
               'com/chatchat/agents/runtime/coding/DurableCodingTaskService'],
}


def overlay_module(original, compiled, module):
    """Preserve deployed extensions absent from this checkout, including their resources."""
    with zipfile.ZipFile(io.BytesIO(compiled)) as source:
        selected = {name: source.read(name) for name in source.namelist()
                    if any(name == family + '.class' or name.startswith(family + '$') and name.endswith('.class')
                           for family in MODULE_FAMILIES[module])}
        if module == 'common':
            key = 'runtime/coding/execution-feedback.schema.json'
            selected[key] = source.read(key)
    result = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(original)) as before, zipfile.ZipFile(result, 'w') as after:
        for info in before.infolist():
            after.writestr(info, selected.pop(info.filename) if info.filename in selected else before.read(info.filename))
        for name, body in selected.items():
            after.writestr(name, body)
    return result.getvalue()


def assert_public_abi(original, candidate, module, javap):
    def members(path, name):
        output = subprocess.check_output([javap, '-public', '-s', '-classpath', str(path), name], text=True)
        result, previous = set(), ''
        for line in output.splitlines():
            line = line.strip()
            if line.startswith('descriptor:'):
                identifier = previous.split('(', 1)[0].split()[-1].rstrip(';')
                result.add((identifier, line))
            elif line:
                previous = line
        return result
    with tempfile.TemporaryDirectory(prefix='coding-abi-') as directory:
        old, new = Path(directory)/'old.jar', Path(directory)/'new.jar'
        old.write_bytes(original)
        new.write_bytes(candidate)
        with zipfile.ZipFile(old) as archive:
            for family in MODULE_FAMILIES[module]:
                if family + '.class' not in archive.namelist():
                    continue
                name = family.replace('/', '.')
                missing = members(old, name) - members(new, name)
                if missing:
                    raise ValueError('Deployed public ABI would be removed: ' + name + ' ' + str(sorted(missing)))


def assemble(source, target, replacements):
    retained = {}
    remaining = dict(replacements)
    with zipfile.ZipFile(source) as before, zipfile.ZipFile(target, 'w', allowZip64=True) as after:
        for info in before.infolist():
            body = before.read(info.filename)
            if info.filename in remaining:
                body = remaining.pop(info.filename)
            else:
                retained[info.filename] = sha(body)
            after.writestr(info, body)
        for name, body in remaining.items():
            if not name.startswith('BOOT-INF/classes/') or '..' in name.split('/'):
                raise ValueError('Only class/resource additions are allowed')
            after.writestr(name, body)
    with zipfile.ZipFile(target) as result:
        for name, digest in retained.items():
            if sha(result.read(name)) != digest:
                raise ValueError('Unrelated deployed bytes changed: ' + name)
    return {'sha256': sha(target.read_bytes()), 'unchangedEntries': len(retained),
            'replacedEntries': sorted(replacements)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-dir', required=True, type=Path)
    parser.add_argument('--reuse-frozen-bases', action='store_true')
    parser.add_argument('--javap', help='JDK javap executable for deployed public ABI checks')
    args = parser.parse_args()
    root = args.release_dir.resolve()
    bundle = root / 'bundle'
    manifest = json.loads((bundle / 'manifest.json').read_text())
    for name, digest in manifest['files'].items():
        path = (bundle / name).resolve()
        if not path.is_relative_to(bundle.resolve()) or sha(path.read_bytes()) != digest:
            raise ValueError('Bundle checksum/path mismatch')
    report = {'source': manifest['source'], 'trafficSwitched': False, 'services': {}}
    frozen = json.loads((root / 'candidate-images.json').read_text()) if args.reuse_frozen_bases else None
    for service in ('api', 'mcp'):
        container = 'runtime-os-app-stage-' + service + '-1'
        image = subprocess.check_output(['docker', 'inspect', '-f', '{{.Image}}', container], text=True).strip()
        if frozen:
            image = frozen['services'][service]['baseImage']
        directory = root / 'images' / service
        directory.mkdir(parents=True, exist_ok=True)
        baseline = root / (service + '-base.jar')
        # A live bind-mounted inode can remain readable after its host pathname
        # was removed. Read through the container mount, never recover another jar.
        if not frozen:
            with baseline.open('wb') as output:
                subprocess.run(['docker', 'exec', container, 'cat', '/srv/app/app.jar'], stdout=output, check=True)
        replacements = {}
        with zipfile.ZipFile(baseline) as archive:
            for module in (('common', 'agents') if service == 'api' else ('common',)):
                names = [n for n in archive.namelist() if n.startswith('BOOT-INF/lib/chatchat-' + module + '-') and n.endswith('.jar')]
                if len(names) != 1:
                    raise ValueError('Unexpected deployed module layout')
                replacements[names[0]] = overlay_module(archive.read(names[0]),
                    (bundle / 'modules' / (module + '.jar')).read_bytes(), module)
                if args.javap:
                    assert_public_abi(archive.read(names[0]), replacements[names[0]], module, args.javap)
        overlay = bundle / service
        for path in overlay.rglob('*'):
            if path.is_file():
                replacements['BOOT-INF/classes/' + path.relative_to(overlay).as_posix()] = path.read_bytes()
        result = assemble(baseline, directory / 'app.jar', replacements)
        base_tag = 'runtime-os-' + service + ':sdk-native-base-' + image.split(':')[-1][:12]
        subprocess.run(['docker', 'tag', image, base_tag], check=True)
        (directory / 'Dockerfile').write_text('FROM ' + base_tag + '\nCOPY app.jar /srv/app/app.jar\n')
        tag = 'runtime-os-' + service + ':cline-sdk-native-20261026'
        subprocess.run(['docker', 'build', '-t', tag, str(directory)], check=True)
        report['services'][service] = dict(result, image=tag, baseImage=image,
            baseSha256=sha(baseline.read_bytes()), publicAbiChecked=bool(args.javap))
    node = root / 'images' / 'node'
    node.mkdir(parents=True, exist_ok=True)
    import shutil
    shutil.copytree(bundle / 'node', node / 'dist', dirs_exist_ok=True)
    image = subprocess.check_output(['docker', 'inspect', '-f', '{{.Image}}', 'runtime-os-app-stage-coding-agent-1'], text=True).strip()
    if frozen:
        image = frozen['services']['node']['baseImage']
    base_tag = 'runtime-os-coding-agent:sdk-native-base-' + image.split(':')[-1][:12]
    subprocess.run(['docker', 'tag', image, base_tag], check=True)
    (node / 'Dockerfile').write_text('FROM ' + base_tag + '\nCOPY --chown=node:node dist/ /srv/coding-agent/dist/\n')
    tag = 'runtime-os-coding-agent:cline-sdk-native-20261026'
    subprocess.run(['docker', 'build', '-t', tag, str(node)], check=True)
    report['services']['node'] = {'image': tag, 'baseImage': image}
    (root / 'candidate-images.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report))


if __name__ == '__main__':
    main()
