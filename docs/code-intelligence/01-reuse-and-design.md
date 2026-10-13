# Code Intelligence 现状核查与增量设计

## 现有能力与复用

| 能力 | 代码证据 | 本次处理 |
| --- | --- | --- |
| Monaco 与多标签 | `DataScienceView.js` 现有 Python Monaco；`SqlEditorPane` 每个工程文件一个 Monaco model，保存 view state | 原编辑器、模型和标签保持原样；共用编辑器选项和语言适配函数 |
| Python 补全 | `pythonCompletions.js` 与 `DataScienceView.js` 已注册 Python provider | 保留 provider；统一缩进、Enter、Tab、Ctrl+Space 与格式化快捷键 |
| Python 格式化 | 原 `formatPythonSource` 只整理空白 | 作为离线降级；授权 Python Asset 上运行 Ruff 格式化与检查 |
| SQL 文件、Mapping、版本 | SQL Studio `SqlStudioService` 与 `SqlStudioView` 的文件 API、草稿和 `expectedVersion` | 继续使用现有工程 API，不新建文件或 Agent 执行状态机 |
| 用户与授权 | Python Asset 按租户和 owner 查询；SQL Project/File 在服务端按对象授权 | Ruff API 先检查 Asset 归属；Schema 提示仅来自已获授权且在当前工程选中的 Mapping 文件 |
| AI 与 Runtime | Python AI 编程助手、SQL 生成任务沿用现有 Agent API | 编辑器基础能力完全不依赖模型；不修改 Runtime OS Core |

## 增量结构

`src/js/editor/codeIntelligence.js` 提供 Python/SQL Language Adapter、Monaco 公共选项、用户+语言偏好键、Mapping 字段提取和带模型版本检查的格式化写回。`sqlDiagnostics.js` 按需加载语法解析器。两个 Studio 使用同一个 `EditorPreferences` 组件；Vue 文件仅含模板以及外部 JS/CSS 引用，业务逻辑置于 `src/js/...`，样式置于 `src/styles/...`。

默认 Python 四空格、SQL 两空格；可分别选择 2/4/8 空格并启停自动提示。Monaco 保留原生 Tab/Shift+Tab 和 Ctrl+Space；`acceptSuggestionOnEnter: off` 确保 Enter 换行，Python 块头后的 Enter 按当前缩进宽度明确增加一级，使用 Tab 或 Ctrl+Space 接受建议。Shift+Alt+F 调用各语言适配器。自动格式化不自动保存，也不会覆盖格式化请求期间发生变化的编辑模型。Python 现有脚本保存现提供可选 `expectedVersion`；新前端发送版本，旧客户端不带版本仍可使用原接口。SQL 原有保存版本检查保持不变。

SQL 方言格式化使用 [sql-formatter 官方支持列表](https://github.com/sql-formatter-org/sql-formatter/blob/master/docs/dialect.md)；Spark/Hive/Trino/MySQL/PostgreSQL 语法诊断使用 [dt-sql-parser](https://github.com/DTStack/dt-sql-parser/blob/main/README.md)。没有采用 monaco-sql-languages：其 [README](https://github.com/DTStack/monaco-sql-languages/blob/main/README.md) 声明的 Monaco 兼容基线与本工程 Monaco 0.52.2 不一致。[SQLFluff](https://docs.sqlfluff.com/en/latest/reference/dialects/) 适合后续工程级风格与规则校验；[SQLGlot](https://sqlglot.com/sqlglot.html) 适合后续 AST 和方言转换。二者暂未接入：现阶段的浏览器本地编辑器功能不需要启动容器；后续 SQL 工程级校验应走服务端授权工具接口。

Python Ruff 通过 `POST /api/v1/data-science/python/code-tools/ruff-format` 和 `/ruff-check` 调用。请求 `{assetId,source}`，响应沿用 `ApiResponse<ExecResult>`，其中 `exitCode/stdout/stderr/timedOut` 来自固定参数的 `docker exec -i ... ruff format|check`。服务端核验租户、用户和 Asset READY 状态；浏览器不接触 Docker 地址或容器文件系统。`python-runtime/requirements.lock` 声明 Ruff；既有容器镜像需要重建后才会具备该工具。

[Ruff 官方说明](https://docs.astral.sh/ruff/editors/)指出其 LSP 侧重格式化、诊断与修复，完整导航和智能补全通常还需要另一 Python 语言服务。当前保留本地 Python 补全；[Pyright](https://github.com/microsoft/pyright/blob/main/docs/features.md) 的类型推断、跨文件解析与 LSP 代理列为下一阶段，不在本次 MVP 中宣称已实现。

## 权限与兼容性边界

- SQL 字段提示只提取服务端已返回的当前项目 Mapping 内容，并且要求该 Mapping 被用户选中；它不是数据库实时 Schema 探查。项目文件对象授权仍由服务端负责。
- Python Ruff 请求先校验 Asset 对象所有权；前端所传 `assetId` 仅定位对象，不能授权。没有独立编辑器身份体系。
- Python 脚本原有保存接口支持旧请求；新请求带 `expectedVersion`，服务端锁定脚本行后检查版本。没有数据库迁移。
- 格式化、诊断不修改 Agent Runtime，也不依赖模型服务。SQL 解析失败时编辑仍可进行；Python Ruff 不可用时仍可用本地缩进整理和已有补全。
