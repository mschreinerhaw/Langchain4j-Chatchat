# Deployment Ops

Independent offline Docker operations management. This is a working first release, not completion of every original requirement. See [Test report](docs/TEST-REPORT.md) and [Known issues](docs/KNOWN-ISSUES.md).

## Existing installation

- Web: http://192.168.195.221:19220
- Server source: `/opt/deployment-ops`
- Compose project: `deployment-ops`
- Login: use the configured administrator credentials.
- Branch: `feature/deployment-ops-offline-20261115`.

Runtime OS, Cline and MCP are managed workloads, not runtime dependencies. The Java backend, frontend, PostgreSQL database and executor have independent configuration and lifecycles.

## Architecture and offline prerequisites

Java 17 provides authentication, roles, audits and the Web API. It has no Docker socket, host directory mount or shell endpoint. Only the separate Python executor mounts the Docker socket and uses allowlisted Docker Engine API operations. Protect its token and network because it has Docker host administration privileges.

PostgreSQL 18 has a dedicated database and volume on an internal network. Web and executor also use a management bridge. No public API, CDN, package repository or SaaS authentication is needed at runtime.

Install Docker Engine 26+ and Compose v2 from offline media. Load these trusted image TARs before building:

```text
eclipse-temurin:17-jdk-jammy
python:3.11-slim
postgres:18
```

Java dependencies and licenses are in `backend/lib`; checksums are in `docs/dependencies.json`. Python uses its standard library. The frontend uses local assets. No Maven, npm or pip download is required. Host Python 3 is used for optional checks; Node is only used for JavaScript checks.

## Disk space

```sh
df -h /
docker system df
docker images
```

Check free space before builds and TAR exports. Use a sufficiently large disk. Do not run global prune commands. This project does not automatically remove existing images, containers, volumes or retained releases.

## Server source build and startup

Run from the deployment-ops directory on the target Linux server. For a NEW installation only:

```sh
cp .env.example .env
chmod 600 .env
```

Edit `.env`: set new random `DB_PASSWORD` and `EXECUTOR_TOKEN`, initial `ADMIN_PASSWORD`, and the desired `OPS_BIND` and `OPS_PORT`. Defaults bind to 127.0.0.1:19220. Use a specific private IP for LAN access. Never commit `.env`.

On the existing server, preserve `.env`, database credentials and executor token. Changing ADMIN_PASSWORD does not reset an existing database account; use Users and roles for password changes.

```sh
python3 scripts/check-docs.py
python3 scripts/build-frontend.py
docker compose config --quiet
docker compose build --pull=false
docker compose up -d --no-build --pull never
docker compose ps
curl -fsS http://127.0.0.1:19220/api/health
```

The Docker build compiles Java source with javac on the target server and packages the independent frontend directory. First startup initializes the dedicated database. Expect health status UP. Reuse a private HTTPS reverse proxy when available. Session cookies use HttpOnly and SameSite=Strict.

## Offline transport

On a preparation machine with the required images:

```sh
sh scripts/offline-images.sh export /media/offline/ops-images.tar
```

Transfer the TAR, its .sha256 file and this source directory. The export includes Web, executor, PostgreSQL and Java/Python bases. It excludes Registry and database/application data.

The checksum file records the ORIGINAL export path. The import helper works when the absolute path is unchanged. If the destination path differs, compare the first digest in the .sha256 file with `sha256sum /new/path/ops-images.tar`. Only after they match, run:

```sh
docker load -i /new/path/ops-images.tar
```

## Web functions

- Overview: real application, container, image and node counts.
- Applications / Containers: discovery, filtering, details, logs, raw metrics, start/stop/restart.
- Images: local inventory and checksum-verified TAR import.
- Deployments: single-container manifests, history and rollback.
- Nodes: executor registration.
- Application links: manually registered application addresses.
- Users and roles: administrator and read-only viewer accounts.
- Audit log: authentication and management records.
- Registry: repository/tag queries when the optional service is available.

The interface is English. Data comes from Docker nodes or the management database, not mock values.

## Managed application deployment

1. Use docker save on a preparation machine to produce an image TAR; compute its SHA-256 offline.
2. In Images, select the target node and upload the TAR. Supply SHA-256 manually over HTTP when browser Web Crypto is unavailable. Limits: Web 256 MiB; executor 2 GiB.
3. In Deployments, choose New deployment. Select a node and local image, then enter the application name, access ports and HTTP health check. Environment variables are optional.
4. Review the configuration before choosing Deploy application. For existing JSON files, expand Advanced: import a JSON manifest; see [manifest.example.json](docs/manifest.example.json).
5. The image must already exist on that node. Missing images are never pulled automatically.
6. Check the release result. Failed HTTP health checks attempt to restore the previous container; retained containers support manual rollback.

