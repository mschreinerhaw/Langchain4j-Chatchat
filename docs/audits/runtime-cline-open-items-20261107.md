# 未完成问题及后续整改清单

已确认 STOP-RACE：新三轮第二轮Flash在截止20:47:10Z发起停止；Worker于20:47:11Z终止，durable任务仍RUNNING/reconciliationRequired。原执行只在取消后立即resume一次，该次早于停止回执；之后页面只读轮询不触发恢复，未自动收尾。现有通用Agent监控resume不覆盖合成asset-analysis run（实际404，证据保留）。可通过同一工作区节点的原生恢复入口使用原requestId/快照/预算完成对账，过期且已有stopReason时Adapter禁止新dispatch。拟只在原执行的停止阶段增加有界对账等待，不把页面读取改成执行入口，不重置预算、不新增Agent循环。

补充已确认 UI-EVENTS：真实文件分析 B2 的 durable journal 有261个事件，但 `primarySnapshot` 没有投影任何 `analysisEvents`，页面只能显示终态事实，无法展示实际中间阶段。已有八阶段前端逻辑和空事件面板修复不能使此项通过。拟只读投影当前任务最后256条事件的安全字段；Python停止必须另查同operation停止证明，不能将worker观察当作停止事实。

本清单与[完成度矩阵](runtime-cline-completion-review-20261107.md)一起更新。核心场景未通过前不宣布正式验收。

## 本轮修复后的状态

以下覆盖下方历史执行中状态，保留原始问题记录供追溯：

| 编号 | 当前判定 | 已完成修复/证据 | 尚需满足的条件 |
|---|---|---|---|
| B-E2E | PASS | B2真实零行→名称及标准代码→2943行全日文件→Python→COMPLETE文本报告→Hash与归档API | 本样本已闭环，不代表所有市场/别名已覆盖 |
| DATA-08 | PASS（交付结构） | `89f11de98`、`832fdcf56`，78项契约回归；首轮两任务实际挂载的当前文件ID、SHA、39列映射已独立验证 | 不能把“正确映射已交付”说成“生成的解析代码正确使用了映射” |
| SEARCH-STATUS / D | PASS（受限合约） | `6a0f026ea`、`c2b09b8e7`；D3报告明确MODEL_INTERNET_DISABLED并继续本地分析归档 | 真实供应商故障的在线降级仍UNKNOWN；关闭配置不是服务宕机 |
| CITATION-REPAIR | PARTIAL | `9ec3a9d76`补重新鉴权的有界URL→证据ID映射，离线严格引用/撤权/元数据边界通过 | C3在代码语法修复阶段失败，未验证此反馈带来模型闭环，不宣称真实修复成功 |
| SEARCH-C | FAIL（整体链） | C3真实搜索两次、读取原文三次；首个源码提交SHA与执行SHA一致，独立编译确认源码已有SyntaxError | 原预算内完成代码修复、计算和有来源的报告；不得把工具调用成功当完整交付 |
| PATCH-F | PASS（离线） | 历史28866字符样本完整SDK回放，分段、版本、Hash、原子拒绝和恢复通过 | 不等于模型每次都会按提示生成正确分段 |
| UI-EVENTS / UI-LIFECYCLE | 待最终联机验收 | 当前任务安全事件投影、同operation停止证明、规范化文件路径和源码行号；历史终态仅补进度不替换报告；前端149项、Analysis60项、Workspace24项通过 | 等六个模型任务终止后发布，再验证原四图实际渲染及事件补齐 |
| REPORT-QUALITY / A / E | PARTIAL | B、D与旧四图归档技术证据保留；旧R5报告重新确认PARTIAL | 以新六任务独立校验完整业务报告及真实图表，不能以COMPLETED标签代替质量 |
| PERF-3 | 进行中 | 三轮成对、六个独立节点/任务；上一对全部终态才进入下一对，不改共享设置和预算 | 全部结束后提交结果及可比性证明；失败不视为提速 |
| PERF-PHASE | UNKNOWN（纯阶段） | 已记录文件、工具、Python、提议模型回调、首次源码/零退出/可归档报告延迟 | 非流式API未分别给出纯规划/代码/报告生成或思考时间；无进展次数未知不填0 |
| SOURCE-COVERAGE | UNKNOWN（未覆盖路径） | 本轮真实链覆盖source38/MCP行情；代码与既有测试覆盖其他发现适配器 | 本轮没有逐个真实验证所有PostgreSQL/OpenSearch数据源、跨市场重名及全部别名；不得泛化为全数据环境通过 |

