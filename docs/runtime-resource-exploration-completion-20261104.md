# 资讯、行情与 Cline 自主探索收尾

## 最终部署与验收结果

运行代码已合并并推送 `origin/main`，最终部署提交为 `47e7a72b2415b5f935b19323f87ec25e2fb14cda`。
访问地址：<http://192.168.195.221:18100>；发布目录：`/opt/chatchat-releases/resource-exploration-completion-20261104-stable`。
后续提交仅归档验收文档，不改变该运行代码。

| 阶段 | 范围 | 实际结果 |
| --- | --- | --- |
| 首次主分支 `f9825e01b` | 原始 50 问 | {'FAILED': 2, 'PARTIAL': 6, 'SUCCEEDED': 42}；262 个报告引用解析完成 |
| 同版失败复测 | V01、LA01 | 1 成功、1 部分完成；首次失败保留 |
| `d52fb8798` 复测 | 选定 6 问 | 仅提交 3 条：1 成功、2 超时取消；其余 3 条未提交 |
| 最终 `47e7a72b2` | V01、LN01、LN02、LN03、LA01、LA02 | {'PARTIAL': 2, 'SUCCEEDED': 4}；17 个引用解析完成 |

最终补丁执行针对性回归，**没有在该版本上重跑完整 50 问**。`PARTIAL` 保留数据/证据缺口，不代表全部业务问题已得到证明。
原始 50 问 SHA-256 保持 `8cbf1a76f5d100d0f5df1484e2f1dcd8b88580a3da9e8d3f375a2cc12938bc99`；真实模型为 `qwen3.7-plus`，未预置来源 ID 或答案，未替换实际数据。
完整记录：[50 问](runtime-resource-exploration-completion-20261104-evidence/main-acceptance-summary.json)、[首次失败复测](runtime-resource-exploration-completion-20261104-evidence/main-initial-retry-summary.json)、
[中断复测](runtime-resource-exploration-completion-20261104-evidence/interrupted-d52-verification-summary.json)、[最终针对性回归](runtime-resource-exploration-completion-20261104-evidence/stable-acceptance-summary.json)。

最终 Worker 178 项测试、Java 10 项测试、前端 20 项测试通过；服务器现有依赖镜像也离线通过同样 178 项 Worker 测试。
admin 实际登录及五个主要页面检查通过，工具目录健康且注册 39 个工具，五个容器运行，最终 Worker 活动会话为 0。
[13 项撤权检查](runtime-resource-exploration-completion-20261104-evidence/stable-resource-security-summary.json)通过，Agent 设置已恢复、临时 DENY 已删除。
队列非空状态未被独立观测，不将 200/null 或 403 冒充非空结果拦截认证。

最终修复还包括：草稿复核保留实际 `input_read` 上下文引用，空引用在 Worker 给出明确修订提示；
采集 HTTP 入口只有一个在途写请求，忙时返回 503 要求调用端重试，不确认未接收的数据。
此前实际发生 199 个采集请求等待写锁、占满 200 个 HTTP 线程并阻断许可/读取接口，见
[故障记录](runtime-resource-exploration-completion-20261104-evidence/http-ingestion-starvation.json)。暂停采集并恢复服务后部署背压修复；
[现场观察](runtime-resource-exploration-completion-20261104-evidence/stable-admission-observation.json)与真实业务复测记录恢复情况。未增加运行预算或放宽来源授权。

[最终部署](runtime-resource-exploration-completion-20261104-evidence/stable-deployment.json)、[健康](runtime-resource-exploration-completion-20261104-evidence/stable-health.json)、[浏览器](runtime-resource-exploration-completion-20261104-evidence/stable-browser-verification.json)、
[网络边界](runtime-resource-exploration-completion-20261104-evidence/stable-network-boundary.log)、[构建](runtime-resource-exploration-completion-20261104-evidence/stable-worker-build.json)、[Java 产物证明](runtime-resource-exploration-completion-20261104-evidence/stable-mcp-recovery-artifact.json)
均已归档。MCP JAR 仅替换采集控制器类，其余 1505 项一致；API JAR 字节不变，无数据库迁移，保留配置、原容器及金融文件备份。
业务验收使用早上已有明确资源授权的专用账户；本轮未扩大 admin 的持久数据权限。

**仍未达到的验收指标：**首轮父任务 P95 为 389.479 秒，直接问题 P95 为 294.102 秒，
10 秒目标未达到；不认证 95% 语义引用正确率。人工抽查仍记录了未声明价格单位的惯例解释和跨期措辞问题。
接口成功、引用可解析和语义正确是不同指标。汇总见[完成记录](runtime-resource-exploration-completion-20261104-evidence/completion-summary.json)。

## 历史过程记录

以下记录保留首次发布与候选阶段的状态和限制，不作为最终运行版本的通过声明。


## 首次主分支发布（历史）

