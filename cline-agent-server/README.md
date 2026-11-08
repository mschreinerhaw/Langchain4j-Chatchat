# Cline Agent Server v4.1.26 POC

复用固定官方产品链：Standalone `common.initialize → SdkController → VscodeSessionHost → ClineCore 0.2.0 → AgentRuntime`。服务只实现 HostBridge、HTTP/SSE、审批关联、任务外层状态和本地进程停止治理；不实现 Java 执行器、Agent Loop、Editor、Patch 或 Shell。

这是单操作者、单活跃 worker 的开发 POC。当前提供真实项目编码与原生工具验证，不是生产多租户服务。原生回合结束为 `turn_completed`，`deliveryValidated=false`；独立验收另行记录，API 不凭模型文本发布“交付成功”。

## 构建

固定源码：`https://github.com/cline/cline`，提交 `a459f1d04dea030833b558be645011ab739c463b`（v4.1.26）。正式 VSIX SHA256：`d8ea4c3505052c48e7c7a5558e7213a53827b6ba1da7e56513c87d896df51c4d`。

在独立完整 Linux checkout 准备 Bun **1.4.2**（包括 bunx）、Node >=22、编译工具链及对应 native sqlite ABI；不使用已有 SDK 包代替源码。运行：

```bash
export CLINE_SOURCE_ROOT=/absolute/path/to/fixed-cline-source
bash cline-agent-server/scripts/build.sh
python3 cline-agent-server/scripts/assemble-runtime.py "$CLINE_SOURCE_ROOT" /absolute/path/to/runtime
node --test cline-agent-server/test/*.test.mjs
```

构建脚本执行官方 `compile-standalone` 的类型检查/lint/esbuild 本体，并显式采用官方 `package-standalone.mjs -s` 构建当前 Linux 平台，避免默认 post 生命周期为无关平台下载二进制。需匹配构建与运行 Node ABI。上游 root lock 与 Standalone 独立运行依赖 lock 分别留档；本 POC 未证明跨平台字节可复现构建或完成全部第三方许可清单。

`patch-upstream.mjs` 只修改四个宿主文件，并输出 before/after SHA256：启动日志与 ready 通知、原生事件观测、精确审批/追问 resolver 关联、可选的原生 Patch 路由配置。它拒绝重复应用。默认编辑仍使用官方模型路由；`editTool=apply_patch` 专项通过 Core 公开 `toolRoutingRules` 选择官方 Patch，不改 Patch 算法或伪装模型名称。

## 启动

服务器私有配置文件（0600，容器 UID 1000 可读）示例，替换占位符后保存在仓库之外：

```json
{
  "sourceRoot": "/build/fixed-cline-source",
  "distDir": "/poc/runtime",
  "stateDir": "/poc/state",
  "apiToken": "<random operator token>",
  "model": "<authorized real model ID>",
  "baseUrl": "<OpenAI-compatible base URL ending in /v1>",
  "apiKey": "<authorized credential>",
  "workspaces": { "monitor-editor": "/poc/project/coding-agent-node", "monitor-patch": "/poc/project-patch/coding-agent-node" },
  "host": "0.0.0.0",
  "port": 18326
}
```

```bash
CLINE_SERVER_CONFIG=/private/server.json node cline-agent-server/src/server.mjs
```

容器运行需挂载 runtime/service 只读、私有 config 文件只读及独立 state/project 工作区；以非 root、cap-drop=ALL、no-new-privileges、CPU/内存/PID 限额运行。**本次 cgroup v1 主机须同时设置 CPU 亲和性，例如 `--cpus=2 --cpuset-cpus=0,1`，并确认 Node `os.availableParallelism()` 返回 2**；单独设置 CPU 配额时 Node 实际看见 24 核，测试全集的默认并发曾触发 3 GiB OOM。只将宿主 `127.0.0.1:18326` 映射到容器端口，需要远程访问时用 SSH 端口转发。`docker/Dockerfile` 支持打包已组装的 runtime/service；构建上下文不得包含 private config、日志或任务资料。

本轮联机实例目录为 `/opt/cline-agent-server-poc-20261108`，容器 `cline-agent-server-poc`，端口 `127.0.0.1:18326`。凭据保存在该目录的私有配置中，不写入代码、镜像或公开证据。不替换现有业务 API、MCP 或 Coding Agent。

