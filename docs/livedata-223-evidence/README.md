# LiveData测试库_223 验收证据索引

所有时间均来自 2026-10-16 的真实平台任务。目录不包含登录密码、API Key 或数据库凭证。

## 业务任务

| 文件 | 内容 |
|---|---|
| `db-ops-submission.json` / `db-ops-status-events.json` / `db-ops-answer.json` | 首次完整需求；因 `QUERY_REQUIRES_STRUCTURED_CONSTRAINTS` 失败，未执行 SQL |
| `db-ops-retry-submission.json` / `db-ops-retry-status-events.json` / `db-ops-retry-answer.json` | 结构化约束重试；因 `QUERY_DSL_BUDGET_EXCEEDED` 失败，未执行 SQL |
| `db-ops-discovery-submission.json` / `db-ops-discovery-status-events.json` / `db-ops-discovery-answer.json` | 成功的资产、InnoDB、事务和配置任务 |
| `db-ops-capacity-submission.json` / `db-ops-capacity-status-events.json` / `db-ops-capacity-answer.json` | 成功的连接、状态和空间任务 |
| `db-ops-executed-sql-references.json` | 8 个最终成功模板的 SQL、哈希、采样时间、行数与 complete 状态 |
| `db-ops-discovery-deterministic-validation.json` | 对 InnoDB 与配置数据的独立计算复核 |
| `db-ops-capacity-deterministic-validation.json` | 对连接、状态和空间数据的独立计算复核及模型错误清单 |

## 完整 Runtime Evidence

两个压缩包保存对应成功任务的 runtime run、steps、observations、timeline 和 trace 原始 JSON。为避免仓库中重复保存数十 MiB 的展开文件，只保留压缩归档和可审阅的摘要/验算文件。

| 文件 | SHA-256 |
|---|---|
| `db-ops-discovery-runtime-evidence.tgz` | `9149c8cc9ef2a91823af9ecdc3fa386a99c3515d055d1ce3051841028cf15b31` |
| `db-ops-capacity-runtime-evidence.tgz` | `a0279b67f975a416d71d053beabe96071750fb70b8b31ddbdaffc91d537933c6` |

## 环境与门禁

| 文件 | 内容 |
|---|---|
| `deployment-manifest.json` | 本地/远端产物哈希、隔离 Node 容器和回滚信息 |
| `focused-gate-rerun.json` | Tool Access、MCP discovery、授权隔离定向复跑结果 |
| `python-sandbox-fixture.py` / `python-sandbox-validation.json` | 目标机限制环境中的 Python fixture 与输出验证；不属于本次数据库业务执行 |
| `business-validation-manifest.json` | 四次平台提交、两次成功运行、模型用量和证据归档总索引 |

原始 Agent 回答保留用于审计，但最终业务结论以结构化 Evidence 和 deterministic validation 为准。
