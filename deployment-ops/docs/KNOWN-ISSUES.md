# Known issues and incomplete acceptance

This first release does not satisfy every original requirement.

- Registry: optional Compose service and repository/tag queries exist. The supplied server lacks its offline image. Authentication management, push UI, digest comparison and safe image deletion UI are incomplete. No automatic image cleanup is performed.
- Releases: image TAR and JSON manifest are separate inputs, not a single signed offline package. Only single-container, no-volume deployments are supported. Multi-service orchestration, stateful migration and database schema rollback are unsupported.
- Monitoring: real Docker stats are exposed as raw details. Historical time series, trend charts and background alerts are incomplete. Node availability and related audit records are updated on inventory refresh.
- Multiple nodes: registration and TLS configuration exist, but a second physical host, private CA installation and certificate rotation remain unverified.
- Offline acceptance: internal-network-only health, login and discovery passed. Whole-server physical disconnection and stopping existing Runtime OS services were not performed on the shared server.
- System recovery: source upgrade, deliberately broken Web image recovery and SQL backup restoration into a separate temporary database passed. Whole-machine disaster recovery, destructive schema migration recovery and build failure scenarios remain unverified.
- Interrupted releases: executor startup attempts to restore pending releases. Failures are marked recovery-required and need operator investigation. This is not an all-failure transactional guarantee.
- Manual rollback: retained container configuration is reused, but successful startup is not a full HTTP health gate or a business data compatibility guarantee.
- Authentication: no built-in HTTPS termination, MFA, external secret vault or full session administration UI. Use an existing private HTTPS proxy. Executor tokens are stored in the dedicated database; protect it and its backups.
- Compatibility: Docker API v1.45 requires Engine 26+. Tags are not pinned by digest. Preserve trusted offline archives, checksums and provenance.
- Uploads: Web limit 256 MiB; executor limit 2 GiB. Executor temporary files use tmpfs and consume memory. Large-image disk staging and upload progress are incomplete.
- Embedded storage: one Web process owns one H2 file database. No shared-file HA, automatic cross-engine conversion or live-file-copy backup. Cold backup briefly interrupts the manager.
- Browser validation: English login, all ten pages, unique navigation, rendered text and logout passed in Chrome. The optional Registry page reports unavailable; this is not proof of a functioning Registry service.

The manager now bootstraps without PostgreSQL. A full multi-service Runtime OS installer remains distinct from the current single-container application wizard; see ENVIRONMENT-BOOTSTRAP.md.
