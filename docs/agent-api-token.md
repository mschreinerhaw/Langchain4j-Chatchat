# Agent API 令牌

Agent API 令牌用于从控制台或自动化程序调用已发布 Agent。它与网页登录会话相互独立，不能用于系统管理接口。

## 管理方式

平台管理员进入“系统管理 → 用户管理”，点击目标用户行中的轻量 `API` 按钮，可以：

- 设置令牌名称和有效期（1 天、7 天、30 天、90 天、1 年或永久）；
- 生成新令牌；
- 重置令牌，使旧值立即失效；
- 吊销令牌；
- 查看状态、过期时间、最后使用时间和累计使用次数。

令牌明文只在生成或重置成功后返回一次。数据库仅保存 SHA-256 摘要和掩码，无法从数据库恢复明文。

## 权限模型

- 令牌绑定平台用户和租户，实时继承用户当前角色及角色绑定的 Agent；
- 用户被停用、角色被停用、用户与角色解绑、Agent 授权失效后，对应访问立即失效；
- 只有平台管理员可以生成、查询、重置或吊销令牌；
- `ccat_` 令牌仅允许访问已发布 Agent 的提问、状态和最终答案接口；
- 管理接口、curl 示例接口及其他业务接口拒绝 Agent API 令牌。

## 可调用接口

```text
POST /api/v1/published-agents/{agentId}/questions
GET  /api/v1/published-agents/{agentId}/questions/{taskId}/status
GET  /api/v1/published-agents/{agentId}/questions/{taskId}/answer
```

请求使用 `Authorization: Bearer <Agent API Token>`。Agent 管理页已发布 Agent 行中的 `API` 按钮会生成包含上述三个步骤的完整 curl 示例。

## 审计和升级

生成、重置、吊销及每次认证均写入系统审计日志。登录审计页面可选择“Agent API 认证”查看请求用户、租户、IP、接口路径和认证结果。

已有数据库升级时执行对应数据库的 `V20260829_01__agent_api_tokens.sql`。旧嵌入登录端点和前端入口已经移除，旧嵌入令牌不再进入登录认证链；原表和历史日志暂时保留，便于审计和兼容升级。
