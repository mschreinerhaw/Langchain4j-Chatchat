"""Recover only original Temurin or Node runtime layers from a local Docker archive.

Offline fallback: source must be an existing trusted Temurin/Node image. This is
not upstream signature verification. Never import a container export or app layer.
Usage: python3 recover-java-base.py SOURCE_IMAGE OUTPUT_IMAGE WORK_DIRECTORY
"""
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile


def recover(source, destination, directory):
    node = destination.startswith('runtime-os-node-base:')
    if not node and not destination.startswith('runtime-os-java-base:'):
        raise ValueError('Destination must be a dedicated Java/Node base tag')
    root = Path(directory)
    root.mkdir(parents=True, exist_ok=True)
    source_tar = root / 'source-image.tar'
    subprocess.run(['docker', 'save', '-o', str(source_tar), source], check=True)
    with tarfile.open(source_tar) as archive:
        manifest = json.load(archive.extractfile('manifest.json'))[0]
        original = json.load(archive.extractfile(manifest['Config']))
        history = original['history']
        stops = [i for i, h in enumerate(history) if
                 (h.get('created_by', '').strip() == 'CMD ["node"]' if node else
                  'ENTRYPOINT' in h.get('created_by', '') and '/__cacert_entrypoint.sh' in h['created_by'])]
        if len(stops) != 1:
            raise ValueError('Cannot identify the exact upstream runtime boundary')
        retained = history[:stops[0] + 1]
        if any(any(marker in h.get('created_by', '') for marker in ('app.jar', '/srv/coding-agent')) for h in retained):
            raise ValueError('Business application detected in base layers')
        count = sum(not h.get('empty_layer', False) for h in retained)
        diff_ids = original['rootfs']['diff_ids'][:count]
        config = {
            'architecture': original['architecture'], 'os': original['os'],
            'created': retained[-1]['created'], 'history': retained,
            'rootfs': {'type': 'layers', 'diff_ids': diff_ids},
            'config': {'Env': [v for v in original['config']['Env']
                               if v.split('=', 1)[0] in ({'PATH', 'NODE_VERSION', 'YARN_VERSION'} if node else
                                   {'PATH', 'JAVA_HOME', 'JAVA_VERSION', 'LANG', 'LANGUAGE', 'LC_ALL'})],
                       'Entrypoint': ['docker-entrypoint.sh'] if node else ['/__cacert_entrypoint.sh'],
                       'Cmd': ['node'] if node else ['/bin/bash'],
                       'WorkingDir': '/', 'Labels': {'com.chatchat.base.source': source}}
        }
        content = json.dumps(config, separators=(',', ':')).encode()
        config_name = hashlib.sha256(content).hexdigest() + '.json'
        layers = manifest['Layers'][:count]
        output = root / ('node-base.tar' if node else 'java-base.tar')
        with tarfile.open(output, 'w') as target:
            for name, data in [(config_name, content), ('manifest.json', json.dumps([
                    {'Config': config_name, 'RepoTags': [destination], 'Layers': layers}]).encode())]:
                info = tarfile.TarInfo(name); info.size = len(data); target.addfile(info, io.BytesIO(data))
            for name in layers:
                # Docker content-addressed archives are self-contained after loading the source.
                member = archive.getmember(name)
                stream = archive.extractfile(member)
                digest = hashlib.sha256()
                while chunk := stream.read(1024 * 1024): digest.update(chunk)
                if 'sha256:' + digest.hexdigest() != diff_ids[layers.index(name)]:
                    raise ValueError('Runtime layer checksum mismatch')
                target.addfile(member, archive.extractfile(member))
    subprocess.run(['docker', 'load', '-i', str(output)], check=True)
    report = {'source': source, 'image': destination, 'imageId': 'sha256:' + config_name[:-5],
              'layers': diff_ids, 'businessLayersRetained': 0,
              'provenance': 'Existing host image; upstream signature not re-verified'}
    (root / 'base-provenance.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__': recover(*sys.argv[1:])
