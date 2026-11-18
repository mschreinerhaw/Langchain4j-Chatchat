#!/usr/bin/env python3
"""Docker-only, offline fresh installation. No production data/config imports."""
import base64
import copy
import datetime
import hashlib
import hmac
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT=Path('/install')
ASSETS=Path('/opt/ops-config')
IMAGES=Path('/opt/product/images.json')

def run(args,**kwargs):
    return subprocess.run([str(s) for s in args],check=True,**kwargs)

def output(args):
    return subprocess.check_output([str(s) for s in args],text=True).strip()

def json_command(args):return json.loads(output(args))

def write_json(path,value,mode=0o600):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    temporary=path.with_name(path.name+'.new')
    temporary.write_text(json.dumps(value,indent=2)+'\n');temporary.chmod(mode);temporary.replace(path)

def read_json(path):return json.loads(Path(path).read_text())

def private_text(path,text,mode=0o600):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(text);path.chmod(mode)

def inspect(name):return json_command(['docker','inspect',name])[0]

def bootstrap_identity():
    # Run in a helper with host networking; obtain the real host NIC MAC.
    routes=Path('/proc/net/route').read_text().splitlines()[1:]
    candidates=[r.split()[0] for r in routes if r.split()[1]=='00000000']
    candidates += [name for _,name in socket.if_nameindex() if name!='lo']
    for interface in candidates:
        if interface.startswith(('lo','docker','veth','br-')):continue
        p=Path('/sys/class/net')/interface/'address'
        if p.exists():
            mac=p.read_text().strip()
            if re.fullmatch(r'(?:[0-9a-f]{2}:){5}[0-9a-f]{2}',mac) and mac!='00:00:00:00:00:00':
                print(mac);return
    raise RuntimeError('Host NIC identity could not be read')

