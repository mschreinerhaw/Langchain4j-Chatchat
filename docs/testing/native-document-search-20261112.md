# Native Cline：Agent 授权文档检索

## 实现范围

复用系统已注册的 `mcp_chatchat_mcp_server_document_search`，通过现有 Native Runtime MCP facade 向 Cline 暴露 `document_search`。Cline 自主选择查询词、检索时机和后续查询；Java 只负责能力准入、授权、证据投影和归档，没有文档问答业务模板或强制执行流程。

能力来自任务实际 `authorizationAgentId`。Agent 必须已发布、账户可访问，且配置了文档资源、知识资源策略及该 MCP 工具授权。没有授权时不暴露工具。文档检索独立于互联网开关。

输入只接受 `query`、`topK`、可选的 `fileIds`（只能缩小范围）。租户、账户和 Agent 由服务端填充；模型不能传入角色、索引或授权范围。未指定 fileIds 时，服务端将当前 Agent 已授权的文档 ID 传给原检索器；标签范围同样由服务端设置过滤条件。调用使用原有 ToolRuntimeService 和 MCP 搜索服务，没有新增索引或搜索 Agent。

任务中签发的只读工具能力通过 Runtime 现有的参数绑定确认协议衔接；预检的拒绝结果不能被覆盖。检索前后重新检查当前授权，返回的每个文档 ID 再经过账户和 Agent 双重校验。文档内容被标记为不可信证据，不作为系统指令。

同时修复原 MCP bridge 的 Agent 上下文传播：将可信调用 Agent 放入远端实际读取的 `ToolInput.context.authorizationAgentId`，覆盖或移除模型提供的身份。保留原有身份认证和文档过滤器。

## 部署要求

1. 从固定提交构建 API 与 Coding Node，保留旧镜像和私有配置备份。
2. Gateway 和 Executor 对应租户的 `tools` 均加入 `document_search`、`runtime__document_search`。
3. Gateway 对应租户现有 `mcp.tools` 加入 `document_search`，继续使用 `authMode: native-runtime`。不要修改成绕过 Java 的搜索端点。
4. 互联网任务已有的工具权限保持原配置；本次文档能力使用明确的 `runtime__document_search`，不新增通配授权。
5. 重启 Gateway/Executor 后启动新任务；等待 API 认证接口就绪再运行验收。不要在活跃 Worker 中途重启。

Node 仍使用官方 Cline 4.1.26 Core（源码固定 `a459f1d04dea030833b558be645011ab739c463b`），没有改动官方 Agent Loop。工具准入变化位于 Host 合同校验层。

## 回归验证

Java 38 项通过：CodingRuntimeContextTest 10、McpAgentContextPropagationTest 2、DocumentEvidenceAuthorizationFilterTest 2、CodingDocumentSearchAccessTest 11、NativeRuntimeMcpControllerTest 7、NativeTaskCapabilitiesTest 6。

覆盖无文档授权、伪造身份、越界 fileIds、跨租户证据、调用期间撤权、取消、空结果与搜索失败区分、只读参数绑定确认及不可覆盖拒绝策略。

Node 10 项通过：task-issuer、runtime-model、governance-boundaries。验证签名合同只接受明确支持的 runtimeTools，拒绝 document_admin。

真实验收脚本：`scripts/verify-native-document-search-live.mjs`。认证信息仅由 `RUNTIME_TEST_USER`、`RUNTIME_TEST_PASSWORD` 环境变量提供；`RUNTIME_TEST_DOCUMENT_FIXTURE` 指向隔离的合成文档/Agent fixture。fixture 含 nonce、agent.id/modelName、documents[0].docId、expected.team/reference/forbidden。测试读取实际模型回执、检索证据、Silo 登记产物并独立核对事实、引用和 SHA-256，不能用模型口头声称检索成功代替。

## 已发现并保留的失败

