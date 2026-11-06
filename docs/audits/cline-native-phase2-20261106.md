# Native Skills 第二阶段：基线与剩余关闭矩阵

日期：2026-11-06，Asia/Shanghai。起点 `025c03961f9f4101ccddab2aad3238996ce4575a`，分支 `refactor/cline-native-skills-20261106`。用户明确授权在所提供服务器部署验证；本文和公开证据不保存密码、Token 或服务密钥。

## 基线复核

上一阶段的六个提交均完整存在，工作区起始干净；本轮检查时本地与 origin 目标分支一致，与任务书“六个未推送提交”的快照不同。未 reset、强推或合并 main。

独立复测：Java 三类 93 项；Node 241 项；探索前端 130 项；报告前端 62 项，全部通过。前端组有重叠，不相加计算唯一用例。Linux 候选 Node 241 项、真实 Docker Sandbox 10 项通过，SDK 仍为 0.0.93。首次测试包缺 integration 辅助文件，门禁拒绝；保留 attempt1 日志，补齐后重跑，失败候选未切流。

部署前服务器父任务 0、Coding 活动会话 0、Python Sandbox 容器 0。原 API JAR 为 `cfe765b0b7af4a7e46d50d24ce5729734d26281bb8a3e5fa11b8763581463818`，Worker 为 R2 镜像 `sha256:84a9df62e9ec1acffbaf54589294334b6002d69292302af8a03fca7b302232cb`。既有无关 preview proxy 显示 unhealthy，未把它归因于本次部署或修改其配置。

## 剩余问题与最小范围

| 项目 | 源码与实际边界 | 本轮处理与验收证据 |
|---|---|---|
| P0 图表交付 | 现有 `/workspace/output/results` → manifest → `CodingEvidenceCollector` → `VisualReportArtifacts` → owner artifact API。前阶段已补安全契约和准确反馈，不重复造 Loader/存储 | 先部署已有修复，通过正式探索单图/多图任务；下载逐文件核对 sha/size/task，再做真实浏览器渲染和刷新。失败保留原始请求，不重放未知请求 |
| P0 断线恢复 | `ExplorationWorkspaceController.status` 只同步 Agent 任务；直接文件分析没有只读快照；`DataExplorationView.pollStatus` 同样只选 Agent。真实 HTTP 中断时 workspace 未必收到最终 receipt | 复用现有 scope、持久合约、DurableCodingTaskService、已归档报告，补直接文件分析只读状态投影。读取不调用模型、submit/resume/Python，不重置预算；轮询接入现有 status 接口 |
| P0 Worker 恢复 | 既有 SDK session/checkpoint、租约/fencing 和未决操作治理必须保留 | 先做本地与 Docker 恢复回归，再仅对本轮合成验收任务做 Worker 故障注入，核对 Python 操作 ID/次数及剩余预算，不触碰用户在途任务 |
| P1 配对测量 | 前阶段只有 Schema 字节计量，历史前后数据不能代替本轮严格配对 | 固定六类合成输入/hash、模型、预算、并发与合约，对不同工件分组；逐任务记录调用、已知用量、UNKNOWN、时钟、质量，失败和缺测不剔除，不以首代码下降宣布总成功 |

## 工件与发布准备

新增 `prepare-native-remediation-api.py` 仅用于工件组装：白名单 API 类族、公共报告契约 class，以及完整构建的前端；其他 JAR 条目和 common 其他类逐字验证一致，Spring Boot 嵌套 JAR 保持 STORED。复用既有部署与隔离测试脚本，API 与 Worker 备份放在分离目录，避免覆盖回滚配置。无数据库迁移、模型/预算配置修改、SDK 版本变更。

最终状态和证据随本轮实际结果补录到 `docs/testing/`；本文件不预先宣布图文、断线恢复或性能验收通过。

## 实测根因与修改矩阵