Deployment names use the ops-app- prefix. Replacement requires this system's ownership label. Discovery never automatically migrates or replaces an existing application. Normal container operations refuse to stop this system's own Compose containers.

Manifests support one container, environment variables and ports. Arbitrary commands, Compose input, privileged flags, host mounts and persistent volumes are not supported. Images must work with Linux capabilities dropped. Do not migrate stateful applications with this manifest format.

## Optional Registry

Import registry:2.8.3 from trusted offline media, then run:

```sh
docker compose --profile registry up -d --no-build --pull never
```

It publishes only 127.0.0.1:19225, uses the dedicated ops-registry volume and disables deletion. Use an existing authenticated HTTPS proxy for other hosts. The executor defaults to REGISTRY_URL=http://registry:5000; configure an existing Registry through an executor Compose override.

Only repository/tag queries are implemented. Registry authentication management, push and deletion UI are incomplete. The service was not installed or verified on the supplied server.

## Additional Docker nodes

Load the executor image offline on the target host. Prepare tls/server.crt and tls/server.key. Set EXECUTOR_TOKEN and NODE_BIND locally, then run:

```sh
docker compose -f compose.node.yaml up -d --pull never
```

HTTPS port 8091 is bound to the specified private IP. Restrict access to the management server. Management Java must trust the node's private CA: mount a truststore read-only via a local Compose override and configure javax.net.ssl.trustStore using JAVA_TOOL_OPTIONS. Never disable certificate validation.

Register the certificate-matching HTTPS URL and token in Nodes. Local management uses the unpublished internal executor. A second physical host and private CA installation remain unverified.

## System backup, upgrade and recovery

These commands run on the host independently of the Web service:

```sh
sh scripts/lifecycle.sh backup
sh scripts/lifecycle.sh upgrade
sh scripts/lifecycle.sh recover
```

Upgrade backs up the database, .env and executor journal, preserves recovery image tags, builds source, starts services and checks health. Failed health checks invoke image recovery. Recovery requires retained images; it does not undo destructive database migrations or restore application data.

Backups are in backups/TIMESTAMP and contain database.sql, environment and executor-state. They contain secrets. Keep a secured copy on a separate disk. Test SQL restoration in a separate empty database first. Before live restoration, stop Web/executor and restore the matching environment, database and journal. Referenced retained application containers must still exist for journal recovery.

```sh
sh scripts/lifecycle.sh stop
sh scripts/lifecycle.sh install
sh scripts/lifecycle.sh uninstall
```

Uninstall removes this Compose project's containers and networks but keeps its named volumes. It does not uninstall managed applications or delete their data.

## Troubleshooting and automated checks

```sh
docker compose ps
docker compose logs --tail 100 web executor db
curl -fsS http://127.0.0.1:19220/api/health
df -h /
python3 scripts/check-docs.py
python3 scripts/build-frontend.py
python3 -m unittest discover -s tests -v
node --check frontend/app.js
OPS_PASSWORD='<current-admin-password>' python3 tests/smoke.py
```

Missing-image errors require loading the matching offline image. Node errors require checking URL, token, certificates, network and Docker. Registry unavailable is expected without its optional service. Investigate recovery-required journals before further changes.

The default smoke test writes a directory entry and creates/updates acceptance_viewer. Deployment checks additionally require deployment-ops-acceptance:local and free port 19221; use --deploy-test. TAR checks require tests/fixture/image.tar; use --import-test. Fixtures are not fetched online.

Browser checks use existing local Chrome and playwright-core; see [English UI verification](docs/ENGLISH-UI-FIX.md). Destructive tests require an isolated installation and --confirm-isolated-system-test for tests/recovery-system.py; they intentionally interrupt the management Web service.

## Product interface

The workspace groups navigation by daily work, infrastructure and administration. Applications are grouped by project and node. Search and pagination keep long lists manageable. Status badges and readable container/resource summaries avoid requiring JSON inspection for routine checks. Technical details remain expandable.

Create and import actions open labeled dialogs. Container changes and rollback require an explicit confirmation. Unavailable optional Registry service has a setup state instead of a raw error. Read-only accounts do not see mutation controls; the server still enforces permissions. See [PRODUCT-UX.md](docs/PRODUCT-UX.md) for validation.
