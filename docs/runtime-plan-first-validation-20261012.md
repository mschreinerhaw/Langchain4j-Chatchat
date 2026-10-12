# Runtime 修复与 DEV 联机验证记录（2026-10-12）

## 设计纠正

资产发现、资产路由、模板发现、模板授权、参数来源绑定及取数依赖，是现有系统的固定流程。模型的自主性作用于授权模板的数据分析，不包含跳过这些流程直接调用底层执行器。模型提出补证时，执行仍须经过同一流程。

沿用已有 InterpretationPlan、DAG 执行及快照机制。没有另建计划类，也不要求分析 Harness 重新提交一份替代整个系统流程的计划。

此前新增的完整任务自主入口是错误的接入位置：它提前调用分析 Harness，绕过资产与模板流程；AgentToolExecutor 的配套分支还跳过了已有模板参数绑定。已撤销该入口、配置开关、工具权限扩展和绑定旁路。DEV 已恢复原始运行包与原始配置，恢复前确认没有活动任务，保存了完整备份。

## 正确的现有链路

1. AgentOrchestrationEngine 进入原有计划和 DAG 执行流程。
2. 系统通过配置的能力发现流程取得资产、路由及模板候选。
3. 模型在返回的授权候选内选择分析需要的模板与合法参数。
4. AgentToolArgumentResolver.applyObservedTemplateContract 编译已观察到的模板契约，enforceObservedAssetContinuity 保持资产连续性。
5. ToolRuntimeService 执行身份、权限、参数、流程、确认、预算与恢复约束。
6. 已执行的授权模板数据进入 AnalysisCoverageCoordinator 和已有 ModelNativeAnalysisHarness，模型读取原始证据、持续分析并形成结果。

固定流程不能通过模型传入的任务标记解除；分析阶段的工具入口保留原有只读限制。没有修改 MCP 的 stopOnError、资产范围、命令模板、角色权限或 Agent 配置。

## 保留的修复

- 权限匹配同时检查工具和数据范围，授权表达式来自可信会话上下文。
- 批次保留成功数据及失败事实，区分空结果与执行失败。
- 不完整但合法的返回行仍可供模型分析，完整性与截断情况如实反馈。
- 原始证据可按字段、对象路径及分页继续读取，分页游标反映实际返回内容。
- 格式错误反馈保留既有执行回执和已取得的证据索引。
- 未知执行结果不自动重复派发，错误信息脱敏后保留。

## 联机记录

错误路径的最后一次任务 df1af622-db14-3aa0-ad32-2bdcdd363e16 虽然交付了受限报告，五项指标均未取得，不能认定巡检成功。直接提交 env=DEV 触发 Ambiguous SSH host execution context, matched=12，随后被现有停止规则拒绝。该现象说明必须恢复原有绑定流程，不能靠放宽授权或不断重试解决。

恢复记录：运行包 SHA256 8d9ce0401920c5b21bdd1cad75071b8e559f448b4472420897a20d7285a451df；恢复备份 /opt/chatchat-deploy-backup/runtime-restore-designed-flow-20261012。

恢复原包后的任务 e5a2c0cd-75c5-3f53-97ca-1a9208c36abd 未完成取数。能力查询返回 stage=asset_selection、assetSelectionRequired=true，但 Runtime 按工具整体角色将其当成模板发现，错误报告 NO_MATCHING_TEMPLATE。

修复通过已有 ToolProtocolPayloadNavigator 读取发现阶段契约；同一发现桥的资产阶段使用原有资产选择和绑定逻辑，模板阶段使用原有模板选择逻辑。只有已声明为发现工具的响应才允许按阶段分类，执行工具不能借响应字段变为发现工具。多阶段发现工具不进入假定直接返回模板的快捷计划，继续使用原有规划器。没有增加业务词、巡检指标、主机地址或命令硬编码。

阶段修复已部署，SHA256 0956a30b836930c7f20e56023badc457d8594f2d7c5d1a59965bf0a2ce410f16；备份 /opt/chatchat-deploy-backup/runtime-original-flow-stage-contract-20261012。

