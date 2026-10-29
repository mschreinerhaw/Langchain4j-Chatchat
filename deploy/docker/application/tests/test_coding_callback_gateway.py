"""Real cached Docker/Nginx checks; the backend is an explicit auth fixture."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
BACKEND = '''
from http.server import BaseHTTPRequestHandler,HTTPServer
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_POST(self):
  self.rfile.read(int(self.headers.get('Content-Length','0')))
  authorized=self.headers.get('Authorization')=='Bearer controlled-test-token'
  self.send_response(200 if authorized else 401);self.end_headers()
  self.wfile.write(b'{"fixture":true}')
HTTPServer(('0.0.0.0',8080),Handler).serve_forever()
'''


@unittest.skipUnless(shutil.which('docker'), 'Docker CLI is required for real gateway tests')
class CodingCallbackGatewayTest(unittest.TestCase):
    def test_only_declared_post_callbacks_reach_the_existing_backend(self):
        tag = 'coding-boundary-' + uuid.uuid4().hex[:12]
        backend, proxy = tag + '-api', tag + '-proxy'
        def docker(*args):
            return subprocess.check_output(['docker', *args], text=True, stderr=subprocess.PIPE).strip()
        try:
            docker('run', '-d', '--pull=never', '--name', backend, '--publish', '127.0.0.1::8081',
                   '--entrypoint', 'python', os.environ.get('GATEWAY_PYTHON_TEST_IMAGE', 'python:3.11-alpine'),
                   '-u', '-c', BACKEND)
            docker('create', '--pull=never', '--name', proxy, '--network', 'container:' + backend,
                   '--entrypoint', 'nginx', os.environ.get('GATEWAY_TEST_IMAGE', 'nginx:1.27.5-alpine'),
                   '-c', '/tmp/boundary.conf', '-g', 'daemon off;')
            docker('cp', str(ROOT / 'node-proxy.conf'), proxy + ':/tmp/boundary.conf')
            docker('start', proxy)
            address = docker('port', backend, '8081').splitlines()[0]
            base = 'http://' + address
            def status(path, method='POST', authorized=False):
                headers = {'Content-Type': 'application/json'}
                if authorized: headers['Authorization'] = 'Bearer controlled-test-token'
                request = urllib.request.Request(base + path, data=b'{}' if method == 'POST' else None,
                                                 headers=headers, method=method)
                try:
                    with urllib.request.urlopen(request, timeout=3) as result: return result.status
                except urllib.error.HTTPError as error: return error.code
            for _ in range(40):
                try:
                    if status('/internal/coding/cline/model') == 401: break
                except (OSError, urllib.error.URLError): pass
                time.sleep(.1)
            self.assertEqual(401, status('/internal/coding/cline/model'))
            for route in ('model', 'input-context', 'input-read', 'submit-report', 'read-skill', 'prepare-python', 'execute-python',
                          'workspace-read', 'workspace-propose', 'checkpoint', 'finish'):
                self.assertEqual(200, status('/internal/coding/cline/' + route, authorized=True), route)
            for path in ('/api/v1/admin/auth/login', '/api/v1/mcp/services', '/api/v1/sql/templates',
                         '/_search', '/internal/coding/cline/data-request',
                         '/internal/coding/cline/model/extra', '/internal/coding/cline/unknown'):
                self.assertEqual(404, status(path, authorized=True), path)
            self.assertEqual(403, status('/internal/coding/cline/model', method='GET', authorized=True))
        finally:
            for container in (proxy, backend):
                subprocess.run(['docker', 'rm', '-f', container], capture_output=True)


if __name__ == '__main__': unittest.main()
