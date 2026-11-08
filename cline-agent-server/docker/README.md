# Standalone POC Docker 构建与验证

最终镜像从 scratch 开始，只装入 Node、明确列出的系统命令及其 ELF 动态库、系统 CA、已装配的官方 Standalone runtime 和本服务。RUNTIME_BASE 只用于中间工具阶段；其 SDK/应用目录和旧镜像层不会进入最终镜像。不会启动旧 SDK Worker。

## 资源装配

先按 [服务说明](../README.md) 在固定官方提交完成 build:sdk、compile-standalone 主体和 Linux package。使用空的、位于源码目录之外的构建上下文：

```bash
mkdir -p /absolute/clean-context
python3 cline-agent-server/scripts/assemble-runtime.py "$CLINE_SOURCE_ROOT" /absolute/clean-context/runtime
cp -a cline-agent-server /absolute/clean-context/service
```

装配会拒绝非空目标，验证 archive 与官方生成的 `apps/vscode/proto/descriptor_set.pb` 存在，把整个生成 proto 目录装入 runtime，并生成逐文件字节数/SHA256 manifest。官方 `apps/vscode/src/standalone/utils.ts:23` 从 **runtime cwd** 读取 `proto/descriptor_set.pb`，随后以 proto-loader 解码；仅复制 cline-core.js 或仅有 HostBridge .proto 都不足以启动 ProtoBus。

启动前校验全部 manifest 文件及 descriptor 的 TaskService/WorkspaceService；Docker 构建还实际加载 native sqlite，验证 ABI。worker 的 cwd 固定为 runtime，运行时不依赖 upstream checkout。`sourceRoot` 配置项为兼容保留，装配 runtime 后可设 `/not-mounted/upstream-source`。

## 构建

工具镜像须有同 ABI Node、Python3、ldd、Git 和 assemble-rootfs.py 列出的 OS 命令。复用已验证构建环境时，先记录 `docker inspect` 的完整 Image ID；使用已核对本地 tag 或可信 registry digest，不把裸 `sha256:...` 当 FROM 仓库名。最终镜像不需要 Python、npm、全局 SDK、源码或构建器。

```bash
docker build --no-cache --pull=false \
  --build-arg RUNTIME_BASE=verified-local-toolchain:fixed-tag \
  -f /absolute/clean-context/service/docker/Dockerfile \
  -t cline-agent-server:4.1.26-clean-poc /absolute/clean-context
docker run --rm --network none --entrypoint node \
  cline-agent-server:4.1.26-clean-poc /poc/service/scripts/verify-runtime.mjs /poc/runtime
```

本次工具镜像 Node 24.21.0、ABI 137、Debian 13；native sqlite 3.50.4。Docker Hub 不可达，因此采用离线明确文件装配；不声称全新公共基础镜像下载验证、任意平台可复现或全量许可证审计已完成。

## 启动

私有 config 示例见主 README，目录放在 context 外；端口、工作区路径与实际映射一致。仅挂载 config、state 和两个真实项目副本，不挂载 runtime、service、上游源码或宿主 Docker socket。

```bash
docker run -d --name cline-agent-server-clean-poc \
  --user 1000:1000 --read-only --cap-drop ALL --security-opt no-new-privileges \
  --memory 3g --cpus 2 --cpuset-cpus 6,7 --pids-limit 256 \
  --tmpfs /tmp:rw,nosuid,nodev,size=128m,mode=1777 \
  -p 127.0.0.1:18327:18327 \
  -v /private/config.json:/run/cline-poc.json:ro \
  -v /private/state:/poc/state \
  -v /projects/editor:/poc/project \
  -v /projects/patch:/poc/project-patch \
  cline-agent-server:4.1.26-clean-poc
```

config port=18327、host=0.0.0.0；挂载项目目录包含 coding-agent-node。state/project 必须 UID1000 可写，config UID1000 可读且不公开。CPU affinity 根据宿主调整，并核验 Node availableParallelism=2。

`/health/live` 不鉴权；`/health/ready` 需要 Bearer token，返回启动时 runtime 校验摘要，**不代表编码任务完成**。镜像 HEALTHCHECK 从私有配置读取端口和 token，检查 ready；不将 token写入镜像或健康日志。另以 HTTP 新建 session，确认真实 native worker.ready 后取消清理。

负向资源检查：将空文件只读挂到 `/poc/runtime/proto/descriptor_set.pb` 后运行 verify-runtime，应非零退出并明确 integrity mismatch；恢复完整镜像后无需手工补文件。真实 HTTP/SSE 任务、审批与取消须全部通过后才判断 POC 验收。原始 runtime-manifest、Docker build log、镜像层、启动回执和任务证据一并留存。

## 真实任务与独立验证

在宿主准备 `ACCEPTANCE_ROOT/context/service`、`private/server.json`、`state`、`project/coding-agent-node`、`project-patch/coding-agent-node`。两个项目从同一完整 tracked archive 开始，包含原有 fixtures/skills、Git 基线和离线可用的项目依赖。config 中的 /poc 路径对应这些挂载，包含实际授权模型和随机 API token。

```bash
python3 "$ACCEPTANCE_ROOT/context/service/test/live-http.py" \
  "$ACCEPTANCE_ROOT/private/server.json" "$ACCEPTANCE_ROOT/evidence/editor" editor
python3 cline-agent-server/test/clean-docker-acceptance.py \
  "$ACCEPTANCE_ROOT" cline-agent-server-clean-poc
```

第二步先检查 Editor 完整原生回合，再独立构建/测试，继续同会话原生 Search，执行 Patch 专项并独立验证，最后执行真实拒绝/取消及 SSE 断线回放。所有步骤非零或缺少原生工具回执均停止并记录失败。验收器不修改生成实现/测试；独立检查前后生成文件 Hash 必须一致。取消后额外观察 35 秒，超过真实延迟写入的 30 秒期限。
