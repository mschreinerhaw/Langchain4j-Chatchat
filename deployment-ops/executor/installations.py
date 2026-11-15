"""Durable installation plans with strict resource ownership and compatibility gates."""
import copy, hashlib, http.client, io, ipaddress, json, os, re, secrets, socket, tarfile, threading, time, uuid
from pathlib import Path
from package_store import atomic, read, require, safe_name, container_path, validate_manifest

ACTIVE={'queued','running','rolling_back','starting','stopping'}
TERMINAL={'succeeded','failed','rolled_back','recovery_required','cancelled','interrupted'}

def stamp(): return time.time()
def fingerprint(value): return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':')).encode()).hexdigest()
def render(value,parameters):
    if isinstance(value,str):
        def sub(match):
            require(match.group(1) in parameters,'Unknown parameter: '+match.group(1))
            v=parameters[match.group(1)];return str(v).lower() if isinstance(v,bool) else str(v)
        return re.sub(r'\$\{([A-Za-z][A-Za-z0-9_]*)\}',sub,value)
    if isinstance(value,list):return [render(v,parameters) for v in value]
    if isinstance(value,dict):return {k:render(v,parameters) for k,v in value.items()}
    return value

def parameters(schema,provided,previous=None):
    require(isinstance(provided,dict) and set(provided)<=set(schema),'Unknown environment parameter')
    result={}
    for key,p in schema.items():
        value=provided.get(key)
        if value is None or (value=='' and p.get('secret')):
            value=(previous or {}).get(key,p.get('default'))
            if value is None and p.get('generate'):value=secrets.token_urlsafe(24)
        if value is None:
            require(not p.get('required'), 'Required parameter: '+key);value='' if p.get('type','string')=='string' else None
        kind=p.get('type','string')
        if kind=='integer':
            require(type(value) is int or (isinstance(value,str) and re.fullmatch(r'-?[0-9]+',value)),'Integer required: '+key);value=int(value)
            require(p.get('min',-2**53)<=value<=p.get('max',2**53),'Parameter outside range: '+key)
        elif kind=='boolean':require(type(value) is bool,'Boolean required: '+key)
        else:
            require(isinstance(value,str) and len(value)<=65536,'Invalid string parameter: '+key)
            if 'pattern' in p:require(re.fullmatch(p['pattern'],value),'Parameter format is invalid: '+key)
        if 'enum' in p:require(value in p['enum'],'Choose an allowed value: '+key)
        result[key]=value
    return result

