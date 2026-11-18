# Deployment Ops

Native Spring Boot + embedded H2 + modular Vue 3. Start the local fat JAR with
`java -jar lib/deployment-ops.jar`; the JDK executor runs inside the same JVM.
The platform starts independently of Docker, Runtime OS and application databases.
Choose platform and application storage directories on the first-install page.
Single-disk servers work; a dedicated mount is an optional administrator policy.
`/mnt/disk01` is an old server's storage configuration, not an installation requirement.
Stopping or upgrading the platform never stops business workloads.

Status and remaining acceptance: [V1 acceptance ledger](docs/V1-REMAINING-WORK.md).
A candidate build is not a formal V1.0 release.

## Install, deploy and operate

Read the complete [JDK operations guide](docs/JDK-OPERATIONS.md) for offline
installation, target-server source compilation, administrator initialization,
systemd controls, Runtime OS media import, deployment, upgrade and backup/restore.
The native offline distribution includes the JAR, source, dependency-only compiler
seed, trusted public keys, scripts and compiled Vue assets. No Maven/npm repository
is needed on the target server. The platform requires JDK 17+ without host Python.
Docker is needed only for business container operations.

## Product modules

- `src/main/java`: Spring Boot entry point, storage guard, authenticated native API,
  H2 backup/version migration, maintenance and persistent service monitoring.
- `backend/src`: preserved authentication/audit/resource APIs hosted by Spring Boot;
  signed-package publication helpers. The legacy standalone Web server is not
  started by the native distribution.
- `src/main/java/.../execution`, `docker`, `protocol`: JDK Docker execution,
  immutable plans, ownership checks, signed media and persistent deployment jobs.
  Application data is retained through stop, upgrade and rollback.
- `executor`: preserved legacy external Python executor; not required by the single JAR.
- `web-ui/src/router`, `layouts`, `views`: lazy domain routes and management console.
- `web-ui/src/api`, `stores`, `composables`: real services, session/resource state,
  polling and operation approval flows.
- `web-ui/src/components`: separate dashboard, applications, containers, deployment,
  metrics, tasks and system management components. There are no mock resources.
- `deploy/jdk`: install/start/stop/restart/status scripts and systemd example.
- `deploy/native`: legacy external-executor installation compatibility files.
- `scripts`: offline source build, native packaging and authenticated local import.

## Build and validate

```sh
cd web-ui
npm ci --offline
npm run build
cd ..
mvn -o package
python -m unittest discover -s tests -p 'test_*.py'
```

Python tests above cover legacy compatibility on the development machine; Python
is not a target-host dependency. Offline caches are needed when rebuilding Vue.
The delivered source compiler uses only JDK and the bundled dependency seed:

```sh
java scripts/BuildNativeOffline.java \
  --runtime build/boot-runtime.zip --output lib/deployment-ops.jar \
  --version VERSION --source-commit SOURCE_COMMIT
```

Real browser and server runners live in `tests/native-*.py` and
`tests/native-*.cjs`. They operate on explicitly scoped acceptance instances, not
production data. Execution evidence is in `docs/evidence/native-product-20261117`.
The formal packager requires every mandatory real scenario and matching evidence
checksums before it accepts a non-candidate version. It never creates a Git tag.

Historical verified P0 instructions are preserved in
[the archived P0 README](docs/HISTORICAL-P0-README.md). They do not define the native
platform installation procedure.
