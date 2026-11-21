"""Run and supervise the private Docker Engine inside the Compose service."""

import fcntl
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time


def start_process(command, identity_file):
    return subprocess.Popen([sys.executable, __file__, 'exec', str(identity_file), *command])


def identity(pid_file):
    # The executor validates these identities in the host PID namespace.
    pid = int(pid_file.read_text())
    proc_stat = (Path('/host/proc') / str(pid) / 'stat').read_text()
    return {'pid': pid, 'startTicks': proc_stat.rsplit(')', 1)[1].split()[19]}


def wait_ready(service, command, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline and service.poll() is None:
        try:
            result = subprocess.run(command, stdout=subprocess.DEVNULL,
                                    stderr=subprocess.DEVNULL, timeout=2)
            if result.returncode == 0:
                return
        except subprocess.TimeoutExpired:
            pass
        time.sleep(0.5)
    raise RuntimeError(f'{command[0]} failed its readiness check')


def health(root):
    deadline = root / 'run/executor-processes.json'
    if not deadline.is_file():
        return 1
    return subprocess.call(['docker', '--host', f'unix://{root}/run/docker.sock', 'info',
                            '--format', '{{.ID}}'], stdout=subprocess.DEVNULL)


def supervise(root):
    os.umask(0o077)
    run = root / 'run'
    for path in (root, run):
        info = path.stat()
        if not path.is_dir() or path.resolve() != path or info.st_uid != 0 or info.st_mode & 0o077:
            raise RuntimeError(f'{path} must be a private root-owned directory')
    with (run / 'compose-daemon.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        bindings = run / 'executor-processes.json'
        bindings.unlink(missing_ok=True)
        processes = []
        stopping = False

        def request_stop(*_):
            nonlocal stopping
            stopping = True

        signal.signal(signal.SIGTERM, request_stop)
        signal.signal(signal.SIGINT, request_stop)
        try:
            containerd_pid = run / 'compose-containerd.pid'
            containerd = start_process(['containerd', '--config', str(root / 'containerd.toml')], containerd_pid)
            processes.append(containerd)
            wait_ready(containerd, ['ctr', '--address', str(run / 'containerd.sock'), 'version'])
            docker_pid = run / 'compose-dockerd.pid'
            docker = start_process(['dockerd', '--config-file', str(root / 'daemon.json')], docker_pid)
            processes.append(docker)
            socket = run / 'docker.sock'
            wait_ready(docker, ['docker', '--host', f'unix://{socket}', 'version', '--format', '{{.Server.Version}}'])
            (root / 'data').chmod(0o700)
            socket.chmod(0o600)
            with tempfile.NamedTemporaryFile(mode='w', dir=run, prefix='.executor-processes-', delete=False) as pending:
                json.dump({'daemon': identity(docker_pid), 'containerd': identity(containerd_pid)}, pending)
            Path(pending.name).replace(bindings)
            while not stopping and all(process.poll() is None for process in processes):
                time.sleep(0.5)
            return 0 if stopping else 1
        finally:
            bindings.unlink(missing_ok=True)
            for process in reversed(processes):
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=25)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == 'exec':
        Path(sys.argv[2]).write_text(os.readlink('/host/proc/self'))
        os.execvp(sys.argv[3], sys.argv[3:])
    root = Path(os.environ['RUNTIME_OS_NATIVE_DAEMON_ROOT'])
    sys.exit(health(root) if len(sys.argv) > 1 and sys.argv[1] == 'health' else supervise(root))
