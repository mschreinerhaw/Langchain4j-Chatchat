"""Prepare a sealed Runtime OS deployment for Live Monitor; do not restart applications.

Source topology and secrets are read on the authorized host. Only references,
content hashes and a sanitized topology are returned to the local control plane.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time


def run(*args):
    p = subprocess.run(args, text=True, capture_output=True, timeout=900)
    if p.returncode:
        raise RuntimeError('Preparation command failed: ' + args[0] + ' (no configuration exported)')
    return p.stdout.strip()


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source-root', type=Path, required=True)
    p.add_argument('--target-root', type=Path, required=True)
    p.add_argument('--project', required=True)
    p.add_argument('--stack-id', required=True)
    p.add_argument('--environment', required=True)
    p.add_argument('--registry', required=True)
    p.add_argument('--docker-config', type=Path, required=True)
    p.add_argument('--agent-config', type=Path, required=True)
    p.add_argument('--contract-file', type=Path, required=True)
    p.add_argument('--candidate-images-file', type=Path, help='Optional service -> immutable image map from a verified CI build; no running containers are changed')
    p.add_argument('--external-container', action='append', default=[])
    p.add_argument('--image-alias', action='append', default=[])
    p.add_argument('--post-deploy-hook', type=Path, action='append', default=[])
    a = p.parse_args()
    assert os.getuid() == 0
    os.umask(0o077)
    source, target = a.source_root.resolve(), a.target_root.resolve()
    contract = json.loads(a.contract_file.read_text())
    candidates = json.loads(a.candidate_images_file.read_text()) if a.candidate_images_file else {}
    assert source.is_dir() and target.parent == Path('/opt') and target != source
    for name in [a.project, a.stack_id, a.environment]:
        assert re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}', name)
    target.mkdir(mode=0o700, exist_ok=True)
    ref = 'release-' + time.strftime('%Y%m%d%H%M%S')
    release = target / ref
    release.mkdir(mode=0o700)
    assets = release / 'assets'
    assets.mkdir(mode=0o700)
    config = json.loads(run('docker', 'compose', '--project-name', a.project,
        '--env-file', str(source / 'config/deployment.env'), '-f', str(source / 'compose.yaml'), 'config', '--format', 'json'))
    original = copy.deepcopy(config)
    (release / 'source-compose-private.json').write_text(json.dumps(original, indent=2))
    shutil.copy2(source / 'config/deployment.env', release / 'source-deployment-private.env')
    running = {}
    for identity in run('docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + a.project).split():
        item = json.loads(run('docker', 'inspect', identity))[0]
        running[item['Config']['Labels']['com.docker.compose.service']] = item
    assert all(name in running and running[name]['State']['Running'] for name in config['services'])
    promoted = {}
    def promote(image_id, name):
        if image_id in promoted:
            return promoted[image_id]
        tag = a.registry + '/' + a.stack_id.lower() + '/' + name.lower() + ':' + ref
        run('docker', 'tag', image_id, tag)
        run('docker', '--config', str(a.docker_config), 'push', tag)
        pinned = next(d for d in json.loads(run('docker', 'image', 'inspect', tag))[0]['RepoDigests'] if d.startswith(tag.rsplit(':', 1)[0] + '@sha256:'))
        assert json.loads(run('docker', 'image', 'inspect', pinned))[0]['Id'] == image_id
        promoted[image_id] = pinned
        print(json.dumps({'phase': 'MIRRORED', 'service': name, 'image': pinned}), flush=True)
        return pinned
    writable, readonly, copied_assets, backups, socket_services, container_names = set(), set(), [], set(), [], []
    postgres_backups, idle_queries, idle_probes = [], [], []
    for name, service in config['services'].items():
        item = running[name]
        service['image'] = promote(item['Image'], name)
        if service.get('container_name'):
            container_names.append(service['container_name'])
        for mount in service.get('volumes', []):
            if mount['type'] != 'bind':
                continue
            path = Path(mount['source']).resolve()
            if 'docker.sock' in str(path):
                socket_services.append(name)
            elif mount.get('read_only'):
                if path.is_file() and not str(path).startswith(('/sys/', '/proc/')):
                    destination = assets / (digest(path)[:16] + '-' + path.name)
                    if not destination.exists():
                        shutil.copy2(path, destination)
                        st = path.stat(); os.chown(destination, st.st_uid, st.st_gid)
                        copied_assets.append({'file': str(destination), 'sha256': digest(destination)})
                    mount['source'] = str(destination)
                    readonly.add(str(assets))
                else:
                    readonly.add(str(path))
            else:
                writable.add(str(path))
                if mount['target'] not in contract.get('non_backup_mount_targets', []):
                    backups.add(str(path))
            mount.setdefault('bind', {})['create_host_path'] = False
        environment = service.get('environment', {})
        database_url = next((v for k, v in environment.items() if k.endswith('_POSTGRESQL_URL')), None)
        if database_url:
            database = database_url.split('?')[0].rsplit('/', 1)[1]
            assert re.fullmatch(r'[A-Za-z0-9_]+', database)
            postgres_backups.append({'container': contract['postgres_container'], 'database': database, 'user': 'postgres'})
            for gate in contract.get('idle_queries', []):
                if gate['service'] == name:
                    idle_queries.append({'container': contract['postgres_container'], 'database': database, 'query': gate['query'], 'expected': gate.get('expected', 0)})
        for probe in contract.get('idle_probes', []):
            if probe['headers_service'] != name:
                continue
            mount = next(m for m in service.get('volumes', []) if m['target'] == probe['headers_mount_target'])
            idle_probes.append({'url': probe['url'], 'headers_files': {probe['header']: {'file': mount['source'], 'prefix': probe.get('prefix', '')}}, 'path': probe['path'], 'expected': probe.get('expected', 0)})
    dependencies, dep_config = [], {'services': {}, 'volumes': {}}
    for index, container in enumerate(a.external_container):
        item = json.loads(run('docker', 'inspect', container))[0]
        name = 'dependency-' + str(index)
        pinned = promote(item['Image'], name)
        c, h = item['Config'], item['HostConfig']
        service = {'image': pinned, 'container_name': container, 'network_mode': 'bridge',
            'cpus': h.get('NanoCpus', 0) / 1000000000 or 1, 'mem_limit': h.get('Memory') or '2g',
            'restart': h['RestartPolicy']['Name'], 'environment': dict(v.split('=', 1) for v in c.get('Env', [])),
            'entrypoint': c.get('Entrypoint'), 'command': c.get('Cmd'), 'volumes': [], 'ports': []}
        if c.get('User'):
            service['user'] = c['User']
        if h.get('Ulimits'):
            service['ulimits'] = {u['Name']: {'soft': u['Soft'], 'hard': u['Hard']} for u in h['Ulimits']}
        for j, mount in enumerate(item['Mounts']):
            spec = {'type': mount['Type'], 'target': mount['Destination'], 'read_only': not mount['RW']}
            if mount['Type'] == 'bind':
                spec['source'] = mount['Source']
                (writable if mount['RW'] else readonly).add(str(Path(mount['Source']).resolve()))
                spec['bind'] = {'create_host_path': False}
            else:
                alias = name + '-volume-' + str(j)
                spec['source'] = alias
                dep_config['volumes'][alias] = {'external': True, 'name': mount['Name']}
            service['volumes'].append(spec)
        readiness = []
        for port, bindings in (h.get('PortBindings') or {}).items():
            for binding in bindings:
                service['ports'].append({'target': int(port.split('/')[0]), 'published': binding['HostPort'],
                    'host_ip': binding.get('HostIp') or '0.0.0.0', 'protocol': port.split('/')[1]})
                readiness.append({'kind': 'tcp', 'host': '127.0.0.1', 'port': int(binding['HostPort'])})
        dep_config['services'][name] = service
        dependencies.append({'name': container, 'container': container, 'image': pinned, 'service': name, 'bootstrap': True, 'readiness': readiness})
    aliases = []
    for index, image in enumerate(a.image_alias):
        image_id = json.loads(run('docker', 'image', 'inspect', image))[0]['Id']
        aliases.append({'alias': image, 'image': promote(image_id, 'runtime-image-' + str(index))})
    # The rollback file captures current binaries before optional verified CI candidates are applied.
    rollback_config = copy.deepcopy(config)
    assert set(candidates) <= set(config['services']), 'Unknown candidate service'
    for name, image in candidates.items():
        assert re.search(r'@sha256:[a-f0-9]{64}$', image), 'CI candidate must use an immutable digest'
        candidate_id = json.loads(run('docker', 'image', 'inspect', image))[0]['Id']
        config['services'][name]['image'] = promote(candidate_id, name)
    for file, value in [('compose.json', config), ('rollback-compose.json', rollback_config), ('dependencies.json', dep_config)]:
        (release / file).write_text(json.dumps(value, indent=2))
    manifest = {'schema': 1, 'stack_id': a.stack_id, 'environment': a.environment, 'project': a.project,
        'dependency_project': a.stack_id + '-dependencies', 'credential_ref': a.docker_config.name,
        'compose': {'file': 'compose.json', 'sha256': digest(release / 'compose.json')},
        'rollback_compose': {'file': 'rollback-compose.json', 'sha256': digest(release / 'rollback-compose.json')},
        'dependency_compose': {'file': 'dependencies.json', 'sha256': digest(release / 'dependencies.json')},
        'external_dependencies': dependencies, 'external_endpoints': contract.get('external_endpoints', []), 'image_aliases': aliases, 'assets': copied_assets,
        'baseline_containers': {name: {'container_id': item['Id'], 'image_id': item['Image']} for name, item in running.items() if name in config['services']},
        'backup_paths': sorted(backups), 'postgres_backups': postgres_backups, 'idle_queries': idle_queries, 'idle_probes': idle_probes,
        'deadline_seconds': 300, 'stable_seconds': 20, 'recovery': {'limit': 3, 'failure_window_seconds': 30},
        'readiness': {}, 'post_deploy_hooks': []}
    manifest['readiness'] = contract.get('service_readiness', {})
    hooks = {}
    for index, path in enumerate(a.post_deploy_hook):
        reference = 'hook-' + str(index)
        hooks[reference] = {'file': str(path.resolve()), 'sha256': digest(path)}
        manifest['post_deploy_hooks'].append(reference)
    (release / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    profile_root = target / ('agent-' + a.environment)
    for directory in ['state', 'backups']:
        (profile_root / directory).mkdir(mode=0o700, parents=True, exist_ok=True)
    agent_config = json.loads(a.agent_config.read_text())
    shutil.copy2(a.agent_config, release / 'agent-config-before-private.json')
    policy = {'packages': {ref: str(release / 'manifest.json')}, 'projects': [a.project, manifest['dependency_project']],
        'writable_bind_roots': sorted(writable), 'read_only_bind_roots': sorted(readonly),
        'docker_socket_services': socket_services, 'container_names': container_names + a.external_container,
        'backup_containers': sorted({b['container'] for b in postgres_backups}), 'backup_paths': sorted(backups),
        'image_aliases': a.image_alias, 'hooks': hooks}
    policy['active_compose_paths'] = {a.project: str(source / 'compose.yaml')}
    existing = agent_config.get('profiles', {}).get(a.environment)
    if existing:
        assert existing['state_root'] == str(profile_root / 'state'), 'Refuse to reassign an existing environment state directory'
        old = existing['stack_policy']
        for field in ['packages', 'active_compose_paths']:
            for key, value in policy[field].items():
                assert key not in old.get(field, {}) or old[field][key] == value, 'Do not overwrite an approved release/path'
            policy[field] = {**old.get(field, {}), **policy[field]}
        for key, value in policy['hooks'].items():
            assert key not in old.get('hooks', {}) or old['hooks'][key] == value, 'Changed hook requires separate host approval'
        policy['hooks'] = {**old.get('hooks', {}), **policy['hooks']}
        for field in ['projects', 'writable_bind_roots', 'read_only_bind_roots', 'docker_socket_services', 'container_names', 'backup_containers', 'backup_paths', 'image_aliases']:
            policy[field] = sorted(set(old.get(field, [])) | set(policy[field]))
    agent_config.setdefault('profiles', {})[a.environment] = {**(existing or {}), 'environment': a.environment,
        'state_root': str(profile_root / 'state'), 'backup_root': (existing or {}).get('backup_root', str(profile_root / 'backups')),
        'stack_policy': policy, 'allowed_registries': sorted(set((existing or {}).get('allowed_registries', [])) | {a.registry}), 'deadline_seconds': 1100}
    temporary = a.agent_config.with_suffix('.live-monitor.tmp')
    temporary.write_text(json.dumps(agent_config, indent=2))
    temporary.chmod(0o600)
    os.replace(temporary, a.agent_config)
    public = {'stack_id': a.stack_id, 'environment': a.environment, 'package_ref': ref,
        'package_sha256': digest(release / 'manifest.json'), 'project': a.project,
        'release_directory': str(release), 'services': {n: s['image'] for n, s in config['services'].items()},
        'dependencies': [{'name': d['name'], 'image': d['image']} for d in dependencies],
        'data_paths_preserved': True, 'applications_restarted': False, 'credential_values_exported': False}
    public['candidate_services'] = sorted(candidates)
    (release / 'prepared-public.json').write_text(json.dumps(public, indent=2))
    print(json.dumps(public), flush=True)


if __name__ == '__main__':
    main()
