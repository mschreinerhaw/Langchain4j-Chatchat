# MCP-owned document library

The browser keeps using `/api/v1/search/**` on `chatchat-api`. After login,
`DocumentMcpGatewayFilter` forwards those requests to
`/internal/api/v1/search/**` on `chatchat-mcp-server`. Upload, import from URL,
library listing, preview, download, category changes, deletion, reindexing and
search now run against the MCP process's document store.
The Agent workshop's document picker and binding checks use the same MCP
store after cutover.

Both processes must receive the same nonempty `CHATCHAT_DOCUMENT_GATEWAY_TOKEN`.
The API reuses its existing `chatchat.mcp.center.base-url` to reach MCP.
Set the API's `CHATCHAT_MCP_GRPC_HOST` and `CHATCHAT_MCP_GRPC_PORT` to the MCP
gRPC listener, and configure the same internal credential on both processes.
Single and batch uploads stream file bytes to MCP over the existing gRPC host
and port (`chatchat.mcp.grpc.client`). The same gRPC stream transfers original
files during legacy reindex. Each message carries at most 1 MiB of file data,
so a 55 MiB file never becomes one large RPC message. MCP saves the file and
builds its index after the stream completes. The gRPC channel authenticates with
the existing internal credential, which is stored encrypted in configuration.
With `plaintext: true`, this
credential and the file traffic are not encrypted on the network, so keep the
gRPC port on a trusted internal network. Other document API calls still use the
configured internal HTTP endpoint and document gateway token.
The MCP internal endpoint rejects requests without
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
   While the gateway is disabled, clicking a legacy document's **Reindex**
   action now streams that document and its original file by gRPC from API to MCP;
   MCP indexes it with the same document ID. A category reindex transfers its
   matching legacy documents in the background. This permits gradual migration.
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
An MCP reindex only works after the source file has arrived in MCP storage;
reindexing alone cannot read files that still exist only in API storage.

## Existing Knowledge IR tables

Older databases define `knowledge_ir_unit.source_section` and `title` as
`varchar(500)`. Apply `database/migration/mysql/V20260921_01__knowledge_ir_full_sections.sql`
to each existing MySQL database containing that table before deploying the
new MCP and reindexing documents. Use the matching H2 migration for H2
installations. The fields now store full section paths and titles as text;
no application-side truncation is applied. Documents whose Knowledge IR write
previously failed need one reindex after the schema change.

Check the database used by the running MCP process before reindexing:

```sql
SELECT DATABASE();
SHOW FULL COLUMNS FROM knowledge_ir_unit WHERE Field IN ('source_section', 'title');
```

Both columns must report `text`. Hibernate `ddl-auto: update` is not a substitute
for verifying this change on an existing database. If either column still reports
`varchar(500)`, run the MySQL migration against that database. If both report
`text` and an insertion still fails, inspect the length of the source heading;
MySQL `TEXT` itself has a finite capacity and needs a separate fix for that case.
