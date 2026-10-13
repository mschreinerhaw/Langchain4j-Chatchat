# Workspace 测试报告

日期：2026-10-13（Asia/Shanghai）。本文保留第二轮本地基线证据；**现已完成生产部署和新入口回归**，最新结果见 [生产验证](06-production-validation.md)。下面“未部署”等表述只描述当时的第二轮本地阶段。

## 生产补充验证（2026-10-13）

新的登录页选择、底部切换、菜单重排与 MCP License 授权已在生产运行。前端最终 28 文件 **280 项通过**并构建成功；更新后的本地浏览器 Shell 冒烟通过；后端 Workspace/License 定向测试通过。生产 API 回归 24 项通过，真实浏览器覆盖登录选择、同 Token 切换、开发空间直接菜单、最近对话隐藏、旧链接和越权拦截；真实模型任务跨空间切换后成功，MCP Python 隔离脚本成功。PostgreSQL 已执行 V1 和 V2，最终 653 会话、1,354 任务、640 产物、0 运行中任务。首轮导航发布的失败及自动回滚记录在生产报告中，不计为通过。

## 第二轮实际验证结果

| 验证 | 实际结果 | 本地证据 |
| --- | --- | --- |
| 后端定向回归，12 套件 | **78 通过，0 failure，0 error** | `target/workspace-audit/final-backend.log`、API Surefire XML |
| 前端完整配置测试，28 文件 | **279 通过，0 失败** | `target/workspace-audit/full-frontend.log` |
| 前端构建 | **成功，13.84 秒** | `target/workspace-audit/final-frontend-build.log` |
| API 发布包构建，13 模块 | **BUILD SUCCESS，34.873 秒**；独立测试完成后跳过重复测试/前端构建 | `target/workspace-audit/final-package.log` |
| 本地真实浏览器 Shell 冒烟 | **通过**；API 为明确的 UI 测试夹具 | `target/workspace-audit/browser-smoke.log`、`workspace-shell.png` |
| H2 附加迁移、重复执行与撤销授权 | **通过**，包含于上述 78 项 | `WorkspaceMigrationTest` |

阶段顺序证据：T03～T06 最小机制完成后，先执行后端 49 项（全通过、Reactor BUILD SUCCESS）、前端原 62 项（全通过）与 Vite 构建（成功、15.93 秒），日志为 `t03-t06-backend.log`、`t03-t06-frontend.log`、`t03-t06-frontend-build.log`；再完成 T07/T08 隔离、草稿与上述扩展回归。没有将未运行的逐任务独立构建计为通过。

后端命令（仓库根目录，PowerShell）：

```powershell
mvn.cmd -o -pl chatchat-api -am test '-Dtest=WorkspaceIntegrationTest,WorkspaceMigrationTest,ObjectAccessPolicyTest,RuntimeOsArchitectureBoundaryTest,ApiAuthenticationFilterTest,ApiPermissionAuthorizationFilterTest,ConversationControllerTest,PythonCodeAssistantServiceTest,UiArtifactControllerTest,PythonDataScienceScriptDeletionTest,PythonDataScienceFileTypeTest,PythonEnvironmentStatusTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dfrontend.skip=true' '-Dfrontend.install.skip=true'
```

前端在 `chatchat-api/web-app` 执行 `npm.cmd test`、`npm.cmd run build` 和 `node scripts/test-workspace-shell.mjs`。新增 Workspace 测试已纳入现有 npm 测试命令。

发布包构建命令为 `mvn.cmd -o -pl chatchat-api -am package '-DskipTests' '-Dfrontend.skip=true' '-Dfrontend.install.skip=true'`。生成本地 `chatchat-api/target/chatchat-api-1.0.0-SNAPSHOT-release.zip`（370,000,756 字节）；仅为审查产物，未上传服务器。前端构建生成静态入口/忽略的 assets；打包后将源码树中生成的旧跟踪入口恢复为基线，源码发布必须先构建前端，ZIP 中已打入本轮构建结果。还直接检查 JAR 中 Workspace 类、生成的静态入口及对应 assets，一致性通过。

### 原后端 15 项失败/错误的分类与处理

第一轮同一基线套件为 49 项：34 通过，1 failure，14 errors，记录保留在本文附录。

