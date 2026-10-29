# SQL Studio：Cline 自主元数据探索

分支：`fix/studio-isolation-metadata-20261029`。本次继续完成 SQL/Python Studio 专项整改，并补充 Cline 自主探索元数据的 P0 能力。

## 接口复用与缺口

| 能力 | 复用入口 | 边界或缺口 |
| --- | --- | --- |
| 数据源、数据库、Schema | `sql_schema_context_query` 的 LIST_ASSETS、LIST_NAMESPACES | 绑定当前工程、租户、用户及所选数据源 |
| 表、字段、类型、注释 | 同一入口的 SEARCH、DESCRIBE | 查询现有元数据索引，不读取业务数据行 |
| 业务术语、指标 | `enterprise_metadata_search` | 定义缺失时返回真实查询结果，禁止模型自行补造口径 |
| 表关联 | DESCRIBE 返回的 JDBC imported keys | 已知外键可以作为证据；完整业务血缘尚未采集，LINEAGE 明确返回 NOT_COLLECTED |
| SQL 模板 | `data_query_query` | 只返回能证明属于当前数据源/数据库的模板；旧接口不能约束 Schema 时返回能力缺口 |
| SQL 校验 | 现有 SQLGlot 工具链与字段引用校验 | 语法、对象、字段和发现状态分开报告；不执行 SQL |

没有新增独立元数据服务。新增代码主要是现有 Coding Agent 的工具适配、任务证据缓存和校验状态处理。

## 调用链路

1. SQL 工程保存当前数据源绑定，Runtime 生成绑定到工程、用户、租户的 discoveryId。
2. Runtime 与 Node worker 协商 `runtime-coding:sql-metadata-tools:v1`，发布 `workspace_read`、`workspace_metadata`、`workspace_validate`、`workspace_propose`。
3. Cline 自主选择 LIST_BINDINGS、LIST_NAMESPACES、SEARCH、DESCRIBE、BUSINESS_SEARCH、TEMPLATE_SEARCH、LINEAGE。Java 不预设表清单或查询顺序。
4. Node 经现有签名回调进入 Runtime，Runtime 校验任务身份、绑定、预算，再经现有 Kernel/MCP 网关查询。
5. 结构化结果写入任务发现证据和工具回执。重复查询可复用，但复用前重新验证绑定和快照状态。
6. Cline 可在任意轮调用校验。引用尚未发现的表返回 METADATA_REQUIRED，sqlValidity 为 UNDETERMINED；模型继续探索后重试。元数据服务故障独立报告，不能当作 SQL 非法。
7. 最终提案再次校验，并关联此前的元数据和校验回执；任务诊断给出调用次数、校验次数、修复次数及最终校验状态。

用户界面显示“正在查找业务表”“正在确认字段关联”“正在校验 SQL”等业务进度。底层参数和结果保存在后端证据记录中。

## 权限修复

普通用户只有工作区进入权限、没有业务执行授权时，也曾复现元数据 403。根因有两层：API 的 MCP 工具入口以及 MCP 服务本身都要求业务工具 ALLOW 授权。

修复在现有中央权限服务内区分元数据与执行。已登录、启用、同租户用户默认可以读取明确声明为只读元数据的四个现有目录工具；本地 MCP 工具代理还检查发布者的 METADATA_READ 边界声明。元数据网关同时检查 Runtime 工具名和中央登记的别名，使 gRPC 与本地传输都执行中央资源拒绝策略。已有显式 DENY、禁用工具、身份及租户检查继续生效。SQL 执行器仍使用原有业务工具授权；没有给验收用户添加任何执行或数据资源授权。

真实反向测试曾确认旧 gRPC 路径在添加通用资源 DENY 后仍返回 200；最终版本已复测为默认读取 200、添加临时 DENY 后 403、删除该 DENY 后恢复 200。临时规则已删除。见 [真实权限边界测试](cline-sql-metadata-20261029-evidence/catalog-authorization.json)。业务术语查询还修复了重复传入相同 query/queryTerms 导致旧服务再次拆词、放大批次的问题，复用原有目录检索实现。

部署内部同步账号仍不作为业务身份导出至 MCP，验收使用临时普通用户，避免绕过现有身份边界。

## 预算与可追踪性

