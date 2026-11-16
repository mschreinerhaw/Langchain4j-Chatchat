"""Build a private offline search image from already reviewed vendor plugin artifacts. No downloads or live search data."""
import argparse,hashlib,json,re,shutil,subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--plugins',required=True,type=Path);p.add_argument('--output',required=True,type=Path);p.add_argument('--base-image',default='opensearchproject/opensearch:2.19.1');p.add_argument('--image',default='deployment-ops-opensearch:2.19.1-offline');p.add_argument('--build',action='store_true');args=p.parse_args()
if args.output.exists():raise SystemExit('Use a new search build directory')
args.output.mkdir(parents=True);checksums={}
for name in ['analysis-icu','analysis-ik','analysis-pinyin']:
 source=args.plugins/name;destination=args.output/'plugins'/name;destination.mkdir(parents=True)
 descriptor=(source/'plugin-descriptor.properties').read_text(encoding='utf-8')
 if not re.search(r'(?m)^opensearch.version=2\.19\.1\s*$',descriptor):raise ValueError('Plugin engine version differs: '+name)
 for file in source.iterdir():
  if not file.is_file():continue
  if file.is_symlink():raise ValueError('Symlinked plugin artifact')
  if file.suffix not in ['.jar','.dic','.properties','.policy'] and not file.name.startswith(('LICENSE','NOTICE')):continue
  target=destination/file.name;shutil.copy2(file,target);checksums[str(target.relative_to(args.output))]=hashlib.sha256(target.read_bytes()).hexdigest()
# Provision a fresh dictionary configuration; never copy a running environment's XML/settings.
ik=args.output/'config/analysis-ik';ik.mkdir(parents=True)
for file in (args.output/'plugins/analysis-ik').glob('*.dic'):shutil.copy2(file,ik/file.name)
(ik/'IKAnalyzer.cfg.xml').write_text('<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">\n<properties><comment>Offline vendor dictionaries</comment><entry key="ext_dict">extra_main.dic;extra_single_word.dic</entry><entry key="ext_stopwords">extra_stopword.dic</entry></properties>\n',encoding='ascii')
(args.output/'Dockerfile').write_text('FROM '+args.base_image+'\nCOPY --chown=1000:1000 plugins/ /usr/share/opensearch/plugins/\nCOPY --chown=1000:1000 config/analysis-ik/ /usr/share/opensearch/config/analysis-ik/\n',encoding='ascii')
(args.output/'artifact-checksums.json').write_text(json.dumps(checksums,indent=2),encoding='ascii')
if args.build:
 subprocess.run(['docker','image','inspect',args.base_image],check=True,stdout=subprocess.DEVNULL)
 subprocess.run(['docker','build','--pull=false','--network=none','-t',args.image,str(args.output)],check=True)
print('Prepared offline search plugins with fresh configuration and artifact checksums')
