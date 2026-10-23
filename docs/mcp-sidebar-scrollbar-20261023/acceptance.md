# MCP 侧栏滚动体验修复

2026-10-23（北京时间）完成部署。分支 `fix/mcp-sidebar-scrollbar`，功能源码提交 `3e0b6ed19`。

用户卡片固定在底部后，菜单区域使用独立滚动，原本的行高与间距导致普通桌面窗口也出现粗白色系统滚动条。本次压缩侧栏留白及菜单间距，按窗口高度调整行高；默认折叠子菜单时，1366×768、1440×900、1920×1080 均完整显示菜单，无需滚动。

保留内容确实超出时的滚动，确保矮窗口及展开子菜单后所有菜单仍可访问。补充深色窄滚动条、透明轨道、圆角滑块、悬停及键盘聚焦配色，并隐藏 WebKit 滚动条按钮。限制横向溢出与滚动串联，底部用户卡片保持可见。

本地及线上浏览器均通过：常用窗口无菜单溢出、1280×600 展开数据能力中心后可滚动到 License 菜单、滚动条使用主题样式、底部卡片可见。用户卡片展开收起、Enter/Esc、修改密码弹窗打开与取消、退出登录，以及手机宽度布局回归均通过，浏览器无脚本异常。

- [本地验收](local-browser.json)、[线上验收](live-browser.json)
- [默认菜单](default-menu.png)、[内容溢出时的菜单](overflow-menu.png)
- Vue 结构检查（23 个组件）和 Vite 生产构建通过，存在既有构建体积及第三方注释提示。
- 验收脚本：`scripts/test-mcp-account-card-browser.mjs`。未提交密码修改或变更账号授权。

部署入口：`http://192.168.195.221:18101/admin/`。

MCP 镜像：`runtime-os-mcp:2.2.2-sidebar-scrollbar-20261023`。

运行 JAR SHA-256：`5e2a0cc4e9705f634eb663e42e4946b805507eff7b4e4ffdeb975bcf364ebe9d`。

只替换管理端静态页面，其他 JAR 条目逐项核验一致；用干净 Java 基础镜像封装，网关 6 个资源与产物一致。账号、菜单授权、Runtime 业务权限和 MCP 模板绑定指纹保持一致，无数据库迁移。API 保持上一轮修复版本，JAR SHA-256 仍为 `e62aa611bf57b0f5fa4a3d6af6b25e137e84c42b23343cf0f47989ec6e6debdc`。

服务器部署记录：`/opt/runtime-os-docker-stage/evidence/mcp-sidebar-scrollbar-20261023/deployment.json`。配置及 MCP 数据库备份在同目录 `backup/`，原始 JAR 在 `baseline.jar`；原镜像 `runtime-os-mcp:2.2.2-account-card-20261023` 保留以供回滚。
