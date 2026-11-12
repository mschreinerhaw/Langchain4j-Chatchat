"""Publish one existing platform model to a Native deployment's private profiles.

Run on the deployment host. No model aliases or fallback models are created.
Credentials remain in root-only files and are never printed. Restart the API,
Node and Executor after applying; take care to drain active tasks first.
Uses the existing Native Node container for AES-GCM; no host packages are installed.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
from urllib.parse import urlsplit, urlunsplit


def prepare(model, row, gateway, executor, connection, api_key):
    if row['name'] != model or not row['enabled']:
        raise ValueError('An enabled published model is required')
    if row['protocol'] not in ('openai', 'auto'):
        raise ValueError('Native OpenAI-compatible transport required')
    if not row['base_url'].startswith('https://') or not api_key:
        raise ValueError('Published HTTPS endpoint and credential required')
    gateway, executor, connection = copy.deepcopy((gateway, executor, connection))
    profile = connection['modelProfiles'].get(model, 'model-' + hashlib.sha256(model.encode()).hexdigest()[:12])
    if profile in executor['modelNames'] and executor['modelNames'][profile] != row['provider_model']:
        raise ValueError('Profile identity collision')
    template = gateway['modelProfiles'][connection['modelProfiles'][connection['defaultModel']]]
    endpoint = urlsplit(row['base_url'])
    if endpoint.query or endpoint.fragment or endpoint.username:
        raise ValueError('A plain published API endpoint is required')
    path = endpoint.path.rstrip('/')
    if path.endswith('/chat/completions'):
        path = path[:-len('/chat/completions')]
    base_url = urlunsplit((endpoint.scheme, endpoint.netloc, path, '', ''))
    gateway['modelProfiles'][profile] = dict(template, model=row['provider_model'], baseUrl=base_url, apiKey=api_key)
    executor['modelNames'][profile] = row['provider_model']
    connection['modelProfiles'][model] = profile
    for deployment in (gateway, executor):
        for policy in deployment['tenants'].values():
            if profile not in policy['models']:
                policy['models'].append(profile)
    return profile, gateway, executor, connection


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for key in ('model', 'database', 'postgres-container', 'gateway', 'executor', 'connection', 'key-file', 'backup-dir'):
        p.add_argument('--' + key, required=True)
    p.add_argument('--apply', action='store_true')
    p.add_argument('--crypto-container', required=True)
    a = p.parse_args()
    # JSON encodes the SQL string literal; never interpolate shell command text.
    literal = "'" + a.model.replace("'", "''") + "'"
    query = "select row_to_json(p) from (select name,provider_model,base_url,protocol,enabled,api_key_cipher from platform_model_publication where model_type='chat' and enabled=true and name=" + literal + ') p'
    result = subprocess.run(['docker', 'exec', a.postgres_container, 'psql', '-U', 'postgres', '-d', a.database, '-Atc', query], stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    rows = result.stdout.decode().splitlines()
    if len(rows) != 1:
        raise ValueError('Exactly one enabled platform publication required')
    row = json.loads(rows[0])
    paths = [Path(a.gateway), Path(a.executor), Path(a.connection)]
    for path in paths:
        if not path.is_absolute() or path.is_symlink() or not path.is_file():
            raise ValueError('Existing absolute private configuration files required')
    values = [json.loads(path.read_text()) for path in paths]
    executor = values[1]
    probe = subprocess.run(['docker', '-H', 'unix://' + executor['dockerSocket'],
        'image', 'inspect', executor['workerImage']], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if probe.returncode:
        raise ValueError('Pinned Worker image is missing from the Executor Docker daemon; restore it before publishing')
    if not a.apply:
        print(json.dumps({'model': a.model, 'published': True, 'apply': False}))
        return
    credential = row['api_key_cipher'] or ''
    if credential.startswith('ENC(') and credential.endswith(')'):
        # Node's standard crypto matches InternalSecretCipher (SHA-256 / AES-GCM).
        program = "const fs=require('fs'),c=require('crypto');const x=JSON.parse(fs.readFileSync(0,'utf8'));const [iv,body]=x.cipher.slice(4,-1).split(':').map(v=>Buffer.from(v,'base64'));const key=c.createHash('sha256').update(x.key.trim()).digest();const d=c.createDecipheriv('aes-256-gcm',key,iv);d.setAuthTag(body.subarray(-16));process.stdout.write(Buffer.concat([d.update(body.subarray(0,-16)),d.final()]));"
        result = subprocess.run(['docker', 'exec', '-i', a.crypto_container, 'node', '-e', program],
            input=json.dumps({'cipher': credential, 'key': Path(a.key_file).read_text()}).encode(),
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
        credential = result.stdout.decode()
    profile, *updated = prepare(a.model, row, *values, credential)
    backup = Path(a.backup_dir)
    backup.mkdir(mode=0o700, parents=True, exist_ok=False)
    for index, (path, value) in enumerate(zip(paths, updated)):
        destination = backup / (str(index) + '-' + path.name)
        destination.write_bytes(path.read_bytes()); destination.chmod(0o600)
        temporary = path.with_name(path.name + '.native-model.tmp')
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as stream:
            json.dump(value, stream, indent=2)
        os.replace(temporary, path)
    print(json.dumps({'model': a.model, 'profile': profile, 'applied': True, 'backup': str(backup)}))


if __name__ == '__main__':
    main()
