# 04 · Runtime OS 自主探索机制审查 — 授权边界修订（第二轮，只读）

> 本轮为**只读审查**，未修改任何代码、配置、提示词或数据库结构。
> 本文档按照修订后的授权边界原则，**重新评估并部分撤销**第一轮
> `02-web-search-root-cause-analysis.md` 的结论。
> 所有事实均附 `路径:行号`。凡未经验证者，一律标注为**假设**，不作结论。

---

## 0. 本轮最重要的一条更正

第一轮文档在若干处把**固定的数据获取链路与权限控制机制**（统一 `web_search` 入口、
固定的"本地金融 → 本地资讯 → 外部补充"顺序、`external_web_search` /
`financial_data_search` 不对模型直接暴露）描述为"限制模型自主探索的架构缺陷"。

**该定性是错误的，本轮予以撤销。** 经核查，这些机制是 Runtime 刻意设计的
**A 类权限与数据获取硬约束**，必须保留。详见 §5 重分类表与 §6 撤销清单。

---

## 1. 授权机制的实际实现（先定位，不假定结构）

### 1.1 不存在名为"授权模板 / AuthorizationTemplate"的构件

全仓检索 `授权模板|AuthorizationTemplate|authorization_template|authTemplate`，
**没有任何 Java 类、数据库表或列使用该名称**；命中仅出现在测试与文档叙述文本中。

仓库中的 "Template" 概念（`mcp_python_template`、`api_template_execute`、
`TEMPLATE_ID_REQUIRED` 语义角色、`authorizedSqlTemplates`）是
**参数/执行模板（Parameter & Execution Template）**，即"可执行什么形状的调用"，
与"谁有权调用"是两套正交机制。二者在 SQL/API 模板类工具上**叠加**生效：
模板限定可执行的语句形状，授权决定该主体能否执行该工具与该 scope。

因此，本文档统一使用代码中真实存在的名称。**"授权模板"在本仓的真实对应物是
下面两张权限表 + 一个服务端强制执行器**。

### 1.2 真实的授权模型：三层

| 层 | 构件 | 作用 |
| --- | --- | --- |
| L1 工具级授权 | `mcp_tool_permission` 表 + `McpAuthorizationService` | 决定**某主体能否执行某 MCP 工具及其 scope** |
| L2 资源级授权 | `resource_grant` 表 + `ResourceAuthorizationService` | 共享 RBAC 覆盖层，与各域 ACL **取交集** |
| L3 技能/文档范围 | `skill_resource_scope`、`skill_execution_binding` + `SkillExecutionScopePort` | 行级范围（documentIds / tags / roles） |

**L1 — `McpToolPermission`**
`chatchat-enterprise/src/main/java/com/chatchat/enterprise/entity/mcp/McpToolPermission.java:20-44`

| 列 | 含义 |
| --- | --- |
| `tenantId` (:20) | 租户隔离键 |
| `targetType` (:23) | `user` / `role` / `group` |
| `targetId` (:26) | 主体 ID |
| `toolId` (:29) / `localToolName` (:32) | 工具标识 |
| `scopeExpression` (:35) | 形如 `mcp:sql_datasource:execute:query@tenant=t1;domain=db-1;level=read` |
| `effect` (:38) | `allow` / `deny` |
| `enabled` (:41) / `expiresAt` (:44) | 启用与过期 |

**L2 — `resource_grant`**
`database/migration/mysql/V20260922_01__resource_grants.sql`：
`tenant_id, resource_type, resource_id, principal_type(TENANT|USER|ROLE),
principal_id, effect(ALLOW|DENY), enabled, expires_at`；
`V20260929_01__role_agent_resource_grants.sql` 追加 `agent_id`（NULL 表示共享授权）
与索引 `idx_resource_grant_agent (tenant_id, agent_id, principal_id)`。

`ResourceAuthorizationService` 的类注释即
**"Shared RBAC overlay. Domain ACLs are intersected with this result."**
（`chatchat-enterprise/src/main/java/com/chatchat/enterprise/service/ResourceAuthorizationService.java:25`）

### 1.3 与用户 / 角色 / 组织 / Agent 的关联

- 用户与租户：`ResourceAuthorizationService.evaluate` 要求用户存在、租户一致、
  `status=enabled` 才算 `validUser`（`:74-76`）。
