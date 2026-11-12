"""Brief Silo outage inside the candidate network namespace, with exact cleanup.

No host firewall rules, shared service stop, credential/configuration changes,
global flush, or global Docker cleanup. Run as the explicitly authorized operator.
"""
import argparse
import json
import os
import pathlib
import subprocess
import time
import uuid
from urllib.parse import urlparse

p = argparse.ArgumentParser()
p.add_argument('--root', required=True)
p.add_argument('--seconds', type=int, default=20)
a = p.parse_args()
root = pathlib.Path(a.root).resolve()
if not str(root).startswith('/opt/runtime-os/native-temporal/') or not 1 <= a.seconds <= 60:
    raise SystemExit('An isolated candidate root and at most 60 seconds required')

def run(cmd, **kw):
    return subprocess.run(cmd, check=True, capture_output=True, text=True, **kw).stdout

api = json.loads(run(['docker', 'inspect', 'runtime-native-temporal-api']))[0]
labels = api['Config']['Labels']
assert labels['com.docker.compose.project'] == 'runtime-native-temporal-candidate'
assert labels['com.docker.compose.service'] in ['api', 'temporal-control-api']
pid = api['State']['Pid']
namespace = pathlib.Path('/proc') / str(pid) / 'ns/net'
identity = namespace.stat().st_ino
assert identity != pathlib.Path('/proc/1/ns/net').stat().st_ino, 'Never change the host firewall'
env = dict(value.split('=', 1) for value in api['Config']['Env'])
uri = urlparse(env['CHATCHAT_ASSETS_ENDPOINT'])
assert uri.scheme == 'http' and uri.hostname in ['silo', 'host.docker.internal'] and (uri.port or 80) == 9000, 'This probe supports the existing test Silo endpoint only'
silo = json.loads(run(['docker', 'inspect', 'silo']))[0]
addresses = [v['IPAddress'] for network, v in silo['NetworkSettings']['Networks'].items()
             if network in api['NetworkSettings']['Networks'] and v['IPAddress']]
if uri.hostname == 'host.docker.internal':
    addresses.append('172.17.0.1')
assert addresses, 'An exact shared-network Silo address is required'
prefix = ['nsenter', '-t', str(pid), '-n']
comment = 'runtime-native-temporal-test-' + uuid.uuid4().hex
rules = []
result = dict(startedAt=time.time(), namespaceInode=identity, targets=addresses,
              seconds=a.seconds, hostFirewallModified=False, cleanup=False)
try:
    for address in addresses:
        rule = ['-p', 'tcp', '-d', address, '--dport', '9000', '-m', 'comment',
                '--comment', comment, '-j', 'REJECT', '--reject-with', 'tcp-reset']
        run(prefix + ['iptables', '-I', 'OUTPUT', '1', *rule])
        rules.append(rule)
    code = '''import urllib.request,urllib.error,sys
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))
try:
 urllib.request.urlopen(sys.argv[1],timeout=3).read();sys.exit(2)
except urllib.error.HTTPError: sys.exit(2)
except OSError: print('SILO_CONNECTION_REJECTED')
'''
    result['blockedProbe'] = run(prefix + ['python3', '-c', code, 'http://'+addresses[0]+':9000/']).strip()
    time.sleep(a.seconds)
    listing = run(prefix + ['iptables', '-L', 'OUTPUT', '-v', '-n', '-x'])
    result['packetCounters'] = [line.strip() for line in listing.splitlines() if comment in line]
    assert sum(int(line.split()[0]) for line in result['packetCounters']) > 0, 'Real blocked traffic required'
finally:
    if namespace.exists() and namespace.stat().st_ino == identity:
        for rule in reversed(rules):
            run(prefix + ['iptables', '-D', 'OUTPUT', *rule])
        result['cleanup'] = True
    else:
        # The old namespace disappearing also removes its private rules.
        result['cleanup'] = not namespace.exists()
    result['finishedAt'] = time.time()
    evidence = root / 'evidence'
    evidence.mkdir(exist_ok=True)
    (evidence / ('silo-outage-'+comment+'.json')).write_text(json.dumps(result, indent=2))
    print(json.dumps(result))
assert result['cleanup'], 'Candidate namespace cleanup must be confirmed'
