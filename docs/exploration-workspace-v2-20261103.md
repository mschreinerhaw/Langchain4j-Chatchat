# 数据探索工作空间 V2：节点、状态与产物关联

定位：Runtime OS 提供的可视化智能分析工作空间。分析路径负责选择和分支，画布负责展示真实已交付成果，底部输入负责基于所选节点继续探索。参考 [Microsoft Data Formulator 的 Data Threads](https://github.com/microsoft/data-formulator)，复用本项目 Runtime、Cline、Python Sandbox、用户资产与图表组件，没有新增分析执行器或独立图表引擎。

## 审查证据

源码基线 `5d7371ab6`，分支 `feature/data-exploration-workspace-v2-20261103`。`origin/main` 检查仍为 `b764ab22e`；本分支保留之前已部署的 SQL 元数据探索和灵感请求状态修复。

1. `ExplorationAgentBridge.snapshot` 原先在任务非终态直接返回，没有读取已经归档的报告。
2. `ExplorationWorkspaceController.status/finish` 与前端轮询把 resultAvailable 当作停止更新条件，阶段成果与任务终态不能独立推进。
3. `DataExplorationView.ask` 创建追问节点后立即选中新节点，报告完全取自 active.receipt，无产物时原图表消失。
4. 树已具备 parentId、展开状态和 URL 选中恢复；图表也已有不可变产物引用、同数据切换样式及 KeepAlive。这些能力继续复用。
5. 真实客户资产项目存在四个已交付节点和一个取消节点。选择取消节点时原页面显示空白占位；这次验收针对该真实历史状态，没有使用用户示意图中的比例数据。

## 最终行为

- 选中节点与画布节点分开：无新成果时显示可用上游或最近历史成果，并标明“画布保留”的具体节点。点击任何已完成节点直接显示其成果。
- 报告、图表身份及滚动区域保持稳定，状态轮询复用未变化的节点和 receipt，拒绝迟到的旧工作空间版本。
- 节点显示完成、运行、失败、取消和等待状态，附真实摘要、图表/数据表数量。折叠及选中恢复仍由原偏好和 URL 实现。
- 默认紧凑指标、图表或数据表及结论；表格型报告直接进入表格画布。默认指标展示四项，全部原始值在原有分析说明中；格式化仅影响展示，完整精度和原单位保留。
- 输入框空白时最小高度 34px，内容可自动扩展；中央“继续分析”移至节点状态和继续探索入口。
- 样式切换保留真实数据，增加环形图；数据点选择只填充草稿，不自动提交分析。涉及口径和范围的改变仍由用户发送追问交给原分析流程。
- 早期未绑定 Agent 的已完成文件分析继续走原有附件/上下文分析接口。选择其他 Agent 时仍开始新的分析，不重写历史 Agent 身份。后台任务存在时可以查看历史，也可从其他已完成节点派生；轮询轮流读取多个活动节点。

## 状态与产物模型

继续存入原 `runtime_exploration_workspace.state_json`，无数据库表迁移。

| 逻辑信息 | 来源与处理 |
|---|---|
| id / parentId | 原节点身份及服务端校验过的父子关系 |
| title / summary | 已归档 report，问题文本作无成果时的标题来源 |
| status | Runtime 生命周期，与 receipt.resultAvailable 分离 |
| artifactRefs | 归档图表 fileId / SHA / 媒体类型及报告数据表引用；历史节点可从持久化 receipt 重建 |
| dataSnapshotRef | 新报告中原 Coding inputSnapshotRef；历史未采集的明确返回 UNKNOWN |
| contextRef | 原 Agent task 或文件分析 task；父上下文由服务端读取、限定当前所有者及所选分支 |
| createdAt / updatedAt | 新节点创建和更新时持久化；不伪造旧节点缺失的创建日期 |
| eventCursor | 原 Agent 事件序号，持久化用于断线后增量读取与去重 |

链路：`DataExplorationView` → 原工作空间 status API → `ExplorationAgentBridge.snapshot(owner, task, previous)` → `AgentTaskService.listEventsAfter` → Runtime `CODING_TRACE` / `workerActivity` → `CodingEvidenceCollector.visualReport` → 已归档的 `CODING_VISUAL_REPORT` → 原 receipt → PythonReportChart / ExplorationDataTable。

读取每批至多 128 个事件、每次至多四批；有积压则继续下一次读取。沿用现有 1.5 秒状态轮询和 Runtime 持久化事件，没有另建 SSE 服务。Worker 的“有结果”声明必须匹配同一 Coding task、operation、resultDatasetRef 的实际归档报告；技术校验失败的报告不会作为阶段成果准入。阶段成果显示“待最终确认”，最终失败仍保留已准入成果及错误。不同执行版本按其归档引用更新当前报告，历史证据仍保留在 Runtime。

`AgentRuntimeTaskEventPublisher` 在摘要压缩后保留有界的 resultAvailable / resultDatasetRef / validationRecordRef / evidenceBundleRef，不暴露代码、提示词或原始工具响应。新报告记录输入快照引用。Java 继续控制所有者、工作空间、Agent 和资产权限；Cline / Python 执行协议不变。

## 验收与限制

66 项前端测试通过，覆盖画布保留、未变化图表身份、滚动位置、阶段成果后继续轮询、数据表画布、环形图不改原值、数据点事件来源与长度边界、历史文件分析追问及灵感状态。46 项 Java 测试通过，包含真实 H2 工作空间持久化、权限边界、阶段成果与最终失败、游标、归档凭据及事件压缩；模型和执行器在这些测试中有明确 mock 边界。[测试记录](exploration-workspace-v2-20261103-evidence/test-suites.json)

真实浏览器验证通过：

- 取消节点仍显示此前已归档图表，明确显示当前成果所属节点。
- 刷新保留同一 iframe 和滚动位置，浏览历史不提交分析。
- 真实归档资产类别图转为环形图，刷新恢复样式；Plotly 的实际数据项事件可填入草稿，无自动分析。
- 页面刷新后恢复选中根节点与折叠状态，空白输入框最小高度为 34px。
- 测试结束恢复本次改变的图表展示配置，不覆盖并发的其他配置项。

[浏览器记录](exploration-workspace-v2-20261103-evidence/browser.json)、[实际工作空间截图](exploration-workspace-v2-20261103-evidence/workspace-donut.png)、[取消节点保留成果截图](exploration-workspace-v2-20261103-evidence/cancelled-preserves-result.png)。历史报告的业务结论和口径沿用原归档内容，此次导航验收没有重新计算客户资产。

**未通过的真实验收：新分析的阶段交付与后续分支执行。** 在单独创建的验收项目中，用已有授权附件提交新 Agent 分析，十五分钟内未产生成果；记录出现 MODEL_FAILED、约 300632ms 的 MODEL_TIMING，原因码 EXECUTION_OUTCOME_UNKNOWN。该记录不足以把问题归因于前端或产物服务。通过原 Runtime API 取消了这个验收任务，保留其节点和日志，确认 Worker activeSessions=0；没有修改或取消其他用户任务。因上游未交付报告，不能声称已通过“首批成果出现于终态之前”的真实端到端验收，亦未执行依赖该新结果的第二轮任务。[实测记录](exploration-workspace-v2-20261103-evidence/live-model-observations.json)

阶段报告读取、终态后保留、父子上下文及权限边界已由针对性服务测试证明；完整实时场景仍需在模型调用恢复后重验。默认未新增金额/占比口径换算、时间过滤或重新聚合控件；这些请求通过所选节点的自然语言追问进入现有执行链。当前节点没有新建额外缩略图文件，显示原报告的真实摘要和产物数量，完整图表按选中节点加载。

## 部署

最终镜像 `runtime-os-api:exploration-workspace-v2-20261103-r3`，API JAR SHA-256 `6f2e7b31f83b5f3c39720a0b3f73ad4f7ac3a00cbf8aff15b5160950d3b364bd`。首次发布替换四个 API 外层类、嵌套 chatchat-chat JAR 中一个事件发布类及前端资源，分别校验嵌套和外层所有无关条目一致；后续两次只更新前端。MCP、Worker 和数据库结构保持原版本。工具目录 HEALTHY、39 个工具；会话内容和消息索引数量保持一致。

发布前等待已有 Coding 会话结束，没有跳过活动任务检查。所有版本具备原镜像及私有备份回滚路径；临时登录文件已清理。[最终部署报告](exploration-workspace-v2-20261103-evidence/r3-deployment.json)、[初次产物核对](exploration-workspace-v2-20261103-evidence/r1-artifact-verification.json)。
