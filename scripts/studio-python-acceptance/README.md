# SQL / Python Studio 的真实服务验收

沿用正式 Studio、工具发布、Agent Runtime 和 MCP 接口。生成代码、编译通过、模板发现和 Python 执行分别取证；任何前置成功都不能替代下一阶段的实际运行。

准备经过授权的测试会话文件（含 `token`、`user`，不提交到 Git），使用 `scripts/sql-tpcds-acceptance` 建立或读取 SQL 测试状态；独立运行只读 JDBC 基准，提供以下 JSON：

```json
{"capturedAt":"actual execution time","baselines":{"yearly":{"source":"DIRECT_MYSQL_JDBC","readOnly":true,"rows":[]},"monthly":{"source":"DIRECT_MYSQL_JDBC","readOnly":true,"rows":[]}}}
```

这里的空数组仅展示文件结构，正式验收必须填入数据库实际返回记录。查询定义和独立 JDBC 程序位于 `scripts/sql-tpcds-acceptance/`。不能把工具说明、编造记录或模板目录充当数据输入。

```powershell
$env:STUDIO_ORIGIN='http://your-test-server:18100'
$env:STUDIO_AUTH_FILE='C:\private\test-session.json'
$env:STUDIO_OUTPUT='target/studio-python-acceptance-new-run'
$env:STUDIO_SQL_STATE='target/tpcds-live-acceptance/state.json'
$env:STUDIO_JDBC_FILE='C:\private\fresh-jdbc.json'
node scripts/studio-python-acceptance/live.mjs generate
node scripts/studio-python-acceptance/live.mjs poll
# 仅在 COMPLETED 后继续。refine 是正常的第二次 Cline 审查修正，不是手工伪造输出。
node scripts/studio-python-acceptance/live.mjs refine
node scripts/studio-python-acceptance/live.mjs poll
node scripts/studio-python-acceptance/live.mjs save
node scripts/studio-python-acceptance/live.mjs trial
node scripts/studio-python-acceptance/live.mjs publish
node scripts/studio-python-acceptance/live.mjs connection
node scripts/studio-python-acceptance/live.mjs agent
node scripts/studio-python-acceptance/live.mjs ask
node scripts/studio-python-acceptance/live.mjs poll-agent
```

每轮使用新的输出目录；首次执行在 `config.json` 冻结唯一脚本文件名、连接域和 Agent ID。后续命令沿用该配置。`poll` 没有完成时继续轮询，失败时保存原错误，不能跳过生成或试运行门禁。Python 执行器由发布协议和运行时权限解析，不手工绑定隐藏执行器、不扩大到整个 MCP 服务。

在正向试运行和发布后，可以对本轮开发脚本执行负向检查；这不修改已发布源快照：

```powershell
node scripts/studio-python-acceptance/negative.mjs
node scripts/studio-python-acceptance/live.mjs trial
```

检查覆盖更改 Schema 后重用旧证据被拒绝、缺少年度输入、空月度输入、重复月度键、非有限金额、非法月份，及失败后原成功标记清除。恢复成功试运行后才能进行下一次发布。

最终从本轮 `observations-{runId}.json` 核验实际模板执行、实际 SQL→Python 参数映射、独立 JDBC 字段及指标：

```powershell
node scripts/sql-tpcds-acceptance/reconcile-template-python.mjs observations.json fresh-jdbc.json proof.json published-template.json
```

没有 Python 执行回执、只发现模板、只做临时 Cline 分析，均会被此核验拒绝。输出证明不认证金额币种或其它未声明的业务语义。

正式页面截图与独立图表导出（必须先取得真实成功的对账证明）：

```powershell
$env:STUDIO_SCREENSHOTS='C:\private\acceptance-artifacts'
node chatchat-api/web-app/scripts/test-studio-python-publication-live.mjs
node chatchat-api/web-app/scripts/test-studio-service-chain-live.mjs
node chatchat-api/web-app/scripts/render-studio-python-result.mjs proof.json trend.png
```

页面脚本使用真实登录和真实任务，不改执行计划、不 mock 接口。图表脚本只导出 proof 中已验证的实际 figure，不充当应用页面截图。