- 角色：**服务端从 `sys_user_role` ∩ `sys_role`（`status=enabled`）重新推导**
  （`:77-89`）。注意四个 public 方法的入参名均为 **`ignoredCallerRoles`** —— 调用方
  传入的角色被**刻意丢弃**。这是一条**优秀的安全属性**：主体角色不可由调用方自述。
- Agent：agent 级授权必须存在启用中、agentId 匹配且在生效/过期时间窗内的
  `RoleAgentBinding`（`:98-106`）；且 agent 级规则仅在 `principalType=ROLE`
  且该角色在 `agentRoleIds` 内时才生效（`:109-111`）。
  实体见 `chatchat-enterprise/.../entity/security/RoleAgentBinding.java:26-41`。

### 1.4 默认拒绝与 DENY 优先

- `ResourceAuthorizationService`：候选为空 / 租户空 / 资源类型空 → `Set.of()`（全拒）；
  无任何直接或通配规则 → `continue` 跳过（`:118`）；
  最终 `if (!deny && allow) allowed.add(id);`（`:131`）→ **DENY 覆盖 ALLOW**。
  `principalMatches` 只认 TENANT/USER/ROLE，其余返回 `false`（`:140-148`）。
- `McpAuthorizationService.evaluateAuthorization`：
  显式 DENY → 拒绝（`:261-264`）；无任何 ALLOW → 拒绝（`:265-269`）。**白名单模式**。

---

## 2. 关键判定：服务端可执行约束，还是仅靠 Prompt？

> **结论：是服务端可执行的强制约束（enforced），不是提示词建议（advisory）。**
> 这是本轮最重要的肯定性结论，第一轮未充分体现。

### 2.1 强制执行点（执行前硬停）

| 执行面 | 位置 | 行为 |
| --- | --- | --- |
| HTTP 传输层 | `chatchat-mcp-server/.../mcp/McpInvocationLoggingFilter.java:93-98` | `authorizeToolCalls(requestJson)`；拒绝则 `writeAuthorizationDenied` 并 `return`，**不进入 filterChain** |
| MCP 工具适配器 | `chatchat-mcp-server/.../tool/ToolRegistryMcpAdapter.java:174-178` | 每次调用先 `authorizationService.authorize(toolName, arguments)`，拒绝即 `permissionDeniedResult` |
| gRPC 本地运行时 | `chatchat-mcp-server/.../grpc/LocalMcpRuntimeServiceProvider.java:85-89` | 同样先 authorize，拒绝返回 `REJECTED` / `MCP_TOOL_FORBIDDEN` |

三条入口**全部在工具真正执行之前**完成校验，无一条路径把决定权交给模型或提示词。

### 2.2 失败即拒绝（fail-closed）

`McpAuthorizationService.java`：

- 授权功能未启用 → 拒绝（`:167-169`，注意是 **deny** 而非放行）
- 快照拉取异常 → 拒绝（`:174-178`）
- 快照不可用 → 拒绝（`:198-200`）；快照过期 → 拒绝（`:201-203`）
- 缺少调用者身份 → 拒绝（`:206-208`）；缺少租户上下文 → 拒绝（`:209-211`）
- 租户与同步用户不符 → 拒绝（`:212-214`）
- 不是已同步的数据库用户 → 拒绝（`:215-218`）；用户被禁用 → 拒绝（`:219-222`）
- scope 非法 → 拒绝（`:228-230`）；scope 租户与调用者租户不符 → 拒绝（`:231-233`）
- 工具被禁用 → 拒绝（`:244`）

并且**执行路径永不使用发现期缓存**：
`:70-73` 注释明确 **"The database is the policy authority. Execution never uses the
discovery cache."**，`fetchExecutionSnapshot()` 带 `fresh=true` 实时拉取。

### 2.3 身份不可由模型伪造

`McpAuthorizationService.principal(...)`：

- `:796-799`
  ```
  // An installed transport principal is authoritative, including absent identity fields.
  // Arguments may describe a target user, but cannot impersonate the caller.
  if (context != null) arguments = Map.of();
  ```
  只要传输层装载了 `McpInvocationContext`，**参数整体被清空**，模型写入
  `userId` / `tenantId` / `mcpContext.identity` 一律无效。
- `:820-825` 一旦在快照中解析到用户，**强制使用其规范身份**，参数不能把普通用户的
  username 替换成另一个已同步身份。
