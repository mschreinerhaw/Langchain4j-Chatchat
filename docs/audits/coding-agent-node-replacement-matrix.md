# Coding Agent Node 替换审查与差异矩阵

本轮基线 `d8f41ff2e`，仅切换专项业务验证实例；共享默认 Coding Agent、模型、预算、Docker 和 Python Runtime 配置均保持。旧节点保留，五类真实场景未全部达标前不批准下线或生产切换。本表先记录源码审查结论，真实验证结果另附测试报告，不能把“接口存在”计为业务通过。

| 职责 | 原调用链 / 源码 | Native Server 差异及接入边界 |
|---|---|---|
| 用户请求与身份 | Agent/controller → analysis harness / AnalysisCoverageCoordinator → AnalysisCodingPort | 保持原用户入口、身份与资源授权，不能直接用测试脚本绕过 Runtime 代替业务验收 |
| 创建任务 | CodingAnalysisService 生成冻结 CodingTaskContract、输入快照、校验计划、预算和唯一 taskId → CodingAgentSPI | 继续使用同一合同、任务身份和 Journal；测试实例只选择一种 backend |
| 调度与回滚 | ClineCodingConfiguration → ClineCodingAdapter → `/v1/runs`；lease owner、contractHash 和 Fence 管派发 | 增加显式 Native backend 适配，校验目标确为官方 Standalone；默认仍旧节点。相同任务不得转交两个执行内核 |
| 原 Agent 内核 | coding-agent-node/server.js → createCodingRun → SDK Agent | Native `/v1/runs` 必须实际进入官方 v4.1.26 Standalone TaskService；不加载旧 runner、sdk-adapter 或旧 SDK Worker |
| 模型与预算 | httpGateway → ClineModelCallbackController → ClineRuntimeModelBridge → Runtime 配置模型/模型回执/Journal 预算 | Native OpenAI HTTP 传输转换成既有 Runtime 模型回调；保持配置与累计计量，不能直接使用另一个 API key 绕过 Runtime |
| 原生文件工具 | 旧 SDK read_files/editor/patch 等，任务级授权目录 | 使用官方 Standalone 原生工具及私有 workspace；Native 内建工具的授权名称与 MCP 名称需要显式映射，不能删除模型侧授权校验 |
| Skills | Runtime 授权卡片/版本 → CodingSkillReader/read-skill → 原 SDK 原生发现器与 skills 工具 | 仅交付已授权的 SKILL.md/资源；由新版本原生发现/选择/读取，记录实际加载，不能把目录复制当作自主使用证明 |
| MCP/金融/检索 | Runtime knowledge/data-request/sql-validate 回调，Java 按合同、agent 和数据源权限执行 | 将既有授权能力投影为 Native MCP 工具，真实调用与结果保持；不把 Java 变成分析规划器或工具顺序控制器 |
| 多文件输入 | CodingInputSnapshotService / CodingInputFilePublisher → CSV/Parquet、版本、Hash、fileId、virtualPath；MCP 共享数据目录 | Native workspace 可接收有 Hash 的原文件副本；Python 继续读取 Runtime 授权不可变输入，不能采用模型编造的路径或 fileId |
| Python | prepare-python → CodingPythonExecutionBridge → PythonManagedExecutionService / Docker → get/cancel → 原始回执 | 由 Native 自主生成/修改源文件并选择 MCP Python 工具；适配只交付源码与轮询真实状态，不执行自定义 Java 分析或修复循环 |
| 自动修复 | 原 SDK 根据真实 executionFeedback 更新代码再执行 | 官方 Cline 根据同一失败回执自主修改/重试；保留每个版本、Hash 和实际工具输出，不把异常改成成功 |
| 结果与报告 | CodingEvidenceCollector 校验 rows.jsonl、引用、证据、可视化；RuntimePythonAssetArchiver / RuntimeAssetArchive 归档 → Runtime 报告交付 | 既有“一份 JSONL 结果”是结构化交付协议，不得被误当作只能输出一个文件。需验证 Markdown/Excel/PNG 等自由产物的真实归档；发现约束缺口必须列失败 |
| 状态与取消 | Runtime Journal + CodingTaskMonitorController + event/finish；停止必须有真实执行器证明 | Native turn 结束不等于业务成功；只有 Runtime 核验结果与文件后才可完成。取消、超时、未知请求仍由既有治理裁决 |
| 恢复/重复提交 | 原适配持久意图、幂等合同、模型/工具停止证明及旧 SDK checkpoint | 原 SDK checkpoint 不能伪装成 Native session；优先查询仍存活原会话/真实回执。跨进程恢复不扩展架构，无法确认保留 UNKNOWN |

已确认的原节点限制：ClineCodingAdapter 限制分析 profile 和一个 JSONL 主结果；ClineRuntimeModelBridge 限制模型工具名称及数量；原 Node 的 Skills、模型和工具包装与 SDK loop 位于同一 runner。替换时需复用治理协议并拆出传输/授权映射，不能复用旧 runner 冒称 Native。

安全遗留单独保留：rootless 资源硬限制、Python rootful 迁移、执行节点启动窗口及多 Authority 均不在本轮整改。本轮独立业务环境沿用既有受信测试执行能力，不将业务可用等同于生产安全通过。

## 2026-11-09 当前验证边界

- 官方结束工具：已在新 Host 镜像真实调用成功；Runtime 对未通过业务验证的交付保留 PARTIAL，非整体交付成功。
- 原生恢复：已对真实 Gateway/Worker 进程故障、原合同重复派发、取消/恢复完成对账；运行中 Managed Python 实际取消回执确认停止，未派发替代执行。旧不完整控制记录继续保留 UNKNOWN。
- 数据供给：原始 CSV/Parquet 的授权下载、Hash、解析与 missing/null/类型、多重集对照已执行；不代表客户交易数据或华是科技行情已具备。
- 替换门槛：全量回归及五类真实业务验收未全部通过。监控单元检查与实际查询已验证的范围见 [最新验收报告](../testing/native-cline-business-acceptance-20261108.md)，不能据此批准生产或灰度切换。