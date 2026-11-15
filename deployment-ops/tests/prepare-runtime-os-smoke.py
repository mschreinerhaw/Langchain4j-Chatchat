"""Check interrupted private credential/TLS initialization in disposable labeled volumes. No application data is used."""
import subprocess,uuid,os,secrets,json
from pathlib import Path
root=Path(__file__).resolve().parents[1];ident='ops-prepare-check-'+uuid.uuid4().hex[:10];volumes=[ident+'-'+v for v in ['config','certs','node']]
def run(args,**kwargs):
 result=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,**kwargs)
 if result.returncode:raise RuntimeError(result.stdout)
 return result.stdout
settings={key:secrets.token_urlsafe(24) for key in ['CRYPTO_KEY','INTERNAL_SECRET','CODING_SECRET','MONITOR_SECRET','OS_ADMIN_PASSWORD']};settings['HOST_MAC']='02:00:00:00:00:01' # Disposable test only; never an application license identity.
try:
 for volume in volumes:run(['docker','volume','create','--label','deployment-ops.prepare-check='+ident,volume])
 args=['docker','run','--rm','--pull=never','--network','none','--cap-drop','ALL','--cap-add','CHOWN','--cap-add','FOWNER','--cap-add','DAC_OVERRIDE','--mount','type=bind,source='+str(root/'recipes/runtime-os')+',target=/opt/ops-config,readonly']
 for volume,target in zip(volumes,['/shared','/certs','/node-data']):args+=['--mount','type=volume,source='+volume+',target='+target]
 for key in settings:args+=['-e',key]
 env={**os.environ,**settings};image='eclipse-temurin:17-jdk-jammy'
 run(args+[image,'bash','/opt/ops-config/prepare.sh'],env=env)
 # Model the exact partial boundary: protected seed/key files exist, completion records were not committed.
 run(args+[image,'bash','-c','rm -f /shared/bootstrap.complete /certs/complete; bash /opt/ops-config/prepare.sh; cmp <(openssl x509 -in /certs/root-ca.pem -outform DER) <(keytool -exportcert -alias runtime-ca -keystore /shared/opensearch-truststore.p12 -storepass changeit 2>/dev/null)'],env=env)
 run(['docker','run','--rm','--pull=never','--network','none','--user','999:999','--cap-drop','ALL','--mount','type=volume,source='+volumes[0]+',target=/shared,readonly',image,'bash','-c','test -r /shared/mcp-initial-user.sql'])
 print('PASS repeated interrupted TLS/bootstrap initialization, matching CA truststore and PostgreSQL-user seed readability')
finally:
 for volume in volumes:
  info=json.loads(run(['docker','volume','inspect',volume]))[0]
  assert info['Labels']['deployment-ops.prepare-check']==ident
  run(['docker','volume','rm',volume])
