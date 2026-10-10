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

## 2026-10-10 后续部署：模型意图、CALL_TOOL 恢复与链路探索

部署至 `192.168.195.221`，保留原安装目录、配置、驱动与插件。每次替换前校验上传 JAR 和旧包备份的 SHA-256；安装脚本重启服务。MCP 在停止脚本的 30 秒等待窗口后触发强停，重新启动后实际调用验证正常。部署在测试任务已结束、RUNNING 任务查询为空时进行，没有取消或改写历史任务。

最终安装产物：

| 服务 | SHA-256 | 最后一次替换前备份 |
| --- | --- | --- |
| API | `25c5c7c6a03b59166d814a904e962551164415243ef30aebb359140fef69b951` | `/opt/chatchat-deploy-backup/runtime-exploration-chatchat-20261010-145242/chatchat.jar` |
| MCP | `3185d3faae6b47066f7b2ca18e7e639cf3327ed6102a668eb13b0b6fb56e7074` | `/opt/chatchat-deploy-backup/runtime-exploration-chatchat-mcp-server-20261010-145153/chatchat-mcp-server.jar` |

开始整改前的 API 包备份保存在 `/opt/chatchat-deploy-backup/runtime-exploration-20261010-142032/chatchat.jar`。登录会话在 API 重启后失效，联机验证重新认证，未修改账号权限。

真实场景均使用数据库运维 Agent 和已授权只读模板，目标为 `LiveData测试库_223`（DEV）；分析任务没有调用主机命令工具，也没有修改目标数据库配置或数据。

| 场景 | Task ID | 执行事实 |
| --- | --- | --- |
| InnoDB 自主分析与显式发布 | `7c6cbdac-7f6c-3232-bbe7-9bc0002bdc82` | SUCCESS；8 轮 Harness，CONTINUE 续读后 PUBLISH；格式错误返回协议回执，未静默改为旧发布协议；报告哈希与证据快照绑定 |
| 草稿完成，不发布 | `65be6eb6-005b-3d06-b295-d04fa639298b` | CONTINUE → COMPLETE；`publicationState=NOT_REQUESTED`；外层兼容状态 NO_PRESENTABLE_RESULT，提示“模型已完成分析，未请求发布；草稿和执行记录已保留” |
| 稳定请求恢复 | `9790fec0-aadd-3396-9719-18e1e32695bf` | 同一 requestId、工具、参数连续请求两轮，回执一致；SHARED_DATABASE，调用预算 1，RESTORED，最终 PUBLISH/DELIVERED |
| 最终 API/MCP 产物复测 | `10603377-1bcd-3027-82b4-6608c618674e` | SUCCESS；CONTINUE → CONTINUE → PUBLISH；两个回执分别标记 NEW_EXECUTION、COMMITTED_RESULT，指纹相同；调用预算 1，最终报告 SHA-256 与发布绑定一致 |

最终复测的 Runtime ID 为 `att-1-37b48036-1787-4e6b-933a-5ce4362e068a`。发布请求的报告 SHA-256 为 `cfb09838b2b36a7a23c998439515047321bd5a8c0bafabb363b75c1cd8546ef3`，证据快照为 `698710f67a6ba395c5aa3b67cf2e4d57f24d0d106e47d5c8b8d3e9eb10dbf042`。Runtime 记录这些绑定，不认证报告业务结论。

完成任务后的 API 重启复核：最初 InnoDB 任务的 50 条执行观察、模型决定、报告草稿和发布绑定全部恢复；草稿 SHA-256 匹配。该验证覆盖已完成运行的持久化读取，不等于已实现运行中长任务的自动唤醒。

Chrome 实际访问 `http://192.168.195.221:8080/#/tasks`。默认链路视图按真实观察增加节点，最终版本的一段动态采样从 17 增至 24，完成后为 30 个节点；可展开运行事件、切换计划快照、定位已提交回执恢复节点并查看 COMMITTED_RESULT、查看 PUBLISH 决定。浏览器没有 pageerror。观察、截图、报告与部署清单保存在忽略目录 `target/codex-live/`，不提交令牌、凭据和业务原始数据。

最终场景还实际返回了模型显式 hypotheses/findings，页面已展示对应“模型假设”和“模型发现”节点，与第二轮探索节点关联。它们来自模型声明，未由 Runtime 从指标或报告文字生成。展示截图为 `target/codex-live/graph-recovery-final-restored.png`。

本轮 19 个相关后端/检索测试类共 181 项通过，前端 27 个测试文件共 262 项通过；前端构建、API/MCP 打包和 `git diff --check` 通过。另行完整重跑 ToolRuntimeServiceTest：70 项中 13 个失败、3 个错误；与此前 69 项基线逐项比较，新增失败为 0，原有 16 项问题未修复。该套件没有被宣称通过，也没有为了通过而改变工具治理逻辑。

