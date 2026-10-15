# Runtime OS Coding Agent D1 联机验证记录（2026-10-15）

## 范围与结论

在 `192.168.195.221` 上使用独立 Java 进程（`18080`、隔离 H2 任务库）、独立 Node 容器（`127.0.0.1:8792`）、现有 MCP Python 控制面（`8090`）和已发布的服务端模型配置完成真实任务验证。数据是测试驱动创建的授权金融夹具和不可变输入快照，**没有向生产 API 提交 Coding 任务，也没有启用生产 Cline**。生产 API `8080/` 最后返回 200，MCP `8090/actuator/health` 最后返回 200。

模型生成、Python Docker 执行、技术错误修复、独立 Decimal 验算、六项证据回流、实际容器取消、累计预算拒绝及 Java 进程中断后的证据恢复均已在隔离链路实测。**D1 总门槛仍阻断**：服务端模型请求在 90 秒超时且未返回可核对的 provider 回执时，A2 保留未知启动意图和预算预留，任务在截止时间后仍为 `RUNNING`、`reconciliationRequired=true`。在没有可验证的 provider 停止/计费事实之前，不能伪造终态。因此 D2 未启动，G3 未通过。

## 本轮源码修复

| 位置 | 修复 |
| --- | --- |
| `PythonAdminController` | 给 `get`、`cancel` 和 artifact 路由的查询参数显式命名；原编译产物缺少 `-parameters`，重启核对曾在 HTTP 入口失败。 |
| `PythonOperationWorkspace`、`PythonDockerRuntime` | 在创建托管容器前把当前操作的输出目录赋给配置的容器 UID/GID，目录权限为所有者读写执行。输入和代码挂载仍只读。原容器写结果报 `PermissionError`。 |
| `McpPythonControlPlaneClient` | 把合法零字节 artifact 解析为 `byte[0]`；原客户端返回 `null`，证据哈希计算抛空指针异常。 |
| `ClineCodingAdapter`、`DurableCodingTaskService` | Node 会话失败/丢失后，只枚举已有 A2 代码启动意图；在租约接管、MCP 停止证明、独立验证和证据提交均成功时提交结果，不重新执行已完成代码。 |
| `ClineModelCallbackController`、`ClinePythonCallbackController` | 保留真实 provider/MCP 失败日志，把未知执行结果明确返回为 502，并要求后续核对。 |
| Node runner、容器 Compose | Node SDK 失败原因不再被吞掉；Node 回调地址及允许主机改由部署显式指定，服务器实测直接使用局域网 IP `192.168.195.221`。 |
| 测试驱动 | 使用真实 `CodingAgentSPI`、真实 A2 持久化、真实服务端模型连接和真实 Python Docker；不暴露新的生产任务入口。 |

## 实测任务

| 场景 | 任务与实测结果 |
| --- | --- |
| 正常端到端 | `d1-smoke-269bf3aedaad491da38e44029c5ad4a7`：`COMPLETED`，revision 16，模型 2 次、代码 1 次，独立验证 `PASSED`，六项引用可解析，结果 1 行。 |
| 技术错误修复 | `d1-smoke-944a1ba6745546b49caaf4d030bb696d`：模型先运行故障注入脚本，MCP 记录一条 `FAILED` 操作；模型读取诊断后重新生成代码，第二条操作 `SUCCEEDED`；最终 `COMPLETED`，模型 3 次、代码 2 次，验证 `PASSED`，六项引用齐全。 |
| 实际取消 | `d1-smoke-da9f358c2b1a40fda15b810254fa46c7`：在 MCP 报 `RUNNING` 后通过 `CodingAgentSPI.cancel` 停止；MCP 确认容器停止并报 `CANCELLED`，A2 终态 `CANCELLED`，revision 12。 |
| 预算耗尽 | `d1-smoke-027b9193042e4e59bc89584a6ee213b8`：2048 Token 累计预留上限拒绝第二次模型调用，终态 `BUDGET_EXCEEDED`，模型实际调用 1 次、代码执行 1 次。 |
| Java 进程中断恢复 | `d1-smoke-cf136d753d2a48ff864d47266a6e22b3`：Python 容器运行时强制终止隔离 Java 进程；MCP 保全成功结果。租约过期后新 Java 实例领取新 fencing token，核对旧操作并提交 `COMPLETED`（revision 13）；再次 `resume` 不增加 revision。六项引用可解析，结果 1 行，预算记录仍为模型 1 次、代码 1 次。 |
| provider 超时阻断 | `d1-smoke-529585c8ee094b6a811b4c05bc1a6044`：服务端模型请求超时，未取得 provider 回执；截止时间后重新恢复仍为 `RUNNING`（revision 6，`reconciliationRequired=true`）。这是当前 D1 未通过项。 |