class Installation:
    def __init__(self,allow_new=True):
        self.images=read_json(IMAGES)
        self.self=inspect(os.environ['HOSTNAME'])
        mount=next((m for m in self.self['Mounts'] if m['Destination']=='/install'),None)
        if not mount:raise RuntimeError('Mount the persistent installation directory at /install')
        self.host=Path(mount['Source'])
        if not self.host.is_absolute() or ',' in str(self.host):raise RuntimeError('Use an absolute Linux installation directory')
        longest=str(self.host/'n/1/w'/('0'*36)/'bridge/broker.sock')
        if len(longest.encode())>107:raise RuntimeError('Installation path is too long for Unix sockets; use /data/runtime-os')
        self.image=self.self['Image']
        self.state_path=ROOT/'installation.json'
        self.new=not self.state_path.exists()
        if self.new:
            if not allow_new:raise RuntimeError('This directory has not been installed; run install first')
            if any(ROOT.iterdir()):raise RuntimeError('Use an empty installation directory for a fresh install; existing data is not adopted automatically')
            project=os.environ.get('PROJECT','runtime-os')
            if not re.fullmatch(r'[a-z][a-z0-9-]{2,39}',project):raise RuntimeError('Invalid PROJECT')
            ports=[int(os.environ.get('HTTP_PORT','18100')),int(os.environ.get('MCP_PORT','18101'))]
            if len(set(ports))!=2 or any(p<1024 or p>65535 for p in ports):raise RuntimeError('Invalid application ports')
            published=output(['docker','ps','--format','{{.Ports}}'])
            in_use={int(value) for value in re.findall(r':(\d+)->',published)}
            if in_use.intersection(ports):raise RuntimeError('An application port is already in use; select different HTTP_PORT and MCP_PORT')
            info=json_command(['docker','info','--format','{{json .}}'])
            if info['OSType']!='linux' or info['Architecture'] not in ['x86_64','amd64']:raise RuntimeError('This release requires Linux/amd64 Docker')
            if info['MemTotal']<12*1024**3:raise RuntimeError('At least 12 GiB Docker memory is required')
            host_mac=output(['docker','run','--rm','--pull=never','--network=host','--entrypoint','python3',self.image,'/opt/product/installer.py','host-identity'])
            self.state={'schemaVersion':1,'project':project,'httpPort':ports[0],'mcpPort':ports[1],'hostMac':host_mac,'status':'PREPARING',
                'secrets':{k:secrets.token_hex(24) for k in ['postgresPassword','apiDatabasePassword','mcpDatabasePassword','newsDatabasePassword','temporalPassword','redisPassword','siloPassword','searchPassword','cryptoKey','internalSecret','codingSecret','monitorSecret','issuerKey','signing1','signing2','ownership1','ownership2','executor1','executor2','frontendToken']},
                'adminPassword':os.environ.get('ADMIN_PASSWORD') or secrets.token_urlsafe(18)}
            if not re.fullmatch(r'[A-Za-z0-9_!@#%+.,:-]{12,128}',self.state['adminPassword']):raise RuntimeError('ADMIN_PASSWORD must contain 12-128 ASCII letters, digits or _!@#%+.,:-')
            write_json(self.state_path,self.state)
        else:self.state=read_json(self.state_path)
        current_images={role:item['imageId'] for role,item in self.images.items()}
        if self.state.get('imageIdentities',current_images)!=current_images:
            raise RuntimeError('This installation belongs to another product image set; a versioned upgrade/migration is required')
        self.state['imageIdentities']=current_images
        write_json(self.state_path,self.state)
        self.project=self.state['project'];self.secret=self.state['secrets']
        self.compose=ROOT/'compose.json'

    def hp(self,path):return str(self.host/path)
    def cp(self,*args):return ['docker','compose','-p',self.project,'-f',str(self.compose),*args]
    def name(self,role):return self.project+'-'+role
    def image_ref(self,role):return self.images[role]['reference']
    def image_id(self,role):return self.images[role]['imageId']
    def worker_image_id(self):
        # The host may use OCI manifest IDs while the private classic store uses
        # configuration IDs. Both are supplied by the signed-archive resolver.
        return self.images['node'].get('configId',self.image_id('node'))

    def check_images(self):
        for role,entry in self.images.items():
            try:actual=inspect(entry['reference'])['Id']
            except subprocess.CalledProcessError:raise RuntimeError('Missing offline image: '+entry['reference'])
            if actual!=entry['imageId']:raise RuntimeError('Wrong image identity for '+role)
        if output(['docker','compose','version','--short'])=='':raise RuntimeError('Installer Compose tool is unavailable')

    def prepare(self):
        for name in ['bootstrap','config','certs','data/postgresql','data/temporal','data/opensearch','data/redis','data/silo','data/api','data/mcp','data/news','data/frontend','n','d/strict']:
            (ROOT/name).mkdir(parents=True,exist_ok=True)
        for source in ASSETS.rglob('*'):
            if source.is_file():
                target=ROOT/'bootstrap'/source.relative_to(ASSETS);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
        env={'CRYPTO_KEY':self.secret['cryptoKey'],'INTERNAL_SECRET':self.secret['internalSecret'],'CODING_SECRET':self.secret['codingSecret'],
             'MONITOR_SECRET':self.secret['monitorSecret'],'HOST_MAC':self.state['hostMac'],'OS_ADMIN_PASSWORD':self.state['adminPassword']}
        args=['docker','run','--rm','--pull=never','--network=none']
        for k,v in env.items():args+=['-e',k+'='+v]
        for source,target in [('config','/shared'),('certs','/certs'),('n','/node-data')]:args+=['-v',self.hp(source)+':'+target]
        run(args+['--entrypoint','bash',self.image,'/opt/ops-config/prepare.sh'])
        # Legacy profile files contain example encrypted client passwords.
        # Bind every internal client to this installation's generated envelope.
        for role in ['api','mcp','news']:
            properties=ROOT/f'config/{role}/application.properties'
            lines=properties.read_text().splitlines()
            envelope=next(line.split('=',1)[1] for line in lines if line.startswith('chatchat.internal-credential.encrypted-secret='))
            overrides={key:envelope for key in ['chatchat.mcp.center.encrypted-admin-password','chatchat.mcp.center.encrypted-invocation-token','chatchat.mcp.security.encrypted-invocation-token','chatchat.tools.document-search.auth.encrypted-password']}
            lines=[line for line in lines if line.split('=',1)[0] not in overrides]
            private_text(properties,'\n'.join(lines+[key+'='+value for key,value in overrides.items()])+'\n')
        # Redis switches to UID 999 before reading its configuration.
        conf=ROOT/'config/redis.conf'
        private_text(conf,'appendonly yes\nmaxmemory 256mb\nmaxmemory-policy allkeys-lru\nrequirepass '+self.secret['redisPassword']+'\n')
        os.chown(conf,999,999)
        os.chown(ROOT/'data/redis',999,999);os.chown(ROOT/'data/opensearch',1000,1000)
        # All bootstrap material is generated per installation, never copied from a deployment.
        user_file=ROOT/'config/silo-user';password_file=ROOT/'config/silo-password'
        if 'siloUser' not in self.state:self.state['siloUser']=user_file.read_text() if user_file.exists() else 'runtime-'+secrets.token_hex(8)
        if 'siloPassword' not in self.secret:self.secret['siloPassword']=password_file.read_text() if password_file.exists() else secrets.token_hex(24)
        private_text(user_file,self.state['siloUser']);private_text(password_file,self.secret['siloPassword'])
        write_json(self.state_path,self.state)
        self.connection()
        self.daemon_config()

    def connection(self):
        nodes={f'cline-worker-{i}':{'endpoint':f'http://127.0.0.1:{18451+i*10}','recoveryEndpoint':f'http://127.0.0.1:{18452+i*10}'} for i in [1,2]}
        write_json(ROOT/'config/native-connection.json',{'dynamicModels':True,'issuerKey':self.secret['issuerKey'],'defaultModel':'','modelProfiles':{},'defaultNodeId':'cline-worker-1','endpoint':nodes['cline-worker-1']['endpoint'],'nodes':nodes})

    def daemon_config(self):
        anchor=self.hp('d/strict');local=ROOT/'d/strict'
        for d in ['','run','data']:(local/d).mkdir(exist_ok=True);(local/d).chmod(0o700)
        write_json(local/'daemon.json',{'hosts':['unix://'+anchor+'/run/docker.sock'],'data-root':anchor+'/data','exec-root':anchor+'/run/docker-exec','pidfile':anchor+'/run/dockerd.pid','containerd':anchor+'/run/containerd.sock','bridge':'none','iptables':False,'ip-forward':False,'ip-masq':False,'userland-proxy':False,'storage-driver':'overlay2','live-restore':False})
        private_text(local/'containerd.toml',f'version = 2\nroot = "{anchor}/containerd"\nstate = "{anchor}/run/containerd"\n[grpc]\n  address = "{anchor}/run/containerd.sock"\n[plugins."io.containerd.grpc.v1.cri"]\n  disable_tcp_service = true\n')

    def bind(self,source,target,ro=False):return {'type':'bind','source':self.hp(source),'target':target,'read_only':ro,'bind':{'create_host_path':False}}

    def render(self):
        s={};sec=self.secret
        def svc(role,image=None,**kwargs):
            value={'image':self.image_ref(image or role),'pull_policy':'never','container_name':self.name(role),'restart':'unless-stopped','logging':{'driver':'json-file','options':{'max-size':'20m','max-file':'3'}},'networks':['control']}
            value.update(kwargs)
            s[role]=value;return value
        def health(test,seconds=5):return {'test':['CMD-SHELL',test],'interval':str(seconds)+'s','timeout':'5s','retries':60,'start_period':'15s'}
        svc('postgresql','postgres',environment={'POSTGRES_USER':'postgres','POSTGRES_PASSWORD':sec['postgresPassword']},volumes=[self.bind('data/postgresql','/var/lib/postgresql')],mem_limit='768m',healthcheck=health('pg_isready -U postgres'))
        svc('temporal-db','postgres',environment={'POSTGRES_USER':'temporal','POSTGRES_PASSWORD':sec['temporalPassword']},volumes=[self.bind('data/temporal','/var/lib/postgresql')],mem_limit='384m',healthcheck=health('pg_isready -U temporal'))
        svc('temporal',environment={'DB':'postgres12','DB_PORT':'5432','POSTGRES_USER':'temporal','POSTGRES_PWD':sec['temporalPassword'],'POSTGRES_SEEDS':'temporal-db','BIND_ON_IP':'0.0.0.0','DEFAULT_NAMESPACE':'runtime-native','NUM_HISTORY_SHARDS':'16','SKIP_DB_CREATE':'false','SKIP_SCHEMA_SETUP':'false'},depends_on={'temporal-db':{'condition':'service_healthy'}},mem_limit='1g',healthcheck=health('nc -z 127.0.0.1 7233'))
        svc('redis',command=['redis-server','/etc/redis/runtime.conf'],volumes=[self.bind('data/redis','/data'),self.bind('config/redis.conf','/etc/redis/runtime.conf',True)],mem_limit='384m',healthcheck=health('REDISCLI_AUTH="$(sed -n "s/^requirepass //p" /etc/redis/runtime.conf)" redis-cli --no-auth-warning ping | grep -qx PONG'))
        svc('opensearch','search',user='1000:1000',environment={'OPENSEARCH_JAVA_OPTS':'-Xms512m -Xmx512m','DISABLE_INSTALL_DEMO_CONFIG':'true'},volumes=[self.bind('data/opensearch','/usr/share/opensearch/data'),self.bind('certs','/usr/share/opensearch/config/certs',True),self.bind('bootstrap/opensearch.yml','/usr/share/opensearch/config/opensearch.yml',True)],mem_limit='1536m',healthcheck=health('curl -s -k -o /dev/null https://127.0.0.1:9200 || exit 1'))
        svc('silo',environment={'MINIO_ROOT_USER_FILE':'/run/secrets/root-user','MINIO_ROOT_PASSWORD_FILE':'/run/secrets/root-password'},command=['server','/data','--console-address',':9001'],volumes=[self.bind('data/silo','/data'),self.bind('config/silo-user','/run/secrets/root-user',True),self.bind('config/silo-password','/run/secrets/root-password',True)],mem_limit='512m',healthcheck=health('curl -fsS http://127.0.0.1:9000/minio/health/live >/dev/null'))
        def java(role,port):
            db={'api':'live_runtime_api','mcp':'live_runtime_mcp','news':'chatchat_news'}[role]
            env={'SERVER_PORT':str(port),'SPRING_PROFILES_ACTIVE':'postgresql' if role=='news' else 'prod','SPRING_CONFIG_ADDITIONAL_LOCATION':'optional:file:/opt/runtime-config/'+role+'/',
                'SPRING_JPA_HIBERNATE_DDL_AUTO':'validate','SPRING_DATASOURCE_URL':'jdbc:postgresql://postgresql:5432/'+db,'SPRING_DATASOURCE_USERNAME':'chatchat_'+role,'SPRING_DATASOURCE_PASSWORD':sec[role+'DatabasePassword'],
                'SPRING_DATASOURCE_DRIVER_CLASS_NAME':'org.postgresql.Driver','JAVA_TOOL_OPTIONS':'-Xms128m -Xmx768m -Djavax.net.ssl.trustStore=/opt/runtime-config/opensearch-truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit',
                'SPRING_DATA_REDIS_HOST':'redis','SPRING_DATA_REDIS_PASSWORD':sec['redisPassword'],'CHATCHAT_LICENSE_HOST_MAC_FILE':'/opt/runtime-config/host-mac'}
            prefix='CHATCHAT_'+role.upper()+'_POSTGRESQL_';env.update({prefix+'URL':env['SPRING_DATASOURCE_URL'],prefix+'USERNAME':env['SPRING_DATASOURCE_USERNAME'],prefix+'PASSWORD':sec[role+'DatabasePassword']})
            if role!='news':env.update(CHATCHAT_DATASOURCE_CONFIG='datasource-postgresql.yml',SPRING_CONFIG_IMPORT='classpath:datasource-postgresql.yml')
            return env
        search={'CHATCHAT_SEARCH_OPENSEARCH_URL':'https://opensearch:9200','CHATCHAT_SEARCH_OPENSEARCH_USERNAME':'admin','CHATCHAT_SEARCH_OPENSEARCH_PASSWORD':sec['searchPassword'],'CHATCHAT_SEARCH_OPENSEARCH_INSECURE_SSL':'false'}
        news=java('news',8091);news.update({'CHATCHAT_RUNTIME_NEWS_TEMPORAL_ENABLED':'true','CHATCHAT_RUNTIME_NEWS_TEMPORAL_SERVERMODE':'external','CHATCHAT_RUNTIME_NEWS_TEMPORAL_TARGET':'temporal:7233','CHATCHAT_RUNTIME_NEWS_TEMPORAL_NAMESPACE':'runtime-native','CHATCHAT_RUNTIME_NEWS_OPENSEARCH_ENABLED':'true','CHATCHAT_RUNTIME_NEWS_OPENSEARCH_ENDPOINT':'https://opensearch:9200','CHATCHAT_RUNTIME_NEWS_OPENSEARCH_USERNAME':'admin','CHATCHAT_RUNTIME_NEWS_OPENSEARCH_PASSWORD':sec['searchPassword'],'CHATCHAT_RUNTIME_NEWS_OPENSEARCH_INSECURESSL':'false','CHATCHAT_RUNTIME_NEWS_MCP_SERVER_BASE_URL':'http://mcp:8090'})
        # ConditionalOnProperty and relaxed field binding must see the same
        # canonical property; a collapsed SERVERMODE env name is not sufficient.
        news['SPRING_APPLICATION_JSON']=json.dumps({'chatchat.runtime.news.temporal.enabled':True,'chatchat.runtime.news.temporal.server-mode':'external','chatchat.runtime.news.temporal.target':'temporal:7233','chatchat.runtime.news.temporal.namespace':'runtime-native'})
        mcp=java('mcp',8090);mcp.update({'CHATCHAT_MCP_LUCENE_OPENSEARCH_URL':'https://opensearch:9200','CHATCHAT_MCP_LUCENE_OPENSEARCH_USERNAME':'admin','CHATCHAT_MCP_LUCENE_OPENSEARCH_PASSWORD':sec['searchPassword'],'CHATCHAT_MCP_LUCENE_OPENSEARCH_INSECURESSL':'false','CHATCHAT_MCP_AUTHORIZATION_API_BASE_URL':'http://api:8080','CHATCHAT_MCP_GRPC_PORT':'9091','CHATCHAT_MCP_NEWS_RUNTIME_BASE_URL':'http://news:8091','CHATCHAT_TOOLS_DATABASEQUERY_DRIVERLIBPATH':'/srv/app/lib/drivers'})
        api=java('api',8080);api.update(search);api.update({'CHATCHAT_MCP_CENTER_BASE_URL':'http://mcp:8090','CHATCHAT_MCP_GRPC_HOST':'mcp','CHATCHAT_MCP_GRPC_PORT':'9091','CHATCHAT_CODING_ENABLED':'true','CHATCHAT_CODING_CLINE_ENABLED':'true','CHATCHAT_CODING_CLINE_NODE_URL':'http://127.0.0.1:8791','CHATCHAT_CODING_CLINE_SHARED_SECRET_FILE':'/opt/runtime-config/coding-secret','CHATCHAT_RUNTIME_CODING_UNIFIED_ENABLED':'true','CHATCHAT_RUNTIME_CODING_UNIFIED_CONFIG_FILE':'/opt/runtime-config/native-connection.json','CHATCHAT_RUNTIME_CONTROL_PLANE_JAVA_SKILLS_ENABLED':'false','CHATCHAT_CHAT_DETAIL_STORE_PATH':'/srv/app/data/chat-rocksdb','CHATCHAT_UI_ARTIFACT_STORAGE_PATH':'/srv/app/data/ui-artifacts','CHATCHAT_ASSETS_ENABLED':'true','CHATCHAT_ASSETS_ENDPOINT':'http://silo:9000','CHATCHAT_ASSETS_ACCESSKEYFILE':'/opt/runtime-config/silo-user','CHATCHAT_ASSETS_SECRETKEYFILE':'/opt/runtime-config/silo-password','CHATCHAT_ASSETS_BUCKET':'runtime-assets','CHATCHAT_TOOLS_DATABASEQUERY_DRIVERLIBPATH':'/srv/app/lib/drivers'})
        api['SPRING_APPLICATION_JSON']=json.dumps({'chatchat.runtime.coding.scheduler':'temporal','chatchat.runtime.coding.temporal.target':'temporal:7233','chatchat.runtime.coding.temporal.namespace':'runtime-native','chatchat.runtime.coding.temporal.task-queue':'runtime-native-analysis-v1','chatchat.runtime.coding.temporal.worker-enabled':False,'chatchat.runtime.coding.temporal.tls-enabled':False,
            'chatchat.assets.enabled':True,'chatchat.assets.endpoint':'http://silo:9000','chatchat.assets.bucket':'runtime-assets','chatchat.assets.access-key-file':'/opt/runtime-config/silo-user','chatchat.assets.secret-key-file':'/opt/runtime-config/silo-password'})
        for role,env in [('news',news),('mcp',mcp),('api',api)]:
            svc(role,environment=env,volumes=[self.bind('config','/opt/runtime-config',True),self.bind('data/'+role,'/srv/app/data')],networks=['control','egress'],mem_limit='1536m' if role=='api' else '1g',healthcheck=health('bash /opt/runtime-health.sh '+str({'news':8091,'mcp':8090,'api':8080}[role])+' '+('9091' if role=='mcp' else "''")+' '+('/internal/v1/news/health' if role=='news' else '/api/v1/enterprise/auth/me' if role=='api' else '/api/v1/admin/auth/me')))
            s[role]['volumes'].append(self.bind('bootstrap/service-healthcheck.sh','/opt/runtime-health.sh',True))
        svc('frontend-resource-hub','frontend',command=['node','server.mjs'],environment={'HUB_TOKEN':sec['frontendToken'],'PUBLIC_URL':'http://frontend-resource-hub:8080'},networks=['frontend','control'],mem_limit='256m',healthcheck={'test':['CMD','node','-e',"fetch('http://localhost:8080/api/v1/health').then(r=>process.exit(r.ok?0:1))"],'interval':'10s','timeout':'5s','retries':30})
        svc('frontend-worker','frontend',command=['node','worker.mjs'],environment={'HUB_TOKEN':sec['frontendToken'],'HUB_ORIGIN':'http://frontend-resource-hub:8080'},networks=['frontend'],shm_size='256m',tmpfs=['/tmp:size=256m','/app/jobs:uid=1001,gid=1001,size=768m'],mem_limit='1536m',security_opt=['no-new-privileges:true','seccomp='+str(ROOT/'bootstrap/frontend-seccomp.json')],pids_limit=256,healthcheck={'test':['CMD','node','-e',"fetch('http://localhost:8081/health').then(r=>process.exit(r.ok?0:1))"],'interval':'10s','timeout':'5s','retries':30})
        daemon=svc('executor-daemon','daemon',environment={'RUNTIME_DAEMON_ROOT':self.hp('d/strict')},network_mode='none',privileged=True,volumes=[self.bind('d/strict',self.hp('d/strict')),self.bind('n',self.hp('n'))],mem_limit='4g',pids_limit=2048)
        daemon.pop('networks')
        node_script='/opt/runtime-os/coding-node/service/src/'
        for i in [1,2]:
            for role,script in [('node','governance-server'),('recovery','recovery-server'),('executor','worker-executor')]:
                name=role+str(i);env={'CLINE_GOVERNANCE_CONFIG':self.hp(f'n/{i}/gateway.json'),'CLINE_RECOVERY_PORT':str(18452+i*10)} if role!='executor' else {'CLINE_EXECUTOR_CONFIG':self.hp(f'n/{i}/executor.json')}
                value=svc(name,'node',user='0:0',entrypoint=['node',node_script+script+'.mjs'],environment=env,network_mode='service:api',volumes=[self.bind('n',self.hp('n'))],mem_limit='384m',security_opt=['no-new-privileges:true'])
                value.pop('networks')
                if role=='executor':value.update(pid='host',cap_add=['SYS_PTRACE']);value['volumes'].append(self.bind('d/strict',self.hp('d/strict'),True))
            worker=dict(s['api']);worker['environment']=dict(api);worker['volumes']=[self.bind('config','/opt/runtime-config',True),self.bind(f'data/worker{i}','/srv/app/data'),self.bind('bootstrap/service-healthcheck.sh','/opt/runtime-health.sh',True)]
            (ROOT/f'data/worker{i}').mkdir(exist_ok=True)
            worker['container_name']=self.name('worker'+str(i));worker['network_mode']='service:api';worker.pop('networks');worker.pop('healthcheck',None)
            worker['environment']['SERVER_PORT']=str(18080+i)
            properties=json.loads(api['SPRING_APPLICATION_JSON']);properties.update({'chatchat.runtime.coding.temporal.worker-enabled':True,'chatchat.runtime.coding.temporal.node-id':f'cline-worker-{i}','chatchat.runtime.coding.temporal.max-concurrent-tasks':1});worker['environment']['SPRING_APPLICATION_JSON']=json.dumps(properties)
            s['worker'+str(i)]=worker
        studio=svc('studio','studio',user='0:0',entrypoint=['node',node_script+'business-server.mjs'],environment={'CLINE_BUSINESS_CONFIG':self.hp('n/studio.json')},network_mode='service:api',volumes=[self.bind('n',self.hp('n'))],mem_limit='512m');studio.pop('networks')
        studio_executor=copy.deepcopy(s['executor1']);studio_executor['container_name']=self.name('studio-executor');studio_executor['environment']={'CLINE_EXECUTOR_CONFIG':self.hp('n/studio-executor.json')};s['studio-executor']=studio_executor
        svc('gateway',user='101:101',read_only=True,tmpfs=['/tmp:size=64m'],cap_drop=['ALL'],security_opt=['no-new-privileges:true'],ports=[str(self.state['httpPort'])+':8080',str(self.state['mcpPort'])+':8090'],mem_limit='128m',networks=['control','egress'],healthcheck=health("curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/v1/enterprise/auth/me | grep -qx 401"))
        manager=svc('manager','api',entrypoint=['python3','/opt/product/installer.py','supervise'],volumes=[self.bind('', '/install'),{'type':'bind','source':'/var/run/docker.sock','target':'/var/run/docker.sock'}],mem_limit='256m');manager['image']=self.image
        owner=getattr(self,'self',{}).get('Config',{}).get('Labels',{}).get('deployment-ops.owner')
        if owner:
            for role,value in s.items():
                value.setdefault('labels',{}).update({'deployment-ops.owner':owner,'deployment-ops.environment':self.project.removeprefix('ops-env-'),'deployment-ops.component':role,'deployment-ops.application':'runtime-os'})
        self.services=s
        write_json(self.compose,{'name':self.project,'services':s,'networks':{'control':{'internal':True},'frontend':{'internal':True},'egress':{}}})

    def up(self,*roles):run(self.cp('up','-d','--no-deps','--no-build','--pull','never',*roles))

    def wait(self,role,timeout=240):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            try:
                c=inspect(self.name(role));state=c['State']
                if state['Running'] and state.get('Health',{}).get('Status','healthy')=='healthy':return
                if not state['Running'] and c.get('RestartCount',0)>=3:raise RuntimeError(role+' repeatedly exited; inspect its logs')
            except subprocess.CalledProcessError:pass
            time.sleep(3)
        raise RuntimeError(role+' did not become healthy; use the installer logs command')

    def schema(self):
        args=['docker','run','--rm','--pull=never','--network',self.project+'_control','--entrypoint','bash',
            '-v',self.hp('bootstrap')+':/opt/ops-config:ro','-v',self.hp('config')+':/opt/runtime-config:ro',
            '-e','PGPASSWORD='+self.secret['postgresPassword']]
        for role in ['api','mcp','news']:args+=['-e',role.upper()+'_DB_PASSWORD='+self.secret[role+'DatabasePassword']]
        run(args+[self.image_ref('postgres'),'/opt/ops-config/initialize-databases.sh'])

    def search_security(self):
        private_text(ROOT/'config/search-password',self.secret['searchPassword'],0o640);os.chown(ROOT/'config/search-password',1000,1000)
        run(['docker','run','--rm','--pull=never','--network',self.project+'_control','--user','1000:1000','--entrypoint','bash',
            '-v',self.hp('certs')+':/usr/share/opensearch/config/certs:ro','-v',self.hp('config/search-password')+':/run/secrets/opensearch_admin_password:ro',
            '-v',self.hp('bootstrap/opensearch-init-security.sh')+':/opt/init-security.sh:ro',self.image_ref('search'),'/opt/init-security.sh'])

    def silo(self):
        run(['docker','run','--rm','--pull=never','--network',self.project+'_control','--entrypoint','python3','-v',self.hp('config')+':/install/config:ro',self.image,'/opt/product/installer.py','silo-init'])

    def configure_native(self):
        # The schema/bootstrap has created the initial tenant. No customer data is imported.
        deadline=time.monotonic()+120
        while True:
            tenants=output(['docker','exec',self.name('postgresql'),'psql','-U','postgres','-d','live_runtime_api','-Atc','select id from sys_tenant order by id']).splitlines()
            if tenants:break
            if time.monotonic()>deadline:raise RuntimeError('Initial tenant is not ready')
            time.sleep(2)
        local_socket=str(ROOT/'d/strict/run/docker.sock')
        daemon=json_command(['docker','--host','unix://'+local_socket,'info','--format','{{json .}}'])
        previous=ROOT/'daemon-binding.json'
        if previous.exists() and read_json(previous)['daemonId']!=daemon['ID']:
            raise RuntimeError('Private execution daemon data identity changed; existing task state must be recovered before admitting new work')
        if daemon['DockerRootDir']!=self.hp('d/strict/data'):raise RuntimeError('Unexpected private executor daemon root')
        if not all(daemon.get(k) for k in ['MemoryLimit','CpuCfsQuota','PidsLimit']):raise RuntimeError('Docker worker resource enforcement is unavailable')
        # Import the worker from the already loaded product image, without any registry access.
        worker=self.worker_image_id()
        if subprocess.run(['docker','--host','unix://'+local_socket,'image','inspect',worker],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
            save=subprocess.Popen(['docker','image','save',self.image_ref('node')],stdout=subprocess.PIPE)
            load=subprocess.run(['docker','--host','unix://'+local_socket,'image','load'],stdin=save.stdout,check=False)
            save.stdout.close()
            if save.wait() or load.returncode:raise RuntimeError('Offline worker image import failed')
        loaded=json_command(['docker','--host','unix://'+local_socket,'image','inspect',worker])[0]
        if loaded['Id']!=worker:raise RuntimeError('Private worker image identity differs from the verified configuration')
        top={'Processes':[line.strip().split(None,1) for line in output(['docker','top',self.name('executor-daemon'),'-eo','pid,args']).splitlines()[1:]]}
        bindings={}
        for role,program,flag,file in [('daemon','dockerd','--config-file','daemon.json'),('containerd','containerd','--config','containerd.toml')]:
            matches=[p for p in top['Processes'] if program in p[1].split()[0] and flag+' '+self.hp('d/strict/'+file) in p[1]]
            if len(matches)!=1:raise RuntimeError('Cannot identify isolated '+program)
            pid=int(matches[0][0]);details=output(['docker','run','--rm','--pull=never','--pid=host','--network=none','--entrypoint','python3',self.image,'/opt/product/installer.py','process',str(pid)])
            bindings[role]=json.loads(details)
        tools=['read_files','search_codebase','editor','apply_patch','skills','run_commands','submit_and_exit','web_search','web_fetch','web_download','document_search','agent_skill_read','runtime__web_search','runtime__web_fetch','runtime__web_download','runtime__document_search','runtime__agent_skill_read','workspace_read','workspace_metadata','workspace_propose_sql','workspace_submit','workspace_propose_python']
        for i in [1,2]:
            local=ROOT/f'n/{i}';local.mkdir(exist_ok=True)
            for p in ['w','template','state','control']:(local/p).mkdir(exist_ok=True)
            authority={tenant:{'users':[],'dynamicUsers':True,'dynamicModels':True,'models':[],'datasets':[],'workspaces':{'project':self.hp(f'n/{i}/template')},'tools':tools,
                'runtimeModelAuthority':'http://127.0.0.1:8080/internal/runtime/native-coding/model',
                'mcp':{'authMode':'native-runtime','url':'http://127.0.0.1:8080/internal/runtime/native-coding/mcp','tools':['web_search','web_fetch','web_download','document_search','agent_skill_read']}} for tenant in tenants}
            executor={'workerImage':worker,'expectedDaemonId':daemon['ID'],'daemonMode':'dedicated-rootful','dedicatedDaemonRoot':self.hp('d/strict'),'dockerSocket':self.hp('d/strict/run/docker.sock'),'dedicatedProcesses':bindings,
                'workerRuntime':'cline-python311','enableNativeSilo':True,'instanceId':self.project+'-executor-'+str(i),'ownershipKey':self.secret['ownership'+str(i)],'apiToken':self.secret['executor'+str(i)],'signingKey':self.secret['signing'+str(i)],'workersRoot':self.hp(f'n/{i}/w'),'hostWorkersRoot':self.hp(f'n/{i}/w'),'controlRoot':self.hp(f'n/{i}/control'),'socketPath':self.hp(f'n/{i}/executor.sock'),
                'maxWorkers':1,'maxPerTenant':1,'resources':{'memoryBytes':1536*1024**2,'nanoCpus':1000000000,'pidsLimit':256,'cpuSet':''},'tenants':authority,'modelNames':{},'dynamicModels':True,'modelOptions':{'thinking':True,'reasoningEffort':'low'},'operationContext':True,'mcpServerName':'runtime'}
            gateway={'host':'127.0.0.1','port':18451+i*10,'workerImage':worker,'workerRuntime':'cline-python311','signingKey':self.secret['signing'+str(i)],'issuerKeys':{tenant:self.secret['issuerKey'] for tenant in tenants},'stateRoot':self.hp(f'n/{i}/state'),'workerRoot':self.hp(f'n/{i}/w'),'maxWorkers':1,'maxPerTenant':1,'tenants':authority,'models':{},'executor':{'socketPath':executor['socketPath'],'token':executor['apiToken']},'mcpServerName':'runtime',
                's3':{'allowedOrigins':['http://silo:9000'],'maxInputBytes':1024**3,'maxOutputBytes':64*1024**2,'autoApproveAuthorizedTools':True,'scopeLayout':'tenant-task-v1','bucket':'runtime-assets'},'frontendHub':{'url':'http://frontend-resource-hub:8080','token':self.secret['frontendToken']}}
            write_json(local/'executor.json',executor);write_json(local/'gateway.json',gateway)
        for key in ['studioSigning','studioOwnership','studioExecutor']:
            if key not in self.secret:self.secret[key]=secrets.token_hex(24)
        write_json(self.state_path,self.state)
        users=json.loads(output(['docker','exec',self.name('postgresql'),'psql','-U','postgres','-d','live_runtime_api','-Atc',"select coalesce(json_agg(t),'[]') from (select id,username,tenant_id from sys_user) t"]))
        studio_executor=copy.deepcopy(read_json(ROOT/'n/1/executor.json'))
        for directory in ['s/w','s/template','s/control']:(ROOT/'n'/directory).mkdir(parents=True,exist_ok=True)
        studio_executor.update(instanceId=self.project+'-studio',ownershipKey=self.secret['studioOwnership'],apiToken=self.secret['studioExecutor'],signingKey=self.secret['studioSigning'],
            workersRoot=self.hp('n/s/w'),hostWorkersRoot=self.hp('n/s/w'),controlRoot=self.hp('n/s/control'),socketPath=self.hp('n/s/executor.sock'),enableNativeSilo=False,modelNames={'runtime':'runtime'},dynamicModels=False)
        for tenant,policy in studio_executor['tenants'].items():
            policy.update(users=sorted({u[k] for u in users if u['tenant_id']==tenant for k in ['id','username']}),dynamicUsers=False,dynamicModels=False,models=['runtime'],tools=['read_files','search_codebase','skills','editor','apply_patch','submit_and_exit','runtime__*'],workspaces={'project':self.hp('n/s/template')})
        write_json(ROOT/'n/studio-executor.json',studio_executor)
        studio={'host':'127.0.0.1','port':8791,'secret':self.secret['codingSecret'],'workerImage':worker,'workerRuntime':'cline-python311','signingKey':self.secret['studioSigning'],'workersRoot':self.hp('n/s/w'),'stateRoot':self.hp('n/studio-state'),'callbackUrl':'http://127.0.0.1:8080/','executor':{'socketPath':self.hp('n/s/executor.sock'),'token':self.secret['studioExecutor']}}
        write_json(ROOT/'n/studio.json',studio)
        write_json(ROOT/'daemon-binding.json',{'daemonId':daemon['ID'],'processes':bindings,'startedAt':inspect(self.name('executor-daemon'))['State']['StartedAt']})

    def install(self):
        initial=self.state.get('status')!='READY'
        self.check_images();self.prepare();self.render()
        self.up('postgresql','temporal-db','redis','opensearch','silo','executor-daemon')
        for role in ['postgresql','redis','opensearch','silo']:self.wait(role)
        self.schema();self.search_security();self.silo()
        self.up('temporal');self.wait('temporal')
        self.up('news');self.wait('news')
        self.up('mcp');self.wait('mcp')
        self.up('api');self.wait('api')
        self.configure_native()
        self.up('frontend-resource-hub','frontend-worker','executor1','executor2','studio-executor','recovery1','recovery2','node1','node2','studio','worker1','worker2','gateway')
        self.wait('frontend-resource-hub');self.wait('frontend-worker')
        self.wait('gateway')
        self.verify()
        self.state['status']='READY';write_json(self.state_path,self.state)
        self.up('manager')
        print('\nEnvironment ready. HTTP port: '+str(self.state['httpPort'])+'; MCP port: '+str(self.state['mcpPort']))
        if initial:print('Administrator: admin\nInitial password: '+self.state['adminPassword'])
        else:print('Existing administrator credentials and persistent data retained.')
        print('Persistent installation directory: '+str(self.host))

    def verify(self):
        started=inspect(self.name('news'))['State']['StartedAt']
        logs=subprocess.check_output(['docker','logs','--since',started,self.name('news')],stderr=subprocess.STDOUT,text=True)
        if 'news_temporal_embedded_server_started' in logs:raise RuntimeError('News is using an in-memory workflow service instead of the managed persistent Temporal service')
        authenticated=self.state.get('status')!='READY' or bool(os.environ.get('VERIFY_PASSWORD'))
        if authenticated:
            run(['docker','run','--rm','--pull=never','--network',self.project+'_control','--entrypoint','python3',
                '-e','OS_ADMIN_PASSWORD='+(os.environ.get('VERIFY_PASSWORD') or self.state['adminPassword']),
                '-e','INTERNAL_SECRET='+self.secret['internalSecret'],self.image,'/opt/ops-config/verify.py'])
        else:
            for role in ['api','mcp','news','gateway']:self.wait(role)
        # Check the actual restricted Executor, not just container state.
        for i in [1,2,'s']:
            deadline=time.monotonic()+60
            while True:
                try:
                    client=http.client.HTTPConnection('localhost',timeout=5)
                    client.sock=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
                    client.sock.settimeout(5);client.sock.connect(str(ROOT/f'n/{i}/executor.sock'))
                    client.request('GET','/health/live');response=client.getresponse();value=json.loads(response.read());client.close()
                    if response.status!=200 or not value.get('resourceEnforced'):raise RuntimeError('Executor resource policy was not admitted')
                    break
                except (OSError,RuntimeError):
                    if time.monotonic()>deadline:raise RuntimeError('Executor '+str(i)+' has not admitted the daemon policy')
                    time.sleep(2)
        # The execution image must actually start with networking disabled and
        # enforced resources; image presence alone is insufficient acceptance.
        host='unix://'+str(ROOT/'d/strict/run/docker.sock')
        run(['docker','--host',host,'run','--rm','--pull=never','--network=none','--read-only',
            '--cap-drop=ALL','--security-opt','no-new-privileges','--memory','768m','--cpus','1','--pids-limit','128',
            '--tmpfs','/tmp:rw,nosuid,nodev,size=128m','-e','OPENBLAS_NUM_THREADS=1','-e','OMP_NUM_THREADS=1',
            self.worker_image_id(),'bash','-ec',
            'node /opt/runtime-os/coding-node/service/scripts/verify-runtime.mjs /opt/runtime-os/cline/runtime && '
            'node /opt/runtime-os/coding-node/service/docker/verify-native-modules.mjs /opt/runtime-os/cline/runtime && '
            'python /opt/runtime-os/coding-node/service/docker/verify-python-runtime.py'])
        write_json(ROOT/'verification.json',{'status':'PASSED','administratorLogins':['api','mcp'] if authenticated else 'retained-existing-password','privateExecutorDaemon':True,'nativeCoreVerified':True,'pythonRuntimeVerified':True,'offlineImages':True,'productionDataImported':False})

    def supervise(self):
        while True:
            try:
                top={'Processes':[line.strip().split(None,1) for line in output(['docker','top',self.name('executor-daemon'),'-eo','pid,args']).splitlines()[1:]]}
                pids={int(p[0]) for p in top['Processes']}
                old=read_json(ROOT/'daemon-binding.json')
                if inspect(self.name('executor-daemon'))['State']['StartedAt']!=old.get('startedAt') or any(b['pid'] not in pids for b in old['processes'].values()):
                    self.configure_native()
                    run(['docker','restart',self.name('executor1'),self.name('executor2'),self.name('studio-executor')])
            except Exception as error:print('Executor binding reconciliation: '+type(error).__name__,flush=True)
            time.sleep(10)

def silo_init():
    user=(ROOT/'config/silo-user').read_text();password=(ROOT/'config/silo-password').read_text()
    def request(method,path,query='',body=b''):
        now=datetime.datetime.now(datetime.timezone.utc);stamp=now.strftime('%Y%m%dT%H%M%SZ');date=stamp[:8]
        content=hashlib.sha256(body).hexdigest();headers={'host':'silo:9000','x-amz-content-sha256':content,'x-amz-date':stamp}
        names=';'.join(sorted(headers));canonical='\n'.join([method,path,query,''.join(k+':'+headers[k]+'\n' for k in sorted(headers)),names,content])
        scope=date+'/us-east-1/s3/aws4_request';tosign='AWS4-HMAC-SHA256\n'+stamp+'\n'+scope+'\n'+hashlib.sha256(canonical.encode()).hexdigest()
        key=('AWS4'+password).encode()
        for value in [date,'us-east-1','s3','aws4_request']:key=hmac.new(key,value.encode(),hashlib.sha256).digest()
        signature=hmac.new(key,tosign.encode(),hashlib.sha256).hexdigest()
        headers['Authorization']='AWS4-HMAC-SHA256 Credential='+user+'/'+scope+', SignedHeaders='+names+', Signature='+signature
        url='http://silo:9000'+path+('?'+query if query else '')
        with urllib.request.urlopen(urllib.request.Request(url,data=body,headers=headers,method=method),timeout=30) as response:return response.read(),response.headers
    try:request('PUT','/runtime-assets')
    except urllib.error.HTTPError as error:
        if error.code!=409:raise
    request('PUT','/runtime-assets','versioning=',b'<VersioningConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Status>Enabled</Status></VersioningConfiguration>')
    payload=('installation-'+secrets.token_hex(8)).encode();_,headers=request('PUT','/runtime-assets/installation-probe.txt',body=payload)
    if not headers.get('x-amz-version-id'):raise RuntimeError('Silo immutable object versions are not enabled')
    data,_=request('GET','/runtime-assets/installation-probe.txt')
    if data!=payload:raise RuntimeError('Silo object read/write verification failed')
    print('Silo bucket, immutable versions and object read/write verified.')

def main():
    command=sys.argv[1] if len(sys.argv)>1 else 'install'
    if command=='host-identity':bootstrap_identity();return
    if command=='process':
        pid=int(sys.argv[2]);value=Path('/proc')/str(pid)
        print(json.dumps({'pid':pid,'startTicks':(value/'stat').read_text().rsplit(')',1)[1].split()[19]}));return
    if command=='silo-init':silo_init();return
    app=Installation(allow_new=command in ['install','start'])
    if command in ['install','start']:app.install()
    elif command=='supervise':app.supervise()
    elif command=='stop':run(app.cp('stop'))
    elif command=='status':run(app.cp('ps','-a'))
    elif command=='logs':run(app.cp('logs','--tail','100',*sys.argv[2:]))
    elif command=='verify':app.verify()
    else:raise RuntimeError('Use install, start, stop, status, logs or verify')

if __name__=='__main__':
    try:main()
    except Exception as error:
        message=('Docker command failed with exit code '+str(error.returncode)+'; see the output above') if isinstance(error,subprocess.CalledProcessError) else str(error)
        print('Installation failed: '+message,file=sys.stderr)
        sys.exit(1)