仍需明确的后续边界：WAIT 自动恢复、运行时动态 Skill 发现和持续探索调度尚未实现；外部工具成功但回执在进程崩溃前彻底丢失时返回结果未知，不自动重跑；有效执行事实仍保留，只有无引用分块会被回收，完整退休/墓碑策略需要治理 Contract。升级后的大回执索引需要恢复 Worker 同步升级。机制说明见 [Model Native Analysis Harness](runtime-model-native-harness.md#2026-10-10v2-意图工具恢复与链路探索)。

## 2026-10-10：内容语义归属与自动会话交付整改

纠正前一版将“未 PUBLISH”统一称为草稿的做法。v2 新增模型显式 `output_type: intermediate|draft|final`，正文使用 `content` 或兼容的 `reportMarkdown`。内容存入中性字段 `modelAnalysisOutput`；未声明类型记录 UNDECLARED。Final 自动交付当前用户，无需独立 PUBLISH；旧 PUBLISH 是兼容入口，旧 v1 保持原行为。模型完成决定、内容声明、执行状态和用户采纳分别记录。

初次联机验证：任务 `303e776b-46ad-358b-9e24-fd69c33ea704` 自主读取两轮证据后选择 COMPLETE + FINAL，SUCCESS / DELIVERED，未使用 PUBLISH；模型声明模板发现缺口仍交付。任务 `c541642d-6530-318d-929e-1a97f5be2925` 选择 COMPLETE + DRAFT，运行事实为 COMPLETED，保留 2869 字正文及模型声明，未升级为 Final。旧 Task 公共码 NO_PRESENTABLE_RESULT 表示没有会话最终回答，不是报告质量评价。该任务声明和正文在后续 API 重启后读取一致，正文哈希为 `6f502d62cefbbc8cfc4860c9d8f3f875560100918020bec36d8d9f43e9fc60ba`；这不是运行中 WAIT 恢复测试。

联机比对还发现旧文本清洗会删除模型引用的工具名，以及图表绑定失败会丢弃模型 payload。最终整改覆盖答案收尾、统一交互入口、Task 展示、会话记忆及读取、报告资源存取。v2 不再套用旧引用清洗；绑定失败的图表仍禁止作为可执行图表交付，其 payload 保留为普通 text 代码围栏。允许的图表绑定与围栏格式转换属于执行 Contract，不是结论评审；原始模型正文和版本保留。旧 v1 清洗与图表行为兼容。

最终部署 API 包 SHA-256：`6aa878eba406b6c8c16f91b4c78ec14afff732fdaafdbf03441106eb278f59f2`，进程 PID `1680489`。回退包目录 `/opt/chatchat-deploy-backup/runtime-exploration-chatchat-20261010-163535`，前一包 SHA-256 为 `3320c75dadeb42b177f1df9b3fa1edf775825ed2f929870f84e591f73f9fdca8`。本次通过字节增量传输重建原 Maven JAR，本地及远端完整哈希均核对通过；未执行 ARTEX 脚本、安装新依赖或更改业务数据库。MCP 沿用此前已部署版本。

最终版本真实只读任务 `c513b2a6-44d8-3aa6-9c29-aad264e0d978`，运行 `att-1-9ff27d4b-9207-4bb7-ae82-2214c77e23f2`：模型自主两轮 READ_TEXT 后输出 COMPLETE + FINAL，SUCCESS / DELIVERED；模型原文、运行答案和 Task 展示正文完全相同，均为 969 字。工具名 `mcp_chatchat_mcp_server_database_capability_query` 完整保留。正文 SHA-256 为 `d0d66b16092f38665460f538d094aae242740f677a5c97aadc7d6a475dda7cd7`，证据快照为 `683752f8efe082636faa60da16d4a3b69f7fe6f2e2f9a21b765e20dcdbf66e69`。模型未请求 PUBLISH 或外部提交。

Chrome 实测 Final 与 Draft 内容声明节点，最终场景 23 个节点，无 pageerror。截图与原始验证记录在忽略目录 `target/codex-live/graph-semantic-final-exact.png`、`semantic-final-exact-verification.json`、`semantic-draft-verification.json`，不提交令牌或业务原始数据。此验收验证决策来源、版本一致性与实际传输，不为数据库分析结论评分。

最终相关后端 18 个测试类 171 项通过，Maven package 返回 0；前端 27 个文件 263 项通过、构建通过。新增覆盖 Final 无需 PUBLISH、声明 Draft 不被升级、预算停止不定义内容类型、证据缺口不阻断 Final、权限及版本绑定仍阻断、正文引用在任务/会话/资源间保留、非法图表不执行且保留模型内容。扩展检查中的旧 `reviewerTimeoutUsesConfiguredModelTimeout` 仍约 5 秒而断言要求小于 3 秒，未修复或放宽；本次 v2 交付不调用该 reviewer。此前 ToolRuntimeServiceTest 的 16 项既有失败仍未被宣称解决。

WAIT 自动恢复、动态 Skill 发现、长任务持续探索及进程重启后自动继续仍未实现。本轮没有新增 Planner、Agent、探索图存储或业务结论审核器，也没有将内容类型与业务正确性绑定。
