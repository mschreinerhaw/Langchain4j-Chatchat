# Native Cline Monitor 集成设计

基线：`db276d8e151ddb9669b603664e5c7bffb0de9843`。执行链继续使用 `feature/runtime-cline-cli-native-20261107`；独立工作目录的监控分支为 `feature/runtime-cline-native-monitor-20261109`。监控模块不修改 business-server.mjs、官方 Core 或旧 SDK Runner。

2026-11-09 统一交付：监控分支已合并至指定执行分支。Native 直接模式新增只读 Core 轮次表，按真实 taskId 绑定 usage/error，明确配置模型及观测耗时的来源；Runtime 模式沿用 Java 回执。面板保留已展开的诊断，源码/文件异步读取绑定当前 Session 和标签，过期响应不覆盖新选择。最新结果见 [统一交付验收](../testing/native-cline-business-monitor-delivery-20261108.md)。

## 来源与映射

已审查旧 `monitor.ts`、`monitor-types.ts`、`coding-progress.js`、`runner.js`、`public` 资源路由及浏览器脚本。旧 MonitorStore 的状态推断、SDK checkpoint 和恢复事件不移植。

| 旧页面能力 | 新版实际来源 | 权威及限制 |
|---|---|---|
| 任务列表/详情 | 私有 Native session.json、Runtime run.json 的白名单投影 | 只展示配置的操作员及其 Runtime scope；私有控制凭据不导出 |
| 对话 | Standalone `/v1/sessions/:id/messages`，`task.accepted`，`native.event` | `agent_event.payload.event` 为 Core 事件；排除 reasoning 和 agent compatibility chunks |
| 工具时间线 | `tool.started/completed/failed`，toolName/toolCallId | 工具成功不推断业务成功 |
| 模型 | Runtime CodingTaskMonitorController 的 modelReceipts | Token、计费未知显示 UNKNOWN；原生直接模式用量仅标记原生观测 |
| Skills | Core skills 工具完成及 Runtime SKILLS_LOADED | discovery、staged、loaded 分开，不用复制文件推断加载 |
| MCP | Core MCP 工具及 Gateway mcp.request/receipt/failure | 保留实际调用关联，不沿用 SDK event 名 |
| 编辑/Patch | Core 工具输入/结果；实际 CODE_READY 和 codeLifecycle | 编辑尝试不计为执行通过；无完整旧版本时标明证据不足 |
| Python | Runtime executions、Gateway runtime.event/mcp.receipt | 保留 operationId、停止证明及执行反馈；exit 0 不等于交付 |
| 最终状态 | Native status 与 Runtime outcome 并列 | Runtime 为业务完成权威，PARTIAL/UNKNOWN 保留 |
| 取消/批准 | 原生 cancel、permissions/respond；Runtime coding/cancel | 使用真实接口，不另建控制状态机；Runtime 自动授权不可在浏览器覆盖 |
| 恢复 | 持久日志补拉及 Runtime resume（仅后端允许时） | 监控不重放任务，不产生新 Python 操作 |
| 文件 | Runtime user-assets 列表与 owner 鉴权 content | 只展示元数据，内容需显式下载，不自动渲染敏感数据 |

## 独立模块

`cline-agent-server/monitor` 是不依赖前端构建链的独立 Node HTTP 服务及三栏浏览器页面。后端只调用既有执行/权限/Runtime HTTP 接口并读取已存在的持久日志，不持有 Docker Socket，不引入任务数据库。页面浏览器登录交换短期 HttpOnly cookie，Runtime JWT 和 Native 服务凭据只在后端。

SSE 使用来源日志的持久 eventId 组成 `来源:eventId` 身份，并以各来源游标组成重连 ID。浏览器去重并保留来源时间；重连后读取持久日志补齐。共享读取缓存限制日志读取频率，订阅关闭不取消执行。业务创建通过现有 exploration prepare/execute 或显式 CSV 合同的 user-assets/analyses 入口，控制台不生成可信合同、预算或源码。

文件分析没有父 Agent Run。新增的按 taskId 读取接口先核对已存在的 DurableCodingTaskService 合同 tenant/user/run，复用任务 Journal、模型回执和 Python 执行投影；不伪造父 Run。已有父 Agent Run 的权限规则保留，跨用户/租户回归覆盖两种路径。源码与日志通过 Runtime 现有 owner 归档按需读取，不能把路径参数映射为任意磁盘读取。

CSV 独立验算由执行分支提供注册的 `runtime-csv-count-sum:v1` 扩展及持久验证计划。它重新校验不可变输入及原始 CSV 分片 Hash，独立解析原文件并核对 rowCount、精确 Decimal sum、源 Hash；不采用模型生成的验证代码或期望数值。通用结构验证仍为 INCONCLUSIVE，不被提升为此领域验算。

## 验收边界

原生测试和 Runtime 业务测试有独立状态标签。仅以真实浏览器派发、真实模型及 Python 回执验收；单元测试和历史只读回放不计真实交付成功。联合通过必须具备浏览器请求、Session/Runtime/operation 身份、真实错误与修复、产物 Hash、独立业务验证及截图。
