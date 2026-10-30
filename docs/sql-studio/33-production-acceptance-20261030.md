# SQL Studio Hive 交付修复：生产发布及原业务账号验收

## 结论与范围

已使用用户提供的正常应用账号，在 `192.168.195.221:18100` 完成发布及原业务需求复验：从自然语言需求出发，自主探索已授权 Oracle 结构，生成 Hive SQL，通过语法与引用字段校验，保存并下载 SQL 文件。未预选表、未更改任何业务权限、未执行生成的 SQL。原失败任务保持失败记录，原业务工程未被本次验收写入；交付保存在独立工程 `4ce59c4c-e5c1-4fa9-8894-954f8bb8e602`。

**这是 Hive 源文件生成范围的验收通过，不是完整原始 P0 的全部验收通过。** 用户明确 Oracle 仅提供源表结构。未配置、授权或试执行 Hive 目标连接；数据库执行错误自动修复仍未验收。生成 SQL 的业务统计口径、两处推断关联仍需业务审查。

## 发布与实际问题

1. `e45b34305` 已合入主分支（合并提交 `b8e7b7a23`），包含 Hive 标识符校验、受控元数据操作、修订预算及持久化交付依据，亦包含前端框架名称隐藏修改。实际发布了 API、MCP 和 Node 任务执行器，保留了此前 Python Studio 关闭全部脚本的修复。
2. 应用登录确认与原失败任务为同一用户，正常目录授权能访问目标 Oracle 数据源。部署服务账号的授权没有被用来代替业务身份。
3. 首次尝试实时刷新失败，日志确认 `oracle.jdbc.OracleDriver` 不可加载。更新前后 MCP JAR 均未内嵌该驱动，更新前容器也未挂载驱动目录；服务器上已有 `lib/drivers/oracle/ojdbc8.jar`。因此为 MCP 增加了既有 Oracle 驱动目录的**只读**挂载，并持久化到 Compose 配置，没有下载或重新分发驱动。回滚容器和配置备份均保留。复现部署脚本：`scripts/repair-sql-studio-oracle-driver-stage.py`。
4. 驱动恢复后，Oracle 结构采集和快照持久化完成；第一次后续搜索索引同步触发 MCP 工具的30秒截止，调用方返回通用502。新增 `f6f71eece` 补充识别服务器结构化 `TIMEOUT` 回执（即使外层错误码被适配器替换），定向回归23项全部通过；没有放宽原有截止或 SQL 校验。随后在线刷新成功；没有人为制造新的线上超时来冒充修复验证，结构化超时分类的补充验证是自动化回归。
5. 最后一次授权刷新为北京时间 **2026-10-30 12:25:34.520**，版本 `sha256:ba3b9b8e151c4cd17ab43d5c744f95bb1a8063728f87701d73f3c462040d8a54`。当前授权范围可见196张表，实际分页有后续页且相邻页不重复。采集时间不是数据库最后 DDL 变更时间。

## 原请求的真实模型闭环

先保留了一轮旧索引下的成功结果，再以当天新快照重新提交原需求；原始历史对话作为不可信参考保留，补充用户已明确的“Hive 目标、Oracle 只提供结构”约束。仍使用 `qwen3.7-plus`，没有预选表，没有提高原任务24次模型调用上限。

| 项目 | 最终新快照运行 |
| --- | --- |
| Task ID | `sql-coding-4b0f5306-4875-4d24-a02b-218b14b24021` |
| 状态 | `COMPLETED`；交付文件已持久化并通过下载接口核对 |
| 任务时间 | 北京时间12:18:24.094—12:21:40.176，196,082ms（轮询在约203秒时观测到完成） |
| 模型调用 | 8次；预算24次 |
| 工具调用 | 消耗记录11次；探索/校验诊断清单10次，另含工作区读取 |
| 发现结构 | 4份源表结构证据；65个引用字段条目 |
| 校验 | `PARSED; FIELDS_CHECKED; SQL_NOT_EXECUTED`；最终提案 `finalSqlValid=true` |
| 文件 | `sql/dwd_delivery_flow_sample.sql`，UTF-8原文9,620字节 |
| 文件 SHA-256 | `4d919367dfb7485b8222332e2dc4844d5cfb820bab048af0ac1c9c7b04ee373d` |
| SQL/代码执行 | 0次；网络策略 `NONE`，仅开放已有4种工作区工具 |
| 停止证明 | `executorStopped=true`、`reconciliationRequired=false`；最终活动 Coding 会话0 |

