"""Create one registered test-only Docker daemon without changing shared Docker.

Linux/root only. Never restarts or cleans an existing daemon. The image is copied
through a pipe from the existing daemon; no registry or shared configuration is
changed. Final cleanup remains subject to the user's acceptance and push gates.
"""
from pathlib import Path
import argparse, hashlib, json, os, re, shutil, subprocess, time

p = argparse.ArgumentParser()
p.add_argument('--root', type=Path, required=True)
p.add_argument('--image', required=True)
p.add_argument('--attempt', type=int, default=1)
a = p.parse_args()
os.umask(0o077)
if os.geteuid() != 0 or not re.fullmatch(r'sha256:[0-9a-f]{64}', a.image) or not 1 <= a.attempt <= 10:
    raise RuntimeError('Root and an immutable local image ID are required')
root = a.root.resolve(strict=True)
if root != a.root or len(root.parts) < 3:
    raise RuntimeError('Explicit canonical test root required')
anchor = root / ('strict-daemon-' + str(a.attempt))
if anchor.exists():
    raise RuntimeError('Registered or uncertain daemon already exists; reconcile without restarting')
source = json.loads(subprocess.check_output(['docker', 'image', 'inspect', a.image]))[0]
shared = json.loads(subprocess.check_output(['docker', 'info', '--format', '{{json .}}']))
def shared_bridge():
    observed = json.loads(subprocess.check_output(['ip', '-j', 'address', 'show', 'dev', 'docker0']))
    if len(observed) != 1: raise RuntimeError('Existing shared docker0 baseline required')
    entry = observed[0]
    return {key: entry.get(key) for key in ['ifindex', 'ifname', 'address', 'mtu', 'addr_info']}

bridge_before = shared_bridge()
if source['Id'] != a.image or not all(shared.get(k) for k in ['MemoryLimit', 'CpuCfsQuota', 'PidsLimit']):
    raise RuntimeError('Pinned image and real host cgroup resource support required')
before = shutil.disk_usage(root)
if before.free < max(source['Size'] * 2, 8 * 1024**3):
    raise RuntimeError('Insufficient independent image-store disk headroom')
anchor.mkdir(mode=0o700)
for directory in ['run', 'data', 'exec', 'containerd-data', 'containerd-state']:
    (anchor / directory).mkdir(mode=0o700)
sock = anchor / 'run/docker.sock'
config = {
    'hosts': ['unix://' + str(sock)], 'data-root': str(anchor / 'data'),
    'exec-root': str(anchor / 'exec'), 'pidfile': str(anchor / 'run/dockerd.pid'),
    'storage-driver': shared['Driver'], 'exec-opts': ['native.cgroupdriver=cgroupfs'],
    'bridge': 'none', 'iptables': False, 'ip6tables': False, 'ip-forward': False,
    'ip-masq': False, 'userland-proxy': False, 'live-restore': False,
    'containerd': str(anchor / 'run/containerd.sock'),
    'containerd-namespace': 'coding-node-' + str(a.attempt),
    'containerd-plugins-namespace': 'coding-node-plugins-' + str(a.attempt),
}
config_file = anchor / 'daemon.json'
config_file.write_text(json.dumps(config, indent=2))
evidence = root / ('evidence/strict-daemon-' + str(a.attempt) + '.json')
containerd_config = anchor / 'containerd.toml'
containerd_config.write_text('version = 2\nroot = ' + json.dumps(str(anchor / 'containerd-data'))
    + '\nstate = ' + json.dumps(str(anchor / 'containerd-state'))
    + '\ndisabled_plugins = ["io.containerd.grpc.v1.cri"]\n[grpc]\naddress = '
    + json.dumps(str(anchor / 'run/containerd.sock')) + '\n')
record = {'schemaVersion': 'owned_acceptance_daemon.v1', 'anchor': str(anchor),
          'sharedDaemonId': shared['ID'], 'sharedDockerRootDir': shared['DockerRootDir'],
          'imageId': a.image, 'diskFreeBefore': before.free,
          'configSha256': hashlib.sha256(config_file.read_bytes()).hexdigest(),
          'containerdConfigSha256': hashlib.sha256(containerd_config.read_bytes()).hexdigest(),
          'containerdSocket': str(anchor / 'run/containerd.sock'),
          'sharedBridgeBefore': bridge_before,
          'state': 'START_INTENT', 'finalCleanupAuthorized': False}

def save():
    temporary = evidence.with_suffix('.tmp')
    with temporary.open('w') as out:
        out.write(json.dumps(record, indent=2)); out.flush(); os.fsync(out.fileno())
    temporary.replace(evidence)