原问题复测任务 071bffd6-2b31-3306-b035-e9ca4fa1493e 已完成，任务状态 SUCCESS，返回《DEV 环境服务器健康巡检报告（最终）》。原始报告保存为 server-health-report.txt，未由调试者替换或改写。

资产发现审计显示 capability_asset_resolution.v1 / typed_asset_discovery_then_decisive_margin，返回 1 个候选并绑定 IDS 服务器：资产 b1345a57-709e-499c-814e-17754116945e，逻辑工具 ssh_ids，环境 DEV，实际执行地址 192.168.195.232。目标来自现有资产流程，部署地址没有作为巡检目标传给模型。

模型选择了 CHECK_CPU、CHECK_MEMORY、CHECK_DISK、CHECK_SYSTEM_LOAD、CHECK_UPTIME、CHECK_MOUNT_DISK_USAGE、CHECK_SYSTEM_OVERVIEW，Runtime 通过原有参数来源和模板绑定机制执行 7 个子调用，结果全部 SUCCESS。已取数据完整，gatewayTruncated=false。

| 指标 | 原始模板输出中的实测值 |
| --- | --- |
| CPU | 空闲率 99.2%，us 0.4%，sy 0.4%，wa 0% |
| 内存 | free -m：总量 15883，可用 4859，Swap 已用 1751 |
| 磁盘 | 根分区 71G，已用 50G，可用 22G，使用率 71% |
| 系统负载 | 0.15/0.06/0.07；后续 uptime 为 0.14/0.06/0.07 |
| 运行时间 | 208 天 3 小时 20 分钟 |

模型随后在已有分析 Harness 中自行请求 READ_TEXT，补读 CPU、内存、磁盘、负载等原始输出后提交最终报告。运行期间没有通过人工 SSH 替代 Agent 取数，也没有修改 Agent、模型、模板或 MCP 权限配置。

本次验证只覆盖发现并绑定的这一台主机。报告里的健康判断、阈值建议和是否需要继续由模型作出，不能据此认定全部 DEV 资产已巡检，或长期健康状态已得到证明。报告还包含导航字段等实现细节，其表达质量仍应由用户评价。

最终代码包 SHA256 aeeabe89c10b51fab6f4a99d58b73a4376c15b5782fa3f94e56a7020fb7cfa63 已部署，保持原配置不变，备份 /opt/chatchat-deploy-backup/runtime-original-flow-final-20261012。重启后，发布 Agent 的 answer 接口仍返回完整报告，状态 SUCCESS、ready=true；计划版本 GENERATED / COMPLETED 及三轮分析观察记录仍可查询。

持久化验证的边界：重启后通用 tasks/{id}/result 接口返回空记录，而发布 Agent 的 answer 接口可正常读取报告。本次没有修复这两个接口的存储一致性，因此不能宣称所有结果查询入口或完整中断续跑已经验证。原始执行结果及模板输出已在本地联机记录中保存。

## 验证范围

最终定向回归 313 项通过，其中 InterpretationPlanRuntimeTest 全部 155 项通过。AgentOrchestratorTest 整套有 28 个失败、47 个错误；将编排引擎临时恢复为 HEAD 基线运行同一套测试，原有失败和错误集合一致，仅新增阶段契约用例在基线失败、修复后通过。记录保存在 baseline-orchestrator-test-outcomes.json 与 current-orchestrator-test-outcomes.json，不能据此宣称完整测试集通过。

原始联机文件、旧错误路径记录、撤回变更副本及测试日志保存在忽略目录 target/codex-live/native-runtime-20261012/。本文件不包含登录凭据。
# 提示词与分析阶段边界后续验证

本节之后的「Runtime OS 运行日志修复」记录了进一步的日志修复和新部署，部署包以最后一节为准。

本轮复用原有 InterpretationPlan，没有新增计划 DTO 或完整任务自主探索分支。分析上下文携带已提交计划的目标、上下文和步骤投影，不携带工具输入；原流程修订计划时同步更新该投影。分析工作状态中的计划调整摘要不改变系统取数流程或授权。

