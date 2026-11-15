# Test report

Date: 2026-11-15 (Asia/Shanghai). Target: 192.168.195.221. Source directory: /opt/deployment-ops. Web port: 19220.

## H2 bootstrap and transition (current)

- Ten local automated tests pass: seven executor safety tests plus real H2 authentication/role/timestamp tests, failed-login query checks and immediate forced-process restart persistence.
- The H2 candidate was compiled on the supplied server with networking disabled for build steps. A read-only legacy migration into an isolated test volume preserved administrator credentials, node/link/audit records and identity sequences. Discovery and container restart persistence passed.
- Production migration copied 2 users, 1 node, 3 entries and 113 historical audit records. Sessions were intentionally invalidated; account password hashes were preserved. Subsequent audit IDs advanced correctly.
- The old dedicated manager PostgreSQL container was stopped. H2 health, authentication and Docker discovery continued working. The separate business database was not stopped or modified.
- Cold H2/environment/journal backup and restart passed. A read-only backup snapshot opened successfully in an independent H2 JVM without network access.
- Across the cutover, the status and StartedAt values of 33 unrelated containers were unchanged. Approximately 7.9 GiB remained free on the root filesystem.
- A separate fresh bootstrap fixture generated protected unique credentials and created only Web and executor. No PostgreSQL service was created. Repeating bootstrap preserved credentials and stored management data. Only those explicitly generated fixture containers/volumes were removed.

The sections below are historical baseline checks from the earlier PostgreSQL-backed manager, not a requirement to install PostgreSQL for the current release.

## Build and safety checks

Java 17 compilation, Node JavaScript syntax and local frontend asset checks passed. The server built from source using existing local base images, without Maven/npm/pip installation. Web, dedicated PostgreSQL and executor started independently.

Seven executor tests passed: invalid ID rejection, self-container protection, host-mount manifest rejection, protection of containers not owned by this system, failed-release restoration, authentication/route allowlist and interrupted-release recovery on restart.

## Nineteen server integration checks passed

1. Health.
2. Anonymous access denial.
3. Administrator login.
4. Real inventory.
5. Discovery of this system.
6. Self-stop denial.
7. CSRF denial.
8. Application link storage.
9. Healthy deployment.
10. Healthy upgrade.
11. Manual rollback.
12. Unhealthy release rejection.
13. Automatic previous-container restoration.
14. Viewer login.
15. Viewer read access.
16. Viewer mutation denial.
17. Invalid TAR checksum rejection.
18. Valid TAR import.
19. Operation audit records.

The deployment fixture used deployment-ops-acceptance:local, ops-app-acceptance and port 19221. Its image was built from the existing local Python base. TAR import used a separate small generated rootfs image without overwriting a business image. See [smoke-evidence.log](smoke-evidence.log).

## System recovery and isolation checks passed

- The external lifecycle upgrade script completed backup, build and health checks.
- Replacing this system's Web image with the acceptance image broke health as expected. The independent recovery command restored service.
- Backup SQL was imported into a new temporary database. User data was verified before dropping only that temporary database. The live management database was not overwritten.
- Web and executor were disconnected from the edge network, retaining only their internal network. Health, login and Docker inventory worked; the public entry point was then restored. This is not whole-server physical disconnection.
- The states and StartedAt timestamps of 27 unrelated containers were identical before and after the recovery test.
- The acceptance application was stopped after validation; history was retained.

See [recovery-evidence.log](recovery-evidence.log).

## Corrections made during verification

- Internal-only Docker networking did not publish the Web host port. A management edge network was added while PostgreSQL stayed internal.
- A manual SHA-256 field supports HTTP browsers without Web Crypto.
- The release HTTP timeout was extended to 240 seconds to allow health checks and recovery to finish.
- Windows CRLF broke shell execution. Shell scripts now use LF with Git attributes.
- An early test was interrupted during executor updates. Pending-release recovery was added and all 19 checks were rerun successfully. Interrupted records remain visible.
- Generated UI text had become literal question marks, causing repeated page labels and routes. English text and ten stable page IDs replaced it. Chrome verified login, all pages, unique navigation, text integrity, no page JavaScript errors and logout. See [ENGLISH-UI-FIX.md](ENGLISH-UI-FIX.md).
- Deployment documents had the same irreversible question-mark substitution. README, known issues and this report were rewritten as English ASCII. The document checker validates encoding, damaged text and local Markdown links.

## Capacity and limitations

Recorded root filesystem capacity during installation and recovery: approximately 45 GiB total, 83% used, 7.7 GiB available. This is historical evidence, not a live metric. No global Docker prune or business image/volume cleanup was performed.

Second-physical-node management, optional Registry operation, stopping existing Runtime OS services and full physical disconnection were not validated. Do not report these as passed. See [KNOWN-ISSUES.md](KNOWN-ISSUES.md).
