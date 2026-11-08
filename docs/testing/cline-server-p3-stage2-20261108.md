# P3 第二阶段：操作恢复与远端停止测试报告

基线 `db60dec41`，分支 `feature/runtime-cline-cli-native-20261107`。本轮限定场景的真实外部操作持久化、去重恢复、取消传播和最终对账门槛通过，提交第二阶段评审。该结论不等于企业生产上线或 P3 整体通过。未进入模型预算、Silo 归档或默认 Coding Agent 切换，未改动共享 MCP/Sandbox/模型配置和现有服务。

交付：[证据索引](evidence/cline-server-p3-stage2-20261108/README.md)、[复用与宿主差异矩阵](../audits/cline-server-p3-stage2-host-diff.md)、[映射/状态机/权限方案](../design/cline-server-p3-stage2-operations.md)、[Docker 构建与复现](../../cline-agent-server/docker/README.stage2.md)。原有真实编码任务验收属于基线；本轮真实模型任务专门验证原生外部工具与故障恢复，不冒充重新完成全部编码任务或业务 MCP 验收。

| 门槛 | 实际验证 | 结果 |
|---|---|---|
| 优先复用 Java 治理 | 现有 JDBC Journal、事务/CAS、租约/Fence、cancel、Managed Python、归档回执验证 | 通过；无新 Journal 或 Java 执行器 |
| 可信 operationId | 原生审批放行前绑定；参数不可变；改变 JSON-RPC ID 仍为同一操作；冒用 ID/变造参数拒绝 | 通过 |
| 派发前持久意图 | `reserve → startIntent → HTTP/MCP`；请求丢失后 UNKNOWN、不重派，再由执行节点持久 tombstone 阻止迟到启动 | 通过 |
| 真实 MCP 长任务 | 官方 Agent 经 HTTP 请求、真实模型自主调用 `tenant_data__long_mcp`，远端实际进程已 RUNNING 后杀 Worker | 取消回执及最终对账通过 |
| 真实 Python Sandbox | 官方 Agent 自主调用 `tenant_data__python_execute`，现有 Managed Python 启动真实容器后取消 | 原始容器/镜像/代码/输入身份、exit 137 和停止回执通过 |
| Core 子进程退出 | 仅杀官方 `cline-core.js`，外层 Worker 仍运行 | 检测异常、独立远端取消与最终对账通过 |
| 控制服务恢复 | kill authority 时远端任务仍 RUNNING；重启后从原 PostgreSQL checkpoint 查询真实完成结果 | 通过，没有重派工具或重建 Agent Loop |
| 重复派发/取消 | 同操作不同请求 ID、执行中重复派发、重复取消 | 一次实际执行；查询/缓存或明确 query-only；停止证明不变 |
| 回执丢失/迟到 | 代理丢弃真实响应，或延迟真实已完成响应后取消 | 查询实际回执恢复 COMPLETED；迟到取消不改写完成结果 |
| 旧 Epoch/Fence | 租约实际过期后接管；旧原生派发与旧远端 Fence 拒绝；既有 Managed Python 旧 Fence 单独实测 | 通过；Python 归档回执 Hash 不变 |
| Journal 扩展边界 | 真实 PostgreSQL 测试停止后对账、禁止新键/其他协议、CAS/Fence 约束 | 7 项通过 |
| 原生内核保留 | Core/Agents 611 个文件 Hash 全部相同；本轮官方宿主仅 2 个文件变化 | 通过 |

最新协议测试 21 项、故障注入 6 项、真实模型链路 18 项检查均通过。另有原有 Node 边界测试 8 项与新增证据完整性/来源检查。官方 Standalone 类型检查和构建成功；Java 适配及既有服务扩展以实际发布 Jar 为 classpath 编译成功，真实 JDBC/Docker 集成验证通过；未声称跑过完整 Maven reactor。