- `:833-837` 租户以用户的 `canonicalTenantId` 为准，与请求不符即 `tenantMismatch`。
- `:839-848` 角色从快照重新解析，且必须同租户、状态 enabled。

测试锁定该行为：
`chatchat-mcp-server/src/test/java/com/chatchat/mcpserver/authorization/McpAuthorizationServiceTest.java`
- `cannotInventCallerIdentityInArgumentsWhenEnvelopeIdentityIsMissing`（信封无身份时，
  参数提供的身份被拒绝，`caller.userId()` / `caller.tenantId()` 均为 null）
- `unknownUserCannotImpersonateKnownUserThroughUsername`（`:287-294`，
  未知 userId + 已知 username `admin` → `allowed()` 为 false）
- `discoveryUsesTheSameDenyAndAssetScopeRulesAsExecution`（发现期与执行期同一套
  deny / scope 规则；`domain=db-2` 越界即 false）

### 2.4 多轮探索中权限是否始终有效

有效。因为授权在**每一次工具调用**时重新评估，且每次都重新拉取数据库快照
（`:172`）。不存在"首轮通过后续免检"的会话级缓存授权。

---

## 3. 绕过面排查（补证 / 重试 / 并行 / 内部桥接）

| 绕过面 | 核查结论 | 证据 |
| --- | --- | --- |
| `userFacingTool=false` 内部桥接 | **不构成绕过**。该标志仅用于日志/呈现，授权路径中**不读取**此字段 | `FinalSummaryWebSearchEnhancer.java:275-281`、`InternalFinancialDataSearchExecutor.java:53-73` 设置该标志；授权代码中无任何读取点 |
| 证据补证 | **不构成绕过**，且**额外复检**范围 | `KnowledgeAgentEvidenceSupplement.java:52`（技能须在 `supplementSkillTypes`）、`:56`（`grantedSkill`）、`:58` 用请求的 tenantId/userId 调 `SkillExecutionScopePort.resolve`、`:61-62` 命中 `DENIED_DOCUMENT_ID` 即返回空 |
| 重试 | **不构成绕过**。每次重试都走 `executeOnce`，策略门重新评估 | `ToolRuntimeService.java:1046-1051` |
| 并发 / 异步 | **不构成绕过**。`McpToolConcurrencyManager` 只做限流与熔断，授权发生在它之前 | `McpToolConcurrencyManager.java:70-100`，调用点在 `ToolRegistryMcpAdapter.java:174` 之后 |
| `FinalSummaryWebSearchEnhancer` 发起的 Runtime 自主检索 | **不构成授权绕过**（仍走 `ToolRuntimeService` → MCP 授权），但存在 **D 类越权裁决**问题，见 §5 | `FinalSummaryWebSearchEnhancer.java:105-112` |

**未发现跨用户数据泄漏路径。** `partitionKey()` 以 `tenantId` 开头
（`chatchat-common/.../kernel/KernelDataScope.java:30-31`），
仓储层查询方法普遍带 `tenantId` 前缀条件
（`McpToolPermissionRepository.java:15,24,34-38`）。

### 3.1 需要如实标注的两点保留意见（非结论，待验证）

1. **`properties.isEnabled()` 为 false 时是 deny（`:167-169`）**，设计正确。
   但 `McpAuthorizationProperties` 的默认值与各 profile 的实际取值本轮未逐一核对，
   **建议在部署核查中确认生产 profile 下该值为 true**。属**待验证项**，不作缺陷结论。
2. **Agent 侧 `isDeniedByPolicy` 在 `allowedTools` 为空时放行**
   （`ToolRuntimeService.java:1718-1726`：list 为 null 或 empty 即 `return false`）。
   这是**纵深防御层而非权限权威层**——权威在 MCP 服务端。
   `enforceAllowedTools` 默认 `true`（`ToolRuntimeProperties.java:17`，
   `packaging/config/application-prod.yml:358`）。
   因此**不构成 E 类缺陷**，但"空列表 = 不限制"的语义建议在文档中显式说明，
   避免被误当作权限边界使用。