save()
try:
    with (anchor / 'containerd.log').open('xb') as log:
        containerd = subprocess.Popen(['unshare', '--net', 'containerd', '--config', str(containerd_config)],
                                     stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    record.update(containerdPid=containerd.pid,
        containerdProcessStartTicks=Path('/proc/' + str(containerd.pid) + '/stat').read_text().split()[21])
    save()
    for step in range(30):
        if containerd.poll() is not None: raise RuntimeError('Owned containerd failed; preserve its log')
        if (anchor / 'run/containerd.sock').exists(): break
        time.sleep(1)
    else: raise RuntimeError('Owned containerd startup deadline exceeded')
    containerd_net = os.stat('/proc/' + str(containerd.pid) + '/ns/net').st_ino
    host_net = os.stat('/proc/1/ns/net').st_ino
    if containerd_net == host_net: raise RuntimeError('Test containerd must not share host network namespace')
    record.update(containerdNetworkNamespaceInode=containerd_net, hostNetworkNamespaceInode=host_net)
    save()
    with (anchor / 'dockerd.log').open('xb') as log:
        # bridge=none alone can delete host docker0 during initialization. The
        # daemon itself MUST first enter a new network namespace, before exec.
        process = subprocess.Popen(['unshare', '--net', 'dockerd', '--config-file', str(config_file)],
                                   stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    record.update(pid=process.pid, processStartTicks=Path('/proc/' + str(process.pid) + '/stat').read_text().split()[21], state='STARTING')
    save()
    command = ['docker', '--host', 'unix://' + str(sock)]
    for step in range(120):
        if process.poll() is not None:
            raise RuntimeError('Owned daemon exited; preserve the startup log')
        ready = subprocess.run(command + ['info', '--format', '{{json .}}'], capture_output=True, text=True, timeout=5)
        if ready.returncode == 0:
            info = json.loads(ready.stdout)
            # Docker may answer /info while initialization is still incomplete.
            if info.get('ID') and info.get('DockerRootDir'):
                break
        if step % 10 == 0: print('DEDICATED_DAEMON_STARTING', step, flush=True)
        time.sleep(1)
    else: raise RuntimeError('Owned daemon startup deadline exceeded')
    if info['ID'] == shared['ID'] or info['DockerRootDir'] != str(anchor / 'data'):
        raise RuntimeError('Wrong daemon; never run image load or task control')
    if not all(info.get(k) for k in ['MemoryLimit', 'CpuCfsQuota', 'PidsLimit']):
        raise RuntimeError('Dedicated daemon does not enforce resource limits')
    daemon_net = os.stat('/proc/' + str(process.pid) + '/ns/net').st_ino
    if daemon_net == host_net: raise RuntimeError('Test dockerd must not share host network namespace')
    if shared_bridge() != bridge_before: raise RuntimeError('Shared host bridge changed; no test dispatch')
    record.update(daemonNetworkNamespaceInode=daemon_net)
    for path in [anchor, anchor / 'run', anchor / 'data', sock]: path.chmod(0o600 if path == sock else 0o700)
    record.update(state='READY_IMAGE_COPY_PENDING', daemonId=info['ID'], socketPath=str(sock),
                  cgroupVersion=info.get('CgroupVersion'), resourceSupport=True)
    save(); print('DEDICATED_DAEMON_READY', info['ID'], flush=True)
    with (anchor / 'image-copy.log').open('xb') as log:
        export = subprocess.Popen(['docker', 'image', 'save', a.image], stdout=subprocess.PIPE, stderr=log)
        imported = subprocess.Popen(command + ['image', 'load'], stdin=export.stdout, stdout=log, stderr=subprocess.STDOUT)
        export.stdout.close()
        try:
            import_code = imported.wait(timeout=600); export_code = export.wait(timeout=30)
        except BaseException:
            # Only the two image-copy child processes; never touch a Docker service.
            for child in [imported, export]:
                if child.poll() is None: child.terminate()
            raise
    if import_code or export_code: raise RuntimeError('Image copy failed; daemon and logs retained')
    actual = json.loads(subprocess.check_output(command + ['image', 'inspect', a.image]))[0]
    if actual['Id'] != a.image: raise RuntimeError('Copied image ID differs')
    if json.loads(subprocess.check_output(['docker', 'info', '--format', '{{json .}}']))['ID'] != shared['ID']:
        raise RuntimeError('Shared daemon identity changed externally')
    if shared_bridge() != bridge_before: raise RuntimeError('Shared host bridge changed during image copy')
    record.update(state='READY', importedImageId=actual['Id'], diskFreeAfter=shutil.disk_usage(root).free)
    save(); print('DEDICATED_DAEMON_IMAGE_VERIFIED', actual['Id'], flush=True)
except BaseException as error:
    record.update(state='RECONCILIATION_REQUIRED', error=str(error)); save()
    raise
