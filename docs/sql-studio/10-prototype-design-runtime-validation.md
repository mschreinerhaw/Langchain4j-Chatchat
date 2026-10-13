# SQL Studio 原型优化与 Runtime 验证

日期：2026-10-13（Asia/Shanghai）。部署环境：`192.168.195.221:8080`，MCP `:8090`，Linux x86_64，部署目录 `/opt`。

## 主分支合并

远端 main 已合入 SQL Studio MVP，但缺少 `feature/sql-studio-mvp` 的六个后续提交。本次先将本地 main 快进到 `bf30efc6`，再完整合入功能分支 `375a1571`，合并提交为 `c23b6c6d`。无冲突，保留工作空间和 SQL IDE 的已有实现；本地与远端 main 已同步该合并。

## 原型对应的交付

- 浅色三栏工作台：项目资源、Monaco 编辑器、AI 工程助手。项目树增加文件/文件夹搜索与空结果提示，使用文件和目录图标。
- 顶部增加新建文件、格式化和保存操作，保留新建/删除工程、方言选择和面板开关；较窄桌面上工具栏换行，保存按钮保持可见。
- AI 面板增加当前文件卡片、对话/智能生成切换、六个快捷提问、相关资源与全部展开。快捷指令填入输入框，由用户发送；支持 `/` 指令提示、`@` 文件引用和 Mapping 文档选择。
- 输入框采用 Ctrl/Cmd+Enter 发送、Enter 换行。Runtime 任务仍在运行时禁止重复提交，取消后按真实终态恢复发送。旧工程异步失败不会写入新工程错误提示，文件刷新也校验工程身份后才更新。
- 首次打开工程自动打开已有 SQL；编辑状态、草稿、版本冲突与确认应用流程继续使用原有实现。

## 真实验证发现的路由问题

修复前，未选择维护型 Agent 的 SQL 请求仍固定使用 `agent_chat`。真实任务 `f8a70b8d-3293-3125-afef-e605f9b2cf02` 进入通用工具 Agent，最终返回分析报告而不是 `sql-studio.output.v1` JSON。Runtime 任务状态虽然为 SUCCESS，SQL 文件提案解析正确拒绝了该报告，没有应用无效产物。测试工程已删除，任务审计保留。

修复在应用适配器和现有模型入口完成：

1. 默认编程助手及模型模式使用已有 `llm_chat`；显式 Agent 继续使用 `agent_chat`，尊重原有 Agent 配置和授权链路。
2. SQL 工程适配器提供明确的 JSON 输出契约系统提示，Mapping 与参考文件仍作为不可信上下文处理；服务端继续严格解析路径和内容、检查版本并在确认后写入。
3. `LlmChatModeHandler` 使用 `ConfigurableChatModelFactory` 调用所选模型，未选择模型时使用配置的默认模型；调用前后检查 Runtime 取消信号，取消后不交付迟到答案。

没有新建模型执行器或并行 Runtime。调度、任务存储、权限、事件与证据核心保持原实现。`llm_chat` 路径不进行工具规划或工具调用；显式 Agent 仍依其已有配置执行，不将默认模型验证扩大为所有 Agent 配置的质量验收。

## 测试与生产验收

| 项目 | 结果 |
| --- | --- |
| 前端单元/组件测试 | 三组共 300 项通过；语法解析器首次动态导入在 Windows 超过原 5 秒测试限制，改为 30 秒后语法断言全部通过 |
| Vite 生产构建 | 通过；保留既有大 chunk 提示 |
| Chromium fixture 回归 | 工具栏响应、搜索、快捷指令、当前文件、键盘发送、运行/取消状态、建删文件、Mapping 导入、Agent/模型互斥、工程删除通过；无页面异常 |
| 后端定向回归 | SQL 集成 17、输出契约 2、纯模型入口 3、Runtime OS 架构 24，共 46 项通过，0 失败、0 错误 |
| 生产页面 | SQL Studio、Python 开发、智策对话均打开成功；1280/1024 桌面编辑区可用；格式化、保存、搜索和快捷指令可操作，0 浏览器异常 |
| 生产权限与版本 | 管理员登录、100 个授权 Mapping 文档候选、临时工程/文件保存通过；过期版本删除返回 409 |
| 真实模型生成 | 任务 `d7626d2c-2767-36fd-98f2-0fd5e1d670fb` 为 SUCCESS，生成并确认写入 `scripts/generated_customer.sql`，6 条任务事件，0 TOOL_CALL/TOOL_RESULT |
| 服务状态 | API health UP；MCP 管理员认证 HTTP 200；API 中注册 30 个 MCP 工具；验收后 active/pending/waiting/queue 均为 0 |
| 测试清理 | 两次验证创建的临时工程均已清理；原有 1 个工程保留，Runtime 审计历史保留 |

真实生成文件为 `SELECT customer_id, customer_name FROM dim_customer`，使用临时合成 Mapping，未连接数据库执行 SQL。结果的 `validation` 保持 `NOT_RUN`，不把生成成功当成数据库执行或业务正确性验证。

可复用验证脚本：`chatchat-api/web-app/scripts/verify-sql-prototype-production.mjs`。通过环境变量 `SQL_STUDIO_DEPLOY_BASE`、`SQL_STUDIO_DEPLOY_USER`、`SQL_STUDIO_DEPLOY_PASSWORD` 提供目标与登录信息；脚本会创建并清理临时工程，执行一次真实模型请求，凭证不写入文件。

审计产物位于 `target/sql-prototype-20261013/`：`deployed-sql-studio.png`、`verification.json`、`runtime-result.json`、修复前结果和两次部署日志。截图：[线上 SQL Studio](../../target/sql-prototype-20261013/deployed-sql-studio.png)。

## 发布与回滚

基于线上精确运行包制作补丁，保留旧带哈希静态资源，兼容已打开的浏览器页面。最终仅更新静态资源、`SqlStudioService.class` 和嵌套 Chat JAR 中的 `LlmChatModeHandler.class`；对其余非静态内容逐项比较字节一致，ZIP 完整性检查通过。未新增数据库迁移，未替换 MCP 包。

- 最终运行包：`/opt/chatchat-1.0.0-SNAPSHOT/lib/app/chatchat.jar`。
- 最终 SHA-256：`625de04b9affd3221bd2f9c6605a3d477c6bf42301cdfc9a6e5bf11130d67019`。
- 本轮开始前精确备份：`/opt/chatchat-deploy-backup/sql-prototype-20261013/chatchat.jar`，SHA-256 `e05d040a8577d00ddeaf2029109e1ede4518e3bd31c3e8e32100a9892dd883bd`。
- 最终补丁前精确备份：`/opt/chatchat-deploy-backup/sql-prototype-final-20261013/chatchat.jar`，SHA-256 `2ff786888845ce2395f573bee9d4c21de89a06bf9253d80ca73ea38bf78ca2f9`。
- 发布暂存与校验清单：`/opt/chatchat-deploy-staging/sql-prototype-final-20261013/deployment.json`。

回滚使用原有 `bin/stop.sh` / `bin/start.sh` 和精确备份，恢复前应核对当前运行包哈希，避免覆盖后续发布。本次验证备份和发布完整性，没有执行回滚演练。
