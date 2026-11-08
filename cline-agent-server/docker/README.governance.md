# P3.1 基础治理部署

设计：[隔离与身份](../../docs/design/cline-server-p3-stage1-isolation.md)；[阶段测试](../../docs/testing/cline-server-p3-stage1-20261108.md)。新增入口不替换 Runtime OS 默认 Coding Agent。

以已验证 `62ce5d9e5` 的 Standalone scratch 镜像为 POC_IMAGE，先 inspect 核对完整 ID。上下文只含 service/，不含私有配置、状态或工作区。

```bash
docker build --pull=false --build-arg POC_IMAGE=verified-poc:fixed-tag \
  -f service/docker/Dockerfile.governance -t cline-agent-server:p3-stage1 .
```

配置放在专属宿主目录，0600，必须在构建上下文之外。以下为字段示例，不含凭据：

```json
{
  "host": "0.0.0.0", "port": 18328,
  "signingKey": "<private random issuer key, at least 32 characters>",
  "workerImage": "sha256:<verified full image ID>",
  "stateRoot": "/control",
  "hostStateRoot": "/absolute/private/control",
  "maxWorkers": 2, "maxPerTenant": 1, "memoryBytes": 3221225472,
  "cpuSets": ["8,9", "10,11"],
  "modelProfiles": {"qwen": {"model": "<authorized real model>", "baseUrl": "<fixed approved /v1 URL>", "apiKey": "<provider secret>"}},
  "tenants": {
    "tenant-a": {
      "users": ["user-a"], "workspaces": {"project": "/templates/a"},
      "models": ["qwen"], "datasets": ["dataset-a"],
      "tools": ["read_files", "search_codebase", "editor", "apply_patch", "run_commands", "tenant_data__read_dataset"],
      "mcp": {"url": "<fixed authorized MCP URL>", "token": "<upstream secret>", "tools": ["read_dataset"]}
    }
  }
}
```

hostStateRoot 必须与 `/control` 的真实宿主挂载目录一致，供 Docker daemon 创建任务挂载。templates 只读、按租户/ref 由服务端选择，建议固定 Git archive；本阶段拒绝模板 symlink。任务副本设置 UID1000，不共享可变工作区。

```bash
docker run -d --name cline-p3-control --user 0:0 --read-only \
  --security-opt no-new-privileges --memory 512m --cpus 1 --pids-limit 128 \
  --no-healthcheck -p 127.0.0.1:18328:18328 \
  -e CLINE_GOVERNANCE_CONFIG=/control/config.json \
  -v /absolute/private/control:/control \
  -v /absolute/private/templates:/templates:ro \
  -v /var/run/docker.sock:/var/run/docker.sock \
  cline-agent-server:p3-stage1 node /poc/service/src/governance-server.mjs
```

控制面持 Docker socket，属于宿主高权限管理服务。不要开放到不可信网络，不在其中运行 Agent/租户代码。默认镜像 HEALTHCHECK 是旧 POC HTTP 入口，本模式关闭它，以治理 `/health/live` 及任务状态单独检查；不能将 health 当完整验收。

Worker 按固定模板创建，不挂控制目录根或 Docker socket。只挂自己的 workspace/state/bridge/config，network=none；自己的 Unix socket 分别用于 native API 和任务 broker。模型/MCP 经 Worker loopback18329 转私有 broker.sock，真正 provider/MCP 秘密不进入 Worker 配置。CPU affinity 按宿主核心设置。

## 本轮真实测试及复验

`test/tenant-data-mcp.mjs` 是独立真实文件 MCP 安全 fixture，使用官方 SDK server transport。`MCP_SDK_PACKAGE` 需指向固定官方 workspace 的 package.json，因 Standalone runtime 不公开 server SDK 模块。只读 SDK 源码/依赖挂载仅给测试 MCP 容器，不给 Agent Worker；它不修改生产 MCP，也不充当远端取消证明。

测试根目录准备 control/config.json、templates、mcp、两个私有数据文件及独立 MCP 服务。可信测试宿主执行：

```bash
python3 service/test/stage1-live.py /absolute/acceptance-root
python3 service/test/stage1-adversarial.py /absolute/acceptance-root
python3 service/test/stage1-revoke.py /absolute/acceptance-root
python3 service/test/stage1-sse.py /absolute/acceptance-root
python3 service/scripts/collect-stage1-evidence.py /absolute/acceptance-root /absolute/scrubbed-evidence
```

live 以私有发行密钥生成测试合同，两个真实模型任务并行；adversarial 在 Worker UID1000 下实际尝试文件/网络/Socket/MCP 越权；revoke 证明真实长 Shell 的本地停止。导出器不修改成果，只脱敏并保存原生回执、Diff、Hash、日志和失败历史。

异常重启只 fail-closed 并停止己方旧容器，**不会续跑任务或恢复外部操作**。保留 UNKNOWN/RECOVERABLE 供下一阶段审计；停止未确认的记录占容量，不得手工改成功。MCP/Python 远端取消未验收，明确 remoteStopConfirmed=false、CANCELLED_LOCAL_ONLY。不得作为完整企业级默认引擎。