| 类别 | 基线数量 | 实际原因 | 本轮处理与结果 |
| --- | --- | --- | --- |
| 架构依赖断言 | 1 failure | Chat 使用 Spring 平台却缺少直接模块依赖 | 明确声明已有平台模块依赖；保留架构断言，当前通过 |
| 架构源码路径/签名过时 | 3 errors | Skill adapter/MCP 控制器已移动包，旧测试读不存在文件 | 按实际模块路径和中立执行 DTO 更新检查；未删除边界检查，当前通过 |
| 会话 HTTP/存储测试上下文 | 11 errors | 首个上下文因未发布 Runtime semantic policy 启动失败，后续错误受上下文失败阈值影响 | 仅该存储/HTTP 测试使用 semantic policy 测试替身；不放宽生产发布检查，当前 11 项通过 |

原 49 项现已全部通过；本轮新增对象权限 12 项、Workspace 真正应用集成 8 项、H2 迁移 1 项，加上扩展资源/Python 回归 8 项，总计 78 项，没有本轮执行范围内的未解决测试失败。

扩展产物回归首次有 2 项内容协商夹具失败；GET 测试显式声明 `Accept: application/json` 后通过，没有改变产物业务接口。过程中另遇 PowerShell Maven 参数拆分、Node localStorage 告警和 Windows 前端依赖安装文件占用；最终使用带引号 Maven 参数、隔离的测试 Storage 和已独立构建的前端通过验证。这些过程错误不计为通过记录，也不隐瞒最终执行前的失败。

### 服务端安全与兼容覆盖

`ObjectAccessPolicyTest` 使用真实 H2 所属关系查询与公共 interceptor/advice，验证：跨用户读取/删除/SSE/产物资源拒绝；跨租户即使 owner 相同也拒绝；Workspace/组织请求声明不能授予权限；历史路径/请求体伪造拒绝；会话复用在执行前拒绝；复用他人幂等键/恢复任务拒绝；旧 username 所有者兼容但不能碰撞他人稳定 ID；同租户任务管理特权不扩展为会话权限；无所属任务的产物 404 且不删除；租户汇总受已有管理权限约束。

`WorkspaceIntegrationTest` 使用真实认证 Filter、数据库 RBAC、JPA 注册/偏好与现有任务执行器，验证：原管理员登录及智策菜单、普通角色空间可见性/管理接口拒绝、同 Token 切换/偏好恢复、注册版本冲突/禁用不删历史、不同组织主体读取他人会话和历史拒绝、来源不可重标及旧会话可读。后台任务通过 latch 暂停在编排阶段，切换空间后继续成功；应用中 `WorkflowRuntime` Bean 数量为 1。模型与 Interaction 编排结果是替身，不宣称真实模型/MCP 成功。

集成回归另外验证普通用户任务列表与分页只返回本人任务，即使传入伪造 tenant 或另一空间的请求头也不扩大范围。

已有会话继续使用的测试还显式传入过期/伪造来源头，验证所有者仍可复用，既有来源保持不变；来源头只在新会话创建时校验，不是已有对象的授权条件。

`WorkspaceMigrationTest` 实际运行 H2 SQL 两次，验证两条默认注册、既有角色的等效入口、普通角色不获得注册管理权限、撤销入口后再运行不重授、原历史字段保持不变。

前端原有 App/Chat/Python/Tasks 62 项通过；新增 8 项验证路由、撤销偏好回退、tenant/主体/空间存储隔离、脚本授权复核、迟到异步响应丢弃、跨空间运行事件归属和组件缓存键。完整前端 279 项均通过。浏览器冒烟验证默认智策、菜单、切换、草稿恢复、SQL 预留、选择页、拒绝空间与不重新登录；使用本地 API 夹具，不替代服务端鉴权测试。

### T10 本地验收矩阵与未验证边界

| 验收项 | 当前证据与边界 |
| --- | --- |
| 原用户登录 | 第二轮本地认证/RBAC 集成通过；生产真实登录见生产补充验证 |
| 默认智策/原菜单 | 注册与菜单集成、浏览器默认入口通过 |
| 授权空间选择/切换 | 服务端可见性/manifest/preference 与浏览器切换通过，同一 Token |
| 无权限/跨用户/跨组织/跨空间越权 | 统一 owner/tenant 负向 HTTP 与真实认证集成通过 |
| Python Studio | 原前端、脚本删除/文件类型/环境状态/编程助手测试通过；真实 MCP Python 执行待环境验证 |
| 原 Agent/工具行为 | 架构断言、Chat 前端及共享任务执行器集成通过；真实模型/工具链未执行 |
| 后台任务不中止 | 实际既有调度器任务跨切换成功，非另造状态机 |
| 旧链接/历史/文件 | 前端旧路由、旧 username 会话、H2 历史保留、原资源测试通过；未执行生产数据全量核查 |
| 无重复 Runtime | 没有改动 Core 注册或创建新 Runtime；集成验证单 WorkflowRuntime Bean |
| SQL Generation | 本期仅保留声明，无实现或调用 |

