# Code Intelligence 实施与验证报告

## 实际变更

- 通用前端：`src/js/editor/{codeIntelligence,sqlDiagnostics}.js`、`src/components/editor/EditorPreferences.vue`、对应外部 JS/CSS；`package.json` 和 lockfile 增加 `dt-sql-parser`、`sql-formatter`。
- Python：`DataScienceView.js/.vue` 接入统一选项、偏好、Ruff 诊断/格式化、模型版本保护；`api.js` 增加工具调用。`PythonDataScienceController`、`PythonDataScienceService`、`PythonRuntimeService`、`PythonRepositories` 增加授权工具接口及保存版本检查；runtime 依赖清单增加 Ruff。
- SQL：`SqlEditorPane`、`SqlStudioView` 接入方言格式化、选中 Mapping 字段补全及语法标记。按 Vue 模板规范，SQL Studio 五个 Vue 组件的逻辑移至 `src/js/views` 和 `src/js/components/sql-studio`。原 SQL 文件、任务、Agent API 无变动。
- 测试：新增 Code Intelligence 单测；扩展 SQL 浏览器测试与 Python 授权/版本冲突测试。

## 验证结果（2026-10-13，本地）

| 检查 | 实际结果 |
| --- | --- |
| 前端 `npm run verify` | 28 组/280 项 + SQL Studio 8 项通过；Vite 构建通过 |
| 新增 `vitest run src/js/editor/codeIntelligence.test.js` | 10 项通过：偏好隔离、两种 SQL 格式化、Python 块缩进、Mapping 白名单、冲突保护，以及 Spark/Hive/Trino/MySQL/PostgreSQL 语法标记 |
| SQL Studio Chromium fixture 测试 | 通过：新增一键格式化和缩进偏好检查；原多标签、草稿、Mapping、任务与保存用例继续通过 |
| Workspace Chromium fixture 测试 | 通过：Python `def`/`if` 换行缩进、Shift+Tab/Tab、一键格式化离线降级及原 Workspace 切换 |
| 后端 Maven 目标回归 | 66 项通过，包括 Python Asset 越权、保存版本冲突、SQL/Workspace/ObjectAccess/Runtime 架构边界 |
| Python runtime Ruff 实际调用 | 未验证：本地 Docker daemon 不可用，未连接生产服务器；新镜像尚未构建部署 |
| 真实模型/真实 Mapping 端到端 SQL 生成 | 未执行；浏览器测试使用 HTTP fixture，不宣称真实生成验证 |

## 未完成及风险

1. Ruff 已配置到新 runtime 镜像并接入接口，但既有镜像不具备该依赖。部署前应在隔离测试环境构建镜像，验证 `ruff format`、`ruff check --output-format json` 的输出契约、超时与资源限制。
2. Pyright/LSP 尚未接入；Python 补全沿用现有本地 provider，跨文件类型诊断和导航未实现。
3. SQL Oracle/T-SQL/ANSI 可格式化，但当前没有对应解析器诊断；SQLFluff/SQLGlot 工程级校验未实现。字段提示来自被选中的 Mapping 文本，不是实时数据库 Schema。复杂 CSV 引号、多层 JSON Mapping 需要正式 Schema 契约后扩展。
4. `dt-sql-parser` 初次按需加载约需数秒，本地构建的共享编辑器 JS chunk 约 3.24 MB；后续应按方言拆包或放到 Web Worker 以改善首次编辑延迟。当前大文件超过 200 KB 时跳过语法诊断。
5. Python 保存接口为兼容旧客户端，缺省 `expectedVersion` 仍允许旧式保存；新客户端已传版本。全量强制 CAS 需另行安排兼容窗口。
6. 本次未执行生产部署、未访问生产数据库，也未验证容器中的 Ruff 安装状态。
