# 历史验收阶段记录

以下记录对应金融规范和统一日期复测之前的阶段，当前结果以 02-acceptance.md 为准。

# 默认技能集成验收记录

实施时间：2026-10-30 至 2026-10-31。开发分支：`feature/cline-agent-default-skills`，基线 `029fe6ee3`。

功能开发及部署检查已通过。计划书 8.5 的真实客户场景经后续复测已取得资产快照和委托记录，技术链路跑通。最终图文复测正确披露期间/口径限制，输出 INPUT_REQUIRED，但未生成客户图表、未观察到本轮内置技能正文读取，仍未满足完整端到端验收；按“成功后提交”的要求，目前尚未创建本次 Git 提交。最新结论见 [客户问题复测](03-customer-retest.md)。

## 最终部署

- 服务入口：`http://192.168.195.221:18100`。
- API：`runtime-os-api:cline-agent-skills-20261031-r4`，JAR SHA-256 `260a34e8d0e1416de122efa050d6c0c1057c2e9380fa17ef178ff1977e33be46`。
- Coding Agent：`runtime-os-coding-agent:repair-909b97378d0f5cb0`，镜像 ID `sha256:812c9ae762a539f3e673872bb113640a2132e677246e90c82a1d329f56b26c91`。两个技能版本均为 1.0.0，Cline SDK 固定 0.0.93。
- API 发布目录 `/opt/chatchat-releases/cline-agent-skills-20261031-r4`；最终 Worker 发布目录 `/opt/chatchat-releases/cline-skills-method-selection-20261031`。原容器、配置和数据库备份保留，可回滚。
- 发布前确认无活动 Runtime/Coding/Python 任务。API 发布验证会话和消息索引保持不变；Worker 更新验证 User、Env、只读根目录、资源限制、挂载、网络地址与运行策略保留，其他容器不变。

证据：[API 最终部署](deployment-api-final.json)、[Worker 最终部署](deployment-worker-final.json)、[最终镜像验证](final-worker-verification.json)。这些是工作区构建产物，尚无对应的新 Git 提交。

## 自动化验证

- 最终 Worker 在真实 Node 24.21.0、`--network none`、只读文件系统、UID 1000/GID 32101 下执行全部 134 项测试，通过且无跳过。包括原生 SDK 发现、按需加载、同名冲突、版本选择、隔离、原有执行及恢复回归。
- Java 定向回归覆盖技能目录持久化、Agent/role 禁用策略、绑定隔离、任务版本冻结、引用哈希校验、模型与工作区回调。97 项通过；12 项已有 SQLGlot 环境依赖测试因未配置本机 `coding.sqlglotPython` 跳过，不计为通过。
- 前端 36 项测试通过，Vue 结构检查、Vite 构建和 Maven 完整打包通过。最终资源校验通过。
- 测试直接调用真实 Cline Agent 循环的单元用例使用模型 fixture，不将这些用例冒充真实供应商智能选择或真实 Python 执行。

计数及范围见 [自动化结果](automated-tests.json)。

## 真实模型及 Sandbox

1. CSV + Parquet 场景通过。数据为明确标识的合成验收数据，6 行交易、1 个金额缺失值、5 个有效金额，预期总额 80、均值 16，区域金额 East=30、West=45、North=5。真实 Cline 读取两种原始格式、验证多对一关联行数、执行既有 Python Sandbox，并生成 PNG、Plotly 及图文报告；独立核对结果与预期一致。
2. 常规分析任务自主选择不加载技能正文，仍完成计算；另一次明确要求检查适用方法的任务，由 Cline 自行选中 `python-data-analysis`，实际请求其正文和 `references/data-methods.md`。没有在 Java 中为任务指定技能。
3. 独立 Python 工程任务由 Cline 选中 `python-code-development`，读取正文及 `references/engineering-checks.md`，生成 `main.py` 和 `test_main.py`，包含独立函数、参数校验、类型注解、异常处理、日志与配置入口。源码工作区任务只有读取技能和源文件提案权限，未获得 Python 执行权限。随后由验收人员在现有 Python 镜像的断网、只读、低权限隔离容器内运行生成的 unittest，29 项通过。这是独立代码测试，不伪装成源码提案阶段的 Runtime 执行回执。
4. 两个临时 Agent 并发验证：A 获得两个内置技能和三个已绑定领域技能；B 无动态绑定，禁用数据分析技能后仅获得代码开发技能。任务目录及动态内容快照分别隔离，A 固定三份动态快照，B 为零。两者均计算出总和 80；B 对 producer 字段口径保留限制，顶层状态为 PARTIAL，未把它改写为完整业务成功。
5. 初次并发测试中 B 的多目标请求被现有规划器要求澄清；保留原始记录后，将隔离测试缩小为单一求和目标重测。没有通过修改规划器或伪造成功绕过该限制。

正文/引用的实际工具调用和版本证据见 [任务日志摘要](skill-journal-proof.json)，数值及源文件哈希见 [真实模型验收](live-acceptance.json)。Java 回执的 `INCONCLUSIVE` 表示没有自动认证业务正确性；合成数值的通过依据为独立已知预期校验。

## 页面、历史与清理

最终 UI 更新补充动态技能卡片的发布编译版本/联邦摘要显示。首次 R4 发布因遗漏网络限制依赖文件而回滚；补齐文件后重试部署成功，并新增部署前依赖文件检查，首次备份保留。最终浏览器验证动态发布版本可见、页面无错误。

真实浏览器验证两个内置卡片、版本与适用范围可见，内置卡片无卸载按钮。普通删除内置 ID 的请求返回业务错误 400，随后技能目录仍完整。静态 PNG 正常显示，刷新恢复既有报告没有再次执行任务，浏览器错误为零。

Agent 禁用配置真实保存、重新启用和原 Agent 不受影响的测试通过。临时 Agent 已回收并删除，原 `ids_data_audit` 配置按规范化 JSON 核对一致。既有 SQL 任务源码候选恢复 HTTP 200，源码保持一致，没有重跑 SQL。

证据：[页面验证](browser-live.json)、[Agent 策略验证](agent-policy-live.json)、[清理及历史恢复](final-cleanup-and-history.json)。原始模型消息、生成代码、截图、登录会话和客户完整回答保存在忽略目录 `target/skills-live` 或服务器私有发布目录，不提交凭据或真实客户数据。

## 未完成的客户验收

已用已发布且获授权的 `ids_data_audit` 实际提交“查询客户号 070200046604 的交易和资产、盈亏情况；总结交易偏好”。任务 `8b49dfc0-11c5-3a41-b1d0-36c66a205163` 到达 SUCCEEDED，但回答明确说明只得到模板查询/选择元数据，没有客户交易、资产或盈亏业务记录，也没有产生该场景的 Coding 分析和客户图表。

因此该项标为 `BLOCKED_NO_CUSTOMER_RECORDS`，不能用任务终态、模板元数据或前述合成数据替代真实客户验收。原 `busi_data_query` 处于 recalled 状态，本次没有重新发布它或扩大业务数据权限。需要用户指定可实际取数的 Agent/数据源后继续；通过后再创建并推送本次 Git 提交。

## 用户指定问题的后续复测

后续已定位 IDS 工作流只有模板发现步骤，并通过已有金融指标 Agent 取得一条实际资产快照，但首次 Coding 分析失败。不能再将整个环境概括为“没有客户记录”。具体任务、失败原因、修复和最终复测结论见 [客户问题复测](03-customer-retest.md)，以后续记录为准。
