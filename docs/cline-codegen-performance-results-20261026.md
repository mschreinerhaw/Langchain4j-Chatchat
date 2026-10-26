# Runtime OS / Cline 代码生成性能整改与验证

分支：`perf/cline-codegen-20261026`；目标分支：`feature/mcp-template-centric-permissions`。先提交 [代码级分析](cline-codegen-performance-analysis-20261026.md)（`7bdc2a684`），再提交实现（`8ee8d5275`）及兼容性修正（`6021029c7`）。没有替换生产服务、主分支或模型配置。

## 真实模型对照

使用用户指定环境登记的 `deepseek-v4-pro` 配置，Java 在独立进程中通过原有 ModelResourceRegistry / ConfigurableChatModelFactory / ClineRuntimeModelBridge 调用模型；Node 为独立子进程，状态和 Evidence 使用独立 H2，所有工作区和授权数据均为合成测试输入。生产数据库仅用于读取该模型配置，没有创建业务任务或修改发布记录。

下表为最终实现的同任务顺序对照，每模式各 **1 个样本**，均完成授权、SDK 工具、确定性解析和 Evidence 登记。没有把早期候选样本或历史 qwen 样本混入结果。

| 指标 | SQL 完整路径 | SQL 快速路径 | Python 完整路径 | Python 快速路径 |
| --- | ---: | ---: | ---: | ---: |
| 任务墙钟 ms | 18,018 | 3,970 | 6,380 | 5,217 |
| 模型请求次数 | 3 | 1 | 3 | 1 |
| 工作区工具次数（含预载） | 2 | 2 | 2 | 2 |
| 输入 Token | 3,118 | 1,088 | 3,100 | 1,072 |
| 输出 Token（不另加推理 Token） | 407 | 234 | 370 | 298 |
| 解析 / Evidence | 通过 | 通过 | 通过 | 通过 |
| 独立容器行为检查 | 通过 | 通过 | 通过 | 通过 |

这个样本中 SQL 墙钟下降 78.0%，Python 下降 18.2%。模型负载、缓存、网络和顺序差异都会影响耗时；这些数值不是 p50/p95、长期成功率或复杂任务收益保证。费用没有配置可核验单价，保持 null。供应商排队与首 Token 未报告时保留未知，不把 Node 缓冲回调首事件伪称供应商首 Token。

逐次模型耗时、Token、请求 Hash/大小、工具耗时及源码 Hash 在 [真实对照数据](cline-codegen-performance-20261026-evidence/real-comparison.json)；[行为检查](cline-codegen-performance-20261026-evidence/sandbox-verification.json)使用无网络、非 root、只读根文件系统、资源受限的独立容器，只挂载生成的候选文件及合成断言。SQL 检查 SUM/AVG、NULL 和空表；Python 检查空列表、普通数值及正负数。它是工作区生成后的独立验收，没有为工作区契约增加执行权限。

## 各项修改、依据和结果

| 修改 | 依据与实现 | 验证与性能结果 |
| --- | --- | --- |
| 有界上下文预载 | Java 显式开关及完整快照 UTF-8 大小选择；复用已有 workspace_read 授权、预算和 Evidence 操作 | 同一工作区仍读取一次，省去模型决定固定读取的请求。真实对照与 SDK 夹具均由 3 请求降为 1（与完成工具组合测量，不拆造单项毫秒收益） |
| SDK 完成工具 | workspace_propose 使用 SDK `lifecycle.completesRun`，要求 valid、resultAvailable 及全部 Evidence 引用；Java 再最终验收 | 省去成功提案后的可选模型收尾；缺失证明不能完成，解析失败继续原会话 |
| 原生增量编辑 | 已有文件要求当前 SHA-256 的 repair；直接复用 SDK 编辑器，保留其余文件并整体解析 | 多轮修复、旧 Hash 拒绝、其他文件保留、检查点重建通过；Python 分析原有回归断言与版本保护继续保留 |
| 请求与阶段观测 | 记录模型/回调边界、大小、哈希、工具和验证耗时；扩展原事件白名单与只读监控 | 成功、异常、取消均有有界记录；监控区分供应商耗时和回调耗时，统计脚本接入；不记录 Prompt/数据正文 |
| 输出配置 | 快速路径独立可配置上限，与原模型、工作区和总预算取最小值；默认仍 8192 | 测试验证不能扩大预算；真实比较保持相同模型和上限，收益不来自加超时或换供应商 |
| 确定性诊断 | Python AST / SQLGlot 返回错误类型、文件和可获得的行列 | 原语法及业务验收断言保留；既有 SQL 门禁夹具只有排序复制，触发有效防拷贝准入，现改为实际 SQL 窗口计数并新增独立结果断言，不放宽金融值、类型来源或结果契约 |