- 每任务最多 24 次模型调用、64 次工具调用；单次元数据调用最多 15 秒，且不能超过任务截止时间。
- 自主探索任务默认 900 秒，可用 `chatchat.coding.workspace.sql-metadata-timeout-seconds` 在 1–3600 秒内配置；普通 SQL/Python 文件任务继续使用原有超时。真实首轮回归触发了旧 300 秒期限，故将探索任务的时间预算单独配置，保留该失败样本。
- 自主探索累计模型 token 上限默认 1048576，可用 `chatchat.coding.workspace.sql-metadata-max-model-tokens` 在 32768–8388608 内配置。普通文件任务保留原预算；模型侧列结果去除重复的所属表/库/Schema、数据源 ID 和空值，后端审计仍保存完整回执。
- 单次结构化结果的 JSON 最多 65536 字符；任务缓存最多 64 张表、32 个查询、750000 字符状态。
- SQL profile 不提供 shell、网络、Python 或业务 SQL 执行能力。
- 元数据操作使用现有预算预留、执行意图、结算和 Evidence Store 回执；最终提案保存父回执关系。
- 快照变更会使旧发现证据失效；权限拒绝、服务故障、对象未发现和 SQL 字段错误分别记录。
- 自主元数据任务的 SDK 检查点上限为 750000 字符、192 条消息，其他任务仍为 262144 字符、64 条消息；保留完整调用/结果配对和持久化恢复校验。校验工具不重复回显整份 SQL；只有具备完整结果与验证回执的 `workspace_propose` 能完成任务。
- SEARCH 的关键词数组会转换到现有物理检索接口并参与缓存键；业务术语目录按现有批次预算每次接收一个聚焦概念，超宽请求返回可继续修复的 QUERY_BUDGET，模型自主决定后续概念及顺序。

## 验证记录

相关 Java 测试共 180 项通过，Node 测试 127 项通过，前端测试 162 项通过；针对关键词、检查点和模型桥的 44 项测试，以及最终业务检索和跨传输权限的 29 项测试均通过。构建成功，部署 JAR 与本地完整 SHA-256 一致。原有页面回归再次通过：三种窗口宽度、每种三轮折叠，无残留布局盒；Python 提示栏已移除，页面错误为零。详见 [测试记录](cline-sql-metadata-20261029-evidence/tests.json) 和 [页面记录](cline-sql-metadata-20261029-evidence/browser.json)。

一次 Native SDK 测试曾超过旧的 20 秒测试等待上限；单独复查后全部 17 项通过，最终修改后的模型桥测试再次全部通过，未修改该等待断言。另一次并行构建时前端目录正被 Vite 重建，资源校验正确阻止了缺少 index.html 的发布；前端完成后重新构建通过。

部署环境：`192.168.195.221`，应用入口 `http://192.168.195.221:18100`。运行 API 镜像 `runtime-os-api:cline-final-4815886b7-20261029`，worker 同版本；MCP 镜像 `runtime-os-mcp:cline-sql-metadata-221efb954-20261029`。最终备份目录 `/opt/chatchat-releases/cline-final-4815886b7-20261029/backup`。发布校验确认既有会话内容、消息索引数量未变，其他容器未变；原容器和配置保留用于回滚。见 [完整构建标识](cline-sql-metadata-20261029-evidence/final-manifest.json) 和 [部署结果](cline-sql-metadata-20261029-evidence/final-deployment.json)。

真实回归脚本为 `scripts/verify-cline-customer-sql.py`，客户号作为字符串 `070200046604`，不预选业务表、不执行客户查询。使用仅有工作区进入权限的临时普通用户，没有新增任何业务执行 ALLOW。最初同账号可复现元数据 403，权限分离后目录读取和真实 Cline 元数据调用均成功。

保留了失败样本：[首轮超时](cline-sql-metadata-20261029-evidence/first-timeout.json) 完成 21 次元数据调用后触发旧 300 秒期限，模型远端用量缺少完整回执，不伪造已结算状态；[第二轮](cline-sql-metadata-20261029-evidence/second-adapter-failure.json) 发现关键词丢失、业务目录批次预算及检查点容量问题，其中 SQL 曾通过字段校验，但最终任务失败，因此未列为验收通过。上述问题已在最终发布版本中修复。[另一次技术通过的草案](cline-sql-metadata-20261029-evidence/third-semantic-review.json) 含标注为“推断”的盈亏公式，未作为最终业务交付；最终约束明确禁止缺少定义时生成该类公式，并复测两份最终 SQL 均无计算表达式。

用首轮真实工具回执测算，模型侧元数据体积从 166605 字节降至 119911 字节，减少 28.03%；后端完整回执保持不变。这是同一批结果的投影体积对比，不代表端到端模型耗时降低同样比例。详见 [性能测量](cline-sql-metadata-20261029-evidence/projection-performance.json)。

最终真实回归使用默认模型 `qwen3.7-plus`，选择原问题涉及的 Oracle 数据源，不指定任何业务表。结果如下；耗时包含客户端轮询，属于本环境单次测量，未测 SQL 执行性能。

| 案例 | 总耗时 | 探索/校验/提案调用 | 元数据调用 | 模型调用 | 元数据中位/P95/最大耗时 |
| --- | ---: | ---: | ---: | ---: | --- |
| 客户交易、资产、盈亏 | 348.64 秒 | 31 | 29 | 12 | 1146 / 1789 / 1800 ms |
| 聚焦盈亏定义 | 152.95 秒 | 18 | 17 | 10 | 1265 / 2222 / 2222 ms |

