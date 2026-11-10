from pathlib import Path
import json,shutil,subprocess,hashlib,sys,os
r=Path('/opt/cline-parquet-isolation-20261110');settings=json.loads((r/'private/settings.json').read_text())
for case in sys.argv[1:]:
 f=r/'runs'/case;source=f/'workspace/analysis.py'
 if not source.exists():continue
 dest=r/'reproduction'/case/'workspace'
 if dest.exists():continue
 dest.mkdir(parents=True);os.chmod(dest,0o777)
 for name in ['analysis.py','test.parquet']:
  shutil.copyfile(f/'workspace'/name,dest/name);os.chmod(dest/name,0o444)
 p=subprocess.run(['docker','run','--rm','--name','cline-parquet-reproduce-'+case,'--user','363143:363143','--network','none','--read-only','--tmpfs','/tmp:rw,size=128m','--cap-drop','ALL','--security-opt','no-new-privileges','--memory','2g','--cpus','2','--pids-limit','256','-e','HOME=/tmp','-e','OPENBLAS_NUM_THREADS=1','-v',str(dest)+':/workspace','-v',str(dest/'test.parquet')+':/workspace/test.parquet:ro','-w','/workspace',settings['image'],'python','analysis.py'],capture_output=True,text=True,timeout=120)
 (dest.parent/'stdout.txt').write_text(p.stdout);(dest.parent/'stderr.txt').write_text(p.stderr)
 v={'case':case,'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'network':'none','originalWorkspaceModified':False,'exitCode':p.returncode,'stdoutSha256':hashlib.sha256(p.stdout.encode()).hexdigest(),'stderrSha256':hashlib.sha256(p.stderr.encode()).hexdigest(),'generatedFiles':[p.name for p in dest.iterdir() if p.name not in ['analysis.py','test.parquet']],'note':'Reproduction is supplementary; formal Python tool execution is recorded separately.'}
 (f/'reproduction-summary.json').write_text(json.dumps(v,indent=2));print(json.dumps(v))
