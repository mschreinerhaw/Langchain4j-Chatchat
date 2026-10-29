# Cline Python 执行与修复监控专项修复

## 审查结论

本次以 [2026-10-22 交付记录](runtime-execution-feedback-delivery-20261022.md)及当前源码、真实部署和浏览器为依据。沿用 `runtime_os.execution_feedback.v1`、原 Java journal、资产归档及 Vue 监控组件，没有新增监控数据库、分类器或日志采集系统。

实际链路：

```text
Cline SDK python_execute
 → ClinePythonCallbackController
 → CodingPythonExecutionBridge / PythonManagedExecutionService
 → Docker Sandbox runtime_capture.py
 → manifest + receipt.json + 安全 runtime-diagnostic.json
 → RuntimePythonAssetArchiver / CodingExecutionFeedbackService
 → DurableCodingTaskService.recordExecutionFeedback
 → CodingTaskMonitorController：executions + 事件分页
 → codingMonitor.ts / CodingTaskMonitor.vue
```

| 核查项 | 实际结果与断点 |
|---|---|
| Sandbox 真实诊断 | 已存在。新真实任务产生 SyntaxError、KeyError、ModuleNotFoundError；exitCode=0 的无效结果由既有 Java 校验产生 OUTPUT_CONTRACT，不能伪称 Python 抛出了这个错误。 |
| Java 安全反馈 | 已保留异常类型、诊断码、受限文件/行号、固定安全建议、退出码、代码哈希和产物状态。没有依据重新实现分类器或保留原始客户日志。 |
| 持久化 | 每次执行按 operationId 保存在原 journal 的反馈中，并产生 EXECUTION_FEEDBACK 事件、资产归档；已有幂等、租约和 fencing。本次未改写旧反馈。 |
| 监控 API | `executions` 已返回完整反馈。旧 Vue 表格只用了其中一部分，遗漏 exceptionType、diagnosticCode、scriptVersion 等可用字段。 |
| 代码与修复关联 | 旧代码列表主要依赖 Node CODE_READY 遥测，且 TOOL_FINISHED 的 payload.operationId 未被正确用于关联。没有利用归档 feedback.script 和 Java 修复计数恢复完整版本，也没有将失败执行与后续验证串联。 |
| 计数 | 代码版本变化不是自动修复次数。真实联合场景有 5 个代码版本，但只用了 3 次错误修复；最后一次是成功后的复核。现分别统计进程结果、产物失败和失败后的修复结果。 |
| 起止时间 | v1 反馈没有起止时间，但已归档 MCP receipt.json 保存了真实 Docker 起止时间。A2 故意只记录派发意图，不将其冒充 EXECUTION_STARTED；旧前端按未实际产生的事件类型找时间。新增只读时间投影，不扩展反馈契约。 |
| 版本 | 修复前的隔离页面实际浏览器截图与源码中的旧表格一致，API 中确有页面未显示的完整字段。新候选使用构建摘要标识，生产仍是原部署，不能宣称生产已更新。 |

Node 独立监控保存的是受限遥测，产品中的 CodingTaskMonitor 从 Java 持久 journal 读取权威反馈。本次没有把完整诊断再复制到另一套 Node 状态存储。

## 页面与后端调整

- 执行概览：执行尝试、进程成功、失败/超时、取消、产物校验失败、自动修复、修复后仍失败、当前真实代码版本、历史最后错误及最后产物验证。未确认的派发不计为进程成功或失败。
- 每次执行：真实 ID、序号、文件名、版本、哈希、异常类型、诊断码、安全消息、受限定位帧、退出码、耗时、产物及业务验证；明确“原始日志不可用”，不拼接伪造 Traceback。
- 修复关系：使用 Java 已记录的修复计数变化，或明确绑定的失败父哈希，关联前后执行。单纯相邻版本不自动视为修复。Diff 按需调用原源码接口，并核对双方代码哈希，切换任务时拒绝迟到响应。
- 时间线：按真实 journal sequence 排序和 eventId 去重，展示提交、派发、失败/产物校验及后续结果；明确派发意图不是进程启动证明。模型成功不改变 Python 状态。
- 长任务约束折叠显示；执行记录、修复入口和代码对比分区，源码与 Diff 使用现有组件。