MySQL/PostgreSQL 脚本未在真实数据库执行；全仓库所有套件未遍历；真实模型/MCP、进程重启后的任务恢复、生产历史和孤立产物清点仍待独立环境/部署审查。前端大 chunk、MockBean 弃用及本地 MCP 不可达告警仍存在。发布状态、迁移与风险详见 [实施审查](04-implementation-review.md)。

## 附录：第一轮原始审查基线（历史记录）

以下内容来自 T01/T02 只读阶段；其中“本轮”“未实施”等描述仅指第一轮，不是第二轮当前交付状态。

## 1. 实际文件与发布状态

本轮新增 `docs/workspace/01-current-architecture.md`、`02-workspace-architecture.md`、本报告。业务源码、数据库结构、接口和路由没有实施变更。构建产生的静态入口变更在检查后恢复为基线内容。

迁移脚本：本轮无；拟议数据方案与迁移步骤在 T02，未执行线上 DDL。部署：未上传、未替换服务包、未重启 API/MCP。按用户第十三节要求，方案确认后进入实施。

## 2. 自动化测试

前端命令（`chatchat-api/web-app`）：

```powershell
npx.cmd vitest run src/js/App.test.js src/js/views/DataScienceView.test.js src/js/views/ChatAssistantView.test.js src/js/views/TasksView.test.js
```

| 套件 | 实际结果 |
| --- | --- |
| App | 15 通过 |
| DataScienceView | 6 通过 |
| ChatAssistantView | 32 通过 |
| TasksView | 9 通过 |
| 总计 | 4 文件，62 测试通过，退出码 0 |

这些测试是现有界面基线，不包含尚未实现的 Workspace 切换，也不能代替浏览器和联机工具调用。Node 提示未提供有效 localstorage-file，不影响本次测试退出码。

后端命令（仓库根目录）：

