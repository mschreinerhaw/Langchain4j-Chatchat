# 空数据识别、自主恢复与受限报告专项 P0

本项独立于报告生成验收。先行[空数据审计及根因矩阵](../audits/runtime-cline-empty-data-p0-20261107.md)提交 `93f2a0fc7`、`deb395b67`；初始UNKNOWN/PARTIAL保留，不以代码或离线通过冒充全部端到端完成。最终矩阵在真实任务与逐档复读完成后更新。

## 实现与边界

| 修改 | 代码/方法 | 独立提交 | 验证 |
|---|---|---|---|
| 统一观测、查询及错误证据 | `CodingDataStatus.describe`、`CodingDataObservation.describe`、`CodingKnowledgeService.call` | `7ae189d1f` | 零行、日期/过滤矛盾、字段缺失、HTTP错误、JDBC持久化、任务继续和撤权回归 |
| 候选空源识别 | `FinancialDataStore.validDatasetCoverage`、`ResourceExplorationReadController.readObservations` | `7ae189d1f` | 真实隔离H2/存储/MCP控制器路径，daily/weekly各最多1行；不使用单个空数组证明整源为空 |
| 部分数据交付 | `CodingDataSupplyService.request/settle` | `f587129d3` | 缺字段仍交付已验证行，明确FIELD_MISSING；相同绑定重读不可变回执，不重复查询或收费 |
| 无额外工具时受限报告 | `AnalysisCoverageCoordinator.analyze` | `e54ff7319` | 保留既有能力缺口答案；其他缺数状态交给原生Cline解释，不授予新查询能力 |

状态与任务生命周期分离。AVAILABLE不等于分析完成，EMPTY_RESULT不等于SOURCE_EMPTY。原有EMPTY/AVAILABLE等传输字段保留兼容性，新增 `dataObservation` 提供明确语义。

| 状态 | 充分依据/含义 |
|---|---|
| EMPTY_RESULT | 成功响应中确有0条记录；默认cause=UNKNOWN，不猜测日期或过滤原因 |
| SOURCE_EMPTY | 当前授权数据集的候选空目录，经既有存储两层有界存在性读取均为空；仅指符合访问/合规条件的保留本地数据，不证明上游、所有平台数据或交易所全覆盖为空 |
| DATE_MISMATCH | 返回记录与指定日期矛盾，或零行且请求起始日晚于本次目录所报最新日期；明确区分目录观测与交易日历证据 |
| FILTER_MISMATCH | 返回记录与所指定的精确过滤值相矛盾；单次带过滤条件的零行仍保留未知原因。后续对照由Cline解释，不改写旧回执 |
| FIELD_MISSING | 已有记录缺少请求的必要字段，包含null/空白；记录缺失字段及受影响行数，不补造值 |
| PARTIAL_DATA | 来源明确不完整或窗口有截断风险；有效部分可交付，不能作全样本推断 |
| ACCESS_DENIED | 明确的401/403或Runtime当前资源授权拒绝；不转换成0行、不回送来源内容 |
| SOURCE_UNAVAILABLE | 明确的5xx/服务不可用状态；实际行数为未知，不包装成成功空数组 |
| UNKNOWN | 查询/投影/参数或原因尚无充分证据；保留原错误，不能据此声称源空 |

`dataObservation`交付原查询、来源/日期、实际条数或null、开始/完成时间、耗时、缺字段、当前任务授予的工具、Search可用性、最近16条尝试路径和证据引用。工具名称来自冻结任务授权，每次使用仍重新鉴权；不是扩大权限的指令。原始错误在同任务受控回执中保留，常见凭据键作脱敏。没有把动态观测元数据加入原始行情来源Hash；既有更新/撤权后的披露保护继续执行。

Java不编写恢复搜索循环。参数、日期、市场/别名、目录、其他授权数据、完整日文件及Search均仍由Cline通过现有SDK工具自主选择。没有新建Editor、搜索Agent或业务编排器；保留16,000字符补丁上限、原预算和资源限制。近期路径摘要不是全部历史的替代品，完整调用及原始参数仍在受控回执/SDK检查点中。

## 分层测试证据

