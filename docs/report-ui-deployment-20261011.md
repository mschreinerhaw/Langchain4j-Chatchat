# 报告与探索图部署验证

2026-10-11 已部署至 192.168.195.221。更新 API 的静态资源与内嵌 Harness 类，恢复可选可视化能力注入；更新 MCP 的外部检索参数通用描述。保持原生数据库驱动、探索事件进度和其余生产模块。未修改业务资产、模型或鉴权配置。

## 部署与回滚依据

- 备份：`/opt/chatchat-deploy-backup/report-ui-20261011/api.jar`、`mcp.jar`。
- 暂存及完整性记录：`/opt/chatchat-deploy-staging/report-ui-20261011/deployment.json`。
- API SHA256：`8d9ce0401920c5b21bdd1cad75071b8e559f448b4472420897a20d7285a451df`。
- MCP SHA256：`cbca3d7c696ccb2f323b1b4364aae99bd06884d90ff22751fe6a161e4cbaa121`。
- 重启前检查当前租户 RUNNING / WAIT_MODEL / WAIT_TOOL / QUEUED / PENDING 均为 0。MCP 标准停止脚本超时后按内置逻辑强制停止；新实例正常启动。
- API PID 2129643，MCP PID 2129585；MCP gRPC 9091 正常启动。

完整性验证比较部署包与补丁所有目标字节：70 份静态资源、Harness 类及 MCP provider 类均一致；确认 Harness 包含恢复后的 injectReportDraft 引用，三类官方客户端仍在包内。回滚时应使用上述备份和标准停止/启动脚本，并核对当前部署哈希；不要覆盖较新的部署。

## 生产验收

1. 登录后 API `/api/v1/health` 返回 UP。
2. API 调用 MCP discover 成功，返回 30 个工具。
3. 生产 OpenSearch 原生连接只读探测通过，耗时 426ms。
4. Edge 无头浏览器加载生产静态资源，打开实际探索图，读取历史任务 `7e7d3db6-0020-3cc7-af94-4f5bbaac35fc`：14 个节点、13 条关联、0 个运行事件节点；页面不含“显示运行事件”开关，已截图确认原占位区缩减。
5. 同一浏览器加载生产聊天组件，在浏览器内存放入原故障股票 JSON 与非金融库存 JSON：均生成结构化摘要卡片；标题、结论及前后正文保留，没有 datasetRef / chartType / encoding 原文或 pre 代码块。样例缺少实际数据，因此明确显示暂无法生成图表或表格；不编造数据。

浏览器样例仅存在内存，没有保存新会话或提交新模型任务。本次验证覆盖生产部署、实际前端组件和历史探索记录；未重新运行完整金融分析任务，不作为行情或融资结论验证。

本地验收产物：`target/codex-live/report-ui-20261011/browser-verification.json`、`deployed-exploration.png`、`deployed-report-summary.png`。

部署前定向验证：前端 58 项、Harness 23 项通过，前端生产构建及 MCP compile 成功。
