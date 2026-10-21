# SQL Studio 用户空间文档参考

文档引用现已统一移至 AI 工程助手的“工程上下文与执行设置 → 文档与参考脚本”，左侧用户空间文档区域已移除，详见 [简洁对话与 Agent 隔离](sql-assistant-simple.md)。用户可上传文档，或选择自己空间中的已有文档，不再需要先将资料导入为工程 SQL 案例。

每个选中文档可以指定用途：

- **参考规则（RULES）**：SQL 风格、命名约定、编码规范、业务口径约束等。
- **分析依据（EVIDENCE）**：表字段说明、计算公式、业务定义、需求与示例数据等。

发送给助手时，所选文档的完整提取文本、用途、文件名、原件 SHA-256 和大小随本次生成计划保存。Cline 读取这些已授权上下文，应用相关规则和定义，并在候选代码说明中引用来源；不自动选择空间中的其他文档。规则不能扩大工具权限，也不能授权数据库连接或 SQL 执行。存在冲突或缺少定义时需要说明。

上传使用原有用户空间接口，原件保存到 Silo，文件归属由 PostgreSQL 管理。Cline 首次启动前，原件再按任务、运行范围归档。已归档任务恢复时使用相同原件；原件在首次提交前被删除会明确报错。生成结果仍进入原有代码审查和版本化保存流程，不直接覆盖工程文件。

取消勾选仅影响后续生成，不删除用户空间原件。当前选择及用途随工程草稿恢复；已不可读取的选项会显示提示，用户可以取消引用。历史生成保留原来的快照，新任务不自动携带旧 SQL 案例。

支持 PDF、DOCX、XLSX、CSV、TXT、MD、JSON、JSONL、SQL，每次最多 8 个文件、原件合计 16 MiB。提取文本合计最多 180,000 字符，工程、文档与计划合计最多 250,000 字符。超过限制、解析失败、无可读文本均明确拒绝，不截断后继续生成。当前选择器列出最近 100 个文档，并保留已选的较早文档，可按文件名筛选。

## 接口增量

`POST /api/v1/data-science/sql/projects/{projectId}/generations` 新增可选字段：

```json
{
  "prompt": "按资料定义生成每币种净销售额 SQL，遵循参考规则",
  "dialect": "postgresql",
  "modelName": "qwen3.7-plus",
  "userDocuments": [
    {"fileId": "<用户空间文件 ID>", "purpose": "RULES"},
    {"fileId": "<另一个用户空间文件 ID>", "purpose": "EVIDENCE"}
  ]
}
```

文件 ID 来自 `POST /api/v1/data-science/user-assets` 的 multipart 上传返回值 `data.id`，或当前用户文件列表。服务器同时校验工程归属与文件归属，拒绝跨租户、跨用户、重复选择、非法用途及任务代码等非文档资产。旧的请求字段保留兼容性；新 UI 使用用户空间文档字段。

## 验证

本地后端相关测试 17 项通过；另有 8 项原有 SQLGlot 测试因本机没有配置 `coding.sqlglotPython` 而跳过。前端相关测试 23 项通过，Maven 与 Vite 打包完成。后端归档内容与最终部署包的非静态资源逐字节一致。

服务器验证使用真实浏览器上传规则 Markdown 和业务定义 JSON，选取 RULES/EVIDENCE 两种用途，调用真实 Cline 生成 SQL，并用服务器现有 SQLGlot 解析。验收检查独有规则标识、资料中表名/字段/公式、引用来源、原件哈希、任务归档及停止回执。候选 SQL 没有执行，也没有自动保存到工程。

权限验收覆盖外用户文件、重复选择、非法用途、工程权限、匿名请求与 SQL 原件字节回读；临时测试用户、角色和无用文件均已清理。详细记录见 [验收证据](sql-studio-user-documents-20261021-evidence/browser-acceptance.json)、[权限证据](sql-studio-user-documents-20261021-evidence/permission-acceptance.json)、[本地验证](sql-studio-user-documents-20261021-evidence/local-validation.json)。

最终部署镜像为 `runtime-os-api:user-assets-ee69dddfc621`，Jar SHA-256 为 `ee69dddfc6215389b30d34863eac55ad2ffbe4b008b09d3531d6980347a1c96a`，1,136 个后端条目与测试包字节完全一致。[部署与回退备份](sql-studio-user-documents-20261021-evidence/deployment.json)。

最终真实任务 `sql-coding-0a6b0b55-682a-430e-9244-0176869155db` 完成，生成 `sql/net_sales_by_currency.sql`，包含独有规则标识 `policy_7333698ddf` 及资料中声明的 `sales_fact`、`gross_amount`、`discount_amount` 和按币种汇总公式。2 份原件、1 份候选代码及 1 份上下文共 4 个归档对象通过逐字节大小与 SHA-256 检查；`executorStopped=true`、`reconciliationRequired=false`，结束时活动 Node 会话及 Python 任务容器为 0。[任务归档](sql-studio-user-documents-20261021-evidence/archive-acceptance.json)。

验证边界：该任务的模型说明把一份原件哈希抄错了一个字符，权威任务快照与 Silo 归档哈希均正确；不能拿模型复述的哈希作为完整性校验。SQL 语法通过，字段与业务真实性未执行验证，原生验证状态为 `INCONCLUSIVE`，费用计量 `meteringComplete=false`。真实付费模型验证使用 Markdown/JSON，没有逐一验证所有文档格式。