上下文大小是资源策略，不声称识别任意程序的语义复杂度。复杂数据分析仍走原 v3 Cline 循环。超出预载上限时在同一 Agent、权限和 Deadline 内使用工具读取；授权或未知执行结果失败不重新创建任务、不盲目重试。

## 验证范围

- Node：90 项通过，包含真实安装的 SDK、原生编辑和压缩、回归保护、取消、超时、恢复、会话/目录隔离、监控脱敏和兼容流程。`npm test` 同时完成 TypeScript 后端/页面编译。
- Java：17 模块 reactor 编译通过；[48 类定向回归](cline-codegen-performance-20261026-evidence/java-regression.json)共发现 418 项，执行通过 409 项、跳过 9 项、失败 0。跳过的 Docker 和真实供应商入口另行运行，不合并成虚假的全仓通过计数。
- 真实模型：Java → 签名 HTTP → 独立 Node/Cline → Java 授权工具 → AST/SQLGlot → Evidence，SQL/Python 两模式共 4 项通过。
- 真实 Docker：另建独立 Java/Node/H2 测试进程，执行现有 CodingEvidenceDockerIntegrationTest 和 CodingAnalysisDockerGateTest；最终明细见 [Docker 门禁](cline-codegen-performance-20261026-evidence/docker-gate.json)。模型和外部数据源是确定性夹具，Sandbox/回执/验收是真实组件。
- [SDK 对照夹具](cline-codegen-performance-20261026-evidence/sdk-fixture-comparison.json)：正常任务 3→1 次模型调用；注入两轮解析失败的任务 5→3 次，保持一次上下文读取，最终源程序一致。夹具墙钟不是供应商性能。

真实 Docker 门禁覆盖正常执行、空结果、进程异常、产物缺失、独立业务验收拒绝、同会话修复、取消、Deadline、并发隔离以及 Java/Node 恢复；不能将这些确定性模型场景描述为真实供应商的复杂分析验收。

## SDK 能力复用与职责

| 能力 | 当前归属 |
| --- | --- |
| Agent 循环、失败反馈、完成工具、Hooks/事件 | `@cline/agents` 0.0.93；没有新增循环或第二套调度 |
| 精确源码编辑 | `@cline/sdk` 的 createEditorExecutor，经既有 editProgram 最小适配 |
| 上下文压缩与恢复 | 原 SDK prepareTurn、restore/continue；工作区补丁状态从规范工具调用/回执对重建，不另存一套源码会话或检查点 |
| Deadline、权限、授权数据、模型配置、预算准入和最终成功 | 原 Java Runtime OS；SPI 与任务协议版本保持不变 |
| Python 隔离执行与结构化诊断 | 原 Sandbox 与执行反馈契约，未替换 |
| 代码版本、完整源码、执行和验收证据 | 原 Java Evidence；监控仅是只读观测 |

没有新增依赖，没有删除稳定业务能力。原循环已经由 SDK 负责，本次不存在可以据实删除的第二套 Agent 框架。保留关闭开关的原工作区流程作为对照和回退；不以“去冗余”为由删除它。新增的工具状态只承担源码编辑适配，不包含规划、模型选择或重试调度。

## 文件级修改

