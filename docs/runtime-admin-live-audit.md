# Runtime / MCP 联调盘点（2026-09-29）

## 已定位的问题

1. 无工具 Agent 的入口已验证可直接回答；专业入口在模型运行超过 30 秒、90 秒时仍保持运行，先前的入口超时问题未在本轮复现。
2. 专业 Agent 绑定的必需工具被候选授权过滤清空。线上 admin 具有数据库中启用的 `SUPER_ADMIN` 角色，MCP 同步角色正确，但工具检索、API 执行策略、MCP 执行策略都要求额外的逐条工具授权。这与超级管理员语义冲突。
3. 过滤可执行工具时同时丢掉了工作流的必需工具约束，导致没有执行工具的文字回答被标记为 SUCCESS。因此不能把之前专业任务的 SUCCESS 当成数据分析已通过。
4. 动态模板查询子能力在有下一页时发布 `pagination.nextCursor`，而统一结果协议要求 `nextPageToken`，导致 `hasMore requires nextPageToken`。生产者现发布标准分页字段并接受 `pageToken/pageSize`，保留旧游标别名兼容。
5. 结果修复器把统一传输失败信封当作普通结构化数据，错误标记为 `REPAIRED`。修复器现保留失败证据并返回 FAILED；执行工作流保留原调用错误，不能通过格式归一化变成成功。
6. 原客户问题实际完成执行并生成报告后，主张审计记录 `claimCoverageStatus=FAIL`、覆盖率 0.243，但任务仍为 SUCCESS。统一结果评估现将“执行完成但主张审计失败”判为 `PARTIAL_SUCCESS`，保留报告并标记缺少 `evidence_verify`；取消和真实失败不会被降级覆盖。

## 修复边界

- `McpAdministratorPolicy` 统一有效租户、角色状态及 `SUPER_ADMIN` 角色码判断；应用端使用数据库用户和角色绑定，MCP 使用同步用户的实际角色绑定。请求传入的角色码和 admin 用户名不构成提权依据。
- 超级管理员无需逐条 MCP 执行授权；普通用户沿用现有授权逻辑。Agent 所选能力、工具启用状态、发布契约和执行治理仍各自生效。
- `ToolPolicy.requiredCapabilityGaps` 保留无法执行的必需能力，准入失败返回 `NO_EXECUTABLE_PLAN`，不能通过删掉约束退化为成功文字回答。
- 临时授予的三个工具权限已删除；MCP 同步快照也确认不再包含这些临时授权。后续超级管理员验收不依赖临时权限。
- 原专业 Agent 能沿服务资产发布契约进入动态模板子能力，未修改其生产绑定。只有“未直接绑定通用模板查询工具”不能断定配置缺失。

本文件记录联调发现及下述部署验证。未记录密码、Token 或客户数据内容。

## 本地验证限制

扩展运行 `ApiUserRoleScheduleMcpAuthorizationIntegrationTest` 时，Spring 启动失败：
`SkillProtocolPersistenceTest.Config` 与 `ChatChatApplication` 同时注册
`skillDataBindingRepository`，触发 `BeanDefinitionOverrideException`。
该测试未进入业务断言，不计为通过；未为绕过冲突开启全局 Bean 覆盖。
同轮入口与授权单元测试通过，MCP 独立测试和服务器联调继续执行。

## 真实环境验证

- 第一批权限修复已于 22:26 启动：API 与 MCP 健康，admin 同步角色为启用的 SUPER_ADMIN，临时执行授权数为零。
- admin 的服务资产查询返回 10 条资产，通用模板查询返回 5 个候选；无额外逐条授权。
- 无工具对话任务 `c40fc697-fcdb-30c9-ba76-84f0d983ac13` 直接回答，零工具调用；测试 Agent 已删除。
- 原客户问题任务 `c2fe6f2a-89f6-3ab1-a1e0-704d8036a298` 沿服务资产查询、发布的客户模板子能力、模板执行完成 InterpretationPlan。DAG 为 10 节点、20 条边，4 个执行步骤成功，随后完成 6 组分析并生成报告；总耗时约 710 秒，入口未提前结束。主张审计失败说明该报告不能作为“全部结论已核验”的验收证据。
- 资产问题任务 `cf7c8bb6-7f41-3c30-b852-2b233b7b5ce7` 完成资产与模板检索并返回说明，回答区分平台已发现的数据基础与通用方法；未找到专属产品功能定义。
- 生产 Agent 绑定及模型配置均未修改。历史任务状态不回写。

## 最终部署与验证（22:49–22:50）

- API PID `1247094`、MCP PID `1247154`，启动成功、API 健康状态 UP；部署目录与配置文件保持原路径。
- 最终 API JAR SHA-256：`f7043435170238728665d26f158e54b7f6df1d500e032e60d863cd5e2c96855d`。
- 最终 MCP JAR SHA-256：`eb09923c506f3e2439df6ba2e450940b2b549ab72cd8c73f8bfdb69e2e96ec8c`。
- 回滚备份：`/opt/chatchat-deploy-backup/runtime-contract-20260929/`；更早版本备份继续保留。
- 无临时授权的 admin 再次成功获取 10 条服务资产。
- 动态模板子能力经统一 MCP Runtime 连续获取 5 条、3 条模板，两个页面无重复；标准 `pageToken/nextPageToken` 生效。
- 非法游标返回 `FAILED / MCP_TOOL_EXECUTION_FAILED`，不再被修复成 REPAIRED。
- 必需工具缺失用例 `665ebdb4-a50a-357b-8a27-4c41abcf48a4` 在约 1.7 秒内返回 `NO_PRESENTABLE_RESULT`，零工具调用，没有退化成成功分析。
- 临时授权及临时 Agent 已清理；联调结束时运行中的任务数为零。

本轮定向回归合计 138 项通过（入口/候选 53、API 权限 14、MCP 权限与绑定 40、分页与修复器 15、结果评估与执行作用域 16），Maven reactor 打包成功。上文的启动级集成测试配置冲突仍是独立验证限制。

最终证据审计降级规则通过单元回归；约 12 分钟的真实客户报告是在该降级规则部署前生成的，未为改变历史状态而重放或回写。取数与执行链路恢复不代表报告所有主张均已核验；本轮没有伪造证据引用或提高审计覆盖率。