3. **`KernelDataScope` 进入 kernel 后不再做授权复检**
   （`McpToolExecutor.executeKernel` 接收但不用于鉴权）。
   这是**正确的分层**（授权已在 kernel 边界之前完成），不是缺陷。
   第一轮曾把"`WebSearchExecutionWorkflow` 不使用 `KernelDataScope` 鉴权"列为
   潜在 E 类疑点，**本轮撤销该疑点**。

---

## 4. 重新审查 web_search 的授权覆盖

### 4.1 三类数据源是否都纳入授权？—— 部分纳入，粒度为**工具级而非来源级**

事实链：

1. 模型可见的检索工具只有一个 `web_search`
   （`RemoteNewsMcpToolProvider.java:28-64`；测试
   `RemoteNewsMcpToolProviderTest.java:359-373` `exposesOnlyUnifiedWebSearch` 锁定）。
2. `financial_data_search` 的完整工具契约存在但**故意不加 `@Component`**
   （`FinancialDataMcpToolProvider.java:22-27`，注释："production search exposes only
   web_search and runs this capability as an internal bridge stage, preventing
   independent query rewriting"）。
3. `external_web_search` 同样**故意不注册**
   （`ExternalWebSearchToolExecutor.java:21-27`：
   "callers can reach it only through the public web_search bridge"）。
4. `WebSearchExecutionWorkflow` 在进程内直接调用这两个桥接执行器，
   **其内部不再调用任何 authorize**（通篇无 `authoriz` 匹配；
   `:185-190` 直接 `financialSearch.map(...)`）。

**因此：对 `web_search` 的一条 ALLOW 授权，等价于同时授予
本地资讯 + 本地金融数据 + 外部搜索三类来源的访问。**

这是一个**授权粒度事实**，不是绕过——模型无法在缺少 `web_search` 授权时触达任一来源，
反向越权不存在。但它意味着三类来源**目前无法被独立授权或独立 DENY**。
这一点直接决定了第 5 问与 §7 的拆分建议。

### 4.2 是否存在"默认外部搜索掩盖本地检索"？—— 否，事实相反

`WebSearchExecutionWorkflow.java:93-99` 的固定阶段序列为
`ANALYZE_QUERY → SEARCH_GOVERNED_FINANCIAL_DATA → SEARCH_LOCAL_NEWS →
SUPPLEMENT_EXTERNAL_WEB → MERGE_AND_RANK → VERIFY_EVIDENCE`，
输出中自述 `retrievalOrder` 且 `externalSearchRole="supplementary_fallback"`（`:228-233`）。
外部补充仅在 `externalRequested || forceExternal || !localSufficient` 时触发
（`WebSearchToolExecutor.java:116-125`）。

**在 `web_search` 路径上，"模型绕过本地数据直接联网"在结构上不可能发生。**
第一轮已给出该结论，本轮**再次确认并强化**：这正是 A 类固定数据获取流程在起作用。

### 4.3 "只调用外部搜索"的现象归因（修订后）

按修订后的框架，候选归因重新排序为：

| 候选 | 评估 |
| --- | --- |
| 授权配置 | **可能**。若租户未被授予 `web_search` 以外的任何检索类工具，模型本就只有这一个入口；若金融数据能力未配置，`web_search` 内部金融阶段会 `skippedReason="capability_unavailable"` |
| 能力暴露 | **已确认存在问题**。Planner 提示词把 `web_search` 重述为"本地资讯 + 外部补充"，**完全未提及金融数据**（`AgentPlannerPromptBuilder.java:248-250`） |
| 工具接口 | **已确认存在问题**。单一工具吞并三种来源语义，模型无法表达来源意图（但这是 A 类设计的**副作用**，整改手段受限，见 §7） |
| 数据质量 | **待验证**。本地资讯索引 `retention-days: 7`，结构上无法覆盖"最近一个月" |
| 模型选择 | **在 `web_search` 路径上不成立**——模型没有"只选外部"的选项 |

### 4.4 拆分后能否保持原有数据访问权限？

**不能自动保持——授权以工具名为键。** `mcp_tool_permission.localToolName`（`:32`）
与 `resource_grant.resource_id` 均按名称匹配；现网授权行指向 `web_search`。
若新增三个独立工具名，这些授权**不匹配新名称 → 白名单模式下默认拒绝**。

