"""Repair the isolated monitor's inherited worker healthcheck, preserving its container for rollback."""
import copy
import datetime
import http.client
import json
from pathlib import Path
import socket
import subprocess


def repair(name, evidence):
    original = json.loads(subprocess.check_output(['docker', 'inspect', name]))[0]
    assert original['HostConfig']['NetworkMode'] == 'host'
    assert any(m['Destination'] == '/monitor' and not m['RW'] for m in original['Mounts'])
    evidence = Path(evidence)
    evidence.mkdir(parents=True, exist_ok=True)
    snapshot = evidence / 'monitor-before-private.json'
    snapshot.write_text(json.dumps(original))
    snapshot.chmod(0o600)
    config = copy.deepcopy(original['Config'])
    health = ['CMD', 'node', '-e', "const c=JSON.parse(require('fs').readFileSync(process.env.CLINE_MONITOR_CONFIG));fetch('http://127.0.0.1:'+(c.port??18346)+'/healthz').then(async r=>{if(!r.ok||(await r.json()).service!=='native-cline-monitor')process.exit(1)}).catch(()=>process.exit(1))"]
    if config.get('Healthcheck', {}).get('Test') == health:
        return {'status': 'ALREADY_CONFIGURED'}
    backup = name + '-before-health-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    config['Image'] = original['Image']
    config['Healthcheck'] = {'Test': health, 'Interval': 15000000000,
                           'Timeout': 5000000000, 'StartPeriod': 5000000000, 'Retries': 3}
    created = False
    subprocess.run(['docker', 'stop', '--time', '15', name], check=True, capture_output=True)
    subprocess.run(['docker', 'rename', name, backup], check=True, capture_output=True)
    try:
        connection = http.client.HTTPConnection('localhost', timeout=30)
        connection.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        connection.sock.connect('/var/run/docker.sock')
        connection.request('POST', '/v1.45/containers/create?name=' + name,
                           json.dumps({**config, 'HostConfig': original['HostConfig']}),
                           {'Content-Type': 'application/json'})
        response = connection.getresponse()
        body = response.read()
        connection.close()
        if response.status >= 300:
            raise RuntimeError('Monitor creation failed: ' + str(response.status))
        created = True
        subprocess.run(['docker', 'start', name], check=True, capture_output=True)
        current = json.loads(subprocess.check_output(['docker', 'inspect', name]))[0]
        assert current['Image'] == original['Image']
        for key in ['Env', 'User', 'Entrypoint', 'Cmd', 'WorkingDir']:
            assert current['Config'][key] == original['Config'][key], key
        assert sorted(current['Mounts'], key=lambda m: m['Destination']) == sorted(original['Mounts'], key=lambda m: m['Destination'])
        assert current['HostConfig'] == original['HostConfig']
    except BaseException:
        if created:
            subprocess.run(['docker', 'rm', '-f', name], check=True, capture_output=True)
        subprocess.run(['docker', 'rename', backup, name], check=True, capture_output=True)
        subprocess.run(['docker', 'start', name], check=True, capture_output=True)
        raise
    return {'status': 'UPDATED', 'container': name, 'backupContainer': backup,
            'imagePreserved': True, 'mountsAndRuntimeConfigurationPreserved': True}


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--name', default='cline-native-monitor')
    parser.add_argument('--evidence', required=True)
    args = parser.parse_args()
    print(json.dumps(repair(args.name, args.evidence)))