核心指令与协议拆为 ModelAnalysisPrompt；有原计划的任务只发送 v2，旧模式只发送 v1。解析器保留旧响应兼容。v2 统一使用 content，可视化注入器按当前协议选择内容字段。新提示词不提供 WAIT、PUBLISH 或普通 Markdown 作为协议选择。

读取参数移至 BoundedAnalysisEvidence 的 L2 能力契约，修正 READ_CONTEXT 的分页上限为 20。记录投影返回实际字段及缺失字段；大值预览附带 readHints.request，定位原始字段并提供合法读取参数，不选择业务分析方向。原数据及分页读取能力保持可访问。

联机发现并修复两处执行边界问题：分析工具清单及 v1/v2 执行入口均依据发布者 workflowContract 限制为 DIRECT 只读能力，不将资产、模板发现与执行工具交给分析 Harness；原模板分支校验改用已有执行阶段识别，避免将同一工具返回的资产阶段误判为空模板分支。真实空模板分支仍拒绝执行。

255 项相关测试通过，API 编译通过。未宣称全仓测试全绿；前文记录的基线失败仍需分别处理。

| 对比项 | 旧版成功样本 | 最新部署样本 |
| --- | --- | --- |
| 核心指令与协议字符数 | 7,476 | 3,400（约减少 55%） |
| 首轮估算输入 token | 13,593 | 12,853 |
| 分析调用轮数 | 3 | 8 |
| 分析输入 token 估算总量 | 51,171 | 141,808 |
| 完整任务耗时 | 524,090 ms | 800,489 ms |

最新首轮完整提示词为 41,344 字符，其中 layers 为 28,845 字符；其余包括核心指令、协议和可视化注入内容。每轮记录 instructionsChars、layersChars、fullChars 与输入 token 估算，不能用静态指令压缩率代表完整请求压缩率或整体成本收益。两次使用相同模型和问题，但计划、模板数量及证据不同，不构成严格控制变量的性能比较；本轮没有证明整体耗时或成本改善。

最新任务 f25f7a11-04ee-30d3-952b-843760d7c8e0，attempt 为 att-1-63b16733-b2c2-4a02-bccb-19e0c948ecc0。原计划持久化为 GENERATED → COMPLETED，分析响应均使用 v2，最后声明 final 并向当前用户交付。CPU、内存、磁盘、负载和运行时间数值已与原模板 stdout 核对：CPU idle 99.2%，内存 total/used/available 为 15883/9783/4920，根分区使用率 71%，load 为 0.01/0.03/0.05，uptime 为 208 days 5:00。数据通过原资产与模板流程取得，没有使用部署 SSH 的人工采集替代 Agent。

报告仍有模型判断局限：参考阈值不能视为平台配置的告警策略；局部进程列表不能证明全部进程无异常；内存单位标注需复核。Runtime 原样交付模型结果，不用内置业务判断改写报告或将 final 降级。此前联机的受限报告及阶段绑定失败样本均保留，未计为完整场景通过。

最新部署 SHA256：88197e1ac39c6dc752a760187bdf5136d335beccbe903d13ff36ccece75d8709。回滚备份目录：/opt/chatchat-deploy-backup/runtime-harness-prompt-navigation-20261012。

本地原始报告与比较数据位于 target/codex-live/native-runtime-20261012/harness-prompt-20261012/server-health-report.txt 和 comparison.json。

# Runtime OS 运行日志修复

## 原因与修复

运行事件已写入事件存储和观察记录，但公共事件出口没有统一的应用日志。模型调用日志此前只在调用结束时输出，等待模型期间无法从该日志看到当前节点。DEV 启动脚本也未显式加载发行包的 logback-spring.xml，DEV 配置没有指定它，实际输出走默认日志配置。

