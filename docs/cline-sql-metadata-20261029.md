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

修复在现有中央权限服务内区分元数据与执行。已登录、启用、同租户用户默认可以读取明确声明为只读元数据的四个现有目录工具；API 还检查发布者的 METADATA_READ 边界声明。已有显式 DENY、禁用工具、身份及租户检查继续生效。SQL 执行器仍使用原有业务工具授权；没有给验收用户添加任何执行或数据资源授权。

部署内部同步账号仍不作为业务身份导出至 MCP，验收使用临时普通用户，避免绕过现有身份边界。

## 预算与可追踪性

- 每任务最多 24 次模型调用、64 次工具调用；单次元数据调用最多 15 秒，且不能超过任务截止时间。
- 自主探索任务默认 900 秒，可用 `chatchat.coding.workspace.sql-metadata-timeout-seconds` 在 1–3600 秒内配置；普通 SQL/Python 文件任务继续使用原有超时。真实首轮回归触发了旧 300 秒期限，故将探索任务的时间预算单独配置，保留该失败样本。
- 自主探索累计模型 token 上限默认 1048576，可用 `chatchat.coding.workspace.sql-metadata-max-model-tokens` 在 32768–8388608 内配置。普通文件任务保留原预算；模型侧列结果去除重复的所属表/库/Schema、数据源 ID 和空值，后端审计仍保存完整回执。
- 单次结构化结果最多 64 KB；任务缓存最多 64 张表、32 个查询、750 KB 状态。
- SQL profile 不提供 shell、网络、Python 或业务 SQL 执行能力。
- 元数据操作使用现有预算预留、执行意图、结算和 Evidence Store 回执；最终提案保存父回执关系。
- 快照变更会使旧发现证据失效；权限拒绝、服务故障、对象未发现和 SQL 字段错误分别记录。

## 验证记录

测试与真实环境验收结果将在部署后补充。真实回归脚本为 `scripts/verify-cline-customer-sql.py`，客户号作为字符串 `070200046604`，不预选业务表、不执行客户查询。
