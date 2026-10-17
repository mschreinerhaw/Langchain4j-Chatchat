# Runtime OS application candidate images

This directory packages the current API, MCP service, and Node Coding Agent. PostgreSQL and OpenSearch stay in their existing containers. The Compose file is for an isolated candidate: it binds API/MCP HTTP to host loopback only, uses separate PostgreSQL databases and an isolated OpenSearch index, and keeps Node on an internal network that cannot reach MCP directly. A small Nginx sidecar exposes Node to Java only on the API container's loopback interface, as required by `ClineCodingAdapter`.

Build on a Linux/amd64 Docker daemon from the repository root. Supply the Docker CLI binary from a trusted Docker installation compatible with the destination daemon; `Dockerfile.mcp` copies only that CLI, since the MCP service launches the existing Python Sandbox through the host Docker socket.

```powershell
./deploy/docker/application/build-images.ps1 -DockerCliPath ./target/docker-application-build/mcp/docker -Tag 9cc5ac8f-stage
```

The script rebuilds both Java JARs, builds all three application images, pulls the pinned Nginx proxy image, and exports one image archive with a SHA-256 digest. The Docker CLI is an explicit build input and must be recorded alongside that digest. No database password, model key, or internal credential key is baked into an image.

On the destination, create separate API/MCP PostgreSQL databases with the matching files under `database/init/postgresql/`, then prepare root-readable-only `api.env`, `mcp.env`, and `deployment.env`. `deployment.env` must set `STAGE_ROOT`, `API_IMAGE`, `MCP_IMAGE`, `NODE_IMAGE`, `NODE_PROXY_IMAGE=nginx:1.27.5-alpine`, `API_INTERNAL_KEY_FILE`, `MCP_INTERNAL_KEY_FILE`, `CODING_AGENT_SHARED_SECRET_FILE`, `CODING_AGENT_NODE_SECRET_FILE`, and `MODEL_CONFIG_FILE`. Copy `node-proxy.conf` to `$STAGE_ROOT/config/node-proxy.conf`. The Node secret file must contain the same secret as the API file and be readable by UID 1000/GID 32101 (for example, root:32101 with mode 0640); the host copy must stay inside the private stage directory. `api.env` and `mcp.env` supply the corresponding PostgreSQL URLs, users, and passwords. Point the API OpenSearch settings to the existing cluster and use a distinct stage index name. Run:

```bash
docker load -i /opt/runtime-os-docker-stage/images/runtime-os-app-stage-9cc5ac8f.tar
docker compose --env-file /opt/runtime-os-docker-stage/config/deployment.env \
  -f /opt/runtime-os-docker-stage/compose.yaml config -q
docker compose --env-file /opt/runtime-os-docker-stage/config/deployment.env \
  -f /opt/runtime-os-docker-stage/compose.yaml up -d --no-build
```

The current init SQL can trail entity changes. An isolated candidate may use Hibernate `update` once to bootstrap its empty databases, then restart with `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`. Production must use reviewed migrations. The candidate requires a published Python environment in its own MCP database before Coding Agent analysis can run. The MCP container has the host Docker socket and must be treated as a privileged application component. Keep the candidate on loopback and do not route production traffic until the full Runtime OS gate and live QA/data-analysis acceptance pass. `docker compose ... down` stops only the candidate containers and leaves existing PostgreSQL, OpenSearch, and application data untouched.

The 2026-10-17 candidate returned `SERVER_MISMATCH` after loading the existing host MCP license inside Docker. A container-valid license is required for licensed MCP tool calls; copying the host license does not satisfy that gate. The deployment evidence is in `docs/python-analysis-20261017-evidence/runtime-os-docker-stage-validation.json`.
