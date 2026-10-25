"""Verify platform takeover against the observed Runtime OS topology and data contract."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request


def run(*args):
    result = subprocess.run(args, text=True, capture_output=True, timeout=120)
    if result.returncode:
        raise RuntimeError('Verification command failed: ' + args[0])
    return result.stdout.strip()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--release', type=Path, required=True)
    p.add_argument('--contract-file', type=Path, required=True)
    p.add_argument('--record', type=Path, required=True)
    p.add_argument('--phase', choices=['before', 'after'], required=True)
    p.add_argument('--approved-upgrade-file', type=Path, help='Explicit service -> immutable image and optional jarSha256 approval for an actual upgrade')
    a = p.parse_args()
    assert os.getuid() == 0
    os.umask(0o077)
    manifest = json.loads((a.release / 'manifest.json').read_text())
    source = json.loads((a.release / 'source-compose-private.json').read_text())
    contract = json.loads(a.contract_file.read_text())
    upgrades = json.loads(a.approved_upgrade_file.read_text()) if a.approved_upgrade_file else {}
    assert set(upgrades) <= set(source['services']), 'Unknown approved upgrade service'
    for name, upgrade in upgrades.items():
        assert re.search(r'@sha256:[a-f0-9]{64}$', upgrade['image']), 'Immutable upgrade image required'
        if name in contract.get('service_jars', {}):
            assert re.fullmatch(r'[a-f0-9]{64}', upgrade.get('jarSha256', '')), 'Expected upgraded JAR checksum required'
    all_containers = json.loads(run('docker', 'inspect', *run('docker', 'ps', '-aq').split()))
    by_name = {c['Name'].lstrip('/'): c for c in all_containers}
    services = {c['Config'].get('Labels', {}).get('com.docker.compose.service'): c for c in all_containers
                if c['Config'].get('Labels', {}).get('com.docker.compose.project') == manifest['project']}
    target = {n: c for n, c in services.items() if n in source['services']}
    assert set(target) == set(source['services'])
    fingerprints, jars = {}, {}
    for service, tables in contract.get('fingerprint_tables', {}).items():
        env = source['services'][service]['environment']
        database = next(v for k, v in env.items() if k.endswith('_POSTGRESQL_URL')).split('?')[0].rsplit('/', 1)[1]
        assert re.fullmatch(r'[A-Za-z0-9_]+', database)
        for table in tables:
            assert re.fullmatch(r'[A-Za-z0-9_]+', table)
            prefix = ['docker', 'exec', '--user', 'postgres', contract['postgres_container'], 'psql', '-At', '-v', 'ON_ERROR_STOP=1', '-d', database, '-c']
            if run(*prefix, "SELECT to_regclass('public." + table + "')"):
                fingerprints[service + '.' + table] = run(*prefix, "SELECT md5(coalesce(jsonb_agg(to_jsonb(t)-'updated_at'-'created_at' ORDER BY id)::text,'[]')) FROM " + table + ' t')
    for service, path in contract.get('service_jars', {}).items():
        jars[service] = run('docker', 'exec', target[service]['Id'], 'sha256sum', path).split()[0]
    for name, container in target.items():
        for key, expected in source['services'][name].get('environment', {}).items():
            actual = dict(x.split('=', 1) for x in container['Config'].get('Env', []))
            assert actual.get(key) == str(expected), 'Service environment changed: ' + name + '/' + key
        for network, settings in source['services'][name].get('networks', {}).items():
            if settings and settings.get('ipv4_address'):
                network_name = source['networks'][network]['name']
                assert container['NetworkSettings']['Networks'][network_name]['IPAddress'] == settings['ipv4_address']
        mode = source['services'][name].get('network_mode', '')
        if mode.startswith('service:'):
            assert container['HostConfig']['NetworkMode'] == 'container:' + target[mode[8:]]['Id']
        mounted = {m['Destination']: m for m in container['Mounts']}
        for declared in source['services'][name].get('volumes', []):
            actual = mounted[declared['target']]
            assert actual['RW'] == (not declared.get('read_only', False)), 'Mount access mode changed'
            if declared['type'] == 'bind':
                expected_path, actual_path = Path(declared['source']).resolve(), Path(actual['Source']).resolve()
                if not declared.get('read_only') or expected_path.is_dir():
                    assert actual_path == expected_path, 'Persistent data/config directory was redirected'
                else:
                    assert hashlib.sha256(expected_path.read_bytes()).digest() == hashlib.sha256(actual_path.read_bytes()).digest(), 'Configuration or secret file content changed'
    snapshot = {'services': {n: {'id': c['Id'], 'imageId': c['Image'], 'startedAt': c['State']['StartedAt']} for n, c in target.items()},
                'otherContainers': {c['Id']: {'image': c['Image'], 'startedAt': c['State']['StartedAt']} for c in all_containers if c['Id'] not in {x['Id'] for x in target.values()}},
                'policyFingerprints': fingerprints, 'jarSha256': jars}
    if a.phase == 'before':
        assert not a.record.exists(), 'Do not overwrite the pre-migration baseline'
        a.record.write_text(json.dumps(snapshot, indent=2))
        print(json.dumps({'baselineCaptured': True, 'services': len(target), 'policyTables': len(fingerprints), 'jars': list(jars)}))
        return
    before = json.loads(a.record.read_text())
    assert before['policyFingerprints'] == fingerprints, 'Existing business authorization/binding records changed'
    for name, actual in jars.items():
        expected = upgrades.get(name, {}).get('jarSha256', before['jarSha256'].get(name))
        assert actual == expected, 'Unexpected business binary change: ' + name
    for name, current in snapshot['services'].items():
        assert current['id'] != before['services'][name]['id'], 'Service was not actually replaced: ' + name
        if name in upgrades:
            expected_image = json.loads(run('docker', 'image', 'inspect', upgrades[name]['image']))[0]['Id']
            assert current['imageId'] == expected_image and target[name]['Config']['Image'] == upgrades[name]['image'], 'Unapproved upgraded image: ' + name
        else:
            assert current['imageId'] == before['services'][name]['imageId'], 'Unexpected image content change: ' + name
        assert target[name]['State']['Running']
    current_ids = {c['Id']: c for c in all_containers}
    for identity, original in before['otherContainers'].items():
        assert identity in current_ids and current_ids[identity]['Image'] == original['image'] and current_ids[identity]['State']['StartedAt'] == original['startedAt'], 'Non-target workload changed'
    result = {'allDeclaredServicesReplaced': True, 'serviceCount': len(target), 'businessBinariesUnchanged': before['jarSha256'] == jars,
              'approvedUpgradeServices': sorted(upgrades), 'approvedUpgradeVerified': bool(upgrades), 'unchangedServiceImagesPreserved': True,
              'businessEnvironmentUnchanged': True, 'staticAddressesPreserved': True, 'namespaceFollowersUpdated': True,
              'persistentMountsPreserved': True, 'configurationAndSecretBytesPreserved': True,
              'businessPolicyFingerprintsPreserved': True, 'nonTargetContainersPreserved': True,
              'services': snapshot['services'], 'jarSha256': jars, 'policyFingerprints': fingerprints}
    report = a.record.with_name('upgrade-verification.json' if upgrades else 'takeover-verification.json')
    report.write_text(json.dumps(result, indent=2))
    print(json.dumps(result))


if __name__ == '__main__':
    main()