证据共收集历次 45 个 Java Operation、37 个远端执行节点记录，最终未对账 start intent 为 **0**。三个最终真实模型任务均有 Java CANCELLED 终态与操作级实际停止证明。协议驱动测试只验证操作，未冒充业务 Agent，因此对账表保留其 Journal task 的 RUNNING 状态与操作的 COMPLETED/CANCELLED 状态，二者不可混用。请求丢失时曾进入的 UNKNOWN 必须经过真实 no-start 回执；响应丢失时必须查询真实完成回执，未将 UNKNOWN 自动标为成功。

原生任务使用真实 `qwen3.7-plus`，通过带签发任务身份的 HTTP 接口提交，审批数据取自官方 pending permission。原始 native 事件、MCP response 和远端 receipt 均保存；取消时 Agent 等待可能被中止，不能要求被杀的 Agent 再生成“工具成功”，因此远端最终回执由独立控制面保留并核验。Python 代码由真实模型提交，实际执行日志与归档均保留。SSE 能力沿用基线，本轮不将 JSON 事件导出宣称为重新完成全部 SSE 测试。

第一轮实际失败未删除：

| 实际失败 | 源码调用点 | 修复与证据 |
|---|---|---|
| Python 已实际停止，但取消 HTTP 返回 revision conflict | `CodingPythonExecutionBridge.reconcile` 的读取 revision、`reconcileStopped` / `settle` 与并行查询交错 | 薄适配仅对精确 Journal CAS 冲突重试；不重试外部派发。`initial-protocol-failure.json` 保留 FAIL，最新回执测试通过 |
| Python 原生审批找不到已登记任务 | `governance-server.mjs` status 投影在 `allocate` 完成 `/session` 登记前提前发布 READY | 保持 STARTING，并在无 durable session 时拒绝 messages/approval；完整初始失败及最终三个模型场景均保留 |
| 旧 Epoch 用例尚未等到租约过期 | 恢复查询经 `lease()` 已 claim 新租约，测试仍按旧时间判断 | 修改测试等待真实 lease 边界；既有 Fence 检查始终拒绝绕过，首轮失败单独保留 |

实际外部 MCP 是本任务独立部署的真实执行节点，运行实际远端子进程并持久保存身份、进度与回执；Python 使用既有 Managed 实现和实际 Docker，不是 Mock 工具回执。故障代理只丢弃/延迟传输，记录实际请求/响应字节 Hash，不能构造工具结果。没有向共享服务安装测试工具或修改其配置。

风险及评审边界：

1. Gateway 和既有 Python Runtime 仍持有 Docker Socket，属于宿主级可信执行面。已减少新增服务挂载、分离 MCP 凭据；受限启动代理、独立 daemon/VM 的最小权限方案已提出，尚未实施。这是生产化阻断项。
2. 一般 MCP 客户端 abort 不等于服务端停止。本次支持具备可信 query/cancel/receipt 协议的实际节点；没有把这一结论推广到所有共享 MCP 工具。不支持该协议的服务应拒绝纳入或保留 UNKNOWN。
3. 执行节点自身在启动/PID 持久化窗口崩溃时仍可能 UNKNOWN；不会伪造 no-start 或自动成功。任意执行节点崩溃窗口和多副本 authority 的完整故障矩阵未验收。
4. 本轮没有执行模型预算、完整计量或产物/Silo 治理。Python 原始回执里的 `cpuMillisMetered`、磁盘/输出硬限制标记保持实际值，不包装成已实现能力。
5. Docker daemon 历史事件有缓冲限制；证据中的空事件列表不能证明停止。停止结论依赖现有执行器的真实检查、归档回执及独立状态对账。Gateway 健康响应仍沿用 `P3.1` 显示标签，企业验收标记为 false；阶段判定以本报告和实际证据为准。

后续建议先评审上述权限边界和执行节点崩溃窗口，再决定是否授权整改。未获得下一阶段授权前停在第二阶段评审点，不开展模型预算、Silo、业务 Java 集成扩展或默认 Agent 切换。
