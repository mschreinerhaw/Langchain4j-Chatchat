"""Export PostgreSQL DDL from the exact image classpath, without starting its application or contacting a database."""
import argparse,hashlib,json,shutil,subprocess,tempfile,zipfile,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--image',required=True);p.add_argument('--role',choices=['api','mcp','news'],required=True);p.add_argument('--output',required=True,type=Path);p.add_argument('--jdk-image',default='eclipse-temurin:17-jdk-jammy');args=p.parse_args()
if args.output.exists():raise SystemExit('Schema output already exists')
def run(argv):
 result=subprocess.run(argv,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
 if result.returncode:raise RuntimeError(result.stdout)
 return result.stdout
args.output.parent.mkdir(parents=True,exist_ok=True)
image=json.loads(run(['docker','image','inspect',args.image]))[0]['Id']
with tempfile.TemporaryDirectory(prefix='ops-schema-',dir=args.output.resolve().parent) as temp:
 folder=Path(temp);container=run(['docker','create','--network','none','--label','deployment-ops.schema-export=true','--entrypoint','/bin/true',image]).strip()
 try:run(['docker','cp',container+':/srv/app/app.jar',str(folder/'app.jar')])
 finally:run(['docker','rm','-v',container])
 with zipfile.ZipFile(folder/'app.jar') as jar:
  for info in jar.infolist():
   if info.is_dir() or not info.filename.startswith(('BOOT-INF/classes/','BOOT-INF/lib/')):continue
   target=(folder/info.filename).resolve();target.relative_to(folder.resolve());target.parent.mkdir(parents=True,exist_ok=True)
   with jar.open(info) as src,target.open('wb') as dst:shutil.copyfileobj(src,dst)
 shutil.copy2(ROOT/'scripts/ImageSchemaExporter.java',folder/'ImageSchemaExporter.java')
 packages={'api':['com.chatchat'],'mcp':['com.chatchat.mcpserver','com.chatchat.integration.mcp','com.chatchat.knowledgebase'],'news':['com.chatchat.runtime.news']}[args.role]
 cp='/export/BOOT-INF/classes:/export/BOOT-INF/lib/*:/export'
 command='javac -cp "$1" /export/ImageSchemaExporter.java && java -cp "$1" ImageSchemaExporter /export/schema.sql "${@:2}"'
 run(['docker','run','--rm','--pull=never','--network','none','--memory','1g','--cpus','2','--mount','type=bind,source='+str(folder.resolve())+',target=/export',args.jdk_image,'bash','-c',command,'schema-export',cp,*packages])
 args.output.write_bytes(('-- Entity metadata from '+image+'; role='+args.role+'; no database data copied.\n').encode('ascii')+(folder/'schema.sql').read_bytes())
args.output.with_suffix(args.output.suffix+'.source.json').write_text(json.dumps({'image':image,'role':args.role,'packages':packages,'sha256':hashlib.sha256(args.output.read_bytes()).hexdigest(),'method':'JPA entity metadata; no application or database data copied'},indent=2),encoding='ascii')
print('Exported '+args.role+' schema from '+image)
