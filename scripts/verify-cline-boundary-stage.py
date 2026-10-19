"""Run on the isolated Docker host; probes open ports only, never authenticates to a business database."""
import json
import pathlib
import subprocess
import urllib.request

ROOT = pathlib.Path('/opt/runtime-os-docker-stage/evidence/cline-monitor-20261019')


def inspect(name):
    return json.loads(subprocess.check_output(['docker', 'inspect', 'runtime-os-app-stage-' + name + '-1']))[0]


def main():
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    credentials = json.loads((ROOT / 'stage-auth.json').read_text())
    login = urllib.request.Request('http://127.0.0.1:18180/api/v1/enterprise/auth/login',
        data=json.dumps(credentials).encode(), headers={'Content-Type':'application/json'})
    with client.open(login, timeout=15) as response:
        token = json.load(response)['data']['token']
    health = []
    for port in (18180, 18100):
        request = urllib.request.Request(f'http://127.0.0.1:{port}/api/v1/health', headers={'Authorization':'Bearer '+token})
        with client.open(request, timeout=10) as response:
            health.append({'port': port, 'status': json.load(response)['data']['status']})
    readiness = subprocess.run(['docker', 'exec', 'runtime-os-app-stage-coding-agent-1', 'node', 'src/healthcheck.js'], capture_output=True)
    api, node, mcp = inspect('api'), inspect('coding-agent'), inspect('mcp')
    mcp_ip = next(iter(mcp['NetworkSettings']['Networks'].values()))['IPAddress']
    probe = """
import net from 'node:net';import {access,writeFile} from 'node:fs/promises';
const hosts=JSON.parse(process.argv[2]);
const connect=(host,port)=>new Promise(resolve=>{const s=net.createConnection({host,port});let done=false;const finish=allowed=>{if(!done){done=true;s.destroy();resolve({host,port,allowed});}};s.setTimeout(1500,()=>finish(false));s.on('connect',()=>finish(true));s.on('error',()=>finish(false));});
const endpoints=await Promise.all([['/internal/coding/cline/model','POST'],['/api/v1/agent/tasks','GET']].map(async([path,method])=>{try{const r=await fetch('http://api:8080'+path,{method,body:method==='POST'?'{}':undefined,headers:{'content-type':'application/json'},signal:AbortSignal.timeout(3000)});return {path,status:r.status};}catch{return {path,status:null};}}));
const network=await Promise.all(hosts.map(([h,p])=>connect(h,p)));
let dockerSocket=false,rootWritable=false;try{await access('/var/run/docker.sock');dockerSocket=true;}catch{}try{await writeFile('/srv/coding-agent/.boundary-probe','unexpected');rootWritable=true;}catch{}
console.log(JSON.stringify({endpoints,network,dockerSocket,rootWritable,uid:process.getuid()}));
"""
    hosts = [[mcp_ip, 8090], ['172.30.93.1', 3306], ['172.30.93.1', 5432], ['192.168.195.223', 3306], ['1.1.1.1', 80]]
    result = subprocess.run(['docker', 'exec', '-i', 'runtime-os-app-stage-coding-agent-1', 'node', '--input-type=module', '-', json.dumps(hosts)],
        input=probe, text=True, capture_output=True, check=True)
    proof = json.loads(result.stdout)
    passed = all(h['status'] == 'UP' for h in health) and readiness.returncode == 0
    passed = passed and all(not item['allowed'] for item in proof['network'])
    passed = passed and all(item['status'] in (401, 403) for item in proof['endpoints'])
    passed = passed and not proof['dockerSocket'] and not proof['rootWritable'] and proof['uid'] != 0
    report = {'status': 'PASSED' if passed else 'FAILED', 'health': health, 'nodeReadinessExit': readiness.returncode,
        'apiImage': api['Image'], 'nodeImage': node['Image'], 'nodeUser': node['Config']['User'],
        'nodeReadOnly': node['HostConfig']['ReadonlyRootfs'], 'nodeMemoryBytes': node['HostConfig']['Memory'],
        'nodeCpuQuota': node['HostConfig']['NanoCpus'], 'nodeCapDrop': node['HostConfig']['CapDrop'],
        'probe': proof, 'productionSwitched': False}
    (ROOT / 'service-and-boundary.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report), flush=True)
    if not passed:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
