# SQL Studio IDE 化生产发布验证

日期：2026-10-13（Asia/Shanghai）。目标：`192.168.195.221:8080`，MCP：`:8090`。源码提交：`955b8142`。本次未修改生产数据库、MCP 服务或 Agent Runtime 实例数量；沿用目标机原有配置。发布前端 UI 和 SQL 文件重命名接口。

## 构建与发布

- 前端 `npm.cmd run build` 成功；Maven `-Pbackend-only -pl chatchat-api -am package -DskipTests` 成功。包内确认存在 `SqlStudioController.class`、新版 `SqlStudioView` JS/CSS。发布前已完成本地前端 300 项测试、SQL Studio H2 集成测试 13 项及 Chromium fixture 回归，见 [实施报告](07-ide-implementation-report.md)。
- 初始发布包 SHA-256：`49bb74466ba8a3215ea10671fec1aac44e5da268b62c422218859dc95a412702`。上传至服务器后哈希一致，ZIP 完整性检查通过。服务器原运行包哈希：`5812cd91baecd2d06bfcaf46df321978f23dfa2c0cf29d02b95257cc5d41eac1`。
- 发布后发现旧浏览器会请求先前版本的 `assets/python-C2wjXMUE.js`。从服务器已有的上一版静态资源暂存目录中取出 43 个当前包缺少的带哈希资源（约 7.3 MiB），追加到发布包；保留当前新版资源和入口文件。兼容包 SHA-256：`178f9874c48634e0e5766bb734c3bf8f6b789a0b6d4512cf462e4688b11df971`。完整性检查通过，现运行此兼容包。旧 Python 资源现返回 HTTP 200。
- 发布前尝试在 `lib/app` 建立原包备份，但发布后发现备份文件未保留在该目录；**不能将其视为可用的精确回退包**。为保持可回退性，已从发布前提交 `b54ffc83` 重新构建回退包，存于服务器 `/opt/chatchat-1.0.0-SNAPSHOT/data/rollback-b54-rebuilt-20261013.jar`，SHA-256：`2f46420a8b62422e5098939aaf259df0cc840c526977fa55936173bbb21d08c3`，ZIP 检查通过。该包由相同源码重新构建，哈希与原运行包不同，**尚未实际执行回退演练**。当前发布的原始包另存为 `data/chatchat-sql-ide-pre-compat.jar`。

## 线上验证

| 检查 | 实际结果 |
| --- | --- |
| 应用首页 | HTTP 200，单一应用进程运行；兼容包重启后正常启动。 |
| SQL 工程接口 | 未登录访问 `GET /api/v1/data-science/sql/projects` 返回 401；真实管理员登录后调用返回 200。 |
| IDE 页面 | 真实 Chromium 打开数据科学 SQL Studio，Activity Bar、Explorer、Editor、AI 助手、Bottom Panel、Status Bar 正常显示；页面无 JavaScript 异常。 |
| 兼容入口 | 同一登录身份打开 Python 开发和智策对话页面成功；旧版 Python 静态资源返回 HTTP 200。 |
| MCP | 独立服务 `:8090/` 保持既有 HTTP 302 跳转。 |

真实环境截图：[SQL Studio IDE 线上页面](../../target/sql-studio-ide-deploy-audit/sql-studio-real.png)。只读冒烟脚本：`chatchat-api/web-app/scripts/verify-sql-ide-production.mjs`，凭证从环境变量读取，不保存 Token 或密码。

## 验证边界

线上验证未创建工程、上传真实 Mapping、修改文件、提交模型任务或调用生产数据库执行 SQL。因此不能据此宣称真实模型生成、写入及并发冲突在生产环境已完成端到端验收。新增重命名路径已由 H2 集成测试覆盖授权和版本冲突；线上只验证服务启动与受权工程列表。旧资源兼容仅覆盖服务器已保存的上一版 43 个缺失资源；更早版本的长期打开页面仍可能需要刷新。
