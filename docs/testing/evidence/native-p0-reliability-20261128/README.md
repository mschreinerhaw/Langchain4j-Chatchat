# P0 任务执行可靠性验证

专项分支为 `fix/model-governance-stability-313f35664`。参考审查版本为 `30b7189c`，本轮起点为 `0d3c33742`，隔离验证镜像使用 `d3b35d1b1` 的源码构建。镜像摘要、Web 产物摘要、各任务时间戳和正式产物核验结果见 [verification.json](verification.json)。

## 已确认的问题

- **CDH YARN** 的旧任务在分类请求发出前就失败。隔离环境中分类 Agent 引用的模型没有对应的已启用发布记录。`NO_CANDIDATES` 先出现在 Skill 上下文选择，随后随结果返回，不能作为授权资产未匹配的证据。新任务通过正式入口进入 `INPUT_REQUIRED`，保留了澄清问题及 `CLASSIFICATION_MODEL_CONFIGURATION_INVALID`。平台仍可在必需数据工作流已经由可信发布明确时跳过模型分类。
- **Docker** 的旧任务已取得八份快照。九分钟观察窗口中，上游完成了 14 次请求，其中 13 次成功。已知用量为 322,493 输入 Token 和 19,854 输出 Token。上游报告的 223,616 缓存读取 Token 已包含在输入用量中，账单用量未核实。两个成功回执分别耗时 134.878 秒和 90.232 秒，另有编辑工具参数格式错误。观察证据显示模型等待和工具纠错同时发生，未确证执行进入无效循环。 [逐次回执](docker-nine-minute-timeline.json)
- **InnoDB** 的本轮任务在提交后 27.534 秒持久化首份快照，取得九份可核验快照，其中一份为空。Native 智能分析达到独立任务时限，Worker 退出，Runtime 通过确定性回执交付了基础 Markdown 和 JSON。授权下载重新计算的 SHA-256 与正式清单一致。业务终态为 `PARTIAL_SUCCESS`，原因是智能分析未完成。旧任务与本轮仅三份对应快照的哈希一致，执行成本与报告质量未作等价比较。 [本轮逐次回执](inno-current-timeline.json)
- **Docker 本轮复测** 使用已有授权模型和八份真实快照。Cline 完成分析轮次并提交工作区文件预览，Runtime 拒绝了缺少 `reportMarkdown`、`evidenceReferences`、`limitations`、`analysisStatus` 和 `fieldProvenance` 的 JSON。交付修复阶段收到三次模型回执，已知用量 97,310 Token。达到任务时限后，Runtime 停止 Worker，正式交付了独立生成的基础 Markdown 和 JSON，业务终态为 `PARTIAL_SUCCESS`。两份产物均通过授权下载与 SHA-256 比对。 [本轮逐次回执](docker-current-timeline.json)

## 证据边界

回执只保留标识、耗时、用量以及工具载荷大小和摘要。归档排除了客户数据、模型请求正文和报告正文。新增进展事件支持关联工具参数与结果，专项验证使用独立任务预算。InnoDB 和 Docker 都由系统自动终止智能分析并交付，测试保护未发出取消请求。固定快照多轮复测、真实低性能模型及完整故障矩阵仍需分别取证。CDH YARN 的完整业务链路仍受分类模型发布配置阻塞。

此前独立的零模型请求预算测试已为两个场景验证确定性基础报告交付。 [前序验证记录](../native-basic-report-20261128/verification.json)
