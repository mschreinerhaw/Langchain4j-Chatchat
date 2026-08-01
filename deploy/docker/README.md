# ChatChat single-host production infrastructure

`docker-compose.yaml` deploys the infrastructure used by the current Runtime:

- MySQL 8.4 with independent `live_runtime_api`, `live_runtime_mcp`, and `chatchat_news` schemas;
- Redis with AOF/RDB persistence and a least-privilege `chatchat` ACL for `db-query-cache:*`;
- OpenSearch 2.12.0 over TLS, matching the repository's OpenSearch 2.12 client line.

This is a hardened **single-host baseline**, not a high-availability topology. Production
requirements that cannot tolerate one host failure must replace it with managed MySQL,
Redis Sentinel/Cluster, and a three-node OpenSearch cluster while preserving the same
application contracts.

## 1. Prepare secrets

Create these files under `deploy/secrets`; each file contains only one 32-128 character
URL-safe random value:

```text
mysql_root_password.txt
mysql_api_password.txt
mysql_mcp_password.txt
mysql_news_password.txt
redis_password.txt
opensearch_admin_password.txt
```

The directory is ignored by Git. On Linux, set directory mode `0700` and file mode `0600`.
Never put secrets in `.env.production`, Compose command lines, or application YAML.

Provision the OpenSearch certificates described in
`deploy/docker/opensearch/certs/README.md` before startup.

## 2. Validate and start

```powershell
Copy-Item .env.production.example .env.production
docker compose --env-file .env.production config --quiet
docker compose --env-file .env.production pull
docker compose --env-file .env.production up -d
docker compose --env-file .env.production ps
docker inspect chatchat-opensearch-security-init --format '{{.State.ExitCode}}'
```

Before a release window, mirror all three images into the approved registry, scan them,
and replace each `*_IMAGE` value with an immutable digest reference. Do not rely on a
public-registry pull or a mutable tag during production rollout. The OpenSearch host must
also have `vm.max_map_count=262144` (or higher) before startup.

The security initialization exit code must be `0`, and MySQL, Redis, and OpenSearch must
all become healthy. Ports bind only to `127.0.0.1`; do not change this to `0.0.0.0`
without a firewall and a documented network threat review.

Database initialization runs only when the MySQL volume is empty. Schema upgrades must
use a reviewed migration; deleting a volume is not an upgrade procedure.

## 3. Application connection contract

For applications running on the Docker host, set `CHATCHAT_REDIS_NODE=127.0.0.1:6379`
in `.env.production` **before the first MySQL initialization**. For application containers
joined to `chatchat-backend`, keep the default `redis:6379`.

Use these service endpoints and inject passwords from the deployment secret manager:

| Consumer | Endpoint / database | User |
| --- | --- | --- |
| API | `jdbc:mysql://mysql:3306/live_runtime_api` | `chatchat_api` |
| MCP | `jdbc:mysql://mysql:3306/live_runtime_mcp` | `chatchat_mcp` |
| News | `jdbc:mysql://mysql:3306/chatchat_news` | `chatchat_news` |
| API/MCP/News search | `https://opensearch:9200` | `admin` |
| MCP query cache | `redis:6379`, database 0 | `chatchat` |

Set every production JPA instance to `spring.jpa.hibernate.ddl-auto=validate`. Application
users intentionally have DML privileges only; run migrations with a separate, audited
migration identity.

The MCP container must mount `redis_password.txt` at `/run/secrets/redis_password`. The
database stores only that file reference, not the Redis password. Existing admin-created
inline Redis passwords remain compatible but should be migrated to a secret reference.

## 4. Release verification

Before application release, archive evidence for:

```powershell
docker compose --env-file .env.production ps
docker compose --env-file .env.production exec mysql sh -c 'MYSQL_PWD="$$(cat /run/secrets/mysql_root_password)" mysqladmin ping -uroot'
docker compose --env-file .env.production exec redis sh -c 'REDISCLI_AUTH="$$(cat /run/secrets/redis_password)" redis-cli --user chatchat --no-auth-warning ping'
docker compose --env-file .env.production exec opensearch sh -c 'curl --fail --cacert config/certs/root-ca.pem -u admin:"$$(cat /run/secrets/opensearch_admin_password)" https://localhost:9200/_cluster/health'
```

Also verify backup restore, disk-full alarms, slow-query alarms, OpenSearch snapshot restore,
Redis AOF recovery, and the repository's live topology/capacity E2E gate. A healthy Compose
stack alone is not release approval.

The repeatable infrastructure smoke check is:

```powershell
.\scripts\test-production-infrastructure.ps1 -EnvFile .env.production
```

CI jobs that use an isolated Compose project add `-ProjectName <name>` to the command.
