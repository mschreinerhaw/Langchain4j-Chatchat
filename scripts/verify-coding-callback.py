"""Fail-closed candidate callback gate; credentials remain inside the worker container."""
import argparse
import json
from pathlib import Path
import subprocess
import time


PROBE = r"""
import {readFileSync} from 'node:fs';
import {httpGateway} from '/srv/coding-agent/dist/server.js';
const base=process.env.RUNTIME_CODING_CALLBACK_URL;
const target=new URL(base);
const allowed=(process.env.RUNTIME_CODING_ALLOWED_CALLBACK_HOSTS??'').split(',').map(x=>x.trim());
if(!['http:','https:'].includes(target.protocol)||!allowed.includes(target.hostname))
  throw new Error('Callback origin is not explicitly allowed');
const secret=readFileSync(process.env.RUNTIME_CODING_SHARED_SECRET_FILE,'utf8').trim();
if(secret.length<32)throw new Error('Callback credential is invalid');
const result=await httpGateway(base,secret,{}, {timeoutMs:10000}).preflight();
if(result.status!=='READY'||result.codeLifecycleVersion!=='runtime_os.code_lifecycle.v1')
  throw new Error('API did not negotiate the candidate lifecycle protocol');
const unsigned=await fetch(base+'/internal/coding/cline/preflight',{
  method:'POST',body:'{}',headers:{'content-type':'application/json'},signal:AbortSignal.timeout(10000),redirect:'error'});
if(unsigned.status!==401)throw new Error('Unsigned callback was not rejected');
console.log(JSON.stringify({status:'PASSED',signedCallback:true,unsignedRejected:true,
  origin:target.origin,codeLifecycleVersion:result.codeLifecycleVersion}));
"""


def verify(worker, runner=subprocess.run):
    result = runner(['docker', 'exec', '-i', worker, 'node', '--input-type=module', '-'],
                    input=PROBE, text=True, capture_output=True, timeout=30, check=False)
    if result.returncode:
        # Neither raw stderr nor container environment is a public deployment artifact.
        raise RuntimeError('Candidate callback connectivity/authentication failed')
    report = json.loads(result.stdout)
    if report.get('status') != 'PASSED' or not report.get('signedCallback') or not report.get('unsignedRejected'):
        raise RuntimeError('Candidate callback gate returned incomplete evidence')
    return report


def gated_switch(worker, switch, runner=subprocess.run):
    report = verify(worker, runner)
    switch()
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--worker', required=True, help='Candidate container, before public routing changes')
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--promote-gateway', help='Reload this gateway only after the candidate passes both authentication checks')
    args = parser.parse_args()
    report = {'worker': args.worker, 'checkedAtMs': int(time.time()*1000), 'status': 'FAILED'}
    try:
        def promote():
            subprocess.run(['docker', 'exec', args.promote_gateway, 'nginx', '-t'],
                           check=True, capture_output=True, timeout=15)
            subprocess.run(['docker', 'exec', args.promote_gateway, 'nginx', '-s', 'reload'],
                           check=True, capture_output=True, timeout=15)
        if args.promote_gateway:
            report.update(gated_switch(args.worker, promote))
            report['trafficPromoted'] = True
        else:
            report.update(verify(args.worker))
            report['trafficPromoted'] = False
    finally:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
