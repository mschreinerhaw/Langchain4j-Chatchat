# Native Coding Agent 业务验证阶段提交说明

日期：2026-11-08。基线：`d8f41ff2e`。专项分支：`feature/runtime-cline-cli-native-20261107`。

**结论：实现和阶段证据提交评审，业务验收未通过，不批准旧节点下线或生产切换。** 按用户“提交并推送代码并说明剩余工作”的要求停止追加业务测试。共享默认 Coding Agent、模型、预算及生产服务配置未修改；旧节点保留。

## 实现与职责边界

调用链为用户业务入口 → Runtime 编排/AnalysisCodingPort → CodingAnalysisService → CodingAgentSPI → ClineNativeCodingAdapter → Native business-server → 已有受限 Executor → 官方 v4.1.26 Standalone TaskService。

Runtime 继续负责身份、合同、数据授权、预算、Journal/Fencing、Managed Python、校验及归档。Native Core 自主选择 Skills、原生文件工具及授权 MCP，生成与修改 Python。适配层转换协议、交付授权文件并传回真实回执；不实现分析算法或 Agent Loop，不启动旧 SDK Worker。

| 源码 | 改动及复用能力 | 验证边界 |
|---|---|---|
| `ClineCodingConfiguration.java` / `ClineNativeCodingAdapter.java` | 显式 `backend=native`，校验目标 engine/version，复用原 Java 生命周期适配器；启动时绑定已有分析服务 | 默认仍为 sdk；拒绝静默回退；单元检查通过 |
| `ClineRuntimeModelBridge.java` | 原生本地工具授权映射、Runtime MCP 前缀映射，保留既有模型与预算治理 | 最终 `submit_and_exit` 白名单修复已编译，尚未做真实部署回归 |
| `ClinePythonCallbackController.java` | 已授权输入文件分块交付，校验实际长度与 SHA256 | 仅 native 后端可用，不增加数据授权 |
| `CodingPythonExecutionBridge.java` | 使用已有 CodingTaskRevisions 处理进度更新导致的 reserve/startIntent CAS 竞争 | 外部 start 不放入重试；真实 Python 派发已验证，独立 Journal 回归通过 |
| `business-server.mjs` | Runtime HTTP 合同、模型/MCP broker、原生审批和事件投影 | Gateway 无 Docker Socket；健康检查不代表业务成功 |
| `business/model-protocol.mjs` / `native-context.mjs` | 保留工具调用与原始结果；绑定可信 SDK session、toolCallId、epoch、审批参数 Hash | JSON-RPC id 不充当 operationId；截断参数交由官方 Core 处理 |
| `business/delivery.mjs` | 文件 Hash 校验、原生 Skills 交付；旧中文 Skill 名映射到安全目录名，正文保持 | 已有真实 Skills 加载事件；不是仅以复制目录计为成功 |
| `business/runtime-tools.mjs` / `runtime-callback.mjs` | 复用现有 Runtime 数据、Python、归档、报告及签名回调；确定性 operationId、重复调用合并 | 最终并发去重及归档报告回传修复仍需真实回归 |
| `business/code-lineage.mjs` | 描述原生源码实际字节差异，复用原 CodeHistory 协议 | 不生成、执行或修复代码 |
| `tool-outcome.mjs` / `worker-executor.mjs` | MCP isError 正确记失败；服务端固定 MCP 名支持 runtime | 不新增任意 Docker API/参数；官方 Core 与 descriptor Hash 未变化 |

完整原节点职责矩阵见 [coding-agent-node-replacement-matrix.md](coding-agent-node-replacement-matrix.md)。

## 实际业务结果

| 场景 | 实际结果 | 未通过原因与剩余动作 |
|---|---|---|
| A：客户 070200046604 | 两次业务入口调用均失败 | 初次隔离配置禁用了 analysis；修正后第二次容量不足。需串行重跑并核对交易、资产、盈亏与偏好报告 |
| B：华是科技行情 | 两次业务入口调用均失败 | 初次 analysis 不可用；第二次旧中文 Skill 名不满足原生目录校验。安全映射已修复，尚未重跑；仍需证明空数据识别、授权搜索与引用真实性 |
| C：CSV + Parquet | 原生读、搜、编辑/Patch、Skills、真实 Managed Python 与自动修改后重跑已有证据；任务仍失败 | C-r5 两次 Python 执行，第二次回执 resultAvailable=true；最终 Runtime 为 BUDGET_EXCEEDED，不能把中间产物当作任务成功。需完成原生结束工具及交付回归 |
| D：报告生成 | 真实 exploration 业务链已派发，未完成最终对账 | 导出时业务 receipt 为 RUNNING，Gateway 为 UNKNOWN；有取消请求，尚无完整最终停止/交付证明。必须先对账，不能直接重派同一任务 |
| E：异常与恢复 | 有真实预算耗尽、缺失产物、工具错误和派发前失败记录 | 真实取消与远端停止、超时、重复请求、崩溃恢复和迟到回执尚未完整验收；单元检查不能代替远端证明 |

C-r5 taskId：`analysis-coding-bdab2aed0e0b20795f4435abb31c61e46cdaefa95cddd3205ba8af5a654f20b9`。两次 operationId：

