# 改造说明与回归边界

本专项复用 SQL Studio → SQL authoring → TemplateCapabilityService → ToolReleaseService → MCP contributor → Agent 的现有链路，没有新增一套模板发布系统。

## 实际修复

- 试执行返回限量真实预览：最多 10 行、128 列、32 KB；同时呈现实际返回行数、查询完整性和预览截断。预览截断与数据库结果截断分别标记，不能用预览替代完整数据。
- Studio 试执行先使用发布后的同一 JSON Schema 校验；只有缺省字段才应用默认值，错误的显式值不可静默回退为默认值。公共参数编译器保持原有兼容行为。
- SQL 渲染允许明确声明的 nullable 类型输出 SQL NULL。MySQL 含反斜杠或 NUL 的字符串使用 UTF-8 十六进制字面量转换，避免受 NO_BACKSLASH_ESCAPES 配置影响；普通引号仍正确转义。其它方言的反斜杠/NUL 输入明确拒绝，不声称已验证这些方言。
- Agent 输入快照及上下文参数解析保留显式 null，继续使用不可变副本，避免 Map.copyOf 抛空指针；不改变必填字段、证据恢复或身份授权规则。
- 长 Agent ID 与工具名组合超出审计 resource_id 长度时保存 SHA256 索引，完整引用保留在 detail，避免绑定成功后接口报 500。
- 已发布 SQL 元数据声明 RAW_RECORDS 和实际数据嵌套位置，保留既有 ToolOutput/sql_result 信封。实际 MCP requestId/traceId 继续传入 SQL sourceTaskId，便于沿工具、查询审计和运行任务定位。
- 对具备 Studio 真实成功试执行证据的 SQL 查询修正风险分类；保留 controlled 执行、当前授权、固定数据源绑定、Schema 校验及只读执行门禁。其它模板和未验证快照保持原高风险分类。
- 既有 capability 停用同步撤销 SQL 定义的 enabled 状态。只作用于该修订当前的 release，历史版本停用不能撤销新发布版本。锁顺序与发布/恢复一致，保留不可变 SQL 供重新试执行后恢复。
- 微软原生包及资源不编译、不改写；Java 仅交付元数据，Cline 按需读取。详见资产清单。

## 已完成的自动检查

`target/tpcds-build-r6.log` 的完整 API/MCP 构建成功，本次相关 Java 检查共 65 项通过：运行器 nullable 输入 1 项、上下文参数恢复 4 项、公共参数编译 12 项、技能上下文 7 项、SQL authoring 16 项、SQL 渲染 16 项、capability 服务 8 项、绑定审计 1 项。之后的 publisher 风险分类有独立检查与 MCP 构建记录。

前端发布面板 11 项通过；正式前端构建成功。Coding Agent 在本地 Node 22（启用 experimental-strip-types）及远程 Node 24 离线构建环境均 149 项通过。其范围包括原始正文哈希、Cline 原生 parser、资源读取、每 Agent 禁用和作用域隔离。测试 stub 不替代真实 Cline / 数据库 / Agent / Python 验收；真实记录另附。

## 扩展回归的既有失败

扩展运行 `InterpretationPlanRuntimeTest` 等 178 项时，174 项通过、4 项失败。将运行器源码恢复为基线 `33a22cc7cbb23f583d508c648f688d5680f1593c` 后，单独复跑以下 4 项仍全部失败；随后已恢复本次 nullable 修复并重新构建。没有为通过验收删除断言或修改这些用例。

1. rejectsTableScopedGlobalTemplateWhenDialectCannotBeInferred
2. rejectsTableScopedGlobalTemplateWhenTemplateDiscoveryDidNotReturnTableMetadataTemplate
3. hydratesTableMetadataSchemaFromSqlMetadataSearchResults
4. rejectsOracleInstanceTemplateWhenTemplateDiscoveryDidNotReturnTableMetadataTemplate

相关原始日志：`target/tpcds-build-r4.log`、`target/tpcds-baseline-existing-failures.log`。这些是既有元数据模板解析回归缺口；不能宣称全仓测试全部通过。本次 TPC-DS 使用已注册且实际查询验证过的表，未修改这些元数据解析行为。

## 明确的产品边界

参数 Schema 的类型、默认值、可空性在发布前显式审阅，不宣称模型自动识别了业务金额单位。试执行时限仍为 10 秒、最多 50 行；大范围全表聚合超时不会伪装成功。本次通过明确的商品主键范围降低扫描量，没有偷偷缩小业务范围或替换成手写生产 SQL。

现有问题分析器会对独立的多工作流目标请求澄清；包含“查询数据”和“解释工具契约”的组合测试曾进入该分支，均保留原失败/澄清记录。专项业务验收使用单一销售分析目标，由 Agent 自主选择数据服务和处理步骤，没有关闭该分析器或放宽执行策略。
