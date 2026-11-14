"""Check shared versioned resources and the existing authenticated preview bundle."""
import hashlib
import json
import os
from pathlib import Path
import sys
import time
import urllib.request

def main():
    release=Path(sys.argv[1]);base=os.environ.get('HUB_URL','http://127.0.0.1:18220').rstrip('/');token=os.environ['HUB_TOKEN']
    client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def request(path,body=None):
        req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
        with client.open(req,timeout=45) as response:return response.read()
    resources=json.loads(request('/api/v1/resources'));report=next(r for r in resources if r['id']=='professional-report');echarts=next(r for r in resources if r['id']=='echarts')
    for resource in [report,echarts]:
        for file in resource['files']:
            if hashlib.sha256(request(file['path'])).hexdigest()!=file['sha256']:raise RuntimeError('Resource service digest mismatch')
    css=next(f['path'] for f in report['files'] if f['path'].endswith('.css'));js=next(f['path'] for f in report['files'] if f['path'].endswith('.js'));chart=next(f['path'] for f in echarts['files'] if f['path'].endswith('.js'))
    html=(release/'examples/bank-original/report.html').read_text(encoding='utf-8').replace('assets/report.css',css).replace('assets/report.js',js).replace('assets/echarts.min.js',chart)
    job=json.loads(request('/api/v1/preview',{'html':html}));route='/api/v1/preview/'+job['id']
    for _ in range(45):
        job=json.loads(request(route+'/result'))
        if job['status']!='running' and (job.get('bundle') or job.get('bundleError')):break
        time.sleep(1)
    if job.get('report',{}).get('htmlSha256')!=hashlib.sha256(html.encode()).hexdigest():raise RuntimeError('Preview evidence HTML differs')
    (release/'hub-preview.json').write_text(json.dumps(job,indent=2))
    if job['status']!='passed' or not job.get('bundle'):raise RuntimeError('Preview/bundle failed; inspect retained Hub evidence')
    (release/'hub-report-bundle.tgz').write_bytes(request(route+'/bundle'))
    print(json.dumps({'status':job['status'],'resourceVersion':report['version'],'bundle':True,'jobId':job['id']}))

if __name__=='__main__':main()