但仓库**已内置**解决该问题的一流机制：
`McpDynamicCapabilityRoute`（`chatchat-common/.../mcp/capability/McpDynamicCapabilityRoute.java:18-67`，
`ROUTING_MODE_PARENT_DELEGATION = "parent_delegation"`，`:24`）配合
`McpAuthorizationService.delegatedAuthorizationTool(...)`（`:273-287`）：
父工具被调用时，若参数中的身份字段指向某子工具，**授权改按子工具名评估**。
测试已锁定该语义（`McpAuthorizationServiceTest.java:266-284`：
父 `api_template_query` + `_templateQueryChildToolName=customer_service_template_query`
→ allow；换成 `unrelated_parent` → false）。

这使得"能力表达拆分"与"权限粒度细化"可以在**不新增绕过面**的前提下同时达成，
且身份参数被强制使用保留的 `_` 命名空间（`:31-33`）。

---

## 5. 硬编码重分类（A–E）

### A 类 — 权限与数据获取硬约束（**必须保留**）

| # | 机制 | 位置 |
| --- | --- | --- |
| A1 | MCP 执行前授权，三入口全覆盖、fail-closed、白名单、DENY 优先 | `McpInvocationLoggingFilter.java:93-98`；`ToolRegistryMcpAdapter.java:174-178`；`LocalMcpRuntimeServiceProvider.java:85-89`；`McpAuthorizationService.java:167-269` |
| A2 | 执行期永不用发现缓存，数据库为策略权威 | `McpAuthorizationService.java:70-73,172` |
| A3 | 传输层身份权威，参数不可冒充调用者 | `McpAuthorizationService.java:796-799,820-837` |
| A4 | 角色服务端重新推导，`ignoredCallerRoles` | `ResourceAuthorizationService.java:77-89` 及四个 public 方法签名 |
| A5 | RBAC 覆盖层与域 ACL 取交集、默认拒绝、DENY 覆盖 ALLOW | `ResourceAuthorizationService.java:25,118,131,140-148` |
| A6 | 租户隔离（scope 租户校验、`partitionKey`、仓储层 tenantId 条件） | `McpAuthorizationService.java:231-233`；`KernelDataScope.java:30-31`；`McpToolPermissionRepository.java:15,24,34-38` |
| A7 | **`web_search` 统一入口 + 固定来源顺序 + 内部桥接不独立暴露** | `WebSearchExecutionWorkflow.java:93-99`；`FinancialDataMcpToolProvider.java:22-27`；`ExternalWebSearchToolExecutor.java:21-27` |
| A8 | 外部检索不直传用户原问题（防数据外泄） | `ExternalWebSearchToolExecutor.java:52-55` |
| A9 | 外部研究算子的私网/本机地址过滤、公网主机校验（SSRF 防护） | `GovernedExternalResearchOperator.java:102-108` |
| A10 | SQL/模板类工具的数据源与模板服务端白名单 | `SqlMcpToolPublisher.java:751,791`（`authorizedSqlTemplates`） |
| A11 | 技能/文档行级范围解析，补证路径复检 | `SkillExecutionScopePort`；`KnowledgeAgentEvidenceSupplement.java:52-62` |

### B 类 — Runtime 合理执行约束（**原则保留**）

| # | 机制 | 位置 |
| --- | --- | --- |
| B1 | 并发/限流/熔断 | `McpToolConcurrencyManager.java:70-100` |
| B2 | 快照过期 TTL 与不可用重试间隔 | `McpAuthorizationService.java:201-203,117-125` |
| B3 | 工具日志摘要长度上限（审计成本控制） | `ToolLogSummarizer.java:17-21` |
| B4 | Agent 侧 `allowedTools` 纵深防御门 | `ToolRuntimeService.java:1718-1726`；`ToolRuntimeProperties.java:17` |
| B5 | 证据/引用条数与长度预算（资源预算） | `AgentTaskService.java:88,2992`；`KnowledgeAgentEvidenceSupplement.java:78` |

> B5 **保留配额本身**，但其"如何向模型表达"存在 C 类问题，见 C3。

### C 类 — 授权范围内不合理的探索限制（**重点整改**）

