# Install Runtime OS in a new environment

The product starts with an independent H2 management service, then installs Runtime OS and its dependencies. PostgreSQL is an application component, never a prerequisite for the manager.

## Operator workflow

1. Install Docker Engine 26+ and Compose v2 from offline media.
2. Verify and load the manager image bundle, then run `scripts/install-offline.sh`. Preserve its generated credentials and dedicated volumes.
3. Register the target executor. On each target, provision the release publisher public key under `trust/<key-id>.json` using a trusted administrative channel. Restart/recreate the executor only when no installation is running.
4. Open **Offline packages**, select the target, choose the signed `.ops.zip` and enter its published SHA-256. Upload uses durable 4 MiB chunks; reopening with the same file/checksum resumes it. Verification runs on disk, outside H2.
5. Open **Install OS**, choose the release and node, give the environment a unique name, and enter its private address, available ports, real license host MAC and a new administrator password.
6. Review preflight and dependency order. Start installation, follow component progress/logs, and open the resulting application links after verification succeeds.
7. Export a profile to reuse non-secret configuration on another environment. Change its environment name/address/ports; supply new secrets. A profile is configuration, not a database backup.

For existing environments, leave secret fields blank to retain the current credentials. Credential rotation is blocked until it has a dedicated verified workflow. The environment name determines ownership and is not a cosmetic rename.

## Prepare a release from source

Use Python 3.11+ for release preparation. Build application images from the reviewed source on the release server, using its offline dependency caches. The target machine consumes pinned images and does not need Maven/npm/pip. No live application data, environment files or credentials are copied into a release.

The repository Cline adapter can also be built from source with a prepared npm cache, without package downloads:

```sh
python3 scripts/prepare-coding-adapter.py --source /source/coding-agent-node \
  --npm-cache /offline/npm-cache --base-image node:24-bookworm-slim \
  --output /large-disk/coding-adapter-context
# First load the matching Node base image from offline media.
docker image inspect node:24-bookworm-slim
docker build --pull=false --network=none \
  -t deployment-ops-coding-adapter:source /large-disk/coding-adapter-context
```

The preparer checks every npm tarball against `package-lock.json`, copies only explicit application source and required public package cache entries, and strips authentication headers from cache metadata. The server compiles the existing adapter and checks its actual bundled skills. No application SDK changes or online install scripts are introduced. Use this adapter image as `--node-image` below.

Prepare the search image from trusted, unpacked offline vendor plugins for OpenSearch 2.19.1:

```sh
python3 scripts/prepare-search-image.py --plugins /media/opensearch-plugins \
  --output /large-disk/search-image-context --build
```

The media directory contains `analysis-icu`, `analysis-ik` and `analysis-pinyin`. The builder checks engine versions, records software checksums and generates fresh dictionary settings. Preserve vendor licenses with the artifacts. Running search settings, indexes and business data are not release inputs. The recipe defaults to `deployment-ops-opensearch:2.19.1-offline` and verifies its plugin list before preparing a release.

Generate an offline publisher key using the built manager image (keep the private key outside source control):

```sh
mkdir -m 700 /secure/runtime-signing
# Use a host path appropriate to the release server.
docker run --rm --network none --user 0:0 \
  --mount type=bind,source=/secure/runtime-signing,target=/keys \
  deployment-ops-web:1.0.0 java -cp 'classes:lib/*' PackageSigner \
  keygen /keys/private.der /keys/public.json runtime-os-release
```

Copy only `public.json` to each target's `trust/runtime-os-release.json`. Verify its fingerprint through the offline distribution channel. Do not offer a Web endpoint for adding trust keys.

```sh
python3 scripts/prepare-runtime-os.py \
  --api-image YOUR_SOURCE_API_IMAGE --mcp-image YOUR_SOURCE_MCP_IMAGE \
  --news-image YOUR_SOURCE_NEWS_IMAGE --node-image YOUR_SOURCE_NODE_IMAGE \
  --gateway-image YOUR_SOURCE_GATEWAY_IMAGE \
  --version YOUR_RELEASE --output /large-disk/runtime-recipe
python3 scripts/build-package.py \
  --manifest /large-disk/runtime-recipe/manifest.json \
  --assets /large-disk/runtime-recipe \
  --private-key /secure/runtime-signing/private.der --key-id runtime-os-release \
  --signer-image deployment-ops-web:1.0.0 \
  --output /large-disk/runtime-YOUR_RELEASE.ops.zip
```

For a package already copied to the target server, the host administrator can avoid a browser upload with `python3 scripts/import-package.py /large-disk/runtime-YOUR_RELEASE.ops.zip`. It prompts for the management password, creates the same audited upload, copies only into its generated staging ID on the local Compose executor, then invokes the same signature and payload verification. This local CLI requires Docker host administration access and is not a Web shell capability. Remote nodes use resumable Web upload.

