# Runtime OS 工具结果审查修复交付记录

日期：2026-10-25，Asia/Shanghai。当前状态：代码已集成并补强，构建及定向回归通过；**尚未部署本轮版本，尚未完成优化后联机验收**。

## Git 与修改保护

- 工作仓库位于 `Langchain4j-Chatchat/Langchain4j-Chatchat`。初始分支 `fix/workspace-mcp-delivery`，HEAD `011f6d35a`，上游领先 13 个提交，本地独有 3 个提交；存在未结束的合并及 3 个冲突。
- 在父目录 `runtime-review-backup-20261025-225310` 备份完整 `.git`、索引、合并状态及相对 HEAD 发生变化的工作文件。没有执行 hard reset、merge abort 或 stash，也没有删除现有改动。
- 专项分支：`fix/runtime-os-tool-review-performance`。合并提交 `f73f1ad25` 保留本地系统搜索能力与远程已发布数据供给、Cline 修复；三个冲突文件分别为 `McpServiceController.java`、`PublishedMcpToolCatalog.java`、`McpCenterView.vue`。
- 随后合入 `origin/main`（`86d496089`）以及最新相关分支 `origin/fix/stable-java-data-acquisition`（`bfc8c538e`），对应合并提交 `b7f418a2d`、`ce851f0c7`。仅同步旧上游会遗漏今天的性能修复，因此同时检查了远程其他分支。
- 本轮补强提交：`e6eba0291`。未强制推送，也未修改其他本地分支的提交指针。

## 遗留问题与状态

| 优先级 | 问题及证据 | 处理状态 |
|---|---|---|
| P0 | 未结束的 Git 合并及三个冲突，阻止安全同步/发布 | 已备份、解决冲突并合并最新主分支和修复分支 |
| P1 | 双阶段发布工具按静态模板角色审查资产，已命名规范资产仍调用模型并超时 | 已集成 `9877a9012` 的动态阶段识别与精确资产准入；待本轮线上验收 |
| P1 | 注册表在权限查询中更新，已计算的缺失必需工具集合没有重算 | 已集成注册修订变化时的一次重新授权核对；待本轮冷启动验收 |
| P1 | 稳定固定无参模板反复做模型准入 | 已集成显式 `mcp.data-supply.v1` 发布供数路径；未声明/参数化/不完整/未授权结果保持原门禁 |
| P1 | 精确资产名称在比较或全体范围请求中可能错误缩小任务范围 | 本轮新增保守回退和回归；只出现一个规范名称不能证明用户只要求一个目标 |
| P1 | Coding 上游模型请求曾二十分钟不返回，无 Python 执行 | 历史联机记录已定位等待 HTTP 返回；本轮没有更改模型绑定、扩大预算或宣称解决上游问题 |
| P1 | 数据请求仅按 requestId/operationId 重放去重；换 requestId 的相同参数请求仍可能重新采集 | 现有 `CodingDataSupplyService.request` 可见这一边界。尚未增加跨身份缓存；需要先获得可核验模板版本、源快照版本和有效期，不能用参数相等推断可安全复用可变数据源 |
| P2 | 全仓历史测试未全绿 | 既有文档记载旧 `AgentOrchestratorTest` 和三项 `CommandTemplateDiscoveryDatabaseQueryTest` 失败；不删除历史实现，也不将本轮定向通过说成全仓通过 |
| P2 | 同模型/数据快照/硬件的多样本前后压测不足 | 当前无本轮部署后的样本，P50/P95/P99 及 50% 目标均待验收 |
| P1 | 旧受管清单引用的 `release-20261025123929/assets` 已不存在，导致发布包预检查失败 | 从运行中只读挂载恢复 12 个原文件；先逐个匹配原封存清单 SHA256，再写入原缺失路径；未覆盖已有文件、未生成新密钥、未重启服务 |