| # | 问题 | 位置 | 说明 |
| --- | --- | --- | --- |
| C1 | Planner 提示词把 `web_search` 描述为"本地资讯 + 外部补充"，**完全不提本地金融数据** | `AgentPlannerPromptBuilder.java:248-250` | 模型对一项**已授权且会被实际执行**的能力完全无感知，无法据其制定分析策略。这是**能力表达缺陷**，与权限无关，整改不触碰权限机制 |
| C2 | 提示词"不要臆造 dataset 代码"（`:236`）与 `dataset` 参数、`capabilityManifest` 中 `selectionAuthority:"MODEL"` 冲突 | `AgentPlannerPromptBuilder.java:236`；`WebSearchExecutionWorkflow.java:38-70` | 契约自相矛盾，模型倾向于回避合法参数 |
| C3 | 本地资讯证据在审计摘要中被压成 `newsCount`，丢失 title/url/publishTime/sourceName | `ToolLogSummarizer.java:77-155` | 同一审计记录保留完整 `financialFacts`，却抹掉资讯条目的可引用字段，削弱模型的来源追溯与去重能力 |
| C4 | `GovernedExternalResearchOperator` 要求公网 http/https URL，导致**无 URL 的本地金融结构化行**被丢弃 | `GovernedExternalResearchOperator.java:73-79` | URL 过滤本身是 A9 安全控制，**必须保留**；但把它用作**本地证据的准入条件**是封装缺陷。整改方向是区分"外部 URL 安全校验"与"本地证据封装"，不是放松校验 |
| C5 | 本地资讯索引 `retention-days: 7` | `application.yml` 检索配置 | 结构上无法覆盖"最近一个月"类问题。属**数据覆盖**问题；且该覆盖情况**未向模型暴露**，模型无法据此判断是否需要补充外部检索 |

### D 类 — Runtime 越权进行业务分析裁决（**重点整改**）

| # | 问题 | 位置 | 说明 |
| --- | --- | --- | --- |
| D1 | `FinalSummaryWebSearchEnhancer` 由 Runtime 自行发起补充检索，且**只承认腾讯 WSA 来源的证据**（`containsTencentWsaEvidence`），本地金融数据不计入充分性判断 | `FinalSummaryWebSearchEnhancer.java:110-112`；`InternalFinancialDataSearchExecutor.java:59-62` | Runtime 代替模型裁定"何种证据有业务价值"、"证据是否足够"。这是典型的 D 类越权，**本轮维持该结论** |
| D2 | 同一增强器仅传单个 `query` 关键词而非模型给出的 `queryTerms` | `FinalSummaryWebSearchEnhancer.java:265-269` | Runtime 改写了模型的检索意图 |

> D1/D2 的整改**不涉及任何权限放宽**：该增强器本身仍走标准 MCP 授权
> （见 §3 表格），整改只针对"谁来判定证据充分性"。

### E 类 — 授权边界缺陷（**最高优先级**）

**本轮未发现 E 类缺陷。**

- 未发现工具绕过权限的路径（§3 全部绕过面均证伪）。
- 未发现"仅靠提示词约束"的权限（§2 证明为服务端强制）。
- 未发现跨用户/跨租户数据泄漏路径。
- 补证路径不仅未越权，反而**额外复检**范围（`KnowledgeAgentEvidenceSupplement.java:58-62`）。

**唯一需要跟进的"非缺陷观察项"**：§4.1 的**授权粒度为工具级**——
三类数据来源无法被独立 ALLOW/DENY。这不是绕过，但若未来需要
"某角色可查本地资讯、不可外网检索"这类策略，当前模型无法表达。
列为**能力缺口**而非安全缺陷。

---

## 6. 第一轮结论的撤销与维持清单

