# SQL Studio MVP 实际测试报告

日期：2026-10-13。基线 `b88d8dd9`；分支 `feature/sql-studio-mvp`。仅本地开发、H2 测试数据库及 HTTP/模型夹具，未连接生产数据库、未部署、未合并 main。

## 最终结果

| 验证 | 实际结果 | 证据 |
| --- | --- | --- |
| 后端定向回归 | **19 套件、95 项通过，0 failure/error/skip** | `target/sql-studio-backend.log`、各模块 Surefire XML、`target/sql-studio-audit/verification.json` |
| 新 SQL 后端测试 | 集成 12 项 + 输出契约 2 项通过，包含真实 HTTP 身份、中央对象授权、JDBC/H2 事务及原任务调度器；模型编排返回夹具 | `SqlStudioIntegrationTest`、`SqlOutputBundleTest` |
| 前端既有回归 | **28 文件、280 项通过** | `target/sql-studio-frontend.log` |
| SQL 前端测试 | **8 项通过**；草稿、版本、请求期间编辑及原 Agent API/Multipart 传输 | 同上及 `sqlStudioState.test.js` |
| Vue/Monaco 浏览器 | **通过**；真实 Chromium、实际组件和 Monaco，后端/模型为 HTTP 夹具 | `target/sql-studio-browser.log`、`target/sql-studio-audit/sql-studio.png` |
| 原 Workspace/Python 页面浏览器 | **通过**；既有登录选择、切换效果、Python 页面布局、对话草稿、旧链接及拒绝空间 | `target/sql-studio-workspace-browser.log` |
| 前端构建 | **成功，16.51 秒**；保留已有大 chunk 提示 | `target/sql-studio-frontend.log` |
| TypeScript 契约 | **tsc --noEmit --strict --skipLibCheck 通过**；检查声明文件，不等于全应用类型检查 | `target/sql-studio-contract-typecheck.log` |
| API/依赖模块发布包 | **package 成功，退出码 0**；先独立测试/构建后使用 backend-only 与 skipTests 打包 | `target/sql-studio-package.log` |
| JAR 内容核查 | SQL Controller/Service/Migration、三种 schema、SQL Vue chunk 均存在，静态入口引用的资源齐全 | 本地 JAR；`verification.json` |
| 迁移 | H2 新建、重复运行、自定义配置保留通过；三种交付脚本与打包资源内容一致 | 集成测试及脚本一致性核查 |

包为 `chatchat-api/target/chatchat-api-1.0.0-SNAPSHOT-release.zip`（370,052,358 字节），只作为本地审查产物。源码树中的既有生成入口恢复基线；发布必须先构建前端，不能直接使用旧静态入口或跳过首次前端构建。

## 用户指定的十项验证

| 项目 | 结果与边界 |
| --- | --- |
| 单 Mapping → 单 SQL | 通过原任务 API 提交夹具回答，预览前 SQL 文件不存在，确认后落库并可编辑、保存、下载；**真实模型理解 Mapping 未验证** |
| 多 Mapping → 多 SQL | 冻结两个输入，模型回答夹具包含两个文件，事务保存成功；**模型自主拆分工程未验证** |
| 历史脚本风格 | 授权案例选择、内容/版本冻结、提交上下文通过；准备后修改参考不改变快照；**真实模型遵循风格未验证** |
| Spark SQL 保存 | spark 方言上下文、SQL 源文保存和下载通过；**未连接 Spark，语法/语义/执行校验未运行** |
| 多标签及恢复 | 真 Monaco 中两文件切换、未保存内容保留、切出工作空间后恢复、刷新后保存内容恢复通过 |
| 用户/Agent 并发 | 陈旧基准 409；整批回滚无部分写入；路径大小写冲突拒绝；前端阻止未保存覆盖，并保留应用请求期间新输入的草稿 |
| 越权文件访问 | 不同用户/组织读、版本、下载、写、案例引用、生成任务关联及应用均拒绝；持久资源跨 tenant 拒绝；伪造 Workspace Header 不授予权限；License/RBAC 拒绝有效 |
| 切换后任务恢复 | SQL taskId/会话/结果持久恢复通过；既有 WorkspaceIntegrationTest 验证运行中任务切换后继续并仅一份 WorkflowRuntime |
| Python 无回归 | Python 后端 16 项、PythonStdoutRecordProjector 2 项、既有前端 DataScienceView 6 项及 Python 页面浏览器通过；**未启动真实 Python Docker 执行** |
| Agent Runtime 无回归 | 架构边界 24 项、会话 HTTP 11 项、中央对象策略 12 项、Workspace 10 项、执行身份/状态/事件/调度 6 项通过；**不是整个 Runtime 全量测试，也不是真实模型/工具端到端回归** |