2026-10-25 公开 GitHub 页面显示 [Issues](https://github.com/mschreinerhaw/Langchain4j-Chatchat/issues) 和 [PR](https://github.com/mschreinerhaw/Langchain4j-Chatchat/pulls) 的开放数均为 0；并不据此推断代码不存在缺陷。检查相关 Runtime/Coding 源码中的 TODO/FIXME/HACK，没有将注释统一转换为缺陷。

## 根因与代码位置

- `AgentOrchestrationEngine.reviewInterpretationPlanToolResult`：通过现有 `ToolProtocolPayloadNavigator` 识别实际返回阶段；先尝试声明式资产选择、精确规范资产引用和发布基线供数，再决定是否需要模型。成功执行及元数据证据沿用原确定性路径。
- `CanonicalAssetReferenceReviewer.review`：完整授权页、显式成功、非截断、计数一致、启用资产、无身份冲突才可能准入；不采用单候选、首位排名或模糊匹配。本轮增加比较和包含全体目标语句的语义审查回退，测试在同名测试类中。
- `PublishedDataSupplyReviewer.review` 与 `McpDataSupplyPolicy`：固定发布范围及明确版本契约下，仅使用授权返回的无参模板。分析覆盖度仍为 `NOT_EVALUATED`，不是业务结论充分性的保证。
- `ToolReviewPromptContext` 和审查提示词构造：复用已集成的单请求共享 schema 字典、历史证据身份投影和大输入 SHA256 引用；保留尾部候选能力和原始执行输入。
- `ModelInvocationBudget`、`DeadlineAwareChatModel`：沿用同一模型执行器，把审查、覆盖审计、绑定纳入同一配置阶段预算；保留取消与全任务截止时间。
- `AgentToolPolicyResolver`：注册表修订改变时重新核对绑定及授权，不添加未绑定工具。
- `CodingDataSupplyService`、`CodingInputSnapshotService`、`DurableCodingTaskService`、Node runner：保留原有授权后重放、不可变输入、执行回执、预算及任务状态契约。未另建 Runtime、模型机制或数据传输系统。

完整相关文件变更可通过 `git diff 011f6d35a..e6eba0291 --stat` 审阅；其中包含同步来的功能，不全是本轮新写代码。

## 实际基线与性能边界

SSH 到目标环境成功，Runtime 认证 API 及任务/事件 API 实际访问成功。运行版本核对为 `715e09f92`：

- API JAR SHA256：`665b3cdb15db3f82b6f69383fcaf1ecb17f82f4280dc5f53794e87cdc8865e0b`。
- MCP JAR SHA256：`fc5854e7c3b1e117e74126bf3d0689073bfa857ed10b6a416c27107f6d72272f`。
- 任务：`6c2eb79c-72ed-32fe-a91f-6d532d231832`；运行：`att-1-56efb611-607b-4722-b4e6-329d3568d56e`。
- 官方持久任务记录：2026-10-25 18:18:15.266889 至 18:20:48.596，最终 `FAILED`。
- 模型日志显示资产 Review 输入 21,876 字符、估算 6,856 token，模型等待 59,976 ms 后抛 `ModelPhaseBudgetExceededException`。生命周期整个 Review 为 60,012 ms。

| 指标 | 改造前实际样本 | 本轮改造后 |
|---|---:|---|
| 端到端 | 153,329 ms，失败样本 | 待部署测量 |
| Tool Result Review | 60,012 ms，超时 | 待部署测量 |
| 该次 Review 模型调用 | 1 次失败，输出 token 未观测 | 待部署测量 |
| Coding / Python | 未进入 / 未进入 | 待部署测量 |
| 重复调用、快照重复读取及传输耗时 | 本样本不足以判定 | 待完整 Trace |
| P50/P95/P99、首次有效结果、成功率 | 无同条件足量成功样本 | 尚不能判定 |

此失败发生在资产语义审查，不能归因于 Python 速度。另一次二十分钟 Coding HTTP 等待属于不同阶段，详见原 `coding-model-response-repair-20261024.md`。已有 30 候选离线夹具的 41.8% 字符缩减与 39.0% 估算 token 缩减见原 `tool-review-performance-20261025.md`，不充当本轮联机 A/B。

## 构建、测试与发布

- 初次标准 `mvn -pl chatchat-api,chatchat-mcp-server -am package -DskipTests -Dfrontend.install.skip=true` 已 `BUILD SUCCESS`，耗时 11:42；两个前端均实际构建。该步骤在新增边界补强编译前启动，所以不单独作为最终发布产物。
- Node 初次运行缺少 `@cline/sdk`。执行锁文件 `npm ci --ignore-scripts` 后重跑：**52 项通过，0 失败、0 跳过**。
- 最终 Java 定向回归及 package：300 项，**299 通过、0 失败、1 跳过**，整体 `BUILD SUCCESS`，耗时 6:49。跳过项为 `CodingDataSupplyServiceTest.sqlAnalysisUsesRealStaticParserAndSameToolBudgetWithoutExecutingSql`，原因是本机未设置 `coding.sqlglotPython`。
- 补充执行回归：**79 项通过，0 失败/跳过**，覆盖 Coding 预算、状态修订、Loop、不可变快照、Python 回执、鉴权及取消恢复。最初直接调用 `surefire:test` 因 reactor 未进入 compile 生命周期、缺少本地依赖产物而启动失败；改为完整 reactor `test` 生命周期后通过，不是隐藏一个用例失败。
- 两轮 Java 合计 **379 项，378 通过、1 跳过**。Node **52 项通过**；MCP 卡片、工具发布、Runtime 阶段展示前端测试 **40 项通过**。不代表全仓测试通过。
- 最终 package 复用同次首次构建的前端产物，没有修改前端源码。生成的跟踪静态文件恢复到构建前状态，最终 JAR 保留本次构建结果。
- 本轮 API JAR SHA256：`854026c00480746cf44f6218304814284419dcb44e1afaf5bf3cb7044e68e699`；MCP JAR：`10768bd17834981f4e2a59050ebe72754f672e863c60bf602625947eb30d8766`。产物源码提交为 `e6eba0291792faefc49a6cf10b3674502bd66aed`。
- 日志在本地忽略目录 `target/runtime-review/`；原始服务器任务和事件在 `/opt/runtime-review-20261025/` 私有目录。不提交凭据或业务原始输出。
- 已准备服务器目录 `/opt/live-monitor-managed-releases/release-20261025-tool-review-e6eba0291`，复用原受管发布 Dockerfile、构建及校验辅助脚本。完整 JAR 上传后校验 SHA256、包内修复类及 API/MCP 公共模块一致性通过。
- 不可变 API 镜像：`localhost:18443/runtime-os/api@sha256:f1ca714d81d6fbc3820102b006721ec7ae8239766688fe973e9d53b0e8b10704`；MCP 镜像：`localhost:18443/runtime-os/mcp@sha256:9c551fbba1a097b14bf1ba720149e66a9515fb2908b9990f8ce6064626d46890`。
- 已生成并登记封存候选包，引用 `release-20261025-tool-review-e6eba0291`，清单 SHA256 为 `2b208620edbc89a1a385c05fec0f81d07e9bb09605a95815d4d1e9ef0805d53d`。保留 `715e09f92` 的回滚镜像和配置，非镜像部署参数未改变。**登记候选包不代表已部署**。
- 最终预检查通过：封存清单、固定镜像引用和不存在 JAR 挂载覆盖均校验通过；准备前后 **48 个既有容器**的镜像及启动时间不变，生产 API/MCP JAR 哈希不变。服务器证据为候选目录下的 `pre-deployment-public.json`、`prepared-public.json`、`asset-recovery-public.json`。
- **本轮尚未部署**。现有 Runtime 登录有效，但已找到的 Live Monitor 控制台登录信息返回 401；已请求提供有效登录信息的安全存放路径。遵守 [现有受管发布流程](live-monitor-production-runbook.md)，没有绕过平台停机备份/回滚，也没有重置账户或修改生产认证配置。

下一步必须完成受管部署、注册表就绪检查、原问题的完整采集与 Coding/Python/结果校验、空数据/QA/文件/权限回归，再做同模型同快照多样本比较。服务健康或直接采集成功均不能替代最终报告验收。
