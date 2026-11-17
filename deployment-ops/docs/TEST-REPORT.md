# Test report

Date: 2026-11-15 (Asia/Shanghai). Target: 192.168.195.221. Source directory: /opt/deployment-ops. Web port: 19220.

## Full Runtime OS acceptance (current)

Release: `source-20261115-r5`. Environment: `runtime-os-acceptance-v5`. OS Web: port 19300; MCP Web: port 19301. Package SHA-256: `f480f75f61a11e531b1705c1dde5babf8c591acd5ca8c8becab0b5f459e8878d`.

- All 15 installation steps passed on a fresh isolated environment: credentials/CA, two private PostgreSQL services, transactional application schemas and required default policy, Redis, Temporal, secured search, News, MCP, API, the repository Cline SDK adapter, private callback proxy, gateway and final verification.
- OS and MCP administrator login plus authenticated identity checks remained successful through the readiness window. No license or model result was fabricated; licensed/model business workloads were not part of this acceptance.
- A full-OS configuration upgrade changed the two ingress ports, replaced only the gateway and skipped initial database/credential jobs. Other service container IDs and both administrator accounts were preserved.
- Manual rollback restored the original gateway/container IDs, ports and authenticated accounts. This is a configuration release test; application binary version changes were separately tested in the multi-service fixture below.
- Stopping and starting the complete OS retained all service container IDs, database accounts and functioning ingress. The independent H2 manager continued accepting authenticated control requests while the OS was stopped.
- Thirty-three unrelated containers retained identical IDs, status and StartedAt values across the work. No existing business database, image or data volume was cleaned. Only explicitly generated obsolete transport copies and disposable, ownership-checked test volumes were removed.
- Final measured free space: about 26 GiB on the data disk backing `/opt` and Docker, and 7.9 GiB on `/`.

Evidence: [OS installation](runtime-os-install-evidence.log), [OS configuration/rollback/power lifecycle](runtime-os-lifecycle-evidence.log), [release preparation](runtime-os-package-evidence.log), [offline adapter source build](coding-adapter-build-evidence.log).

Fresh deployment required more than empty tables: the release now includes the formal repository Runtime semantic policy and offline ICU/IK/pinyin software plugins with fresh search settings. MCP DDL is exported from the exact image entity scope. The adapter image is compiled on the server from existing repository source using 329 checksum-verified cached npm artifacts, with build networking disabled; dependency versions and all 16 bundled skills passed their image gates. Search plugin software/dictionaries were staged without reading running search settings, indexes or business data.

## Signed multi-service installer

- Twenty-five local automated tests pass, including Java-to-Python RSA verification/tamper rejection, durable upload offsets, dependency validation, secret retention/redaction, ownership, interruption and compatibility gates. Java compilation and local JavaScript/document checks pass.
- Three signed real PostgreSQL/Python fixture releases were imported through the chunked Web API. First install created a private database, ran transactional initialization and verified persisted data through the app and a SQL job.
- Compatible upgrade skipped initial schema jobs. Manual rollback restored the previous app and retained data. Deliberate HTTP 503 health failure triggered automatic compatible rollback and rechecked old health.
- Restarting the independent executor mid-install marked the journal interrupted; resume reconciled owned resources and completed. Thirty-three unrelated container IDs/status/start timestamps were unchanged.
- Stop environment completed in dependency order for the three isolated fixture environments. Volumes/history remain retained.
- Chrome checked all twelve English pages with no corrupted text, duplicate navigation or page errors. Product checks passed for navigation, actual inventory, search/pagination, dialogs, wizard review, retry after API failure, mobile layout and server-enforced viewer write denial.
- The first full OS attempt failed closed at MCP schema validation: the repository MCP-only SQL omitted knowledge-base entities in the actual runtime scan. The release preparer now exports DDL from the selected image classpaths without application startup or live database access.
- Fixture results are distinct from the complete OS acceptance above and from licensed/model business validation.

## H2 bootstrap and transition

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
