"""Recreate the inspected PostgreSQL container with bounded Docker logging.

Usage: python3 bound-postgres-container-logs.py PRIVATE_RELEASE_DIRECTORY
Keeps the exact image/config/mount sources/ports. Requires idle Coding workers;
retains a stopped rollback container and a root-only original configuration.
"""
import copy
import http.client
import json
from pathlib import Path
import socket
import subprocess
import sys
import time


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()


class DockerConnection(http.client.HTTPConnection):
    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.connect('/var/run/docker.sock')


def mounts(info):
    return sorted((m['Type'], m['Source'], m['Destination'], m['RW']) for m in info['Mounts'])


def main():
    release = Path(sys.argv[1]).resolve()
    release.mkdir(mode=0o700, parents=True, exist_ok=True)
    original = json.loads(run('docker', 'inspect', 'postgresql18'))[0]
    if original['HostConfig']['LogConfig'].get('Config') == {'max-size': '100m', 'max-file': '3'}:
        print('PostgreSQL logging is already bounded');return
    if original['HostConfig']['AutoRemove'] or not original['State']['Running']:
        raise RuntimeError('Expected a running, persistent PostgreSQL container')
    for node, port in [('runtime-native-temporal-node1',18461),('runtime-native-temporal-node2',18471)]:
        health = json.loads(run('docker','exec',node,'node','-e',"fetch('http://127.0.0.1:"+str(port)+"/health/live').then(r=>r.text()).then(console.log)"))
        if health['capacity']['activeWorkers']:
            raise RuntimeError('Active Coding workers prevent database container replacement')
    backup = release / 'postgres-container.rollback.private.json'
    with backup.open('x') as f:
        f.write(json.dumps(original, indent=2))
    backup.chmod(0o600)
    config = copy.deepcopy(original['Config'])
    config['Image'] = original['Image']
    host = copy.deepcopy(original['HostConfig'])
    host['LogConfig'] = dict(Type='json-file', Config={'max-size': '100m', 'max-file': '3'})
    # Explicitly reattach anonymous volumes; never let Docker create an empty PG
    # data volume while recreating an official PostgreSQL image.
    binds = list(host.get('Binds') or [])
    destinations = {m['Target'] for m in host.get('Mounts',[])}
    for mount in original['Mounts']:
        if mount['Type'] == 'volume' and mount['Destination'] not in destinations:
            if not any((':'+mount['Destination']+':') in (b+':') for b in binds):
                binds.append(mount['Name']+':'+mount['Destination']+('' if mount['RW'] else ':ro'))
    host['Binds'] = binds
    config['HostConfig'] = host
    endpoints = {}
    for name, network in original['NetworkSettings']['Networks'].items():
        endpoints[name] = {k:network[k] for k in ['IPAMConfig','Links','Aliases','DriverOpts'] if network.get(k) is not None}
    config['NetworkingConfig'] = dict(EndpointsConfig=endpoints)
    rollback = 'postgresql18-storage-rollback-20261115'
    run('docker','stop','--time','60','postgresql18')
    run('docker','rename','postgresql18',rollback)
    created = False
    try:
        connection = DockerConnection('localhost', timeout=60)
        connection.request('POST','/containers/create?name=postgresql18',json.dumps(config),{'Content-Type':'application/json'})
        response = connection.getresponse();body=response.read();connection.close()
        if response.status != 201:
            (release/'postgres-create-error.private.json').write_bytes(body)
            (release/'postgres-create-error.private.json').chmod(0o600)
            raise RuntimeError('Docker rejected the preserved PostgreSQL configuration')
        created = True
        candidate = json.loads(run('docker','inspect','postgresql18'))[0]
        if mounts(candidate) != mounts(original) or candidate['Config']['Env'] != original['Config']['Env']:
            raise RuntimeError('PostgreSQL mount/config identity changed')
        run('docker','start','postgresql18')
        for attempt in range(60):
            try:
                run('docker','exec','postgresql18','pg_isready','-q');break
            except subprocess.CalledProcessError:
                if attempt==59:raise
                time.sleep(1)
        candidate = json.loads(run('docker','inspect','postgresql18'))[0]
        if candidate['NetworkSettings']['Ports'] != original['NetworkSettings']['Ports']:
            raise RuntimeError('Published PostgreSQL ports changed')
        if run('docker','exec','postgresql18','sh','-c','PGPASSWORD="$POSTGRES_PASSWORD" psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-${POSTGRES_USER:-postgres}}" -Atqc "select 1"') != '1':
            raise RuntimeError('Authenticated PostgreSQL check failed')
    except Exception:
        if created:
            run('docker','stop','--time','60','postgresql18')
            run('docker','rename','postgresql18','postgresql18-storage-failed-20261115')
        run('docker','rename',rollback,'postgresql18')
        run('docker','start','postgresql18')
        raise
    receipt=dict(status='PASSED',container='postgresql18',image=original['Image'],
                 logging=host['LogConfig'],sameMounts=True,sameEnvironment=True,samePublishedPorts=True,
                 authenticatedQuery='SELECT 1',rollbackContainer=rollback,dataVolumesModified=False)
    (release/'postgres-logging.json').write_text(json.dumps(receipt,indent=2))
    print(json.dumps(receipt))


if __name__ == '__main__':
    main()
