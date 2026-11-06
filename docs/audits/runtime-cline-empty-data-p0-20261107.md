# 空数据识别、恢复与交付专项 P0

审查基线 `06cd30b2f`。本文件先于本轮产品修改提交；空数据是独立验收项，不由报告生成或整体成功状态代替。各场景必须分别保留查询、来源、时间、返回状态、授权、恢复与交付证据。

## 修改前根因矩阵

| 编号 | 已确认事实 | 影响 | 最小整改 |
|---|---|---|---|
| EMPTY-01 | MCP行情零行已有NO_MATCH_IN_REQUESTED_SLICE和探索提示，SDK仍交还Cline；AnalysisCoverageCoordinator已有空首批后授权探索分支 | 未发现所有零行均提前终止；不能把既有能力重写成新Agent循环 | 保留原生工具选择和现有预算 |
| EMPTY-02 | CodingKnowledgeService成功响应无统一数据状态；回执仅有requestSha256，没有查询参数、返回行数和失败分类 | 查询零行、未知、字段缺失及错误不易审计 | 增加独立、可归档的观测状态，保留原查询和时间，不污染来源版本Hash |
| EMPTY-03 | CodingDataSupplyService在任一记录缺requiredFields或源complete=false时拒绝整批输入修订 | 可用的部分记录/字段无法交给Cline做受限分析 | 保留源证据和行字段缺口；完整且有可用行时按既有快照契约交付，明确PARTIAL/FIELD_MISSING，不补造值 |
| EMPTY-04 | DATA_SUPPLY_STATUS描述交付状态，与来源状态不是同一维度；sourceVerifiedEmpty只证明查询零行 | 容易误把EMPTY_RESULT当整源为空 | 保留原字段兼容性，增加独立数据状态；没有完整源覆盖证据不产生SOURCE_EMPTY |
| EMPTY-05 | callback通用异常包装为Invalid Coding callback binding，可能遮盖查询失败原因 | 工具难以区分权限、错误参数和源失败 | 在已绑定、已预留的读取边界记录原始失败，保持权限拒绝和服务异常，不返回空数组 |
| EMPTY-06 | 行情auto模式在无起始日期时默认只读daily_hot；单层零行与空目录日期都不足以证明整源为空 | SOURCE_EMPTY没有充分依据 | 仅对候选空目录做既有存储daily/weekly两次各最多1行的存在性检查；保留异常，不作恢复循环或全量扫描导出 |
| EMPTY-07 | Coding所有初始数据为空且没有额外探索工具时，AnalysisCoverageCoordinator除已有能力缺口答案外直接抛异常 | 未把状态交给Cline生成有证据的受限报告 | 保留已有模型缺口答案；其余在原授权、预算下交给原生Cline，状态记录不能当业务数据 |
| EMPTY-08（本轮新增回归） | 新E02零行读取已结束且回执存在，但dataObservation复制了descriptor.chunks中的Java记录；原始字段顺序Hash为91fecbdd…，解码后排序Map Hash为e8122313…；按存储原顺序复算仍精确等于原Hash | A2拒绝停止证明，读取操作挂起，后续探索无法准入；单纯Map模拟没有覆盖生产Chunk类型 | 新回执先规范化为JSON数据；旧回执只接受精确原编码Hash校验，不改写证据；原生恢复仅核对已停止读取，不重放查询、不改变预算 |
| EMPTY-09 | E04三次Python退出0，均因NO_DATA报告装入诊断指标/表格被拒绝；E05先混淆visualReport.status与analysisStatus，后将字段不可用标为计算FAILED；反馈未给精确字段路径和枚举 | 模型反复原生修复仍未交付受限/部分报告 | 保留原验证规则，补可操作字段级错误与缺输入UNRESOLVED语义；不把失败改为PASSED，不自动改写报告 |

## 状态与治理约束

至少支持EMPTY_RESULT、SOURCE_EMPTY、DATE_MISMATCH、FILTER_MISMATCH、FIELD_MISSING、PARTIAL_DATA、ACCESS_DENIED、SOURCE_UNAVAILABLE、UNKNOWN，并额外区分AVAILABLE。零行默认只说明本次查询无匹配；日期/过滤原因须有实际对照证据，不能凭参数猜测。来源全空须有完整范围证明，单日文件空不代表整个数据源为空。错误的行数必须是未知，而不是0。

恢复步骤是供Cline选择的授权路径，不由Java执行固定查询序列。保留工具/数据权限、原任务预算、日期和导出上限。原始参数只进入同任务受控证据，不进入公开日志或跨用户页面。异常详情仍按现有安全边界保护。

## 修改前专项矩阵

| 场景 | 初始判定 | 已有证据与缺口 |
|---|---|---|
| E01 查询零行 | PARTIAL | B2真实零行存在；缺统一状态和本轮完整断言 |
| E02 精确未中、全日存在 | PARTIAL | 真实301218/2026-11-02/2943行探索计算曾通过；最终旧报告因目录刷新被拒绝下载，不能标完整PASS |
| E03 本地行情全空 | UNKNOWN | 尚无本轮真实完整范围证明 |
| E04 日期不一致 | UNKNOWN | 不得仅用latest日期推断整个历史范围 |
| E05 缺必要字段 | PARTIAL | 代码已确认整批拒绝；尚未验证部分分析交付 |
| E06 超时/不可用 | PARTIAL | 异常不会合法变成零行，但回执和模型侧分类不完整 |
| E07 无权访问 | PARTIAL | 既有撤权测试；缺本轮状态/归档专项证明 |
| E08 全部授权源无数据且Search不可用 | UNKNOWN | 既有D3仅证明联网关闭后使用有效本地数据，不满足全空条件 |

专项验收不允许仅凭模拟空数组标PASS。离线确定性回归、真实存储查询、故障注入、真实模型完整交付分别标识；缺少哪一层就保留PARTIAL/UNKNOWN。历史失败和严格来源校验不改写。
