"""Private provider audit for the generic S3 gateway; no task/analysis policy.

Each signed gateway session has its own unchanged model requests/responses.
Configuration is a private file; no credentials are accepted from task content.
"""
import argparse, importlib.util, json, http.server, threading
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--settings',type=Path,required=True)
p.add_argument('--root',type=Path,required=True)
p.add_argument('--audit-module',type=Path,required=True)
p.add_argument('--port',type=int,default=18539)
a=p.parse_args()
spec=importlib.util.spec_from_file_location('audit',a.audit_module)
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
audit.configure(a.root,json.loads(a.settings.read_text()))
class Proxy(audit.AuditProxy):
    def forward(self):
        case=self.path.split('/')[1]
        if not case or len(case)>96 or not all(c.isascii() and (c.isalnum() or c in '-_') for c in case):
            self.send_error(403);return
        audit.active[case]=True
        super().forward()
server=http.server.ThreadingHTTPServer(('127.0.0.1',a.port),Proxy)
server.serve_forever()
