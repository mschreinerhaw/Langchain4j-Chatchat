# MySQL and PostgreSQL for the Agent control plane

The ChatChat API and standalone MCP Server can each use MySQL or PostgreSQL as their
relational store. The default `dev` configuration still selects MySQL. Set
`CHATCHAT_DATASOURCE_CONFIG=datasource-postgresql.yml` for PostgreSQL. For MySQL,
use `datasource-mysql.yml` (or the existing `mysql` profile). The two processes can
use different engines.

| Process | PostgreSQL connection variables | Default database |
| --- | --- | --- |
| API | `CHATCHAT_API_POSTGRESQL_URL`, `CHATCHAT_API_POSTGRESQL_USERNAME`, `CHATCHAT_API_POSTGRESQL_PASSWORD` | `live_runtime_api` |
| MCP | `CHATCHAT_MCP_POSTGRESQL_URL`, `CHATCHAT_MCP_POSTGRESQL_USERNAME`, `CHATCHAT_MCP_POSTGRESQL_PASSWORD` | `live_runtime_mcp` |

Example for the API:

```bash
export CHATCHAT_DATASOURCE_CONFIG=datasource-postgresql.yml
export CHATCHAT_API_POSTGRESQL_URL=jdbc:postgresql://127.0.0.1:5432/live_runtime_api
export CHATCHAT_API_POSTGRESQL_USERNAME=chatchat_api
export CHATCHAT_API_POSTGRESQL_PASSWORD='...'
```

Create an empty database and apply the matching schema and optional starter data from
`database/init/postgresql/` before the first start. The PostgreSQL schema is generated
from the same JPA entities as the MySQL and H2 schemas. Apply
`chatchat-api-post-schema.sql` after the API schema to add the PostgreSQL partial
unique index for ACTIVE MCP contracts. Existing databases should use
the matching scripts under `database/migration/postgresql/` for changes introduced
after their deployed version; do not run the full initialization schema over a populated
database. The current `ddl-auto: update` remains available for development, but
production upgrades should apply reviewed migrations before switching to `validate`.

RBAC, Skill document scope, OpenSearch recall, and RocksDB source retrieval use the
same application services on both engines. PostgreSQL Row Level Security is not
enabled automatically: a PostgreSQL policy would require transaction-scoped user and
tenant context on every database connection. The application authorization layer
remains the authority on both engines.

Changing the database setting does not copy existing MySQL data. Export and migrate
the data separately, then verify row counts, role grants, Skill scopes, document IDs,
and indexing state before routing users to the new database. The standalone News
Runtime and governed market storage are separate from this control plane and retain
their existing MySQL/H2 configurations.

For a one-time copy in either direction, use the Shell entry points documented in
`database/migration/mysql-postgresql-transfer.md`.
The standalone Java migration tool is documented in `chatchat-data-migration/README.md`.

If the MCP Server fails on startup with `syntax error at or near
"innodb_lock_wait_timeout"`, its PostgreSQL connection is still receiving a MySQL
Hikari `connection-init-sql`. Set `CHATCHAT_DATASOURCE_CONFIG=datasource-postgresql.yml`
for the MCP process and remove any external
`SPRING_DATASOURCE_HIKARI_CONNECTION_INIT_SQL` or
`spring.datasource.hikari.connection-init-sql` override containing MySQL SQL.
The PostgreSQL datasource config now sets `lock_timeout` using PostgreSQL syntax.