- 初始合成 Agent 使用了无效发布状态，被 API 400 拒绝；已改用合法的 draft → publish 流程，没有修改生产 Agent。
- 初次部署脚本误以为 Node 配置是单文件挂载，检查失败，尚未修改运行服务；按实际目录挂载定位后完成部署。
- API 重启窗口首次登录返回 502；验收启动器增加服务就绪检查。
- 节点任务准入列表遗漏文档工具权限，返回 403；补齐 Gateway/Executor 准入配置。
- 首轮真实模型自主调用了 document_search，但原 Runtime 确认要求未衔接，返回明确失败；该轮最终交付也失败（Task metadata binding required），不计为成功。修正版增加已有参数绑定确认协议的适配和拒绝策略测试，重新构建 API 后独立重跑。
- API 认证可用但 MCP 目录未完成注册时，一轮任务未获得检索能力，生成了缺证据报告；任务交付状态虽然 COMPLETED，事实验收失败，不计为成功。现在授权文档工具未就绪会明确返回 503，避免静默省略能力。
- 下一轮真实检索已成功调用远端，但适配器最初没有解包 Runtime 的 `mcp_analysis_payload.v1`，导致 Invalid document evidence contract。该轮同样只交付了缺证据报告，不计为成功。修复复用 `McpAnalysisPayload.canonicalData`，仅取治理后的 data，禁止回退 rawData；新增真实协议形状的回归测试。
- 返回证据校验阻止了账户范围大于 Agent 绑定范围的结果，向 Cline 返回授权错误，没有披露超范围内容。修复在检索前将当前 Agent 的授权文档 ID／标签范围下传，仍保留返回后校验。

## 回滚

恢复发布目录 `rollback/` 中对应私有 Compose 配置，并重新创建 API；恢复前版 Gateway/Executor 镜像及配置。保留 Silo、数据库、任务历史和输入文档，不删除持久化卷。镜像与实际验收结果见随附证据清单。

## 最终真实运行结果

2026-11-12 在现有 18100 入口完成验证，使用真实 qwen3.7-plus 和原生 Cline，输入是明确标识的合成测试文档，没有将测试内容写入生产逻辑。隔离 Agent 只绑定 assigned 文档；同账户下另有 unassigned 文档及泄露校验标记。

- Task：`native-17ed7eeb88516bbad0bf0c8aec5262dfdaec9220532c47670169b50269480912`
- Run：`coding-e59d4023713ec37a72d91ca637c7e18178a7d7505d3c61c2844c3f6eb0498d9d`
- 实际自主调用 document_search 1 次，返回且仅返回 `native-doc-assigned-db0b53d851`。
- 独立验证回答中的 Aurora Desk、7 calendar days、测试参考编号与原文一致；报告和 JSON 均有来源引用，没有未绑定文档的校验标记。
- 任务 COMPLETED，Java 已登记 Silo 交付，下载内容与 SHA-256 一致；业务事实验证另行执行并通过。
- 8 个真实模型请求：输入 67,447 Token、输出 3,025 Token、总计 70,472 Token。缓存是输入的子集，不重复相加。完整验收耗时 87.658 秒，含启动准入重试和下载验证。
- 本轮启动期间 2 次 503 准入重试使用相同 requestId，成功准入后才开始模型任务。没有通过重新发送模型任务掩盖错误。
- 验收后已召回隔离 QA Agent，保留测试文档和任务证据；生产 Agent、共享模型配置未修改。
- 已核对 Worker 标签及停止状态，归档日志后仅删除本次 5 个已停止的 QA Worker 容器；没有执行全局清理或删除持久化数据卷。

源码镜像：API `runtime-os-api:native-document-search-20261112-r4`，image ID `sha256:7371ac334707f823b8d430db9410788582e61d128b40bf0542c86cf9bd8bf8e5`，源码提交 `e01575fc6460b4fe9800702078864ae90018214a`。Node/Executor 使用 `runtime-os-coding-node:native-document-search-20261112-r1`，image ID `sha256:60641b46986f7b260de9e89c491f9bb956ca0322038dbbc034683390a448953a`，Node 源码提交 `dcb5dbd1067e7093ab52691a628f9cbbe55ebda7`。这里记录本机 image ID，未冒称镜像仓库的远端 Digest。

完整构建日志、原生日志及回滚配置位于服务器 `/opt/runtime-os/releases/native-document-search-20261112-r1` 至 `r4`。仓库中经检查的证据在 [evidence/native-document-search-20261112](evidence/native-document-search-20261112)，包括 fixture、实际报告/JSON、检索回执、供应商用量回执、监控事件、交付登记、测试结果和失败记录。

权限撤销、伪造身份、跨租户证据及取消由上述回归测试覆盖；此次真实端到端验证覆盖同账户的 Agent 文档绑定隔离，没有声称执行了真实跨租户渗透测试。
