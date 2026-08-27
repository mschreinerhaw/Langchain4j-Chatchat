# Cache package layout

`com.chatchat.mcpserver.cache` owns shared cache configuration, RocksDB and Redis storage,
database-query caching, and API-response caching.

| Package | Responsibility |
| --- | --- |
| `cache.config` | Shared MCP cache configuration properties |
| `cache.rocksdb` | Embedded RocksDB lifecycle and byte-oriented storage operations |
| `cache.redis` | Redis configuration, persistence, secret resolution, connection management, and storage |
| `cache.query` | Database-query cache configuration, entries, service behavior, and administration |
| `cache.api` | API-response cache entries, keying, concurrency, and persistence orchestration |

## Placement rules

1. Keep the `cache` root free of concrete types.
2. Keep backend-specific lifecycle and connection behavior in `rocksdb` or `redis`.
3. Keep workload-specific keys, entries, and concurrency policies in `query` or `api`.
4. Shared storage configuration belongs in `config`; it must not absorb workload behavior.
5. Avoid generic `model`, `service`, `persistence`, and `util` packages when a functional owner exists.
