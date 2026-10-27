# Runtime OS 业务任务进度展示（2026-10-27）

本报告记录此前已部署的第一版五阶段展示。随后开发的实时执行工作台尚未完成部署验收，状态及待办见 [未完成工作说明](../runtime-os-remaining-work-20261027.md)。

本项仅改变前端展示，未改动后端事件、执行、调度、恢复或权限机制。SQL 输入修复属于另一项变更。

## 原因与改动

原路径：任务 SSE/轮询 → `ChatAssistantView.mergeExecutionSteps()` → `ChatMessageList.runtimeProcessSteps()` → 事件列表。列表按事件 ID 逐条展示，重复恢复事件不断新增行；原进度条按事件行数及活动行权重计算，不能代表实际业务步骤。

保留事件行和原始顺序，在现有 `runtimeObservationPresentation.js` 中增加无状态展示投影，固定五个业务步骤：

1. 接收任务：创建、提交和启动合并。
2. 理解需求：需求识别和规划。
3. 准备数据：真实文件绑定、供数和输入准备。
4. 执行分析：执行任务及真实执行反馈。
5. 返回结果：结果校验和最终任务结果。

编号固定为 `1/5` 至 `5/5`。恢复与预算更新不生成新步骤，也不推进进度。展示只依据结构化阶段、状态和父任务终态，不依据事件条数、耗时或日志文本中的“成功”。

失败、取消和超时保留在所属步骤，后续普通事件和流关闭通知无法覆盖。父任务失败未指定阶段时，落在已有当前步骤。代码提交、执行返回和单次校验均不等于最终成功；未单独记录的步骤显示“未单独记录”，不伪造完成证据。

只有明确的新 attemptId 才展示新一轮执行；旧尝试的失败日志不会删除，也不会误算为新尝试失败。普通恢复事件不属于新尝试。

聊天页和 SQL 助手共用投影，默认隐藏事件类型、工具标识和内部技术名称。错误描述保留必要原因并过滤技术名称。已有任务进度时，不再重复显示“准备任务”占位气泡。技术排障页、服务端事件和日志仍保留。

## 文件

- `chatchat-api/web-app/src/js/utils/runtimeObservationPresentation.js`：映射、聚合和异常锁定。
- `chatchat-api/web-app/src/js/views/ChatAssistantView.js`：在原事件行保留简短的展示分类，不删减事件。
- `chatchat-api/web-app/src/js/components/ChatMessageList.js`、`src/components/ChatMessageList.vue`：数字进度、业务步骤和占位去重。
- `chatchat-api/web-app/src/js/utils/sqlTaskPresentation.js`、SQL 助手组件：当前及历史会话复用聚合。
- `chatchat-api/web-app/src/styles/pages/chat-assistant.css`：数字标记布局。
- 对应测试覆盖事件映射、聊天实际 DOM、SQL 助手 DOM 和 SQL 状态投影。

## 验证

- 定向 Vitest：5 个文件，95 项通过。
- 原 Node 兼容测试：4 项通过。
- 生产构建通过，包括 63 个 Vue 组件结构检查。
- Chrome 实际页面：追加 20 条恢复事件和失败/预算事件后，原始事件行 26 条，业务列表仍为 5 条，编号不变；失败落在 `4/5 执行分析`，无内部名称。390px 移动视口无横向溢出，无脚本异常。控制结果见 `browser-verification.json`。
- 扩大测试：UI 321 项通过、1 项失败；SQL Studio 70 项通过、3 项失败。4 项失败均在未修改的基线 `425797114` 独立复现：MCP 按钮模板的旧精确空格断言，以及现有 Node 测试环境的 `localStorage.clear is not a function`。扩大测试未标记为全通过。
- 另检查了 5 项现有部署/归档辅助测试：4 项通过，Windows 路径拒绝测试 1 项失败，并在原基线单独复现；当前 Windows/Python 在构造 ZIP 测试夹具时已经规范化反斜杠，旧断言未触发。实际发布资源使用 POSIX 路径，95 项 HTTP 哈希校验均通过。

## 边界

未增加状态管理框架、组件或后端协调器。投影不授予权限、不触发重试、不判断业务正确性、不删除技术日志。原始事件和技术监控接口仍用于追踪真实执行。

## 部署核验

已部署至既有服务器环境，发布目录 `/opt/chatchat-releases/runtime-task-progress-20261027-r5`。该发布只替换前端资源：1,150 个非静态资源条目与上一运行包逐字节相同，后端 class 覆盖数为 0，运行配置保留；95 个前端资源均通过 HTTP 下载校验 SHA-256。详情见 `deployment-verification.json`。前一运行包及容器保留用于回滚。