## HTTP

除 `/health/live` 外，使用 `Authorization: Bearer <operator token>`。这是一个授权操作者身份，未实现 Runtime 租户/任务级委托令牌。

- `POST /v1/sessions`：`{"workspaceRef":"monitor-editor","editTool":"editor"}`，201；只接受配置工作区引用，不接受任意宿主路径。
- `GET /v1/sessions/{id}`、`/status`：查询 worker/task/native session/epoch、待审批。
- `POST /v1/sessions/{id}/messages`：`{"text":"..."}`，必带 Idempotency-Key，202；繁忙时409，不静默覆盖当前任务。
- `GET /v1/sessions/{id}/events`：SSE，Last-Event-ID 回放；`Accept: application/json` 用于查询持久 journal。
- `POST /v1/permissions/{id}/respond`：`{"decision":"approve"}` 或 deny。工具调用、epoch、输入 Hash 在原生 resolver 内复核，重复同决议幂等，冲突409，过期批准410。
- `POST /v1/sessions/{id}/cancel`：202；先 native cancel，再停止所拥有的 worker/本地进程树并确认，确认后才 cancelled。
- `GET /v1/sessions/{id}/messages`、`/diff`：原生事件历史与实际工作区 tracked diff。
- `GET /v1/sessions/{id}/artifacts`：本 POC 未接企业归档，返回空清单且 `deliveryValidated=false`。
- `GET /health/ready`：API 配置状态；worker.ready 另有会话事件。不得将此接口当成真实编码任务验收。

新 session 需要先取消现有 worker；一个 worker 内可继续发送新回合。SSE 断线不取消任务。API 重启会把旧会话标为 recoverable，拒绝盲目重放 native/远端副作用；自动恢复、日志压缩和保留期限尚未实现。

## 真实验收

本次真实结果、源码差异和保留的失败记录见 [验收报告](../docs/testing/cline-server-runtime-tests.md)。官方未修改 Standalone 对照、HTTP Editor/Patch 主任务、原生搜索、审批拒绝、运行中取消与 SSE 回放均有证据；完整 VS Code Desktop A/B 与生产治理尚未验收。

正式 descriptor 资源装配及 scratch 镜像的补验另见 [Docker 构建说明](docker/README.md) 和 [干净 Docker 报告](../docs/testing/cline-server-clean-docker-tests-20261108.md)。启动时先校验整个 runtime manifest；ready 返回该摘要，native worker readiness 仍由会话事件确认。

`test/live-http.py` 在宿主运行，用真实模型修改独立真实项目；保存 HTTP、SSE、原生工具事件、diff 和概要。`test/verify-real-project.mjs` 只独立检验生成结果，不修改文件；其中 fetch fixture 仅用于验证所生成监控客户端的参数行为，模型与 Agent 工具执行不做 mock。`test/controls-http.py` 验证原生权限拒绝及长运行 Shell 的实际进程停止。

```bash
python3 service/test/live-http.py /private/server.json evidence/editor editor
node service/test/verify-real-project.mjs /poc/project/coding-agent-node
# 保存证据后经 cancel 释放 worker，再在相同起点副本验证 Patch。
python3 service/test/live-http.py /private/server.json evidence/patch apply_patch
python3 service/test/controls-http.py /private/server.json evidence/controls
```

最后一项独立验证在相同工具链、已构建的真实项目内运行；不得由验收程序代替 Agent 补代码或补做其漏掉的构建。

## 当前边界

Headless 终端沿用官方 backgroundExec/createShellExecutor；IDE diagnostics、交互窗口/编辑器和 diff UI 未实现时明确返回 UNIMPLEMENTED，记录 host.unsupported，不伪造成功。后台编辑保留原生审批与磁盘写入。

单 worker 策略避免本 POC 共享进程状态，但不等于租户隔离。Shell 仍能访问容器内其 UID 可读的挂载；没有通过 native 文件工具范围控制或 OS 级多租户沙箱验收。模型凭据由单次批准的测试 profile 注入；企业网关逐调用预算、租约/fencing、MCP/Sandbox 远端停止与 Operation Journal、Skills/MCP 动态变更、完整恢复及 OpenAPI 合同仍需后续集成。禁止把这些未验证项标为 PASS 或切换生产默认 Agent。
