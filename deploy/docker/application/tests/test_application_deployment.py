"""Validate real Compose expansion and Nginx syntax without starting applications."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[1]

class ApplicationDeploymentTest(unittest.TestCase):
    def config(self, explicit=False):
        with tempfile.TemporaryDirectory(prefix='runtime-compose-') as directory:
            stage = Path(directory)
            (stage / 'config').mkdir()
            for name in ['api.env', 'mcp.env', 'news.env']:
                (stage / 'config' / name).write_text('')
            values = {'STAGE_ROOT': stage.as_posix(), 'API_IMAGE': 'test-api', 'MCP_IMAGE': 'test-mcp',
                      'NEWS_IMAGE': 'test-news', 'NODE_IMAGE': 'test-node', 'NODE_PROXY_IMAGE': 'nginx:1.27.5-alpine',
                      'MODEL_CONFIG_FILE': '/config/model.yml', 'MCP_HOST_MAC_FILE': '/sys/class/net/ens192/address',
                      'API_INTERNAL_KEY_FILE': '/keys/api', 'MCP_INTERNAL_KEY_FILE': '/keys/mcp',
                      'CODING_AGENT_SHARED_SECRET_FILE': '/keys/shared', 'CODING_AGENT_NODE_SECRET_FILE': '/keys/node',
                      'NEWS_DATA_DIR': '/existing/news/data', 'NEWS_CONFIG_DIR': '/existing/news/config'}
            if explicit:
                values.update(API_DATA_DIR='/existing/api/data', MCP_DATA_DIR='/existing/mcp/data',
                              MCP_PYTHON_WORKSPACE_ROOT='/legacy/python-assets', MCP_PYTHON_DATA_ROOT='/legacy/user-data')
            envfile=stage/'deployment.env'
            envfile.write_text('\n'.join(k+'='+v for k,v in values.items()))
            env=os.environ.copy()
            for key in values:env.pop(key,None)
            result=subprocess.run(['docker','compose','--env-file',str(envfile),'-f',str(ROOT/'compose.yaml'),
                                   'config','--format','json'],env=env,capture_output=True,text=True,check=True)
            return json.loads(result.stdout),stage.as_posix()

    def test_existing_paths_and_private_news(self):
        doc,_=self.config(True);services=doc['services']
        for service,expected in [('api','/existing/api/data'),('mcp','/existing/mcp/data'),('news','/existing/news/data')]:
            mount=next(v for v in services[service]['volumes'] if v['target']=='/srv/app/data')
            self.assertEqual(mount['source'],expected)
            self.assertFalse(mount['bind'].get('create_host_path',False))
        news=services['news'];self.assertFalse(news.get('ports'))
        self.assertEqual(services['mcp']['environment']['CHATCHAT_MCP_NEWS_RUNTIME_BASE_URL'],'http://news:8091')
        self.assertEqual(services['mcp']['environment']['CHATCHAT_MCP_PYTHON_WORKSPACE_ROOT'],'/legacy/python-assets')
        self.assertTrue(any(v['source']==v['target']=='/legacy/python-assets' for v in services['mcp']['volumes']))
        self.assertTrue(next(v for v in news['volumes'] if v['target']=='/srv/app/config')['read_only'])

    def test_stage_defaults_remain_persistent(self):
        doc,stage=self.config()
        for service in ['api','mcp']:
            mount=next(v for v in doc['services'][service]['volumes'] if v['target']=='/srv/app/data')
            self.assertEqual(mount['source'],stage+'/'+service+'/data')

    def test_nginx_accepts_gateway_config(self):
        subprocess.run(['docker','run','--rm','-i','--add-host','api:127.0.0.1','--add-host','mcp:127.0.0.1',
                        '--entrypoint','sh','nginx:1.27.5-alpine','-c',
                        'cat > /tmp/test.conf && exec nginx -t -c /tmp/test.conf'],
                       input=(ROOT/'public-gateway.conf').read_text(),text=True,capture_output=True,check=True)

    def test_redirects_keep_the_external_port(self):
        container=subprocess.check_output(['docker','create','--publish','127.0.0.1::8080',
            '--add-host','api:127.0.0.1','--add-host','mcp:127.0.0.1','--entrypoint','nginx',
            'nginx:1.27.5-alpine','-g','daemon off;'],text=True).strip()
        self.assertTrue(len(container)==64 and all(c in '0123456789abcdef' for c in container))
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self,*args,**kwargs):return None
        try:
            subprocess.run(['docker','cp',str(ROOT/'public-gateway.conf'),container+':/etc/nginx/nginx.conf'],check=True,capture_output=True)
            subprocess.run(['docker','start',container],check=True,capture_output=True)
            address=subprocess.check_output(['docker','port',container,'8080'],text=True).strip()
            opener=urllib.request.build_opener(NoRedirect())
            for path,expected in [('/admin/index.html','/'),('/admin','/'),('/admin/','/'),('/favicon.ico','/lingdong-insight-mark.svg')]:
                for attempt in range(20):
                    try:
                        opener.open('http://'+address+path,timeout=2)
                        self.fail('Expected redirect')
                    except urllib.error.HTTPError as response:
                        self.assertEqual(response.code,302)
                        self.assertEqual(response.headers['Location'],expected)
                        break
                    except urllib.error.URLError:
                        if attempt==19:raise
                        time.sleep(.25)
        finally:
            subprocess.run(['docker','rm','-f',container],check=True,capture_output=True)

if __name__=='__main__':unittest.main()
