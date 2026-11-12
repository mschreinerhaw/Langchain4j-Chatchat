# 完整行情日文件供数：取消 10,000 行限制

源码版本：`a7053cf4e`、`5fec1039d`、`7d2437308`。当前专项仅解除日文件供数链路的人工行数及内联窗口限制，不改变业务分析规则、Cline Core、模型配置或数据授权。

## 修改

* `FinancialDataStore.daySnapshot` 对明确的数据集、来源及交易日读取完整存储快照，不再设置 10,000 行 SQL 上限或抛出 `DAY_SNAPSHOT_ROW_LIMIT`。保留最新观测去重、来源/日期筛选、受限记录过滤、取消检查、查询超时及并发隔离。
* MCP 日文件接口移除重复的 10,000 行检查及 8 MiB 内联响应窗口；普通交互查询原有分页限制不变。
* Java `CodingDataSupplyService` 移除重复的日文件行数检查，使用真实行数生成快照。完整性和 `count` 一致性校验继续执行，不允许截断后标记完整。
* 文件交付继续使用现有受治理快照及共享空间。`chatchat.coding.analysis.data-supply.max-resource-input-bytes` 默认 64 MiB，超预算明确失败；原有传输格式、分块、哈希、权限及通用资源约束仍然适用。
* 删除旧 `max-day-snapshot-rows` 配置项；没有通过调大旧数值掩盖固定上限。
* 容器源码构建增加内置 Skill 索引的输入和打包结果检查。源码包必须包含 `coding-agent-node/skills/catalog.json`，防止 Maven 忽略缺失目录后生成不可启动的 API。

## 回归

Java 三组共 43 项：42 项通过，1 项因缺少 `coding.sqlglotPython` 环境属性跳过，没有将跳过算作通过。

* `FinancialDataStoreTest`：21 项通过，包含真实 H2 写入 10,001 行后完整读取、首尾记录存在、其他来源/日期不混入，以及普通交互查询仍返回最多 200 行。
* `ResourceExplorationReadControllerTest`：8 项通过，包含 10,001 行且超过 8 MiB 的日文件响应、拒绝不完整快照、受限记录及非法 SQL 参数。
* `CodingDataSupplyServiceTest`：13 项通过、1 项跳过，包含 10,001 行且超过 8 MiB 的实际快照序列化、行数不符拒绝及配置字节预算生效。

真实端到端脚本：[verify-market-day-delivery.mjs](../../scripts/verify-market-day-delivery.mjs)。凭据通过环境提供，不写入脚本或镜像。测试问题只用于验收，不进入生产分析决策。

## 部署与真实供数

最终镜像基于完整已提交源码 `7d2437308b78314f4273bd87c2c527040a0cac9e` 在现有服务器动态构建：

| 组件 | 标签 | 本地 image ID |
| --- | --- | --- |
| API | `runtime-os-api:day-snapshot-unlimited-20261112-r3` | `sha256:a2e76af8350163e85ff85f5734cc38e25970a3ed887f768e25c7c7e32951ac7a` |
| MCP | `runtime-os-mcp:day-snapshot-unlimited-20261112-r3` | `sha256:8e4ce47bbd085aa93162c588922ec4a49a57f297364bcbc8f012e0965d3d468a` |

源码归档 SHA-256：`e22e91167ba41db322878a0ed917e784593d5e17ca85852c9bd1e5332900cbd0`。这些是本地 image ID，不冒充镜像仓库 RepoDigest。API 仍通过现有 18100 入口访问，MCP 管理端口继续只绑定回环地址；Node、模型、News、Silo 与 PostgreSQL 配置没有更换。

最终 MCP 镜像经现有内部账户认证，真实读取 `market_quote_daily`、`source_id=38`、`2026-11-11`：返回 **24,380 行、25,644,715 字节、`complete=true`**，实际返回数组长度与 count 一致。保留响应 SHA-256 与版本记录。这里的“完整”仅指已存储的指定来源/日观测，不代表交易所采集覆盖完整，也不构成实时行情。

