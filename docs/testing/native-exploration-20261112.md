# Native 数据探索、图文分析与活动记录修复

源码版本：`b584e97f17ded74e437eaa88d2ccf162cc5b32c4`（含 `f43c64b504a1472baa94d5475e5681bed0229e99`）。已从提交源码上传构建并部署到 18100。

## 发现与修改

* 数据探索 Agent 入口已设置 `reportMode=visual`，上次修复提供的真实图表投影可复用，没有新增业务报告生成器。
* `ExplorationAgentBridge` 的事件白名单遗漏 Native `normalizedType`、请求 ID、原生时间、模型及部分退出码，导致前端无法关联模型请求和命令执行。现保留必要摘要和证据引用，继续排除工具参数、源码、命令正文与密钥。
* 附件分析直接入口的 Native 快照返回 `payload=trace`，前端却读取 `payload.metadata`。现通过 `NativeCodingProgress.eventView` 统一为现有事件契约，保留原事件 ID、序号、run 和时间。
* 数据探索的交付状态、当前阶段仍只识别旧 Worker 回执。现识别 Native 模型请求、读取、命令开始/结束及可修复错误。命令退出 0 只表示进程结束，正式报告交付仍依据 Runtime 已登记结果。
* 活动列表的终态事件过去没有操作关联时显示“时间未记录”；现使用真实事件时间。诊断日志展示 Native 事件类型，失败模型请求不会混成 Python 执行。
* 初次历史读取实际被 AgentTaskService 限制为 500 条；达到上限时必须继续增量读取，不能提前宣布终态而漏掉后续图表交付。

## 验证

* Java 定向回归：16 项通过，包括授权边界、Native 字段保留、快照契约、增量读取和第 501 条交付事件。
* 前端最终回归：6 个文件、173 项通过，覆盖聊天、工作台、数据探索、Native 修复阶段及活动面板。
* 真实主环境任务经数据探索 API、已授权 `financial-news-agent`、Native Cline 和 Silo 完成。没有手写最终分析源码或图表，也没有 Mock 业务接口。
* 输入为明确标记 SYNTHETIC 的三行 CSV：12.5、20、15。独立核对报告中的行数 3、总和 47.5、均值 15.8333；单位不由 Java 推断。
* 实际采集 132 条任务事件，包含模型请求、工具调用、文件读取失败和后续继续执行、Python 命令开始及结束。失败记录保留在 [事件证据](evidence/native-exploration-20261112/events.json)，没有只留成功记录。
* 真实 PNG 经授权接口下载并核对 SHA-256。浏览器访问 18100 正式数据探索页面，显示图表与活动记录；刷新后图表仍可加载、已保存的事件保持一致，无页面脚本错误。
* 模型状态为“已响应”、数据为“已读取”、Python 为“进程已结束”、报告为“已归档”。显示窗口保留最近 120 条事件，完整原始日志继续使用现有 Runtime Journal/Silo。

证据：[验收结果](evidence/native-exploration-20261112/verification.json)、[截图](evidence/native-exploration-20261112/exploration.png)、[任务回执](evidence/native-exploration-20261112/receipt.json)、[独立数值检查](evidence/native-exploration-20261112/numeric-verification.json)、[回归摘要](evidence/native-exploration-20261112/regression-summary.txt)。

验证范围为本次明确数据的端到端交付与展示，不将任意模型报告的业务语义视为自动验收通过；未新增业务硬编码或修改官方 Agent Loop。Java 测试覆盖直接附件快照契约；真实浏览器端到端覆盖数据探索 Agent 入口。

## 部署与回滚

运行镜像：`runtime-os-api:native-exploration-20261112-r2`，image ID：`sha256:ce6e5a8856c49442d9d1cd7052f66bcf589685e3221cd48a845c5d7a1fa6b9b5`。这是服务器本地构建的 image ID，不冒充仓库 RepoDigest。

服务器归档：`/opt/runtime-os/releases/native-exploration-20261112-r2/`。保留修复前 API 镜像 `sha256:4d93d02480199906ff6c5242385702f030b7d44ff9eb99565367eb4c5fbe7d31`；排空活动任务并检查后续配置后，可运行该目录 `rollback.py` 仅回滚 API 镜像。

没有更换 Native Node、模型动态路由、共享模型配置、MCP、News、Silo 或数据卷。主入口及监控均返回 200，匿名监控任务查询返回 401。归档日志后，仅移除本次已停止验收 Worker 和未部署的 r1 中间 API 镜像标签；未执行全局清理。详见 [部署检查](evidence/native-exploration-20261112/final-check.json)。

刷新浏览器后新分析使用修复后的链路。此次未批量重写旧版本已经固化的终态节点缓存，也不为历史缺失记录伪造事件。
