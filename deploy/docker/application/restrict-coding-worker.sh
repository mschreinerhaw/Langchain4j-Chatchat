#!/usr/bin/env bash
# Apply only the selected worker boundary; resolve container identities for every release.
set -euo pipefail
python3 - <<'PY'
import ipaddress
import json
import os
import re
import shlex
import subprocess

network = os.environ.get('CODING_NETWORK', 'runtime-os-app-stage_coding')
chain = os.environ.get('CODING_FIREWALL_CHAIN', 'CLINE_APP_STAGE')
if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,27}', chain):
    raise SystemExit('Invalid owned firewall chain')
def inspect(name):
    return json.loads(subprocess.check_output(['docker', 'inspect', name]))[0]
def address(name):
    value = ipaddress.IPv4Address(inspect(name)['NetworkSettings']['Networks'][network]['IPAddress'])
    if value.is_unspecified or value.is_loopback or value.is_multicast:
        raise SystemExit('Invalid container identity')
    return str(value)
worker = address(os.environ.get('CODING_WORKER_CONTAINER', 'runtime-os-app-stage-coding-agent-1'))
api = address(os.environ.get('CODING_API_CONTAINER', 'runtime-os-app-stage-api-1'))
if worker == api:
    raise SystemExit('Worker and Runtime must have separate identities')
port = int(os.environ.get('CODING_CALLBACK_PORT', '8081'))
if not 1 <= port <= 65535:
    raise SystemExit('Invalid callback port')
if port in (8080, 8791, 8792):
    raise SystemExit('Coding callbacks must use a dedicated callback-only listener, not a general API or worker/monitor port')
rules = subprocess.check_output(['iptables', '-S'], text=True).splitlines()
if '-N DOCKER-USER' not in rules:
    raise SystemExit('Docker firewall chain is unavailable')
# One atomic filter-table commit replaces only our chain and its source-address jumps.
batch = ['*filter']
if '-N ' + chain not in rules:
    batch.append('-N ' + chain)
for rule in rules:
    parts = shlex.split(rule)
    if len(parts) > 2 and parts[0] == '-A' and parts[1] in ('INPUT', 'DOCKER-USER'):
        if '-j' in parts and parts[parts.index('-j') + 1] == chain:
            batch.append(shlex.join(['-D'] + parts[1:]))
batch += ['-F ' + chain,
          f'-A {chain} -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT',
          f'-A {chain} -d {api} -p tcp --dport {port} -j ACCEPT',
          f'-A {chain} -j DROP',
          f'-I INPUT 1 -s {worker} -j {chain}',
          f'-I DOCKER-USER 1 -s {worker} -j {chain}', 'COMMIT']
validate = os.environ.get('CODING_FIREWALL_VALIDATE_ONLY', '0')
if validate not in ('0','1'):
    raise SystemExit('Invalid firewall validation mode')
command = ['iptables-restore', '--noflush', '--wait', '10'] + (['--test'] if validate == '1' else [])
subprocess.run(command, input='\n'.join(batch)+'\n', text=True, check=True)
print('Coding worker boundary '+('validated' if validate == '1' else 'updated')+' from current container identities')
PY