[定向与相关回归](evidence/runtime-empty-p0-20261107/offline-regression-proof.json)：9组145项，143通过、2个既有可选环境测试跳过。新空数据分类、真实来源回放、HTTP传输和隔离存储路径未跳过。最初的编译/回归失败日志与最终通过日志均保留并记录完整Hash。不得把145项算作145个真实业务样本。

[真实金融来源采集](evidence/runtime-empty-p0-20261107/real-source-capture-proof.json)：source38，华是科技301218，2026-11-02。未改行情或过滤引擎：SZSE:301218精确读取0行；全日读取2943行且包含目标；标准代码读取1行；2099-01-01读取0行且目录最新日期较早。完整原始响应留在私有目录，fixture SHA `5b7bc678b8cf398909d180353d73fa81a368450105ee34978649fdc970f7857b`；Java消费者按此Hash回放并验证[状态输出](evidence/runtime-empty-p0-20261107/real-source-classification-proof.json)，不是人为返回空数组。

[隔离空源证明](evidence/runtime-empty-p0-20261107/isolated-source-census-proof.json)来自实际H2 SQL、FinancialDataStore及MCP控制器，只有该内存测试库被清空。保留源范围、双层检查和每次1行上限；生产数据未删除。该路径能验证SOURCE_EMPTY，但不代表生产环境所有授权来源均为空。

[503](evidence/runtime-empty-p0-20261107/controlled-http-503-proof.json)及[403](evidence/runtime-empty-p0-20261107/controlled-http-403-proof.json)通过实际本机HTTP传输和原MCP客户端验证，保留状态，行数为null，无web工具新增。属于受控故障注入；不冒充生产服务宕机或生产普通用户RBAC在线验证。

## 真实模型场景与验收口径

E02单独使用原资源型分析入口及已有金融Agent，让Cline真正选择工具和完整行情文件。E01同时检查其首个零行子步骤，二者不会算成两次独立模型重复试验。

E03–E08另有独立节点、输入Hash、任务和执行记录，经现有工作区入口进行真实模型分析、原生编辑和报告归档。输入分别为实际捕获的金融响应或明确标注的隔离数据库/HTTP故障记录；文件型任务只授予这些文件和原有本地工具，不向其开放生产行情或联网工具。这验证模型如何解释证据及形成受限报告，不能替代“生产MCP实际拒绝后Cline恢复”或生产RBAC的完整验证。

每项都检查：状态和原错误、授权不扩大、有界执行、支持的分析子集、实际报告内容、引用、同operation停止证明及归档文件逐字节Hash。任务COMPLETED、Python退出0或资产AVAILABLE均不足以单独判PASS。最终分类及未覆盖范围见下方后续补充；未取得结果的行保持UNKNOWN/PARTIAL。

| 场景 | 状态/数据层证据 | 模型及归档层（执行中） |
|---|---|---|
| E01 零行 | 真实精确查询EMPTY_RESULT，原因不伪推为源空 | 独立核对E02的零行步骤及后续完整链 |
| E02 精确未中、日文件存在 | 真实0行与2943行日文件、目标存在；既有历史闭环保留 | 新版本原生资源任务执行中 |
| E03 授权本地源空 | 真实隔离存储双层检查及SOURCE_EMPTY | 文件型受限报告任务执行中，生产全空未人为制造 |
| E04 日期不一致 | 真实未来日期查询、目录上限及DATE_MISMATCH | 文件型受限报告任务执行中 |
| E05 缺字段 | 真实1行行情缺official_listing_date；部分输入交付回归 | 文件型部分分析任务执行中，必须保留可支持的价格分析 |
| E06 超时/不可用 | 实际HTTP503，SOURCE_UNAVAILABLE且条数未知 | 文件型解释/报告执行中；生产故障恢复未注入 |
| E07 无权访问 | Runtime撤权/JDBC回执及实际HTTP403传输 | 文件型拒绝解释/报告执行中；生产用户RBAC在线覆盖未完成 |
| E08 授权材料全空且Search不可用 | 隔离空源及既有Plus联网关闭策略 | 独立文件型报告执行中，须核对实际工具和Search状态 |
