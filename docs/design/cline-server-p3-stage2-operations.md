# Cline Server P3 第二阶段操作协议

基线 `db60dec41`，专项分支 `feature/runtime-cline-cli-native-20261107`。范围仅为操作恢复、远端取消和执行证据。保留官方 v4.1.26 Standalone 的 Agent Core、工具选择、审批解析器及工具执行器；不恢复旧 SDK Worker，不实现 Java Agent Loop，不进入模型预算、Silo 或默认 Agent 切换。

操作身份由可信 HTTP 控制面在**原生审批放行前**登记。输入必须来自该 Worker 当前真实 pending permission：

```text
operationId = "cline-" + SHA256(canonical JSON array [
 tenantId, userId, publicSessionId, runtimeTaskId,
 nativeSessionId, nativeToolCallId, toolName
])
argumentsHash = SHA256(recursively key-sorted JSON arguments)
childPythonOperationId = "cline-" + SHA256(operationId + "/python")
```

`nativeToolCallId` 是官方 Agent 的调用关联值，并不独立构成授权。绑定还必须匹配签发的任务身份、原生 Session、Epoch、参数 Hash 和当前 Journal 租约。MCP JSON-RPC ID 只关联当前 HTTP 响应；更换请求 ID 不产生新操作。重放已缓存原生 MCP 回执时只重映射传输 ID，不能改写工具 result。

官方宿主仅增加两处上下文透传：`McpHubToolProvider.callTool` 将 `AgentToolContext.sessionId/toolCallId` 与 `CLINE_SERVER_EPOCH` 送到 `McpHub.callTool`；后者按显式环境开关写入 MCP `_meta.runtimeNative`。Worker 没有操作控制服务凭据，不能创建持久绑定。Broker 的 `/resolve` 只读取已登记绑定。Java 在现有 `reserve`、`startIntent` 提交后才向远端发送带签名的绑定。

绑定存放在现有 `CodingJournal.Task.privateState["native:" + operationId]`；意图仍是同一个 Journal 的 Operation，不另建 Node Journal 或操作数据库。恢复清单直接投影这些键，避免辅助索引与操作登记之间出现崩溃窗口。节点本地文件只保存实际执行节点的去重状态、PID/进程启动时间和回执，不能替代 Java 的派发授权。

| 状态 | 进入条件 | 可以离开的条件 |
|---|---|---|
| PENDING | 原生调用已持久绑定；尚无已确认远端运行 | 提交 Journal start intent 后派发；取消则需执行节点 no-start tombstone |
| RUNNING | 执行节点查询确认真实进程或 Sandbox 已运行 | 实际终态回执，或取消意图 |
| COMPLETED | 实际完成回执，范围、参数、Epoch/Fence、结果 Hash 与停止证明均通过校验 | 不因迟到取消改写 |
| FAILED | 执行节点明确失败且已停止，回执校验通过 | 不因迟到普通状态改写 |
| CANCEL_REQUESTED | 先提交取消意图，再调用远端停止控制接口 | 真实停止回执；已完成的操作保持 COMPLETED |
| CANCELLED | 实际执行进程停止，或持久 no-start tombstone 阻止该操作迟到启动 | 回执不可变；重复取消返回同一证明 |
| UNKNOWN | 请求/回执丢失、查询不可用、执行身份不完整或回执冲突 | 只通过真实查询、取消及证明对账转为明确终态；禁止自动成功和重派 |

`startIntent.dispatchIntentCreated=false` 一律查询已有操作。无缓存原生响应时返回 `OPERATION_QUERY_ONLY`，即使远端已完成也不伪造一个工具结果。CAS 重试仅限现有 Journal 的精确 revision 冲突；传输失败、过期 Fence 和 UNKNOWN 不触发派发重试。Python 的实际派发、请求指纹、容器命名、快照、no-start 取消、Fencing 和归档回执全部由现有 `CodingPythonExecutionBridge` / `PythonManagedExecutionService` / `PythonDockerRuntime` 执行。

取消分三层记录：原生 Worker 停止、远端执行停止、Java Journal 最终对账。`remoteStopConfirmed` 必须来自所有已登记操作的真实终态证明。控制面先复用 Java `cancel` 阻断新动作，分别停止原生 Worker 与远端操作，再调用既有 `finishStoppedAfterReconciliation`；没有停止证明时保留 UNKNOWN。Worker 容器退出与官方 Core 子进程退出均触发恢复，不重放 Agent turn。

MCP 普通 `AbortSignal` 或 JSON-RPC cancellation notification 不能证明服务器停止。本 POC 的真实独立 MCP 执行节点提供经过认证的 `/control/get`、`/control/cancel`。不支持此类可信操作控制协议的 MCP 服务不能宣称具备远端停止能力，必须拒绝纳入该门槛或保留 UNKNOWN。通用 MCP 业务服务的改造不在本轮授权范围。

进程启动与节点本地 PID 保存之间如果执行节点本身崩溃，缺少可证明身份的操作保持 UNKNOWN；代码不会把“没有 PID”自动当作“从未执行”。本轮故障注入覆盖 Worker、官方 Core、操作控制服务及 HTTP 传输；没有将执行节点自身任意写盘窗口崩溃宣传为全部恢复成功。

Docker 控制面目前仍是高权限可信执行面：Gateway 通过 Docker API 启停 Worker，现有 Python Runtime 通过 Docker CLI 启停 Sandbox，二者均需要宿主 Socket。挂载 Socket 即具备宿主级控制能力，`cap-drop` 或只读 Socket 挂载不能将 Docker API 降权。该风险没有因 POC 测试通过而消除。

最小权限整改方案供第二阶段评审：将公共 HTTP/SSE Gateway 与 Docker 启动权限拆开，前者无 Socket；受限执行代理仅接受固定镜像摘要、服务端派生工作目录、固定网络/用户/资源规格与归属标签，拒绝 host namespace、特权模式、任意挂载和任意 Docker API。租户任务继续无网络、无 Socket、无控制面配置。优先验证独立 rootless daemon 或专属执行 VM；执行代理和 Python Runtime 使用独立服务身份与凭据。代理故障必须保留 UNKNOWN，不扩大 Worker 权限。此方案尚未实施，需另行授权，不在本轮扩展架构。

本轮已限制新增服务挂载：MCP 仅能读自身配置及脚本并写自身状态；不能挂载 Gateway/JDBC 配置。MCP 使用单独凭据读取已存在绑定及访问 Python 控制接口，不能调用 Gateway 的登记/派发接口。Java 仅挂载所需类库、配置、Python 工作目录和 Socket；Worker 维持第一阶段的隔离规格。
