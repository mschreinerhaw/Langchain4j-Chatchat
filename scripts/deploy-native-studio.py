"""Install the Native Studio transport alongside the existing S3 Node.

Run on the Docker host after building Dockerfile.studio from source. Secrets
stay in existing private files. Never restarts the S3 executor or its workers.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import re
import secrets
import subprocess


def deploy(root, secret_file, image, api_port=8080,
           application_compose=Path('/opt/runtime-os-docker-stage/compose.yaml'),
           application_service='api', application_project='runtime-os-stage-maintenance'):
    if not re.fullmatch(r'sha256:[a-f0-9]{64}', image):
        raise ValueError('Use an inspected immutable Studio image ID')
    compose_file = root / 'compose.json'
    compose = json.loads(compose_file.read_text())
    base = json.loads((root / 'private/executor.json').read_text())
    private = root / 'private/studio-executor.json'
    gateway_file = root / 'private/studio-gateway.json'
    if private.exists() != gateway_file.exists():
        raise RuntimeError('Incomplete Studio deployment; reconcile private configuration first')
    if not private.exists():
        executor = copy.deepcopy(base)
        executor.update(instanceId='studio-'+secrets.token_hex(8), ownershipKey=secrets.token_hex(32),
                        apiToken=secrets.token_hex(32), signingKey=secrets.token_hex(32),
                        workersRoot=str(root/'studio-workers'), hostWorkersRoot=str(root/'studio-workers'),
                        controlRoot=str(root/'studio-executor-state'), socketPath=str(root/'studio-executor.sock'),
                        maxWorkers=1, maxPerTenant=1, enableNativeSilo=False)
        executor['modelNames']['runtime'] = base['modelNames']['fixed']
        for tenant in executor['tenants'].values():
            tenant['models'] = ['runtime']
            tenant['tools'] = ['read_files', 'search_codebase', 'skills', 'submit_and_exit', 'runtime__*']
        gateway = dict(secret=secret_file.read_text().strip(), signingKey=executor['signingKey'],
                       workerImage=executor['workerImage'], workerRuntime=executor['workerRuntime'],
                       executor=dict(socketPath=executor['socketPath'], token=executor['apiToken']),
                       workersRoot=executor['workersRoot'], stateRoot=str(root/'studio-state'),
                       callbackUrl=f'http://127.0.0.1:{api_port}/', port=8791, maxWorkers=1)
        if len(gateway['secret']) < 32:
            raise ValueError('Invalid existing Java shared secret')
        for path, value in [(private, executor), (gateway_file, gateway)]:
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'w') as out:
                json.dump(value, out, indent=2)
    backup = root / 'compose.before-studio.json'
    if not backup.exists():
        backup.write_bytes(compose_file.read_bytes())
    studio_gateway = None
    for name, template, config, script in [
        ('studio-executor', 'executor', private, 'worker-executor.mjs'),
        ('studio-gateway', 'node', gateway_file, 'business-server.mjs'),
    ]:
        service = copy.deepcopy(compose['services'][template])
        service.update(image=image if name=='studio-gateway' else base['workerImage'],
                       container_name='runtime-coding-'+name,
                       entrypoint=['node', '/opt/runtime-os/coding-node/service/src/'+script],
                       environment={('CLINE_EXECUTOR_CONFIG' if name=='studio-executor' else 'CLINE_BUSINESS_CONFIG'):str(config)})
        service.pop('depends_on', None)
        if name=='studio-gateway':
            # Java's existing endpoint is loopback inside its container, not the
            # Docker host. Compose restarts this sidecar when it replaces Java.
            service['network_mode'] = 'service:'+application_service
            service['depends_on'] = {application_service:{'condition':'service_started','restart':True}}
            service.pop('extra_hosts', None)
            studio_gateway = service
        else:
            compose['services'][name] = service
    app = json.loads(application_compose.read_text())
    if application_service not in app['services']:
        raise ValueError('Application Compose must contain the selected API service: '+application_service)
    # One-time migration of the candidate sidecar; never stops the S3 Node.
    if 'studio-gateway' in compose['services']:
        subprocess.run(['docker','compose','-p','runtime-coding-integrated','-f',str(compose_file),
                        'stop','studio-gateway'],check=True)
        subprocess.run(['docker','compose','-p','runtime-coding-integrated','-f',str(compose_file),
                        'rm','-f','studio-gateway'],check=True)
        del compose['services']['studio-gateway']
    app_backup = application_compose.with_name('compose.before-native-studio.private.json')
    if not app_backup.exists():
        fd=os.open(app_backup,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'wb') as out:out.write(application_compose.read_bytes())
    app['services']['studio-gateway'] = studio_gateway
    compose_file.write_text(json.dumps(compose, indent=2)+'\n')
    application_compose.write_text(json.dumps(app, indent=2)+'\n')
    subprocess.run(['docker', 'compose', '-p', 'runtime-coding-integrated', '-f', str(compose_file),
                    'up', '-d', '--no-deps', 'studio-executor'], check=True)
    command=['docker','compose']
    env_file=application_compose.parent/'config/deployment.env'
    if env_file.is_file():command+=['--env-file',str(env_file)]
    subprocess.run(command+['-p',application_project,'-f',str(application_compose),
                    'up','-d','--no-deps','studio-gateway'],check=True)



if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('/opt/runtime-os/cn2'))
    parser.add_argument('--secret-file', type=Path, default=Path('/opt/runtime-os-docker-stage/config/secrets/coding-agent-shared-secret'))
    parser.add_argument('--image', required=True)
    parser.add_argument('--api-port', type=int, default=8080)
    parser.add_argument('--application-compose',type=Path,default=Path('/opt/runtime-os-docker-stage/compose.yaml'))
    parser.add_argument('--application-service',default='api')
    parser.add_argument('--application-project',default='runtime-os-stage-maintenance')
    args = parser.parse_args()
    deploy(args.root, args.secret_file, args.image, args.api_port,args.application_compose,args.application_service,args.application_project)
