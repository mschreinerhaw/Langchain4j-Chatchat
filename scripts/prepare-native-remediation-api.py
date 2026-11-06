"""Prepare a minimal API overlay from compiled classes and a complete built console.

Runs on the deployment host. Does not replace containers. Existing dependencies,
runtime configuration and non-target application classes remain byte identical.
"""
import argparse
import copy
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import zipfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def members(archive):
    with tarfile.open(archive) as bundle:
        result = {}
        for entry in bundle.getmembers():
            if not entry.isfile():
                if not entry.isdir():
                    raise ValueError('Links and special files are forbidden')
                continue
            path = Path(entry.name)
            if path.is_absolute() or '..' in path.parts or entry.name in result:
                raise ValueError('Unsafe or repeated source entry')
            result[entry.name] = bundle.extractfile(entry).read()
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True, type=Path)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--classes-sha256', required=True)
    parser.add_argument('--web-sha256', required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    for name, expected in [('classes.tar.gz', args.classes_sha256), ('web.tar.gz', args.web_sha256)]:
        if sha((root / name).read_bytes()) != expected:
            raise ValueError('Upload digest mismatch: ' + name)
    baseline = root / 'api-baseline.jar'
    subprocess.run(['docker', 'cp', 'runtime-os-app-stage-api-1:/srv/app/app.jar', str(baseline)], check=True)
    classes, web = members(root / 'classes.tar.gz'), members(root / 'web.tar.gz')
    families = ('com/chatchat/api/runtime/coding/analysis/CodingAnalysisService',
                'com/chatchat/api/runtime/coding/cline/ClineModelCallbackController',
                'com/chatchat/api/runtime/coding/cline/ClineCodingAdapter',
                'com/chatchat/api/runtime/coding/cline/ClinePythonCallbackController',
                'com/chatchat/api/runtime/coding/evidence/VisualReportArtifacts',
                'com/chatchat/api/assets/AssetAnalysisController',
                'com/chatchat/api/exploration/ExplorationWorkspaceController')
    common_name = 'com/chatchat/common/runtime/coding/VisualReportMode.class'
    replacements = {}
    for name, data in classes.items():
        if name.startswith('api/'):
            path = name.removeprefix('api/')
            if not any(path == f + '.class' or path.startswith(f + '$') and path.endswith('.class') for f in families):
                raise ValueError('Unreviewed application class: ' + path)
            replacements['BOOT-INF/classes/' + path] = data
        elif name.startswith('agents/'):
            path=name.removeprefix('agents/')
            family='com/chatchat/agents/runtime/coding/DurableCodingTaskService'
            if not (path==family+'.class' or path.startswith(family+'$') and path.endswith('.class')):
                raise ValueError('Unreviewed agents class: '+path)
        elif name != 'common/' + common_name:
            raise ValueError('Unreviewed common class')
    for family in families:
        if 'BOOT-INF/classes/' + family + '.class' not in replacements:
            raise ValueError('Missing compiled application family')
    if 'common/' + common_name not in classes:
        raise ValueError('Missing compiled report contract')
    static = 'BOOT-INF/classes/static/'
    static_files = {static + name.removeprefix('static/'): data for name, data in web.items() if name.startswith('static/')}
    if not static_files or static + 'index.html' not in static_files:
        raise ValueError('Complete built console required')
    with zipfile.ZipFile(baseline) as old:
        common = next(n for n in old.namelist() if n.startswith('BOOT-INF/lib/chatchat-common-') and n.endswith('.jar'))
        buffer = io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(old.read(common))) as lib, zipfile.ZipFile(buffer, 'w') as output:
            for entry in lib.infolist():
                output.writestr(copy.copy(entry), classes['common/' + common_name] if entry.filename == common_name else lib.read(entry.filename))
        replacements[common] = buffer.getvalue()
        agent_classes={n.removeprefix('agents/'):data for n,data in classes.items() if n.startswith('agents/')}
        agents=None
        if agent_classes:
            agents=next(n for n in old.namelist() if n.startswith('BOOT-INF/lib/chatchat-agents-') and n.endswith('.jar'))
            nested=io.BytesIO()
            with zipfile.ZipFile(io.BytesIO(old.read(agents))) as lib,zipfile.ZipFile(nested,'w') as output:
                if not set(agent_classes).issubset(lib.namelist()):
                    raise ValueError('Agents overlay cannot add unrelated entries')
                for entry in lib.infolist():output.writestr(copy.copy(entry),agent_classes.get(entry.filename,lib.read(entry.filename)))
            replacements[agents]=nested.getvalue()
        with zipfile.ZipFile(root / 'app.jar', 'w', allowZip64=True) as output:
            for entry in old.infolist():
                if entry.filename.startswith(static):
                    continue
                if entry.filename in [common,agents] and entry.compress_type != zipfile.ZIP_STORED:
                    raise ValueError('Nested Spring Boot jar must remain STORED')
                output.writestr(copy.copy(entry), replacements.get(entry.filename, old.read(entry.filename)))
            for name, data in static_files.items():
                output.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
        with zipfile.ZipFile(root / 'app.jar') as new:
            if new.testzip() is not None:
                raise ValueError('Invalid candidate archive')
            for entry in old.infolist():
                if entry.filename not in replacements and not entry.filename.startswith(static):
                    if old.read(entry.filename) != new.read(entry.filename):
                        raise ValueError('Unrelated archive entry changed')
            with zipfile.ZipFile(io.BytesIO(old.read(common))) as before, zipfile.ZipFile(io.BytesIO(new.read(common))) as after:
                for name in before.namelist():
                    if name != common_name and before.read(name) != after.read(name):
                        raise ValueError('Unrelated common entry changed')
            if agents:
                with zipfile.ZipFile(io.BytesIO(old.read(agents))) as before,zipfile.ZipFile(io.BytesIO(new.read(agents))) as after:
                    for name in before.namelist():
                        if name not in agent_classes and before.read(name)!=after.read(name):
                            raise ValueError('Unrelated agents entry changed')
    proof = {'sourceCommit': args.commit, 'baselineSha256': sha(baseline.read_bytes()),
             'sha256': sha((root / 'app.jar').read_bytes()), 'classesArchiveSha256': args.classes_sha256,
             'webArchiveSha256': args.web_sha256, 'applicationClasses': sorted(n for n in replacements if n.endswith('.class')),
             'commonClass': common_name, 'agentsClasses':sorted(agent_classes),
             'staticFiles': len(static_files), 'unrelatedEntriesByteIdentical': True,
             'nestedJarStored': True}
    (root / 'api-artifact-proof.json').write_text(json.dumps(proof, indent=2))
    manifest = {**proof, 'image': 'runtime-os-api:native-remediation-' + proof['sha256'][:12], 'preserveRuntimeConfiguration': True}
    (root / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    print(json.dumps(proof), flush=True)


if __name__ == '__main__':
    main()
