"""Create the portable Runtime OS installation definition from tracked product assets.

Run on the release Docker builder. Image map/signing key are explicit CI inputs;
no running configuration, credentials or customer databases are exported.
"""
import argparse
import ipaddress
import json
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository',required=True,type=Path)
    parser.add_argument('--image-map',required=True,type=Path)
    parser.add_argument('--version',required=True)
    parser.add_argument('--output-dir',required=True,type=Path)
    args=parser.parse_args();repo=args.repository.resolve();out=args.output_dir.resolve()
    assert not out.exists(),'A release definition must be immutable'
    images=json.loads(args.image_map.read_text(encoding='utf-8-sig'))
    required={'api','mcp','news','coding-agent','gateway','postgres','redis','opensearch','python'}
    assert required <= set(images),'Explicit business and dependency images are required'
    out.mkdir(parents=True);assets=out/'assets';assets.mkdir()
    source=repo/'deploy/docker/application'
    with tempfile.TemporaryDirectory(prefix='runtime-os-definition-') as temp:
        stage=Path(temp)
        (stage/'config').mkdir()
        for name in ['api.env','mcp.env','news.env']:(stage/'config'/name).write_text('')
        environment={'STAGE_ROOT':str(stage),'API_IMAGE':images['api'],'MCP_IMAGE':images['mcp'],'NEWS_IMAGE':images['news'],'NODE_IMAGE':images['coding-agent'],'NODE_PROXY_IMAGE':images['gateway'],
            'API_DATA_DIR':str(stage/'data/api'),'MCP_DATA_DIR':str(stage/'data/mcp'),'NEWS_DATA_DIR':str(stage/'data/news'),'NEWS_CONFIG_DIR':str(stage/'config/news'),'MODEL_CONFIG_FILE':str(stage/'config/api/application.yml'),
            'API_INTERNAL_KEY_FILE':str(stage/'config/shared/internal-credential.key'),'MCP_INTERNAL_KEY_FILE':str(stage/'config/shared/internal-credential.key'),'CODING_AGENT_SHARED_SECRET_FILE':str(stage/'secrets/coding-shared'),'CODING_AGENT_NODE_SECRET_FILE':str(stage/'secrets/coding-shared'),
            'MCP_HOST_MAC_FILE':'/sys/class/net/lo/address','RUNTIME_HTTP_PORT':'18100','MCP_HTTP_PORT':'18101'}
        env=stage/'deployment.env';env.write_text('\n'.join(k+'='+v for k,v in environment.items())+'\n')
        process=subprocess.run(['docker','compose','--env-file',str(env),'-f',str(source/'compose.yaml'),'config','--format','json'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,timeout=30)
        if process.returncode:raise RuntimeError('Cannot resolve reviewed application Compose; no live environment is used')
        compose=json.loads(process.stdout)
        def portable(value):
            if isinstance(value,str):return value.replace(str(stage),'${INSTALL_ROOT}').replace('/sys/class/net/lo/address','${HOST_MAC_FILE}')
            if isinstance(value,list):return [portable(item) for item in value]
            if isinstance(value,dict):return {key:portable(item) for key,item in value.items()}
            return value
        compose=portable(compose)
    compose.pop('name',None)
    secrets=compose.pop('secrets',{})
    for name,service in compose['services'].items():
        service.pop('env_file',None);service.pop('extra_hosts',None)
        service['image']='@image:'+('gateway' if name=='node-proxy' else name)
        service['restart']='on-failure:3';service.setdefault('environment',{})
        for secret in service.pop('secrets',[]):
            source_record=secrets[secret['source']]
            target=secret.get('target',secret['source'])
            if not target.startswith('/'):target='/run/secrets/'+target
            service.setdefault('volumes',[]).append({'type':'bind','source':source_record['file'],'target':target,'read_only':True})
        for mount in service.get('volumes',[]):
            if mount['type']=='bind':
                if mount['source']=='/var/run/docker.sock':mount['source']='/run/docker.sock'
                mount.pop('bind',None)
        service.pop('ports',None)
    # Preserve the product's isolated Node network, allocating addresses at install time.
    network=compose['networks']['coding'];old_subnet=ipaddress.ip_network(network['ipam']['config'][0]['subnet'])
    addresses={}
    for service in compose['services'].values():
        data=service.get('networks',{}).get('coding')
        if data and data.get('ipv4_address'):
            address=data['ipv4_address'];offset=int(ipaddress.ip_address(address))-int(old_subnet.network_address);addresses[address]='${CODING_IP_'+str(offset)+'}'
    old=str(old_subnet)
    def dynamic_network(value):
        if isinstance(value,str):
            if value==old:return '${CODING_SUBNET}'
            for address,replacement in addresses.items():value=value.replace(address,replacement)
            return value
        if isinstance(value,list):return [dynamic_network(item) for item in value]
        if isinstance(value,dict):return {key:dynamic_network(item) for key,item in value.items()}
        return value
    compose=dynamic_network(compose)
    for network in compose['networks'].values():network.pop('name',None)
    for name in ['api','mcp','news']:
        service=compose['services'][name]
        service['environment']['SPRING_CONFIG_ADDITIONAL_LOCATION']='optional:file:/srv/app/config/'
        service['volumes']=[mount for mount in service.get('volumes',[]) if mount.get('target')!='/srv/app/config']
        if name=='mcp':service['volumes'].append({'type':'bind','source':'${INSTALL_ROOT}/config/mcp/application.yml','target':'/srv/app/config/application.yml','read_only':True})
        if name=='news':service['volumes'].append({'type':'bind','source':'${INSTALL_ROOT}/config/news/application.yml','target':'/srv/app/config/application.yml','read_only':True})
        service['volumes']=[mount for mount in service['volumes'] if mount.get('target')!='/srv/app/config/internal-credential.key']
        service['volumes'].append({'type':'bind','source':'${INSTALL_ROOT}/config/shared/internal-credential.key','target':'/srv/app/config/internal-credential.key','read_only':True})
    for name,database in [('api','runtime_os_api'),('mcp','runtime_os_mcp')]:
        env=compose['services'][name]['environment'];prefix='CHATCHAT_'+name.upper()+'_POSTGRESQL_'
        env.update({prefix+'URL':'jdbc:postgresql://postgres:5432/'+database,prefix+'USERNAME':'runtime_owner',prefix+'PASSWORD':'${database_password}', 'CHATCHAT_DATASOURCE_CONFIG':'datasource-postgresql.yml'})
        compose['services'][name]['depends_on']={'postgres':{'condition':'service_healthy'}}
    compose['services']['api']['environment'].update({'CHATCHAT_SEARCH_OPENSEARCH_URL':'http://opensearch:9200','CHATCHAT_SEARCH_OPENSEARCH_USERNAME':'','CHATCHAT_SEARCH_OPENSEARCH_PASSWORD':'','CHATCHAT_SEARCH_OPENSEARCH_INDEX':'${PROJECT}-documents'})
    compose['services']['api']['depends_on']['opensearch']={'condition':'service_healthy'}
    compose['services']['gateway']['ports']=[{'target':8080,'published':'${http_port}','host_ip':'${PUBLIC_BIND}','protocol':'tcp'},{'target':8090,'published':'${mcp_port}','host_ip':'${PUBLIC_BIND}','protocol':'tcp'}]
    compose['services'].update({
      'postgres':{'image':'@image:postgres','restart':'on-failure:3','cpus':1,'mem_limit':'768m','environment':{'POSTGRES_USER':'runtime_owner','POSTGRES_PASSWORD':'${database_password}','POSTGRES_DB':'runtime_os_bootstrap'},'volumes':[{'type':'bind','source':'${INSTALL_ROOT}/data/postgres','target':'/var/lib/postgresql'},{'type':'bind','source':'${INSTALL_ROOT}/init/databases.sql','target':'/docker-entrypoint-initdb.d/00-runtime.sql','read_only':True}],'networks':{'control':{}},'healthcheck':{'test':['CMD-SHELL','pg_isready -h 127.0.0.1 -U runtime_owner -d runtime_os_mcp'],'interval':'5s','timeout':'3s','retries':60}},
      'redis':{'image':'@image:redis','restart':'on-failure:3','cpus':0.5,'mem_limit':'128m','command':['redis-server','--appendonly','yes'],'volumes':[{'type':'bind','source':'${INSTALL_ROOT}/data/redis','target':'/data'}],'networks':{'control':{}},'healthcheck':{'test':['CMD','redis-cli','ping'],'interval':'5s','timeout':'3s','retries':20}},
      'opensearch':{'image':'@image:opensearch','restart':'on-failure:3','cpus':1,'mem_limit':'1536m','environment':{'discovery.type':'single-node','DISABLE_SECURITY_PLUGIN':'true','DISABLE_INSTALL_DEMO_CONFIG':'true','OPENSEARCH_JAVA_OPTS':'-Xms512m -Xmx512m'},'volumes':[{'type':'bind','source':'${INSTALL_ROOT}/data/opensearch','target':'/usr/share/opensearch/data'}],'networks':{'control':{}},'healthcheck':{'test':['CMD-SHELL','curl -fsS http://127.0.0.1:9200/_cluster/health >/dev/null'],'interval':'10s','timeout':'5s','retries':60}}
    })
    # Include tracked empty-database schemas; never query or dump a customer database.
    init=assets/'templates/databases.sql';init.parent.mkdir(parents=True)
    api=(repo/'database/init/postgresql/chatchat-api.sql').read_text(encoding='utf-8')
    mcp=(repo/'database/init/postgresql/chatchat-mcp-server.sql').read_text(encoding='utf-8')
    post=(repo/'database/init/postgresql/chatchat-api-post-schema.sql').read_text(encoding='utf-8')
    runtime_image=json.loads(subprocess.check_output(['docker','image','inspect',images['python']],text=True))[0]['Id']
    assert runtime_image.startswith('sha256:') and len(runtime_image)==71
    python_seed="\nINSERT INTO mcp_python_environment (id,name,description,docker_image,python_version,cpu_limit,memory_limit,disk_limit,tmpfs_limit,runtime_user,network_policy,requirements_json,timeout_seconds,network_enabled,version_number,status,created_at,updated_at) VALUES ('runtime-python','Python 3.11','Offline installation runtime','"+runtime_image+"','3.11','1','1g','2g','512m','10001:10001','NONE','[]',300,false,1,'PUBLISHED',now(),now());\n"
    init.write_text('CREATE DATABASE runtime_os_api;\nCREATE DATABASE runtime_os_mcp;\n\\connect runtime_os_api\n'+api+'\n'+post+'\n\\connect runtime_os_mcp\n'+mcp+python_seed,encoding='utf-8')
    application={'chatchat':{'internal-credential':{'enabled':True,'username':'chatchat_mcp_internal','encrypted-secret':'${internal_cipher}','crypto-key-file':'/srv/app/config/internal-credential.key'}}}
    # JSON is valid YAML and preserves escaped credentials without a YAML library.
    (assets/'templates/application.yml').write_text(json.dumps(application),encoding='utf-8')
    (assets/'templates/internal.key.txt').write_text('${internal_key}',encoding='utf-8')
    (assets/'templates/coding-secret.txt').write_text('${coding_secret}',encoding='utf-8')
    for file in ['public-gateway.conf','node-proxy.conf']:shutil.copyfile(source/file,assets/('templates/'+file))
    asset_records=[{'source':'templates/databases.sql','target':'init/databases.sql'}, {'source':'templates/internal.key.txt','target':'config/shared/internal-credential.key','template':True}, {'source':'templates/coding-secret.txt','target':'secrets/coding-shared','template':True,'mode':'0644','uid':1000,'gid':32101}]
    asset_records += [{'source':'templates/application.yml','target':'config/'+name+'/application.yml','template':True} for name in ['api','mcp','news']]
    asset_records += [{'source':'templates/'+file,'target':'config/'+file} for file in ['public-gateway.conf','node-proxy.conf']]
    doc={'format':'live-monitor.install.v1','product':'runtime-os','name':'Runtime OS','version':args.version,'capabilities':['docker_socket','host_identity'],
         'requirements':{'memory_mb':16384,'disk_mb':12288,'cpus':8,'architecture':'amd64'},'parameters':{'http_port':{'type':'integer','label':'Runtime OS 访问端口','default':18100,'min':1024,'max':65535},'mcp_port':{'type':'integer','label':'MCP 工具访问端口','default':18101,'min':1024,'max':65535},'database_password':{'type':'generated'},'internal_key':{'type':'generated'},'internal_token':{'type':'generated'},'internal_cipher':{'type':'generated','generation':'aes-gcm','source':'internal_token','key':'internal_key'},'coding_secret':{'type':'generated'}},
         'compose':compose,'assets':asset_records,'network_allocations':{'CODING':{'offsets':[10,20]}},'entrypoints':[{'label':'打开 Runtime OS','url':'http://${PUBLIC_HOST}:${http_port}/'},{'label':'打开 MCP 管理','url':'http://${PUBLIC_HOST}:${mcp_port}/admin/'}], 'recovery':{'limit':3,'failure_window_seconds':30,'recover_unhealthy':True},'deadline_seconds':600,'stable_seconds':10}
    # Container health plus TCP readiness; no success based merely on container creation.
    doc['readiness']={name:[{'kind':'container_tcp','port':port,'network':None}] for name,port in [('api',8080),('mcp',8090),('news',8091),('coding-agent',3100),('gateway',8080),('postgres',5432),('redis',6379),('opensearch',9200)]}
    (out/'installer-definition.json').write_text(json.dumps(doc,indent=2),encoding='utf-8')
    print(json.dumps({'definition':str(out/'installer-definition.json'),'assets':str(assets),'services':list(compose['services']),'liveConfigurationRead':False,'customerDataRead':False}))


if __name__=='__main__':main()
