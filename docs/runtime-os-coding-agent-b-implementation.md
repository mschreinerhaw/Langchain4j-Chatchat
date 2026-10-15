# Runtime OS Coding Agent — B 阶段实现说明

日期：2026-10-15。前置门槛：已读取并核对 [G0 验收记录](runtime-os-coding-agent-g0-acceptance.md) 及 A2 源码；G0 的 152 项定向测试通过。本文记录本轮 B1 → B4 的代码和验证边界。G1 的最终判定见 [G1 验收报告](runtime-os-coding-agent-g1-acceptance.md)。

主要新增源码为 MCP 节点的 `PythonManagedExecutionService`、`PythonManagedOperationRecord`、`PythonManagedFenceRecord`、`PythonOperationWorkspace`、`PythonManagedInternalAuthFilter`，以及 API 侧的 `CodingPythonExecutionBridge`、`McpPythonExecutionProofVerifier`。修改既有 `McpPythonControlPlanePort`、`McpPythonControlPlaneClient`、`PythonAdminController`、`PythonCapabilityService`、`PythonDataFileService`、`PythonDockerRuntime`。测试新增 `PythonManagedExecutionServiceTest`、`PythonManagedRecoveryProcess`、`PythonOperationWorkspaceTest`、`PythonManagedInternalAuthFilterTest`、`McpPythonExecutionProofVerifierTest`、`McpPythonControlPlaneClientTimeoutTest`，并扩展原 `PythonDataFileServiceTest`。

## B1：异步执行控制

- `McpPythonControlPlanePort` 增加 `StartRequest`、`OperationView`、`start/get/cancel` 和产物读取；旧 `preview/executeTemplate` 签名保留。`McpPythonControlPlaneClient` 对新 start 使用既有 AES-GCM 源码加密与内部凭据，并支持凭 operationId 查询、取消及流式下载。
- `PythonAdminController` 增加 MCP 节点操作端点；`PythonManagedInternalAuthFilter` 限定这些端点只接受现有内部 Runtime 账户令牌，普通管理账户不能直接派发 Coding 容器。
- `PythonManagedExecutionService` 使用 MCP 现有数据源的 `mcp_python_managed_operation` 与 `mcp_python_managed_fence`，在返回 start 前持久化 `ACCEPTED`、请求 hash、稳定容器名、版本、预算引用和截止时间。任务级数据库行锁递增 fencing；同一 operationId 同一请求返回同一操作，不同请求拒绝。后台派发不占用长事务；Docker `start` 在持有任务 fencing 行锁的短事务内调用，阻止失效 token 越过启动边界。
- API 侧 `CodingPythonExecutionBridge` 先核对 A2 合同、scope、数据/网络授权、Lease、预算预留及 start intent，再调用现有 Python control-plane port。任务停止请求先进入 A2，再传到 MCP 节点；恢复时通过 `McpPythonExecutionProofVerifier` 复核带哈希的停止回执，并向 A2 结算已知用量下界，未知 CPU/费用继续保留预留。它不新增通用 Runtime 或 Evidence Store。
- 节点状态：`ACCEPTED`、`STARTING`、`RUNNING`、`UNKNOWN`、`SUCCEEDED`、`FAILED`、`TIMED_OUT`、`CANCELLED`、`FENCED`。`UNKNOWN` 不自动重复创建容器；查询时先按稳定容器名检查 Docker。

验证：真实 H2 JDBC 上检查重复提交、并发重复提交、不同请求冲突、失效 fencing、跨租户读取与未知启动意图不重派。真实 Docker 运行与独立 JVM 崩溃恢复见 G1。残余风险：节点信任专用内部 Runtime 账户，预算引用本身不是独立的签名能力凭证；必须经 API bridge 取得 A2 的权威准入。真实 MySQL/PostgreSQL 并发未在本轮执行。

## B2：任务执行隔离

- `PythonOperationWorkspace` 以 tenant/user/run/task/operation/attempt 的 SHA-256 路径段建立专属工作区。源码、参数、显式授权的文件先复制成快照并记录 SHA-256。重试读取已有快照并校验哈希，原上传文件变化不改变已接受的执行输入。
- `PythonDataFileService.snapshotAuthorized` 只复制指定 fileId，拒绝穿越、符号链接及非常规文件；`PythonDockerRuntime` 的 Coding 路径仅挂载该操作的 `scripts`、`input` 和 `output`，不挂载用户 uploads 根目录。源码与输入只读，输出目录可写。
- 接受前将 Docker 镜像解析为内容 ID；执行时按该 ID 创建容器。已发布环境版本及执行超时写入操作记录，派发前复核版本。

