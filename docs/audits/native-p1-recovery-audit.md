# P1-03 多服务恢复第一阶段源码审查

**状态：仅源码和历史证据复核；本轮未注入新故障，跨服务完整交付不得标为通过。** SDK/Runtime 基线见 [总矩阵](native-p1-source-comparison.md)。

## 上游原生能力与 Runtime 必要适配

官方 `sdk/packages/agents/src/agent-runtime.ts:627,636,669,690` 提供 continue、abort、restore、snapshot。restore 保留模型/工具/hooks/身份，重置 iteration/pendingToolCalls/SDK usage 等状态。`core/src/session/services/file-session-service.ts:278 FileSessionService` 与 `core/src/services/storage/sqlite-session-store.ts:25 SqliteSessionStore` 是上游高层本地会话存储能力，**不包含 Runtime 的跨 Java/Worker/MCP/Sandbox 预算与副作用事务**。

Runtime 没有使用这些本地存储，而由 `native-session.js:24 checkpoint` → 原 Java/Silo journal 保存 SDK transcript/序号/合同绑定；`native-runner.js:162,176` 调原 restore/continue。`ClineCodingAdapter.java:143 resume` 先获取原状态/所有权，检查远端 Worker；只有 missing/failed/fenced 且原操作协调后才能重建 SDK。原生分支恢复 fence:184，CODE 证据查询:226，checkpoint 读取:238、已存模型/工具回执构造:257；`DurableCodingTaskService.java:196,408,433,543` 分别管理原回执恢复、fence、预算准入、独立停止证明。没有必要另建 checkpoint/预算数据库。

执行链：SDK run_commands → `NativeCodingCommandService.execute` → 原 CodingPythonExecutionBridge → MCP `PythonManagedExecutionService.start:54/get:130` → Docker。MCP 在 `recoverPending:248` 与 `reconcile:355` 从已持久身份检查原容器；运行中不另派，缺失或不能确认的结果标 UNKNOWN；`finalizeStopped` 写回执、日志及归档。**缺容器不是执行未发生的证明，UNKNOWN 不能用自动重跑替代。**

## 已有事实、确认缺口和待证风险

| 项目 | 源码/原证据 | 当前判断 |
| --- | --- | --- |
| 原任务 Worker 恢复 | P0 task `native-6833b25f89de6d528de6efe56815b12266790184bf02c960`，同 session/合同/deadline/声明验证，CODE 2→2、模型 4→5，最终交付 | 历史实证，源码保留；不是 P1 所有服务通过 |
| 压缩后再崩溃 | P0 恢复样本 compactionCount=0，其他 SDK 构件覆盖压缩 | 组合故障未实测，不补写“已通过” |
| Java/MCP 重启 | JDBC/Silo、MCP 操作表及 reconcile 路径存在 | 可恢复机制有源码，不证明真实重启顺序、租约、配置/凭据/对象存储可用性全部正确 |
| Sandbox 期间服务中断 | 原操作身份、容器 inspect、回执及 UNKNOWN 分支存在 | 必须区分执行容器重启、MCP 控制面重启、Worker/API 断线；不能把它们视为一次测试 |
| 已完成步骤回放 | Java native tool/model 原回执 + SDK checkpoint/operation 绑定 | 必须确认原参数/Schema/版本不变，且没有实际第二次副作用；日志出现“恢复成功”不够 |
| MCP/Skill 撤权恢复 | 当前 Skill read 再授权；一般 native MCP 链尚未接通 | 旧授权不得随恢复重生；MCP 恢复验收依赖 P1-01 接线，不能现在报通过 |
| 最终产物 | `NativeCodingTaskService.completeLocked:132` 校验当前 manifest、声明及停止证明，再归档 | P0 代码项目已有实证；报告/数据/图表任务需逐文件实际验证，不能套用历史文本或 exit=0 |
| R-01 范围 | 本任务书八种跨服务故障未形成完整真实交付矩阵 | **确认的是验收证据缺口。尚无足够证据指定某个重启分支为确定产品缺陷，不提前大修恢复算法** |

