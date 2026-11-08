# P3 根因与整改矩阵（第一阶段）

基线 `62ce5d9e5`，分支 `feature/runtime-cline-cli-native-20261107`，官方 `v4.1.26 / a459f1d04dea030833b558be645011ab739c463b`。任务书 SHA256：`a2c24feef6f0473961f142851f6d6159a250336795a9c883b117db6d7936c34a`。

本轮只完成任务书第十五节授权的“架构审查及第一阶段基础实现”。用户要求的三个硬门槛中，先交付租户隔离基础证据；操作恢复、MCP/Python 远端停止仍未验收。不得因此进入预算、Silo 或 Java 集成。相关：[隔离设计](../design/cline-server-p3-stage1-isolation.md)、[第一阶段测试报告](../testing/cline-server-p3-stage1-20261108.md)。

## 根因、具体调用点和依赖顺序

| 门槛/领域 | 基线缺口及调用点 | 本轮最小处理 | 剩余门槛 |
|---|---|---|---|
| 任务身份 | `src/server.mjs:authorize` 只比较单一 operator token，`sessions` Map 没有 owner | `task-contract.mjs:verifyContract/authorizeSession` 校验 HMAC 任务合同，绑定 tenant/user/task/session/ref/profile/权限/期限；入口统一 owner 检查 | 尚未接 Runtime 实际身份发行系统/JWKS；本轮为私有可信发行密钥的真实授权验证 |
| API 越权 | 原 status/events/diff/permissions 以 ID 查全局 Map | 新 governance-server 先校验合同所属 Session，再转任务专属 API socket；跨身份统一404 | 正式角色/管理员观察权限未建设，不开放全局列表 |
| 文件和网络隔离 | 基线 `startWorker → fork` 位于共用容器，Shell 可读该 UID 可见挂载；workspacePath helper 不约束 Shell | `docker-engine.mjs:workerContainerSpec` 每任务 Docker：独立 workspace/state/bridge，network=none、UID1000、只读 rootfs、无 Docker socket/端口 | 同宿主 kernel 风险、磁盘配额/资源压力与其他内核攻击未验收 |
| 原生共享状态 | 官方 HostProvider/common/StateManager 是宿主全局状态，不能当租户边界 | 每任务独立 native 进程、配置、HOME、CLINE_DIR；复用原产品初始化，未新改 Core | 跨 Worker Session 迁移未实现 |
| 并发与资源 | 基线 POST sessions 只允许单活跃 worker；没有租户级配额 | 新 Worker Scheduler：全局/租户限额、原子预留 slot、镜像 pin、独立 cpuset/cgroup；满额429；未知停止状态占用配额 | 无持久排队、公平调度、磁盘限额、完整健康自动剔除，不能称生产 Pool 完成 |
| MCP 数据 | 基线未接原生 MCP，不能以管理端登录替代工具验收 | 官方 `SdkController.ts:337 → McpHub` 从私有 data/settings 加载授权；任务 broker 校验 tool+dataset 后转实际 MCP | 本轮使用独立真实文件 MCP 安全 fixture；生产 Runtime MCP 及动态增权/撤权/Schema 更新仍待下一阶段 |
| 工具授权 | 原生请求能经 HTTP 批准，但没有任务级授权上限 | server.mjs 的 native.permission 分支对合同 toolPermissions 不匹配者直接原生 deny；客户端不能重新批准 | 工具目录/授权版本的运行期更新与 fencing 尚未实现；不按猜测前缀授权 |
| 运行中撤权 | POC token 无独立租约；关闭 SSE 不停止任务 | 新 revoke：先失效 broker、关闭观察连接/在途转发，再 native cancel 与 Docker stop/inspect；记录 local proof | 仅本地 Worker；远端操作停止和副作用对账没有因此完成 |
| Session 恢复 | server.mjs 启动加载 session.json 后只标 recoverable；`persist` 非数据库事务 | control registry 原子文件替换，异常重启标 UNKNOWN/撤权，核对 container label 后仅停止旧资源，禁止自动重放 | **硬门槛2未过**：不能判定每一外部操作完成与否；尚无外部事务/outbox/Operation Journal、单调 Epoch/Fencing、PostgreSQL/Silo 快照和受控续跑 |
| 外部操作幂等 | 本地 commands Map 只防重复消息；MCP transport JSON-RPC ID 不等于 durable operation ID | 仅保留基线消息幂等与 create 的合同/session/key 去重；未包装虚假操作回执 | 必须在工具转发前 durable intent，绑定 RuntimeTask/nativeToolCall/epoch/requestHash，再按远端 receipt 对账，禁止盲重试 |
| MCP 远端取消 | 原 `cancel → TaskService.cancelTask → kill本地进程` 没有操作句柄和远端 receipt | 配置 MCP 时原生本地取消明确 `remoteStopConfirmed:false`；治理终态用 CANCELLED_LOCAL_ONLY | **硬门槛3未过**：原生 McpHub 的 client 断线/abort 不是服务端停止；必须独立测试真实长 MCP 作业 |
| Python Sandbox | Cline Server 没有映射 native toolCallId 到 managed operation | 只审查，不新增 Java 执行逻辑。现有 `McpPythonControlPlanePort:start/get/cancel` 可复用 | `PythonManagedExecutionService.java:141` cancel 已有 scope/fence/stop；:180 调 `PythonDockerRuntime.stopManaged`，:157/165 以实际 inspect 确认。尚未与新 Server 调用链关联或故障注入 |
| Runtime 恢复治理 | `DurableCodingTaskService.java:326/334` cancel/resume、:435 阻止未对账新工作；`CodingJournal` 已承载操作治理 | 保留既有实现，无 Java 修改 | 下一阶段经受控协议复用，而不是将该类重新变成 Agent loop；必须证明已完成操作不重放、未知操作不误判 |
| 模型与预算 | 基线直接注入真实 API key；usage 不能替代累计预算 | 仅隔离所需的定址字节转发，真正 provider key 留在控制面，Worker 只有任务 broker token | Runtime 模型网关、reservation、重试累计预算均未接；budgetPolicy 明确 DEFERRED，不得宣传已完成预算 |
| 交付归档 | artifacts 返回空/deliveryValidated=false，turn_completed 不等于业务成功 | 保持原语义；本轮文件 Hash/Diff 仅作为测试证据 | Silo 元数据/业务校验/归档幂等必须在前三门槛评审通过后实施 |
| Java 集成 | 仍有既有 SDK 路径；没有新 Server generated Client | 零 Java 改动，默认 Coding Agent/旧 SDK 不变 | OpenAPI/Java Client 和迁移开关留待后续授权，不能现在替换默认路径 |

