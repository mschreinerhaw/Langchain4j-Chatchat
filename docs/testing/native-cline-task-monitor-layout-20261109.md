# Native Cline 任务监控优先布局

日期：2026-11-09。分支：`feature/runtime-cline-cli-native-20261107`。入口：<http://192.168.195.221:18346/monitor/>。

本次按“任务执行监控为主，对话辅助测试”调整现有页面。

- 默认进入 Runtime 任务监控并选中最近的任务。任务列表支持类型、真实观测状态和 ID 筛选；列表注明 Runtime 已查询状态与 Native 网关观测来源。
- 主区域首先展示业务状态、模型调用数、Python 执行状态、业务验证结果，然后是可展开回执的执行时间线。模型、工具、代码、Python、产物和诊断的标签位于主区域。
- Runtime 的业务结果为首要状态；Native 网关、Core 本轮、停止确认和关联 ID 放在右侧任务信息中，分别保留真实值。
- Runtime 任务不显示聊天输入框；执行文字记录默认折叠，通过“对话记录”辅助查看。
- 顶部“对话测试”是独立辅助入口，进入后才突出 Native 会话和多轮对话。“新建分析任务”和“新建对话”分别匹配当前视图。
- 切换任务立即清空旧状态并显示读取提示，回执和事件流绑定新任务，避免上一任务的 COMPLETED 短暂出现在失败任务中。

## 验证

部署的界面实现：`b1a26549f2fb13e2d8e52bc9236bcc46c290b666`。后续提交补充测试脚本修正、说明与证据；执行链及业务数据未修改。

| 检查 | 实际结果 |
| --- | --- |
| Node 回归及 JS 语法 | 42/42；语法检查通过 |
| 默认入口、任务筛选、创建类型 | 浏览器通过，默认 Runtime 任务及执行时间线，新建入口默认 Runtime 分析 |
| 用户截图中的失败任务 | `analysis-coding-4317632155748de9f0a8e835224846ddd845f103e4a70de274bca5fe6e1b69ae`：主区域 BUDGET_EXCEEDED，右侧 Native ABORTED；MCP method unavailable 原因直接出现在时间线 |
| 独立对话入口 | 浏览器通过；切换后才显示 Native 对话输入和对话优先布局，创建类型为 Native |
| 已成功业务的查看与下载 | Runtime COMPLETED，5 条模型回执、2 次 Python 执行保持；实际下载 report.md，SHA 与既有归档相同 |
| 原有源码/Python 诊断 | 只读浏览器回归通过；实际 NameError 日志在 SSE 更新后仍保持展开，Native Diff 可查看 |
| 刷新、屏幕适配 | 当前任务定位保持；1880、1280、390 宽度复核，窄屏无横向溢出 |
| 对执行的影响 | 浏览器记录除登录外无 POST 控制请求，没有新建、取消或重新派发业务任务 |

本次只修改监控前端及测试说明，复用现有 API、授权、SSE、回执和归档；不修改 Java、官方 Core、执行策略、模型或预算。因此未重复执行 Maven 全构建及模型业务场景，已有完整构建与业务验收结论仍见 [统一交付报告](native-cline-business-monitor-delivery-20261108.md)。本次界面通过不改变历史业务成功率或生产替换边界。

取证中第一次暴露任务切换的旧状态残留，已修复；第二次因测试脚本 waitForFunction 触发现有 CSP 而失败，改用元素定位等待后通过，没有放宽 CSP。失败记录和最终通过记录分别保留。

证据：[浏览器结果](evidence/native-cline-task-first-20261109/browser-task-monitor.json)、[原有诊断回归](evidence/native-cline-task-first-20261109/execution-regression.json)、[部署文件 Hash](evidence/native-cline-task-first-20261109/deployed-assets.json)、[SHA256 清单](evidence/native-cline-task-first-20261109/SHA256SUMS)。

截图：[任务监控首页](evidence/native-cline-task-first-20261109/task-monitor.png)、[失败任务](evidence/native-cline-task-first-20261109/failed-task.png)、[辅助对话](evidence/native-cline-task-first-20261109/auxiliary-chat.png)、[1280 宽度](evidence/native-cline-task-first-20261109/monitor-1280.png)。
