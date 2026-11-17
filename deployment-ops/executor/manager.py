"""Deployment Manager P0: read-only discovery and explicitly approved operations.

The Docker socket stays in the node agent. No shell/exec/host-path API is exposed.
An interrupted operation becomes UNKNOWN; startup never replays Docker writes.
"""
import hashlib
import json
import os
import re
import secrets
import sqlite3
import threading
import time
from pathlib import Path
from contextlib import contextmanager
from urllib.parse import quote, urlencode


def encoded(value):
    return quote(str(value), safe='')


def redact(value):
    if isinstance(value, dict):
        return {k: ('[REDACTED]' if re.search(r'password|secret|token|credential|authorization|^Env$', k, re.I) else redact(v)) for k, v in value.items()}
    if isinstance(value, list):
        return ['[REDACTED]' if i and isinstance(value[i-1], str) and re.fullmatch(r'--?(?:password|token|secret|credential|api-key)', value[i-1], re.I) else redact(v) for i, v in enumerate(value)]
    if isinstance(value, str):
        value = re.sub(r'(https?://)[^/@\s]+:[^/@\s]+@', r'\1[REDACTED]@', value)
        return re.sub(r'((?:password|token|secret|credential|api[_-]?key)\s*[=:]\s*)[^\s,;]+', r'\1[REDACTED]', value, flags=re.I)
    return value


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def container_metrics(raw):
    cpu, previous = raw.get('cpu_stats', {}), raw.get('precpu_stats', {})
    delta = cpu.get('cpu_usage', {}).get('total_usage', 0) - previous.get('cpu_usage', {}).get('total_usage', 0)
    system = cpu.get('system_cpu_usage', 0) - previous.get('system_cpu_usage', 0)
    cores = cpu.get('online_cpus') or len(cpu.get('cpu_usage', {}).get('percpu_usage', []))
    memory = raw.get('memory_stats', {})
    return {'cpuPercent': delta / system * cores * 100 if system > 0 and cores and delta >= 0 else None,
            'memoryBytes': max(0, memory.get('usage', 0) - memory.get('stats', {}).get('inactive_file', 0)),
            'memoryLimitBytes': memory.get('limit'), 'scope': 'container',
            'networkRxBytes': sum(x.get('rx_bytes', 0) for x in raw.get('networks', {}).values()),
            'networkTxBytes': sum(x.get('tx_bytes', 0) for x in raw.get('networks', {}).values()),
            'blockReadBytes': sum(x['value'] for x in raw.get('blkio_stats', {}).get('io_service_bytes_recursive', []) or [] if x.get('op', '').lower() == 'read'),
            'blockWriteBytes': sum(x['value'] for x in raw.get('blkio_stats', {}).get('io_service_bytes_recursive', []) or [] if x.get('op', '').lower() == 'write')}


