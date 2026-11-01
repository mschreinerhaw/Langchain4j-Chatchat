# 普通 Agent 实测

Agent：**SQL Template E2E Acceptance Agent**，ID `sql_template_e2e_20261101_r1`，模型 deepseek-v4-flash。系统提示词只有“根据用户问题和已授权能力回答，结论须有实际数据依据；信息不足时明确说明并追问。”无工具名、商品号、业务字段或固定依赖步骤。工作流为空步骤，通过正常模板绑定及发布授予四项能力。

每例由实际 Agent 管理页“对话使用”提交原问题；配置、提交、任务/会话ID、原始问句、事件、工具选择/参数/返回、最终答案和截图都在用例目录。独立协议客户端不作为 Agent 执行证据。

| 用例 | 最终判定及原始证据 |
| --- | --- |
| A01 无参数历史回归 | [PASS，6行42字段](cases/A01/verdict.md) |
| A02 参数查询 | [PASS，仓库2、5行25字段](cases/A02/after-r6/verdict.md)；[商品2的12个月84字段](cases/A02-productdata/after-r9/verdict.md) |
| A03 缺少期间 | [PASS，追问且无SQL](cases/A03/after-r2/verdict.md)，原错误扩展期间记录保留 |
| A04 非法参数 | [PASS，执行前拒绝](cases/A04/verdict.md) |
| A05 参数变化 | [PASS，仓库4/Books/下限500/不同期间，3行15字段](cases/A05/after-r6/verdict.md) |
| A06 依赖工具 | [PASS，两个SQL成功及模型绑定](cases/A06/after-r9/verdict.md)，原失败记录保留 |
| A07 未授权 | [PASS，零绑定Agent不能调用](cases/A07/verdict.md)，独立MCP另证实403 |
| A08 下线 | [PASS，不替换能力、不查询、保留能力不足答复](cases/A08/after-r7/verdict.md) |
| A09 新业务 | [PASS，普通Agent自行选择仓储分布](cases/A09/after-r6/verdict.md) |
| A10 空数据 | [PASS，真实0行并明确说明](cases/A10-empty/after-r6/verdict.md) |
| A10 无匹配 | [PASS，不用零售数据冒充金融客户数据](cases/A10-unmatched/after-r7/verdict.md) |

A06 的[实际计划](cases/A06/after-r9/agent-plan.json)声明从步骤1的 $.data.data.rows[0].global_min_warehouse_sk 绑定到步骤2的 warehouse_id，步骤2静态input没有仓库编号。实际返回全局最小值1，随后有效参数为1，两份结果与独立JDBC一致，[自动比较](cases/A06/after-r9/answer-and-binding-comparison.json)通过。此处全局聚合由已验证SQL提供；没有声称Java推理节点能执行任意中间程序。

PARTIAL 按问题和数据限制评估：A06/C01/C02有实际结果与可用分析，缺失日期、未知单位等限制明确保留；A08/A10无匹配属于正确拒绝。它们的功能验收PASS不等于所有业务含义已认证。

[真实角色/空间与对象授权测试](workspace-role-isolation.json)：受限角色登录看不到Studio空间，伪造空间头或用户参数不能读取；授予Studio权限后可访问空间，但仍不能读取另一用户的工程。全程复用既有权限机制，临时用户和角色已删除。