## 后端套件计数

SQL 集成 12、输出契约 2；PythonCodeAssistant 4、PythonFileType 2、PythonScriptDeletion 3、PythonEnvironmentStatus 1、PythonSystemExampleCatalog 2、PythonTemplatePublication 3、PythonTemplateToolRegistry 1、PythonStdoutRecordProjector 2；RuntimeOsArchitectureBoundary 24、ConversationController 11、ObjectAccessPolicy 12、WorkspaceIntegration 9、WorkspaceMigration 1、AgentExecutionIdentity 2、AgentExecutionState 2、AgentEventBusMultiTenantConcurrency 1、AgentRuntimeTaskSchedulerConcurrency 1。合计 95。

原工作空间阶段的 15 项失败分类与修复记录保留在 `docs/workspace/03-test-report.md`。本轮重新运行对应架构 24 项和会话 11 项均通过，未通过删除断言或放宽生产授权解决问题。早期 Python/Workspace 定向基线通过；SQL 加入后的最终范围如上，无本轮执行范围内未解决失败，不宣称整个仓库零失败。

## 可复现命令

仓库根目录 PowerShell：

```powershell
mvn.cmd -Pbackend-only -pl chatchat-api -am "-Dtest=Sql*Test,Python*Test,Workspace*Test,ObjectAccess*Test,RuntimeOsArchitectureBoundaryTest,ConversationControllerTest,AgentRuntimeTaskSchedulerConcurrencyTest,AgentExecutionIdentityTest,AgentExecutionStateTest,AgentEventBusMultiTenantConcurrencyTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

`chatchat-api/web-app`：

```powershell
npm.cmd ci --no-audit --no-fund --prefer-offline
npm.cmd run verify
npm.cmd run test:sql-studio:browser
node scripts/test-workspace-shell.mjs
npx.cmd --yes --package typescript -- tsc --noEmit --strict --skipLibCheck src/services/sqlStudioContracts.d.ts
```

先构建前端，再在仓库根目录打包：

```powershell
mvn.cmd -Pbackend-only -pl chatchat-api -am package "-DskipTests"
```

首次验证发生 Windows npm ci/前端构建共用 node_modules 的文件锁冲突，已分离依赖安装与验证；不计作通过。浏览器初次断言根据 Monaco 的 NBSP 和 Shell 恢复末页行为校正；实际捕获的 Monaco occurrence-highlighter Delayer 取消异常则通过 SQL 编辑器局部关闭可选 occurrence 高亮修复。最终浏览器断言无 pageerror。没有忽略测试失败或屏蔽全局浏览器错误。

## 未验证与风险

1. 未配置真实模型或业务 Mapping，不能声明端到端 ETL 工程生成已验证。严格 JSON 文件包不匹配时只展示原回答，不自动猜测、拆分或覆盖。
2. Validation 始终为 NOT_RUN。sqlglot/sqlparse 未在 runtime 依赖清单中声明；本地 Docker daemon 不可用，实际安装版本未知。后续应通过现有授权 MCP/Skill 的离线校验工具接入，不增加 SQL Loop。
3. MySQL/PostgreSQL 脚本未在实库执行；上线前需在隔离克隆环境验证 DDL、权限、字符集、备份和回退。定制 Workspace Manifest 要手动合并新页面，脚本不覆盖定制配置。
4. 工程当前个人所有；未增加团队共享、重命名/删除、工程 ZIP 导出、版本差异/恢复、二进制旧版本下载或全库 SQL 案例索引。原 Python 文件与历史不迁移。
5. 文档预览为 Tika 提取文本；表格布局/合并单元格、扫描 PDF/OCR、复杂 Office 文档质量未逐项验证。复用解析器无通用硬超时，复杂恶意文档的资源限制需后续隔离验证。
6. 每文件 2MiB 上传/200000 字符、上下文 250000 字符、生成最多 32 文件；版本与上下文持久化会增长。缺少工程容量配额、归档/保留策略，大工程需后续分页及存储治理。
7. 浏览器草稿沿用现有每命名空间 2MiB/浏览器配额限制；存储失败会提示并阻止切换工程，离开未保存页面触发原生提醒。浏览器存储不作为文件权限或已提交文件的唯一来源。
8. 选择模型/Agent 仍须原 API 授权，默认配置是否发布、工具是否可用由原 Runtime 判断。等待确认任务应进入现有任务管理处理，本轮没有独立 SQL 确认/调度器。

下一阶段先用隔离模型、真实脱敏 Mapping 与历史案例验证单/多文件输出及风格，再接授权的离线方言校验，最后验证 MySQL/PostgreSQL 克隆迁移。完成审查前不部署、不合并主分支。
