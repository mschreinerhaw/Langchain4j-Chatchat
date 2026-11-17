"""Prepare a source-only Cline SDK adapter build with verified offline npm tarballs.
No application code is changed. The output is a Docker context to compile on the target server.
"""
import argparse,base64,hashlib,json,shutil,time
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source',required=True,type=Path);p.add_argument('--npm-cache',required=True,type=Path);p.add_argument('--output',required=True,type=Path);p.add_argument('--base-image',default='node:24-bookworm-slim');args=p.parse_args()
if args.output.exists():raise SystemExit('Use a new build directory')
source=args.source.resolve();out=args.output.resolve();out.mkdir(parents=True);cache=args.npm_cache.resolve()/'_cacache';target=out/'npm-cache/_cacache'
for name in ['src','web','skills']:
 shutil.copytree(source/name,out/name)
(out/'scripts').mkdir();shutil.copy2(source/'scripts/build.mjs',out/'scripts/build.mjs')
for name in ['package.json','package-lock.json','tsconfig.json','tsconfig.web.json']:shutil.copy2(source/name,out/name)
lock=json.loads((source/'package-lock.json').read_text(encoding='utf-8'));count=0
for name,package in lock['packages'].items():
 if not package.get('integrity') or not package.get('resolved'):continue
 url=package['resolved']
 if not url.startswith('https://registry.npmjs.org/'):raise ValueError('Review nonstandard package source: '+name)
 algorithm,b64=package['integrity'].split('-',1);digest=base64.b64decode(b64).hex();relative=Path('content-v2')/algorithm/digest[:2]/digest[2:4]/digest[4:];body=cache/relative
 with body.open('rb') as f:
  if hashlib.file_digest(f,algorithm).hexdigest()!=digest:raise ValueError('Cached tarball checksum mismatch: '+name)
 destination=target/relative;destination.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(body,destination)
 key='make-fetch-happen:request-cache:'+url;hashed=hashlib.sha256(key.encode()).hexdigest();index=Path('index-v5')/hashed[:2]/hashed[2:4]/hashed[4:]
 entries=[json.loads(line.split('\t',1)[1]) for line in (cache/index).read_text(encoding='utf-8').splitlines() if '\t' in line]
 entry=next(e for e in reversed(entries) if e.get('key')==key)
 entry.update({'integrity':package['integrity'],'size':body.stat().st_size})
 for field in ['reqHeaders','resHeaders']:
  headers=entry.get('metadata',{}).get(field,{})
  for header in list(headers):
   if header.lower() in ['authorization','proxy-authorization','cookie','set-cookie']:headers.pop(header)
 data=json.dumps(entry,separators=(',',':'));destination=target/index;destination.parent.mkdir(parents=True,exist_ok=True);destination.write_text('\n'+hashlib.sha1(data.encode()).hexdigest()+'\t'+data+'\n',encoding='utf-8');count+=1
text=(source/'Dockerfile').read_text(encoding='utf-8');text=text.replace('ARG NODE_RUNTIME_IMAGE=node:24.21.0-bookworm-slim','ARG NODE_RUNTIME_IMAGE='+args.base_image).replace('RUN npm ci --ignore-scripts --no-audit --no-fund','COPY npm-cache /offline-npm\nRUN npm ci --offline --cache /offline-npm --ignore-scripts --no-audit --no-fund').replace('npm prune --omit=dev','npm prune --offline --omit=dev')
skill_count=sum(1 for file in (source/'skills').glob('*/SKILL.md') if file.is_file())
if not skill_count:raise ValueError('No source skills found')
text=text.replace('r.records.length!==15','r.records.length!=='+str(skill_count))
(out/'Dockerfile').write_bytes(text.replace('\r\n','\n').encode('utf-8'))
(out/'offline-build.json').write_text(json.dumps({'profile':'repository-cline-sdk-adapter','packageCount':count,'skillCount':skill_count,'lockSha256':hashlib.sha256((source/'package-lock.json').read_bytes()).hexdigest(),'baseImage':args.base_image,'build':'docker build --pull=false --network=none -t deployment-ops-coding-adapter:source .'},indent=2),encoding='ascii')
print('Prepared '+str(count)+' checksum-verified offline npm artifacts. Build uses network=none; no source SDK changes.')
