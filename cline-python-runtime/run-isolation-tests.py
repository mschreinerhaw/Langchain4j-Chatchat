"""Run real official Cline tasks in the combined image, without Java or MCP.

The driver only stages the original file, observes unchanged model traffic and
archives results. It supplies no analysis code and never retries a failed task.
Run on the authorized remote host after build and private settings preparation.
"""
from pathlib import Path
import datetime
import hashlib
import http.client
import http.server
import json
import os
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.parse

ROOT = Path('/srv/cline-python-runtime-20261110')
TIMEOUT = 1200
PROXY_PORT = 18520
PROMPT = '请分析工作目录中的 test.parquet 文件。你可以自主读取文件、探索字段、编写 Python 程序并运行。请分析数据规模、字段类型、缺失值、重复值、数值分布和潜在异常，最终生成 analysis.py 和 report.md。报告必须基于真实计算结果，不允许编造数据。'
settings = json.loads((ROOT / 'private/model.json').read_text())
manifest = json.loads((ROOT / 'evidence/image-manifest.json').read_text())
active, counts, lock = {}, {}, threading.Lock()


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2))


def docker(*args, timeout=90):
    result = subprocess.run(['docker', *args], capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr[-1500:])
    return result.stdout.strip()


class AuditProxy(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *args):
        pass

    def do_GET(self):
        self.forward()

    def do_POST(self):
        self.forward()

    def forward(self):
        parts = self.path.split('/')
        case = parts[1] if len(parts) > 1 else ''
        if case not in active or self.headers.get('Authorization') != 'Bearer ' + settings['proxyToken']:
            self.send_error(403)
            return
        body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        model_request = self.command == 'POST' and any(p in self.path for p in ['chat/completions', '/responses'])
        with lock:
            if model_request:
                counts[case] = counts.get(case, 0) + 1
            ordinal = counts.get(case, 0)
        folder = ROOT / 'runs' / case / 'model'
        folder.mkdir(parents=True, exist_ok=True)
        ident = f'{ordinal:04d}' if model_request else 'discovery-' + str(time.time_ns())
        receipt = {'case': case, 'ordinal': ordinal, 'startedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                   'requestSha256': hashlib.sha256(body).hexdigest(), 'forwardedUnchanged': True}
        if model_request:
            request = json.loads(body)
            receipt['requestParameters'] = {k: v for k, v in request.items() if k not in ['messages', 'tools']}
            receipt['toolNames'] = [t.get('function', {}).get('name') for t in request.get('tools', [])]
            (folder / (ident + '-request.json')).write_bytes(body)
            if request.get('model') != settings['model']:
                receipt['error'] = 'FIXED_MODEL_MISMATCH'
                write(folder / (ident + '-receipt.json'), receipt)
                self.send_error(400, 'Fixed model mismatch')
                return
        upstream = urllib.parse.urlparse(settings['baseUrl'])
        suffix = '/'.join(parts[2:]).removeprefix('v1/')
        path = upstream.path.rstrip('/') + '/' + suffix
        connection = (http.client.HTTPSConnection(upstream.hostname, upstream.port or 443, timeout=180)
                      if upstream.scheme == 'https' else http.client.HTTPConnection(upstream.hostname, upstream.port or 80, timeout=180))
        if settings.get('providerIp'):
            connection._create_connection = lambda address, timeout=None, source_address=None: socket.create_connection((settings['providerIp'], address[1]), timeout, source_address)
        response_body = bytearray()
        started = time.monotonic()
        try:
            connection.request(self.command, path, body=body or None,
                               headers={'Authorization': 'Bearer ' + settings['apiKey'], 'Content-Type': 'application/json', 'Accept': self.headers.get('Accept', '*/*')})
            response = connection.getresponse()
            receipt['httpStatus'] = response.status
            self.send_response(response.status)
            self.send_header('Content-Type', response.getheader('Content-Type', 'application/json'))
            self.send_header('Connection', 'close')
            self.end_headers()
            self.close_connection = True
            while True:
                block = response.read1(8192)
                if not block:
                    break
                response_body.extend(block)
                try:
                    self.wfile.write(block)
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    receipt['clientDisconnected'] = True
                    break
            chunks = []
            for line in response_body.decode('utf-8', 'replace').splitlines():
                if line.startswith('data: '):
                    try:
                        chunks.append(json.loads(line[6:]))
                    except json.JSONDecodeError:
                        pass
            if not chunks:
                try:
                    chunks = [json.loads(response_body)]
                except json.JSONDecodeError:
                    pass
            usages = [p['usage'] for p in chunks if p.get('usage')]
            receipt['usage'] = usages[-1] if usages else None
            receipt['providerModels'] = sorted({p['model'] for p in chunks if p.get('model')})
        except Exception as error:
            receipt['error'] = repr(error)
            try:
                self.send_error(502, 'Provider transport failure')
            except (BrokenPipeError, ConnectionResetError):
                pass
        finally:
            connection.close()
            receipt['durationMs'] = round((time.monotonic() - started) * 1000)
            receipt['responseSha256'] = hashlib.sha256(response_body).hexdigest()
            (folder / (ident + '-response.bin')).write_bytes(response_body)
            write(folder / (ident + '-receipt.json'), receipt)


def run_case(case):
    folder = ROOT / 'runs' / case
    if folder.exists():
        raise RuntimeError('Refusing to redispatch existing case: ' + case)
    workspace, state = folder / 'workspace', folder / 'state'
    workspace.mkdir(parents=True)
    state.mkdir()
    uid = manifest['environment']['uid']
    gid = manifest['environment']['gid']
    for path in [workspace, state]:
        os.chown(path, uid, gid)
    original = ROOT / 'data/test.parquet'
    shutil.copyfile(original, workspace / 'test.parquet')
    os.chown(workspace / 'test.parquet', uid, gid)
    profile = {'provider': 'openai', 'apiKey': settings['proxyToken'],
               'baseUrl': f'http://cline-model-audit:{PROXY_PORT}/{case}/v1', 'model': settings['model']}
    credentials = ROOT / 'private' / (case + '-connection.json')
    write(credentials, profile)
    os.chown(credentials, uid, gid)
    credentials.chmod(0o600)
    name = 'cline-python-' + case
    write(folder / 'intent.json', {'case': case, 'prompt': PROMPT, 'imageId': manifest['imageId'],
          'clineVersion': manifest['officialCliVersion'], 'model': settings['model'], 'thinking': 'low',
          'modelRequestLimit': 'none in driver', 'toolCallLimit': 'none in driver', 'timeoutSeconds': TIMEOUT,
          'nativeConsecutiveMistakeGuard': 'official CLI default; not patched',
          'inputSha256': hashlib.sha256(original.read_bytes()).hexdigest(),
          'inputDelivery': 'unmodified writable original-file copy; no Java/schema adapters',
          'startedAt': datetime.datetime.now(datetime.timezone.utc).isoformat()})
    active[case] = time.monotonic()
    docker('run', '-d', '--name', name, '--init', '--network', 'cline-python-direct',
           '--add-host', 'cline-model-audit:host-gateway', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
           '--memory', '3g', '--cpus', '2', '--pids-limit', '256',
           '-e', 'CLINE_CONNECTION_FILE=/run/secrets/connection.json',
           '-v', str(workspace) + ':/srv/workspace', '-v', str(state) + ':/srv/state',
           '-v', str(credentials) + ':/run/secrets/connection.json:ro', manifest['imageId'],
           'cline', '--json', '--auto-approve', 'true', '--provider', 'openai', '--model', settings['model'],
           '--thinking', 'low', '--timeout', str(TIMEOUT), PROMPT)
    code, terminal = None, 'HARNESS_FAILURE'
    stdout = (folder / 'cline.stdout.jsonl').open('w')
    stderr = (folder / 'cline.stderr.log').open('w')
    process = None
    try:
        process = subprocess.Popen(['docker', 'logs', '--follow', name], stdout=stdout, stderr=stderr)
        try:
            waited = subprocess.run(['docker', 'wait', name], capture_output=True, text=True, timeout=TIMEOUT + 60)
            if waited.returncode:
                raise RuntimeError('Docker could not observe the task exit')
            code = int(waited.stdout.strip())
            terminal = 'CLI_EXIT_' + str(code)
        except subprocess.TimeoutExpired:
            terminal = 'EXTERNAL_RESOURCE_DEADLINE'
    finally:
        stop = subprocess.run(['docker', 'stop', '-t', '5', name], capture_output=True, text=True)
        if process and process.poll() is None:
            process.wait(timeout=20)
        stdout.close()
        stderr.close()
        inspect = json.loads(docker('inspect', name))[0]
        elapsed = round(time.monotonic() - active[case], 3)
        artifacts = {p.name: {'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
                     for p in workspace.iterdir() if p.is_file()}
        receipts = [json.loads(p.read_text()) for p in (folder / 'model').glob('*-receipt.json') if p.name[0].isdigit()]
        summary = {'case': case, 'terminal': terminal, 'cliExitCode': code, 'elapsedSeconds': elapsed,
            'modelRequests': counts.get(case, 0), 'usage': {k: sum((p.get('usage') or {}).get(k, 0) for p in receipts)
            for k in ['prompt_tokens', 'completion_tokens', 'total_tokens']},
            'inputUnchanged': artifacts.get('test.parquet', {}).get('sha256') == hashlib.sha256(original.read_bytes()).hexdigest(),
            'artifacts': artifacts, 'stopConfirmed': not inspect['State']['Running'],
            'oomKilled': inspect['State']['OOMKilled'], 'independentAcceptance': 'PENDING',
            'javaOrRuntimeCallbacks': False}
        write(folder / 'summary.json', summary)
        active.pop(case, None)
        print(json.dumps(summary, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    cases = sys.argv[1:] or ['parquet-1', 'parquet-2', 'parquet-3']
    if not all(case.startswith('parquet-') and case[8:].isdigit() for case in cases):
        raise ValueError('Expected independent parquet-N case names')
    server = http.server.ThreadingHTTPServer(('0.0.0.0', PROXY_PORT), AuditProxy)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        for case in cases:
            run_case(case)
    finally:
        server.shutdown()
