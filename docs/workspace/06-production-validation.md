# Workspace 生产部署与验证

日期：2026-10-13（Asia/Shanghai）。环境：`192.168.195.221`，API `:8080`，MCP `:8090`。本文件记录已执行的部署；不包含登录凭证、令牌或内部签名密钥。

## 最终运行状态

| 项目 | 实际结果 |
| --- | --- |
| API 包 SHA-256 | `6205ad70edaa029a7abdea52a67ee5860b7a198064eac43f845388c03da5a948` |
| MCP 包 SHA-256 | `19ed5f43b072ed7718298eccfddfb15896ffed3a2c10bb2e17d6d293cc6db1d2` |
| API / MCP 进程 | 各 1 个；健康检查和真实登录通过 |
| PostgreSQL Workspace Manifest | `zhice=2`，`data-science=2` |
| 当前会话 / 任务 / UI 产物 | 653 / 1,354 / 640；运行中任务 0 |
| 数据兼容 | 导航迁移前后计数保持不变；之后真实模型/Python 冒烟新增测试记录，旧历史仍可读取 |

最终 API 包以生产上已验证的包为基础，仅替换前端静态文件；之前的服务端候选包保留生产原有 390 个不相关依赖 JAR 的字节内容。MCP 先更新 License 控制器及模块目录，最后的通用模块解析补充包仅替换控制器类，其他 1,405 个包内条目保持原字节内容。未复制 Agent Runtime、修改模型计划器或更改资产发现与授权流程。

## 交付行为

- 登录页调用公开的 `GET /api/v1/workspaces/catalog`，显示当前有效 License 和启用状态允许的空间。用户选择空间后仍使用原统一登录；登录成功时再由服务端按该用户 RBAC 确认。默认选择智策，数据科学也可在登录页直接选择。
- 登录后可在左侧底部 admin 用户工作区域切换空间，令牌不变。智策侧栏不显示数据科学开发页；领域技能位于智策的“能力管理”。数据科学侧栏直接列出 Python 环境、开发、我的数据和我的脚本，不显示最近对话。
- API `WorkspaceService` 对列表和 Manifest 同时检查 MCP License、注册状态、RBAC 与所需能力。MCP 通过原有内部 HMAC 接口 `GET /internal/v1/license/workspace-entitlement` 返回空间授权；前端 `workspaceId`、路由及请求头都不授予资源权限。
- 新 License 可分别选择 `workspaceZhice`、`workspaceDataScience` 模块；未来独立 App 可注册 `workspace:<id>` 模块，MCP 按稳定 ID 通用解析，不需为新 App 改 Runtime Core。现有生产 License 签发于这些模块出现之前，且目前有效；兼容模式维持两个既有空间可见。License 中只要出现显式 Workspace 模块，就仅按这些模块显示。License 无效或 MCP 授权查询不可用时，空间目录拒绝显示。
- 历史 `#/library`、`#/dataScienceDevelop`、`#/dataScienceSkills` 等路由仍能抵达其现归属空间；新路由为 `#/w/{workspaceId}/{pageId}`。SQL Studio 仍只是预留入口。

## 数据库与回退

首次生产部署前已备份原 API 包、配置、PostgreSQL 和停止后的数据目录，执行 `V20261013_01__workspace_apps.sql`；原始基线为 650 会话、1,351 任务、637 产物。第二次执行导航迁移 `V20261013_02__workspace_navigation.sql`，PostgreSQL 先在真实库事务演练并回滚，确认两个 Manifest 可升为版本 2、领域技能菜单父级可调整。正式迁移使用单事务；语句仅在原始 Manifest 完全匹配时更新，不覆盖管理员自行编辑的配置。MySQL/H2 同义脚本已交付，本轮未在对应生产数据库执行。

导航发布备份位于 `/opt/chatchat-deploy-backup/workspace-navigation-20261013-112536`，API 重试备份位于 `/opt/chatchat-deploy-backup/workspace-navigation-api-20261013-115244`，最终前端包备份位于 `/opt/chatchat-deploy-backup/workspace-navigation-ui-20261013-120258`。通用 License 控制器补充包位于 `/opt/chatchat-deploy-staging/workspace-license-dynamic-20261013-121357`，备份位于相应 `/opt/chatchat-deploy-backup/workspace-license-dynamic-20261013-121357`。回退先恢复最近的 API/MCP 包；如回退到导航迁移前版本，使用暂存目录中的受条件约束的 `rollback.sql` 恢复两个旧 Manifest 与技能菜单父级。所有步骤保留会话、任务和产物数据。

第一次导航发布在 MCP 重新启动时出现短暂 `FinancialIngestionPool` 连接池超时，随后 MCP 新接口可用；API 就绪检查立即读取到空的空间目录，自动恢复了 API、MCP 包和导航数据。复核确认两服务与版本 1 数据均恢复。第二次先独立验证 MCP 签名授权接口返回两个空间，再启动 API 并等待目录稳定，部署成功。该过程说明 MCP 启动和 API 首次授权查询需要充分的就绪等待；没有将第一次尝试记为通过。

## 实际验证

| 验证 | 结果 |
| --- | --- |
| 本地前端完整测试与构建 | 28 个测试文件、280 项通过；Vite 构建成功 |
| 后端 Workspace/License 定向测试 | Maven 定向测试通过，包含真实 H2 鉴权、导航迁移、License 显式模块与旧版兼容；构建成功 |
| 生产 API 回归 | 24 项通过：真实登录、同身份切换、低权限空间拦截、跨用户/跨组织会话与任务拒绝、旧历史和 Python 服务读取 |
| 生产浏览器 | 登录页选择两个空间、直接进入所选空间、底部切换、独立菜单、最近对话隐藏、旧链接、草稿恢复及越权拦截均通过；无页面异常或失败请求 |
| MCP License | 真实内部签名请求 HTTP 200，返回有效 License 与两个空间；MCP 模块目录暴露新授权项 |
| Agent 后台任务 | 真实模型任务在 `WAIT_MODEL` 时切换空间，随后 `SUCCESS`；登录令牌不变 |
| Python Studio | 在既有 READY 环境创建临时 Python 脚本，MCP 隔离执行状态 `SUCCEEDED`，随后删除测试脚本 |
| 生产数据与进程 | 最终会话/任务/产物计数及一套 API、一套 MCP 进程已核对，运行中任务 0 |

原后端 15 项失败/错误的分类与第一轮修复证据见 [测试报告](03-test-report.md)。最终修改文件见 [变更清单](05-changed-files.md)。

## 保留边界与后续

生产现有 License 沿用兼容授权，因此此次没有在生产切换到“仅智策”或“仅数据科学”的新签发 License。服务端显式模块拒绝与通用 `workspace:<id>` 解析路径已有单元/集成测试；未来重新签发 License 时应在 MCP 管理流程中验证单空间授权的真实签名文件及撤销/恢复。独立的 `chatchat-license` 签发服务代码目录已补充两项内置模块，若实际签发环境单独部署该服务，仍需同步发布并审查签发流程。新 App 的模块也须加入签发服务目录。

Workspace 仅控制应用入口和页面可见性；原有 Python、Skills、MCP 及资源接口继续由各自 Runtime/RBAC/对象授权裁决。SQL Generation 仍处于方案阶段。首次 MCP 重启暴露的连接池启动超时值得单独观察，不能以进程存在代替服务就绪。