只增加现有受保护 artifact 路由中的 `timing` 投影：

```text
GET /api/v1/agent/runtime/runs/{runId}/coding/{taskId}/{operationId}/timing
```

它只读取原 RuntimePythonAssetArchiver 中已归档的 `receipt.json`，返回 operationId、codeHash、startedAt、finishedAt、source 五个字段。沿用 run owner/tenant 授权和任务归档绑定；不请求模型、不重新启动或查询沙箱、不返回原日志、环境凭据或容器细节。前端再核对执行 ID 和哈希，归档时间只加载一次，不随轮询重复下载。旧任务缺少归档时返回 404，页面显示不可用，不拿 Java 调度时间填充。

没有改变 `runtime_os.execution_feedback.v1`、MCP 协议、任务数据库或自动修复循环。模块名没有从任意异常原文放宽保存；缺依赖的真实名称可从对应版本导入语句和实际 Diff 核对，固定安全消息不被解释成模型根因。

旧前端不受新增时间投影影响；新版前端遇到不支持该投影的旧 API 或缺档记录时，只将起止时间标为不可用，其余反馈与代码查看仍可使用。

## 真实验收记录

使用隔离环境中的真实已发布 `db_ops_assistant`、配置模型、Cline SDK、MCP 和既有 Python 镜像。三行受控 CSV 的 amount 为 11.23、14.56、7.89，正确合计为 33.68；不是客户生产数据。

| 场景 | 父任务 / run | 真实结果 |
|---|---|---|
| A/B/D/E 联合验证 | `6d021fab-0ae9-3ca4-bcb4-dc66763953cd` / `att-1-a7872437-bc74-4a65-b96e-6bc4ffc74263` | v1 SyntaxError；v2 KeyError；v3 exitCode=0 但 OUTPUT_CONTRACT；v4 修复并归档；v5 成功后的复核。5 次执行、3 次进程成功、2 次进程失败、1 次产物失败、3 次自动修复、2 次修复后仍失败。Coding 最终 COMPLETED，业务校验仍 INCONCLUSIVE。 |
| C 缺依赖 | `3d0e7652-d206-3ffd-be7f-a54159b007ee` / `att-1-8d22de58-9687-45ec-bb82-2eae7c36bf70` | v1 ModuleNotFoundError / PACKAGE_NOT_INSTALLED，定位第 1 行；v2 用已安装标准库修复成功，1 次修复。真实 Diff 删除了受控缺失模块的 import，没有安装依赖或扩大网络权限。 |
| F 历史恢复 | 上述两任务 | API 更新重启前保存完整反馈，重启后及浏览器刷新重新打开任务，按执行 ID 核对反馈、版本及 Diff；监控读取不触发模型或 Python 执行。最终证据见下方浏览器与恢复记录。 |
| G 真实页面 | 上述两任务 | 浏览器直接读取真实 API，不拦截、不替换网络响应。对照 API 校验计数、错误类型/定位、每次修复前后版本及哈希、真实 Diff、执行器起止时间和最终验证。 |

另发起的一次“持续 ValueError 直到修复上限”尝试，被模型处理为一次成功的测试报告，未产生预期连续失败；该尝试保留但不计为修复上限验收通过。本次 D 使用联合任务中三次独立失败及两次修复后仍失败证明，未把模型成功冒充失败执行。

## 自动化、证据与回退

最终专项测试：Java 92 项，90 通过、2 项 Docker 门控跳过，零失败/错误；前端 44 项通过，Vite 构建通过。不是全仓库全量测试。真实浏览器验证两任务、7 次执行、4 组修复 Diff，页面无 JavaScript 异常；每条起止时间又与原资产 API 下载并校验 SHA256 后的 MCP receipt.json 独立对照一致。

