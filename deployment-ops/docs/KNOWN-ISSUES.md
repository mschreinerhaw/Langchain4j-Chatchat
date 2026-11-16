# Known issues and acceptance boundaries

- The manager uses one local H2 file and one Web process. No shared-file HA or live-file backup. Cold backup briefly stops management services.
- The signed multi-service installer and resumable package uploads are implemented. The legacy Deployments page remains for single-container stateless releases; its older TAR limits/recovery rules do not describe the Install OS workflow.
- Package retirement hides obsolete releases and blocks new installations; it intentionally does not delete archives, rollback containers, images or volumes. Failed upload/staging retention needs operator review. Never use global prune on the shared host.
- Stateful destructive migrations, credential rotation and infrastructure major-version upgrades need a separate tested data workflow and are blocked by default. Container rollback does not restore a database snapshot.
- The supplied OS recipe provisions new private services. Reusing customer databases needs a separately reviewed recipe and verification; it is not enabled by changing a host field.
- The supplied OS recipe uses the repository Cline SDK adapter profile. Existing governed Standalone native workers and their dedicated daemons require a separately reviewed release topology. Existing deployments are not converted by this installer.
- Valid OS licensing, model services/credentials and external business integrations remain application prerequisites. No license or model is fabricated. The manager itself runs offline; OS business functions requiring external providers need local alternatives.
- Optional Registry service requires its image on offline media. Existing repository/tag APIs are available; registry authentication administration, push UI and safe deletion are incomplete and not claimed as accepted.
- Multi-node registration and TLS configuration exist. Second physical host, private CA lifecycle and certificate rotation remain unverified. Same-host namespace tests are not physical multi-node acceptance.
- Deployment Manager P0 adds real host/container samples with seven-day retention, resource trends and bounded SSE logs. Background alert delivery and multi-filesystem host disk discovery remain future work. Node availability updates on refresh; expired samples are not displayed as fresh.
- The new manager uses local UTF-8 Chinese assets; legacy installation pages retain English assets. Built-in Web TLS termination, MFA and a secret vault are not provided; use the private reverse proxy and protect local state/backups.
- Generic Compose editing/deployment/rollback, interactive terminals and multi-host application scheduling are not part of P0. See [Deployment Manager](DEPLOYMENT-MANAGER.md) for exact scope and API limits.
- Whole-server network disconnection, stopping production Runtime OS and whole-machine disaster recovery were deliberately not performed on the shared server. Isolated bootstrap/recovery results must not be described as those destructive tests.