上述计数不含每任务另一次 `workspace_read` 上下文读取，完整回执包含该读取。第一案例元数据工具累计 30148 ms、模型累计 266353 ms；模型输入 413947 token、输出 15312 token，其中缓存读取 293632 token。第二案例元数据累计 19113 ms、模型累计 109596 ms。

交易/资产/盈亏案例自主描述了 10 张候选表，最终使用 TKHXX、TZJZH、TZQGL、TZJYELS、TZQYELS、TWTLS、TJGMXLS、TTZSY、TSO_TZSY 等实际发现对象。生成 10 条原始明细查询；客户号保留为字符串，Oracle 语法及所有字段引用通过校验。没有四则计算表达式、聚合或 JOIN；没有执行业务 SQL，也没有读取客户业务明细。盈亏专项进一步独立命中标准术语“盈亏 / PL”，来源是现有证券期货行业标准模板，并生成两条原始记录查询。

对应交付物：

- [客户完整查询 SQL](cline-sql-metadata-20261029-evidence/customer_070200046604_queries.sql) 与 [调用、字段、来源及性能证据](cline-sql-metadata-20261029-evidence/customer-acceptance.json)。
- [盈亏原始记录 SQL](cline-sql-metadata-20261029-evidence/profit_loss_records.sql) 与 [盈亏定义检索证据](cline-sql-metadata-20261029-evidence/pnl-definition-acceptance.json)。

## 验收结论与业务边界

| 要求 | 验证结果 |
| --- | --- |
| 无预选表自主探索 | 真实两轮通过；输入无预选业务表，策略及具体工具参数由 Cline 产生 |
| 找到相关业务表 | 真实发现交易、资金、证券余额及投资损益表 |
| 表字段、含义及指标定义 | 表字段/注释和业务术语已真实取回；命中“交易”“盈亏 / PL”。本轮查询结果未提供具体盈亏计算公式、期间和币种口径，这部分仍未确认 |
| 关联关系 | 服务支持返回已采集可见外键；本案例均为 NOT_COLLECTED，已明确标注并避免 JOIN，不能宣称实库已发现关联 |
| 数据库方言 | Oracle SQL 语法和字段引用校验通过；未执行 SQL，不代表业务结果已验算 |
| 初始快照外对象 | 初始无预选表，动态发现后所有引用通过校验，未再因初始快照缺失被直接判非法 |
| 探索后修复 | 真实模型在不同检索词和候选表间迭代；真实生成两轮无 SQL 字段错误，另有真实 SQLGlot/H2 回归覆盖 METADATA_REQUIRED、错误字段、继续发现及修复闭环 |
| 未授权访问 | 真实 DENY 测试 200→403→200；工具集不提供 SQL 执行器，执行授权边界另由原权限测试覆盖 |
| 审计与追踪 | 全部回执具有任务、操作、内容哈希与父证据；客户案例最终提案关联 31 个父证据，导出摘要含候选表和字段含义 |

因此自主探索、权限隔离、校验迭代及审计链路已验证通过；**不能把业务口径未确认部分称为完整业务验收通过**。本轮取得“盈亏”术语及其所属场景，尚未取得具体计算公式；本次遵守约束只交付原始字段查询，没有替业务方补造口径。发现的业务对象包含历史/日终备份表，数据实时性亦未验证。

临时验收账号已停用、临时角色已删除、临时 DENY 已撤销。首轮超时模型操作缺少完整远端回执，原工程删除接口因此返回 409；按既有保护规则保留该隔离工程及审计记录，没有绕过守卫删除或伪造停止/用量证明。见 [身份清理记录](cline-sql-metadata-20261029-evidence/fixture-cleanup.json)。

## 源码入口

- [元数据工具及发现状态](../chatchat-api/src/main/java/com/chatchat/api/datascience/sql/SqlWorkspaceMetadataTools.java)、[既有元数据网关](../chatchat-api/src/main/java/com/chatchat/api/datascience/sql/SqlStudioMetadataGateway.java)。
- [Runtime 预算、工具回执与进度](../chatchat-api/src/main/java/com/chatchat/api/runtime/coding/workspace/CodingWorkspaceService.java)、[签名回调](../chatchat-api/src/main/java/com/chatchat/api/runtime/coding/cline/ClineModelCallbackController.java)。
- [Cline 工具与完成条件](../coding-agent-node/src/runner.js)、[worker 协商及网关](../coding-agent-node/src/server.js)。
- [中央资源权限](../chatchat-enterprise/src/main/java/com/chatchat/enterprise/service/ResourceAuthorizationService.java)、[MCP 目录权限](../chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/authorization/McpAuthorizationService.java)。
- [可复用真实回归脚本](../scripts/verify-cline-customer-sql.py)：支持 UTF-8 `--prompt-file` 及原任务 `--resume`，身份文件留在忽略目录，输出不包含登录凭据。
