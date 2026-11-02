# Cline 联机业务验收

验收日期：2026-11-03（Asia/Shanghai；各运行时间保留原始 UTC 时间戳）。环境：[192.168.195.221:18100](http://192.168.195.221:18100)。在实际应用登录、上传合成附件、Runtime/Cline 模型调用、Python Sandbox 执行及产物归档后核验，未用模拟模型替代业务运行。

最终运行镜像：API `runtime-os-api:cline-context-completion-20261102-r3`，worker `runtime-os-coding-agent:cline-context-completion-20261102-r2`。发布与回滚材料保留在 `/opt/chatchat-releases/cline-context-completion-20261102-r1`。

## 结果

| 用例 | 实际状态 | 独立核对结果 | Coding 耗时 | 模型调用 | 输入 / 输出 Token | Python 执行 |
| --- | --- | --- | --- | --- | --- | --- |
| 简单 CSV | 任务 SUCCEEDED；需求 COMPLETE；展示 SUCCESS | 5 行，区域缺失 1，销量 10，金额 43.75；4 个区域/缺失分组的图表与表格一致 | 111.601 秒 | 3 | 75,197 / 5,378 | 2 次，均成功 |
| 多文件关联 | SUCCEEDED；COMPLETE | 4 笔订单；华东 12.30、华南 3.30；未匹配 1 笔 4.40；合计 20.00 | 67.864 秒 | 3 | 54,612 / 3,308 | 1 次成功 |
| 精确修复 | Python Studio / Coding COMPLETED；COMPLETE | 首次真实 SyntaxError；第二次成功，仅补标题引号，汇总函数及其余源码逐字保留；金额 30.50 和区域图有效 | 39.189 秒 | 3 | 53,653 / 1,072 | 先失败、后成功 |
| 账户语义缺失 | 父任务 PARTIAL；Coding COMPLETED；INPUT_REQUIRED | 确认 2 行结构；列出金额、符号、币种、时间及风险规则缺口，没有发布净流入或风险图 | 85.996 秒 | 4 | 64,949 / 4,414 | 1 次成功 |

这些状态符合不同任务的真实交付范围。账户样本所求业务结论确实受缺失定义阻塞，不能为了绿灯改成 COMPLETE。图表和结构报告成功也不等于业务语义已独立认证。

耗时为 Coding outcome 的 endedAt − createdAt，不包含父任务前置规划，也不使用人工轮询间隔。普通任务的用量取 Java 原始模型回执；Python Studio 的用量取持久化 MODEL_USAGE 事件，并与累计 modelTokens 勾稽。保留计量未完全结算的边界，不把 costAmount=0 当作免费或已完成费用核算。

这组样本是当前实现的质量与用量记录，不是受控前后对照；不能与此前其他模型、数据或不同修复次数的运行直接相减来宣称节省比例。计量中仍可见初始任务说明和历史报告的较大占比。

## 联机发现及修复

1. `VisualReportArtifacts.collect`（127–138 行）原先将程序警告和图表缺失混在一起。首次 CSV 已返回 COMPLETE、正确数值及图表，却因发现 1 条缺失区域被显示为 PARTIAL。现在保留数据限制，单独处理真实展示缺失；新增 requirementStatus 保留 Agent 的需求完成声明。无效图表、失败或未决计算检查及显式 PARTIAL / INPUT_REQUIRED 继续保留其约束。28 项 VisualReportArtifactsTest 全过，包含 3 项新增用例。
2. `deploy/docker/application/Dockerfile.coding-agent` 原先遗漏运行时必需的 Skills，镜像内测试在切换环境前拦截了启动失败。模板现复制完整技能包、建立 `.cline/skills`，并在构建时检查目录和技能发现数量。实际部署镜像中 163 项 Node 测试通过，原生链接已验证。
3. 首次服务替换时动态分配的 API/worker 内网地址互换，导致首个问题在进入 Coding 前超时。失败任务 `184f0887-808b-37b4-a97b-ed4e808e5330` 保留为失败，未计入通过用例。恢复原 API `172.30.93.2`、worker `172.30.93.3` 后重测；没有放宽网络隔离规则。最终地址、资源配置和代理绑定已复核。

简单 CSV 在最终 API r3 上重新完整运行，验证新的归档与展示路径；修复样本也在 r3 上运行。多文件、语义缺失样本运行于配套 worker r2 / API r1；随后 r3 仅修改其不涉及的视觉报告归一化类，最终环境再次读取并验证其报告和归档哈希，未重复消耗模型重跑这两个不受影响的用例。Java 专项回归同时覆盖结构验证和回答状态转换。

## 证据与交付检查

普通三项通过正式“服务器主机运维助手”接口提交；精确修复通过正式 Python Studio 代码复用接口提交并授权冻结的完整源码。上传附件 SHA-256 与固定样本一致；输出通过拥有者授权的资产接口下载，校验大小和 SHA-256。独立数值计算采用整数分，未复用 Agent 的 Python 计算函数。多文件合计由报告中的两个区域小计和未匹配金额勾稽，不将“整体总额”额外强加为未请求交付项。

修复前后文件逐字比较，只增加一个闭合引号；第二版父代码哈希精确匹配第一版失败执行，changeKind=PATCH。报告 JSONL 末尾为真实换行，报告正文正常分行。两个 PNG 已检查头部、尺寸、哈希并人工查看，中文和区域数值显示正常；数值正确性还由实际计算代码、归档回执和独立汇总交叉验证，不以截图 OCR 替代数值校验。

- [四项规范化观察与执行回执](observations.json)
- [独立数值核对](numeric-oracles.json)
- [可重跑的整数分核对程序](verify-numeric-oracles.mjs)
- [实际报告正文](reports.json)
- [逐次上下文字节归因](request-contexts.json)
- [CSV 展示与图表验收](simple-sales-visual-check.json)
- [CSV 图表](simple-sales-csv-chart.png)、[修复图表](exact-repair-chart.png)
- [旧报告只读重验](historical-display-replay.json)：警告保留，展示恢复 SUCCESS，原任务执行次数仍为 1，没有重放 Python
- [部署](deployment.json)、[健康检查](health.json)、[测试记录](tests.json)

门禁可复验：

```powershell
node coding-agent-node/scripts/check-analysis-regression.mjs docs/cline-context-completion-20261103-evidence/observations.json
node docs/cline-context-completion-20261103-evidence/verify-numeric-oracles.mjs
```

最终普通任务、Coding 执行器均已停止；worker 活跃会话、父任务和 Sandbox 均为 0；worker、node-proxy、gateway 健康，首页 HTTP 200。API/worker 旧容器及原 JAR 保留用于回滚。公开证据不含登录密码、会话令牌、容器完整环境配置或非合成业务数据。

现场没有触发新增 code_read 工具调用：短会话仍保有足够源码，项目型修复走兼容路径。code_read 的哈希拒绝和按需恢复由专项单测覆盖，不能将此处业务结果冒充该工具的联机调用证明。没有模拟部署环境崩溃恢复，没有全仓回归；既有状态与权限约束继续保留。
