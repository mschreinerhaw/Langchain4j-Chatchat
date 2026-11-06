# 第二阶段公开证据

主报告：[真实环境验收](../../cline-native-phase2-20261106.md)。目录只保存合成任务的受限字段、文件 hash、统计与浏览器截图；不含认证数据、部署配置、原始模型对话或完整 Journal。

| 证据 | 用途与边界 |
|---|---|
| `local-tests.json` | 最终定向测试摘要、原始日志 SHA；前端组有重叠，不合计唯一用例 |
| `schema-baseline.json` / `schema-candidate.json` | 同 SDK 合成目录精确前后 Schema 字节；估算 Token 与一次准备耗时不等于 Provider 收益 |
| `browser-running/` | 两个真实运行任务断网刷新、同任务与单调游标、零 execute 请求 |
| `browser-completed/` | 单图/多图逐图实际渲染、离线刷新后回执不变；截图中的 running 文件名来自同一节点标签，API 状态为 COMPLETED |
| `browser-flash/` | 原 flash 预算失败和 FAILED/PARTIAL 三图阶段结果，不改为业务成功 |
| `browser-historical/` | 历史非法 Plotly 拒绝报告的正式页面读回，逐图可见拒绝；不是本轮重新生成的正向成功样本 |
| `browser-harness-failed/` | 保留认证初始化注入 sandbox iframe 导致的验收脚本失败；修复顶层窗口限制后重新验证，未改 iframe 安全策略 |
| `browser-worker-and-cancel/` | 实际故障后 Java 归档收束完成与真实取消节点，独立深链接进入、终态文字、离线刷新与原图展示；不证明 SDK Restore |
| `browser-worker-harness-timeout/` / `browser-worker-harness-blank/` | 保留跨 workspace 只改 hash 未挂载新页面、about:blank 初始化认证的脚本失败，最终限定目标 origin 并独立载入后复核 |

服务器导出文件保留原子目录名称：`api-r4/`、`worker-r4/` 为发布与隔离门禁；`report-plus-compact/` 为独立正向交付；`report-r3/` 为原 flash 失败组；`perf-plus/` 为两组配对测量、原生 Skills 生命周期与独立质量复核；`recovery-plus*` 和 `cancel-plus/` 为实际故障/取消记录。未通过的证据也保留。

`quality-review-public.json` 是合成报告原文的有界导出，模型 COMPLETE/INPUT_REQUIRED 不作为独立判断；人工质量结论另列。Provider 计费与 reasoning tokens 未取得可核验记录时保持 UNKNOWN。公开证据的文件清单和 SHA 存于 `manifest.json`，不包含该清单自身的 hash。
