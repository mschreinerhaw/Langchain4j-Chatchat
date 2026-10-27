# 实时任务执行工作台（2026-10-27）

基于 `5dc7fab75` 的未完成清单继续开发。Git 同步使用 fetch/prune 与当前分支 fast-forward，保留其他功能分支，未合并无关分支。

## 修复

- 工具开始事件保留 operationId；压缩后的事件保留 attemptId、toolCallId 及有界 workerActivity，开始/结束可关联，私有提示词、SQL 和源码不进入新增事实字段。
- 完成操作去重，按序号处理迟到事件，按尝试隔离；同工具并发使用操作标识区分。同类并行时段合并计算，只有结束记录时耗时保持未知。
- 未知执行结束不计成功；明确 repairAttempt 才重新打开同一失败操作，清空上次结束时刻与耗时。修复成功不删除失败原始日志。
- 明确 canonicalState 为任务终态依据，历史界面 completed 标记不能覆盖失败事件；心跳用于时钟校准及通道检查，页面长时间提醒不判断 Runtime 超时。
- 已完成历史会话也从事件接口完整分页重放；替换投影前完整读取，游标不前进则保留原投影并提示记录不完整。恢复只读，不提交任务，不把已完成历史重新发布成运行中会话。
- 已完成会话重放后显式刷新可见消息数组，修复原始对象与 Vue 代理混用导致的统计不重绘；失败时的不完整记录提示也走同一刷新路径。
- 已结束会话停止每秒更新投影。主聊天工作台保留五阶段、总体状态、实时操作、阶段成果及键盘可操作详情。SQL 助手保留现有紧凑五阶段布局，共用基础事件语义；本轮不另建执行入口。

## 验证

- Java 17 完整 API reactor package 通过。后端定向回归 33 项通过：事件发布 13、SSE 4、模型回调 15、执行桥 1。
- 前端 8 个文件、136 项测试通过，覆盖重复/乱序、跨尝试、同工具并发、修复、时钟偏差、状态连接中断、取消/超时、未知耗时及多页恢复。
- 真实任务发现 SQL 静态检查会被旧工具分类误称为结果校验。新增程序检查类别，保留在分析阶段；只有明确的产物读取才归入结果验证，未知工具不推断为验证完成。
- 真实 Chrome 桌面 1280px / 移动 390px 验证，无横向溢出，无 pageerror，详情支持键盘展开。`visual-*.png` 使用明确的视觉夹具，仅用于界面验收。
- 10,000 条观察事件投影连续 10 次，本机平均约 89.7ms；活动列表最多展示六条，结束的历史会话不再每秒计算。此为本机测量，不是服务端吞吐承诺。

## 发布与回滚

发布仅覆盖工作台相关 API 生产类、chat 事件发布类及前端资源，基于运行包摘要 `da5298b92fc36e6f859434f6a4ec473f2b0b812e37529417aba027d9d6938571`，保留其余库和配置。复用 `scripts/deploy-runtime-template-supply.py` 的空闲检查、旧容器保留、代理重新绑定、资源摘要校验与失败回滚。

最终发布目录：`/opt/chatchat-releases/workbench-20261027-r3`，运行源码 `78342faaa`，运行包 SHA-256 `b884d8deaea838552ddcdcd26d08c4a499bbb31ec82138a58924d4fff95a5974`。入口 `http://192.168.195.221:18100/`。95 个资源逐项通过 HTTP SHA-256 校验；R3 的 1,150 个非静态条目与 R2 逐字节一致，MCP 包及运行配置保持原值。见 `deployment.json`。

前端定向回归最终 136 项、后端定向回归 33 项均通过，完整 Java reactor package 和最终 Vite build 通过。实际部署页面经 Chrome 登录加载，无脚本异常及服务端 5xx；通过原有会话恢复方法加载真实任务记录，未重新提交计算。

### 真实任务与历史重放

本轮新提交 CSV/SQLite SQL 任务 `70332352-31db-31cb-ae61-0fe0c53480a3` 为 SUCCEEDED，报告包含 3.75；Coding 实际执行两次 Python，四次模型调用。124 条父任务事件中包括 18 条执行节点观察，包含真实运行、停止确认。最终前端投影为五阶段完成、10 个去重操作（含一项需求规划）。独立业务校验状态为 INCONCLUSIVE，未宣称 PASSED；这里只核验了固定合成 CSV 的已知合计与执行事实。见 `normal-task.json`、`normal-task-events.json`。线上完整恢复后实际 DOM 指标为 `05 / 05`、`06:36`、`10`，见 `deployed-browser.json`、`deployed-workbench.png`。

本轮正式取消任务 `e409b4c7-6868-37a8-b66a-ac5f37877d8b` 在规划阶段取消为 CANCELLED，未产生 Python 操作。见 `cancellation.json`。

当前服务还完整读取并重放六组真实持久化记录，其中历史执行失败为 248 条，确实跨过 200 条分页。`scripts/verify-workbench-replay.mjs` 核验正常完成、历史执行超时、执行失败、语法修复成功、供数预算拒绝、模型失败的终态与去重。证据见 `replay-verification.json`；历史记录缺少新执行观察字段时保持未知，不补造运行确认。

新版本未重新注入上游数据库宕机或模型服务断网。历史故障重放验证的是兼容展示与恢复，不能替代所有故障在新版本上的实时注入。外部授权数据库 SQL 仍需选定数据源后独立验收；本轮没有更换模型绑定或扩大资源权限。

### 健康与回滚

交付前活动父任务 0、Python 沙箱 0；API、MCP、worker、node-proxy、gateway 均运行，已配置的健康检查全部 healthy。见 `final-health.json`。

R3 前一 API 容器为 `runtime-os-app-stage-api-1-before-template-supply-fc1eec5a7a78`；原始 SQL/权限版本也仍作为 R1 的前一容器保留。每次发布目录保存 `before.jar`、`compose-before.json`、`deployment-before.env`、`container-before-private.json`、代理备份及 `deployment.json`。发布异常自动恢复原容器、网络别名、配置和代理，并重验 API 就绪。

手工回滚必须先确认无活动任务，停用并保留当前 API，恢复记录中的备份 API 容器名和原网络别名，恢复 compose 与部署环境文件，再恢复 `deployment.json` 所列 nodeProxy.backupContainer（它共享上一 API 网络命名空间），启动原 API/代理并重载 gateway。最后重验登录入口及资源哈希；不要仅修改镜像标签或单独 restart 共享网络命名空间的代理。备份 JSON/环境文件含运行配置，仅保留在服务器私有发布目录，不进 Git。
