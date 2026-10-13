# SQL Studio 与 Code Intelligence 部署验证

日期：2026-10-13。目标：`192.168.195.221:8080`，源码提交 `64f526c9`，部署的 API JAR SHA-256 为 `7f167f8b25fa1be41fba038d218ccb8cd9db92d6c3a20d920c88704ddc2fb0f6`。这份记录补充此前仅限本地的 [SQL Studio 测试报告](03-test-report.md)和 [Code Intelligence 测试报告](../code-intelligence/02-test-report.md)。未合并主分支，未部署或重启独立 MCP 服务。

## 发布前核查

- 目标应用实际使用 Spring 默认 `dev` profile，连接 PostgreSQL `live_runtime_api`，而非服务器上保留的 H2 配置。发布沿用既有配置，未切换 profile 或数据库。
- 发布前库中没有 `ds_sql_%` 表和 `sqlStudio` 菜单；内置 `data-science` Workspace 存在。
- 使用容器内 `pg_dump -Fc` 备份该库，并以 `pg_restore -l` 校验归档。备份保存在 `/opt/chatchat-1.0.0-SNAPSHOT/data/live_runtime_api-pre-sql-studio-20261013.dump`，约 233 MiB。
- 将备份恢复到独立的 `sql_studio_verify_20261013` 数据库，在克隆库执行 PostgreSQL schema 与 Workspace 升级脚本。首次执行创建 4 张表、升级 1 条内置 Manifest、插入菜单和权限关联各 1 条；重复执行未再次升级或插入。校验后删除克隆库，保留备份。

## 构建、测试与发布

| 检查 | 实际结果 |
| --- | --- |
| 前端 `npm.cmd run verify` | 既有 280、SQL Studio 8、Code Intelligence 10 项通过；Vite 构建成功。仍有大 chunk 提示。 |
| 后端定向 `mvn.cmd -Pbackend-only ... test` | 98 项通过，0 failure、0 error、0 skipped；含 SQL Studio、对象授权、Workspace、Python、Runtime 边界与会话测试。日志为 `target/full-deploy-backend-test.log`。 |
| 后端 `mvn.cmd -Pbackend-only -pl chatchat-api -am package -DskipTests` | 13 个 Reactor 模块打包成功；前述测试独立执行。日志为 `target/full-deploy-package.log`。 |
| 包核查 | JAR 中存在 SQL Controller、迁移脚本与前端资源；上传后 SHA-256 一致，ZIP 完整性通过。 |
| 发布 | 备份旧包至 `/opt/chatchat-1.0.0-SNAPSHOT/lib/app/chatchat.jar.backup-pre-sql-20261013`；替换 API JAR 并重启现有单一 Runtime OS 实例。 |

## 线上验证

- API 正常启动，8080 页面 HTTP 200；独立 MCP 8090 根路径返回既有登录重定向 302。
- PostgreSQL 中现有 4 张 `ds_sql_%` 表、1 个 `sqlStudio` 菜单，`data-science` Manifest 版本升级。未迁移旧 Python 文件和会话。
- 真实 Chromium 以管理员登录，进入数据科学 SQL Studio，工程列表 GET 返回 HTTP 200，页面无错误提示；再进入 Python 开发页，Monaco 成功加载，切回智策后原对话页正常，浏览器无脚本错误。截图：`target/deploy-audit/sql-studio.png`、`target/deploy-audit/admin-menu.png`。
- SQL 工程列表与 Ruff 格式化 API 在无身份时均返回 HTTP 401。服务器 Python Runtime 镜像包含 `ruff 0.17.0`。

## 验证边界与风险

- 线上未创建工程、上传真实 Mapping 或调用真实模型。因此不能宣称生产环境中单/多 Mapping SQL 生成、文件保存、真实 Ruff API 调用已完成端到端验证；这些路径目前由本地集成与浏览器测试覆盖。
- 服务器虽作为部署目标使用，Spring 实际采用默认 `dev` profile。应单独审查 profile、配置来源和发布流程，不能仅凭目录中的 `application-prod.yml` 判断运行配置。
- 本次数据库迁移为新增表及精确匹配内置 Manifest 的更新。旧 JAR 备份可以回退应用；若要回退数据库，需先评估发布后的新数据，不能直接全库恢复备份覆盖用户写入。
- 当前菜单依赖原有 Workspace License/RBAC 与服务端对象权限。真实低权限用户跨对象负例、真实模型任务和 Python Docker 运行未在这次线上冒烟中执行；定向测试通过不等于全仓库测试全绿。
