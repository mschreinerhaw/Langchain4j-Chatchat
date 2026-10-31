# 真实环境复验

运行位置为仓库根目录。需要 Node 22+、已授权网页登录会话，以及正式服务地址。不要将登录 token、数据库凭据或完整内部配置提交 Git。

PowerShell 示例（登录会话文件为 `{ "token": "…", "user": { "tenantId": "…" } }`）：

```powershell
$env:TPCDS_ORIGIN='http://测试服务器:18100'
$env:TPCDS_AUTH_FILE='私有登录响应文件的绝对路径'
$env:TPCDS_OUTPUT='target/tpcds-live-acceptance'
node scripts/sql-tpcds-acceptance/live.mjs setup
node scripts/sql-tpcds-acceptance/live.mjs generate
# 模型完成后执行；尚无提案时会失败，稍后再次查询，不应伪造文件。
node scripts/sql-tpcds-acceptance/live.mjs generated
node scripts/sql-tpcds-acceptance/live.mjs draft
# 如绑定数据源配置了白名单，通过既有数据源管理批准刚输出的两个模板编码。
# 保留原有白名单和连接配置；不可禁用校验以通过验收。
node scripts/sql-tpcds-acceptance/live.mjs test
node scripts/sql-tpcds-acceptance/live.mjs publish
node scripts/sql-tpcds-acceptance/live.mjs agent
node scripts/sql-tpcds-acceptance/live.mjs ask
node scripts/sql-tpcds-acceptance/live.mjs poll
```

`poll` 保存真实事件和完整运行器观察；终态必须存在 `python_visual_report.v1`、执行 operationId、停止证明及哈希匹配的真实代码/图表产物。仅有文字回答、PARTIAL、工具列表、接口成功或空结果都不算 Python 验收通过。脚本不替换模型 SQL，不伪造 MCP 身份，不修改任务状态。

阶段状态保存在输出目录的 state.json，可恢复生成、草稿和轮询。创建新一轮应指定新的输出目录。发生模板授权清单变更时，旧发布依赖快照可能失效，应使用已有 SQL Studio 历史恢复操作重新真实试执行并确认当前线上版本；不可关闭新鲜度校验。

## 独立数据库对账

`TpcdsJdbc.java` 使用独立 JDBC 连接、只读事务模式、10 秒超时及 100 行上限，接受运维审查后的 SELECT 基准文件。它不经过模板渲染器。私有 properties 的 url/user/password 值是 UTF-8 的 Base64 编码；Base64 不是加密，文件必须保持最小权限并留在测试环境。MySQL driver 在实际运行时由 `-cp` 提供，编译只需 JDK 17。

```powershell
javac --release 17 -d target/tpcds-jdbc scripts/sql-tpcds-acceptance/TpcdsJdbc.java
# 由具备数据源授权的运维在受控环境提供私有 properties、独立基准 SQL 和 JDBC driver。
# 将真实查询的 JSON 标准输出保存为 baseline.json，不应手工填入返回值。
node scripts/sql-tpcds-acceptance/reconcile.mjs observations.json baseline.json 精确的本地MCP工具名 comparison.json
```

比较器只读完整的真实 `stepOutput.data` 信封，拒绝截断结果，逐字段对比日期、字符串、NULL 及十进制值，忽略金额表示中的无意义尾零，不做容差掩盖。基准 SQL 应独立表达同一业务口径；本次采用 YEAR/MONTH/日期 BETWEEN，未复制模型 SQL 作为对账实现。金额单位未声明，不能默认人民币。

版本、停用、回滚、特殊参数及失败记录见 `docs/sql-tpcds-e2e` 的本次验收报告。这些操作应在独立验收模板组上进行，禁止对业务在用模板或其它用户任务执行停用。

## 版本生命周期

等待上一轮 Agent 任务终结后，在同一输出目录使用下列命令。新修订只改变默认数量 0 → 50，SQL 仍是模型保存的原文件；若数据源要求模板编码白名单，必须先通过正常管理流程批准新编码，再试执行。白名单变更可能使年度旧快照过期，应先通过正常恢复操作重新试执行并发布年度服务。

```powershell
node scripts/sql-tpcds-acceptance/lifecycle.mjs revise
node scripts/sql-tpcds-acceptance/lifecycle.mjs test
node scripts/sql-tpcds-acceptance/lifecycle.mjs publish
# 正常提交一个新的 Agent 问题，沿用月度服务默认值；核对实际使用 50 的结果。
# 不重新绑定 Agent，不重写工具定义。等待任务终结，再执行停用。
node scripts/sql-tpcds-acceptance/lifecycle.mjs offline
# 正常提交月度查询，确认 Agent 无法正常调用已经停用的服务；等待终结。
node scripts/sql-tpcds-acceptance/lifecycle.mjs rollback
# 正常再次查询默认月度结果，使用独立 JDBC 的 >= 0 基准逐字段复验。
```

`offline` 同时核对 SQL 定义的 active 状态；`rollback` 要求没有活动 head，并真实重新试执行后恢复原修订。同一工具标识不变。命令自身的 HTTP 成功不代表 Agent 验收通过，必须另外保存真实任务的调用参数、返回行和对账结果。

最终报告数值复核（精确整数分值，不使用浮点容差掩盖差异）：

```powershell
node scripts/sql-tpcds-acceptance/reconcile-python.mjs docs/sql-tpcds-e2e/python-visual-report.json docs/sql-tpcds-e2e/yearly-jdbc-baseline.json docs/sql-tpcds-e2e/monthly-direct-jdbc-baseline.json target/tpcds-python-recheck.json
```

运行成功、产物完整与业务检查是不同证据。`poll` 验证真实 Python 退出、持久回执和下载文件哈希，保留原始业务验证状态；`reconcile-python` 独立验证计算结果，不会把缺少月份、未知单位或未认证业务口径改为通过。
