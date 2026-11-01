# 修复、检查、部署与复现

专项分支 `fix/sql-template-e2e-remediation`。本轮生产源码最终提交 `fe7dc9e7a`；后续证据提交不改变已部署二进制。

| 提交 | 作用 |
| --- | --- |
| 96c8ca17d | 同一关系库查询入口投影Studio模板，纠正旧完成结论 |
| 371cd3afc | 受信Prepared SQL、日期依据、参数来源及实际Agent范围 |
| b6489bf24 | 可信试执行SQL首尾空白兼容 |
| 3e3d398e5 | 日期格式空值、单一分析目标、PARTIAL正式传播 |
| 577f5b6d7 | 解包实际MCP/ToolOutput，交付SQL来源 |
| 8f144eb0f | READY计划允许未使用的clarificationQuestion为空 |
| 22f4e8913 | JDBC实际绑定参数名称及顺序 |
| f6f24b602 | 拒绝未执行推理节点的虚构输出；保留合法能力缺口答复 |
| 0e5f688fc | 只发布试执行验证的列名，无样例值；目录业务文案 |
| 54711f174 | 模型意图风险与实际工具治理分开；部署API本地MCP就绪检查 |
| fe7dc9e7a | 明确manifest storageName/presence/原生TEXT通用传输契约 |

Studio SQL由服务端编译为占位符与独立绑定值，经既有工作流、授权、表白名单、只读及结果限制后使用JDBC PreparedStatement；JSON不能构造受信计划。旧手工模板和原有Java领域技能编译保留原路径。[范围明确的新增代码审计](production-no-scenario-hardcoding-audit.json)保留文件哈希和边界，没有增加验收仓库、商品、字段或答案的生产Java分支。

模型整体intent风险仅供审计，工具执行继续依据已发布工具元数据、租户策略和显式确认要求，未降低高风险或强制确认规则。全局最小仓库由已验证SQL计算，Java只执行声明的依赖绑定，不声称可以执行任意reasoning代码。

## 部署及检查

最终API镜像 `runtime-os-api:sql-template-remediation-20261101-r9`，JAR SHA256 `59713b76cf180a2c125976cac5c5c7e317c422b6c6031b9ed73c0baf041663d9`。

最终MCP镜像 `runtime-os-mcp:sql-template-remediation-20261101-r4`，JAR SHA256 `634a3e9541103aeef3c1f5b2a39f4506e5818aaf139a174e71b9eb409c2bc7cd`。

Coding worker仍为 `runtime-os-coding-agent:agent-skills-microsoft-20261031-r1`，镜像 `sha256:8033635169a6695010b4a6c6ce0d1f177e697c9f52560b5a668b0b068ef6def5`，未改变Sandbox边界。无数据库迁移。[r9回执](deployment-r9.json)记录备份、对话/任务索引及其他容器不变，API本地目录HEALTHY/36 tools。中间部署和失败原件均保留。

`scripts/deploy-studio-remediation.py`在有活动Runtime/Coding/Python作业时拒绝切换。可选远程私有 `catalog-readiness-private.json`提供username/password，部署manifest提供非空catalogReadinessExpectedTools，正常登录后轮询API-side recovery状态与工具目录。凭据不纳入Git，私有目录700/文件600；不只检查端口连通。

专项检查：首轮88项；Prepared SQL37项；相关回归87项；最终数据交付/覆盖/契约/工作流47项；最终工具治理7项；CodingAnalysisService39项；MCP发布3项；原生技能资源2项。不同轮次有重叠，不相加冒充独立测试总数。最终API/MCP打包和前端构建成功。

原有AgentPlannerTest的9项失败与改动前源码执行结果相同，[基线对照](planner-baseline-comparison.json)保留。不能宣称全仓库测试全绿，真实业务验收以各用例原件为准。

## 离线重复证据核验

仓库根目录运行，需要Node.js：

```powershell
node scripts/sql-tpcds-acceptance/verify-warehouse-protocol.mjs docs/sql-template-remediation/mcp-final-r9.json docs/sql-template-remediation/compatible-geography/independent-jdbc.json docs/sql-template-remediation/catalog-final-pagination/catalog-responses.json docs/sql-template-remediation/mcp-final-r9-comparison.json
node scripts/sql-tpcds-acceptance/verify-final-acceptance.mjs
```

第一项核对真实MCP返回、Schema、release、绑定模式、完整行与独立JDBC；第二项核对实际模型依赖计划、回答表格/合计、商品2协议调用、实际报告表格/动态图值、上传文件哈希及页面渲染回执。脚本为验收夹具，含本轮问题的字段映射，不参与生产业务执行。

## 真实环境复验

用户界面入口与可复制原问题见 [最终验收](08-final-acceptance.md)。普通Agent采用通用提示词，用户问题不指定工具名；每例包含任务/会话ID、原始问题、模型轨迹和返回。

- `scripts/sql-tpcds-acceptance/studio-scenario.mjs`：设置STUDIO_ORIGIN、STUDIO_AUTH_FILE（正常登录响应含token）、STUDIO_SCENARIO、STUDIO_OUTPUT，配置示例见 [scenario.json](blind-business/scenario.json)。依次setup、generate geography、status geography、apply geography、draft geography；正常审批精确数据源模板编码后再test geography、publish geography，inventory同理。模型完成后才能apply，真实试执行成功后才能publish。新的输出目录/工程名避免覆盖归档现场。
- `scripts/sql-tpcds-acceptance/SqlMcpProtocolClient.java`：正常登录身份、可访问Agent和生产签名类执行initialize/list/call/close，鉴权配置存放私有文件。归档不含凭据/签名；客户端不是Agent执行器。
- `scripts/sql-tpcds-acceptance/TpcdsJdbc.java`：独立只读数据库基准，配置由环境私有提供，不从模型答案生成期望值。
- `chatchat-api/web-app/scripts/test-sql-studio-catalog-live.mjs`：真实同一管理页搜索、来源筛选、分页及详情。
- `chatchat-api/web-app/scripts/test-sql-agent-conversation-live.mjs`：真实管理页进入对话，提交自然语言，归档轨迹、代码、上传产物及页面渲染。脚本顶部列明环境配置，登录文件不入Git。
- `scripts/sql-tpcds-acceptance/verify-workspace-isolation-live.mjs`：设置STUDIO_ORIGIN、STUDIO_AUTH_FILE后运行。仅创建自己的临时角色/用户，实际登录、授权刷新、伪造参数及对象隔离检查，finally清理临时对象。要求管理员及本轮工程存在，不修改既有角色。
- `reconcile.mjs`、`verify-report-table.mjs`、`archive-agent-case.mjs`分别执行原始数据对账、报告字段对账及证据装配。装配不自动推断PASS；负向用例明确NOT_EXECUTED。

更新/下线/回滚均经正常发布接口实测；同一活跃快照两个并发发布请求保持同一release，过期写入被拒。不冒称已完成不同草稿高并发竞争、任意业务覆盖或生产容量压力认证。