| 文件 | 修改 |
| --- | --- |
| `chatchat-agents/.../DurableCodingTaskService.java` | 在现有事件准入白名单增加性能与策略观测类型 |
| `chatchat-api/.../CodingWorkspaceService.java` | 冻结策略、预载资格、验证耗时；原授权和 Evidence 登记保留 |
| `chatchat-api/.../ClineCodingAdapter.java` | 通过已有能力协商接口确认快速路径支持 |
| `chatchat-api/.../ClineRuntimeModelBridge.java` | 配置化单回复上限，不扩大总预算 |
| `chatchat-api/.../ClineModelCallbackController.java` | 有界性能元数据准入，不保存敏感正文 |
| `chatchat-api/src/main/resources/sql-toolchain/validate.py` | 结构化 AST/SQL 诊断 |
| `coding-agent-node/src/runner.js` | 原 Agent 中预载、完成工具、失败继续、阶段观测、准备阶段取消传播 |
| `coding-agent-node/src/sdk-adapter.js` | 原生编辑器适配与规范检查点重建 |
| `coding-agent-node/src/server.js` | 在现有能力接口中声明新增可选工作区策略 |
| `coding-agent-node/src/monitor.ts`、`monitor-types.ts`、`web/main.ts` | 区分并展示回调/模型计时及传输大小 |
| `coding-agent-node/scripts/summarize-metrics.mjs` | 汇总回调和确定性验证指标 |
| `coding-agent-node/scripts/benchmark-workspace-codegen.mjs` | 可复现 SDK 对照夹具 |
| `scripts/analyze-codegen-baseline.py`、`verify-codegen-candidates.py` | 历史样本复算、独立容器产物验证 |
| `config/coding-workspace-performance.example.yml` | 默认关闭的部署开关和有界参数 |
| `CodingWorkspaceNodeIntegrationTest`、`CodingWorkspaceServiceTest`、`ClineRuntimeModelBridgeTest`、`SqlGlotToolchainTest` | 跨进程、授权、预算冻结、诊断及真实供应商可选入口 |
| `CodingAnalysisDockerGateTest` | SQL 夹具生成实际累计观测数，新增结果断言，保留全部金融和防拷贝验收断言 |
| `coding-agent-node/test/workspace-fast-path.test.js`、`monitor.test.js`、`server.test.js` | 编辑/恢复/超时/监控/能力协商回归 |

## 复现与回退

在仓库根目录执行 `python scripts/analyze-codegen-baseline.py` 复算 67.767 秒历史样本；`node coding-agent-node/scripts/benchmark-workspace-codegen.mjs` 复现 SDK 对照。不要把两者当作同一供应商基准。

```bash
npm --prefix coding-agent-node test
mvn -pl chatchat-api -am test \
  '-Dtest=*Coding*Test,*Cline*Test,SqlGlotToolchainTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfrontend.skip=true -Dfrontend.install.skip=true \
  -Dcoding.nodeE2e=true -Dcoding.sqlglotPython=/absolute/path/to/sqlglot-python
```

真实模型入口显式要求 `coding.realModelConfig` 的受控私有配置快照和 `coding.allowedModel`。不提供这两个参数时保持跳过，防止常规测试意外调用付费模型。测试工作目录使用独立 H2；镜像和 Docker 数据必须属于隔离测试工作区。

```bash
mvn -pl chatchat-api -am test \
  '-Dtest=CodingWorkspaceNodeIntegrationTest#authorizedRealModelComparison' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfrontend.skip=true -Dfrontend.install.skip=true \
  -Dcoding.nodeE2e=true -Dcoding.sqlglotPython=/absolute/path/to/sqlglot-python \
  -Dcoding.allowedModel=AUTHORIZED_CONFIG_NAME -Dcoding.realModelConfig=/secure/model.json
```

新任务在测试部署启用配置示例中的 fast-path.enabled；关闭即可让后续新任务回到原路径。已创建任务继续服从冻结契约，不能通过开关重置权限、预算、会话或晚到结果终态。旧 Worker 不声明能力时拒绝快速路径契约，避免静默降级到不兼容实现。

## 尚未验证的范围

没有重放原始 67.767 秒请求的私有完整提示词，也未复测原约 30 分钟复杂分析场景；缺少同一工作负载与完整跨度时不能宣布该问题已经解决。没有跨模型比较，因为本次仅授权 deepseek-v4-pro。没有进行大样本并发/长时间压测或全仓全部业务测试；当前性能收益限于这组工作区生成任务。PR 保持待审，不自动合并、发布或启用生产开关。