```powershell
mvn.cmd -o -pl chatchat-api -am '-Dtest=ApiAuthenticationFilterTest,ApiPermissionAuthorizationFilterTest,ConversationControllerTest,PythonCodeAssistantServiceTest,RuntimeOsArchitectureBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

| 套件 | 测试数 | 结果 |
| --- | --- | --- |
| ApiAuthenticationFilterTest | 7 | 全部通过 |
| ApiPermissionAuthorizationFilterTest | 3 | 全部通过 |
| PythonCodeAssistantServiceTest | 4 | 全部通过 |
| RuntimeOsArchitectureBoundaryTest | 24 | 20 通过，1 failure，3 errors |
| ConversationControllerTest | 11 | 11 errors，应用上下文无法启动 |
| 总计 | 49 | 34 通过，1 failure，14 errors；退出码 1 |

失败细节：

- 架构测试 3 个读取文件错误引用不存在的旧路径：`runtime/skill/spi/AgentRuntimeAdapter.java`、API 顶层 `controller/McpRuntimeBridgeController.java` 和 `controller/McpServiceController.java`。
- 架构依赖断言：`chatchat-chat must own its Spring platform dependency explicitly`。不能直接假定这是可删除的旧断言，需要核实模块边界后处理。
- 会话套件首个上下文启动原因：`Runtime semantic policy is not published`，其余错误受到测试上下文失败阈值影响。日志另有本地 MCP gRPC `localhost:9091` 连接拒绝。未修改测试夹具来掩盖失败。

完整后端日志：`target/workspace-audit/backend-tests.log`；JUnit XML 位于 `chatchat-api/target/surefire-reports/`。本轮初次调用曾遇到 PowerShell 对 Maven 逗号参数的解析错误，以及 npx.ps1 执行策略限制；改用带引号参数和 `.cmd` 后运行以上实际测试。它们不是源码测试失败。

## 3. 构建

前端 `npm.cmd run build` 成功（退出码 0，Vite 报告约 14.54 秒）。输出至 API 的 static 目录，日志：`target/workspace-audit/frontend-build.log`。已有大 chunk 警告，包含 Python/Monaco 与对话页面；本轮不因此扩大到无关性能重构。

后端打包命令：

```powershell
mvn.cmd -o -pl chatchat-api -am '-DskipTests' '-Dfrontend.skip=true' '-Dfrontend.install.skip=true' package
```

结果：`BUILD SUCCESS`，13 个 Reactor 项全部成功，退出码 0，用时约 1 分 13 秒。生成 API JAR 与 `chatchat-api/target/chatchat-api-1.0.0-SNAPSHOT-release.zip`。此命令跳过测试和前端重复构建，使用已成功生成的前端产物；独立打包不代表上述测试通过，也不是已经批准发布的 Workspace 新版本。日志：`target/workspace-audit/backend-package.log`。

## 4. 服务器基线

目标 `192.168.195.221`，SSH 端口 5221。以下为本轮实际检查，没有记录密码或 Token：

| 检查 | 结果 |
| --- | --- |
| SSH 连接 | 成功 |
| `/opt` 容量 | 180G，总使用 134G，可用约 47G，75% |
| 根文件系统容量 | 45G，可用约 29G |
| API/MCP 监听 | 8080、8090 均监听 |
| API `/api/v1/enterprise/auth/login` | HTTP 200、业务码 200，返回 Token |
| API `/api/v1/enterprise/auth/me` | HTTP 200、业务码 200 |
| API `/api/v1/health` | HTTP 200，`status=UP` |
| API `/api/v1/enterprise/menus` | HTTP 200，授权菜单 3 组 |
| API `/api/v1/workspaces` | HTTP/业务码 404；候选路径当前不存在 |
| MCP `/api/v1/admin/auth/login` | HTTP 200、业务码 200，返回 Token |
| MCP `/api/v1/admin/auth/me` | HTTP 200、业务码 200 |

线上结果摘要保存在 `target/workspace-audit/server-baseline.json`，无凭据与 Token。管理员登录会产生普通审计记录；没有创建业务任务、修改用户权限或查询其他用户的私有对象。`/actuator/health` 不是本部署可用的健康入口，初次探测分别返回 API 404、MCP 500，不能将 curl 成功退出等同健康。API 后续使用源码确认的 `/api/v1/health` 验证；MCP 尚未验证独立执行健康。

本地 HTTP 首次访问受到代理影响，关闭 urllib 代理后完成以上检查。没有执行磁盘清理。未确认正式启动脚本、数据库方言、服务实例总数或包校验和；这些属于实施发布前置检查。

## 5. T10 验收矩阵

| 要求 | 当前证据/状态 |
| --- | --- |
| 原用户正常登录 | API/MCP 管理员认证通过；普通用户/浏览器未验证 |
| 默认进入智策空间 | 未实现多 Workspace，不能验收 |
| 选择、切换授权空间 | 未实现 |
| 拒绝未授权空间 | 未实现；旧 API 权限过滤单元测试通过 |
| Python Studio 正常 | 现有前端 6 项、编程助手后端 4 项通过；真实环境/执行未测 |
| 原 Agent 对话/工具调用 | 对话前端 32 项通过；未发起真实 Agent/工具任务 |
| 切换不终止后台任务 | 源码卸载只关闭事件流，联机切换验证未做 |
| 旧链接/菜单兼容 | App 15 项通过，线上菜单可读取；新兼容路由尚未实施 |
| 历史/文件不丢失 | 本轮无数据库/文件迁移；未做新旧版本计数与内容比对 |
| 无重复 Runtime 实例 | 设计禁止复制执行器；新实现和实例指标尚未验收 |

## 6. 后续顺序与风险

先确认 T02 的薄 Registry、菜单投影、对象权限修复与兼容来源方案。再完成 T03–T06，之后 T07/T08/T10；T09 仅预研。每步补充实际构建、测试、接口、路由、文件清单与迁移记录，发布经验证的包后再记录线上验收与回滚位置。

当前关键风险：会话/产物部分入口对象授权不足；全局浏览器缓存缺少用户/Workspace 维度；普通 Token 为内存态，服务重启失效；后端基线测试未全绿；没有普通低权限账户的负例验证。不能将本报告描述为多 Workspace 已实现或已部署。
