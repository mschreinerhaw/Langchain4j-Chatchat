# Source-built container deployment

The deployment directory is the existing `/opt/runtime-os-docker-stage`. Retain
its configuration, secrets and data mounts. PostgreSQL and OpenSearch that have
already been restored are external dependencies and must not be recreated.

`Dockerfile` builds API, MCP and News from uploaded Java and frontend sources.
Application JARs and `target/` directories are excluded from the build context.
The Coding Node target uses the verified, fixed Cline 4.1.26 / Python 3.11 base,
checks its runtime integrity and Python imports, and copies the current Native
Server source. No legacy SDK Worker entrypoint is started.

## Build

Upload a ZIP of the current working branch, excluding secrets, `.git`, `target`,
`node_modules`, local runtime directories and generated archives. Include
`source-manifest.json` with `baseCommit` and a `files` array containing `path` and
`sha256` for every uploaded source file. Uncommitted changes are included and
must be identified as such; the base commit alone does not identify the image.

On the Docker host:

```sh
python3 scripts/build-source-stack.py \
  --archive /opt/runtime-os-docker-stage/source.zip \
  --root /opt/runtime-os-docker-stage/builds/UNIQUE_BUILD
```

The coordinator copies the Maven build tool and Docker CLI, removes host-specific
Maven settings and `JAVA_HOME` overrides, and builds all four targets inside
Docker. It imports the pinned unified base from the existing restricted daemon
if necessary. Supply the daemon/base arguments when using another verified
installation. A third-party Maven dependency cache can accelerate downloads;
application artifacts must still be compiled inside the build container.

Evidence under `builds/UNIQUE_BUILD/evidence` includes per-target logs, source
manifest hash, fixed base references and actual image IDs. Locally built images
have content-addressed image IDs; a registry RepoDigest exists only after a push.

## Activate

Back up the current Compose/configuration and databases before activation.
The migration helper requires an idle, verified Native deployment and pins exact
host process identities before stopping its previous Node/Python control roles:

```sh
python3 scripts/containerize-native-node.py \
  --stage /opt/runtime-os-docker-stage \
  --previous /srv/cs3-v5-20261111 \
  --image runtime-os-coding-node:source-20261111
python3 scripts/activate-source-stack.py --stage /opt/runtime-os-docker-stage
cd /opt/runtime-os-docker-stage
docker compose --env-file config/deployment.env up -d
```

These authority-generation helpers are first-activation tools. Routine restarts
use Compose and the existing private authority files. Never regenerate keys to
retry a task or silently replay an unknown session. Host-specific driver/TLS
paths must be set through the deployment dotenv file or `.env`.

The generic S3 service (`coding-node`) and existing Java business compatibility
adapter (`coding-business`) are separate roles of the same image. Their
Executors have separate task roots and capabilities. Only trusted Executors
access the pinned execution daemon. Core Workers run with network disabled,
read-only input mounts and memory/CPU/PID limits. The trusted Executor container
uses the host PID namespace to verify the existing daemon process identities;
this does not grant the Core Worker host process access.

The compatibility adapter retains its existing managed-Python protocol. Its
startup must not be represented as completion of the newer Java-to-generic-S3
business dispatch integration. Native S3 execution itself uses local Shell and
Python and does not call the remote Python Sandbox.

## Verification and rollback

Check actual HTTP authentication, News/MCP connectivity, Silo health, native
runtime integrity and real model execution. `container-silo-upload.py` verifies
each returned Silo object version and SHA-256 after a sealed real-model run.
`prepare-container-silo-input.py` supplies an explicitly synthetic Parquet object
for the same live test's `--input-plan` option. Controller credentials remain
private and never enter the Worker. These fixtures do not establish financial
business acceptance or authenticated Java SPI acceptance.

Keep failed builds, model failures and rejected transfers. The two historical
evidence test files require repository `docs/testing/evidence`; their fixtures
are deliberately excluded from runtime images. Run those checks against the
source checkout, and run the remaining runtime suite inside the image as root
to include the Linux Unix-socket HTTP tests.

Rollback uses the saved Compose configuration and retained image IDs, followed
by `docker compose up -d` for the affected application roles. Do not run
`down -v`, prune images/volumes, reset databases, or recreate the restored
PostgreSQL/OpenSearch containers. Reconcile active tasks before a version
rollback. The original pre-restoration application images may need rebuilding
or restoring before an old Compose file can be activated. PostgreSQL dumps and
the closed News H2 backup are recovery points, not automatic rollback actions.