class Installer:
    def __init__(self,root,store,engine,raw_put,engine_lock,self_id=None):
        self.root=Path(root);self.root.mkdir(parents=True,exist_ok=True);self.store=store;self.engine=engine;self.raw_put=raw_put;self.engine_lock=engine_lock;self.self_id=self_id or socket.gethostname();self.guard=threading.RLock();self.busy=False
        for file in self.root.glob('*/job.json'):
            job=read(file)
            if job['status'] in ACTIVE:job['status']='interrupted';job['message']='Executor restarted. Resume to reconcile owned resources.';atomic(file,job)
    def path(self,ident):
        require(re.fullmatch(r'[a-f0-9]{32}',ident or ''),'Invalid installation ID');return self.root/ident
    def public(self,job):
        value=copy.deepcopy(job);env=self.previous(job['environment']) if job.get('environment') else None;value['active']=bool(env and env.get('activeRun')==job.get('id'));return value
    def list(self):return [self.public(read(f)) for f in sorted(self.root.glob('*/job.json'),key=lambda f:f.parent.name)][::-1]
    def get(self,ident):return self.public(read(self.path(ident)/'job.json'))
    def environment(self,name):return self.root/('env-'+safe_name(name)+'.json')
    def previous(self,name):
        file=self.environment(name);return read(file) if file.exists() else None
    def resolved(self,spec):
        folder,m=self.store.package(spec.get('package'));require(not (folder/'retired.json').exists(),'Package is retired; choose a current verified release');safe_name(spec.get('environment'))
        prior=self.previous(spec['environment']);old_params=read(self.path(prior.get('activeRun') or prior['lastRun'])/'private.json')['parameters'] if prior else None
        params=parameters(m.get('parameters',{}),spec.get('parameters',{}),old_params)
        active=prior.get('activeRun') if prior else None
        if prior:
            require(prior['packageName']==m['name'],'Environment belongs to another package')
            require(not active or self.get(active)['package']==spec['package'] or (prior['version'] in m.get('upgradeFrom',[]) and m.get('dataCompatibility')=='backward-compatible'),'Upgrade is not declared backward compatible with the installed version')
            if active:
                for key,p in m.get('parameters',{}).items():
                    if p.get('secret') and key in (old_params or {}):require(params[key]==old_params[key],'Credential rotation needs its own verified workflow; leave the existing secret unchanged: '+key)
            # Data-bearing jobs may not silently run on an upgrade.
            if active:require(not any(c.get('kind')=='job' and c.get('runOn','install')=='always' and c.get('dataChange',True) for c in m['components']),'A data-changing upgrade needs a separately verified database migration/backup workflow')
            if active:
                _,old_manifest=self.store.package(self.get(active)['package']);old_components={c['id']:c for c in old_manifest['components']}
                for c in m['components']:
                    if c.get('kind')=='job' and c.get('runOn','install')=='install' and c['id'] in old_components:
                        consumed=set(re.findall(r'\$\{([A-Za-z][A-Za-z0-9_]*)\}',json.dumps(c)))
                        for key in consumed:
                            if key in (old_params or {}):require(params[key]==old_params[key],'Initialization setting cannot change on an existing environment: '+key)
                for c in m['components']:
                    if c.get('schemaSensitive') and c['id'] in old_components:
                        old=old_components[c['id']];require(m['images'][c['image']]['id']==old_manifest['images'][old['image']]['id'],'Infrastructure engine image upgrade requires a separate tested data migration: '+c['id'])

        order=validate_manifest(m,read(folder/'receipt.json')['checksums']);components=render(m['components'],params)
        by_id={c['id']:c for c in components};selected=[]
        for key in order:
            c=by_id[key];condition=c.get('when')
            if condition:
                require(set(condition)=={'parameter','equals'} and condition['parameter'] in params,'Invalid component condition')
                if params[condition['parameter']]!=condition['equals']:continue
            if c.get('kind')=='job' and active and c.get('runOn','install')=='install':continue
            selected.append(c)
        return folder,m,params,prior,selected
    def preflight(self,spec):
        checks=[]
        try:
            folder,m,params,prior,components=self.resolved(spec)
            info=self.engine('GET','/info');arch={'x86_64':'amd64','aarch64':'arm64'}.get(info['Architecture'],info['Architecture'])
            require(m['platform']==info.get('OSType','linux')+'/'+arch,'Package CPU/OS differs from target Docker host')
            checks.append({'name':'Platform','status':'passed','detail':m['platform']})
            total_memory=sum(int(c.get('memoryBytes',0)) for c in components)
            declared=max(total_memory,int(m.get('requirements',{}).get('memoryBytes',0)));resident_credit=0
            if prior and prior.get('activeRun'):
                for component in components:
                    if component.get('kind','service')!='service':continue
                    old=self.inspect_optional('/containers/'+self.container_name(spec['environment'],component['id'])+'/json')
                    if old and old['State']['Running']:
                        self.owned(old,spec['environment'])
                        try:
                            sample=self.engine('GET','/containers/'+old['Id']+'/stats?stream=false&one-shot=true').get('memory_stats',{});counters=sample.get('stats',{})
                            # File cache is already reclaimable in MemAvailable; never credit it twice.
                            anon=int(counters.get('total_rss',counters.get('rss',counters.get('anon',0))))
                            resident_credit+=max(0,min(anon,int(sample.get('usage',0)),int(component.get('memoryBytes',0))))
                        except (ValueError,OSError):pass # Missing samples retain the conservative full budget.
            minimum=max(0,declared-resident_credit)
            available=info['MemTotal']
            if Path('/proc/meminfo').exists():
                for line in Path('/proc/meminfo').read_text().splitlines():
                    if line.startswith('MemAvailable:'):available=int(line.split()[1])*1024
            require(minimum<=available,'Not enough available memory for the selected components')
            self.store.free(int(m.get('requirements',{}).get('diskBytes',0)))
            checks.append({'name':'Capacity','status':'passed','detail':{'availableMemoryBytes':available,'requiredMemoryBytes':minimum,'declaredMemoryBytes':declared,'reusedResidentBytes':resident_credit}})
            ports=[]
            for c in components:
                if c.get('kind')!='external':
                    actual=self.engine('GET','/images/'+m['images'][c['image']]['id']+'/json');require(actual['Id']==m['images'][c['image']]['id'],'Missing or changed image')
                    if c.get('health',{}).get('type')=='docker' and not c['health'].get('test'):
                        test=actual.get('Config',{}).get('Healthcheck',{}).get('Test',[]);require(test and test[0]!='NONE','Selected image has no required Docker health probe: '+c['id'])
                for binding in c.get('ports',[]):
                    require(set(binding)<= {'container','host','bind'},'Unknown port binding field')
                    host=int(binding['host']);internal=int(binding['container']);require(1024<=host<=65535 and 0<internal<=65535,'Port outside allowed range');require(host not in ports,'Duplicate host port in plan');ports.append(host)
                    ipaddress.ip_address(binding.get('bind','0.0.0.0'))
                require(0<=int(c.get('memoryBytes',0))<=info['MemTotal'],'Invalid memory limit')
                require(0<float(c.get('cpus',1))<=info['NCPU'],'Invalid CPU limit')
                if c.get('kind')=='external':self.external_health(c['health'])
                name=self.container_name(spec['environment'],c['id'])
                old=self.inspect_optional('/containers/'+name+'/json')
                if old:self.owned(old,spec['environment'])
            occupied={p.get('PublicPort') for c in self.engine('GET','/containers/json?all=1') if c['State']=='running' and (c.get('Labels') or {}).get('deployment-ops.environment')!=spec['environment'] for p in c.get('Ports',[])}
            require(not occupied.intersection(ports),'A requested port is used by an unrelated container')
            # Fixed probe code only, in a short-lived, unprivileged host-network container.
            self.probe_ports([p for p in ports if p not in {p.get('PublicPort') for c in self.engine('GET','/containers/json') if (c.get('Labels') or {}).get('deployment-ops.environment')==spec['environment'] for p in c.get('Ports',[])}])
            for volume in m.get('volumes',{}):
                existing=self.inspect_optional('/volumes/'+self.volume_name(spec['environment'],volume))
                if existing:
                    require((existing.get('Labels') or {}).get('deployment-ops.environment')==spec['environment'] and prior,'Existing volume has no matching installation ownership/journal')
            checks.append({'name':'Images, ports and ownership','status':'passed','detail':str(len(components))+' selected components'})
            public_params={k:v for k,v in params.items() if not m.get('parameters',{}).get(k,{}).get('secret')}
            links=render(m.get('links',[]),params)
            for link in links:require(re.fullmatch(r'https?://[^\s<>"\x27]+',link['url']),'Invalid application link')
            token=fingerprint({'package':spec['package'],'environment':spec['environment'],'parameters':params,'activeRun':(prior or {}).get('activeRun')})
            atomic(self.root/'plans'/(token+'.json'),{'createdAt':stamp(),'package':spec['package'],'environment':spec['environment'],'parameters':params,'activeRun':(prior or {}).get('activeRun')})
            return {'ok':True,'checks':checks,'environment':spec['environment'],'package':spec['package'],'packageName':m['name'],'version':m['version'],'reviewToken':token,'parameters':public_params,'links':links,'components':[{'id':c['id'],'kind':c.get('kind','service'),'dependsOn':c.get('dependsOn',[])} for c in components],'upgrade':bool(prior and prior.get('activeRun'))}
        except Exception as error:
            checks.append({'name':'Preflight','status':'failed','detail':str(error)})
            return {'ok':False,'checks':checks}
    def inspect_optional(self,path):
        try:return self.engine('GET',path)
        except ValueError as e:
            # Never treat daemon/network/authorization errors as absence.
            if 'no such' in str(e).lower() or 'not found' in str(e).lower():return None
            raise
    def owned(self,info,environment):
        labels=(info.get('Config',{}).get('Labels') or info.get('Labels') or {})
        require(labels.get('deployment-ops.environment')==environment and labels.get('deployment-ops.installation')=='true','Refusing to change a resource not owned by this installation')
    def container_name(self,env,component):return 'ops-env-'+safe_name(env)+'-'+safe_name(component)
    def volume_name(self,env,volume):return 'ops-env-'+safe_name(env)+'-'+safe_name(volume)
    def probe_ports(self,ports):
        if not ports:return
        own=self.engine('GET','/containers/'+self.self_id+'/json')
        command=['python','-c','import socket,json,sys; sockets=[]\nfor port in json.loads(sys.argv[1]):\n s=socket.socket(); s.bind(("0.0.0.0",port)); sockets.append(s)\n',json.dumps(ports)]
        config={'Image':own['Image'],'Entrypoint':command,'Cmd':[],'User':'10001:10001','Labels':{'deployment-ops.probe':'true'},'HostConfig':{'NetworkMode':'host','CapDrop':['ALL'],'ReadonlyRootfs':True,'SecurityOpt':['no-new-privileges:true'],'Memory':32*1024**2,'PidsLimit':32}}
        ident=self.engine('POST','/containers/create',json.dumps(config).encode())['Id']
        try:self.engine('POST','/containers/'+ident+'/start');status=self.engine('POST','/containers/'+ident+'/wait?condition=not-running');require(status['StatusCode']==0,'A requested host port is already in use by a host process')
        finally:self.engine('DELETE','/containers/'+ident+'?force=true&v=false')
    def submit(self,spec,actor):
        with self.guard:
            require(not self.busy,'Another installation operation is running on this node')
            token=spec.get('reviewToken','');require(re.fullmatch(r'[a-f0-9]{64}',token),'Preflight review token required')
            plan=read(self.root/'plans'/(token+'.json'));require(stamp()-plan['createdAt']<900,'Preflight expired; review again');require(plan['package']==spec.get('package') and plan['environment']==spec.get('environment'),'Preflight scope mismatch')
            require((self.previous(plan['environment']) or {}).get('activeRun')==plan['activeRun'],'The active installation changed; run preflight again')
            prepared={'package':plan['package'],'environment':plan['environment'],'parameters':plan['parameters']}
            review=self.preflight(prepared);require(review['ok'],'Preflight failed: '+str(review['checks'][-1]['detail']))
            require(spec.get('reviewToken')==review['reviewToken'],'Review is stale; run preflight again')
            folder,m,params,prior,components=self.resolved(prepared)
            ident=uuid.uuid4().hex;job={'id':ident,'environment':spec['environment'],'package':spec['package'],'packageName':m['name'],'version':m['version'],'actor':actor,'createdAt':stamp(),'updatedAt':stamp(),'status':'queued','message':'Waiting for executor','links':review['links'],'previousRun':(prior or {}).get('activeRun'),'parameters':review['parameters'],'steps':[{'id':c['id'],'kind':c.get('kind','service'),'status':'pending'} for c in components]}
            atomic(self.path(ident)/'private.json',{'parameters':params,'components':components});atomic(self.path(ident)/'job.json',job)
            env=prior or {'packageName':m['name'],'version':None,'activeRun':None};env['lastRun']=ident;atomic(self.environment(job['environment']),env)
            self.launch(ident);return self.get(ident)
    def launch(self,ident,rollback=False):
        self.busy=True
        threading.Thread(target=self.work,args=(ident,rollback),daemon=True).start()
    def resume(self,ident):
        with self.guard:
            require(not self.busy,'Node is busy');job=self.get(ident);require(job['status'] in ('interrupted','failed','cancelled'),'Only an interrupted/failed installation can resume')
            require(self.previous(job['environment'])['lastRun']==ident,'A newer installation supersedes this run')
            job['cancelRequested']=False;job['status']='queued';atomic(self.path(ident)/'job.json',job);self.launch(ident);return self.get(ident)
    def cancel(self,ident):
        with self.guard:
            job=self.get(ident);require(job['status'] in ACTIVE,'Installation is not running');job['cancelRequested']=True;atomic(self.path(ident)/'job.json',job);return job
    def save(self,job):
        with self.guard:
            disk=self.get(job['id']);job['cancelRequested']=disk.get('cancelRequested',False);job['updatedAt']=stamp();atomic(self.path(job['id'])/'job.json',job)
    def cancelled(self,job):require(not self.get(job['id']).get('cancelRequested'),'Installation cancelled by operator')
    def labels(self,job,component=None):
        labels={'deployment-ops.installation':'true','deployment-ops.environment':job['environment'],'deployment-ops.package':job['packageName'],'com.docker.compose.project':'ops-env-'+job['environment']}
        if component:labels.update({'deployment-ops.component':component['id'],'deployment-ops.kind':component.get('kind','service'),'deployment-ops.run':job['id']})
        return labels
    def work(self,ident,rollback=False):
        job=self.get(ident)
        try:
            with self.engine_lock:
                folder,m=self.store.package(job['package']);private=read(self.path(ident)/'private.json');components=private['components']
                network='ops-env-'+job['environment'];existing=self.inspect_optional('/networks/'+network)
                if existing:self.owned(existing,job['environment'])
                else:self.engine('POST','/networks/create',json.dumps({'Name':network,'Driver':'bridge','Labels':self.labels(job)}).encode())
                own=self.engine('GET','/containers/'+self.self_id+'/json')
                if network not in own['NetworkSettings']['Networks']:self.engine('POST','/networks/'+network+'/connect',json.dumps({'Container':self.self_id}).encode())
                if rollback in ('stop','start'):self.power(job,components,rollback);return
                if rollback:self.perform_rollback(job,components,manual=True);return
                job['status']='running';job['message']='Preparing owned resources';self.save(job)
                for name in m.get('volumes',{}):
                    value=self.inspect_optional('/volumes/'+self.volume_name(job['environment'],name))
                    if value:self.owned(value,job['environment'])
                    else:self.engine('POST','/volumes/create',json.dumps({'Name':self.volume_name(job['environment'],name),'Labels':self.labels(job)}).encode())
                for component,step in zip(components,job['steps']):
                    self.cancelled(job);job['message']='Installing '+component['id'];step['startedAt']=stamp();self.save(job)
                    if component.get('kind')=='external':self.external_health(component['health']);step['status']='succeeded';self.save(job);continue
                    name=self.container_name(job['environment'],component['id']);current=self.inspect_optional('/containers/'+name+'/json')
                    if current:self.owned(current,job['environment'])
                    peer_id=self.engine('GET','/containers/'+self.container_name(job['environment'],component['networkFrom'])+'/json')['Id'] if component.get('networkFrom') else None
                    desired=fingerprint({'component':component,'image':m['images'][component['image']]['id'],'networkPeer':peer_id,'assets':{asset['source']:read(folder/'receipt.json')['checksums'][asset['source']] for asset in component.get('files',[])}})
                    matches=current and (current['Config'].get('Labels') or {}).get('deployment-ops.config')==desired
                    if step.get('status')=='succeeded' and component.get('kind')=='job':
                        require(current and current['Id']==step.get('container') and current['State'].get('Status')=='exited' and current['State'].get('ExitCode')==0,'Completed initialization evidence is missing');continue
                    if component.get('kind')=='job' and step.get('container') and step.get('status') not in ('creating','configuring') and not component.get('retrySafe',False):
                        state=self.engine('GET','/containers/'+step['container']+'/json')['State']
                        if state.get('Status')=='exited' and state.get('ExitCode')==0:step['status']='succeeded';self.save(job);continue
                        require(False,'Initialization may have changed data. This job is not declared safe to retry; inspect its logs and recover manually.')
                    if current and not matches:
                        require(not step.get('previous') or step['previous']==current['Id'],'Conflicting retained resource journal')
                        step['previous']=current['Id'];step['previousRunning']=current['State']['Running'];step['status']='replacing';self.save(job)
                        if current['State']['Running']:self.engine('POST','/containers/'+current['Id']+'/stop?t=20')
                        self.engine('POST','/containers/'+current['Id']+'/rename?name='+name+'-retained-'+ident[:12]);current=None
                    if not current:
                        config=self.config(job,component,m,network,desired)
                        step['status']='creating';self.save(job)
                        new=self.engine('POST','/containers/create?name='+name,json.dumps(config).encode())['Id'];step['container']=new;step['createdByRun']=True;step['status']='configuring';self.save(job)
                        self.assets(folder,component,private['parameters'],new);step['configured']=True;self.save(job)
                    else:
                        step['container']=current['Id'];step['createdByRun']=step.get('createdByRun',False) or (current['Config'].get('Labels') or {}).get('deployment-ops.run')==ident
                        if (current['Config'].get('Labels') or {}).get('deployment-ops.run')==ident and not step.get('configured'):
                            require(not current['State']['Running'],'Unconfirmed configuration needs operator recovery');self.assets(folder,component,private['parameters'],current['Id']);step['configured']=True;self.save(job)
                    step['status']='starting';self.save(job);status=self.engine('GET','/containers/'+step['container']+'/json')['State']
                    if not status['Running']:
                        step['startedByRun']=True;self.save(job);self.engine('POST','/containers/'+step['container']+'/start')
                    step['status']='checking';self.save(job);self.health(component,step['container'],network,job)
                    step['status']='succeeded';step['completedAt']=stamp();self.save(job)
                atomic(self.environment(job['environment']),{'packageName':job['packageName'],'version':job['version'],'activeRun':ident,'lastRun':ident})
                job['status']='succeeded';job['message']='Installation is ready';self.save(job)
        except Exception as error:
            if rollback in ('stop','start'):
                job['status']='recovery_required';job['message']='Environment control needs recovery: '+str(error);self.save(job);return
            # Error strings never include resolved environment values or Docker config payloads.
            for step in job.get('steps',[]):
                if step['status'] in ('creating','configuring','replacing','starting','checking'):
                    step['status']='cancelled' if job.get('cancelRequested') else 'failed';step['message']=str(error);step['completedAt']=stamp()
            job['message']='Installation stopped: '+str(error);job['failure']=str(error);self.save(job)
            try:
                with self.engine_lock:self.perform_rollback(job,read(self.path(ident)/'private.json')['components'],manual=False)
            except Exception as recovery:
                job['status']='recovery_required';job['message']='Recovery needs operator attention: '+str(recovery);self.save(job)
        finally:
            with self.guard:self.busy=False
    def config(self,job,c,m,network,digest):
        labels=self.labels(job,c);labels['deployment-ops.config']=digest
        environment=c.get('environment',{});require(isinstance(environment,dict) and all(re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*',k) and isinstance(v,str) for k,v in environment.items()),'Invalid container environment')
        mounts=[{'Type':'volume','Source':self.volume_name(job['environment'],v['volume']),'Target':container_path(v['target']),'ReadOnly':bool(v.get('readOnly',False))} for v in c.get('mounts',[])]
        ports={str(v['container'])+'/tcp':[{'HostIp':v.get('bind','0.0.0.0'),'HostPort':str(v['host'])}] for v in c.get('ports',[])}
        config={'Image':m['images'][c['image']]['id'],'Env':[k+'='+v for k,v in environment.items()],'Labels':labels,'ExposedPorts':{p:{} for p in ports},'HostConfig':{'NetworkMode':network,'PortBindings':ports,'Mounts':mounts,'RestartPolicy':{'Name':'no' if c.get('kind')=='job' else 'unless-stopped'},'CapDrop':['ALL'],'CapAdd':c.get('capabilities',[]),'SecurityOpt':['no-new-privileges:true'],'PidsLimit':512,'Memory':int(c.get('memoryBytes',0)),'NanoCpus':int(float(c.get('cpus',1))*10**9)},'NetworkingConfig':{'EndpointsConfig':{network:{'Aliases':[c['id']]}}}}
        if c.get('health',{}).get('type')=='docker' and 'test' in c['health']:
            h=c['health'];require(isinstance(h['test'],list) and h['test'] and h['test'][0] in ('CMD','CMD-SHELL') and all(isinstance(v,str) for v in h['test']),'Invalid Docker health command')
            config['Healthcheck']={'Test':h['test'],'Interval':int(h.get('intervalSeconds',5))*10**9,'Timeout':int(h.get('probeTimeoutSeconds',3))*10**9,'Retries':int(h.get('retries',60)),'StartPeriod':int(h.get('startPeriodSeconds',0))*10**9}
        if c.get('networkFrom'):
            peer=self.engine('GET','/containers/'+self.container_name(job['environment'],c['networkFrom'])+'/json');self.owned(peer,job['environment']);require(not ports,'Shared-namespace containers cannot publish ports');config['HostConfig']['NetworkMode']='container:'+peer['Id'];config.pop('NetworkingConfig');config.pop('ExposedPorts')
        for source,target in [('command','Cmd'),('entrypoint','Entrypoint'),('user','User')]:
            if source in c:config[target]=c[source]
        return config
    def assets(self,folder,c,params,ident):
        if not c.get('files'):return
        stream=io.BytesIO()
        with tarfile.open(fileobj=stream,mode='w') as archive:
            directories=set()
            for asset in c['files']:
                data=(folder/asset['source']).read_bytes();require(len(data)<=16*1024**2,'Config asset exceeds limit')
                if asset.get('template'):data=render(data.decode('utf-8'),params).encode()
                target=container_path(asset['target']).lstrip('/')
                info=tarfile.TarInfo(target);info.size=len(data);info.mode=int(asset.get('mode','0644'),8);info.uid=int(asset.get('uid',0));info.gid=int(asset.get('gid',0));archive.addfile(info,io.BytesIO(data))
        self.raw_put('/containers/'+ident+'/archive?path=/',stream.getvalue())
    def external_health(self,health):
        require(health.get('type')=='tcp','External dependencies require a TCP gate plus a package verification job')
        host=health.get('host');port=int(health['port']);require(isinstance(host,str) and 0<port<=65535,'Invalid external dependency')
        ips=socket.getaddrinfo(host,port,type=socket.SOCK_STREAM);require(ips and all(ipaddress.ip_address(ip[4][0]).is_private for ip in ips),'External dependencies must resolve to private network addresses')
        with socket.create_connection((ips[0][4][0],port),timeout=5):pass
    def health(self,c,ident,network,job):
        health=c.get('health',{});timeout=int(health.get('timeoutSeconds',300));require(1<=timeout<=1800,'Invalid health timeout')
        deadline=stamp()+timeout
        while stamp()<deadline:
            self.cancelled(job);state=self.engine('GET','/containers/'+ident+'/json')
            if c.get('kind')=='job':
                if not state['State']['Running']:require(state['State'].get('ExitCode')==0,'Initialization/verification job failed: '+c['id']);return
            elif state['State']['Running']:
                kind=health['type']
                if kind=='docker':
                    status=state['State'].get('Health',{}).get('Status');require(status is not None,'Image has no Docker health check')
                    if status=='healthy':return
                else:
                    peer=self.engine('GET','/containers/'+self.container_name(job['environment'],c['networkFrom'])+'/json') if c.get('networkFrom') else state
                    ip=peer['NetworkSettings']['Networks'][network]['IPAddress'];port=int(health['port']);require(0<port<=65535,'Invalid health port')
                    try:
                        if kind=='tcp':
                            with socket.create_connection((ip,port),timeout=2):return
                        path=health.get('path','/');require(re.fullmatch(r'/[A-Za-z0-9/_?=.&%-]*',path),'Invalid HTTP health path')
                        connection=http.client.HTTPConnection(ip,port,timeout=2);connection.request('GET',path);response=connection.getresponse();response.read(1024);status=response.status;connection.close()
                        if status in health.get('statuses',list(range(200,300))):return
                    except (OSError,http.client.HTTPException):pass
            elif state['State'].get('Status') in ('exited','dead'):raise ValueError('Service exited before health succeeded: '+c['id'])
            time.sleep(1)
        raise ValueError('Health check timed out: '+c['id'])
    def rollback(self,ident,actor):
        with self.guard:
            require(not self.busy,'Node is busy');job=self.get(ident);require(job['status']=='succeeded' and job.get('previousRun'),'No previous compatible installation available')
            require(self.previous(job['environment'])['activeRun']==ident,'Only the active installation can be rolled back');job['rollbackActor']=actor;job['status']='rolling_back';atomic(self.path(ident)/'job.json',job);self.launch(ident,True);return self.get(ident)
    def perform_rollback(self,job,components,manual):
        previous_status=job['status'];job['status']='rolling_back';self.save(job)
        for c,step in reversed(list(zip(components,job['steps']))):
            if c.get('kind')=='external':continue
            current=self.inspect_optional('/containers/'+self.container_name(job['environment'],c['id'])+'/json')
            if step.get('previous'):
                previous=self.engine('GET','/containers/'+step['previous']+'/json');self.owned(previous,job['environment'])
                if current and current['Id']!=previous['Id']:
                    self.owned(current,job['environment'])
                    if current['State']['Running']:self.engine('POST','/containers/'+current['Id']+'/stop?t=10')
                    self.engine('POST','/containers/'+current['Id']+'/rename?name='+self.container_name(job['environment'],c['id'])+'-failed-'+job['id'][:12]+'-'+uuid.uuid4().hex[:6])
                if previous['Name']!='/'+self.container_name(job['environment'],c['id']):self.engine('POST','/containers/'+previous['Id']+'/rename?name='+self.container_name(job['environment'],c['id']))
                # Services are restarted later in dependency order, after every old name is restored.
            elif current and (step.get('createdByRun') or step.get('startedByRun')):
                self.owned(current,job['environment'])
                if current['State']['Running']:self.engine('POST','/containers/'+current['Id']+'/stop?t=10')
        if job.get('previousRun'):
            old=self.get(job['previousRun']);private=read(self.path(old['id'])/'private.json')
            # Health is checked again in dependency order before declaring recovery successful.
            for c,step in zip(private['components'],old['steps']):
                if c.get('kind') not in ('external','job'):
                    state=self.engine('GET','/containers/'+step['container']+'/json');self.owned(state,job['environment'])
                    if not state['State']['Running']:self.engine('POST','/containers/'+step['container']+'/start')
                    self.health(c,step['container'],'ops-env-'+job['environment'],dict(old,cancelRequested=False))
            atomic(self.environment(job['environment']),{'packageName':old['packageName'],'version':old['version'],'activeRun':old['id'],'lastRun':job['id']})
            job['status']='rolled_back';job['message']='Previous compatible version restored and health checked'
        else:
            job['status']='cancelled' if job.get('cancelRequested') else 'failed';job['message']=job.get('failure','Installation stopped; persistent data retained')
        self.save(job)

    def control(self,ident,action,actor):
        with self.guard:
            require(not self.busy,'Node is busy');job=self.get(ident)
            require(action in ('stop','start') and job['active'],'Only the active owned environment can be controlled')
            require(job['status']==('succeeded' if action=='stop' else 'stopped'),'Environment is not in the expected state')
            job['status']='stopping' if action=='stop' else 'starting';job['message']='Stopping environment' if action=='stop' else 'Starting environment';job['controlActor']=actor;atomic(self.path(ident)/'job.json',job);self.launch(ident,action);return self.get(ident)
    def power(self,job,components,action):
        sequence=list(zip(components,job['steps']))
        if action=='stop':sequence.reverse()
        for c,step in sequence:
            if c.get('kind') in ('job','external'):continue
            job['message']=('Stopping ' if action=='stop' else 'Starting and checking ')+c['id'];self.save(job)
            state=self.engine('GET','/containers/'+step['container']+'/json');self.owned(state,job['environment'])
            if action=='stop' and state['State']['Running']:self.engine('POST','/containers/'+step['container']+'/stop?t=20')
            if action=='start':
                if not state['State']['Running']:self.engine('POST','/containers/'+step['container']+'/start')
                self.health(c,step['container'],'ops-env-'+job['environment'],job)
        job['status']='stopped' if action=='stop' else 'succeeded';job['message']='Environment stopped; volumes retained' if action=='stop' else 'Environment started and verified';self.save(job)
