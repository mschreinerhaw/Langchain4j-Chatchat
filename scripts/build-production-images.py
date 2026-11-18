"""Build through the required TLS proxy, including tools that only support HTTP proxies."""
import argparse
import collections
import pathlib
import select
import socket
import socketserver
import ssl
import subprocess
import tempfile
import threading
import urllib.parse
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
MODULES = ['chatchat-runtime-news', 'chatchat-mcp-server', 'chatchat-api',
           'cline-agent-server', 'frontend-resource-hub']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--proxy', default='https://192.168.36.128:8443')
    parser.add_argument('--service', choices=MODULES + ['all'], default='all')
    parser.add_argument('--tag', default='production-local')
    parser.add_argument('--output', default=str(ROOT.parent / 'target' / 'production-build'))
    parser.add_argument('--container-host', default='host.docker.internal')
    opts = parser.parse_args()
    upstream = urllib.parse.urlsplit(opts.proxy)
    if upstream.scheme != 'https' or not upstream.hostname or upstream.username:
        parser.error('Expected an HTTPS proxy URL without embedded credentials')
    context = ssl.create_default_context()

    def connect():
        conn = socket.create_connection((upstream.hostname, upstream.port or 443), timeout=30)
        try:
            return context.wrap_socket(conn, server_hostname=upstream.hostname)
        except Exception:
            conn.close()
            raise

    # Validate the upstream certificate before starting any build.
    with connect() as connection:
        proxy_ca = ssl.DER_cert_to_PEM_cert(connection.getpeercert(binary_form=True))

    class Relay(socketserver.BaseRequestHandler):
        def handle(self):
            try:
                with connect() as remote:
                    remote.settimeout(None)
                    self.request.settimeout(None)
                    while True:
                        ready = [remote] if remote.pending() else select.select([self.request, remote], [], [], 120)[0]
                        if not ready:
                            return
                        for source in ready:
                            data = source.recv(65536)
                            if not data:
                                return
                            (remote if source is self.request else self.request).sendall(data)
            except (OSError, ssl.SSLError):
                return

    class Server(socketserver.ThreadingTCPServer):
        allow_reuse_address = True
        daemon_threads = True
        request_queue_size = 128

    output = pathlib.Path(opts.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    # Ephemeral host port, available to build containers via host.docker.internal.
    # The relay forwards only to the specified TLS proxy; it performs no DNS lookup for origins.
    with Server(('0.0.0.0', 0), Relay) as server, tempfile.TemporaryDirectory() as tmp:
        threading.Thread(target=server.serve_forever, daemon=True).start()
        port = server.server_address[1]
        proxy = f'http://{opts.container_host}:{port}'
        settings = ET.Element('settings')
        proxies = ET.SubElement(settings, 'proxies')
        for protocol in ('http',):
            entry = ET.SubElement(proxies, 'proxy')
            for key, value in {'id': 'build-' + protocol, 'active': 'true',
                               'protocol': protocol, 'host': opts.container_host,
                               'port': str(port), 'nonProxyHosts': 'localhost|127.*'}.items():
                ET.SubElement(entry, key).text = value
        proxy_ca_file = pathlib.Path(tmp, 'proxy-ca.pem')
        proxy_ca_file.write_text(proxy_ca, encoding='ascii')
        settings_file = pathlib.Path(tmp, 'settings.xml')
        ET.ElementTree(settings).write(settings_file, encoding='utf-8', xml_declaration=True)
        try:
            for module in MODULES if opts.service == 'all' else [opts.service]:
                command = ['docker', 'build', '--add-host', 'host.docker.internal:host-gateway']
                for key in ['HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy']:
                    command += ['--build-arg', key + '=' + (opts.proxy if module == 'frontend-resource-hub' else proxy)]
                command += ['--build-arg', 'NPM_HTTPS_PROXY=' + opts.proxy, '--build-arg', 'NO_PROXY=localhost,127.0.0.1',
                            '--secret', f'id=maven_settings,src={settings_file}',
                            '--secret', f'id=proxy_ca,src={proxy_ca_file}',
                            '-f', module + ('/Dockerfile.production' if module == 'frontend-resource-hub' else '/Dockerfile'), '-t', module + ':' + opts.tag, '.']
                logfile = output / (module + '.log')
                print(f'Building {module} via {opts.proxy}; log: {logfile}', flush=True)
                with logfile.open('w', encoding='utf-8') as log:
                    result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
                if result.returncode:
                    print(''.join(collections.deque(logfile.open(encoding='utf-8'), maxlen=25)), flush=True)
                    return result.returncode
                print('Built ' + module, flush=True)
        finally:
            server.shutdown()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
