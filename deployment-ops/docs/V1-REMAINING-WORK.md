# Deployment Ops V1.0 acceptance ledger - 2026-11-17

Status: **INCOMPLETE. Candidate only; no formal V1.0 tag.**
Baseline `8dbd6b915`, branch `feature/deployment-ops-offline-20261115`.

## Implemented and executed

- Native Spring Boot + embedded H2, shared persisted authentication, roles and audit,
  existing signed-package and Docker APIs integrated without discarding verified code.
- Modular Vue: lazy routes, console layout, domain API clients, stores, reusable
  tables/charts, dashboard, hosts, images, containers, applications, deployment
  wizard, task details, alerts, audit and separate system management components.
- Real confirmed Docker volume/create/start/logs/metrics/stop/delete browser flow:
  17 checks passed; persistent volume retained on the data disk.
- Native source compilation on target using JDK17 and offline dependency seed.
  Two native systemd services; no business-service stop propagation.
- Persisted configurable HTTP/TCP service checks, alert transitions, settings/users.
- Consistent H2 backup, verified restore and session invalidation. Actual restore
  recovered the before-backup record and removed the later record; all existing
  business container identities/start times stayed unchanged.
- Actual isolated-network native install/start/login/API/H2 restart persistence
  acceptance passed. Required dependencies inaccessible; platform remained UP.
- Actual missing-mount refusal passed in a transient private systemd mount view;
  host /mnt/disk01 stayed mounted. No host unmount or recursive business chown.
- Local latest regression: Java 11 tests and Python 46 tests passed; Vue production
  build passed. These do not replace Runtime OS acceptance.

## In execution / requires actual acceptance

1. Current Native/Temporal Runtime OS adapter: signed scoped publisher capability,
   immutable image pins, source-built product installer, isolated project/data path,
   preflight, durable job/logs, explicit recovery and compatible rollback.
   A 6.2 GiB signed offline package was produced from the already source-built
   13.4 GB image archive. Browser import and fresh platform-driven deployment
   are in progress. No Runtime OS delivery claim until this passes.
2. Actual application compatible upgrade, deliberate failure and rollback.
3. Actual platform upgrade success and failed-candidate recovery, preserving all
   business containers. Add explicit recovery health result in the journal.
4. Assemble candidate native offline ZIP with JAR, source/compiler seed, scripts,
   trusted public keys, checksums, documentation and executable acceptance tests.
   Install from that immutable ZIP into fresh server state and verify login.
5. Rerun isolation/missing-mount acceptance against final built artifact; archive
   browser/API/source-build evidence and exact hashes/commit IDs.
6. Complete operations documentation, final complete regression, checkpoint/push.
   Formal tag requires every mandatory real acceptance to pass.

## Environment and scope

Candidate URL: http://192.168.195.221:19240
Candidate home: /mnt/disk01/deployment-ops-v1-candidate/release
Candidate state: /mnt/disk01/deployment-ops-v1-candidate/state
Services: deployment-ops-v1 and deployment-ops-v1-executor.
Never stop or adopt existing production containers for tests. Never delete data
volumes. Runtime OS tests use a new ops-env-* project and separate short data root.
DolphinScheduler multi-address checks are implemented, but no real cluster endpoint
has been supplied; actual cluster acceptance remains conditional/unverified.

## Resume and evidence

- tests/native-product-browser.cjs: actual page and container lifecycle acceptance.
- tests/native-runtime-browser.cjs: actual multi-GB package import and Runtime wizard.
- tests/native-platform-live.py: actual H2 backup/restore and business isolation.
- tests/native-server-acceptance.py: native independent startup and mount refusal.
- target/native-browser-lifecycle-r2/result.json: 17 passed browser checks.
- docs/evidence/native-integration-20261116/restore-acceptance.json.
- Server /mnt/disk01/deployment-ops-v1-validation/full-native-acceptance.json.
- Server /mnt/disk01/deployment-ops-v1-validation/runtime-browser/ (active run).
- Server /mnt/disk01/ops-publisher-20261116/ contains signed Runtime media.
  Private publisher keys are restricted there and must never enter Git or releases.
