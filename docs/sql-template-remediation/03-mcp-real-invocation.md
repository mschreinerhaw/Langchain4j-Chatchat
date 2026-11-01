# 独立 MCP 协议与生命周期实测

独立 Java 客户端使用生产鉴权及签名类，实际执行 initialize、notifications/initialized、tools/list、tools/call 与关闭会话。身份来自登录用户及其可访问 Agent 配置；未载入业务应用或 Agent 执行器，没有临时角色或隐藏执行器。请求归档去掉服务凭据和签名，只保留无签名公开参数及实际响应、traceId。

| 阶段 | 核验 |
| --- | --- |
| 历史两项模板 v3 | [原始协议](mcp-protocol-invocations.json)、[JDBC](mcp-independent-jdbc.json)、[对比](mcp-protocol-comparison.json)：6 年度行、11 月度行，共 119 字段一致 |
| 两项新业务首次发布 | [原始协议](mcp-warehouse-protocol-r3.json)、[对比](mcp-warehouse-comparison-r3.json)：4 组调用、71 字段一致；5 组非法参数拒绝 |
| 未绑定 Agent | [协议](mcp-unbound-protocol-r3.json)、[对比](mcp-unbound-comparison-r3.json)：工具未列出；两项调用 HTTP 403 MCP_ASSET_ACCESS_DENIED |
| 更新至分布模板 v2 | [协议](lifecycle/mcp-v2.json)、[对比](lifecycle/v2-comparison.json)：同一 MCP 工具名、当前版本和 releaseId 与管理页一致，72 字段与独立 JDBC 相同 |
| v2 下线 | [协议](lifecycle/mcp-offline.json)、[对比](lifecycle/offline-comparison.json)：分布工具消失且直接调用被拒，库存模板仍可查询 |
| API 重启时仍下线 | [原始定义与发布状态](lifecycle/offline-after-api-restart.json)：未自动复活 |
| 正常回滚至原 SQL | [回滚](lifecycle/v1-rollback.json)、[协议](lifecycle/mcp-restored.json)、[对比](lifecycle/restored-comparison.json)：发布号 v3，原六列恢复，71 字段一致 |
| MCP 重启及结果列声明补充 | [最终协议](mcp-final-r8.json)、[对比](mcp-final-r8-comparison.json)：当前 v3 / v2、71 字段一致；只发布已验证列名，不注入试执行数据 |
| 重复／并发重放 | [实际响应](lifecycle/concurrent-idempotent-publication.json)：两个并发发布请求仍为同一活跃 release，无新版本；过期写入被拒。此项不冒充竞争不同草稿的并发测试 |

实际执行均保留 PREPARED_STATEMENT、占位符数量／顺序、生效参数、资产 248 及 sql_result.v1 完整返回行。缺日期、坏格式、负下限、整数注入和带 SQL 语法的字符串在执行前被拒。原探针 MCP-INJECTION-IS-LITERAL 的名称是当时预期；实际为既有严格校验拒绝，未把它描述成成功执行了字面量查询。

辅助 v2 是生命周期测试 SQL，输出列形状与原版不同；不作为“只增加一列且其余字段不变”的业务验收。最终恢复的是已验证原版冻结 SQL，源文件历史仍保留辅助 v2，页面详情明确发布绑定的源版本。没有手工修改数据库表，也没有另建模板存储。

独立协议通过不等于 Agent 或图表通过；分别见 04、06。

## 当前发布的最终复验

最终 r9 使用正常模型生成并试执行的兼容分布 SQL（保留原六列、增加全局最小主键），发布 v4；库存为 v3，历史年度/月度为 v6。见 [兼容修订发布](compatible-geography/publish.json)与[最终实际目录](catalog-final-pagination/catalog-responses.json)。先前 v3/v2 是历史时间点，不是当前版本。

[最终完整协议](mcp-final-r9.json)共11项请求；[仓储对比](mcp-final-r9-comparison.json)包含4组成功、72字段与独立 JDBC 相同及5组拒绝；另加[非默认商品2对比](mcp-product-final-r9-comparison.json)，12行84字段相同。全部使用同一生产 MCP 服务及实际 Agent 授权身份。

重启后 API 本地目录曾短暂为空。部署现增加真实登录后的 API-side MCP registry readiness 检查，要求 HEALTHY 和预期工具实际就绪；[r9部署回执](deployment-r9.json)确认36项工具。未用端口通或独立 MCP 列表替代 Agent 本地目录就绪。