- `cline-1ab290b8f4aeb31f40a9af657da21d23021cb4e8031f5c8e491c803a1b68eafa`：进程成功，业务必需产物缺失。
- `cline-5288c075508584b51855eac620f0cb253f1f3c83da4d9f3d26b93df2d0a1feb7`：进程成功、executorStopped=true、resultAvailable=true，之后存在真实 artifact_read；任务仍因预算耗尽失败。

未完成 Markdown、Excel、PNG 及最终报告的全部独立可读性、内容、Hash 和用户交付检查。没有五类场景的总体通过结论。

## 失败定位与修复状态

1. 隔离 API 的 `SPRING_APPLICATION_JSON` 覆盖了 analysis 环境变量，导致 `PYTHON_ANALYSIS_UNAVAILABLE`。只修正专项 API 的配置，新增启动绑定检查；非共享配置变更。
2. 初期 MCP GET 使用错误状态码；原生 MCP metadata/session 绑定字段不匹配。定位 `business-server.mjs::broker` 和 `business/native-context.mjs`，已修正，后续真实工具调用可到达 Runtime。
3. 进度回调缺少 sequence 且错误传播阻断 Python。定位 `business-server.mjs::emit`，已改为顺序投影并记录投递失败。
4. C-r3 在 `CodingPythonExecutionBridge.start` 的 reserve(expectedRevision) 与进度写入竞争，抛 CodingContractException；Managed Journal 中操作数为 0。改用已有 CodingTaskRevisions；后续 C-r4/C-r5 真正执行 Python。原 UNKNOWN 及失败证据保留。
5. 官方 `sdk/packages/core/src/extensions/tools/helpers.ts:26/30` 的 editor 6000 字符限制，以及 `executors/editor.ts:207` 的插入行校验在 C-r4 实际触发。没有修改原生工具；后续选择官方已有 apply_patch profile。
6. 原生结束工具 `sdk/packages/core/src/extensions/tools/definitions.ts::createSubmitAndExitTool`（`submit_and_exit`，lifecycle.completesRun）未被 Runtime 模型白名单放行。最终提交已补齐白名单和真实归档报告回传，但未部署并重跑真实任务。Executor 的固定工具清单也必须在独立环境中同步允许该工具后再验证。

## 检查与证据

- Node 全套检查：27/27，通过；其中业务协议单元检查 10 项。见 `../testing/evidence/runtime-cline-native-business-20261108/node-tests.tap`。
- 最终 5 个 Java 改动源码在隔离 review-classes 中以真实 API 依赖编译成功；Native Adapter 7 项、真实 Journal + 单元 MCP fixture 的进度竞争/重复派发/Fencing 3 项通过。不是完整 Maven 构建，不是业务验收。见同目录 `java-checks.json`。
- 脱敏快照 `review-snapshot.json` 包含 7 个 Native 运行状态、工具/Skills 摘要、实际 Python 回执、Runtime 返回状态、54 个私有证据文件的 SHA256 索引和镜像差异。
- 原始任务、完整模型记录、工具回执、源文件及私有构建日志保留于服务器 `/opt/cline-native-business-20261108` 的 `evidence/`、`state/`、`workers/`。仓库只提交索引与脱敏摘要，不包含金融原始数据、登录凭据或模型密钥。证据文件继续写入时 Hash 会变化，索引代表导出时点。
- 派生 Worker 仅覆盖宿主 `tool-outcome.mjs`；官方 cline-core.js SHA256 `39e76ca525a3736d645ebb972e60a34a45bac413837e709f0bf3d2a7a6aad361`、descriptor SHA256 `d28dace3b4daff727770c3625917b22a0c54a8f855c4222530de1923b493086f` 均与基线一致。

## 恢复、回滚与下一步

Native Gateway 重启后把未决运行保留为 UNKNOWN，不自动重派；当前恢复记录不足以恢复所有控制凭据，恢复后的 UNKNOWN 无法通过当前取消入口完成控制。因此不能宣称已具备完整恢复能力。workspace v2/SQL profile、通用代码项目交付与旧 SDK checkpoint 迁移也未实现。

按顺序完成：先对账 D 的 Runtime Journal、Native Worker、MCP/Python 及归档状态；在独立实例部署最终提交并同步 Executor 固定工具清单；保持原预算重跑 A/B/C/D；独立核验全部文件及报告；再完成 E 的真实故障与取消测试。失败不得更改为成功或以 Mock 回执替代。

回滚只针对专项 API：停止接收新任务，查询并停止/对账现有远端操作；确认没有未决执行后，将该实例 `chatchat.coding.cline.backend` 改回 `sdk`，node-url/secret 恢复旧节点对应值并重启实例。保留旧节点和原 Journal，不把 Native taskId 转交 SDK 重跑；对 UNKNOWN 先人工对账。共享默认从未切换，无需生产回滚。

Rootless 资源限制、Python rootful 路径、控制面高权限和执行节点恢复架构的遗留风险继续单独保留，本轮未扩展整改。停在业务评审点，不进入生产切换或后续架构阶段。