## 超时和状态边界

至少分开记录四条时钟/责任：外层 Agent/HTTP 调用等待；Coding task 原合同截止/预算；Node→Java→供应商的模型请求；Node→Java→MCP→Sandbox 的执行时限。P0 原任务 300 秒和单 Sandbox 75 秒只是其冻结条件，不能把它们混成同一超时或在 P1 随意增大。

模型请求连接结束、SDK abort、本地线程停止、Worker 容器停止、Sandbox 独立停止分别需要各自证据。`ClineCodingAdapter.reconcileModel:403` 等代码会核对原模型回执/本地终止证明；供应商是否实际完成/是否重复推理必须按可观测证据标注，不能把本地连接关闭等同远端计算停止。工具执行状态未知时不得新开冲突执行。

区分原操作已执行/中断/恢复中/失败/完整交付；SOURCE/验证/产物尚未核对时，即使 Worker READY 或 SDK completed，也不直接写 Runtime 成功。

## 评审后故障注入矩阵

全部在隔离环境进行；保持同 taskId、原 deadline/预算，不改共享模型/原样本。每项先记录原执行与停止状态，再注入，未确认旧进程停止/被 fence 前不得启动冲突工作。每行当前均为 **未执行 P1 验收**。

| 故障 | 注入时点/关注点 | 必要独立证据 |
| --- | --- | --- |
| Java Runtime 重启 | 模型/工具/CODE 已准入、回执落地前后分别测 | 原 journal、lease/fence/配置、已准入序号及同任务续行；不能由新任务冒充 |
| Node Worker 重启 | 编辑已持久 ACK 前、工具回执返回前、验证后、压缩后 | 同 session/manifest、原回执回放、累计预算、最终产物 |
| Python Sandbox 中断/重启 | 原 CODE 真正在运行，结果落地前 | 原容器/operation 身份、真实退出/停止与输出可用性；重新执行必须有合法未执行证明或新授权身份 |
| MCP Server 中断 | 正在发现、调用期间、结果已产生但 ACK 丢失 | 控制面与执行容器分开；其他服务隔离、无重复副作用、真实错误返 Agent |
| 模型请求超时 | 发送前、供应商处理中、响应已保存但回传前 | 原模型 operation/请求 Hash/回执，调用数/Token 未重置，未知结果不误报成功 |
| 短暂网络中断 | Node/API 与 API/MCP 两段分别切断 | 原参数/Hash、请求/响应和恢复时间线，重连不等于重新执行 |
| 多服务同时重启 | 先后次序、旧租约存续、对象存储暂不可用 | 原合同/所有权/预算、未知操作协调及全产物 Hash/归档再读 |
| 恢复后再次中断 | 同一任务连续两次故障 | fencing 单调、同 session 可解释、无重复启动/超预算、最终真实交付 |

每个样本记录 artifact 是否存在、原始 SHA256、归档重读；需要报告/数据/图表时逐项校验内容与任务证据一致。故障用例的等待/注入本身也不得延长原任务预算；超限如实保留失败。

## 修复与回滚原则

本阶段无运行时变更，无故障注入或回滚。后续先复现具体问题，优先 SDK restore/continue 与原回执协调点的最小改动；不新增恢复 Agent、重试 Loop、Git 或数据库，不在 Java 生成修复方案。恢复/超时不可证明时保留 UNKNOWN/失败和证据，不能删除容器或清空 journal 美化结果。

运行时回滚必须先停止新准入并核对所有 MODEL/TOOL/CODE 独立停止、无未核对操作；保留版本、原任务及冻结预算。P1-01/02/03 分项回归后，仍需完整 Java/Node、原 P0 八项及真实原生对照，才能决定是否合并 main。
