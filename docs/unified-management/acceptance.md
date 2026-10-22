# 设计书逐项追踪

| 章节 | 实施内容 | 验证位置 |
|---|---|---|
| 1 Git 与基线 | fetch 全部远程引用和标签；干净工作区；无分叉；不可覆盖基线标签；独立开发分支 | implementation.md 基线；最终交付 commit |
| 2 架构盘点 | 复用现有 RBAC、WorkspaceService、中央资源授权、确定性模板和部署拓扑 | implementation.md 盘点与职责表 |
| 3 统一权限 | API PostgreSQL 为唯一权威；移除 MCP 权限写代理；角色为只读投影；执行重新取授权；最终目标复核；旧中央接口也绑定登录租户 | McpAuthorizationServiceTest、ExecutionTargetRouterTest、权限集成测试；远程迁移前后指纹 |
| 4 权限工作空间 | 复用用户、组织、角色、菜单与资源授权组件；新增工具/数据范围授权和审计视图；既有管理路由迁移 | UnifiedManagementMigrationTest；浏览器与普通用户拒绝验证 |
| 5 工具发布工作空间 | 草稿、Schema 校验、固定范围试运行、发布、下线、版本回滚；不可变证据；原有执行器；类型扩展端口 | ToolReleaseLifecycleTest、TemplateQueryMcpToolPublisherTest；远程 API 和浏览器生命周期 |
| 6 MCP 职责 | 保留数据连接、受控执行、审计和运维；删除重复用户/权限、发布 UI；内部发布 API 只接受服务身份 | ControlPlaneManagementFilterTest、旧接口验证；MCP 前端构建 |
| 7 配置治理 | 用户界面移除执行预算实现细节；后端保留已有运行限制，按权限保存内部设置；模型与必选工具保留 | RuntimeConfigurationPolicyTest、AgentWorkshopControllerTest；查询和分析回归 |
| 8 Runtime 边界 | Java 编排、Node/Cline 分析、Python 隔离、SILO 产物、OpenSearch 检索；不新增自由探索数据路径 | 原模块依赖保留；远程 Runtime 文件/联合分析证据 |
| 9 冗余清理 | 删除旧 MCP 两组页面/脚本/样式/API；删除权限写代理与重复 Scope 解析器；旧角色表保留为回滚档案 | Git 删除清单；构建；无源码调用的写接口封堵 |
| 10 测试验收 | 自动化授权、发布、Schema、隔离、撤权、配置、迁移及既有功能回归；远程调试 | validation.json 与远程 acceptance.json、浏览器证据 |
| 11 交付物 | 模块对比、数据模型、SQL、工作空间、配置分级、清理清单、验证和回滚说明 | 本目录、deploy/unified-management-release.json、scripts 下部署和验收脚本 |
| 12 开发约束 | 原系统内改造；复用组件与执行链路；迁移不放宽数据权限；阶段验证和版本记录；构建包与部署哈希 | 基线标签、commit、发布 manifest、迁移核对报告 |

## 验证状态

本地回归共 185 项后端测试，183 项通过、0 失败、0 错误；2 项需要显式开启 `coding.realDocker` 的真实 Docker 及进程崩溃恢复测试跳过。前端四组相关回归 63 项通过，两个前端构建通过。完整套件与计数见 validation.json。真实远程分析单独记录，不以这些跳过项代替。

远程 RC5 控制面 28 项验收通过，覆盖旧会话即时撤权、执行拒绝即时生效、移除拒绝恢复、跨租户隔离、实际固定模板检索、版本发布/回滚/下线及数据库不可变保护，见 [rc5-control-plane.json](evidence/rc5-control-plane.json)。浏览器 6 项验收通过、无 JavaScript 异常，见 [rc5-browser.json](evidence/rc5-browser.json)。

已通过真实新建查询服务的单文件和双文件联合分析：每个文件 2 行，金额合计 30、10，匹配 A 的差值为 7，B、C 分属两份文件。验证了独立执行目录、Node/Cline、Python 容器、代码哈希、归档结果和至少 6 个证据引用，见 [runtime-analysis.json](evidence/runtime-analysis.json)。另外真实触发 KeyError，收到结构化字段错误反馈后修正执行成功，见 [runtime-error-feedback.json](evidence/runtime-error-feedback.json)。

部署版本 `2.0.0-rc5`，源码提交 `f21465306dd9989900a89665269c9e529cdf97c0`，入口 `http://192.168.195.221:18100`。权限空间 `/#/w/permissions/systemRoles`，发布空间 `/#/w/mcp-publication/toolPublications`。迁移前后既有策略和模板绑定指纹一致，制品 SHA-256、备份及迁移核对见 [rc5-deployment.json](evidence/rc5-deployment.json)。

RC5 鉴权修正后再次完成真实文件分析，耗时 342 秒，检查均通过，见 [rc5-runtime-single.json](evidence/rc5-runtime-single.json)。模型响应时间会影响总耗时，不作固定时延承诺。验收临时账号、角色、租户和新建查询服务已清理；临时工具均下线，保留不可变版本与任务审计。最终 API、MCP、Node、代理及网关运行正常且无重启，容器内 JAR 哈希与发布 manifest 一致；原始中央策略和排除临时工具后的原始模板绑定均恢复到初始指纹，见 [rc5-final-health.json](evidence/rc5-final-health.json)。

限制：两个 opt-in Docker 自动化用例未运行，其中包含子 JVM 崩溃恢复，不能以真实分析成功替代该用例。最初将“主动抛错”和“统计附件”作为两个目标的请求被原有多工作流安全规则拒绝；单目标统计任务内的实际错误与修复已验证通过。当前产品工具类型为受控模板，后端通过执行器接口扩展其他受控类型。
