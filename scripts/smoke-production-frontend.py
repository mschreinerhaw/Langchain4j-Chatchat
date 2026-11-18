"""Validate the production hub and Chromium worker with existing synthetic acceptance cases."""
import json
import pathlib
import secrets
import subprocess
import time
import urllib.request


def docker(*args, timeout=600):
    return subprocess.check_output(['docker', *args], encoding='utf-8', errors='replace',
                                   stderr=subprocess.STDOUT, timeout=timeout).strip()


def main():
    out = pathlib.Path(__file__).resolve().parents[2] / 'target/production-frontend-smoke'
    out.mkdir(parents=True, exist_ok=True)
    suffix = secrets.token_hex(4)
    network, hub, worker = ['production-hub-' + part + suffix for part in ('net-', 'hub-', 'worker-')]
    token = secrets.token_urlsafe(32)
    created = []
    result = {'scope': 'synthetic browser acceptance, no model or production data', 'passed': False}
    try:
        docker('network', 'create', network)
        for name, alias, command in [(worker, 'frontend-preview-worker', 'worker.mjs'),
                                     (hub, 'frontend-resource-hub', 'server.mjs')]:
            args = ['run', '-d', '--name', name, '--network', network, '--network-alias', alias,
                    '--init', '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges',
                    '--memory=2g', '--cpus=2', '--shm-size=256m',
                    '--tmpfs', '/tmp:size=256m', '--tmpfs', '/app/jobs:uid=10001,gid=10001,size=768m',
                    '-e', 'HUB_TOKEN=' + token, '-e', 'HUB_ORIGIN=http://frontend-resource-hub:8080',
                    '-e', 'WORKER_URL=http://frontend-preview-worker:8081',
                    '-e', 'PUBLIC_URL=http://frontend-resource-hub:8080']
            if name == worker:
                profile = pathlib.Path(__file__).resolve().parents[1] / 'frontend-resource-hub/seccomp_profile.json'
                args += ['--security-opt', 'seccomp=' + str(profile)]
            if name == hub:
                args += ['-p', '127.0.0.1::8080']
            docker(*args, 'frontend-resource-hub:production-local', 'node', command)
            created.append(name)
        address = 'http://' + docker('port', hub, '8080/tcp').splitlines()[0] + '/api/v1/health'
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        for _ in range(60):
            try:
                with opener.open(address, timeout=3) as response:
                    if response.status == 200:
                        break
            except OSError:
                pass
            time.sleep(1)
        else:
            raise RuntimeError('Hub HTTP startup timeout')
        output = docker('exec', '-e', 'EVIDENCE_PATH=/tmp/frontend-hub-acceptance.json', hub, 'node', 'acceptance.mjs')
        out.joinpath('acceptance-output.log').write_text(output, encoding='utf-8')
        out.joinpath('acceptance.json').write_text(docker('exec', hub, 'cat', '/tmp/frontend-hub-acceptance.json'), encoding='utf-8')
        result.update(passed=True, http_status=200, user=docker('exec', hub, 'id', '-u'))
    except Exception as exc:
        result['error'] = str(exc)
        if isinstance(exc, subprocess.CalledProcessError):
            out.joinpath('failure.log').write_text(exc.output, encoding='utf-8')
    finally:
        for name in created:
            try:
                out.joinpath(name + '.log').write_text(docker('logs', name), encoding='utf-8')
            finally:
                docker('rm', '-f', name)
        docker('network', 'rm', network)
    out.joinpath('results.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result), flush=True)
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
