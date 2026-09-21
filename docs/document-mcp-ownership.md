# MCP-owned document library

The browser keeps using `/api/v1/search/**` on `chatchat-api`. After login,
`DocumentMcpGatewayFilter` forwards those requests to
`/internal/api/v1/search/**` on `chatchat-mcp-server`. Upload, import from URL,
library listing, preview, download, category changes, deletion, reindexing and
search now run against the MCP process's document store.
The Agent workshop's document picker and binding checks use the same MCP
store after cutover.

Both processes must receive the same nonempty `CHATCHAT_DOCUMENT_GATEWAY_TOKEN`.
Set `CHATCHAT_DOCUMENT_MCP_BASE_URL` on API when MCP is not at
`http://localhost:8090`. The MCP internal endpoint rejects requests without
the token and the API-asserted tenant and user identity. Do not expose
`/internal/api/v1/search/**` through the public ingress.
The API gateway is disabled by default until existing documents are migrated;
enable it with `CHATCHAT_DOCUMENT_GATEWAY_ENABLED=true` at cutover.

## Existing API documents

The existing RocksDB database is not automatically moved. Before enabling the
gateway in a running installation:

1. Back up API's `search-rocksdb` and `search-files` directories.
2. Start MCP with `CHATCHAT_DOCUMENT_GATEWAY_TOKEN` set. Keep the API document
   gateway disabled during migration.
3. Set `CHATCHAT_API_TOKEN` to an admin API session token and run:

   ```powershell
   python scripts/migrate_api_documents_to_mcp.py --tenant-id <tenant> --user-id <admin-user-id>
   ```

   The script transfers all versions visible to that account, preserving
   document IDs and original files when present. Run it for each tenant or
   visibility scope that has documents. It can be rerun after interruption.
4. Compare the API library count with MCP's internal library count, then enable
   the API gateway and restart API. Verify upload, list, search, preview and
   delete through the browser.

The migration does not infer documents hidden from the migration account.
Those documents require an account with access or a separate scoped export.
