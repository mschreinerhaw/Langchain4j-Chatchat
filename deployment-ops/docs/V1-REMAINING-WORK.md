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
- Local latest regression: Java 11 tests and Python 51 tests passed; Vue production
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

The modular native console, real Docker lifecycle, image TAR import/version tags,
three native Unix-socket executor probes, RBAC, configured health check edit/disable,
consistent H2 restore/session invalidation and isolated startup/missing-mount refusal
all have real execution evidence. Native application stop/start/stop also passed;
all original 20 workloads were unchanged and every persistent directory/volume was
retained. Only the 24 stopped containers created by this acceptance were removed
after strict project/image/mount checks to free memory for the final cold install.

1. Freeze source and build the final coherent candidate JAR on the target server.
   Install that exact offline distribution into empty release/state directories;
   import the complete signed media and submit a fresh Runtime OS GUI deployment.
2. During that deployment, restart only the two native platform services and
   explicitly resume the durable task through the page. Verify the same installer
   and all existing business container identities/start times are retained.
3. Verify actual gateway image replacement, a deliberately failing gateway after
   replacement, automatic image rollback and retained application data. Previous
   configuration/version upgrade and early-installer failure already passed.
4. Regress the latest unified service alerts and final compact monitoring layout;
   finish all source/build/browser/server reports and the native offline bundle.
5. Aggregate reports through the formal source/JAR/evidence gate, verify the final
   archive inventory/SHA, publish the verified commits and only then tag V1.0.

Checkpoint ad2304370 is pushed. Formal release is still gated; do not stop at this
checkpoint or describe candidate acceptance as completed V1 delivery.

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