在 common Runtime OS 层增加 RuntimeEventLog，输出 runtime_log_v1 JSON 执行事实投影。AbstractAgentRunStore 在事件交付前写日志，覆盖内存、RocksDB 和数据库运行存储，不依赖 SSE、任务映射成功或外部事件发布器正常工作。不为日志额外执行工具、增加业务判断或修改运行状态。日志失败不改变事件交付；原始证据继续由既有存储管理。

记录 runId、eventId、流内 sequence、执行时间、阶段、步骤/工具、模型决策动作、读取范围、数据引用/指纹、调用数量、耗时、协议/预算反馈和失败摘要。只投影允许的诊断字段，限制递归、条目和字符串长度，转义换行及控制字符，脱敏常见凭据表达，不把原始提示词、工具参数、stdout、报告全文或内部思维写入该投影。摘要或日志截断不等于原始证据截断。

模型调用新增 STARTED 日志；结束日志保留同一 callId、节点、runId、耗时、排队时间和 token 估算，失败附异常类型，不输出模型输入输出正文。任务桥接保存 taskId/executionId/attemptId/runId 的关联。计划保存日志复用已有 InterpretationPlan，仅记录计划 ID、版本、保存状态、步骤类型/工具和依赖，省略工具输入。数据库实现覆盖 savePlan，已单独接入公共计划日志方法。

运行事件的 sequence 属于原事件流；计划保存日志是独立持久化事实，sequence=0、eventId 为空，以 planId/planVersion 定位，并未伪造一个新的运行事件。日志显示的计划状态是保存记录的状态，不代表新的审批或质量评分。

启动脚本显式加载发行包日志配置，可用 LOGGING_CONFIG 覆盖。增加轮转的 logs/runtime.log，同时保留 chatchat.log 和标准输出 chatchat.out，Runtime 事件默认 INFO，运行失败终态 WARN。轮转参数沿用 LOG_MAX_FILE_SIZE、LOG_MAX_HISTORY、LOG_TOTAL_SIZE_CAP。

## 验证与部署

75 项相关测试通过，覆盖字段投影和脱敏、日志注入转义、超大/循环数据、外部发布器失败隔离、事件去重和顺序、数据库计划日志、模型调用开始时可见性及失败关联、运行存储和 Harness 回归。API 编译通过；不宣称全仓测试通过。

使用相同的已授权 Agent 和原 DEV 巡检问题联机验证。最终部署任务 c1932ee8-9569-3448-a01d-8a01c36847c0，runId=att-1-f5adbe80-d426-40c5-9ea9-bed4590b7cc1：

- 日志记录 40 条运行事件，sequence=1..40 连续，逐条对照持久化 API：eventId 和 sequence 一致，没有漏记。
- 另有 4 条计划保存事实：两个 GENERATED、两个 FAILED；模型修订后继续执行的过程可见。
- 6 次 Runtime 模型调用均有开始/结束记录，运行中能直接定位等待的节点和 callId。
- runtime.log、chatchat.log、chatchat.out 中该运行的事件投影均为 40 条。
- 实际任务 FAILED，错误为 STEP_OUTPUT_CONTRACT_FAILED / EDGE_CONTRACT_FAILED：取数步骤未返回下游契约要求的 $.templates[0].templateId；修订后再次失败，最终错误码 MANDATORY_TOOL_NOT_SCHEDULED。没有跳过资产/模板流程，没有修改契约让任务虚假通过。

该样本验证了日志对真实等待、工具/契约失败、计划修订及终态的可定位性，不是巡检成功样本；本次两个联机样本均在取数阶段失败，未进入原生分析 Harness。Harness 分页读取/回合事件的日志投影由相关测试覆盖，不能据此宣称本轮已完成分析阶段的联机覆盖。第一次失败样本另保留于 attempt-one 目录。

最新部署 SHA256：64ecd0d8a311bfffae8c0f56e976e92ce890de5712ab2581301851f66d19faa6，进程 PID=2526580。重启前确认没有活动任务，备份 jar、应用配置、启动脚本和日志配置。最终回滚备份目录：/opt/chatchat-deploy-backup/runtime-os-logging-plan-20261012。