该阶段 50 问已经完成，结果见上表。

实现合并提交：`f9825e01ba42c66d446b75f1d41a4421b77d8cdb`，已推送 `origin/main`。
主分支版本部署目录：`/opt/chatchat-releases/resource-exploration-completion-20261104-main`。
访问地址：<http://192.168.195.221:18100>。本轮不在仓库保存登录口令、令牌、原文或金融数据库。

[部署记录](runtime-resource-exploration-completion-20261104-evidence/main-deployment.json)确认
39 个工具的 API 目录健康，未执行数据库迁移；会话内容、消息索引数量与其他服务容器保持一致。
[服务器离线构建](runtime-resource-exploration-completion-20261104-evidence/main-worker-build.json)中的
177 项 Worker 测试通过。[浏览器验收](runtime-resource-exploration-completion-20261104-evidence/main-browser-verification.json)
使用 admin 实际登录，5 个页面无脚本错误或 API 失败；静态资产逐项匹配复用的 API JAR。
[网络边界](runtime-resource-exploration-completion-20261104-evidence/main-network-boundary.log)核对
签名回调、路径隔离及数据库、搜索服务、外网出口禁止访问。

## 候选回归与时限说明

[R4 候选记录](runtime-resource-exploration-completion-20261104-evidence/candidate-summary.json)保留首次超时，
没有用重试覆盖失败。`F01` 模型反复提交同一抄错的引用 ID，420 秒验收脚本请求取消；
其实际 Runtime 合同 `deadlineAt` 为开始后 1200 秒，`maxCpuMillis=300000` 是 CPU 预算，
不能把它误写成 300 秒任务截止时间。修复引用 ID 复核提示后停止候选回归，两个仍在执行的测试任务
按升级需要取消并确认终态。候选记录属于不完整回归，不能计作完整 50 条通过。

首次主分支回归使用独立目录和原始 50 问，不复用候选答案；验收观察时限显式设为 1200 秒，
不修改 Runtime 合同预算。最多同时执行 2 个模型任务，来源引用解析单独占用验收线程。
来源解析时间和父任务完成时间分开记录，不把等待引用解析的耗时记入父任务终态耗时。


本轮从主分支 `37e1df1fe` 继续早上的资源探索工作。新闻、金融数据适配器、Agent
资源配置、引用解析与实时权限检查此前已经进入主分支；本轮补齐 Cline 报告收尾、
真实模型验收和部署证据。底层数据读取仍使用既有 News Runtime、FinancialDataStore
和 OpenSearch 目录，没有新增采集器、存储服务或模型审查链。

## 修复

资源探索原先可能在已有工具结果时直接输出普通文本，或者耗尽模型轮次后没有提交
报告。现在资源任务必须通过既有 `report_submit` 提交并验证结果；最后三个模型轮次
仅保留报告提交，用于草稿复核、收尾和一次校验修复。预算、合同截止时间、签名回调、引用校验和
撤权检查保持生效，没有通过延长预算或放宽权限取得成功。

真实报告复核还发现了两类问题：将选定数据集的查询结果泛化成整个平台的能力，
以及在发布时间为空时，后文又用采集日期推断发布时间。已在同一个 Cline 循环中
补充提交前核对要求：

- 否定结论限定到实际检索范围；区分未检索、空结果与平台不存在。
- 发布时间未知时始终保持未知；区分目录采集心跳、整个数据集的最新观测日期与
  当前证券/时间范围的实际观测日期。
- 缺少币种、单位或复权口径时说明未知，不从交易所名称或成交额单位推断价格单位。
- 证券识别同时考虑交易所、来源和品类；摘要不等于已阅读全文；比较多个来源时
  分别引用相应依据。

首次提交保留为私有草稿并返回复核要求，不会发布报告；同一个 Cline 会话在后续
模型回合核对、修订后再次提交，才进入既有 Runtime 校验。连续在同一个模型回合
调用两次提交不能跳过复核。复核状态与模型序号进入既有 SDK 检查点，恢复会话
不会重新发布草稿。目录工具同时说明空查询可列出已授权目录，非空查询只是过滤。

这些是 Cline 自身的证据复核要求。Runtime 继续执行确定性的权限、来源版本与报告
契约校验，不新增 Java 语义审查模型，也不将接口通过率当作结论正确率。

真实并发验收另发现金融只读副本偶发 H2 `DATABASE_IS_CLOSED`，导致来源复核返回
500 并被 API 拦截为 403。只读通道现在仅针对该驱动错误恢复当前只读连接池并
重试一次；并发请求通过原有读写锁确认池身份，只重建一次。恢复读取同一份已发布
快照，不从写库取新数据，不推进快照代际。查询超时、无效 SQL 等错误不重试。

## 验证与证据