| 编号 | 状态 | 问题 | 本轮动作与通过条件 |
|---|---|---|---|
| B-E2E | PARTIAL | 原证据未证明真实精确未命中后完成全日分析 | B2已出现真实count=0→自主名称解析→同日全文件2943行，须继续Python、报告和独立校验 |
| SEARCH-C | PARTIAL | 旧自主搜索最终预算耗尽 | 新C用既有联网模型，取得官方原文并完成有范围限制的报告 |
| SEARCH-D | UNKNOWN | 真实不可用时最终本地分析缺证据 | 新D保持原关闭联网模型，保留不可用原因并完成本地报告；授权关闭不冒充供应商故障 |
| REPORT-QUALITY | PARTIAL | 单位未知却写元、百分位措辞不准确 | 原件保留，新报告人工核对数值/单位/范围；不能通过验证器改写生成报告 |
| PERF-3 | PARTIAL | 旧版本各两轮，不足三轮且稳定交付失败 | 新最终Worker六个独立任务；同快照、权限、预算证明及UNKNOWN完整记录 |
| PERF-PHASE | UNKNOWN | 非流式回执不能拆纯规划/代码/报告生成 | 只报告可观测阶段和延迟；没有证据不填0，不虚构提速 |
| UI-LIFECYCLE | PARTIAL | 四事实状态外缺当前八阶段展示 | 最小修复fb56f38cc，146测试和构建通过，待空闲发布及浏览器验证 |
| HARNESS-UTF8 | PASS（已修正） | Windows管道中文变问号导致首个B目标失真 | 保留无效批；UTF-8文件传输、SHA和实际objective核对后才接受结果 |
| DATA-08 | FAIL（真实D已定位） | 上传的原生Parquet与外层传输表都使用c0等物理名；执行布局的logicalColumns未限制为外层parts，原文件包的manifest未与原生Parquet按Hash建立显式schema关联 | D任务`analysis-coding-cd7e3bc6af127f95f46d22cc559d53c18bcc7779e61ec5846baecd3f598e6eec`读到正确原生文件并通过Hash，却用外层manifest.columns重命名导致trade_date KeyError；后续原生编辑参数错误耗尽12轮。须补原生文件schema绑定及实际重叠列名离线回归，再测D；不能归因于行情不存在或Search故障 |
| SEARCH-STATUS | PARTIAL（D2实际报告确认） | Search关闭/未授权/未配置时snapshot统一返回空Map，工具被安全移除，但原因没有交给Cline；D2虽已归档本地分析，却只说明公告缺失，未说明Search不可用原因 | 需交付不授予权限的结构化可用性状态，区分配置/授权与实际服务探测；不得为探测把未授权web工具放回会话，也不得由Java执行搜索循环 |
| CITATION-REPAIR | PARTIAL（C2真实失败） | C2报告已产生，但没有任何知识引用，修复反馈只给出不透明ID列表，最后仍反复artifact_read直到预算耗尽 | 补充已披露且重新鉴权来源的有界元数据映射（URL、标题、时间→精确evidence_id），不重新搜索、不自动选择业务结论；保留严格引用拒绝。模型未正确保留/使用来源的原因仍不能断言为SDK上下文缺陷 |

不新增Editor、搜索Agent、上下文压缩器或业务编排引擎。不改权限/共享模型/预算，不删历史失败。测试批处理只提交现有原生入口，不决定业务工具和计算方法。