class Manager:
    def __init__(self, state, docker, lock=None, read_only=None):
        self.root = Path(state) / 'manager'
        self.root.mkdir(parents=True, exist_ok=True)
        self.path = self.root / 'ledger.sqlite'
        self.docker = docker
        self.lock = lock or threading.Lock()
        self.read_only = os.getenv('OPS_READ_ONLY', '1') != '0' if read_only is None else read_only
        self.owner=os.getenv('OPS_INSTANCE_ID') or hashlib.sha256(str(self.root.resolve()).encode()).hexdigest()[:32]
        self.previous = None
        self.stopping = threading.Event()
        with self.db() as db:
            db.executescript('''
              CREATE TABLE IF NOT EXISTS plans(id TEXT PRIMARY KEY, actor TEXT, spec TEXT, snapshot TEXT, preview TEXT, expires REAL, used INTEGER DEFAULT 0);
              CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY, actor TEXT, request_key TEXT UNIQUE, request_hash TEXT, status TEXT, action TEXT, target TEXT, created REAL, updated REAL, result TEXT, error TEXT);
              CREATE TABLE IF NOT EXISTS samples(id INTEGER PRIMARY KEY, at REAL, resource TEXT, payload TEXT);
              CREATE INDEX IF NOT EXISTS sample_time ON samples(at);
              CREATE INDEX IF NOT EXISTS sample_resource ON samples(resource,at);
              UPDATE jobs SET status='UNKNOWN',error='Agent restarted; reconcile actual Docker state before a new operation',updated=strftime('%s','now') WHERE status IN ('PENDING','RUNNING');
            ''')
        if os.name != 'nt':
            self.root.chmod(0o700)
            self.path.chmod(0o600)

    @contextmanager
    def db(self):
        if os.getenv('OPS_NATIVE')=='1':
            from native_storage import validate
            validate()
        db = sqlite3.connect(self.path, timeout=20)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def policy(self):
        return {'readOnly': self.read_only, 'approvalRequired': True,
                'allowedRegistries': os.getenv('OPS_ALLOWED_REGISTRIES', '').split(',') if os.getenv('OPS_ALLOWED_REGISTRIES') else [],
                'terminalSupported': False, 'hostBindMountsSupported': False}

    def inspect_container(self, ident):
        if not re.fullmatch('[a-f0-9]{64}', ident):
            raise ValueError('Full container ID required')
        return self.docker('GET', '/containers/' + ident + '/json')

    def mutable_container(self, ident):
        item = self.inspect_container(ident)
        labels = item.get('Config', {}).get('Labels') or {}
        if labels.get('com.docker.compose.project', '').startswith('deployment-ops') or labels.get('deployment-ops.control-plane') == 'true':
            raise ValueError('Control plane is protected; use the external recovery procedure')
        if labels.get('deployment-ops.installation') == 'true':
            raise ValueError('Use the owning signed installation workflow for this container')
        if os.getenv('OPS_STRICT_OWNERSHIP')=='1' and (not any(labels.get(key)=='true' for key in ('deployment-ops.manager','deployment-ops.managed')) or labels.get('deployment-ops.owner')!=self.owner):
            raise ValueError('External containers are discovery-only; no lifecycle ownership')
        return item

    def image(self, ident):
        if not re.fullmatch(r'sha256:[a-f0-9]{64}', ident):
            raise ValueError('Immutable image ID required')
        return self.docker('GET', '/images/' + encoded(ident) + '/json')

    def references(self, image):
        return [{'id': c['Id'], 'names': c.get('Names', [])} for c in self.docker('GET', '/containers/json?all=1') if c['ImageID'] == image]

    def create_spec(self, spec):
        allowed = {'action', 'name', 'image', 'command', 'environment', 'ports', 'volumes', 'network', 'memoryMiB', 'cpus', 'restart', 'healthcheck', 'application', 'user'}
        if set(spec) - allowed:
            raise ValueError('Unsupported container configuration')
        if not re.fullmatch('[a-z][a-z0-9_.-]{2,63}', spec.get('name', '')):
            raise ValueError('Container name must contain 3-64 lowercase letters, numbers, dot, dash or underscore')
        image = self.docker('GET', '/images/' + encoded(spec.get('image', '')) + '/json')
        if any('/' + spec['name'] in c.get('Names', []) for c in self.docker('GET', '/containers/json?all=1')):
            raise ValueError('Container name already exists')
        environment = spec.get('environment', {})
        if not isinstance(environment, dict) or len(environment) > 100 or any(not re.fullmatch('[A-Za-z_][A-Za-z0-9_]*', k) or not isinstance(v, str) or len(v) > 16384 for k, v in environment.items()):
            raise ValueError('Invalid environment map')
        command = spec.get('command', [])
        if not isinstance(command, list) or len(command) > 100 or any(not isinstance(x, str) or len(x) > 4096 for x in command):
            raise ValueError('Command must be an argument array')
        cpu = float(spec.get('cpus', 1))
        memory = int(spec.get('memoryMiB', 256))
        if not .1 <= cpu <= 64 or not 16 <= memory <= 131072:
            raise ValueError('Invalid CPU or memory limit')
        network = spec.get('network', 'bridge')
        net = self.docker('GET', '/networks/' + encoded(network))
        if net.get('Driver') not in ('bridge', 'null') or net.get('Name') == 'host':
            raise ValueError('Only bridge or none networking is supported')
        bindings = {}
        for port in spec.get('ports', []):
            if set(port) - {'container', 'host', 'address'} or not 1 <= int(port['container']) <= 65535 or not 1024 <= int(port['host']) <= 65535:
                raise ValueError('Invalid port mapping')
            address = port.get('address', '127.0.0.1')
            import ipaddress
            ipaddress.ip_address(address)
            bindings[str(port['container']) + '/tcp'] = [{'HostIp': address, 'HostPort': str(port['host'])}]
        mounts = []
        for volume in spec.get('volumes', []):
            if set(volume) - {'source', 'target', 'readOnly'} or not re.fullmatch('[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', volume.get('source', '')):
                raise ValueError('Only named volumes are accepted; host paths are forbidden')
            target = volume.get('target', '')
            if not target.startswith('/') or '..' in target.split('/') or target == '/' or target.startswith(('/proc', '/sys', '/dev')):
                raise ValueError('Invalid volume target')
            info = self.docker('GET', '/volumes/' + encoded(volume['source']))
            labels=info.get('Labels') or {}
            if (labels.get('deployment-ops.manager') != 'true' or (os.getenv('OPS_NATIVE')=='1' and labels.get('deployment-ops.owner')!=self.owner)) and not volume.get('readOnly', False):
                raise ValueError('External volumes can only be mounted read-only')
            mounts.append({'Type': 'volume', 'Source': volume['source'], 'Target': target, 'ReadOnly': bool(volume.get('readOnly', False))})
        if os.getenv('OPS_NATIVE')=='1':
            declared=set((image.get('Config') or {}).get('Volumes') or {})
            if declared-{mount['Target'] for mount in mounts}:
                raise ValueError('Map every image-declared persistent volume explicitly; anonymous system-disk volumes are forbidden')
        restart = spec.get('restart', 'unless-stopped')
        if restart not in ('no', 'unless-stopped', 'on-failure', 'always'):
            raise ValueError('Invalid restart policy')
        result = {'Image': image['Id'], 'Cmd': command or None, 'Env': [k + '=' + v for k, v in environment.items()],
                  'Labels': {'deployment-ops.manager': 'true', 'deployment-ops.application': str(spec.get('application', spec['name']))[:100]},
                  'ExposedPorts': {k: {} for k in bindings},
                  'HostConfig': {'NetworkMode': network, 'PortBindings': bindings, 'Mounts': mounts, 'Memory': memory * 1024**2,
                                 'NanoCpus': int(cpu * 10**9), 'PidsLimit': 256, 'RestartPolicy': {'Name': restart},
                                 'CapDrop': ['ALL'], 'SecurityOpt': ['no-new-privileges:true']}}
        if spec.get('user'):
            if not re.fullmatch(r'[0-9]{1,10}:[0-9]{1,10}', spec['user']):
                raise ValueError('Runtime user must be numeric UID:GID')
            result['User'] = spec['user']
        if os.getenv('OPS_NATIVE')=='1':result['Labels']['deployment-ops.owner']=self.owner
        if spec.get('healthcheck'):
            check = spec['healthcheck']
            if set(check) - {'command', 'intervalSeconds', 'timeoutSeconds', 'retries'} or not isinstance(check.get('command'), list) or not check['command'] or any(not isinstance(x, str) for x in check['command']):
                raise ValueError('Healthcheck requires an argument array')
            interval, timeout, retries = int(check.get('intervalSeconds', 30)), int(check.get('timeoutSeconds', 5)), int(check.get('retries', 3))
            if not 1 <= interval <= 3600 or not 1 <= timeout <= 300 or not 1 <= retries <= 20:
                raise ValueError('Invalid healthcheck timing')
            result['Healthcheck'] = {'Test': ['CMD'] + check['command'], 'Interval': interval * 10**9, 'Timeout': timeout * 10**9, 'Retries': retries}
        return result

    def snapshot(self, spec):
        action = spec.get('action')
        if action in ('container.start', 'container.stop', 'container.restart', 'container.delete'):
            item = self.mutable_container(spec.get('id', ''))
            children = [c['Id'] for c in self.docker('GET', '/containers/json?all=1') if c.get('HostConfig', {}).get('NetworkMode', '') in ('container:' + item['Id'], 'container:' + item['Name'].lstrip('/'))]
            if children and action in ('container.stop', 'container.restart', 'container.delete'):
                raise ValueError('Dependent containers share this network namespace; use the owning deployment workflow')
            if action == 'container.delete' and item['State']['Running']:
                raise ValueError('Stop the container first; forced removal is forbidden')
            return {'id': item['Id'], 'name': item['Name'].lstrip('/'), 'image': item['Image'],
                    'state': {k:item['State'].get(k) for k in ('Running','Status','StartedAt','FinishedAt','Restarting','ExitCode')}, 'mounts': item.get('Mounts', []), 'config': item['Config'],
                    'hostConfig': {k:item.get('HostConfig', {}).get(k) for k in ('Memory','NanoCpus','NetworkMode','PortBindings','Binds','Mounts','Privileged','CapAdd','CapDrop','RestartPolicy')},
                    'source': (item['Config'].get('Labels') or {}).get('com.docker.compose.project', 'standalone'), 'dependents': children}
        if action == 'container.create':
            return {'name': spec['name'], 'config': self.create_spec(spec)}
        if action in ('image.delete', 'image.tag'):
            item = self.image(spec.get('id', ''))
            refs = self.references(item['Id'])
            if action == 'image.delete' and refs:
                raise ValueError('Image is referenced by existing containers, including stopped containers')
            if action == 'image.tag':
                reference = self.reference(spec.get('repository', '') + ':' + spec.get('tag', ''))
                if any(reference in (i.get('RepoTags') or []) for i in self.docker('GET', '/images/json')):
                    raise ValueError('Target tag already exists; overwriting is forbidden')
            return {'name': item['Id'], 'id': item['Id'], 'tags': item.get('RepoTags'), 'references': refs}
        if action == 'image.pull':
            reference = self.reference(spec.get('reference', ''))
            registry = reference.split('/')[0]
            if registry not in self.policy()['allowedRegistries']:
                raise ValueError('Registry is not allowlisted on this node')
            existing = [i['Id'] for i in self.docker('GET', '/images/json') if reference in (i.get('RepoTags') or [])]
            if existing:
                raise ValueError('Pull would overwrite an existing tag; choose a new version tag')
            return {'name': reference, 'existing': existing}
        if action == 'volume.create':
            name = spec.get('name', '')
            if not re.fullmatch('ops-[a-z0-9][a-z0-9_.-]{1,60}', name):
                raise ValueError('New volumes must use an ops- name')
            if any(v['Name'] == name for v in self.docker('GET', '/volumes').get('Volumes') or []):
                raise ValueError('Volume already exists')
            return {'name': name}
        raise ValueError('Unsupported operation')

    @staticmethod
    def reference(value):
        if not re.fullmatch('[A-Za-z0-9][A-Za-z0-9._/:@-]{0,250}', value):
            raise ValueError('Invalid image reference')
        return value

    def plan(self, spec, actor):
        if len(json.dumps(spec)) > 65536:
            raise ValueError('Operation is too large')
        snapshot = self.snapshot(spec)
        ident = secrets.token_hex(16)
        preview = {'id': ident, 'action': spec['action'], 'target': snapshot['name'], 'snapshot': redact(snapshot),
                   'preservesVolumes': True, 'readOnly': self.read_only, 'expiresAt': time.time() + 300,
                   'externalManagement': snapshot.get('source') not in (None, 'standalone')}
        with self.db() as db:
            db.execute('DELETE FROM plans WHERE expires<?', (time.time(),))
            db.execute('INSERT INTO plans VALUES(?,?,?,?,?,?,0)', (ident, actor, json.dumps(spec), fingerprint(snapshot), json.dumps(preview), preview['expiresAt']))
        return preview

    def submit(self, data, actor):
        if self.read_only:
            raise ValueError('Node is read-only; deployment owner must explicitly enable operations')
        key = data.get('idempotencyKey', '')
        if not re.fullmatch('[A-Za-z0-9_-]{12,100}', key):
            raise ValueError('Idempotency key required')
        request_hash = fingerprint(data)
        with self.lock, self.db() as db:
            old = db.execute('SELECT * FROM jobs WHERE request_key=?', (key,)).fetchone()
            if old:
                if old['actor'] != actor or old['request_hash'] != request_hash:
                    raise ValueError('Idempotency key conflict')
                return self.public_job(old)
            plan = db.execute('SELECT * FROM plans WHERE id=?', (data.get('planId'),)).fetchone()
            if not plan or plan['actor'] != actor or plan['expires'] < time.time() or plan['used']:
                raise ValueError('Approval plan expired, missing, or already used')
            preview = json.loads(plan['preview'])
            if data.get('confirmation') != preview['target']:
                raise ValueError('Exact target name confirmation required')
            spec = json.loads(plan['spec'])
            if fingerprint(self.snapshot(spec)) != plan['snapshot']:
                raise ValueError('Docker resource changed; review a fresh plan')
            ident, now = secrets.token_hex(16), time.time()
            db.execute('UPDATE plans SET used=1 WHERE id=?', (plan['id'],))
            db.execute('INSERT INTO jobs VALUES(?,?,?,?,?,?,?,?,?,?,?)', (ident, actor, key, request_hash, 'PENDING', spec['action'], preview['target'], now, now, None, None))
        threading.Thread(target=self.run, args=(ident, spec, plan['snapshot']), daemon=True).start()
        return self.job(ident)

    def run(self, ident, spec, expected):
        with self.lock:
            try:
                with self.db() as db:
                    if db.execute('SELECT status FROM jobs WHERE id=?', (ident,)).fetchone()['status'] == 'CANCELLED':
                        return
                    db.execute("UPDATE jobs SET status='RUNNING',updated=? WHERE id=?", (time.time(), ident))
                if fingerprint(self.snapshot(spec)) != expected:
                    raise ValueError('Resource changed after approval; operation cancelled')
                action, target = spec['action'], spec.get('id', '')
                if action == 'container.create':
                    result = self.docker('POST', '/containers/create?name=' + encoded(spec['name']), json.dumps(self.create_spec(spec)).encode())
                elif action == 'container.delete':
                    result = self.docker('DELETE', '/containers/' + target + '?force=false&v=false')
                elif action.startswith('container.'):
                    result = self.docker('POST', '/containers/' + target + '/' + action.split('.')[1] + '?t=20')
                    if action in ('container.start', 'container.restart'):
                        # Engine can acknowledge start before an entrypoint fails.
                        # Persist the actual result, not just HTTP 204 acceptance.
                        time.sleep(.3)
                        state = self.inspect_container(target)['State']
                        if not state.get('Running') and state.get('ExitCode', 0) != 0:
                            raise ValueError('Container exited after start; exit code ' + str(state.get('ExitCode')))
                        result = {'id': target, 'state': {k:state.get(k) for k in ('Running','Status','ExitCode','StartedAt','FinishedAt')}}
                elif action == 'image.delete':
                    result = self.docker('DELETE', '/images/' + encoded(target) + '?force=false&noprune=true')
                elif action == 'image.tag':
                    result = self.docker('POST', '/images/' + encoded(target) + '/tag?' + urlencode({'repo': spec['repository'], 'tag': spec['tag']}))
                elif action == 'image.pull':
                    result = self.docker('POST', '/images/create?' + urlencode({'fromImage': spec['reference']}))
                    if isinstance(result, dict) and result.get('error'):
                        raise ValueError('Registry rejected pull; inspect registry configuration')
                    for line in result.get('text', '').splitlines() if isinstance(result, dict) else []:
                        if json.loads(line).get('error'):
                            raise ValueError('Registry rejected pull; inspect registry configuration')
                elif action == 'volume.create':
                    config={'Name':spec['name'],'Labels':{'deployment-ops.manager':'true'}}
                    if os.getenv('OPS_NATIVE')=='1':config['Labels']['deployment-ops.owner']=self.owner
                    if os.getenv('OPS_APP_DATA'):
                        base=Path(os.environ['OPS_APP_DATA']).resolve();target=base/'volumes'/spec['name']
                        if base not in target.resolve().parents:raise ValueError('Volume path escapes managed data root')
                        target.mkdir(parents=True,exist_ok=True)
                        config.update(Driver='local',DriverOpts={'type':'none','o':'bind','device':str(target)})
                    result = self.docker('POST','/volumes/create',json.dumps(config).encode())
                else:
                    raise ValueError('Unsupported action')
                with self.db() as db:
                    db.execute("UPDATE jobs SET status='SUCCESS',result=?,updated=? WHERE id=?", (json.dumps(redact(result)), time.time(), ident))
            except Exception as error:
                # A lost response can follow a successful write. Do not claim failure
                # or replay it. Operator must inspect the target before retrying.
                uncertain = not isinstance(error, ValueError)
                with self.db() as db:
                    message = 'Transport interrupted; reconcile actual Docker state' if uncertain else str(error)[:500]
                    for value in spec.get('environment', {}).values():
                        if len(value) >= 4:message = message.replace(value, '[REDACTED]')
                    db.execute('UPDATE jobs SET status=?,error=?,updated=? WHERE id=?', ('UNKNOWN' if uncertain else 'FAILED', redact(message), time.time(), ident))

    def cancel(self, ident):
        with self.db() as db:
            updated = db.execute("UPDATE jobs SET status='CANCELLED',updated=? WHERE id=? AND status='PENDING'", (time.time(), ident))
            if not updated.rowcount:
                raise ValueError('Only pending jobs can be cancelled; running writes require state reconciliation')
        return self.job(ident)

    @staticmethod
    def public_job(row):
        return {k: row[k] for k in ('id', 'actor', 'status', 'action', 'target', 'created', 'updated', 'error')}

    def job(self, ident):
        with self.db() as db:
            row = db.execute('SELECT * FROM jobs WHERE id=?', (ident,)).fetchone()
            if not row:
                raise ValueError('Job not found')
            result = self.public_job(row)
            result['result'] = json.loads(row['result']) if row['result'] else None
            return result

    def jobs(self):
        with self.db() as db:
            return [self.public_job(row) for row in db.execute('SELECT * FROM jobs ORDER BY created DESC LIMIT 300')]

    def sample(self, resource, payload):
        with self.db() as db:
            db.execute('INSERT INTO samples(at,resource,payload) VALUES(?,?,?)', (time.time(), resource, json.dumps(payload)))
            db.execute('DELETE FROM samples WHERE at<?', (time.time() - 7 * 86400,))

    def history(self, resource='host'):
        with self.db() as db:
            rows = db.execute('SELECT at,payload FROM samples WHERE resource=? ORDER BY at DESC LIMIT 120', (resource,)).fetchall()
        return [{'at': row['at'], **json.loads(row['payload'])} for row in reversed(rows)]

    def latest(self, resource):
        with self.db() as db:
            row = db.execute('SELECT at,payload FROM samples WHERE resource=? ORDER BY at DESC LIMIT 1', (resource,)).fetchone()
        return {'at': row['at'], **json.loads(row['payload'])} if row else None

    def host_metrics(self):
        if os.getenv('OPS_NATIVE')=='1':
            from native_storage import validate
            validate()
        proc = Path(os.getenv('OPS_HOST_PROC', '/host/proc'))
        disk = Path(os.getenv('OPS_HOST_ROOT', '/host/root'))
        now = time.monotonic()
        result = {'scope': 'host', 'collectedAt': time.time(), 'available': proc.is_dir() and disk.is_dir()}
        if not result['available']:
            return {**result, 'reason': 'Read-only host proc and root mounts are not configured', 'cpuPercent': None, 'memoryPercent': None, 'diskPercent': None}
        ticks = list(map(int, (proc / 'stat').read_text().splitlines()[0].split()[1:9]))
        memory = {k: int(v.strip().split()[0]) * 1024 for k, v in (line.split(':', 1) for line in (proc / 'meminfo').read_text().splitlines())}
        rx, tx = None, None
        # /proc/net is scoped to this process's network namespace, unlike CPU and
        # memory. Never label the agent's interface counters as host traffic.
        net_source = proc / '1/net/dev'
        if net_source.is_file():
            interfaces = [(line.split(':')[0].strip(), list(map(int, line.split(':')[1].split()))) for line in net_source.read_text().splitlines()[2:]]
            rx, tx = sum(v[0] for n, v in interfaces if n != 'lo'), sum(v[8] for n, v in interfaces if n != 'lo')
        fs = os.statvfs(disk)
        total, available = fs.f_blocks * fs.f_frsize, fs.f_bavail * fs.f_frsize
        result.update(memoryTotalBytes=memory['MemTotal'], memoryUsedBytes=memory['MemTotal'] - memory['MemAvailable'], memoryPercent=(1 - memory['MemAvailable'] / memory['MemTotal']) * 100,
                      diskTotalBytes=total, diskAvailableBytes=available, diskPercent=(1 - available / total) * 100, diskMount=str(disk), cpuPercent=None, networkRxPerSecond=None, networkTxPerSecond=None)
        if self.previous:
            before, old, old_rx, old_tx = self.previous
            delta = sum(ticks) - sum(old)
            result['cpuPercent'] = max(0, min(100, 100 * (1 - ((ticks[3] + ticks[4]) - (old[3] + old[4])) / delta))) if delta > 0 else None
            if rx is not None and old_rx is not None:
                result['networkRxPerSecond'] = max(0, rx - old_rx) / (now - before)
                result['networkTxPerSecond'] = max(0, tx - old_tx) / (now - before)
        self.previous = now, ticks, rx, tx
        return result

    def collect(self):
        while not self.stopping.is_set():
            try:
                self.sample('host', self.host_metrics())
            except Exception:
                try:self.sample('host', {'scope': 'host', 'available': False, 'reason': 'Host collection failed'})
                except Exception:pass
            self.stopping.wait(10)

    def collect_containers(self):
        while not self.stopping.is_set():
            try:
                running = [c for c in self.docker('GET', '/containers/json?all=1') if c['State'] == 'running']
                for item in running[:128]:
                    if self.stopping.is_set():break
                    try:
                        ident = item['Id']
                        value = container_metrics(self.docker('GET', '/containers/' + ident + '/stats?stream=false&one-shot=true'))
                        info = self.inspect_container(ident)
                        value['restartCount'] = info.get('RestartCount')
                        value['health'] = info.get('State', {}).get('Health', {}).get('Status')
                        self.sample(ident, value)
                    except Exception:pass
            except Exception:pass
            self.stopping.wait(30)

    def get(self, path):
        if path == '/manager/policy':
            return self.policy()
        if path == '/manager/jobs':
            return self.jobs()
        if re.fullmatch('/manager/jobs/[a-f0-9]{32}', path):
            return self.job(path.rsplit('/', 1)[1])
        if path == '/manager/history':
            return self.history()
        if path == '/manager/host':
            history = self.history()
            return history[-1] if history else {'scope': 'host', 'available': False, 'reason': 'Waiting for first sample'}
        if path == '/manager/networks':
            return self.docker('GET', '/networks')
        if path == '/manager/volumes':
            return self.docker('GET', '/volumes')
        if path == '/manager/events':
            now = int(time.time())
            raw = self.docker('GET', '/events?' + urlencode({'since': now - 300, 'until': now}))
            if isinstance(raw, dict) and 'text' in raw:
                return [redact(json.loads(line)) for line in raw['text'].splitlines() if line.strip()][-200:]
            return [redact(raw)] if raw else []
        if re.fullmatch('/manager/logs/[a-f0-9]{64}', path):
            ident = path.rsplit('/', 1)[1]
            info = self.inspect_container(ident)
            result = self.docker('GET', '/containers/' + ident + '/logs?stdout=1&stderr=1&timestamps=1&tail=200')
            text = result.get('text', '')[-131072:] if isinstance(result, dict) else json.dumps(result)[-131072:]
            for entry in info.get('Config', {}).get('Env') or []:
                key, _, value = entry.partition('=')
                if value and re.search('password|token|secret|credential|key', key, re.I):
                    text = text.replace(value, '[REDACTED]')
            return {'text': text, 'collectedAt': time.time(), 'tail': 200}
        if re.fullmatch('/manager/containers/[a-f0-9]{64}/(metrics|history)', path):
            ident, action = path.split('/')[-2:]
            if action == 'history':
                return self.history(ident)
            value = container_metrics(self.docker('GET', '/containers/' + ident + '/stats?stream=false&one-shot=true'))
            self.sample(ident, value)
            return value
        raise ValueError('Unsupported read operation')