## 已暴露并修复的实际问题

1. MCP 安全 fixture 最初从 Standalone runtime require server SDK 失败：官方 runtime 将客户端库打包，不公开该 server 包。fixture 改从固定官方 workspace 的 SDK 依赖加载；该只读源码挂载仅在测试 MCP 容器，Agent Worker 没有此挂载。不是 Cline 原生工具缺失，也没有模拟结果。
2. 初次新 Session 在 native ID 尚未建立时 status 返回409：位于 governance-server 的路由次序。现为返回 CREATED/STARTING 及 native=null，不把启动中当不存在。
3. 真实 MCP 工具名是 `tenant_data__read_dataset`，原按 `mcp*` 推测未匹配，server 的任务策略真实 deny。改为精确原生目录名；保留拒绝与工具失败记录，不回写为通过。
4. 资源状态复核发现 UNKNOWN/启动失败不能直接释放配额；现由 holdsCapacity 在未证实停止时保留 slot。slot 在异步分配前预留，不用异步 active 数量推断 CPU 位置。新增回归后在最终镜像重新执行双租户任务及恶意探测。
5. 撤权竞态复核：HTTP 正文可能在初次鉴权之后、撤权之后才读完；现于实际转发前再查 live lease，过期的 create 也拒绝。晚到 native status 不覆盖已撤权状态。以真实分段 HTTP 正文重现窗口，要求403且没有 native 模型派发。
6. 证据导出也是信任边界：不能对 Agent 可写的 Git 配置运行宿主高权限 Git，也不能直接跟随 workspace symlink。导出器改为停止确认后，以可信模板做直接字节比对；evidence_files.py 使用目录FD/O_NOFOLLOW，拒绝特殊文件与越界路径，2项实际文件回归通过。该问题是审查发现的潜在风险，没有把读取别租户数据当作允许的导出行为。

下一步只能是评审本轮范围、身份/威胁模型、证据和剩余风险。未经该停点评审，不执行 Worker kill 后续跑、真实 MCP/Sandbox 长作业取消集成，更不进入预算、Silo、Java。
