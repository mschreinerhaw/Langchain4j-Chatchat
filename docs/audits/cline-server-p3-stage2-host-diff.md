# P3 第二阶段复用审查与宿主差异矩阵

本轮只增加 Server HostBridge 上下文与 HTTP 操作协议适配；没有重新实现 Cline Agent Loop 或现有 Java Operation Journal。最终 Hash、实际差异与执行对账位于 [证据目录](../testing/evidence/cline-server-p3-stage2-20261108/README.md)。

| 调用点 | 审查结果 / 本轮处理 | 边界 |
|---|---|---|
| `DurableCodingTaskService` / `CodingJournal` | 复用 checkpoint、reserve、startIntent、租约/Fence、cancel、reconcileStopped、finishStoppedAfterReconciliation | 新增已有 `native:` 元数据的有界 CAS 对账，以及只读绑定投影；无新 Journal、表或执行器 |
| `JdbcRuntimeEvidenceStore` | 复用现有 PostgreSQL owner partition 锁、事务、revision、内容 Hash 和 checkpoint 分块 | 原实现未修改；隔离库应用现有迁移 |
| `CodingTaskRevisions.mutate` | 精确匹配 revision conflict 才重试纯 Journal 变更 | 不重试外部 I/O、不吞 Fence 错误 |
| `CodingPythonExecutionBridge.start/reconcile` | 复用发布环境验证、CODE 意图、稳定执行 Handle、已有操作只查询及真实回执验证 | 原实现未修改；不启用 Asset/Silo Archiver |
| `PythonManagedExecutionService.start/get/cancel` | 复用持久 ACCEPTED、请求指纹、稳定容器身份、租户范围、no-start tombstone、旧 Fence 拒绝 | 原实现未修改；无共享服务或数据库配置变更 |
| `PythonDockerRuntime.createManaged/stopManaged` | 使用真实 Docker Sandbox 与停止前后检查 | 原实现未修改；没有 Java 自定义 Python/Shell 执行路径 |
| `McpPythonExecutionProofVerifier` | 复用实际 operation 查询、归档 receipt 与 SHA256 证明 | 原实现未修改；普通 Worker 退出不能通过该验证 |
| 官方 `sdk/vscode-runtime-builder.ts` / `McpHubToolProvider.callTool` | 将真实 AgentToolContext Session/Tool Call/Epoch 传到宿主 MCP 层 | 官方 Agent Core/Agents/工具选择不变 |
| 官方 `services/mcp/McpHub.ts` / `callTool` | 可选 MCP `_meta` 上下文，原 timeout/signal/request/result 路径保持 | 只在显式开关开启；JSON-RPC ID 不作为持久身份 |
| Server `server.mjs` | 传入显式操作上下文环境开关 | 沿用原生审批解析器、原生事件与工具回执 |
| Server `governance-server.mjs` | 审批前登记、Broker 只解析已有绑定、真实远端取消、Worker/Core 丢失检测 | 修复 STARTING 被状态查询提前投影为 READY 的竞态；无原生工具执行代码 |
| `NativeOperationAuthority` | 服务身份/范围验证、身份映射、签名、HTTP 查询/取消和 Journal 对账 | 不选择工具、不运行模型或项目代码 |
| `NativeStage2Application` | 在新隔离进程装配现有 Journal / Managed Python / Repository | 不扫描启动业务 API，不替换共享 Spring 服务 |
| `stage2-remote.mjs` | 真实隔离 MCP 任务节点；实际 Node 子进程或已有 Managed Python | 不是模拟回执。新增工具仅用于隔离长任务验收，不代表所有现有 MCP 服务已支持停止协议 |
| `stage2-fault-proxy.mjs` | 转发真实请求/响应或丢弃传输，记录字节 Hash | 仅测试使用，从不生成工具结果/执行回执 |

官方源码是在单独副本构建；第一阶段运行目录与镜像均保持。基线已有的四处 Standalone 宿主适配继续保留；本轮增加上述两处官方宿主文件。最终 [宿主差异 Hash](../testing/evidence/cline-server-p3-stage2-20261108/source/upstream-host-diff-matrix.json) 和 [Core/Agents 全文件 Hash](../testing/evidence/cline-server-p3-stage2-20261108/source/core-source-hashes.json) 是实际比较结果，构建生成文件若存在额外格式差异也保留记录。

失败边界：`McpHub.callTool` 的 signal 只能取消 MCP 客户端等待；原始协议没有强制远端查询/停止回执。未接入可信远端控制协议的工具必须保留 UNKNOWN。本轮没有修改共享 MCP 以掩盖这个差异。执行节点在派发/PID 保存窗口自身崩溃也可能无法证明最终结果，代码明确保留 UNKNOWN。