| 优先级 | 实测根因、代码位置 | 最小修改与提交 | 已有验证 |
|---|---|---|---|
| P0 | `CodingAnalysisService.executePrimaryAnalysis` 在业务请求校验前追加平台图文契约，合成请求达到 16435/16491 字符，超过业务输入 16000 限制；未进入模型 | `6ac6544f6`：先校验外部分析指令，再在已有统一执行入口追加同一平台契约；不放宽外部上限、不改变最终合同指纹 | Java 94 项；正式入口后续模型任务实际受理 |
| P0 | 直接文件探索执行依赖长 HTTP 响应；执行意图与权威任务没有接入既有 status/stop 路径 | `f417dafca`、`060e249f1`：派发前持久化意图，按原 owner/run/requestId 派生原 taskId；只读任务快照及归档数据；复用 stop；前端轮询直接文件任务 | Java 127 项，UI 132 项；真实运行中断网/刷新同 taskId、游标不回退且无 execute 请求 |
| P0 | qwen3.8-flash 多图任务已生成并归档三张图，后续模型补丁失败，最终 FAILED 响应未暴露已经验证的阶段交付 | `3d53566bc`：复用原 `deliverable:<operationId>` 索引，核对 stop proof、原 task/op/result 引用及归档安全验证；失败任务保持 FAILED，阶段报告投影为 PARTIAL；旧终态一次性补齐后停止轮询 | Java 130 项，UI 133 项、报告 UI 62 项；真实失败任务三张图下载/归属核验及浏览器渲染通过；完整业务任务仍 FAIL |
| P1 | native Skills purpose 元数据重复携带 qualified id、name 和 false flag，导致每次工具 Schema 重复字节；没有证据表明已加载 Skill 正文是当前瓶颈 | `b87fbd459`：只移除现有元数据投影的重复 name、false flag，保留全部 id、完整 description、disabled:true；SDK discovery/getter/执行器未改变 | Node 241 项；15/138/515 项 Schema 字节分别减少 774/6568/24664；真实 Provider 性能按独立配对组测量 |

上述修改没有引入第二套 Loader、Agent Loop、Checkpoint 或结果存储，也没有更换 SDK、全局模型或预算。源码修改按 P0 与 P1 分别提交；部署脚本及证据另行提交。

### Worker 真实故障后尚未关闭的边界

`recovery-plus-r3` 在 Python 已停止并验证成功后、`model-3` 仍未决时注入 SIGKILL。Java 最终收到该模型回执；最近 SDK Checkpoint 仍是 modelOrdinal=2、checkpointOrdinal=4。`DurableCodingTaskService.sdkCheckpoint` 要求 operationCount 精确覆盖全部已开始操作，因而拒绝旧快照；`sdkCheckpointAfterLocalModelStops` 只允许没有返回响应的本地终止模型，不接受这个已经有真实回执的额外操作。`ClineCodingAdapter.resume` 最终走 `completeVerifiedOperation`，记录 “Verified Coding execution recovered after worker session loss”，没有重新创建 SDK 会话。

这次实际任务 COMPLETED、原成功 Python 操作与 intent 集合不变、SDK sessionId 和原预算/截止时间不变、未知用量保留。但 monitor recoveryMode 仍 NEW_SDK，checkpointOrdinal 未前进，不能判定原生 SDK Checkpoint 续跑通过。完整 Worker 恢复项记 FAIL；“从已验证归档收束”和“不重跑成功 Python”有独立通过证据。Node 观测记录在进程丢失后可能仍显示 RUNNING，前端必须以 Java 权威 status 为准。

不能直接放宽 operationCount 校验，也不能以重新派发原模型或 Python 补齐 SDK 消息。本轮保留该失败及根因，未绕过检查或新建恢复机制。后续修复必须在现有回执/SDK Restore 路径内证明准确的模型请求指纹、消息/工具边界与回执对应关系，再针对这个真实窗口复测。

## 第二模型授权与统计边界

原 qwen3.8-flash 存在真实 300 秒模型失败、多轮修复及截止时间耗尽。用户明确允许使用服务器另一个已配置模型并单独统计，因此选择既有已发布的 qwen3.7-plus，不修改共享配置。保留原 flash 单图 BUDGET_EXCEEDED、多图 FAILED，不并入 plus 成功率。

qwen3.7-plus 性能基线 12 次执行均完成，但图文一次 PARTIAL、无可交付图；另一次同输入交付两张图。输入未声明字段含义时，模型处理边界存在差异。独立 P0 正向交付任务明确声明合成字段含义，不提供观测数值；这不替换或改变配对基准的问题。
