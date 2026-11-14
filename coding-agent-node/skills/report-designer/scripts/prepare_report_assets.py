"""Fetch only catalog-pinned shared files through the existing task broker."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request

def transport(workspace):
    value=json.loads((workspace/'input/frontend-transport.json').read_text())
    client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def request(path):
        req=urllib.request.Request(value['url'].rstrip('/')+path,headers={'Authorization':'Bearer '+value['token']})
        with client.open(req,timeout=65) as response:
            data=response.read(4*1024*1024+1)
            if len(data)>4*1024*1024:raise ValueError('Resource exceeds limit')
            return data
    return request

def prepare(output,workspace=None):
    workspace=Path(workspace or Path.cwd()).resolve();output=Path(output).resolve()
    if output==workspace or not output.is_relative_to(workspace):raise ValueError('Use an authorized output folder inside the task workspace')
    request=transport(workspace);catalog=json.loads(request('/api/v1/resources'))
    report=next(r for r in catalog if r['id']=='professional-report');chart=next(r for r in catalog if r['id']=='echarts')
    expected=json.loads((Path(__file__).parents[1]/'assets/version.json').read_text())['version']
    if report['version']!=expected:raise ValueError('Node and Hub report resource versions differ; retain pinned versions and reconcile deployment')
    output.mkdir(parents=True,exist_ok=True);receipt={'reportVersion':report['version'],'echartsVersion':chart['version'],'files':{}}
    for package in [report,chart]:
        for file in package['files']:
            name=file['path'].rsplit('/',1)[-1]
            if name not in ['report.css','report.js','echarts.min.js','LICENSE']:continue
            target=output/('LICENSE.'+package['id'] if name=='LICENSE' else name)
            if not target.is_file() or hashlib.sha256(target.read_bytes()).hexdigest()!=file['sha256']:
                data=request(file['path'])
                if hashlib.sha256(data).hexdigest()!=file['sha256']:raise ValueError('Resource differs from catalog')
                target.write_bytes(data)
            receipt['files'][target.name]={'path':file['path'],'sha256':file['sha256']}
    (output/'resource_versions.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8')
    return receipt

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    print(json.dumps(prepare(args.output)))
