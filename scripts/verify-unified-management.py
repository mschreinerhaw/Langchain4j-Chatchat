"""Authenticated live control-plane acceptance using temporary identities and a fixed-template test tool.

Published test versions remain as offline audit history. Run --cleanup after browser verification to remove
temporary users, roles and the isolated test tenant. Credentials and sessions stay in private host files.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage-root', type=Path, default=Path('/opt/runtime-os-docker-stage'))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cleanup', action='store_true')
    args = parser.parse_args()
    os.umask(0o077)
    args.output.mkdir(mode=0o700, parents=True, exist_ok=True)
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    fixtures = args.output / 'temporary-identities-private.json'
    identities = {'users': [], 'roles': [], 'tenants': []}
    checks = {}

    def call(path, body=None, token=None, method=None, expected=200, port=18180):
        request = urllib.request.Request('http://127.0.0.1:' + str(port) + '/api/v1' + path,
            data=None if body is None else json.dumps(body).encode(), method=method,
            headers={'Content-Type': 'application/json', **({'Authorization': 'Bearer ' + token} if token else {})})
        try:
            with client.open(request, timeout=90) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        try:
            envelope = json.loads(raw)
        except ValueError:
            envelope = {}
        if status != expected or (expected == 200 and envelope.get('code') not in (None, 0, 200)):
            (args.output / 'http-error-private.json').write_text(json.dumps({'path': path, 'status': status, 'body': envelope}))
            raise RuntimeError('Unexpected response at ' + path + ': HTTP ' + str(status))
        return envelope.get('data') if expected == 200 else status

    login = json.loads((args.stage_root / 'evidence/cline-monitor-20261019/stage-auth.json').read_text())
    admin = call('/enterprise/auth/login', login)
    token = admin['token']
    (args.output / 'admin-session-private.json').write_text(json.dumps(admin))

    def cleanup():
        for kind in ['users', 'roles', 'tenants']:
            for item in reversed(identities[kind]):
                call('/enterprise/' + kind + '/' + item, token=token, method='DELETE')
        fixtures.unlink(missing_ok=True)

    if args.cleanup:
        if fixtures.exists():
            identities.update(json.loads(fixtures.read_text()))
            cleanup()
        print(json.dumps({'status': 'CLEANED_UP'}))
        return

    publications = []
    try:
        catalog = call('/workspaces', token=token)
        checks['governanceWorkspaces'] = {'permissions', 'mcp-publication'} <= {item['workspaceId'] for item in catalog}
        for workspace in ['permissions', 'mcp-publication']:
            manifest = call('/workspaces/' + workspace + '/manifest', token=token)
            checks[workspace + 'Manifest'] = bool(manifest['manifest']['pages'])
        checks['unauthenticatedApiBlocked'] = call('/tool-publications', expected=401) == 401
        checks['directMcpManagementBlocked'] = call('/tool-publications?tenantId=' + admin['user']['tenantId'], expected=401, port=18190) == 401
        permissions = call('/enterprise/permissions', token=token)
        permission_ids = {item['permissionCode']: item['id'] for item in permissions}

        def identity(tenant, codes, label):
            role = call('/enterprise/roles', {'tenantId': tenant, 'roleCode': 'UNIFIED_' + uuid.uuid4().hex[:12],
                'roleName': 'Temporary unified acceptance ' + label, 'roleType': 'business', 'status': 'enabled'}, token)
            identities['roles'].append(role['id'])
            call('/enterprise/roles/' + role['id'] + '/authorization',
                 {'permissionIds': [permission_ids[code] for code in codes], 'orgScopes': [], 'userIds': [], 'agentIds': []}, token, 'PUT')
            username = 'unified_' + uuid.uuid4().hex[:12]
            password = secrets.token_urlsafe(24)
            user = call('/enterprise/users', {'user': {'tenantId': tenant, 'username': username,
                'passwordHash': password, 'displayName': 'Temporary unified acceptance', 'status': 'enabled'}, 'roleIds': [role['id']]}, token)
            identities['users'].append(user['id'])
            fixtures.write_text(json.dumps(identities))
            return call('/enterprise/auth/login', {'username': username, 'password': password})

        base_codes = ['account:self:read', 'account:menus:read', 'workspace:api:access',
                      'workspace:mcp-publication:access', 'mcp:publication:read']
        reader = identity(admin['user']['tenantId'], base_codes, 'reader')
        (args.output / 'reader-session-private.json').write_text(json.dumps(reader))
        call('/tool-publications', token=reader['token'])
        checks['readerCannotPublish'] = call('/tool-publications/nonexistent/publish', {}, reader['token'], expected=403) == 403
        checks['readerCannotEdit'] = call('/tool-publications', {}, reader['token'], expected=403) == 403
        checks['readerCannotManagePermissions'] = call('/permission-center', token=reader['token'], expected=403) == 403
        foreign_tenant = call('/enterprise/tenants', {'tenantCode': 'UNIFIED_' + uuid.uuid4().hex[:10],
            'tenantName': 'Temporary unified acceptance tenant', 'status': 'enabled'}, token)
        identities['tenants'].append(foreign_tenant['id'])
        foreign = identity(foreign_tenant['id'], base_codes + ['mcp:publication:edit'], 'foreign tenant')
        (args.output / 'foreign-session-private.json').write_text(json.dumps(foreign))
        call('/permission-center', token=token)
        roles = call('/tool-publications/catalog/roles', token=token)
        role = next(item for item in roles if item['roleCode'] == 'SUPER_ADMIN')
        parents = call('/tool-publications/catalog/parents', token=token)
        chosen = None
        for parent in parents:
            templates = call('/tool-publications/catalog/templates?roleId=' + urllib.parse.quote(role['id']) +
                '&parentToolName=' + urllib.parse.quote(parent['toolName']), token=token)
            if templates:
                chosen = (parent, templates[0])
                break
        if chosen is None:
            raise RuntimeError('No existing authorized template is available for the controlled recall test')
        parent, template = chosen
        domain = 'unified_acceptance_' + uuid.uuid4().hex[:12]
        tool_name = domain + '_template_query'
        draft = {'title': '临时验收工具 · 固定范围检索', 'description': 'Synthetic release acceptance; kept offline after verification.',
                 'kind': 'CONTROLLED_TEMPLATE', 'definition': {'parentToolName': parent['toolName'], 'roleId': role['id'],
                     'subjectType': 'ROLE', 'userId': None, 'domainCode': domain, 'templateKeys': [template['key']], 'enabled': False},
                 'inputSchema': {'type': 'object', 'properties': {'limit': {'type': 'integer', 'minimum': 1, 'maximum': 20}}},
                 'outputSchema': {'type': 'object', 'properties': {'success': {'const': True}}, 'required': ['success']}}

        def visible():
            tools = call('/mcp/runtime/tools?toolNames=' + tool_name, token=token)
            return any(item.get('toolName') == tool_name or item.get('localToolName') == tool_name
                       or item.get('remoteToolName') == tool_name for item in tools)

        def action(row, name, body=None, expected=200, actor=token):
            return call('/tool-publications/' + row['id'] + '/' + name, body or {}, actor, 'POST', expected)

        first = call('/tool-publications', draft, token)
        publications.append(first)
        checks['draftInvisibleToRuntime'] = not visible()
        checks['untestedPublishRejected'] = action(first, 'publish', expected=400) == 400
        checks['foreignTenantCannotTestRelease'] = action(first, 'validate', expected=404, actor=foreign['token']) == 404
        action(first, 'validate')
        checks['invalidSchemaSampleRejected'] = action(first, 'test', {'input': {'limit': 0}}, expected=400) == 400
        tested = action(first, 'test', {'input': {'limit': 1}})
        evidence = json.loads(tested['testEvidenceJson'])
        actual = evidence['output']
        checks['draftUsesActualControlledRecall'] = actual['success'] and actual['returnedCount'] == 1
        checks['testDidNotExpandTemplateScope'] = {row['templateId'] for row in actual['templates']} == {template['templateId']}
        first = action(first, 'publish')
        checks['publishedToolVisible'] = visible()
        descriptor = next(item for item in call('/mcp/runtime/tools?toolNames=' + tool_name, token=token)
                          if item.get('remoteToolName') == tool_name)
        invocation = {'serviceId': descriptor['serviceId'], 'toolName': descriptor['localToolName'],
                      'arguments': {'limit': 1}, 'context': {}}
        executed = call('/mcp/runtime/invoke', invocation, token)
        checks['publishedToolExecutesWithAuthenticatedEnvelope'] = executed['status'] == 'SUCCESS'
        checks['executionRetainsFixedTemplateScope'] = {row['templateId'] for row in executed['data']['templates']} == {template['templateId']}
        forged = {**invocation, 'context': {'tenantId': foreign_tenant['id']}}
        checks['forgedInvocationTenantRejected'] = call('/mcp/runtime/invoke', forged, token, expected=403) == 403
        original_definition = first['definitionJson']
        checks['publishedVersionCannotRevalidate'] = action(first, 'validate', expected=400) == 400
        draft.update(logicalId=first['logicalId'], title='临时验收工具 · 第二版本')
        second = call('/tool-publications', draft, token)
        publications.append(second)
        action(second, 'validate')
        action(second, 'test', {'input': {'limit': 1}})
        second = action(second, 'publish')
        checks['immutableVersionHistory'] = second['releaseNumber'] == 2
        restored = action(first, 'rollback')
        checks['rollbackPreservesExactDefinition'] = restored['definitionJson'] == original_definition and restored['releaseNumber'] == 1
        checks['rollbackRestoresRuntimeVisibility'] = visible()
        action(first, 'offline')
        checks['offlineToolRemovedFromRuntime'] = not visible()
        stopped = call('/mcp/runtime/invoke', invocation, token)
        checks['offlineToolCannotExecuteByStaleId'] = stopped['status'] not in ['SUCCESS', 'REPAIRED', 'PARTIAL']
        config = json.loads((args.stage_root / 'compose.yaml').read_text())
        database = config['services']['mcp']['environment']['CHATCHAT_MCP_POSTGRESQL_URL'].rsplit('/', 1)[1].split('?', 1)[0]
        attempted = subprocess.run(['docker', 'exec', '-i', '-u', 'postgres', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-d', database],
            input="update mcp_tool_release set definition_json='{}' where id='" + first['id'] + "';", text=True, capture_output=True)
        checks['databaseProtectsReleasedDefinition'] = attempted.returncode != 0 and 'immutable' in attempted.stderr
        sessions = {'admin': admin, 'reader': reader, 'foreign': foreign}
        (args.output / 'browser-sessions-private.json').write_text(json.dumps(sessions))
        summary = {'status': 'PASSED' if all(checks.values()) else 'FAILED', 'checks': checks,
                   'temporaryToolLogicalId': first['logicalId'], 'temporaryToolState': 'OFFLINE',
                   'templateFamily': parent['assetType'], 'realModelInvoked': False}
        (args.output / 'acceptance.json').write_text(json.dumps(summary, indent=2))
        print(json.dumps(summary), flush=True)
        if summary['status'] != 'PASSED':
            raise RuntimeError('One or more live acceptance checks failed')
    except Exception:
        try:
            if publications:
                for row in call('/tool-publications', token=token):
                    if any(item['logicalId'] == row['logicalId'] for item in publications) and row['state'] == 'RUNNING':
                        call('/tool-publications/' + row['id'] + '/offline', {}, token)
        finally:
            cleanup()
        raise


if __name__ == '__main__':
    main()
