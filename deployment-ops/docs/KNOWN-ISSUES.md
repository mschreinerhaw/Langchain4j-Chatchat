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
- Real Docker resource metrics exist; historical time series and background alert delivery are not implemented. Node availability updates on refresh.
- Browser pages use local English assets to avoid the earlier corrupted source text. Built-in TLS termination, MFA and a secret vault are not provided; use the private reverse proxy and protect local state/backups.
- Whole-server network disconnection, stopping production Runtime OS and whole-machine disaster recovery were deliberately not performed on the shared server. Isolated bootstrap/recovery results must not be described as those destructive tests.
