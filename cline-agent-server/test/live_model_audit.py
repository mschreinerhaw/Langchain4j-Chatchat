"""Private real-provider audit transport for isolated live acceptance tests.

Forwards request bodies unchanged. Never supplies an analysis program or retries
an Agent task. Full provider evidence stays in the configured private directory.
"""
import datetime, hashlib, http.client, http.server, json, socket, threading, time, urllib.parse
from pathlib import Path
ROOT = None
settings = None
active, counts, lock = {}, {}, threading.Lock()
def configure(root, profile):
    global ROOT, settings
    ROOT, settings = Path(root), profile
def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2))

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