The preparation script bundles reviewed configuration and exports PostgreSQL DDL from the exact selected application images. The release-time exporter uses their packaged JPA classes and actual entity package scopes in a network-disabled JDK container, without starting application services or reading a live database. This includes knowledge-base entities scanned by MCP which the older repository MCP-only SQL omitted. `--repository-schema` is an explicit fallback only for releases whose committed DDL has been verified against the selected image scope. Select explicit infrastructure images with its command-line options; defaults include PostgreSQL 18, Redis, OpenSearch and Temporal. The PostgreSQL mount layout is for version 18. Other major versions need a reviewed recipe, not only a changed image argument. Every selected image must already exist locally. Optional `--image-archive` reuses a reviewed Docker-save TAR after checking that it contains every selected image ID. `--store-images` trades archive size for faster packaging; allow the extra disk space. `build-package.py` exports a deduplicated Docker-save TAR, pins image IDs/platforms, hashes every payload and signs the inventory with RSA/SHA-256. Use a new output path for every release.

This recipe uses the repository SDK adapter profile (`chatchat.coding.cline.backend=sdk`), with its image health probe and existing callback protocol. A raw Standalone native-worker image is not a drop-in adapter image. Existing governed native executors/daemons remain separately managed deployments; this recipe does not copy their live configuration or bypass their dedicated-daemon policy.

The complete recipe includes private PostgreSQL databases and transactional schema initialization, Temporal and its own database, Redis authentication, a private search CA/security setup, News, MCP, API, the repository Cline SDK adapter, its private proxy, an HTTP gateway and an authenticated administrator verification job. It generates fresh internal credentials compatible with the existing OS encryption format and seeds the initial MCP administrator using the supplied OS administrator password. Existing administrator rows are never overwritten. The OS license and model configuration remain real application prerequisites; no license or model service is fabricated.

Fresh API initialization also publishes the repository's formal default Runtime semantic policy when absent. This required configuration comes from the reviewed migration source, with its source checksum recorded in the asset. Startup validation remains enabled. The final verification requires OS and MCP login/identity checks to stay successful through a readiness window.

## Package contract

A ZIP contains `manifest.json`, `checksums.json`, `signature.json` and the exact signed payload inventory. Image TARs and configuration files are payloads. Paths must be relative ASCII paths without traversal, symlinks or duplicates. The trusted key verifies the signed inventory before image loading or container creation. All required images must match immutable `sha256:` IDs and the target CPU/OS. Existing tags may not be replaced with different images.

`manifest.json` declares schemaVersion 1, name/version/platform, images, typed parameters, named volumes, components, dependency edges, health gates, links, capacity requirements and upgrade compatibility. Components are services, bounded initialization/verification jobs or explicit private TCP dependencies. Container commands are reviewed, signed application instructions; the Web API provides no host shell, host bind mounts, devices, privileged mode or Docker socket for workloads. Limited capabilities are explicitly allowlisted.

See the generated Runtime OS manifest and `tests/test_installations.py` for executable examples. External-service recipes require their own non-destructive verification jobs; the supplied Runtime OS recipe provisions new isolated databases.

## Capacity and data protection

Packages default to a 16 GiB compressed limit and use the persistent `ops-packages` volume with a 1 GiB free-space reserve. Ensure enough space for the compressed upload, uncompressed archive and Docker's layer storage. Streaming avoids loading large archives into Web memory. Shared archive blobs are deduplicated; existing images and volumes are never pruned automatically. Failed staging may be retained for operator diagnosis. Check the filesystem actually backing `/opt` and Docker, not only `/`.

Preflight checks signature/content, architecture, real available memory, disk, immutable images, host/container port conflicts, resource ownership and declared upgrade compatibility. Owned resources are named `ops-env-<environment>-<component>` and carry installation labels. Existing unrelated resources are never adopted by name alone.

Upgrade capacity credits only anonymous resident memory already used by this environment, sampled from Docker. Reclaimable file cache receives no extra credit because it is already included in available host memory. A new environment must meet the full declared budget.

## Recovery and upgrades

The executor persists redacted jobs plus private resolved plans under `ops-state`, including per-step progress. Initialization jobs must declare a safe retry contract. Executor restart marks unfinished work interrupted; **Resume safely** reconciles only owned resources and retains data. Cancellation stops newly created services and retains volumes and diagnostic containers.

Compatible upgrades require `--upgrade-from OLD_VERSION` when preparing a release, which declares backward-compatible application data. Initialization jobs are skipped on upgrade. Data-changing always-run jobs and changes to schema-sensitive infrastructure image IDs are blocked. Retained previous containers enable manual rollback, and failed health gates automatically restore/recheck a compatible previous version. Rollback changes containers/configuration; it never rewinds database contents. Destructive schema migrations require a separate tested backup/recovery procedure.

**Stop environment** and **Start environment** act in dependency order and retain all volumes. Do not use individual container controls for installation-owned services. Keep manager/executor journals, H2, trust keys and original signed packages together in your recovery plan. The cold lifecycle backup includes H2/state/config/trust; keep the large immutable package volume or offline release media separately.

The manager's own upgrade uses the independent host lifecycle script and does not depend on Runtime OS. Do not run Compose recreation while an installation is active unless intentionally testing interruption.

## Acceptance scope

See TEST-REPORT.md for measured results. Same-host isolated environments exercise real Docker, SQL, health gates and recovery. They do not prove a second physical node, whole-server disconnection or a fully licensed business workflow. `tests/bootstrap-smoke.py` separately verifies manager bootstrap without PostgreSQL.
