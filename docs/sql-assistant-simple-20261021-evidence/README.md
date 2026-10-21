# SQL Studio 简洁对话与 Agent 权限验证

- `local-validation.json`：20 项后端、66 项 SQL Studio 前端测试及最终 8 项组件测试通过，生产构建通过。
- `package.json`：部署包 SHA-256，1,150 个非静态后端条目与通过测试的后端包一致。
- `deployment.json`：部署镜像与回退备份。
- `api-acceptance.json`：11 项真实角色授权、发布状态、用户隔离和撤权检查；临时身份和工程已清理。
- `browser-acceptance.json`：8 项真实浏览器检查，覆盖简洁对话、设置内文档/脚本引用及有无 Agent 权限的差异，无页面异常。
- `simple-conversation.png`、`documents-in-settings.png`、`settings-with-agent.png`、`settings-without-agent.png`：真实临时用户验证截图，演示上传已清理。

本次服务器验证未调用模型或执行 SQL/Python。完整认证会话、临时用户密码及服务器凭据不入库。
