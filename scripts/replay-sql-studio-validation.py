"""Reproduce parser/metadata mismatches without a database connection or model invocation."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def main():
    cli=argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--input',type=Path,required=True)
    cli.add_argument('--python',default=sys.executable)
    cli.add_argument('--baseline',type=Path)
    cli.add_argument('--output',type=Path,required=True)
    args=cli.parse_args()
    request=json.loads(args.input.read_text(encoding='utf-8'))
    raw=json.dumps(request,sort_keys=True,ensure_ascii=False).encode()
    result={'requestSha256':hashlib.sha256(raw).hexdigest(),'dialect':request['dialect'],
        'sqlExecuted':False,'sameInputForBothRuns':True,
        'files':[{ 'path':item['path'],'sha256':hashlib.sha256(item['content'].encode()).hexdigest()} for item in request['files']]}
    current=Path(__file__).resolve().parents[1]/'chatchat-api/src/main/resources/sql-toolchain/validate.py'
    for name,script in [('before',args.baseline),('after',current)]:
        if script is None:continue
        completed=subprocess.run([args.python,'-I',str(script)],input=raw,capture_output=True,check=True,timeout=20)
        parsed=json.loads(completed.stdout)
        if parsed.get('version')!='30.23.0':raise RuntimeError('Pinned SQLGlot 30.23.0 is required')
        result[name]={key:parsed.get(key) for key in ['valid','fieldValidation','diagnostics','references','referencedColumns','sqlExecuted','version']}
        result[name]['validatorSha256']=hashlib.sha256(script.read_bytes()).hexdigest()
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'before':result.get('before',{}).get('valid'),'after':result['after']['valid'],'sqlExecuted':False}))


if __name__=='__main__':main()
