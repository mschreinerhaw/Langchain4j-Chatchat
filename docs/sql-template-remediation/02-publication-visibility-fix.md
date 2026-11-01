# 发布可见性整改

状态：原入口、四项模板详情及更新／下线／回滚后的目录一致性已真实复验。见 [最终同一入口截图](catalog-final-pagination/catalog-after.png)与[实际响应](catalog-final-pagination/catalog-responses.json)。

原入口漏读 mcp_sql_template。现通过受现有 databaseMcp 管理菜单权限保护的只读接口投影，合并进入关系库查询同一搜索/分页列表，增加来源筛选、MCP 工具名、版本、发布状态、数据源、参数摘要和发布时间。只读记录不提供旧 CRUD 的编辑、测试、启停、删除或批量选择，避免错误送入手工模板接口。

详情展示源工程/文件版本、SQL、Schema、试执行记录及历史发布；MCP 非超级管理员只能读取脱敏的 SQL/试执行摘要，权限不足返回明确错误。同步授权规则不等于最终执行许可，调用仍需通过既有 Runtime ACL。

后端不保存投影、不缓存发布状态。列表刷新及页面重新激活都会重新读取已有定义和 release；下线状态直接来自现有 release。七项目录检查包括恢复较早 SQL 后再停用时仍显示最新 release，另有 authoring 与 capability 回归。

目录修复源提交：96c8ca17d；范围投影及恢复版本选择修复：371cd3afc。[第一轮页面](catalog-live/catalog-after.png)、[第二轮实际响应](catalog-r2/catalog-responses.json)和[部署回执](deployment-r2.json)均已归档。

详情实时调用既有 AgentTemplateToolPolicy，以发布者的实际租户、用户授权、已发布 Agent 配置及工具绑定求交集。历史两项工具实际返回三个可调用 Agent，包含本轮普通 Agent；这不是将 MCP 同步权限规则数量当作 Agent 绑定数量。其他用户仍须通过自己的权限检查，管理视图不授予任何执行权限。

最终页面实测还验证了来源筛选、四项结果及第二页切换，见 [分页与筛选](catalog-final-pagination/filter-and-pagination.json)。当前分布 v4、库存 v3、历史年度/月度各 v6；版本增长来自正常验证、审批刷新及发布，不代表重建了第二套模板。