验证：独立工作区、授权输入复制与源文件删除后的快照稳定性、并发同名脚本的实际 Docker 结果隔离、路径拒绝。残余风险：工作区位于本地持久盘，跨机器部署需要共享且具备一致语义的存储；本轮没有跨机器文件系统验证。

## B3：取消与资源控制

- 容器名由完整执行身份确定。`docker create`、短事务内 `docker start`、`docker wait`、`docker inspect`、`docker stop`、归档后 `docker rm` 分开执行。取消先持久化 stop intent，再对指定容器停止并 inspect；查不到容器时保留 `UNKNOWN`，不把 HTTP/CLI 超时当作停止证明。
- 保存 `container_started_at`、deadline、环境超时、退出码和 OOM 标志；服务重启后通过稳定名称检查现有容器，并在超时时停止。失效 fencing 的旧容器被停止、保全日志后标为 `FENCED`；旧结果不会提交为权威成功。清理失败留 `cleanup_failed` 并定期重试。
- Docker `--cpus`、`--memory`、只读根文件系统及 `/tmp` tmpfs 仍起作用。累计 CPU、宿主 bind 输出目录磁盘额度、全部输出字节和费用没有硬隔离；回执明确标记这些维度未强制。

验证：真实容器运行中取消、带子进程和持续写入的取消、取消后容器停止及归档数据不再变化、fencing 接管、容器退出后恢复。残余风险：多机时钟偏差、Docker 守护进程不可达时的停止确认、容器资源计量未实现。

## B4：产物持久化

- 操作目录中的 `output/results`、`output/validation`、`output/logs` 分通道保存。容器停止后先将输出复制到持久 `archive`，再生成不可覆盖的 `manifest.json`，最后提交权威操作结果并清理容器。归档失败保留容器和 `UNKNOWN`，可重试核对。
- manifest 保存通道、文件名、大小、SHA-256、JSONL 行数、推断 Schema 与完整性。`MISSING`、`EMPTY`、`PARTIAL`、`PRESENT`、`UNVERIFIED` 分开；损坏或 Schema 改变的 JSONL 为 `PARTIAL`。`logs/receipt.json` 记录执行身份、代码/输入快照哈希、镜像 ID、预算引用、退出状态、OOM/停止确认及未强制资源维度。
- MCP 授权端点在读取前核对 manifest 与文件哈希，并流式传输归档文件；`manifest`、`code` 和 `input-{fileId}` 通道分别读取清单及经过快照哈希验证的原始代码/输入，供 C 阶段归集。原 `byte[]` 便捷方法只适合小文件。容器清理后仍可按相同 scope 读取归档产物。

验证：单元测试区分零行、缺失及部分 JSONL；真实 Docker 测试在容器删除后读取结果；独立 JVM 崩溃后从 H2 文件数据库定位容器并归档。残余风险：归档依赖本地持久盘，不具备跨机共享存储保证；Schema 是推断结果，业务规则及计算正确性留给 C 阶段验证。

内部产物通道：`manifest/manifest.json` 返回不可变清单；`code/{fileName}` 和 `input-{fileId}/{fileName}` 返回经过快照哈希核对的代码/输入；`results/{fileName}`、`validation/{fileName}`、`logs/{fileName}` 返回归档文件。全部读取携带相同 tenant/user/run/task/operation 作用域，普通管理员令牌无法访问。大文件调用 `downloadArtifact(..., OutputStream)`；`artifact(...)` 仅供小型回执与清单。

## 数据库迁移

新增 [H2](../database/migration/h2/V20261015_01__mcp_python_managed_operation.sql)、[MySQL](../database/migration/mysql/V20261015_01__mcp_python_managed_operation.sql)、[PostgreSQL](../database/migration/postgresql/V20261015_01__mcp_python_managed_operation.sql) 同构迁移。现有 `mcp_python_runtime_execution`、Runtime Evidence Store、A1 公共契约均未变更。开发配置的 JPA `ddl-auto:update` 可从两个 MCP 实体创建新表；生产应显式应用对应数据库迁移，不依赖自动改表。已有安装必须在启动新版本前完成迁移。迁移只增加 MCP 专用操作与任务 fencing 表，不建立第二套 Evidence Store。
