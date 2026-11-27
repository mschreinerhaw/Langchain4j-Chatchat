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
- `ccat_` 令牌仅允许访问已发布 Agent 的附件、提问、状态、取消、答案和报告接口；
- 管理接口、curl 示例接口及其他业务接口拒绝 Agent API 令牌。

## 可调用接口

```text
POST /api/v1/published-agents/{agentId}/questions
POST /api/v1/published-agents/{agentId}/attachments
GET  /api/v1/published-agents/{agentId}/questions/{taskId}/status
GET  /api/v1/published-agents/{agentId}/questions/{taskId}/answer
GET  /api/v1/published-agents/{agentId}/questions/{taskId}/report
GET  /api/v1/published-agents/{agentId}/questions/{taskId}/report?bundle=true
DELETE /api/v1/published-agents/{agentId}/questions/{taskId}
```

请求使用 `Authorization: Bearer <Agent API Token>`。管理员可以从已发布 Agent 行的「API → 下载对接文档 (.md)」获取该 Agent 的 Markdown 文档。文档接口为 `GET /api/v1/published-agents/{agentId}/curl-example?format=markdown`，需要管理员的网页登录凭证。

### 图文报告

在问题请求体中设置 `"reportMode":"visual"` 启用图文报告。省略该参数时默认启用，以 HTML 为主要交付格式。设置 `"reportMode":"off"` 关闭报告，可继续获取文本答案。

任务完成后读取 `answer`。如有可下载报告，`data.report.downloadUrl` 指向 HTML 文件，`data.report.bundleUrl` 指向 ZIP 资源包。原生报告依赖本地脚本、样式或图片时，下载 ZIP 并打开 `data.report.entryPath`。纯文本结果可交付由答案渲染的 HTML。将生成的 HTML 集成到网页时，在禁用同源权限的沙箱中展示。

报告仅对使用 Agent API Token 的任务发起者开放。关闭报告、任务未就绪或未产生可交付结果时，报告接口返回 HTTP 404。网页的报告导出继续使用 PDF。

## 审计和升级

生成、重置、吊销及每次认证均写入系统审计日志。登录审计页面可选择“Agent API 认证”查看请求用户、租户、IP、接口路径和认证结果。

已有数据库升级时执行对应数据库的 `V20260829_01__agent_api_tokens.sql`。旧嵌入登录端点和前端入口已经移除，旧嵌入令牌不再进入登录认证链；原表和历史日志暂时保留，便于审计和兼容升级。
