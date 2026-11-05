"""Isolated SDK regression with real Docker Python; deterministic model responses.

Usage: python3 test-cline-sdk-sandbox.py --source-dir VERIFY --node-image IMAGE
No production task, model, database or input data is used by this fixture.
"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import threading

PYTHON_RUNNER = r'''
import json,sys,traceback
from pathlib import Path
request=json.load(sys.stdin)
try:
    exec(compile(request['source'], request['fileName'], 'exec'), {'__name__':'__main__'})
    path=Path('/workspace/output/rows.jsonl')
    if not path.is_file(): result={'valid':False,'error':'OUTPUT_MISSING'}
    else:
        rows=[json.loads(line) for line in path.read_text().splitlines()]
        valid=len(rows)==1 and type(rows[0].get('value')) is int and rows[0]['value']==6
        result={'valid':valid,'error':None if valid else 'OUTPUT_SCHEMA','rows':rows}
except Exception as error:
    frames=traceback.extract_tb(error.__traceback__)
    line=getattr(error,'lineno',None) or next((f.lineno for f in reversed(frames) if f.filename==request['fileName']),None)
    result={'valid':False,'error':type(error).__name__,'line':line}
print('__SDK_FIXTURE__'+json.dumps(result))
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', required=True, type=Path)
    parser.add_argument('--node-image', required=True)
    parser.add_argument('--python-image', default='python:3.11-alpine')
    args = parser.parse_args()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *unused):
            pass

        def do_POST(self):
            if self.path != '/execute':
                self.send_error(404)
                return
            size = int(self.headers.get('Content-Length', 0))
            if size > 512000:
                self.send_error(413)
                return
            payload = self.rfile.read(size)
            # Code is confined to a new, unprivileged, offline Python container.
            # The SDK container receives neither a Docker socket nor credentials.
            try:
                result = subprocess.run(['docker', 'run', '--rm', '-i', '--network', 'none', '--read-only',
                    '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--user', '10001:10001',
                    '--pids-limit', '64', '--memory', '128m', '--cpus', '1',
                    '--tmpfs', '/workspace/output:rw,noexec,nosuid,size=4m,uid=10001,gid=10001',
                    '--entrypoint', 'python', args.python_image, '-c', PYTHON_RUNNER],
                    input=payload, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, check=True)
                lines = result.stdout.decode().splitlines()
                response = next(line[len('__SDK_FIXTURE__'):] for line in reversed(lines) if line.startswith('__SDK_FIXTURE__'))
                json.loads(response)
                body = response.encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except Exception:
                self.send_error(500, 'Sandbox fixture failed; inspect the test host')

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        result = subprocess.run(['docker', 'run', '--rm', '--network', 'host', '--read-only',
            '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--tmpfs', '/tmp:rw,noexec,nosuid,size=32m',
            '--entrypoint', 'node', '-v', str(args.source_dir.resolve()) + ':/verify:ro', '-w', '/verify/coding-agent-node',
            '-e', 'CLINE_SANDBOX_TEST_URL=http://127.0.0.1:' + str(server.server_port), args.node_image,
            '--test', 'integration/sdk-sandbox.mjs'], check=False)
    finally:
        server.shutdown()
        server.server_close()
        thread.join()
    raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
