# Product direction: install Runtime OS into a new environment

## Purpose

The primary product is an offline installation and upgrade entry point for Runtime OS and its applications across different customer environments. Container operations are supporting diagnostics, not the primary installation experience.

A new environment must not already have Runtime OS, its database, its scheduler, or its registry. The deployment manager must start first and remain usable when those managed services are unavailable.

## Two separate data layers

1. Management data: local H2 embedded file database, owned by deployment-ops. It stores local accounts, registered nodes, links, audit records, and future installation plans. No PostgreSQL service is required to start it.
2. Application data: databases and persistent storage needed by Runtime OS or another package. These are installed as managed components, or explicitly mapped to approved existing services. They must never be reused as the manager's own storage.

The default manager is one Java process opening one local H2 file database. Do not share that file between replicas or across a network filesystem. If control-plane HA is required later, design a separate storage and failover mode rather than sharing an embedded file.

## New environment path

1. Prepare Linux and a supported Docker Engine/Compose installation from offline media.
2. Copy the deployment-ops bootstrap bundle and verify its checksums.
3. Run scripts/install-offline.sh with the manager image TAR. It loads images, checks the manager/executor image OS and CPU architecture against Docker, generates unique local credentials if missing, and starts Web plus executor with a local H2 volume.
4. Open the manager and import/select a compatible Runtime OS installation package.
5. Choose the target node and environment-specific parameters.
6. Run preflight and show a clear installation review.
7. Install required infrastructure services, initialize application schemas, deploy Runtime OS, then deploy optional applications in dependency order.
8. Run health checks, record the installed versions, and show ready-to-open application links.

Steps 1-3 are implemented for the manager. Steps 4-8 describe the next product workflow for a full multi-service OS package; the current single-container wizard is not a complete OS installer.

## Offline package contract (next implementation)

A release package should contain:

- Immutable package identity, Runtime OS version, supported CPU architectures and compatible upgrade source versions.
- Exact component image references/digests and their offline TAR files.
- Parameter schema with required values, defaults, constraints, secret flags and environment-specific overrides.
- Component dependency graph, health gates, persistent data ownership and port mappings.
- Explicit application schema initialization/migration steps executed by approved components, not arbitrary uploaded host shell scripts.
- SHA-256 inventory and a trusted signature/verification policy.
- Upgrade compatibility rules and recovery instructions, including data migration limitations.

Environment configuration is separate from the immutable release: private IP/domain, host ports, storage roots, secrets, resource settings and new-versus-existing application database choices. Exported reusable profiles must omit secrets by default.

Do not require target-side Maven/npm/pip downloads, an online registry, or per-environment application source changes. The release preparation environment builds images once for each supported architecture; the target imports and configures them offline.

## Preflight requirements

Check package integrity, architecture, Docker/Compose compatibility, disk capacity for both archives and extracted layers, memory requirements from package metadata, host port conflicts, writable managed storage, dependency availability and upgrade compatibility. Do not invent fixed capacity requirements without measurements for the real OS package.

Existing database integration must be explicitly selected and tested without destructive initialization. New managed databases receive dedicated volumes and credentials. Never erase or reinitialize an existing data directory as a retry strategy.

## Execution and recovery

The independent executor should persist each installation step, target environment, package version and result before advancing. Retrying a failed plan must reconcile owned resources and resume, not create duplicates. Ownership is scoped to an environment and package; discovered unrelated containers remain untouched.

Health success gates each dependency stage. Keep the last compatible version and persistent data. Container rollback alone does not reverse a destructive database migration. Such upgrades require an explicit backup and tested data recovery plan.

The manager's own upgrade remains an external host-owned operation. Its local database, credentials and executor journals are backed up together. Its recovery must not depend on starting Runtime OS.

## Large packages

A complete OS bundle may exceed the current 256 MiB Web import and 2 GiB executor limit. The full installer needs resumable streaming into a quota-controlled persistent staging area, path/symlink checks, archive size limits, integrity verification and explicit retention. Do not put images into H2 or buffer multi-gigabyte packages in Web memory/tmpfs.

## Current delivery boundary

Implemented now: embedded H2 manager storage; offline manager bootstrap; independent file backup; one-time legacy PostgreSQL management-data migration tooling; existing single-container application deployment.

Still required: package catalog/import, parameter profiles, dependency-aware OS execution, owned named-volume provisioning, schema initialization jobs, durable plan progress and full OS installation acceptance on a clean target. These are functional work, not another UI-only redesign.

## Repeat the bootstrap acceptance check

On a Linux Docker host with the built manager/executor images loaded, run `python3 tests/bootstrap-smoke.py`. It starts a separate deployment-ops-bootstrap-check Compose project on loopback port 19224, verifies local credential generation, H2 storage, exactly two services and repeat-install data preservation, then removes only its own labeled test containers/volumes. It refuses to reuse an existing fixture directory. No managed application is deployed or controlled.