浏览器复验脚本：`scripts/verify-coding-monitor-diagnostics-browser.mjs`，需要私有会话和真实任务清单；不包含账号、Token 或虚构页面数据。关键证据：

- [真实浏览器验收](cline-python-monitor-observability-20261028-evidence/browser-proof.json)、[执行回执、原始归档哈希与重启对照](cline-python-monitor-observability-20261028-evidence/real-execution-proof.json)
- [执行概览截图](cline-python-monitor-observability-20261028-evidence/syntax-field-output-overview.png)
- [SyntaxError](cline-python-monitor-observability-20261028-evidence/syntax-field-output-execution-1.png)、[KeyError 与真实起止时间](cline-python-monitor-observability-20261028-evidence/syntax-field-output-execution-2.png)、[退出 0 但 OUTPUT_CONTRACT 失败](cline-python-monitor-observability-20261028-evidence/syntax-field-output-execution-3.png)
- [v3 → v4 修复 Diff](cline-python-monitor-observability-20261028-evidence/syntax-field-output-repair-3-diff.png)、[缺依赖真实导入语句及修复](cline-python-monitor-observability-20261028-evidence/dependency-repair-1-diff.png)
- [真实执行时间线](cline-python-monitor-observability-20261028-evidence/syntax-field-output-timeline.png)、[修复前旧页面](cline-python-monitor-observability-20261028-evidence/before-monitor.png)
- [自动化测试](cline-python-monitor-observability-20261028-evidence/test-results.json)、[本次代码文件及摘要](cline-python-monitor-observability-20261028-evidence/changed-files.json)
- [部署摘要](cline-python-monitor-observability-20261028-evidence/build-manifest.json)、[实际服务身份](cline-python-monitor-observability-20261028-evidence/deployed-containers.json)、[实际页面 bundle 与本地构建一致](cline-python-monitor-observability-20261028-evidence/served-bundles.json)、[生产未替换核对](cline-python-monitor-observability-20261028-evidence/production-preservation.json)

工作区在本任务开始前已有上一轮分析链路整改未提交。本次证据逐一复核那一轮 64 个实现/测试文件 SHA256 均未改变；以上“本次修改清单”以本轮开始时的工作区为基准，不将此前核心改动归为本次监控专项。

本次应用修改文件：

| 文件 | 修改 |
|---|---|
| `chatchat-api/web-app/src/services/codingMonitor.ts` | 真实执行、修复与时间线投影，旧记录恢复与去重，payload.operationId 关联，时间投影客户端 |
| `chatchat-api/web-app/src/js/components/CodingTaskMonitor.ts` | 概览与关联、哈希核对、异步响应隔离、按需源码/Diff、归档时间缓存 |
| `chatchat-api/web-app/src/components/CodingTaskMonitor.vue` | 逐次诊断、修复关联、概览与时间线、长目标折叠 |
| `chatchat-api/web-app/src/styles/components/coding-task-monitor.css` | 执行与修复分区及窄屏布局 |
| `chatchat-api/src/main/java/com/chatchat/api/controller/agent/CodingTraceArtifactController.java` | 复用原归档的安全只读起止时间投影 |
| 对应前端两份测试、`CodingTraceArtifactControllerTest.java` | 错误/成功分离、归档恢复、Diff、派发语义、时间投影授权、隐私与旧记录 |
| `scripts/verify-coding-monitor-diagnostics-browser.mjs` | 真实 API 与真实页面一致性、Diff 和刷新恢复验收 |

候选仅替换隔离 API 和其回调代理；MCP、Node、模型配置、Secret、License、业务数据库及生产容器不替换。回退时排空隔离任务，恢复记录中的 previousImage，并复用原挂载与代理配置、重算现有 worker/API 防火墙 IP。没有数据库迁移，也不删除已有任务或资产。

剩余边界：旧历史缺少反馈、代码或回执时只能显示未记录；原始客户 stdout/stderr 不恢复；INCONCLUSIVE 不代表业务正确性已独立验证；本次验证终态任务的服务重启恢复，没有新增对模型调用进行中重启的承诺。生产尚未应用本次修复。
