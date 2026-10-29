"""Authenticated live checks using only temporary owned fixtures; no permission changes."""
import argparse
import json
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request


def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument('--base', required=True)
    args.add_argument('--auth', type=Path, required=True)
    args.add_argument('--output', type=Path, required=True)
    args = args.parse_args()
    session = json.loads(args.auth.read_text(encoding='utf-8-sig'))['data']
    headers = {'Authorization': 'Bearer ' + session['token'], 'X-Workspace-Id': 'data-science', 'Content-Type': 'application/json'}
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(path, method='GET', body=None):
        request = urllib.request.Request(args.base + '/api/v1' + path, method=method, headers=headers,
            data=None if body is None else json.dumps(body).encode())
        try:
            with client.open(request, timeout=45) as response:
                result = json.loads(response.read())
                return result['code'], result.get('data')
        except urllib.error.HTTPError as error:
            return error.code, None

    user = urllib.parse.quote(session['user']['id'])
    name = '__studio_isolation_smoke_' + str(int(time.time()))
    project = ordinary = None
    report = {'mockedRequests': False, 'permissionChanges': False}
    try:
        status, project = call('/data-science/sql/projects', 'POST', {'name': name, 'dialect': 'mysql'})
        assert status == 200 and project
        status, conversation = call('/conversations/' + project['sessionId'])
        assert status == 200 and conversation['sourceChannel'] == 'SQL_STUDIO'
        status, page = call('/conversations/user/' + user + '/summaries/page?keyword=' + name)
        assert status == 200 and page['total'] == 0
        status, page = call('/conversations/user/' + user + '/studio-history?channel=SQL_STUDIO&keyword=' + name)
        assert status == 200 and page['total'] == 1 and page['items'][0]['id'] == project['sessionId']
        status, page = call('/conversations/user/' + user + '/studio-history?channel=PYTHON_STUDIO&keyword=' + name)
        assert status == 200 and page['total'] == 0
        status, _ = call('/conversations/user/forbidden-owner/studio-history?channel=SQL_STUDIO')
        assert status == 403
        report.update(sqlCreationClassified=True, recentListAndCountExcludeStudio=True,
            sqlHistoryScoped=True, pythonHistoryExcludesSql=True, crossOwnerDenied=True, detailRestoration=True)
        status, ordinary = call('/conversations', 'POST', {'userId': session['user']['id'], 'title': name + '_chat'})
        assert status == 200 and ordinary['sourceChannel'] == 'PLATFORM_UI'
        status, page = call('/conversations/user/' + user + '/summaries/page?keyword=' + name)
        assert status == 200 and page['total'] == 1
        report['ordinaryConversationStillListed'] = True
        status, providers = call('/data-science/sql/projects/' + project['id'] + '/metadata/providers')
        assert status == 200 and providers
        status, _ = call('/data-science/sql/projects/' + project['id'] + '/metadata/assets?serviceId=' + urllib.parse.quote(providers[0]['serviceId']))
        report['metadataAssetsStatus'] = status
        report['businessTableAcceptance'] = 'BLOCKED_BY_CURRENT_ACCOUNT_PERMISSION' if status == 403 else 'REQUIRES_SOURCE_SELECTION'
        status, workbench = call('/data-science/python/workbench')
        assert status == 200
        report['pythonWorkbenchAvailable'] = True
        report['readyPythonAssets'] = sum(asset['status'] == 'READY' for asset in workbench['assets'])
    finally:
        if ordinary:
            assert call('/conversations/' + ordinary['id'], 'DELETE')[0] == 200
        if project:
            assert call('/data-science/sql/projects/' + project['id'], 'DELETE')[0] == 200
            # A project can preserve its existing conversation; remove only this new fixture.
            status, _ = call('/conversations/' + project['sessionId'], 'DELETE')
            assert status in [200, 404]
        report['temporaryFixturesCleaned'] = True
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2))
    print(json.dumps(report))


if __name__ == '__main__':
    main()
