"""Local image startup checks. H2 and disabled integrations are NOT production acceptance."""
import argparse
import json
import pathlib
import re
import secrets
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


def docker(*args):
    return subprocess.check_output(['docker', *args], text=True, encoding='utf-8', errors='replace', stderr=subprocess.STDOUT).strip()


def smoke(service, output, timeout):
    module = {'api': 'chatchat-api', 'mcp': 'chatchat-mcp-server',
              'news': 'chatchat-runtime-news', 'cline': 'cline-agent-server'}[service]
    port = {'api': 8080, 'mcp': 8090, 'news': 8091, 'cline': 18326}[service]
    name = 'production-smoke-' + service + '-' + secrets.token_hex(4)
    token = secrets.token_hex(32)
    bootstrap_password = secrets.token_urlsafe(24)
    args = ['run', '-d', '--name', name, '--init', '--cpus=2', '--memory=3g',
            '--cap-drop=ALL', '--security-opt=no-new-privileges',
            '-p', f'127.0.0.1::{port}']
    path = '/'
    headers = {}
    started = False
    result = {'service': service, 'scope': 'isolated startup, not production integration', 'passed': False}
    with tempfile.TemporaryDirectory(prefix='cline-container-smoke-') as tmp:
        if service == 'cline':
            config = pathlib.Path(tmp, 'config.json')
            config.write_text(json.dumps({
                'sourceRoot': '/opt/cline/source', 'distDir': '/opt/cline/runtime',
                'stateDir': '/srv/app/state', 'apiToken': token, 'model': 'startup-check',
                'baseUrl': 'http://127.0.0.1:1/v1', 'apiKey': 'unused-startup-check',
                'workspaces': {'default': '/workspace'}, 'host': '0.0.0.0', 'port': port,
            }), encoding='utf-8')
            args += ['--mount', f'type=bind,src={config.resolve()},dst=/run/secrets/cline-server.json,readonly']
            path, headers = '/health/ready', {'Authorization': 'Bearer ' + token}
        else:
            env = {'SPRING_PROFILES_ACTIVE': 'h2' if service == 'news' else 'prod',
                   'SPRING_APPLICATION_JSON': json.dumps({'chatchat': {'chat': {'detail-store': {'external-text': {'enabled': False}}}, 'enterprise': {'bootstrap': {'admin-password': bootstrap_password}}}}),
                   'CHATCHAT_DATASOURCE_CONFIG': 'datasource-h2.yml',
                   'SPRING_JPA_HIBERNATE_DDL_AUTO': 'update',
                   'CHATCHAT_INTERNAL_CREDENTIAL_ENABLED': 'false',
                   'CHATCHAT_MCP_GRPC_SERVER_ENABLED': 'false',
                   'CHATCHAT_RUNTIME_NEWS_TEMPORAL_ENABLED': 'false',
                   'CHATCHAT_RUNTIME_NEWS_OPEN_SEARCH_ENABLED': 'false',
                   'CHATCHAT_RUNTIME_NEWS_ANALYSIS_ENABLED': 'false',
                   'CHATCHAT_SEARCH_OPENSEARCH_ENABLED': 'false',
                   'CHATCHAT_CHAT_DETAILSTORE_EXTERNALTEXT_ENABLED': 'false'}
            for key, value in env.items():
                args += ['-e', key + '=' + value]
            if service == 'api':
                schema = pathlib.Path(__file__).resolve().parents[1] / 'database/init/postgresql/chatchat-api.sql'
                seed = re.search(r"insert into agent_runtime_semantic_policy\s*\(policy_key, policy_json\)\s*select\s*'default',\s*'((?:''|[^'])*)'",
                                 schema.read_text(encoding='utf-8'), re.IGNORECASE)
                if not seed:
                    raise RuntimeError('Official runtime semantic policy seed missing')
                json.loads(seed.group(1).replace("''", "'"))
                policy = pathlib.Path(tmp, 'policy.sql')
                policy.write_text("INSERT INTO agent_runtime_semantic_policy (policy_key, policy_json) VALUES ('default', '"
                                  + seed.group(1) + "');\n", encoding='utf-8')
                args += ['--mount', f'type=bind,src={policy.resolve()},dst=/run/smoke/policy.sql,readonly',
                         '-e', 'SPRING_SQL_INIT_MODE=always',
                         '-e', 'SPRING_SQL_INIT_DATA_LOCATIONS=file:/run/smoke/policy.sql',
                         '-e', 'SPRING_JPA_DEFER_DATASOURCE_INITIALIZATION=true']
            if service == 'mcp':
                path = '/admin/'
            if service == 'news':
                path = '/internal/v1/news/health'
        try:
            user = docker('image', 'inspect', module + ':production-local', '--format', '{{.Config.User}}')
            if user != '10001:10001':
                raise RuntimeError('Unexpected image user: ' + user)
            docker(*args, module + ':production-local', *(['--chatchat.chat.detail-store.external-text.enabled=false'] if service == 'api' else []))
            started = True
            mapping = docker('port', name, f'{port}/tcp')
            address = 'http://' + mapping.splitlines()[0] + path
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                if docker('inspect', name, '--format', '{{.State.Running}}') != 'true':
                    raise RuntimeError('Container exited before readiness')
                try:
                    req = urllib.request.Request(address, headers=headers)
                    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=3) as response:
                        if response.status == 200:
                            body = response.read().decode('utf-8')
                            if service == 'news' and 'UP' not in body:
                                raise RuntimeError('News health did not report UP')
                            if service in ('api', 'mcp') and '<html' not in body.lower():
                                raise RuntimeError('Frontend HTML missing')
                            break
                except (urllib.error.URLError, TimeoutError, ConnectionError):
                    pass
                time.sleep(2)
            else:
                raise RuntimeError('HTTP startup timeout')
            # ApplicationReadyEvent listeners run after the HTTP port opens.
            # Verify continued availability so a late bootstrap failure cannot pass.
            for _ in range(10):
                time.sleep(1)
                if docker('inspect', name, '--format', '{{.State.Running}}') != 'true':
                    raise RuntimeError('Container exited during startup stabilization')
            docker('exec', name, 'node' if service == 'cline' else 'java', '--version' if service == 'cline' else '-version')
            if service == 'api':
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                login = urllib.request.Request(address.rstrip('/') + '/api/v1/enterprise/auth/login',
                    data=json.dumps({'username': 'admin', 'password': bootstrap_password}).encode(),
                    headers={'Content-Type': 'application/json'})
                with opener.open(login, timeout=10) as response:
                    auth = json.load(response)
                auth_token = auth.get('data', {}).get('token')
                if not auth_token:
                    raise RuntimeError('Administrator login did not return a token')
                request = urllib.request.Request(address.rstrip('/') + '/api/v1/enterprise/auth/me',
                    headers={'Authorization': 'Bearer ' + auth_token})
                with opener.open(request, timeout=10) as response:
                    if response.status != 200:
                        raise RuntimeError('Authenticated profile request failed')
                result['administrator_login'] = 'passed'
                docker('exec', name, 'python3', '-c', "import sqlglot; assert sqlglot.__version__ == '30.23.0'")
            if service == 'mcp':
                docker('exec', name, 'docker', '--version')
            result.update(passed=True, http_status=200, user=user)
        except Exception as exc:
            result['error'] = str(exc)
        finally:
            if started:
                output.joinpath(service + '.log').write_text(docker('logs', name), encoding='utf-8')
                docker('rm', '-f', name)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--service', choices=['api', 'mcp', 'news', 'cline', 'all'], default='all')
    parser.add_argument('--timeout', type=int, default=240)
    parser.add_argument('--output', default='../target/production-container-smoke')
    opts = parser.parse_args()
    out = pathlib.Path(opts.output).resolve()
    out.mkdir(parents=True, exist_ok=True)
    results = []
    for name in (['news', 'mcp', 'api', 'cline'] if opts.service == 'all' else [opts.service]):
        result = smoke(name, out, opts.timeout)
        results.append(result)
        print(json.dumps(result), flush=True)
    out.joinpath('results.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
    raise SystemExit(0 if all(r['passed'] for r in results) else 1)
