# MCP 本地用户管理验收记录

2026-10-23，分支 `feature/unified-management-workspaces`，版本 `2.2.2-rc1`。

## 已部署功能

访问 `http://192.168.195.221:18101/admin/`，MCP 管理端新增「用户管理」「登录审计」。用户管理支持创建用户、状态和名称筛选、分页、启用/停用、重置密码及功能菜单授权。菜单授权使用用户列表与授权明细的主从布局，沿用现有 MCP 管理端组件和样式。本人修改密码位于侧栏底部。

普通用户默认没有管理菜单权限；授权后只显示对应菜单。后台按请求检查数据库授权，拒绝未授权接口读写。撤销菜单授权即时影响现有会话；账号停用、密码重置会撤销该用户会话。业务模块同时受原 License 约束。内置管理员受到保护。

Runtime OS 的角色、业务工具和数据权限保持原有权威来源，本次本地菜单权限仅覆盖 MCP 运维控制台。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| MCP 前端结构检查和构建 | 23 个 Vue 组件结构检查通过，Vite 构建成功 |
| 后端编译、打包和相关自动化测试 | 28 项通过，0 失败，0 跳过 |
| PostgreSQL 增量迁移 | 4 项检查通过，验证重复执行、应用表归属、最小权限账号读写和隔离测试回滚 |
| 远程真实浏览器 | 12 项检查通过，包含创建、默认无权、菜单授权、直接接口越权拒绝、撤销即时生效、密码重置、停用、成功/失败登录日志和管理员保护 |
| 桌面布局 | 1366、1440、1920 宽度无页面横向溢出 |
| Runtime OS/MCP 控制面回归 | 28 项通过，包括工具发布、固定模板范围、运行调用、即时权限拒绝、撤销恢复、跨租户拒绝、版本回滚和下线 |
| 验收数据清理 | 本次合成 MCP 用户及其菜单授权已删除；控制面测试身份已清理，测试发布工具已下线，审计历史保留 |

远程联调首次发现新增授权表由数据库超级用户持有，应用角色无法访问。已将新表归属修正为现有账号表的应用所有者，并修改发布脚本，使后续 DDL 使用该角色执行。修复后重新完整验证上述浏览器流程；迁移隔离测试覆盖应用角色的实际读写。失败的首次创建事务已回滚。

## 截图与证据

- [1440px 用户及菜单授权](evidence/menu-permissions-1440.png)、[1366px](evidence/menu-permissions-1366.png)、[1920px](evidence/menu-permissions-1920.png)。
- [登录审计页面](evidence/login-audits.png)。
- [浏览器报告](evidence/browser-report.json)、[PostgreSQL 迁移报告](evidence/postgresql-migration.json)、[控制面回归](evidence/control-plane.json)、[清理和运行制品核验](evidence/cleanup.json)。
- [发布清单](evidence/release-manifest.json)、[部署和业务授权指纹核验](evidence/deployment.json)。后端测试原始摘要保存在同目录的测试类文本报告中。

## 发布与回滚记录

应用源码提交：`bfc7f4f2defb3f77f664d02e5176266c1aadac87`。

- MCP 镜像：`runtime-os-mcp:2.2.2-rc1`。
- MCP 制品 SHA-256：`32cb883f098848b63b46174c670a1ee110af8937884b38f2eba2cc3c2250ffe2`。
- API 保持 `runtime-os-api:2.2.1-rc1`，运行制品 SHA-256：`734f324ece9808a88fd4481f3f92db26a8de1626982bb613422d5991efa8a7db`。
- 服务端发布目录：`/opt/runtime-os-docker-stage/releases/mcp-local-admin-2.2.2-rc1`。
- 数据库和部署配置备份：`/opt/runtime-os-docker-stage/evidence/mcp-local-admin-2.2.2-rc1/backup-1792710204`。原 MCP 镜像 `runtime-os-mcp:2.1.0-rc1` 保留。

升级前后既有本地账号、Runtime 业务授权和模板绑定指纹一致。回滚步骤与降级时新账号处理要求见 [设计与回滚说明](design.md)。私有会话和密码不进入仓库。
