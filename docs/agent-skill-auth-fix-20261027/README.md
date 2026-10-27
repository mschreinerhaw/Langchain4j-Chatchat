# Agent Skills 绑定与登录状态修复

## 实际原因

1. `data_asset_operation_management` 保存失败：原配置绑定三个 Skill，其中 `239e79a1-415b-45ca-aa68-054fb02866d3` 已不存在。其余两个仍已发布且被当前账号授权。旧代码将不存在的历史引用和真实越权统一报为 `Agent contains domain Skills outside the current user's permissions`。已在部署前通过原样 PUT 真实复现 HTTP 403。
2. 会话/工作台接口 401：`EnterpriseAdminService` 的登录令牌保存在进程内存中，API 重启会使旧令牌失效。新登录后，相同账号、租户及 `admin` 用户名路径都返回 200，用户名路径本身不是本次原因。
3. 前端另外存在竞态：旧请求迟到的 401 会无条件清理刚建立的新登录会话；旧身份刷新结果也可能覆盖新登录用户。回归测试已复现并验证修复。
4. 单次 502 当前未复现；正式登录后的相同待办接口实测为 200，不能仅凭无时间戳的控制台记录断定其历史原因。Autofocus 提示不属于服务器认证或授权错误。

## 修改与边界

- `AgentWorkshopController`：保存时仅清理同一 Agent 原来已绑定、且经存储确认已删除的 Skill 引用；保留对仍存在但未授权、未发布或新传入非法 ID 的校验。
- `DomainSkillService`：复用现有 repository 提供仅判断存在性的查询，不返回其他租户内容，也不授予权限。
- `web-app/src/services/api.js`：主 API 请求的认证失败只能清理该请求使用的会话；HTTP 401 和 HTTP 200/code=401 两种形式均有测试。
- `web-app/src/js/App.js`：迟到的身份刷新结果不能覆盖已切换或已退出的会话。
- 未增加登录旁路、默认授权或自动接受无效令牌。重启之前的登录仍须重新认证，本次没有改造会话持久化机制。

## 验证与部署

- Java 权限及绑定回归：21 项通过。
- 前端请求、身份刷新和 Agent 编辑回归：40 项通过。
- 生产构建通过。
- 实际 Agent 原样保存：修复前 403，修复后 200；仅移除上述失效引用，另外两个 Skill、模型、系统提示词、工具及文档绑定保持一致。
- 将已删除 ID 作为新绑定重新加入：仍为 403；拒绝之后登录会话仍有效。
- 正式登录后，auth/me、会话摘要、工作台待办、工作台、Runtime 待办、目标 Agent 六个接口均为 HTTP 200。
- 已发布至 `/opt/chatchat-releases/agent-skill-auth-20261027-r1`，保留上一运行包及容器供回滚。端口 18100 的 HTML 和主 JS 已按部署清单核对哈希。

具体控制结果见 `verification.json`，未包含登录令牌或口令。