| 第一轮条目 | 本轮裁定 | 理由 |
| --- | --- | --- |
| ① 单一工具吞并三种来源语义，模型无法表达来源意图 | **部分撤销 → 改判 A7 + C1** | "统一入口"本身是 A 类权限与数据获取硬约束，**不是缺陷**；真正可整改的只有"能力描述不充分"（C1/C2） |
| ② Planner 提示词遗漏金融数据能力、与 `dataset` 参数冲突 | **维持 → C1 / C2** | 纯能力表达问题，不触碰权限 |
| ③ `FinalSummaryWebSearchEnhancer` 只认 WSA 证据、不读金融数据 | **维持 → D1 / D2** | Runtime 越权裁决证据业务价值 |
| ④ `GovernedExternalResearchOperator` 要求公网 URL 导致本地行被丢弃 | **部分撤销 → A9 + C4** | URL/私网过滤是必须保留的安全控制；仅"用它过滤本地证据"属封装缺陷 |
| ⑤ `ToolLogSummarizer` 把本地资讯压成 `newsCount` | **维持 → C3** | 上下文信息丢失 |
| ⑥ Semantic Policy 缺少来源级角色 | **撤销定性为"缺陷"→ 改列为能力缺口** | 语义角色服务于工具发现与分类；来源级粒度缺失的根因是 A7 的工具级设计，须与授权粒度一并考虑（§7），不应单独作为缺陷整改 |
| ⑦ 本地资讯 `retention-days: 7`、`minimum-local-results: 3` | **降级维持 → C5（覆盖情况未暴露给模型）+ B 类（阈值本身是资源预算）** | 阈值是合理执行约束；真正的问题是**覆盖情况对模型不可见** |
| ⑧ 证据/引用条数与长度上限 | **部分撤销 → B5** | 预算本身合理保留；仅"把资源上限包装成分析已完成"需要整改（与 D1 同源） |
| ⑨ 疑点：`WebSearchExecutionWorkflow` 接收但未使用 `KernelDataScope`，疑似 E 类 | **撤销** | 授权在 kernel 边界之前已完成，kernel 内不复检是正确分层 |
| ⑩ 假设 H6：`FinancialEnrichmentService` 为 `Optional`，若生产未装配则本地金融数据从不检索 | **维持为最高优先级待验证假设** | `WebSearchExecutionWorkflow.java:29-34`。若成立，可在模型零参与的情况下完整复现所报现象 |

---

## 7. 在不改变权限架构前提下的改进方向（仅建议，本轮不实施）

> 约束：**工具拆分属于能力表达优化，不得改变权限机制和数据获取流程。**

**推荐路径：父委派式能力表达（Parent-Delegation Capability Expression）**

保留 `web_search` 作为唯一执行入口与固定数据获取链路（A7 不变），
利用仓库已有的 `McpDynamicCapabilityRoute` / `parent_delegation`
（`McpDynamicCapabilityRoute.java:18-67`；`McpAuthorizationService.java:273-287`）
把三类来源表达为**可被独立授权的子能力标识**，而非三个独立的执行入口。

该路径同时满足：

- 权限机制不变：仍由 `McpAuthorizationService` 在执行前裁决，且**授权粒度从工具级
  细化到来源级**，补上 §5「E 类观察项」的能力缺口。
- 数据获取流程不变：执行仍落在同一个 `WebSearchExecutionWorkflow`，
  固定顺序与外部补充角色不变。
- 兼容性可控：现网 `web_search` 授权行保持有效；来源级授权缺省继承父工具，
  需要细分时再下发子工具授权行。
- 不新增绕过面：子能力不注册为可独立执行的 MCP 工具，`external_web_search`
  仍然无法被直接调用（`ExternalWebSearchToolExecutor.java:21-27` 不变）。

**配套的 C/D 类整改（均不触碰权限）**

1. C1/C2：修正 Planner 提示词，如实列出 `web_search` 实际包含的三类来源及其角色；
   消除 `:236` 与 `dataset` 参数的契约冲突。
2. C5：把**数据覆盖元信息**（本地资讯索引保留窗口、金融数据集时间范围、
   本次各来源命中数与时间跨度）作为结构化字段返回给模型，
   由**模型自行判断**是否需要追加外部检索——Runtime 只提供事实，不下结论。
3. C3：审计摘要保留本地资讯条目的 title/url/publishTime/sourceName。
4. C4：区分"外部 URL 安全校验"（保留）与"本地证据封装"（不应要求 URL）。
5. D1/D2：`FinalSummaryWebSearchEnhancer` 的充分性判定应纳入本地金融与本地资讯证据，
   或将"证据是否充分"的裁决交还模型；传递模型原始 `queryTerms` 而非单一关键词。
6. 统一证据封装协议：为三类来源标注 `sourceType / retrievedAt / publishTime /
   coverageWindow / conflictsWith`，使"旧资讯被误当最新"与"外部摘要覆盖本地结构化数据"
   在**数据层面可被模型识别**，而非由 Runtime 代为排序裁决。

---

## 8. 七问明确回答

