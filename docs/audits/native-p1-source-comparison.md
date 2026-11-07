# Phase 4 P1 第一阶段：原生源码差异矩阵

日期：2026-11-07（Asia/Shanghai）。**仅提交源码审查，等待本矩阵审查后再实施代码修改。P1 专项与最终验收均未通过，也没有开始新的故障注入。**

## 固定基线与证据等级

- Runtime 基准 `d5dd48356b65e97935052c257ffc1575104bf221`；独立分支 `fix/native-coding-phase4-p1-audit-20261107`，独立工作树 `native-p1`。开始时工作树干净，未修改 P0 分支、业务入口、模型、预算或运行服务。
- SDK 固定五包 0.0.93。重新校验缓存 npm tarball 的 SHA256、SHA512/lock integrity 和安装内容，**629 个发布文件逐字节相同**。官方源码 clean checkout 为 [`1a5c3f84e0933268a9dba14a55a6383a6578414d`](https://github.com/cline/cline/tree/1a5c3f84e0933268a9dba14a55a6383a6578414d)。没有升级 SDK 或引用最新 main 的接口。
- 本次安装内容核对使用本机 P0 共用 SDK 目录；P1 工作树未安装依赖，未把本机核对冒充远端重新部署或真实模型验收。机器证据同时记录工作树原始字节和 Git blob SHA256，区分检出换行与源码版本。
- [源码与包指纹](evidence/native-p1/source-baseline.json)记录 16 个官方文件和 20 个本地关键文件 SHA256。发布证明沿用此前官方 npm provenance 载荷归属；本次未独立验证 Sigstore 证书链，也未宣称从 TypeScript 重建出同字节 npm 包。
- P0 原 105 份证据的工作树/Git 字节 Hash、19 次固定任务、84 次请求事实及既有恢复/安全记录重新只读核验通过。**这是历史证据完整性复核，不是本轮重新执行 8/8 或 P1 测试。** 原证据保留于基准提交，不重复创建存储。

状态约定：`源码确认`是已核对执行路径的事实；`历史实证`仅指已有 P0 任务；`风险/待验证`不当作已复现缺陷；`未测试`不得写成通过。旧 P0 前差异文档中“缺少验证事实”的描述不直接沿用：当前 `native-runner.js:105–123` 已保留 P0 的验证、原余额和重载修复。

以下官方 `U/` 路径相对上述官方提交的 `sdk/packages/`；本地 `R/` 相对 Runtime 仓库根。数字为当前源码行号，完整路径及字节指纹在机器证据中。

## 核心能力与差异矩阵

| 能力 | 官方源码、机制及调用链 | 当前 Runtime 源码与链路 | 分类及结论 | 最小处理及验收 |
| --- | --- | --- | --- | --- |
| 自主循环、工具选择、修复 | `U/agents/src/agent-runtime.ts:627,669,690`，run/continue → model → tools → tool-result → 下一轮 | `R/coding-agent-node/src/native-runner.js:125,162,176`，直接 new Agent、restore、continue；Java 不生成修复方案 | 已继承；P0 有真实交付证据，无第二套 Loop 证据 | 保留；禁止用 P1 重构已通过的循环或自动结束任务 |
| 文件、搜索、Editor/Patch | `U/core/src/extensions/tools/definitions.ts`、原 executors | `R/coding-agent-node/src/native-workspace.js:148,203` 调原定义、Editor/Patch/搜索，Adapter 在 IO 前校验及 staging | 原算法复用，受控差异是任务范围、文本/体积、原子持久化 | 保留权限/Hash/只读测试；P1 结束重跑原八项 |
| 命令执行 | SDK shell executor；本身可访问原宿主环境 | `R/chatchat-common/src/main/resources/python/native_command.mjs` 在 Sandbox 调同版本 SDK；Java 冻结当前项目和原命令身份 | 治理适配，不是丢失 Shell，也不应恢复宿主 Shell | 保留 Sandbox；Skill 脚本可达性需单独补齐，见 S-02 |
| MCP 定义及参数/结果 | `U/core/src/extensions/mcp/tools.ts:16`，provider.listTools → createTool(inputSchema) → provider.callTool(arguments,context) | `R/coding-agent-node/src/native-runner.js:80–87` 已调用 createMcpTools；但 HTTP gateway 无相应两个方法、Java open 无 server 清单 | **M-01：只有装配端口，生产链路断开，源码确认** | 复用 provider；接既有 Java 授权目录/网关，不写第二个 MCP 协议栈；测试发现→披露→自主调用→原结果 |
| MCP 连接及多服务 | `U/core/src/extensions/mcp/manager.ts:43,144,168,263` 按服务加锁、缓存/刷新；client.ts 支持 stdio/SSE/streamable HTTP | 既有 `R/chatchat-integration/.../McpGatewayClient.java:133,143,189` 有发现、调用、通知入口，但未接 generic native task | Runtime 已有另一治理域的传输能力；**缺连接到 Coding Agent 的治理桥，不等于全仓库没有 MCP** | 优先既有 Java 网关 + SDK provider，连接权威只留一处；不同时再建 Node 连接管理器 |
| MCP 故障隔离 | `U/core/src/runtime/orchestration/runtime-builder.ts:207,280–307` 并行 allSettled，单服务失败不丢弃其他成功工具 | `native-runner.js:80` 串行 await；任一失败会退出 prepare | **M-02：候选路径有静态可用性差异；当前生产路径尚未接通，未作真实故障复现** | 复用原多服务隔离语义；必需服务/授权失败不可静默放行；一坏一好服务真实测试 |
| MCP 动态目录/Schema | manager.refreshTools 更新服务缓存；createMcpTools 返回当次静态 AgentTool[]；高层 builder 会话装配 | Native 合同工具固定，`NativeCodeWorkspace.TOOLS` 只容许六种原生工具 | **M-03：即使补 gateway，现有准入仍拒绝 MCP 命名工具。** 动态授权扩大不能偷偷改旧合同 | 冻结 server/tool/Schema 身份并逐次再授权；同会话热更新不是此版已证明的默认能力；按明确会话版本策略验收 |
| Skill 发现、选择和内容 | `U/core/src/extensions/agent-plugin/loader.ts:1061` → `user-instruction-service.ts:322` → `user-instruction-plugin.ts:200`；SDK 按模型给定 Skill 名返回正文/root | `R/coding-agent-node/src/native-skills.js:117–133,149` 原 discovery/service/executor；模型调用 skills 决定加载 | 已继承；无 Java 按相关性替模型选 Skill 的证据。显式绑定是授权目录，不等于模型选择已被 Java 接管 | 保留 SDK 选择，授权目录与自主选择分开验收；单/多/不匹配 Skill |
| Skill 目录、资源及上下文 | SDK configuredSkills 元数据与正文调用分开；本地 watcher/refreshType 可刷新源 | `NativeCodingTaskService.java:60,75,97–128` 显式绑定且最多 8 项；`CodingSkillReader.java:30–102` 冻结所有包/资源；Node:77–111 全部落地 | **S-01：包/资源预取属源码确认；全量注入模型上下文不成立。** 大目录 Node 构件测试不能证明 Java 入口突破 8 项上限 | 先度量预取/模型披露/重载成本；必要时复用 SDK 元数据发现和现有授权快照，不能另造 Skill 选择器 |
| Skill 原包脚本 | `user-instruction-plugin.ts:32` 返回 skill-root；脚本运行仍靠文件/命令工具，不是 skills 工具自动执行 | Node root 在 `/tmp/runtime-cline-authorized-skills`；`NativeCodingCommandService.java:63–104` 只上传当前项目；`NativeProjectSnapshot.java:16` 只落地项目 files | **S-02：原包 root 未进入 Sandbox 的直接执行视图，源码确认。** 模型可能读取后复制到项目再运行，不能据此说完全不能执行代码 | 论证复用现有只读资源/快照传输，保留原包版本/Hash；不让 Worker 执行脚本，不写 Skill 专用执行引擎 |
| Skill 授权/恢复 | SDK 不提供 Runtime 租户授权 | `CodingSkillReader.read:42` 每次 scopes.resolve/resolver 再校验；Node authorize:48 检查 Hash、每次调用/资源读均走授权 | 已有权限治理；不是“缓存可永久绕过撤权”。真实发布、多 Skill、撤权/压缩/重启组合仍未测 | 保留实时权限与冻结内容区分；新增 Skill 按已冻结目录规则验证，不自动扩大旧授权 |
| Skill 错误隔离 | SDK executor 对不存在/重入 Skill 返回反馈；loader 提供 diagnostics | Node:118 任意 discovery error/数量不一致会中止准备；所有资源预取失败也可阻断 Agent 创建 | **S-03：静态行为差异/风险，尚未证实真实任务故障。** 完整性失败必须拒绝，不能泛化为“忽略坏包” | 区分必需/可选包、完整性失败/可恢复不可用；真实失败反馈及其他 Skill 可用性测试 |
| Context/Checkpoint | `U/agents/src/agent-runtime.ts:669` restore 重置 SDK usage；高层有 FileSessionService/SqliteSessionStore | `native-session.js:24` → Java/Silo 原 journal；`native-runner.js:105` 每轮从原回执/余额恢复事实 | 已继承 SDK 会话行为并有必要治理；不能因 SDK 有本地存储就再建第二套 checkpoint 或预算账本 | 复用既有恢复，不把 SDK usage 当累计账单；原预算、Hash、同 session 核对 |
| 跨服务恢复 | 原 SDK snapshot/restore 只覆盖 Agent；本地 session persistence 不治理 Java/MCP/Docker 多进程事务 | `ClineCodingAdapter.resume:143` → fence/回执协调 → checkpoint restore；`PythonManagedExecutionService.recoverPending:248/reconcile:355` 恢复原容器状态 | P0 同任务 Worker 恢复有历史实证；**多服务完整交付缺实测，不是已经证明所有恢复实现损坏** | R-01 按故障矩阵实测，发现具体故障后才修复；未知结果不重派，不新建任务冒充恢复 |

## 七个审查问题的明确回答

1. 已继承：原 Agent Loop、原工具定义与编辑/搜索算法、工具反馈、基础/有状态压缩、restore/continue、SDK Skills discovery/loader，以及 MCP provider 装配入口。继承 API 与当前端到端通过分开标记。
2. 仅接口未闭环：generic Coding MCP 的服务器目录、HTTP gateway、Java 回调/准入、动态 Schema/回执恢复。逐层断点见 [MCP 审查](native-p1-mcp-audit.md)。
3. 被本地逻辑替换/约束的部分：SDK 默认宿主 IO/进程/连接配置改为 Runtime 授权、Sandbox、Silo 与 fence；Skills 本地全目录发现改为任务绑定目录。模型仍规划、选工具、选 Skill、构造参数及修复。治理替换与自主决策替换不是同一回事。
4. 未发现新 native Agent Loop/Editor/Skills 选择算法。Node 分发包、Java 权限冻结/执行账本有不同职责；不能按代码量判为重复。**风险是未来同时加 SDK MCP manager 和已有 Java 生命周期，或为 Skill 脚本再写执行器**，本阶段拒绝这类未经论证方案。旧业务 repair 协议是兼容路径，不能据其存在认定 generic native 路径仍由 Java 修复。
5. 确认缺口 M-01/M-03、S-01/S-02；M-02/S-03 是源码可见异常传播差异但未真实复现。恢复目前主要为 R-01 证据缺口与待测边界，没有把未测项冒充确定的恢复缺陷。
6. 直接复用：createMcpTools/name transform/Schema、SDK Skills discovery/service/executor/metadata、原 IO/Editor/Patch/shell、原压缩/restore。高层私有 loadConfiguredMcpTools 不当作 npm 公共 API 导入；应复用其公开构件与隔离语义。
7. 确需 Runtime：服务/工具授权与 Schema 冻结、逐次撤权、执行身份/结果未知协调、Skill 只读资源进入 Sandbox 的版本和 Hash 治理、跨服务累积预算/产物归档。它们不应包含模型相关性选择、参数代填或代码修复决策。

## 经过审查后的最小实施顺序（尚未执行）

| 独立单元 | 前置与最小修改边界 | 必须验证 | 本阶段状态 |
| --- | --- | --- | --- |
| P1-01 MCP | 先复用既有服务授权/网关；补目录、provider 回调、命名工具准入/原参数/Schema/回执身份；保留模型自主选择 | 多服务发现/自主调用、真实错误/中断、Schema 漂移、撤权/跨租户、恢复不重复副作用 | 未修改、未测试 |
| P1-02 Skills | 先保持原 discovery/loader，度量预取；只补已确认资源执行视图缺口，旧/新目录与版本策略明确 | 七类任务，含多包、不匹配、资源/脚本、错误、大目录、动态新增；再加撤权/压缩/重启组合 | 未修改、未测试 |
| P1-03 恢复 | 先注入故障并检查原 journal/回执；只修经复现的协调缺陷，不新建恢复状态库 | Java/Worker/Sandbox/MCP、模型超时、网络、多服务、二次中断；同任务、原预算、全产物 Hash/停止 | 未注入、未测试 |
| P1-04 对照 | 官方客户端/CLI 与 SDK 参考明确分组；任务、数据、权限、模型参数和预算尽量统一，差异逐项登记 | 核心能力/工具可达性、最终质量及产物可靠性；再观察重复调用与资源开销 | 仅制定口径；未执行 |

## 对照验收口径与审查门槛

**不要求逐任务模型调用次数或耗时机械相等。** 硬门槛是原始需求、授权能力和工具可达性、正确计算/代码及有效测试、完整报告/数据/图表（任务需要时）、交付/归档/Hash 与恢复可靠性。相同原预算是上限条件，不是消耗次数必须相同。

模型输入/输出 Token、调用数、修复轮次、按工具分类的次数/错误、开始至交付时长分别记录。连接初始化、授权、快照/Hash、对象存储、回执协调列作可解释治理开销；不简单从总时长减去估计值来制造“等速”。不能观测时记未测量，供应商内部重试不冒充 Runtime 调用。性能差异需重复样本和归因，不能遮蔽不必要循环，也不能仅因更慢就判能力缺失。

审查需确定：MCP 连接治理权威及 Schema/会话版本规则；授权 Skill 目录来源、预取边界及脚本只读视图；恢复未知副作用的处理与故障样本；对照各组可比边界。**用户已明确要求先审查矩阵再动代码，本阶段在此停止实施，不新增功能、故障注入或模型回归，不合并 main。**

本阶段仅文档/源码指纹，回滚为撤回该审查提交；P0 产品与运行环境无迁移、无回滚操作。后续每个专项独立提交，必须提供修改前失败、最小补丁、组件/集成/真实任务及停止/归档证据，最后重新完成完整 Java/Node 与原 P0 八项验收。
