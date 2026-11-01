# 调用链与存储审查

当前状态：目录断点已修复，真实管理页面已复验；完整业务链路仍按逐项证据验收。

SQL Studio 使用 SqlStudioPublicationController 以已保存工程文件的版本和摘要创建/试执行/发布，经 McpControlPlaneClient 调用 MCP 的 SqlTemplateAuthoringService。定义保存在 mcp_sql_template；不可变发布版本保存在 mcp_tool_release，kind=TEMPLATE_CAPABILITY；TemplateCapabilityPublisher 暴露 tpl_ 工具，执行时复核调用签名、租户、用户/角色、Agent 工具权限及源模板/数据源依赖。

用户当前“关系库查询”入口的 DatabaseMcpView 调用 /api/v1/database-query，原实现仅投影 mcp_database_query_config，因此 Studio 发布记录没有进入这一列表。两者已有不同事实模型，不应通过复制行伪造同步。

[数据库原始核查](persistence-audit.json)确认两项 Studio 记录存在，其 author_tenant_id、author_id、源工程/文件、发布版本及发布 ID 均有记录；mcp_database_query_config 中没有同名记录。[修复前入口搜索](catalog-before.png)实际返回零项。数据库定义没有单独 workspace 列；所属租户和源工程以实际字段为准，不能编造空间字段。

修复在现有关系库入口合并既有定义/发布的只读投影，通过 sourceKind=SQL_STUDIO 标识来源；没有新增数据库表、写入复制模板或创建发布管线。原修改/试执行/版本恢复继续在源工程进行，管理端仅查看。

源码：SqlTemplateAuthoringService、TemplateCapabilityService、StudioSqlCatalogController、DatabaseQueryAdminController；页面 DatabaseMcpView、CrudCatalog。

[修复后原入口及两项详情](catalog-live/catalog-responses.json)与[界面](catalog-live/catalog-after.png)均有原始记录。后续正常扩充数据源模板白名单后，通过 Studio 重新执行恢复操作更新依赖快照，该轮两项历史模板为第 4 版；[第二轮目录响应](catalog-r2/catalog-responses.json)保留版本变更及实时 Agent 范围。不同时间点的第 3、4 版证据不可混为同一次执行。

最终版本为历史两项v6、仓库地理分布v4、库存快照v3，见 [当前同一入口完整响应](catalog-final-pagination/catalog-responses.json)；每项的租户、源工程/文件版本、实际发布ID及可访问Agent范围均在详情原件中。不得将不同轮次版本拼接为同一次执行。
