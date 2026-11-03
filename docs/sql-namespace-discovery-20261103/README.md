# AI 数据库助手库名与表结构可见性修复

2026-11-03，验证地址：http://192.168.195.221:18100 。分支：`feature/sql-namespace-discovery-20261103`。

## 真实原因

针对截图中的 `db_query_tdh_dwd`（TDH 数据仓库，Inceptor）现场验证，管理员授权目录原本就能返回 **13 个库 / Schema、442 张表**。因此不能把“下拉框只有全部授权库”认定为 MCP 没采集到库名，也不能把编辑器目标方言 MySQL 认定为原因。

确认了两个相互独立的问题：

1. **窗口恢复遗漏了未完成的目录加载。** `SqlSchemaExplorer.js:378` 的 `suspend()` 在失焦时使正在执行的请求失效；原 `resume()` 只复核权限，没有重新请求尚未完成的库目录。绑定上下文已经存在，旧页面仍显示绿色。现场复现结果是只有“全部授权库”，与用户截图一致。现在恢复未完成的上下文、库目录和已展开导航的表目录，丢弃迟到响应；加载完成之前不显示绿色就绪状态。完整加载后的分页、滚动和已展开字段继续保留。
2. **OpenSearch 元数据写入部分失败且同步结果误报成功。** 刷新后的注册快照有 442 张表，但搜索接口和实际索引只有 38 张。服务器日志明确记录 `text.pinyin` 的 `immense term` 错误：38243 字节超过 32766 字节限制。原实现将完整字段诊断 JSON 拼进一个搜索文本值，导致宽表写入被拒绝。底层异常被 `LuceneMcpSearchService.replaceSqlDatasourceAssets()` 吞掉，上层依然按输入文档数量报告 443 条成功。

搜索文本现在使用已有 `text` 字段的多值形式，按字段名称、注释、类型建立有界文本片段；全部字段和版本仍保留在 `metadataEvidence`，没有删减结构证据。显式数据源元数据重建失败会向上抛出异常，阻止返回成功计数。权限与版本探测改用已有 `GET_METADATA_FRESHNESS`，不再依赖空白搜索是否命中。

## 调用链与修改位置

- 前端：`SqlSchemaExplorer.js` 的 `fetchNamespaces()`、`suspend()`、`resume()`；`SqlSchemaExplorer.vue` 的 `namespacesLoaded` 就绪条件。
- 每次读取 / 缓存复用前：`sqlMetadataSession.js:26` → `authorize()` → `GET_METADATA_FRESHNESS`，继续检查当前授权快照。
- HTTP 元数据操作 → `SqlStudioMetadataService.java:155 operate()` → `SqlStudioMetadataGateway.java:60 invokeScoped()` → Kernel MCP 调用 `sql_schema_context_query` → `SqlMetadataDiscoveryWorkflow.java:32 executePlan()` → `SqlMetadataDiscoveryService.java:59 discover()`。
- `LIST_NAMESPACES` / `LIST_TABLES` 读取当前授权的注册快照；`SEARCH` / `DESCRIBE` → `OpenSearchMcpSearchService.searchMetadataEvidence()`，继续按资产、授权表集合及版本过滤。
- 索引写入：现有 `McpAssetLuceneIndexService.rebuildSqlDatasources()` → `LuceneMcpSearchService.replaceSqlDatasourceAssets()` → `OpenSearchMcpSearchService.replaceSqlDatasourceAssets()` → `assetSource()` / `metadataSearchText()` → OpenSearch bulk。

本次库目录与结构验收不调用模型，不执行用户 SQL，也没有 JDBC 回源。不能将这些检查解释为模型端到端 SQL 生成或 SQL 已运行成功。

## 现场验收

| 检查 | 修复前 | 修复后 |
| --- | --- | --- |
| 库目录接口 | 13 个 | 13 个 |
| 正常页面加载 | 可见 13 个库 | 可见 13 个库 |
| 加载中失焦再恢复 | 只剩“全部授权库”，绿色 | 重新加载完成后可见 13 个库 |
| 注册表目录 | 442 张 | 442 张 |
| OpenSearch 实际文档与搜索命中 | 38 张表 | 442 张表 |
| 索引结构逐表对比授权结构 | 缺失 404 张表 | 442 张全部一致，差异 0 |
| `ods_a5_jzjy` 下表展开 | 原索引缺失 | 浏览器能展开真实字段 |

使用既有“选定数据源重建索引”接口修复 TDH 索引：443 个文档 = 1 个数据源 + 442 张表。此次同步耗时 13351.89 ms，源库读取次数 0，注册快照版本保持 `sha256:0649ff0c13a570aa242615b1d4232d891691a9e5d08272be810c3bcdddb29bdf`。

独立 HTTP 验收各操作调用 1 次：版本探测 283.02 ms、库目录 294.05 ms、表目录 252.50 ms、搜索 720.68 ms、指定表结构读取 303.02 ms。均为单次含网络耗时的样本，不能作为 P50 / P95。

测试：前端 69 项通过；后端搜索与失败传播 12 项通过；已有 OpenSearch、探索工作流、授权投影测试 29 项通过。前端生产构建通过。浏览器正常加载、故意延迟库目录后失焦恢复两条路径均通过，页面异常 0；两条路径都选择 `ods_a5_jzjy` 并展开表字段。

证据：`verification.json` 保存脱敏后的前后接口计数、调用记录、下拉选项和字段读取数量；截图文件包括修复前、修复后及中断恢复场景。可运行 `scripts/verify-sql-namespaces-live.mjs` 重测，登录密码通过 `CHATCHAT_LOGIN_PASSWORD` 环境变量传入；设置 `NAMESPACE_INTERRUPT=1` 验证请求中断恢复。

## 部署

已部署 API、MCP 镜像 `sql-namespaces-20261103-r1`，运行 JAR 哈希及基线见 `manifest.json`。从现场当前 API V2 r3、MCP 元数据 r2 基线构建补丁，并逐项验证未修改的后端内容一致；没有数据库迁移。39 个 MCP 工具注册正常，Worker 容器保持原版本，已有会话内容未改变。部署、回滚备份位置和校验结果见 `deployment.json`、`artifact-verification.json`。凭据不进入验收文档或提交。