**1. 授权模板是否真正构成模型探索边界？**
是，但其真实实现不叫"授权模板"，而是
`mcp_tool_permission`（工具级）+ `resource_grant`（RBAC 覆盖层）+
`skill_resource_scope` / `skill_execution_binding`（行级范围）三层，
由 `McpAuthorizationService` 与 `ResourceAuthorizationService` 在**服务端强制执行**。
它构成了真实的、不可由模型突破的探索边界。

**2. 固定数据获取链路是否存在权限绕过风险？**
**未发现。** 三条调用入口均在执行前授权；快照失败、过期、身份缺失一律拒绝；
传输层身份权威使参数伪造无效；补证/重试/并行/内部桥接四类绕过面全部证伪（§3）。
唯一需要部署侧确认的是生产 profile 下 `chatchat.mcp.authorization.enabled` 为 true
（该值为 false 时是 deny 而非放行，风险方向安全）。

**3. 模型是否能够充分利用全部已授权能力？**
**不能，原因是能力表达而非权限。** 最突出的是本地金融数据检索：它**已被授权且会被
实际执行**，但 Planner 提示词从未告知模型它的存在（`AgentPlannerPromptBuilder.java:248-250`），
模型因而无法在分析策略中加以利用，也无法在报告中正确归因。
其次是数据覆盖情况（索引保留窗口、各来源命中与时间跨度）未向模型暴露。

**4. 哪些硬编码是必须保留的？**
§5 的 A 类全部 11 项（MCP 执行前授权、数据库为策略权威、传输层身份权威、
服务端角色重推导、默认拒绝与 DENY 优先、租户隔离、`web_search` 统一入口与固定来源顺序、
外部检索不直传原问题、SSRF 防护、SQL 模板白名单、技能行级范围）
加 B 类执行约束。**第一轮把其中若干项误判为缺陷，本轮已撤销（§6）。**

**5. 哪些硬编码不必要地限制了授权范围内的分析自由？**
C 类 5 项（提示词遗漏金融能力、契约自相矛盾、资讯证据被压成计数、
安全 URL 过滤误用于本地证据准入、数据覆盖对模型不可见）
与 D 类 2 项（Runtime 以 WSA 单一来源裁定证据充分性、改写模型检索意图）。
这些整改**全部不涉及权限放宽**。

**6. web_search 的三种数据源是否正确受授权体系管理？**
**受管，但粒度是工具级而非来源级。** 一条 `web_search` 的 ALLOW 授权等价于同时授予
三类来源；三者无法被独立 ALLOW/DENY（§4.1）。
反向越权不存在——无 `web_search` 授权则任一来源均不可达，
`external_web_search` / `financial_data_search` 均未注册为可独立调用的工具。
故这是**能力缺口，不是安全缺陷**。

**7. 如何在不改变权限架构的前提下提升模型自主探索质量？**
采用 §7 的父委派式能力表达：保留统一执行入口与固定数据获取链路，
用仓库已有的 `parent_delegation` 机制把三类来源表达为可独立授权的子能力；
同时完成 C/D 类整改，核心原则是
**Runtime 如实提供来源语义、覆盖元信息、时效标识与冲突信号，
把"证据是否充分、是否继续探索、如何归因"的判断交还模型。**

---

## 9. 本轮无法验证的部分（如实声明）

仓库中**不存在**运行日志、执行轨迹、`web_search` 调用录制或引用转录，
因此以下内容**本轮仍无法验证，且不予编造**：

- 生产环境中模型实际收到的工具定义文本。
- 任何一次真实调用的参数、各来源返回条数与时间覆盖。
- 本地证据进入上下文后是否被截断的实际发生率。
- 假设 H6（`FinancialEnrichmentService` 是否在生产装配）的真实取值。

**最小验证方案**（与第一轮 `02` 文档 §6 一致，优先级重排）：

1. 在预发环境打印一次完整的 Planner 工具清单文本，核对金融数据能力是否可见。
2. 对同一问题各执行一次，记录 `WebSearchExecutionWorkflow` 输出中的
   `financialSearchQuery` / `skippedReason` / `retrievalOrder`，验证 H6。
3. 以非管理员、仅授予 `web_search` 的测试账号调用一次，
   并以无任何授权的账号各调用一次三类来源，确认白名单与默认拒绝的实际表现。
4. 复用现有测试 `McpAuthorizationServiceTest`、`RemoteNewsMcpToolProviderTest`
   作为授权与暴露面的回归基线。
