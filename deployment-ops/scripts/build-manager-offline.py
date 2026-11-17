"""Package existing, locally source-built images without pulling or tagging images."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT=Path(__file__).resolve().parents[1]

def release_compose(web,agent):
    protected={'deployment-ops.control-plane':'true'}
    return {'name':'deployment-ops','services':{
        'executor':{'image':agent,'pull_policy':'never','labels':protected,'environment':{'EXECUTOR_TOKEN':'${EXECUTOR_TOKEN:?set token}','OPS_READ_ONLY':'${OPS_READ_ONLY:-1}','OPS_ALLOWED_REGISTRIES':'${OPS_ALLOWED_REGISTRIES:-}'},
            'volumes':['/var/run/docker.sock:/var/run/docker.sock','ops-state:/state','ops-packages:/packages','./trust:/trust:ro','/proc:/host/proc:ro','/:/host/root:ro,rslave'],
            'networks':['ops','edge'],'read_only':True,'tmpfs':['/tmp:size=512m'],'security_opt':['no-new-privileges:true'],'restart':'unless-stopped'},
        'web':{'image':web,'pull_policy':'never','labels':protected,'environment':{'DB_PASSWORD':'${DB_PASSWORD:?set password}','EXECUTOR_TOKEN':'${EXECUTOR_TOKEN}','ADMIN_PASSWORD':'${ADMIN_PASSWORD:?set password}'},
            'volumes':['ops-data:/data'],'ports':['${OPS_BIND:-127.0.0.1}:${OPS_PORT:-19220}:8080'],'networks':['ops','edge'],'read_only':True,'tmpfs':['/tmp:size=64m'],'cap_drop':['ALL'],'security_opt':['no-new-privileges:true'],'restart':'unless-stopped'}},
        'networks':{'ops':{'internal':True},'edge':{}},'volumes':{'ops-data':{},'ops-state':{},'ops-packages':{}}}

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--web-image',required=True);parser.add_argument('--agent-image',required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if args.output.exists():raise SystemExit('Output must be a new directory; existing release is never overwritten')
    records=[]
    for role,tag in [('web',args.web_image),('executor',args.agent_image)]:
        item=json.loads(subprocess.check_output(['docker','image','inspect',tag]))[0]
        records.append({'role':role,'tag':tag,'id':item['Id'],'os':item['Os'],'architecture':item['Architecture'],'size':item['Size']})
    args.output.mkdir(parents=True);(args.output/'images').mkdir();(args.output/'trust').mkdir()
    archive=args.output/'images/manager-images.tar'
    subprocess.run(['docker','image','save','-o',str(archive),args.web_image,args.agent_image],check=True)
    digest=hashlib.sha256()
    with archive.open('rb') as source:
        for block in iter(lambda:source.read(1024**2),b''):digest.update(block)
    (args.output/'SHA256SUMS').write_text(digest.hexdigest()+'  images/manager-images.tar\n')
    (args.output/'images.lock.json').write_text(json.dumps(records,indent=2))
    (args.output/'compose.yaml').write_text(json.dumps(release_compose(args.web_image,args.agent_image),indent=2))
    shutil.copy2(ROOT/'.env.example',args.output/'.env.example')
    for name in ['install.sh','upgrade.sh','backup.sh','restore.sh','verify-images.py','restore.py']:
        shutil.copy2(ROOT/'deploy'/name,args.output/name)
        (args.output/name).chmod(0o755)
    shutil.copy2(ROOT/'docs/DEPLOYMENT-MANAGER.md',args.output/'README.md')
    print(json.dumps({'output':str(args.output),'sha256':digest.hexdigest(),'images':records},indent=2))

if __name__=='__main__':main()