隔离环境默认模型临时选用生产已发布配置的 `qwen3.5-plus`；仅复制加密配置到权限为 700 的隔离库，生产模型默认值未改。真实任务数据固定为 `0.10`、`0.20` 两笔交易，由独立验证计划使用 Decimal 核对 `0.30`。Node 容器不持有业务数据库凭据或 Docker Socket，所有数据来自受控输入快照。证据回流复用既有 Evidence Store。

正常任务的六项实际引用如下；重启后再次读取同一结果仍为 1 行，重复 `resume` 保持 revision 16：

| 引用 | 值 |
| --- | --- |
| `inputSnapshotRef` | `runtime-evidence:coding:input:d2a32156abaad7d30db60b5734333c1fa4f85e93af546b95cd7d1327fc52eafc4` |
| `codeArtifactRef` | `runtime-evidence:coding:6557e7ea26bd19890b209dda6820c668d30843ca1dfd8ba20ed8fbd07150f506:code` |
| `executionReceiptRef` | `runtime-evidence:coding:6557e7ea26bd19890b209dda6820c668d30843ca1dfd8ba20ed8fbd07150f506:receipt` |
| `resultDatasetRef` | `runtime-evidence:coding:6557e7ea26bd19890b209dda6820c668d30843ca1dfd8ba20ed8fbd07150f506:result` |
| `validationRecordRef` | `runtime-evidence:coding:6557e7ea26bd19890b209dda6820c668d30843ca1dfd8ba20ed8fbd07150f506:validation` |
| `evidenceBundleRef` | `runtime-evidence:coding:6557e7ea26bd19890b209dda6820c668d30843ca1dfd8ba20ed8fbd07150f506:bundle` |

## 验证与部署状态

- Node `npm test`：12/12 通过，覆盖 SDK 修复循环、授权工具、回调、fencing 和错误传递。
- MCP 路由与托管执行定向测试通过；新增路由级测试覆盖无参数名元数据时的 `get/cancel` 绑定。
- Java MCP 客户端零字节 artifact 回归测试通过。
- Java Adapter、模型回调和证据定向测试通过；隔离服务器上的上述任务是真实模型和 Docker 验证，不是 Mock 替代。
- 恢复路径的最后一处源码收紧限定为“仅检查按 A2 事件顺序记录的最后一次代码执行”，避免后续失败操作被更早的成功操作掩盖；该收紧已通过定向编译/测试，隔离服务器的重启实测使用了此前等价的单操作路径。
- MCP `8090` 已部署路由绑定及输出目录权限修复；部署前 JAR 备份在 `/opt/coding-deploy-stage-20261015/chatchat-mcp-server.pre-d1-binding.jar`。生产 API `8080` 未部署本轮 Coding 回调或启用 Coding。Node 修复仅在独立 `8792` 测试容器验证，原 `8791` 容器未替换。

## 未关闭风险与阶段门槛

1. 模型 provider 超时后的执行/计费事实不可核对。需要可检索的 provider 请求身份、停止确认或可审计的人工核对流程；未知费用继续占用预留。完成前不得把未知执行写成 `FAILED`、`CANCELLED` 或 `COMPLETED`。
2. Java 重启后必须等旧租约到期才能接管；实测可恢复，但恢复延迟可达租约期限。不能仅因进程重启提前夺取可能仍在运行的执行者。
3. D1 事件订阅复用 A2 持久事件和游标；尚未在外部 SSE/网络断线场景做联机续传验收。
4. 本次未覆盖生产默认模型的持续稳定性，也未执行 D2 主分析循环接线或 D3 金融故障注入对照。生产 Coding 保持关闭。

**门槛结论：D1 受控主链路与取消、预算、重启恢复已通过联机验证；D1 整体验收因 provider 超时未知结果仍未通过。D2 不启动，G3 不通过。**