随后通过现有 18100 数据探索入口提交本地行情分析，工作区 `0d3805d9-f4de-45d7-bdfc-f48517e6c47c`、节点 `50d381c2-ea6a-4591-a385-f746057bb48f`、Agent task `0d62bdf5-88d5-318c-81bf-9e75fd256b02`。任务进入原生 Cline，产生真实文件编辑、Python 命令及模型回执，并登记 FINAL 交付。SVG/PNG 经 Java 授权接口下载并核对 SHA-256、大小，两份图表均通过对象完整性检查。

**本次分析状态为 PARTIAL，并非完整业务成功。** 该选股任务实际收到的输入文件有 14 行，仅两行是目标股票、对应两个交易日；报告披露了数据不足。不能把上述 24,380 行日文件接口验证混称为该任务分析了全量市场，也不能把原生执行和对象完整性检查当作金融结论验算。Java `businessValidationPassed=false`、`validationScope=STRUCTURE_AND_OBJECT_INTEGRITY` 保持真实值，未放宽为通过。

原生回执保留了 21 个工具调用、其中 3 次失败及后续继续执行；6 个原生命令请求涉及 Python。这里不把 Host 的配置/生命周期 `command.completed` 数量当作 Python 执行次数。

证据：[真实日文件响应摘要](evidence/day-snapshot-unlimited-20261112/mcp-live.json)、[Cline 执行回归](evidence/day-snapshot-unlimited-20261112/verification.json)、[原生回执与输入哈希](evidence/day-snapshot-unlimited-20261112/native-receipts.json)、[Java 交付回执](evidence/day-snapshot-unlimited-20261112/receipt.json)、[产物下载校验](evidence/day-snapshot-unlimited-20261112/artifacts.json)、[原始报告](evidence/day-snapshot-unlimited-20261112/report.md)。完整 Worker 日志保存在服务器专项证据目录。

## 失败记录

* 首次解除存储/MCP 行数检查后，真实请求揭示既有 `DAY_SNAPSHOT_BYTE_LIMIT`；继续清除内联窗口，并修复 Java 接收端的重复行数检查，不将首次失败算作通过。
* 首次新增 API 单测夹具缺少日文件必备元数据而失败；补齐真实协议字段后回归通过。
* r2 上传源码遗漏内置 Skill 目录，Agent 列表返回 500、启动报 `Builtin Coding Skill catalog missing`。已立即将 API 恢复到之前验证版本，保留 MCP 修复，再用完整源码构建 r3，并新增构建门禁。此失败发生在任务创建之前，未制造成功任务记录。
* 初版部署探针错误假定 MCP 定义了 Docker Healthcheck；已改用真实受保护接口和认证读数验证，没有以不存在的健康检查宣称验收通过。

服务器构建、部署、回滚记录位于 `/opt/runtime-os/releases/day-snapshot-unlimited-20261112-r1/`、`r2/`、`r3/`。回滚应排空任务后仅恢复 API/MCP 镜像，不覆盖其他服务配置，不删除数据库或 Silo 数据。

最终保留的稳定回滚镜像：API `sha256:0646027a8e359c071cc642160bbe6e5c0a16cbab9ac5aec46ae6cbd708bd03fb`、MCP `sha256:94f95194b83f908ba4fd724b56d962f16e591458ca4a9d62762f5f85807e0a71`。用这两个 image ID 恢复现有 Compose 的 `services.api.image` / `services.mcp.image`，再仅对这两个服务执行 `up -d --no-deps`；旧镜像会恢复旧的供数限制。

仅归档并移除了本任务已停止的 Worker 容器和三项中间镜像标签，未执行全局 prune 或卷删除。最终 API、MCP、Node 镜像和稳定回滚镜像均已核对。清理期间文件系统可用空间由 42,348,912,640 变为 42,248,232,960 字节，受并发系统写入影响，本次不宣称实现了可量化的净磁盘回收。18100 页面和监控页面返回 200，匿名监控任务查询返回 401。见[收尾记录](evidence/day-snapshot-unlimited-20261112/final-check.json)。
