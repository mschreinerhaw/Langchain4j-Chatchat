"""Collect non-secret source, image, health and infrastructure reuse evidence."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from datetime import datetime, timezone

from production_compose import PROBE, release_compose, validate_container


def docker(*args):
    return subprocess.check_output(['docker', *args], text=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', required=True)
    args = parser.parse_args()
    root = Path(args.release).resolve()
    source = root / 'source'
    manifest = json.loads((root / 'source-manifest.json').read_text())
    for name, expected in manifest['files'].items():
        path = (source / name).resolve()
        assert path.is_relative_to(source)
        assert hashlib.sha256(path.read_bytes()).hexdigest() == expected, name
    release_compose(root)
    config = json.loads((root / 'compose.production.json').read_text())
    config['services'][PROBE] = {}
    containers = json.loads(docker('inspect', *docker('ps', '-aq').split()))
    by_name = {c['Name'].lstrip('/'): c for c in containers}
    infra = []
    for line in (root / 'infrastructure-before.txt').read_text().splitlines():
        name, cid, image, started = line.split()
        c = by_name[name.lstrip('/')]
        assert (c['Id'], c['Image'], c['State']['StartedAt']) == (cid, image, started), name
        assert c['State']['Running'], name
        infra.append({'name': name, 'id': cid, 'imageId': image, 'startedAt': started, 'unchanged': True})
    deployed = []
    for service, spec in config['services'].items():
        if spec.get('profiles'):
            continue
        name = spec.get('container_name', config['name'] + '-' + service + '-1')
        c = by_name[name]
        state = c['State']
        if service == PROBE:
            validate_container(c)
        if service in ['temporal-schema', 'temporal-namespace']:
            assert state['Status'] == 'exited' and state['ExitCode'] == 0, service
        else:
            assert state['Running'], service
            assert state.get('Health', {}).get('Status', 'healthy') == 'healthy', service
        deployed.append({'service': service, 'name': name, 'imageId': c['Image'],
            'status': state['Status'], 'health': state.get('Health', {}).get('Status'),
            'restartCount': c['RestartCount'], 'startedAt': state['StartedAt']})
    worker = json.loads((root / 'private/cn2-gateway.json').read_text())['workerImage']
    executor = json.loads((root / 'private/cn2-executor.json').read_text())
    worker_actual = docker('-H', 'unix://' + executor['dockerSocket'], 'image', 'inspect',
        config['services']['native-node']['image'], '--format', '{{.Id}}').strip()
    assert worker == worker_actual
    active = docker('-H', 'unix://' + executor['dockerSocket'], 'ps', '-q').split()
    assert not active, 'Acceptance workers must be stopped before final evidence collection'
    assert (root / 'runtime-regression.exit').read_text().strip() == '0'
    report = {'passed': True, 'timestamp': datetime.now(timezone.utc).isoformat(),
        'sourceCommit': manifest['commit'], 'verifiedSourceFiles': len(manifest['files']),
        'serverBuilds': json.loads((root / 'build-summary.json').read_text()),
        'workerImageId': worker, 'infrastructure': infra, 'containers': deployed,
        'activeAcceptanceWorkers': len(active),
        'limitations': ['Upstream enterpriseAcceptance/productionReady control-plane flags remain false; this evidence covers deployment and the recorded acceptance cases.']}
    target = root / 'evidence'
    target.mkdir(exist_ok=True)
    (target / 'server-verification.json').write_text(json.dumps(report, indent=2))
    print(json.dumps({'passed': True, 'services': len(deployed), 'sourceFiles': len(manifest['files']), 'infrastructureReused': len(infra)}))


if __name__ == '__main__':
    main()
