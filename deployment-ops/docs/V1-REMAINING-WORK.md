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
- Local latest regression: Java 11 tests and Python 47 tests passed; Vue production
  build passed. These do not replace Runtime OS acceptance.

## Additional real acceptance completed

- Browser imported the entire signed 6.2 GiB Native/Temporal Runtime OS release.
- The real Vue wizard submitted deployment c8ef8046c7504acf95075a2887ba2803.
  Initial host-network HOSTNAME incompatibility was diagnosed and fixed in the
  adapter. Explicit recovery completed all 24 services and source-product checks:
  administrator login, persistent Temporal, Native Core and Python runtime.
- Actual compatible upgrade 2a6d29cebc8d4bffbd92fbff740c5223 passed; ports changed
  to 19402/19403 and actual OS/MCP administrator identities were verified.
- Deliberate signed QA installer exit 42 triggered automatic rollback, task
  cfa606d3551a4adc981ca539188294e4 ended rolled_back. Other workloads unchanged.
  The QA package was retired after acceptance.
- Native offline ZIP rc.1 was checksum/inventory verified, compiled on the server,
  installed into empty state, logged in, and restarted with persistent H2 records.
- Platform rc.2 replacement and deliberately broken JAR recovery passed on that
  fresh instance. All 44 business container IDs/start times stayed unchanged.
  The extra fresh-instance services were stopped/disabled after acceptance.

## Remaining acceptance and delivery work (continue without phase stop)

1. Roll out the latest native executor health endpoint and modular service overview;
   verify actual Unix-socket Coding/Studio resource-policy health through Java/Vue.
   A method-name conflict found during live testing has been corrected; rerun it.
2. Regress latest Vue including image version tagging and independent TAR import,
   viewer restrictions, service probe edit/disable and enterprise overview layout.
3. Repeat H2 restore with explicit old-session invalidation, final native isolated
   startup/mount refusal, and platform restart business-container isolation.
4. Produce final coherent source-built native offline artifact, verify file/SHA
   inventory and clean installation; include Runtime media/public trust and docs.
5. Aggregate actual reports through the formal release gate; push all verified
   source and evidence. No formal V1 tag before all required real scenarios pass.

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
