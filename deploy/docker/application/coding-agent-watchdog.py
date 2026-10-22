"""Recover only an unhealthy Compose coding-agent; Docker owns process-exit recovery."""
import argparse
import datetime
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time


def decision(container, state, now, project, service, cooldown):
    labels = container.get('Config', {}).get('Labels') or {}
    if labels.get('com.docker.compose.project') != project or labels.get('com.docker.compose.service') != service:
        return 'SCOPE_MISMATCH'
    # A stopped container may represent an operator's deliberate maintenance. Never start it here.
    runtime = container.get('State', {})
    if not runtime.get('Running') or runtime.get('Paused') or runtime.get('Restarting'):
        return 'NOOP'
    if runtime.get('Health', {}).get('Status') != 'unhealthy':
        return 'NOOP'
    policy = container.get('HostConfig', {}).get('RestartPolicy', {}).get('Name')
    if policy not in ('unless-stopped', 'always'):
        return 'RESTART_POLICY_REQUIRED'
    if state.get('containerId') == container.get('Id') and now - state.get('lastAttemptAt', 0) < cooldown:
        return 'COOLDOWN'
    return 'RESTART'


def event(action, container=None, **facts):
    print(json.dumps({'timestamp': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                      'component': 'CODING_AGENT_WATCHDOG', 'action': action,
                      'containerId': (container or {}).get('Id'), **facts}), flush=True)


def command(*args):
    return subprocess.run(['docker', *args], capture_output=True, text=True, timeout=25, check=True).stdout


def persist(path, state):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.is_symlink():
        raise ValueError('Watchdog state must not be a symlink')
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent, delete=False) as output:
            temporary = Path(output.name)
            json.dump(state, output)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--container', default='runtime-os-app-stage-coding-agent-1')
    parser.add_argument('--project', default='runtime-os-app-stage')
    parser.add_argument('--service', default='coding-agent')
    parser.add_argument('--state-file', type=Path, default=Path('/var/lib/runtime-os-coding-agent-watchdog/state.json'))
    parser.add_argument('--cooldown', type=int, default=300)
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    if args.cooldown < 60:
        parser.error('Cooldown must be at least 60 seconds')
    os.umask(0o077)
    try:
        if args.state_file.is_symlink():
            raise ValueError('Invalid watchdog state')
        state = json.loads(args.state_file.read_text()) if args.state_file.exists() else {}
        container = json.loads(command('inspect', args.container))[0]
        now = time.time()
        action = decision(container, state, now, args.project, args.service, args.cooldown)
        if action == 'NOOP':
            return
        if action != 'RESTART' or args.dry_run:
            event(action, container, dryRun=args.dry_run)
            return
        # Recheck identity and health immediately before mutation; never act on an inspected replacement.
        current = json.loads(command('inspect', args.container))[0]
        if current['Id'] != container['Id'] or decision(current, state, time.time(), args.project, args.service, args.cooldown) != 'RESTART':
            event('STATE_CHANGED', current)
            return
        persist(args.state_file, {'containerId': container['Id'], 'lastAttemptAt': now,
                                 'attempts': int(state.get('attempts', 0)) + 1})
        event('RESTART_REQUESTED', current, reason='UNHEALTHY', cooldownSeconds=args.cooldown)
        command('restart', '-t', '10', container['Id'])
        event('RESTART_COMMAND_COMPLETED', current)
    except Exception as failure:
        # Inspect responses contain environment secrets. Log only the failure type, never command output.
        event('CHECK_FAILED', errorType=type(failure).__name__)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