验证材料目录：target/codex-live/native-runtime-20261012/runtime-os-logging-20261012，包含 runtime-live.log、journal-events.json、log-verification.json、deployment-verification.txt、test-results.json 和任务结果。

排查时先查看 /opt/chatchat-1.0.0-SNAPSHOT/logs/runtime.log，以 taskId 查找 Runtime OS task link，再用 runId 过滤整条运行链。按 eventId/sequence 对照 /api/v1/agent/runtime/runs/{runId}/events；凭 rawObservationLocation 和 evidenceId/datasetReference 继续读取授权证据。常规应用异常仍查看 chatchat.log。

## 固定资产流程的阶段及环境契约修复

用户提供的日志对应任务 c1932ee8-9569-3448-a01d-8a01c36847c0。工具以 `stage=asset` 返回资产数据，但计划编译器仅按工具注册角色识别模板发现，错误生成了资产步骤到执行步骤的 `$.templates[0].templateId` 绑定和必需字段契约。模型重写后仍重复错误，最终显示 `MANDATORY_TOOL_NOT_SCHEDULED`。后者是下游未执行的结果，不是最初失败原因。

本次沿用现有模板流程插件、DAG 编译及运行时绑定机制：

- `InterpretationPlanOptimizer` 根据发现调用的阶段区分同一工具的资产和模板节点；按发布者声明的伴随发现工具补齐现有流程，清理旧计划从资产结果读取模板 ID 的错误绑定和契约。重复编译不会持续增加节点。
- `SshTemplateWorkflowPlugin` 对明确分阶段的资产调用生成模板阶段输入，保留查询范围；未声明分阶段的工具沿用既有输入行为。
- `ToolProtocolPayloadNavigator` 在没有 `stage` 时根据发布的 `asset_query_result.*` / `template_query_result.*` 协议识别结果阶段。此识别仅适用于已注册的发现能力，不能将普通工具或执行工具变成发现能力。
- `PlanEdgeContractValidator` 对模板发现的环境契约复用运行时已记录的执行范围和既有环境解析机制，不再强求模板结果包含 `assets[0].asset.environment`。普通工具仍不允许该回退；没有环境依据仍拒绝，不构造环境值。

角色过滤、资产权限、模板授权和执行绑定机制未放开，没有增加模型重新发现资产或自由组合流程的接口。上述四个生产类及其内部类组成单独部署包；尚在整改的分析提示词、计划状态及协议改动没有包含在此次部署中。

部署到 `192.168.195.221:8080`，最终 SHA256 为 `424b0f9827774c75f01cc0cc989b36f2f8d7a83c1c00e86c4829011bdb46da07`，PID=2547107。重启前检查无活动任务，备份位于 `/opt/chatchat-deploy-backup/asset-stage-boundary-second-20261012`。固定流程及执行契约相关 215 项测试通过，覆盖桥接阶段、错误绑定清理、重复编译、环境字段回退与拒绝、既有执行及授权模板回归。

联机验证任务 `68bf0d7e-350b-38f8-ad0d-29317cfe2b8c`，runId=`att-1-f52bcefb-1f65-4338-99bb-8fd98e403f75`，使用原授权 Agent 和原 DEV 巡检问题。流程完成模板绑定，SSH 执行步骤 2、3 各执行 19 个已授权模板调用，两个批次均 SUCCESS。CPU、内存、磁盘、负载和 uptime 对应模板包含在执行批次中。本次修复的两个字段契约不再阻断执行。

完整任务仍未成功：后续分析输出阶段出现 `Publication report or evidence snapshot does not match`，终态为 `NO_PRESENTABLE_RESULT`。这属于报告协议与交付的剩余问题，不能将上述取数成功描述为完整巡检报告已交付。运行日志有该任务的 120 条事件，包含实际模型调用、模板绑定、SSH 执行与最终失败。验证材料保存在 `target/codex-live/runtime-protocol-audit-20261012`；第一次复测材料另存 `attempt-one`。

