"""Real Docker proxy-chain regression; backend is a deterministic auth fixture."""
import http.cookiejar
import json
import os
from pathlib import Path
import subprocess
import time
import unittest
import urllib.error
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parents[1]
BACKEND=r'''
import http.server,json,sys
class Handler(http.server.BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def reply(self,code,body='',**headers):
  self.send_response(code)
  for k,v in headers.items():self.send_header(k.replace('_','-'),v)
  self.end_headers();self.wfile.write(body.encode())
 def do_GET(self):
  if sys.argv[1]=='api':return self.reply(404)
  if self.path=='/monitor':return self.reply(302,Location='/monitor/')
  if self.path in ['/monitor/','/monitor/app.js','/monitor/style.css']:return self.reply(200,'monitor-static')
  if self.path=='/v1/monitor/node':
   valid=self.headers.get('Authorization')=='Bearer fixture-token' or 'fixture=session' in self.headers.get('Cookie','')
   return self.reply(200 if valid else 401,json.dumps({'monitor':valid}))
  return self.reply(200,'execution-endpoint-must-not-be-exposed')
 def do_POST(self):
  from urllib.parse import urlsplit
  if self.path!='/monitor/session':return self.reply(404)
  if urlsplit(self.headers.get('Origin','')).netloc!=self.headers.get('Host'):return self.reply(403)
  data=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
  if data.get('token')!='fixture-token':return self.reply(401)
  return self.reply(200,'{}',Set_Cookie='fixture=session; Path=/; HttpOnly; SameSite=Strict')
http.server.HTTPServer(('0.0.0.0',int(sys.argv[2])),Handler).serve_forever()
'''

class MonitorGatewayTest(unittest.TestCase):
    def test_monitor_auth_origin_cookie_and_execution_isolation_across_both_proxies(self):
        prefix='monitor-route-test-'+uuid.uuid4().hex[:12]
        control=prefix+'-control';coding=prefix+'-coding'
        api=prefix+'-api';worker=prefix+'-worker';proxy=prefix+'-proxy';gateway=prefix+'-gateway'
        python=os.environ.get('PYTHON_TEST_IMAGE','python:3.11-alpine')
        nginx=os.environ.get('GATEWAY_TEST_IMAGE','nginx:1.27.5-alpine')
        def docker(*args):return subprocess.check_output(['docker',*args],text=True,stderr=subprocess.PIPE).strip()
        def status(url,body=None,headers=None,opener=None):
            req=urllib.request.Request(url,headers=headers or {},data=json.dumps(body).encode()if body is not None else None)
            try:
                with (opener or urllib.request.build_opener(urllib.request.ProxyHandler({}))).open(req,timeout=5)as response:
                    return response.status,response.read()
            except urllib.error.HTTPError as error:return error.code,error.read()
        try:
            docker('network','create',control);docker('network','create','--internal',coding)
            docker('run','-d','--name',api,'--network',control,'--network-alias','api','--entrypoint','python',python,'-u','-c',BACKEND,'api','8080')
            docker('network','connect','--alias','api',coding,api)
            docker('run','-d','--name',worker,'--network',coding,'--network-alias','coding-agent','--entrypoint','python',python,'-u','-c',BACKEND,'worker','3100')
            docker('create','--name',proxy,'--network','container:'+api,'--entrypoint','nginx',nginx,'-g','daemon off;')
            docker('cp',str(ROOT/'node-proxy.conf'),proxy+':/etc/nginx/nginx.conf');docker('start',proxy)
            docker('create','--name',gateway,'--network',control,'--publish','127.0.0.1::8080','--add-host','mcp:127.0.0.1','--entrypoint','nginx',nginx,'-g','daemon off;')
            docker('cp',str(ROOT/'public-gateway.conf'),gateway+':/etc/nginx/nginx.conf');docker('start',gateway)
            address=docker('port',gateway,'8080');base='http://'+address
            for attempt in range(60):
                try:
                    if status(base+'/monitor/')[0]==200:break
                except urllib.error.URLError:pass
                time.sleep(.25)
            else:self.fail('Monitor proxy chain did not become ready')
            for path in ['/monitor','/monitor/','/monitor/app.js','/monitor/style.css']:
                self.assertEqual(status(base+path)[0],200)
            self.assertEqual(status(base+'/v1/monitor/node')[0],401)
            self.assertEqual(status(base+'/v1/monitor/node',headers={'Authorization':'Bearer fixture-token'})[0],200)
            cookie=http.cookiejar.CookieJar();browser=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(cookie))
            headers={'Origin':base,'Content-Type':'application/json'}
            self.assertEqual(status(base+'/monitor/session',{'token':'wrong'},headers,browser)[0],401)
            self.assertEqual(status(base+'/monitor/session',{'token':'fixture-token'},headers,browser)[0],200)
            self.assertEqual(status(base+'/v1/monitor/node',opener=browser)[0],200)
            self.assertEqual(status(base+'/monitor/session',{'token':'fixture-token'},{**headers,'Origin':'https://outside.invalid'},browser)[0],403)
            for path in ['/v1/runs','/v1/runs/cancel','/internal/coding/cline/preflight','/monitor-evil','/v1/monitoring']:
                self.assertEqual(status(base+path)[0],404)
            denied=docker('run','--rm','--network','container:'+api,'--entrypoint','python',python,'-c',
                "import urllib.request,urllib.error\ntry: urllib.request.urlopen('http://127.0.0.1:8792/v1/runs')\nexcept urllib.error.HTTPError as e: print(e.code)")
            self.assertEqual(denied,'404')
        finally:
            for name in [gateway,proxy,worker,api]:subprocess.run(['docker','rm','-f',name],capture_output=True)
            for network in [coding,control]:subprocess.run(['docker','network','rm',network],capture_output=True)

if __name__=='__main__':unittest.main()