每轮工具回执、元数据版本、模型提案及交付依据保留在原证据库；最终文件写入的是本次独立验收工程。在线下载字节与模型提案、保存内容及上述哈希一致。最后一次 API 更新后重新登录，历史仍可恢复且文件仍可下载；原失败任务状态仍为 `FAILED`，未修改成成功。

计费记录 `meteringComplete=false`，部分 token/费用预留仍保留，未擅自清除。`consumed.costAmount=0` **不是实际免费或计费已结清的证明**。这不改变已保存文件的可用性，但费用核销仍是独立限制。

## 生产证据与回滚

- [真实业务运行及限制](30-p0-evidence/live-business-acceptance.json)：耗时、预算、停止证明、文件哈希及未确认关联。
- [各项授权元数据操作](30-p0-evidence/production-metadata-operations.json)：Catalog、Schema、表分页、表/字段搜索、表描述、可见字段DDL及关系信息全部通过，不执行业务SQL。
- [生产浏览器](30-p0-evidence/production-browser.json)：真实HTTP、交付依据/字段/未执行原因可见、框架名称不出现在助手中、页面错误0、查看历史触发的生成请求0。脚本：`scripts/verify-sql-studio-hive-delivery-browser.mjs`。
- [重启后的文件下载及任务状态](30-p0-evidence/production-post-restart-check.json)。
- [首次配套发布](30-p0-evidence/production-deployment-initial.json)、[API增补发布](30-p0-evidence/production-deployment-final.json)、[驱动挂载](30-p0-evidence/production-oracle-driver.json)、[超时回归范围](30-p0-evidence/live-timeout-regression.json)。

当前 API 镜像 `runtime-os-api:sql-hive-p0-timeout-f6f71eece-20261030`，JAR哈希 `58383447c63a247e1d00bf035dd9c96860530b656e5d1f2081eb5dd70f53e12a`；MCP/worker 保持配套 `sql-hive-p0-e45b34305-20261030`，因为增补只涉及API的错误分类。MCP JAR哈希 `3516cc87b72d9a6569870f47dc05f06f513d7fe28447a004f6f3ad5537000871`。

发布目录 `/opt/chatchat-releases/sql-hive-p0-e45b34305-20261030` 和 `/opt/chatchat-releases/sql-hive-p0-timeout-f6f71eece-20261030` 保留部署记录、数据库/配置备份和旧容器。驱动挂载修改另有 `oracle-driver-backup` 和旧MCP容器。首次发布与API增补均核对无关服务未被重建、会话内容及消息数量未被迁移过程改变。

实际业务SQL、完整结构及浏览器截图不写入Git。工作目录 `target/sql-p0-20261030/live-hive-delivery.zip` 提供最终SQL、原结果、交付依据及可读说明；`target/sql-p0-20261030/final-hive/sql/dwd_delivery_flow_sample.sql` 可单独查看。它与此前历史快照的 `recovered-hive-sql.zip` 是不同交付物。

## 仍未完成的验收

- 生成结果的两处关联只有SQL等值条件证据，未由完整可见外键证明；指标分母、去重、有效委托筛选及Hive落地命名须业务审查，未宣称统计结果正确。
- 真实Hive执行、执行错误位置反馈及数据库执行失败自动修复、空结果执行用例尚未验收；不得改为在Oracle执行Hive SQL。
- `GET_TABLE_DDL` 明确只生成授权可见列结构，不是数据库原始完整DDL，不能用于完整重建源库。
- 已验证完成任务的进程重启后恢复；仍未进行运行中生产任务的强制中断/恢复演练。此前SDK及证据事务恢复回归与此应区分。
- 费用预留尚未全部核销。没有把设计文档、静态校验或上述生产生成成功替代完整原始P0验收。
