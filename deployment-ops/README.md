# Deployment Ops

**New release requirement:** Deployment Manager itself runs natively on the host,
with embedded H2, and must start without Docker or any Runtime OS application.
The Compose/Docker instructions below describe historical P0 only. They are not
the installation method for the forthcoming Spring Boot/Vue release.

## Spring Boot / Vue 3 migration

The target architecture is now a Maven child module with a Spring Boot control plane
and a Vue 3 CLI frontend. See [the application platform implementation contract](docs/APPLICATION-PLATFORM.md).
This migration is in progress; the existing Compose distribution below still runs P0.

Build the new backend from the repository root: `mvn -pl deployment-ops package`.
The new backend defaults to loopback port 19240 and Spring Security authentication.
The Vue development project lives in `web-ui`: run `npm ci`, then `npm run serve`
(loopback port 19241), or `npm run build` for the static distribution.
The new UI currently checks control-plane connectivity only; it does not yet expose deployment actions.
Do not treat this development scaffold as a replacement for the tested P0 distribution.

The new **Runtime OS Deployment Manager P0** opens at `/` or `/manager.html`.
Read [architecture, operation approvals and offline delivery](docs/DEPLOYMENT-MANAGER.md).
See the [single-host acceptance report](docs/MANAGER-ACCEPTANCE-20261116.md) for real test results and remaining boundaries.
Existing package installation pages remain at `/index.html`. Nodes now default to
read-only discovery (`OPS_READ_ONLY=1`); enabling operations requires the deployment
owner to set `OPS_READ_ONLY=0`, followed by per-operation preview and confirmation.

Independent offline Docker operations management. Includes an independent H2 manager and a signed, resumable multi-service offline installer. See [Test report](docs/TEST-REPORT.md) and [Known issues](docs/KNOWN-ISSUES.md).

## Install an OS environment

Start with **Offline packages**, then **Install OS**. The manager starts without PostgreSQL; the OS package provisions its own private application databases and services. Read the [Operator quick start](docs/OPERATOR-QUICKSTART.md), then [Environment installation](docs/ENVIRONMENT-BOOTSTRAP.md) for signing, source-image preparation, profiles, preflight and recovery.

## Existing installation

- Web: http://192.168.195.221:19220
- Server source: `/opt/deployment-ops`
- Compose project: `deployment-ops`
- Login: use the configured administrator credentials.
- Branch: `feature/deployment-ops-offline-20261115`.

Runtime OS, Cline and MCP are managed workloads, not runtime dependencies. Deployment-ops owns its backend, frontend, local storage and executor. H2 shares the Java process and persists in its dedicated volume; no Runtime OS service is required.

## Architecture and offline prerequisites

Java 17 provides authentication, roles, audits and the Web API. It has no Docker socket, host directory mount or shell endpoint. Only the separate Python executor mounts the Docker socket and uses allowlisted Docker Engine API operations. Protect its token and network because it has Docker host administration privileges.

H2 runs inside the Java Web process and stores management data in the dedicated ops-data volume at /data/deployment-ops.mv.db. No database server or database image is required. Run one Web instance per local database; do not share this file across replicas or a network filesystem. Web and executor also use a management bridge. No public API, CDN, package repository or SaaS authentication is needed at runtime.

Install Docker Engine 26+ and Compose v2 from offline media. Load these trusted image TARs before building:

```text
eclipse-temurin:17-jdk-jammy
python:3.11-slim
```

The default database is H2 2.3.232, bundled with Java. The PostgreSQL JDBC JAR is retained only for the explicit legacy migration tool, not as a runtime database dependency. Java dependencies and licenses are in `backend/lib`; checksums are in `docs/dependencies.json`. Python uses its standard library. The frontend uses local assets. No Maven, npm or pip download is required. Host Python 3 is used for optional checks; Node is only used for JavaScript checks.

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

On the existing server, preserve `.env`, local H2 credentials, the ops-data volume and executor token. Changing ADMIN_PASSWORD does not reset an existing database account; use Users and roles for password changes.

