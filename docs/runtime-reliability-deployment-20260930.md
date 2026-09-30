# Runtime 可靠性修复部署联调

## 部署

- 代码版本：`6b00d40a`。
- 服务器：`192.168.195.221`；API 目录：`/opt/chatchat-1.0.0-SNAPSHOT`。
- 部署包 SHA-256：`1386f186d7bb2e86cd87499d7db3f2d3cc25f9b425c6da526afa54de8deba981`，服务器与本地一致。
- 备份：`/opt/chatchat-deploy-backup/reliability-20260930-0635/chatchat.jar`。
- 原包 SHA-256：`f7043435170238728665d26f158e54b7f6df1d500e032e60d863cd5e2c96855d`。
- 新 API PID：`1386869`。MCP 服务、数据库与应用配置未变更。
- 部署前活动任务分页查询返回 0；API 优雅停止后替换，健康接口 `UP`，Web 首页 HTTP 200。
- Maven 离线打包成功；安装包中 agents/chat 依赖与本次 reactor 产物哈希一致。构建未重复执行测试，部署前控制链路 27 项回归通过；完整架构门禁仍有 132 项原有失败，参见 [可靠性审计](runtime-reliability-audit.md)。

## 真实服务测试

| 场景 | Task ID | 验证 |
|---|---|---|
| 未绑定 MCP，直接解释净值比对 | `94d5829a-ee59-3f11-8b9e-f76319b6b9e0` | SUCCESS，约 41 秒，工具调用 0；临时 Agent 已删除 |
| 缺少必需模板执行工具 | `9641529f-3ecb-3c2b-9364-f5364d541e2f` | NO_PRESENTABLE_RESULT，执行前拒绝，工具调用 0 |
| 原资产问题 | `a5651ab5-3b53-39d3-9406-08481e5428fa` | PARTIAL_SUCCESS，约 644 秒，4 次工具调用；一次重规划，Runtime 已 COMPLETED |
| 原客户交易、资产、盈亏与偏好分析 | `4fb7b41e-a9d7-34c5-8c86-d3e9353100d3` | PARTIAL_SUCCESS，约 747 秒，3 段工具调用成功，处理 8 组数据；Runtime 已 COMPLETED |

MCP 协议冒烟：服务查询 SUCCESS；模板第一页 5 条、第二页 3 条，页面不重复；非法游标返回 FAILED / MCP_TOOL_EXECUTION_FAILED。没有增加临时授权。

## 资产问题结果与限制

Runtime：`att-1-edfd6ed5-00a9-4398-b933-601038fd48e9`。
真实调用顺序为资产查询 → 模板查询 → 重规划 → 资产查询 → 模板查询。
`refinementAdmission.reason=dependency_replan_required`，`interpretationPlanRewriteCount=1`，预算为 1。
因此本次实际覆盖了评审拒绝后的执行恢复分支，不只是正常路径。

分析流程经过 `REFINEMENT_PLAN → REFINEMENT_DATA → REFINEMENT_ANALYSIS → FINALIZE → END`，产生 `RUN_COMPLETED`，Runtime 的完成时间早于 Task 完成时间。未再出现 Task 已结束而 Runtime 无完成时间的孤悬状态。

业务结果仍是部分结果：`claimCoverageStatus=FAIL`、`answerClaimAuditPassed=false`。同时发现底层 Runtime 元数据 `publicStatus=SUCCESS`，外层 Task 经证据审计降级为 `PARTIAL_SUCCESS`。运行生命周期已闭合，但结果状态投影尚未完全统一，不能宣称完整架构验收通过。单次任务耗时约 10 分 44 秒，性能也不能视为已达标。

## 专业数据分析结果与限制

Runtime：`att-1-e6d590d3-5555-4d24-8351-29fc4fbc9551`。
服务查询、声明的模板发现子工具、模板执行三个调用均成功；进入 8 组业务数据分析，最终生成约 3230 字符的结果。
停止原因为 `no_verified_new_retrieval_path`，没有找到已验证的新补证路径，因而保留有限结论结束。
`claimCoverageStatus=FAIL`、`answerClaimAuditPassed=false`，与资产场景一样仍存在底层 publicStatus 与外层部分成功投影的差异。

两条分析任务都具有 `RUN_COMPLETED`，Runtime 完成先于 Task 完成，任务完成前入口持续等待模型与分析子任务。结束后活动 Task 数为 0，健康接口仍为 UP。
本次验证了部署可用、实际重规划、父子流程等待与生命周期闭合；没有验证通过全部业务结论，也没有消除原有 132 项自动化失败。历史任务的异常状态没有被重写。

运行记录保存在本地忽略目录 `target/codex-live/reliability-*`。记录包含任务时间线、计划、事件、最终结果和 Runtime 快照；不将业务结果、令牌或凭据提交到仓库。
