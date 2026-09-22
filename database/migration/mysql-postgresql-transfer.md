# MySQL ↔ PostgreSQL data transfer

The two Shell entry points copy the **ChatChat API** (`--module api`) or the
**standalone MCP Server** (`--module mcp`) relational database in either direction:

- `mysql-to-postgresql.sh`
- `postgresql-to-mysql.sh`

They transfer rows, including primary keys, binary artifacts, document/Skill relations,
RBAC grants and MCP configuration. They do not transfer OpenSearch indexes, RocksDB
files, original attachments outside the database, or the separate News/market database.

## Prerequisites

Use Bash and Python 3.10+ with the database drivers:

```bash
python3 -m pip install PyMySQL 'psycopg[binary]' tzdata
```

Stop API/MCP writers and take backups of **both** databases before migration. Both
databases must already have the schema from the **same application version**. Create
an empty target database and load its schema only; the transfer copies existing seed
rows from the source. The schema files are `database/init/mysql/chatchat-api.sql`,
`database/init/mysql/chatchat-mcp-server.sql`, and their counterparts under
`database/init/postgresql/`. For PostgreSQL API, also apply
`database/init/postgresql/chatchat-api-post-schema.sql`. Do not load the separate
`*-securities-seed.sql` files into the target before copying; their rows come from
the source.

Example connection settings (set the appropriate database names for `mcp`):

```bash
export MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_USER=chatchat_api
export MYSQL_DATABASE=live_runtime_api MYSQL_PASSWORD_FILE=/path/to/mysql-password
export PGHOST=127.0.0.1 PGPORT=5432 PGUSER=chatchat_api
export PGDATABASE=live_runtime_api PGPASSWORD_FILE=/path/to/pg-password
```

The script also accepts `MYSQL_PASSWORD`, `PGPASSWORD`, `MYSQL_SSL_CA`, `PGSSLMODE`
and `PGSCHEMA` (default `public`). Passwords are not printed. The database user needs
`SELECT` on the source and `SELECT`, `INSERT`, `DELETE` on the target; PostgreSQL
identity sequence adjustment also needs ownership or appropriate sequence privileges.

## MySQL to PostgreSQL

```bash
bash database/migration/mysql-to-postgresql.sh --module api --dry-run
bash database/migration/mysql-to-postgresql.sh --module api
```

Repeat with `--module mcp` and the MCP database credentials when migrating MCP.
The target must be empty unless `--replace-target` is supplied explicitly.

## PostgreSQL to MySQL (return migration)

Stop writers again. The original MySQL database normally contains old data, so first
check the schemas and row counts, then explicitly replace its rows:

```bash
bash database/migration/postgresql-to-mysql.sh --module api --dry-run
bash database/migration/postgresql-to-mysql.sh --module api --replace-target
```

Repeat for MCP if it was switched. `--replace-target` deletes target rows in reverse
foreign-key order, then copies source rows in parent-first order. A failed transfer
rolls back target row changes. Every table's copied row count is compared with the
target before commit. PostgreSQL identity sequences are advanced after a successful
copy. The script refuses missing tables, mismatched columns, unsupported foreign-key
cycles, and a populated target without `--replace-target`.

The default `--mysql-timezone Asia/Shanghai` converts MySQL `DATETIME` values to/from
PostgreSQL `timestamp with time zone`. Set this option to the time zone used when the
MySQL data was written if your deployment differs. `--batch-size` defaults to 500.

After transfer, point the relevant process at `datasource-postgresql.yml` or
`datasource-mysql.yml`, and verify login, role grants, Skill document access and MCP
calls before reopening writes. Keep the same encryption keys and the same authoritative
RocksDB/object-storage data. Rebuild or verify OpenSearch indexes separately where
needed; they are not part of this relational transfer. The scripts perform a one-time
copy, not ongoing bidirectional synchronization.