```sh
python3 scripts/check-docs.py
python3 scripts/build-frontend.py
docker compose config --quiet
docker compose build --pull=false
docker compose up -d --no-build --pull never
docker compose ps
curl -fsS http://127.0.0.1:19220/api/health
```

The Docker build compiles Java source with javac on the target server and packages the independent frontend directory. First startup initializes the embedded file database. Expect health status UP with database H2 embedded. Reuse a private HTTPS reverse proxy when available. Session cookies use HttpOnly and SameSite=Strict.

## Offline transport

On a preparation machine with the required images:

```sh
sh scripts/offline-images.sh export /media/offline/ops-images.tar
```

Transfer the TAR, its .sha256 file and this source directory. The export includes Web, executor and Java/Python bases. It excludes Registry and database/application data.

The import helper verifies the stored digest against the supplied path, so the export and import paths may differ. Run:

```sh
sh scripts/offline-images.sh import /new/path/ops-images.tar
```

## Web functions

- Install OS: signed multi-service installation, preflight, initialization, progress and compatible recovery.
- Offline packages: trusted releases, resumable import and version selection.
- Overview: real application, container, image and node counts.
- Applications / Containers: discovery, filtering, details, logs, raw metrics, start/stop/restart.
- Images: local inventory and checksum-verified TAR import.
- Deployments: single-container manifests, history and rollback.
- Nodes: executor registration.
- Application links: registered addresses plus ready installation links.
- Users and roles: administrator and read-only viewer accounts.
- Audit log: authentication and management records.
- Registry: repository/tag queries when the optional service is available.

The interface is English. Data comes from Docker nodes or the management database, not mock values.

## Legacy stateless application deployment

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

Upgrade briefly stops Web/executor to close and copy the H2 file, .env and executor journal, preserves recovery image tags, builds source, starts services and checks health. Failed health checks invoke image recovery. Recovery requires retained images; it does not undo destructive database migrations or restore application data.

Backups are in backups/TIMESTAMP and contain data/, environment, executor-state/ and a COMPLETE marker. They contain secrets. Copy them to secure separate storage. Never copy a live open H2 file as a consistent backup. Stop Web/executor before restoring matching data files, environment and journals, preserve UID/GID 10001 access to /data, then start services. First verify the snapshot with VerifyBackup in an isolated read-only container. Referenced retained application containers must still exist for journal recovery. Restore using the same H2 version before attempting any database engine upgrade.

```sh
sh scripts/lifecycle.sh stop
sh scripts/lifecycle.sh install
sh scripts/lifecycle.sh uninstall
```

Uninstall removes this Compose project's containers and networks but keeps its named volumes. It does not uninstall managed applications or delete their data.

## Troubleshooting and automated checks

```sh
docker compose ps
docker compose logs --tail 100 web executor
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

## New-environment bootstrap

The primary goal is to install Runtime OS and applications on a new offline host. The manager starts FIRST with H2; application databases are installed later as managed components. See [Environment bootstrap design](docs/ENVIRONMENT-BOOTSTRAP.md).

On a new Docker/Compose host, copy the manager source/release directory and verified image TAR, then run:

```sh
sh scripts/install-offline.sh /media/offline/ops-images.tar
```

No PostgreSQL, Runtime OS, Java host installation or Python host installation is required. If .env is missing, the script creates unique credentials using the already loaded executor image. Read the protected .env locally, set the desired private OPS_BIND, and run the installer again to apply configuration. It never overwrites existing .env or data volumes.

The current application wizard deploys one container. Complete Runtime OS packages still require dependency-aware infrastructure installation, persistent volumes, application schema jobs and environment profiles. This functionality is explicitly separate from the implemented manager bootstrap.

## One-time legacy management database migration

Do not run the new Web service against an existing PostgreSQL URL. Its supported runtime is embedded H2 only. To preserve an older manager installation, see [H2 migration](docs/H2-MIGRATION.md). Managed applications may continue using their own PostgreSQL service; that is independent of manager storage.
