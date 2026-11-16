# Deployment Ops

Native Spring Boot + embedded H2 + modular Vue 3. The platform runs as two local
systemd processes, independent of Docker, Runtime OS and application databases.
The required real data mount is `/mnt/disk01`; missing storage prevents startup.
Stopping or upgrading the platform never stops business workloads.

Status and remaining acceptance: [V1 acceptance ledger](docs/V1-REMAINING-WORK.md).
A candidate build is not a formal V1.0 release.

## Install, deploy and operate

Read the complete [native operations guide](docs/NATIVE-OPERATIONS.md) for offline
installation, target-server source compilation, administrator initialization,
systemd controls, Runtime OS media import, deployment, upgrade and backup/restore.
The native offline distribution includes the JAR, source, dependency-only compiler
seed, trusted public keys, scripts and compiled Vue assets. No Maven/npm repository
is needed on the target server. Java 17 and Python 3.11+ are prerequisites.
Docker is needed only for business container operations.

## Product modules

- `src/main/java`: Spring Boot entry point, storage guard, authenticated native API,
  H2 backup/version migration, maintenance and persistent service monitoring.
- `backend/src`: preserved authentication/audit/resource APIs hosted by Spring Boot;
  signed-package publication helpers. The legacy standalone Web server is not
  started by the native distribution.
- `executor`: local Docker execution, immutable operation plans, ownership checks,
  signed offline media, persistent deployment jobs and Native/Temporal Runtime OS
  adapter. Application data is retained through stop, upgrade and rollback.
- `web-ui/src/router`, `layouts`, `views`: lazy domain routes and management console.
- `web-ui/src/api`, `stores`, `composables`: real services, session/resource state,
  polling and operation approval flows.
- `web-ui/src/components`: separate dashboard, applications, containers, deployment,
  metrics, tasks and system management components. There are no mock resources.
- `deploy/native`: installation, start/stop/restart/status and systemd definitions.
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

Offline dependency caches are required on a development machine when rebuilding
Vue. The delivered native source compiler uses only the bundled dependency seed:

```sh
JAVA_HOME=/path/to/jdk17 python3 scripts/build-native-offline.py \
  --runtime build/boot-runtime.zip --output lib/deployment-ops.jar
```

Real browser and server runners live in `tests/native-*.py` and
`tests/native-*.cjs`. They operate on explicitly scoped acceptance instances, not
production data. Execution evidence is in `docs/evidence/native-product-20261117`.
The formal packager requires every mandatory real scenario and matching evidence
checksums before it accepts a non-candidate version. It never creates a Git tag.

Historical verified P0 instructions are preserved in
[the archived P0 README](docs/HISTORICAL-P0-README.md). They do not define the native
platform installation procedure.
