"""Verify an unchanged application and its live runtime after compact-image deployment."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request


def run(*args):return subprocess.check_output(args,text=True,stderr=subprocess.PIPE).strip()


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True);p.add_argument('--auth',type=Path,required=True)
    args=p.parse_args();os.umask(0o077);root=args.root.resolve()
    deployment=json.loads((root/'deployment.json').read_text());assert deployment['status']=='DEPLOYED'
    candidates=json.loads((root/'candidates.json').read_text());original=json.loads((root/'containers-private.json').read_text())
    checks={}
    for kind,name in [('api','runtime-os-app-stage-api-1'),('mcp','runtime-os-app-stage-mcp-1')]:
        current=json.loads(run('docker','inspect',name))[0];expected=candidates['images'][kind];old=original[kind]
        assert current['State']['Running'] and current['Image']==expected['imageId']
        assert run('docker','exec',name,'sha256sum','/srv/app/app.jar').split()[0]==expected['jarSha256']
        for field in ('Env','Cmd','Entrypoint','User','WorkingDir','Labels'):assert current['Config'].get(field)==old['Config'].get(field),field
        for field in ('ReadonlyRootfs','CapDrop','Privileged','SecurityOpt','RestartPolicy','Memory','NanoCpus','PidsLimit','PortBindings','NetworkMode'):
            assert current['HostConfig'].get(field)==old['HostConfig'].get(field),field
        mounts=lambda c:sorted((m['Type'],m['Source'],m['Destination'],m['RW']) for m in c['Mounts'])
        assert mounts(current)==mounts(old)
        checks[kind]={'image':expected['image'],'imageId':current['Image'],'containerId':current['Id'],'bytes':expected['bytes'],
                      'jarSha256':expected['jarSha256'],'configurationPreserved':True,'mountsPreserved':True,'runtimePolicyPreserved':True}
    sql=run('docker','exec','runtime-os-app-stage-api-1','/usr/bin/python3','-c',"import sqlglot;assert sqlglot.__version__=='30.23.0';print(sqlglot.parse_one('select 1').sql())")
    assert sql=='SELECT 1'
    mcp='runtime-os-app-stage-mcp-1'
    run('docker','exec','runtime-os-app-stage-api-1','bash','-c','exec 3<>/dev/tcp/mcp/9091')
    cli=run('docker','exec',mcp,'sha256sum','/usr/local/bin/docker').split()[0]
    assert cli==candidates['images']['mcp']['dockerCliSha256']
    daemon=run('docker','exec',mcp,'docker','version','--format','{{.Server.Version}}')
    python_tag='chatchat-python-runtime:3.11-v1.2-fonts-r2-20261022'
    assert run('docker','image','inspect','--format','{{.Id}}',python_tag)=='sha256:3434f7630ff7c54a053c7385710e089d249e65d668699ab96f9603b6e1b27eef'
    fonts=json.loads(run('docker','exec',mcp,'docker','run','--rm','--network','none','--read-only',
        '--tmpfs','/tmp:rw,noexec,nosuid,size=128m','--user','10001:10001','--cap-drop','ALL','--security-opt','no-new-privileges:true',
        '--entrypoint','python',python_tag,'/opt/chatchat/verify-fonts.py'))
    assert fonts['status']=='PASSED' and fonts['glyphCoverage'] and fonts['legacySansOverride']
    spec=importlib.util.spec_from_file_location('callback',root/'verify-coding-callback.py')
    callback=importlib.util.module_from_spec(spec);spec.loader.exec_module(callback)
    signed=callback.verify('runtime-os-app-stage-coding-agent-1')
    token=json.loads(args.auth.read_text())['token']
    def api(path):
        req=urllib.request.Request('http://127.0.0.1:18180/api/v1'+path,headers={'Authorization':'Bearer '+token})
        with urllib.request.urlopen(req,timeout=15) as response:v=json.load(response)
        assert v['code']==200
        return v['data']
    deadline=time.monotonic()+180
    while True:
        registry=api('/mcp/center/recovery/status');tools=api('/mcp/tools')
        names={t.get('remoteToolName') for t in tools if t.get('serviceId')=='chatchat-mcp-server' and t.get('description')}
        if registry.get('state')=='HEALTHY' and names:break
        assert time.monotonic()<deadline,'MCP catalog did not recover'
        time.sleep(3)
    proof={'status':'PASS','services':checks,'sqlParser':sql,'dockerCliSha256':cli,'dockerDaemonVersion':daemon,
           'fontGate':fonts,'pythonRuntimeImageUnchanged':True,'callback':signed,'mcpRegistryState':registry['state'],
           'registeredToolCount':len(tools),'mcpToolNames':sorted(names),'modelCallsSubmitted':0}
    (root/'verification-public.json').write_text(json.dumps(proof,indent=2));print(json.dumps(proof),flush=True)


if __name__=='__main__':main()
