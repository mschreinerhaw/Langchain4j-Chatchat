"""Adapter for the existing source-built Native/Temporal Runtime OS installer.

Only signed runtime-os-native/v1 releases can enter this privileged adapter.
The product installer remains responsible for its own architecture. Platform
startup, shutdown and recovery never invoke this adapter automatically.
"""
import copy,hashlib,json,os,re,socket,time,uuid
from pathlib import Path
from urllib.parse import urlencode
from package_store import atomic,read,require,safe_name
from installations import parameters,render,stamp
from manager import redact

ACTIVE={'queued','running','starting','stopping','rolling_back'}

class RuntimeOsAdapter:
    def __init__(self,installer):self.base=installer;self.engine=installer.engine
    def health(self,ident):
        import http.client
        job=self.base.get(ident);private=read(self.base.path(ident)/'private.json');target=Path(private['runtimeRoot'])
        require(target.resolve()==self.root(job['environment'],private['parameters']).resolve(),'Runtime OS health scope mismatch')
        probes=[]
        for role in ['1','2','s']:
            started=time.monotonic();status='UNKNOWN';enforced=False
            client=http.client.HTTPConnection('localhost',timeout=1)
            try:
                client.sock=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);client.sock.settimeout(1);client.sock.connect(str(target/'n'/role/'executor.sock'))
                client.request('GET','/health/live');response=client.getresponse();value=json.loads(response.read(65536));enforced=value.get('resourceEnforced') is True
                status='HEALTHY' if response.status==200 and enforced else 'UNHEALTHY'
            except Exception:pass
            finally:client.close()
            probes.append({'name':{'1':'Coding Executor 1','2':'Coding Executor 2','s':'Studio Executor'}[role],'status':status,'resourceEnforced':enforced,'latencyMs':round((time.monotonic()-started)*1000)})
        verified=target/'verification.json';value=read(verified) if verified.is_file() else {}
        return {'jobId':ident,'environment':job['environment'],'sampledAt':stamp(),'executors':probes,'installationVerification':{key:value[key] for key in ('status','nativeCoreVerified','pythonRuntimeVerified','privateExecutorDaemon','offlineImages') if key in value},'verifiedAt':verified.stat().st_mtime if verified.exists() else None}
    def root(self,environment,params):
        base=Path(os.getenv('OPS_RUNTIME_ROOT','/mnt/disk01/ops/r')).resolve()
        require(Path('/mnt/disk01') in base.parents,'Runtime OS root must remain below required mount')
        namespace=hashlib.sha256(str(self.base.root).encode()).hexdigest()[:4]
        name=params.get('directoryName') or hashlib.sha256((str(self.base.root)+environment).encode()).hexdigest()[:12]
        require(re.fullmatch('[a-z0-9][a-z0-9-]{0,15}',name),'Use a short deployment directory name, max 16 characters')
        target=base/(namespace+'-'+name)
        require(base in target.resolve().parents,'Runtime OS directory escapes its allowed root')
        require(len(str(target/'n/1/w'/('0'*36)/'bridge/broker.sock').encode())<=107,'Runtime OS deployment directory exceeds Unix socket path limit')
        return target
    def image_map(self,folder,manifest):
        mapping=read(folder/manifest['runtimeInstaller']['imageMap'])
        pins={image['id'] for image in manifest['images'].values()}
        require(isinstance(mapping,dict) and mapping,'Runtime OS image map is empty')
        require({'api','mcp','news','node','gateway','postgres','redis','search','silo','temporal','daemon','frontend'}<=set(mapping),'Native Runtime OS image map omits a required product role')
        for role,item in mapping.items():
            require(isinstance(item,dict) and item.get('imageId') in pins,'Native installer image map contains an unsigned image identity')
            require(re.fullmatch('[A-Za-z0-9][A-Za-z0-9._/:@-]{0,250}',item.get('reference','')),'Invalid native Runtime OS image reference')
        return mapping
    def resolved(self,spec):
        folder,m=self.base.store.package(spec.get('package'));require(m.get('runtimeInstaller',{}).get('protocol')=='runtime-os-native/v1','Unsupported Runtime OS protocol')
        require(not (folder/'retired.json').exists(),'Package is retired; choose a current verified release')
        safe_name(spec.get('environment'));prior=self.base.previous(spec['environment']);old={}
        if prior:old=read(self.base.path(prior.get('activeRun') or prior['lastRun'])/'private.json')['parameters']
        params=parameters(m.get('parameters',{}),spec.get('parameters',{}),old)
        target=self.root(spec['environment'],params);project='ops-env-'+spec['environment'];require(len(project)<=40,'Runtime OS environment name must contain at most 32 characters')
        if prior:
            require(prior['packageName']=='runtime-os','Environment is owned by another application')
            old_private=read(self.base.path(prior.get('activeRun') or prior['lastRun'])/'private.json')
            require(str(target)==old_private['runtimeRoot'],'Changing an existing deployment directory requires a separate data migration')
            for key,field in m.get('parameters',{}).items():
                if field.get('secret') and key in old:require(params[key]==old[key],'Credential rotation requires a separately verified workflow: '+key)
            if prior.get('activeRun'):
                _,previous=self.base.store.package(self.base.get(prior['activeRun'])['package'])
                require(prior['version']!=m['version'] or self.base.get(prior['activeRun'])['package']==spec['package'],'An immutable version already belongs to another package; publish a new version')
                require(previous.get('runtimeInstaller'),'A legacy SDK installation cannot be adopted as a native Runtime OS deployment')
                require(prior['version']==m['version'] or (prior['version'] in m.get('upgradeFrom',[]) and m.get('dataCompatibility')=='backward-compatible'),'Release does not declare a compatible upgrade path')
                require(previous['runtimeInstaller']['schemaHashes']==m['runtimeInstaller']['schemaHashes'],'Database schema changes require a separately tested migration package')
                old_map=self.image_map(self.base.store.package(self.base.get(prior['activeRun'])['package'])[0],previous)
                new_map=self.image_map(folder,m)
                for role in ['postgres','temporal','redis','search','silo','daemon']:
                    require(old_map.get(role,{}).get('imageId')==new_map.get(role,{}).get('imageId'),'Infrastructure engine upgrade requires a separate data migration: '+role)
        if target.exists() and any(target.iterdir()):
            require(prior and (target/'installation.json').is_file(),'An existing directory has no matching ownership journal')
            require(read(target/'installation.json')['project']==project,'Runtime OS project ownership mismatch')
        mapping=self.image_map(folder,m)
        return folder,m,params,prior,target,project,mapping
    def preflight(self,spec):
        checks=[]
        try:
            require(os.getenv('OPS_NATIVE')=='1','Native Runtime OS releases require a host executor')
            require(Path('/mnt/disk01').is_mount(),'Required real data mount unavailable')
            folder,m,params,prior,target,project,mapping=self.resolved(spec)
            if prior and prior.get('lastRun'):
                previous_job=self.base.get(prior['lastRun'])
                if previous_job.get('runtimeContainer') and previous_job['status'] in ('interrupted','recovery_required'):
                    info=self.engine('GET','/containers/'+previous_job['runtimeContainer']+'/json')
                    require(not info['State']['Running'],'An interrupted installer is still running; resume its existing task instead of submitting another deployment')
            info=self.engine('GET','/info');require(info.get('OSType')=='linux' and info.get('Architecture') in ('x86_64','amd64'),'Native Runtime OS requires Linux/amd64')
            available=next(int(line.split()[1])*1024 for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemAvailable:'))
            required=512*1024**2 if prior and prior.get('activeRun') else int(m.get('requirements',{}).get('memoryBytes',8*1024**3))
            require(available>=required+512*1024**2,'Insufficient available host memory; existing business workloads are never stopped to free memory')
            self.base.store.free(int(m.get('requirements',{}).get('diskBytes',8*1024**3)))
            checks.append({'name':'Host and capacity','status':'passed','detail':{'availableMemoryBytes':available,'requiredMemoryBytes':required}})
            for image in m['images'].values():require(self.engine('GET','/images/'+image['id']+'/json')['Id']==image['id'],'A signed offline image is missing')
            for item in mapping.values():
                try:current=self.engine('GET','/images/'+item['reference']+'/json')
                except ValueError as error:
                    if any(message in str(error).lower() for message in ('no such image','not found')):continue
                    raise
                require(current['Id']==item['imageId'],'An existing image tag belongs to another image; it will not be overwritten')
            own=[c for c in self.engine('GET','/containers/json?all=1') if (c.get('Labels') or {}).get('com.docker.compose.project')==project]
            require(not own or prior,'Existing project containers have no corresponding management ownership record')
            occupied={p.get('PublicPort') for c in self.engine('GET','/containers/json?all=1') if (c.get('Labels') or {}).get('com.docker.compose.project')!=project and c['State']=='running' for p in c.get('Ports',[])}
            ports=[int(params['httpPort']),int(params['mcpPort'])];require(len(set(ports))==2 and all(1024<=port<=65535 for port in ports) and not occupied.intersection(ports),'Invalid or occupied Runtime OS application ports')
            own_ports={p.get('PublicPort') for container in own if container['State']=='running' for p in container.get('Ports',[])}
            self.base.probe_ports([port for port in ports if port not in own_ports])
            checks.append({'name':'Offline images, ports and ownership','status':'passed','detail':str(len(m['images']))+' immutable offline images; project '+project})
            checks.append({'name':'Approved privileged application adapter','status':'passed','detail':'Native Runtime OS installer uses Docker administration to create only project '+project+' and its dedicated execution daemon. The Deployment Ops Java process remains native. Data: '+str(target)})
            token=uuid.uuid4().hex+uuid.uuid4().hex
            atomic(self.base.root/'plans'/(token+'.json'),{'createdAt':stamp(),'package':spec['package'],'environment':spec['environment'],'parameters':params,'activeRun':(prior or {}).get('activeRun'),'adapter':'runtime-os-native/v1'})
            public={key:value for key,value in params.items() if not m.get('parameters',{}).get(key,{}).get('secret')}
            return {'ok':True,'checks':checks,'environment':spec['environment'],'package':spec['package'],'packageName':m['name'],'version':m['version'],'reviewToken':token,'parameters':public,'links':render(m.get('links',[]),params),'components':[{'id':name,'kind':'service','dependsOn':[]} for name in m['runtimeInstaller']['services']],'upgrade':bool(prior and prior.get('activeRun')),'deploymentDirectory':str(target),'orchestration':'runtime-os-native/v1'}
        except Exception as error:
            return {'ok':False,'checks':checks+[{'name':'Preflight','status':'failed','detail':str(error)}]}
    def submit(self,spec,actor):
        with self.base.guard:
            token=spec.get('reviewToken','');require(re.fullmatch('[a-f0-9]{64}',token),'Preflight token required')
            plan=read(self.base.root/'plans'/(token+'.json'));require(plan.get('adapter')=='runtime-os-native/v1' and stamp()-plan['createdAt']<900,'Native Runtime OS review expired')
            if plan.get('submittedJob'):
                require(plan.get('submittedActor')==actor,'Preflight token was consumed by another actor');return self.base.get(plan['submittedJob'])
            require(not self.base.busy,'Node is busy')
            require(plan['package']==spec['package'] and plan['environment']==spec['environment'],'Reviewed scope mismatch')
            require((self.base.previous(plan['environment']) or {}).get('activeRun')==plan['activeRun'],'Active deployment changed; run preflight again')
            resolved={'package':plan['package'],'environment':plan['environment'],'parameters':plan['parameters']};folder,m,params,prior,target,project,mapping=self.resolved(resolved)
            review=self.preflight(resolved);require(review['ok'],'Preflight changed; review target conditions again')
            ident=uuid.uuid4().hex;job={'id':ident,'environment':spec['environment'],'package':spec['package'],'packageName':m['name'],'version':m['version'],'actor':actor,'createdAt':stamp(),'updatedAt':stamp(),'status':'queued','message':'Waiting for Native Runtime OS installer','links':review['links'],'previousRun':(prior or {}).get('activeRun'),'parameters':review['parameters'],'steps':[{'id':name,'kind':'service','status':'pending'} for name in m['runtimeInstaller']['services']],'runtimeAdapter':'runtime-os-native/v1'}
            atomic(self.base.path(ident)/'private.json',{'parameters':params,'runtimeRoot':str(target),'project':project})
            atomic(self.base.path(ident)/'job.json',job)
            env=prior or {'packageName':m['name'],'version':None,'activeRun':None};env['lastRun']=ident;atomic(self.base.environment(job['environment']),env)
            plan.update(submittedJob=ident,submittedActor=actor);atomic(self.base.root/'plans'/(token+'.json'),plan)
            self.base.launch(ident);return self.base.get(ident)
    def redact(self,text,private,target):
        secrets=list(private['parameters'].values())
        if (target/'installation.json').exists():
            state=read(target/'installation.json');secrets+=list(state.get('secrets',{}).values())+[state.get('adminPassword','')]
        for value in secrets:
            if isinstance(value,str) and len(value)>=8:text=text.replace(value,'[REDACTED]')
        return redact(text)[-65536:]
    def update_components(self,job,project):
        containers=[c for c in self.engine('GET','/containers/json?all=1') if (c.get('Labels') or {}).get('com.docker.compose.project')==project]
        by_name={(c.get('Labels') or {}).get('com.docker.compose.service'):c for c in containers}
        for step in job['steps']:
            current=by_name.get(step['id'])
            if current:
                step['container']=current['Id'];step['message']=current.get('Status','');step['status']='checking' if current['State']=='running' else 'stopped' if job['status']=='stopping' else 'starting'
                if current['State']=='running' and '(healthy)' in current.get('Status',''):step['status']='succeeded'
        self.base.save(job)
    def aliases(self,mapping):
        for item in mapping.values():
            try:present=self.engine('GET','/images/'+item['reference']+'/json');require(present['Id']==item['imageId'],'Image tag changed after approval')
            except ValueError as error:
                if 'tag changed' in str(error):raise
                if not any(message in str(error).lower() for message in ('no such image','not found')):raise
                repository,separator,tag=item['reference'].rpartition(':');require(separator and repository and tag,'An explicit release image tag is required')
                self.engine('POST','/images/'+item['imageId']+'/tag?'+urlencode({'repo':repository,'tag':tag}))
    def run(self,job,manifest,private,command,reuse=True,package=None,ignore_cancel=False):
        target=Path(private['runtimeRoot']);target.mkdir(parents=True,exist_ok=True);project=private['project'];folder,_=self.base.store.package(package or job['package']);self.aliases(self.image_map(folder,manifest))
        ident=job.get('runtimeContainer') if reuse else None;existing=None
        if ident:
            try:existing=self.engine('GET','/containers/'+ident+'/json')
            except ValueError:ident=None
            if existing and not existing['State']['Running']:ident=None
        if not ident:
            labels={'deployment-ops.installation':'true','deployment-ops.environment':job['environment'],'deployment-ops.component':'native-installer','deployment-ops.kind':'job','deployment-ops.run':job['id']}
            socket_path=os.getenv('DOCKER_SOCKET','/var/run/docker.sock')
            container_name=project+'-installer-'+job['id'][:10]+'-'+uuid.uuid4().hex[:6]
            environment={'HOSTNAME':container_name,'PROJECT':project,'HTTP_PORT':str(private['parameters']['httpPort']),'MCP_PORT':str(private['parameters']['mcpPort']),'ADMIN_PASSWORD':private['parameters']['osAdminPassword']}
            config={'Image':manifest['images'][manifest['runtimeInstaller']['image']]['id'],'Cmd':[command],'Env':[key+'='+value for key,value in environment.items()],'Labels':labels,'User':'0:0','HostConfig':{'NetworkMode':'host','Binds':[socket_path+':/var/run/docker.sock',str(target)+':/install'],'RestartPolicy':{'Name':'no'},'Memory':512*1024**2,'PidsLimit':128}}
            ident=self.engine('POST','/containers/create?'+urlencode({'name':container_name}),json.dumps(config).encode())['Id'];job['runtimeContainer']=ident;self.base.save(job);self.engine('POST','/containers/'+ident+'/start')
        started=stamp();last_log=0
        while stamp()-started<3600:
            state=self.engine('GET','/containers/'+ident+'/json');self.update_components(job,project)
            if stamp()-last_log>=6:
                logs=self.engine('GET','/containers/'+ident+'/logs?stdout=1&stderr=1&timestamps=1&tail=100')
                job['installerLog']=self.redact(logs.get('text','') if isinstance(logs,dict) else str(logs),private,target);self.base.save(job);last_log=stamp()
            if not ignore_cancel and self.base.get(job['id']).get('cancelRequested'):
                self.engine('POST','/containers/'+ident+'/stop?t=20');raise ValueError('Installer cancelled; already created application resources and data retained')
            if not state['State']['Running']:
                logs=self.engine('GET','/containers/'+ident+'/logs?stdout=1&stderr=1&timestamps=1&tail=150');job['installerLog']=self.redact(logs.get('text','') if isinstance(logs,dict) else str(logs),private,target)
                self.base.save(job);require(state['State'].get('ExitCode')==0,'Native Runtime OS installer failed; inspect retained task logs and component state');return
            time.sleep(2)
        raise ValueError('Runtime OS installer timed out; retained installer container requires explicit inspection')
    def work(self,ident,action=False):
        job=self.base.get(ident);private=read(self.base.path(ident)/'private.json');target=Path(private['runtimeRoot']);snapshot=self.base.path(ident)/'runtime-state.before.json'
        try:
            with self.base.engine_lock:
                folder,m=self.base.store.package(job['package'])
                if action is True:return self.perform_rollback(job,private)
                if action in ('start','stop'):
                    self.run(job,m,private,action,reuse=False);job['status']='stopped' if action=='stop' else 'succeeded';job['message']='Native Runtime OS '+action+' completed; persistent data retained';job.pop('requestedControl',None)
                    if action=='stop':
                        for step in job['steps']:step['status']='stopped'
                    self.base.save(job);return
                job['status']='running';job['message']='Installing Native/Temporal Runtime OS using its source-built installer';self.base.save(job)
                if job.get('previousRun') and (target/'installation.json').exists():
                    if not snapshot.exists():atomic(snapshot,read(target/'installation.json'))
                    state=read(target/'installation.json');state['imageIdentities']={role:item['imageId'] for role,item in self.image_map(folder,m).items()};state['httpPort']=int(private['parameters']['httpPort']);state['mcpPort']=int(private['parameters']['mcpPort']);atomic(target/'installation.json',state)
                self.run(job,m,private,'install')
                state=read(target/'installation.json');require(state['project']==private['project'] and state['status']=='READY','Native installer has not verified application readiness')
                verification=read(target/'verification.json');require(verification['status']=='PASSED' and verification.get('nativeCoreVerified') and verification.get('pythonRuntimeVerified'),'Native Core/Python verification is incomplete')
                for step in job['steps']:step['status']='succeeded'
                atomic(self.base.environment(job['environment']),{'packageName':'runtime-os','version':job['version'],'activeRun':job['id'],'lastRun':job['id']})
                job['status']='succeeded';job['message']='Native Runtime OS administrator, Temporal, Core, Python and executor checks passed';self.base.save(job)
        except Exception as error:
            job['failure']=str(error);job['message']=str(error)
            settled=False
            try:settled=not self.engine('GET','/containers/'+job['runtimeContainer']+'/json')['State']['Running']
            except Exception:pass
            if action is not False or not settled:
                job['status']='recovery_required';job['message']='Installer outcome requires reconciliation; no automatic replay or rollback: '+str(error);self.base.save(job)
            elif job.get('previousRun'):
                try:
                    with self.base.engine_lock:self.perform_rollback(job,private)
                except Exception as recovery:job['status']='recovery_required';job['message']='Rollback requires operator inspection: '+str(recovery);self.base.save(job)
            else:job['status']='cancelled' if job.get('cancelRequested') else 'failed';self.base.save(job)
        finally:
            with self.base.guard:self.base.busy=False
    def perform_rollback(self,job,private):
        previous=self.base.get(job['previousRun']);folder,m=self.base.store.package(previous['package']);old_private=read(self.base.path(previous['id'])/'private.json');target=Path(private['runtimeRoot'])
        require(old_private['runtimeRoot']==str(target),'Rollback scope differs from deployment root')
        snapshot=self.base.path(job['id'])/'runtime-state.before.json';require(snapshot.is_file(),'Retained pre-upgrade Runtime OS state is missing')
        monitor_existing=False
        if job.get('rollbackRequested') and job.get('runtimeContainer'):
            monitor_existing=self.engine('GET','/containers/'+job['runtimeContainer']+'/json')['State']['Running']
        if not monitor_existing:atomic(target/'installation.json',read(snapshot))
        job['status']='rolling_back';job['rollbackRequested']=True;job['message']='Restoring the previous compatible native image set';job['failedInstallerLog']=job.get('installerLog','');self.base.save(job)
        self.run(job,m,old_private,'install',reuse=monitor_existing,package=previous['package'],ignore_cancel=True)
        atomic(self.base.environment(job['environment']),{'packageName':'runtime-os','version':previous['version'],'activeRun':previous['id'],'lastRun':job['id']})
        job['status']='rolled_back';job['rollbackRequested']=False;job['message']='Previous Native Runtime OS version restored and verified; application data retained';self.base.save(job)
    def rollback(self,ident,actor):
        with self.base.guard:
            require(not self.base.busy,'Node is busy');job=self.base.get(ident);require(job['active'] and job['status']=='succeeded' and job.get('previousRun'),'Only an active compatible upgrade can be rolled back')
            job['status']='rolling_back';job['rollbackActor']=actor;atomic(self.base.path(ident)/'job.json',job);self.base.launch(ident,True);return self.base.get(ident)
    def control(self,ident,action,actor):
        with self.base.guard:
            require(not self.base.busy,'Node is busy');job=self.base.get(ident);require(job['active'] and action in ('stop','start'),'Only the active native environment can be controlled')
            require(job['status']==('succeeded' if action=='stop' else 'stopped'),'Unexpected application state')
            job['status']='stopping' if action=='stop' else 'starting';job['controlActor']=actor;job['requestedControl']=action;atomic(self.base.path(ident)/'job.json',job);self.base.launch(ident,action);return self.base.get(ident)
    def resume(self,ident):
        with self.base.guard:
            require(not self.base.busy,'Node is busy');job=self.base.get(ident)
            require(job['status'] in ('interrupted','recovery_required','failed','cancelled'),'This task cannot be resumed')
            require(self.base.previous(job['environment'])['lastRun']==ident,'A newer task supersedes this installer')
            job['cancelRequested']=False;job['status']='queued';atomic(self.base.path(ident)/'job.json',job)
            self.base.launch(ident,True if job.get('rollbackRequested') else job.get('requestedControl',False));return self.base.get(ident)
