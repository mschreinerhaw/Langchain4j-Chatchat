"""One-time transfer of API-owned library documents to MCP-owned storage.

Run while the API document gateway is disabled. The script preserves document IDs,
versions, metadata and original files when they are available.
"""

import argparse
import json
import mimetypes
import os
import urllib.error
import urllib.parse
import urllib.request
import uuid


def request(url, *, headers=None, body=None, method="GET"):
    call = urllib.request.Request(url, data=body, headers=headers or {}, method=method)
    with urllib.request.urlopen(call, timeout=300) as result:
        return result.read()


def api_json(base, path, token, scope):
    separator = "&" if "?" in path else "?"
    path += separator + urllib.parse.urlencode(scope)
    payload = json.loads(request(base + path, headers={"Authorization": "Bearer " + token}))
    if payload.get("code") != 200:
        raise RuntimeError(f"API request failed: {path}: {payload.get('message')}")
    return payload["data"]


def multipart(document, file_bytes, file_name):
    boundary = "chatchat-" + uuid.uuid4().hex
    segments = [
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"document\"\r\n"
        "Content-Type: application/json; charset=utf-8\r\n\r\n".encode(),
        json.dumps(document, ensure_ascii=False).encode(),
        b"\r\n",
    ]
    if file_bytes is not None:
        safe_name = (file_name or "document").replace('"', "_").replace("\r", "_").replace("\n", "_")
        content_type = mimetypes.guess_type(safe_name)[0] or "application/octet-stream"
        segments += [
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{safe_name}\"\r\n"
            f"Content-Type: {content_type}\r\n\r\n".encode(),
            file_bytes,
            b"\r\n",
        ]
    segments.append(f"--{boundary}--\r\n".encode())
    return b"".join(segments), f"multipart/form-data; boundary={boundary}"


def migrate(args):
    api = args.api.rstrip("/")
    mcp = args.mcp.rstrip("/")
    scope = {"tenantId": args.tenant_id, "userId": args.user_id}
    headers = {
        "X-Document-Gateway-Token": args.gateway_token,
        "X-Document-Tenant-Id": args.tenant_id,
        "X-Document-User-Id": args.user_id,
        "X-Document-Username": args.username,
    }
    seen = set()
    page = 1
    transferred = 0
    while True:
        listing = api_json(api, f"/api/v1/search/library?page={page}&pageSize=100", args.api_token, scope)
        if page == 1:
            for category in listing.get("categories", []):
                name = category.get("name")
                if not name or name in ("all", "uncategorized"):
                    continue
                category_payload = json.loads(request(
                    mcp + "/internal/api/v1/search/library/categories",
                    headers={**headers, "Content-Type": "application/json"},
                    body=json.dumps({"name": name}, ensure_ascii=False).encode(), method="POST"))
                if category_payload.get("code") != 200:
                    raise RuntimeError(f"MCP category import failed for {name}: {category_payload.get('message')}")
        for item in listing.get("documents", []):
            doc_id = item["docId"]
            encoded_id = urllib.parse.quote(doc_id, safe="")
            versions = api_json(api, f"/api/v1/search/documents/{encoded_id}/versions", args.api_token, scope)
            for version in versions or [{"docId": doc_id, "version": item.get("version", 1)}]:
                version_id = version["docId"]
                if version_id in seen:
                    continue
                seen.add(version_id)
                number = version["version"]
                path = f"/api/v1/search/documents/{encoded_id}/versions/{number}"
                document = api_json(api, path, args.api_token, scope)
                file_bytes = None
                file_path = path + "/file?" + urllib.parse.urlencode(scope)
                try:
                    file_bytes = request(api + file_path, headers={"Authorization": "Bearer " + args.api_token})
                except urllib.error.HTTPError as error:
                    if error.code != 404:
                        raise
                body, content_type = multipart(document, file_bytes, document.get("fileName"))
                payload = json.loads(request(
                    mcp + "/internal/api/v1/search/documents/migrate",
                    headers={**headers, "Content-Type": content_type}, body=body, method="POST"))
                if payload.get("code") != 200:
                    raise RuntimeError(f"MCP import failed for {version_id}: {payload.get('message')}")
                transferred += 1
                print(f"Migrated {version_id} v{number}")
        if page >= listing.get("totalPages", 1):
            break
        page += 1
    print(f"Migrated {transferred} document versions")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--mcp", default="http://localhost:8090")
    parser.add_argument("--tenant-id", required=True)
    parser.add_argument("--user-id", required=True)
    parser.add_argument("--username", default="admin")
    parser.add_argument("--api-token", default=os.getenv("CHATCHAT_API_TOKEN"), required=not os.getenv("CHATCHAT_API_TOKEN"))
    parser.add_argument("--gateway-token", default=os.getenv("CHATCHAT_DOCUMENT_GATEWAY_TOKEN"),
                        required=not os.getenv("CHATCHAT_DOCUMENT_GATEWAY_TOKEN"))
    migrate(parser.parse_args())