本地 Worker 全量 177 项测试通过；生产 Node 24.21.0 / Cline 0.0.93 依赖镜像中的
对应验证见发布证据。新增回归通过真实 SDK 循环验证普通文本不能提前结束任务，
草稿必须在下一模型回合复核、同回合双提交不能绕过复核，以及收尾阶段的额外
资源调用不会到达 Java。引用弹窗和 Agent 资源配置的前端回归
20 项通过。
没有授权任何资源工具的普通文本任务保留原来的一次提交路径，不增加资源复核回合。

金融读写隔离及副本恢复 7 项 Java 测试通过。恢复测试在发布快照后修改写库，
确认恢复后仍读到旧快照；还覆盖两个并发故障只重建一次、命名参数的行数上限，
以及查询取消不触发重建。

完整混合问题集、真实模型调用、引用解析与耗时见配套验收证据。业务问题保留原有
问题文本，不预置文档 ID、证券观测或答案，不用 Mock 代替服务结果。无法回答、
源数据缺失和范围不完整应明确反馈，而不是编造价格、交易日或灾备测试结论。

admin 用于实际登录、页面检查、原有权限边界和跨用户拒绝验证；业务问题使用
已有显式授权文档、新闻和金融资源的专用验收账号。admin 原本没有新闻/行情资源
授权，本轮不会将超级管理员身份当作隐式数据授权。撤权试验只操作专用验收 Agent
及临时 DENY 规则，并在 `finally` 中恢复设置、删除临时规则。
结果队列即使停止主动轮询仍返回空值，因此队列检查只认证空结果不披露；不将其
冒充为已验证非空结果的撤权拦截。

来源弹窗验证使用实际已发布报告与实际来源引用，在浏览器中装入临时消息以覆盖
交互；原文读取仍经过线上鉴权和版本校验。该检查验证弹窗、表格、时间字段、
禁止缓存及无效引用清空旧内容，不代表另外完成了整个聊天提交交互的自动化。

## 发布方式

`scripts/prepare-resource-completion.py` 对上传包和所有 Worker 模块校验哈希，
只允许 `runner.js` 和 `knowledge-tools.js` 发生运行代码变化；使用当前生产依赖离线执行完整 Worker 测试。
API JAR 从当前容器逐字节复用并验证哈希，没有数据库迁移。
金融恢复部署只替换原 MCP JAR 中的 `SnapshotFinancialReadOperations.class`，
逐项验证所有其他 JAR 条目保持一致；新闻服务保留原镜像，在 MCP 替换期间暂停
生产者，并在停写后备份金融 H2 文件。

部署沿用 `scripts/deploy-integrated-runtime.py`：部署前核对活动任务、Worker 会话
和 Python 沙箱；保留数据库、配置和原容器，按依赖恢复服务、重绑代理、更新网络
边界并检查 API 侧工具目录。原 API JAR SHA-256 为
`97a2bd14e23f2b3e91b71223af00acccf71dd50036d7a03d1a40670e9a825f7c`。

## 结果解释边界

行情是已采集的观测，不是实时报价；不同证券、数据集与时间段的覆盖可能不同，
不能据某次空结果宣称平台没有对应能力。未知发布时间、单位和复权口径保持未知。
报告提交成功、引用可解析、引用支持结论，是三个不同的检查，不能相互替代。

本轮记录真实耗时，不将多轮探索宣称为 10 秒内完成，也不自动认证 95% 语义引用
正确率。具体执行结果、性能与人工复核范围以配套证据为准。

补充回归发现模型可能反复携带同一个抄错的 Evidence ID。草稿复核现在同时返回本次工具实际交付的完整 ID 清单及草稿中未匹配的 ID，要求复制原值或删除无依据结论；这只是修订提示，不能替代 Java 的引用与实时权限校验。行情工具说明明确精确匹配只接受标量值，不能传数组或 IN 条件。


## 已观察到的运行限制

主分支 `V01` 首次运行在来源复核时遇到 MCP 500，Cline 未发布未经复核的结果，任务失败。
同一时段浏览器引用请求被 API 拒绝为 403；随后记录到只读池自动恢复 generation=11。
内部诊断重新读取同两批来源、以及同份报告的浏览器复测恢复正常。
[运行观察](runtime-resource-exploration-completion-20261104-evidence/main-source-recovery-observation.json)
保留首次失败和后续读取结果；原始 MCP 500 内层异常码未被记录，不能断言它只有单一原因。
[浏览器复测](runtime-resource-exploration-completion-20261104-evidence/main-citations-browser-retry.json)
使用主分支实际发布的报告引用，验证了 9 行行情表、资讯原文、禁止缓存与无效引用清空。

[人工复核范围](runtime-resource-exploration-completion-20261104-evidence/manual-review.json)明确区分候选与主分支报告。
主分支 F01 仍将未声明的价格单位解释为指数点位，并存在跨期材料使用并行时段措辞的问题。
这些不能由“引用返回 200”自动判为语义正确；本轮不签署 95% 语义正确率认证。
