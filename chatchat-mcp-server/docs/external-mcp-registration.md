# 外部 MCP 服务注册与模板归属

“本机接入凭证”管理调用当前 MCP Server 的客户端；“外部 MCP 模板”管理当前系统主动调用的远端 MCP Server。两者分表、分接口，出站 Authorization 不参与本机入站鉴权。

注册流程：

1. 在“外部 MCP 模板”登记名称、Streamable HTTP 端点、可选 Authorization、父类模板和执行工作流。新服务始终停用。
2. 手动“发现工具”。系统使用 MCP SDK 初始化会话并分页读取 `tools/list`，将工具名称、标题、描述、输入 Schema 和 `readOnlyHint` 保存为快照，不会直接发布。
3. 审核快照并启用服务。只有远端同时标注 `readOnlyHint=true` 且未标注 `destructiveHint=true` 的模板才会发布到本机 MCP/Agent 工具目录。未标注的工具保留在快照中供审查，但不能通过系统调用。
4. 调用时按“服务 ID + 已发现的工具名”路由到所选执行工作流；停用、未注册或非只读的工具一律拒绝。执行参数仅向远端转发工具 Schema 声明的字段，避免泄露本机注入的用户上下文。

启用的只读工具会以稳定的 `external_<serviceId>_<toolName>_<hash>` 名称发布，并进入对应 API/数据库/HTTP 父类的模板资产目录。模板检索仍受既有角色与模板绑定控制；检索结果中的 `executionToolName` 指向实际可调用的本机代理工具，不会把远端端点或凭证暴露给 Runtime。

父类模板来自 `TemplateQueryParentCatalog`，当前开放 API 服务、数据库查询/运维和 HTTP 请求父类。它决定目录中的资产归属；实际传输由 `ExternalMcpExecutionWorkflow` 实现。新增传输协议或执行模式时添加一个 Spring 实现并赋予唯一 `id()`，页面会从 `/api/v1/external-mcp-services/workflows` 自动获取，而无需复制注册页面。新增父类需同时扩充 `TemplateQueryParentCatalog` 与 `ExternalMcpRegistryService` 的允许资产类型。

修改端点、凭证、父类或工作流后，旧快照自动失效并停用；重新发现和审核才能再次发布。出站凭证使用内部密钥 AES-GCM 加密，因此配置凭证前须设置 `chatchat.internal-credential` 的加密密钥。远端的只读声明属于对方提供的元数据，不是独立安全证明；生产接入仍需审核远端供应方和工具行为。
