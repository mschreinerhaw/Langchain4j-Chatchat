# V1.0 remaining work and verified bootstrap — 2026-11-16

Status: **INCOMPLETE — not a V1 release**. Baseline: `8dbd6b915` on
`feature/deployment-ops-offline-20261115`. Historical P0 acceptance does not prove
the native Spring Boot product works. No release tag has been created.

## Repository assessment

The legacy `backend`, `frontend`, and Python `executor` contain the existing
authentication, audit, signed package import, Docker observation, deployment plans,
installation journals and recovery implementation. Preserve these implementations
and their existing tests when migrating. They have not been connected to the new
Spring Boot application. The Vue page checks connectivity only. `RuntimeAdapter`
is an interface; `ApplicationRelease` validates dependency graphs but executes none.

## Changes and executed evidence

- Native Java entry point checks actual Linux mountinfo before Spring/H2 startup;
  rejects absent mount, mount symlink, data outside mount, and ancestor symlink escape.
- H2 default path is under `/mnt/disk01`; removed working-directory fallback.
- Added native install/stop/restart/status helpers, Java 17 prerequisite validation,
  disk-based process logs and temporary directory configuration. Installation
  helpers are not yet installation-acceptance tested.
- Added network-free target-host source compiler using a prepared Spring Boot loader
  and dependency archive. Includes no precompiled application classes in that archive.
- Local `mvn -o -pl deployment-ops package`: 5 tests passed, 0 failures.
- Real server `192.168.195.221:5221`: Java 17 compilation passed without Maven/network.
  Server JAR SHA256: `fda1d2db16c5e68b5ee6678fb392d2a4bd5503610a4431ac7dd96fcb9c922153`.
- Real transient systemd service with private network and inaccessible Docker socket:
  startup and restart health `UP`, H2 file exists, shutdown leaves all previously
  running business container IDs, StartedAt and running state unchanged.
  The test service has been stopped. Existing deployment has not been replaced.
- First offline repackaging attempt failed because the classes directory ZIP entry
  was absent; corrected and recompiled on the server, then passed the real check.

Evidence: [server acceptance](evidence/native-bootstrap-20261116/server-acceptance.json)
and [server source build](evidence/native-bootstrap-20261116/server-build.log).
Generated Spring development password has been redacted from archived logs.

The isolation test proves independence when dependencies are inaccessible; it does
not claim Docker or the production databases were stopped. Production services
were deliberately left running. The mount namespace unmount experiment was rejected
by automatic approval review (`blocked by policy`); actual absent-mount startup
acceptance remains unverified. Unit tests do not replace that acceptance.

## Required remaining work, in dependency order

1. Migrate existing authenticated APIs and persistent users/roles/audit into Boot;
   implement schema version migrations and preserve P0 credentials/data explicitly.
   Remove development generated authentication from the production flow.
2. Package and install the Python executor as an independent local non-container
   service with least privilege, mount validation and durable state paths. Connect
   Docker discovery and operation plans; test disconnected Docker behavior.
3. Connect the real Vue management pages to those APIs, preserving confirmation,
   role checks, audits, import verification, logs and metrics.
4. Implement current Temporal Runtime OS release contract and dependency adapter,
   configuration/ports/volumes, immutable versions, upgrade and rollback history.
   Legacy SDK recipes do not qualify as current Runtime OS acceptance.
5. Add persistent sampled service health, alerts and host resource charts; implement
   Coding Agent/MCP/Python and multi-address DolphinScheduler checks.
6. Implement native consistent H2/config/executor-state backups, isolated restore
   checks, versioned platform replacement and failure recovery. Verify no business
   shutdown during platform upgrade, not just during restart.
7. Complete reproducible offline release assembly, dependency/license checksums,
   installation documentation and clean-host install/initialize/login acceptance.
8. Execute all required real acceptance scenarios, then commit/push and tag V1.0.

## Reproduce bootstrap source compilation only

This is a developer validation procedure, **not a full server installation guide**.
Source work directory on the server:
`/mnt/disk01/deployment-ops-v1-validation/source`.
The prepared dependency archive is its sibling `boot-runtime.zip`.

```sh
cd /mnt/disk01/deployment-ops-v1-validation/source
JAVA_HOME=/opt/jdk-17.0.5 TMPDIR=/mnt/disk01/deployment-ops-v1-validation \
  python3 scripts/build-native-offline.py --runtime ../boot-runtime.zip \
  --output ../lib/deployment-ops.jar
python3 native-server-acceptance.py \
  --java /opt/jdk-17.0.5/bin/java \
  --jar /mnt/disk01/deployment-ops-v1-validation/lib/deployment-ops.jar \
  --work /mnt/disk01/deployment-ops-v1-validation \
  --report /mnt/disk01/deployment-ops-v1-validation/acceptance.json
```

No complete offline V1 installation package, verified Runtime OS deployment, data
restore result, production login endpoint or V1 commit is delivered by this bootstrap.
