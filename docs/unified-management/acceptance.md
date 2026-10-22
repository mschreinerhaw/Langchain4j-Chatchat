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

本地扩大回归已通过 99 个后端测试，0 失败、0 错误、0 跳过；前端四组相关回归 63 项通过，两个前端构建通过。最终目标资源复核的新增测试与最终包验收结果在交付前补充到 validation.json。

远程验收、运行包 SHA-256、浏览器和真实 Runtime 结果在部署后补充。未执行的检查不得视为通过。
