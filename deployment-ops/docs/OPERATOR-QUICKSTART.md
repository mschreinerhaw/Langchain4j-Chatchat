# Operator quick start

Open http://192.168.195.221:19220 on the acceptance server and use the configured local administrator account. The manager uses its own H2 file and starts before any application database exists.

## Install an environment

1. Install Docker Engine 26+ and Compose v2 from offline media. Load the manager/base image TAR and copy this source directory. Run `sh scripts/install-offline.sh /media/ops-images.tar`. Read the generated administrator credentials locally and configure the private listener.
2. Provision the publisher public key under `trust/<key-id>.json`. Use **Nodes** for remote executors, or the automatically registered local executor.
3. In **Offline packages**, import the signed `.ops.zip` with its published SHA-256. Selecting the same file/checksum resumes an interrupted upload. For media already on the local server, use `python3 scripts/import-package.py /media/runtime-release.ops.zip`.
4. In **Install OS**, choose the release and target. Enter a unique environment name, private address, available ports, real host identity and an initial administrator password of 12-128 permitted characters. Internal credentials are generated automatically under Advanced credentials.
5. Review the target, release, capacity and dependency order, then start. Use **View progress** for status and component logs. Open application links after the authenticated administrator checks pass.

The example provisions private application databases, Redis, Temporal, TLS search, News, MCP, API, the repository Cline SDK adapter and a gateway. Activate the actual OS license and configure local model/provider services for business workloads.

## Reuse configuration

**Export profile** saves non-secret settings. Change its environment name, target address, ports and actual host identity for another installation. Supply new administrator credentials. Existing environments retain their credentials when secret fields are blank.

Profiles contain configuration only. Keep signed release media or a secured copy of the immutable package volume with recovery storage.

## Operate and recover

- Use **Stop environment** and **Start environment** to preserve dependency order and data volumes.
- Review the failed component log, then use **Resume safely** for an interrupted installation. Completed initialization evidence and retry contracts protect data.
- Select the same environment for a compatible upgrade. **Restore previous version** rechecks previous services and retains application database contents.
- Upgrade the manager from its source directory with `sh scripts/lifecycle.sh upgrade`. Its independent lifecycle backs up management data, journals, configuration and trust before building. Schedule this when no installation is running.

Source and lifecycle on the acceptance server are under `/opt/deployment-ops`. Signed OS releases and recipes are under `tests/runtime-os-release`. The protected `credentials.json` there contains the acceptance OS and newly initialized MCP administrator credential; the username is `admin`. Customer installations use the password entered in their own wizard.

See [Environment preparation](ENVIRONMENT-BOOTSTRAP.md), [Test report](TEST-REPORT.md) and [Known issues](KNOWN-ISSUES.md) for publisher commands, measured results and remaining boundaries.
