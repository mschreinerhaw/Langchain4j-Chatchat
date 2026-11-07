# Cline CLI 原生化 Phase 1 源码审查与架构对照

日期：2026-11-07（Asia/Shanghai）。授权范围：仅 Phase 1；先复用，再扩展；先证明，再重构。

## 评审结论

1. **CLI 确实进入官方完整宿主链路，并复用官方 AgentRuntime 循环**，不是 CLI 自写循环。但“完整复用”不能理解成每种模式、每个模型默认开放全部工具。工具路由、扩展、权限和运行模式仍决定实际能力。CLI 3.0.74 的 Core 是 **0.2.0**，当前 Runtime 是 **0.0.93**；CLI 与旧 SDK 的效果比较同时存在版本和集成方式两个变量。
2. **当前 Runtime 已复用官方低层 Agent、Editor、Skills、压缩和 restore**。缺口主要在宿主装配与能力暴露：Skill 专用文件读取不能读取代码目录，缺少通用代码搜索、原生 Patch 和一般命令工作区；模型还需承担 Runtime 自定义代码版本/修复协议。不能将这些事实写成“SDK 没有 Agent Core”，也不能将生成代码错误、输入说明缺陷和供应商超时全部归因于 SDK。
3. **CLI 提供服务化所需的大部分原语，但尚不能判定满足 Runtime 生产生命周期合同**。普通 `--json` 不等于双向授权 RPC；默认 auto-approve=true；`--data-dir` 会切换 local 路径；默认 Hub 是可共享进程。one-shot 首轮 timeout 和启动期间 abort 有源码风险。需保留 Java 的租户授权、预算、fencing、Sandbox、停止证明与交付验收。

本阶段只新增审查材料和证据采集脚本。旧组件的删除均是候选建议，**未批准、未执行**。Phase 2–4 不在本次实施范围。

## 基线、来源与可复现性

| 项目 | 固定值 / 证据 |
|---|---|
| Runtime 基线 | `167dfedf5ab4b9e898accc84587644fcd03398bf`，原主工作区干净；现有分支 `fix/runtime-cline-phase2-20261107`。这是当前代码对照基线，不宣称生产已全面验收 |
| 专项分支 | `feature/runtime-cline-cli-native-20261107`，独立 worktree `D:/IdeaProjects/Langchain4j-AI/cline-cli-native` |
| 官方仓库 | https://github.com/cline/cline |
| 正式 CLI Release | [CLI v3.0.74](https://github.com/cline/cline/releases/tag/cli-v3.0.74)，2026-10-26 发布；draft=false，prerelease=false |
| Annotated tag object | `04b67752bfcc31c225b39141ef28573382777817`；未签名，不声称签名验证通过 |
| 解引用源码 commit | `071e1b25b48f9ef5dff7fc32085e6a16f02bf02e`；后续所有 U 路径均基于此 SHA |
| 正式分发 | npm **`cline@3.0.74`** wrapper → `@cline/cli-windows-x64@3.0.74` 原生二进制；不是把源码包名 `@cline/cli` 直接当安装包 |
| CLI/Core 关系 | `apps/cli/package.json` → workspace `@cline/cline-hub` / Core；`sdk/packages/core/package.json` 为 **0.2.0**，编译时打包进二进制 |
| Runtime SDK | `coding-agent-node/package.json` 固定 sdk/core/agents **0.0.93**；既有发布载荷来源提交 `1a5c3f84e0933268a9dba14a55a6383a6578414d`，见既有 `cline-native-source-baseline-20261107.md` |
| 许可证 | 上游 Apache-2.0；保留 LICENSE SHA；本次未复制上游实现进入生产代码 |
| 二进制来源绑定 | registry SHA512 与下载 tarball 校验；npm SLSA payload 的 subject SHA512、resolvedDependencies.gitCommit 与上述源码 SHA 对齐，workflow `cli-publish.yml`，run `40266392937`。**未独立验证 Sigstore 证书链，也未重建二进制证明字节可复现** |

机器证据：[源码指纹与原生冒烟结果](../testing/evidence/cline-cli-native-phase1-20261107/source-and-smoke.json)。其中记录关键源码逐文件 SHA256、二进制 SHA256、tarball integrity 和原始 stdout/stderr。采集脚本：[cline-cli-native-phase1.mjs](../testing/cline-cli-native-phase1.mjs)。原始 npm/GitHub 元数据摘要见同目录发布记录。

## 实际执行链：逐层到模型与工具

下列 `U/` 表示[固定上游提交](https://github.com/cline/cline/tree/071e1b25b48f9ef5dff7fc32085e6a16f02bf02e)，`R/` 表示 Runtime 基线。Runtime 的 runner.js、native-skills.js、sdk-adapter.js、code-repair.js 均位于 `coding-agent-node/src/`。行号是审查定位，函数名和文件指纹是复核依据。

```text
U/apps/cli/src/index.ts
  → main.ts / commands/program.ts：解析模式、provider/model、配置和隔离目录
  → runtime/run-agent.ts:runAgent（普通 one-shot）
  → session/session.ts:createCliCore
  → sdk/packages/core/src/ClineCore.ts:create → createRuntimeHost
       local：LocalRuntimeHost
       hub：HubRuntimeHost → Hub RPC → 守护进程内 LocalRuntimeHost
       auto：发现/连接 Hub，失败可回退 local；可能预热 detached Hub
  → LocalRuntimeHost.startSession/startResolvedSession
  → orchestration/runtime-builder.ts：原生工具、模型路由、Skills、MCP、extensions
  → SessionRuntime orchestrator：createAgentRuntimeConfig → createAgentRuntimeImpl
  → @cline/agents AgentRuntime.run/continue → execute
  → prepareTurnForModelRequest → config.model.stream
  → executeToolCalls → executePreparedTool → 工具结果写回 → 下一轮
```

定位：`U/apps/cli/src/session/session.ts:49` 调用 `ClineCore.create`；`U/sdk/packages/core/src/ClineCore.ts:216` 创建宿主；`runtime/host/host.ts:17` 选模式；`local-runtime-host.ts:502` 开始 session；`runtime-builder.ts:144,210,508,601` 装配工具/Skills/MCP；`session-runtime-orchestrator.ts:1019–1080` 构造官方 runtime 并 run/continue；`sdk/packages/agents/src/agent-runtime.ts:627,791,977,2116,2360,2560` 为循环、模型流和工具执行。

这证明是源码调用链复用；不证明本次已经用真实模型跑完任务。`yolo` 或 `config.sandbox=true` 会在 `run-agent.ts` 选择 forceLocalBackend；`--data-dir` 设置 sandbox=true。因此“官方 CLI 总通过 Hub 运行”也不准确，适配器必须固定并观测实际 backend。

工具集合仍有条件：模型路由可在 Editor 与 Patch 间切换；plan/act、enableTools、extensions、toolPolicies、Skills 配置和 MCP 连接结果均影响最终 Schema。不能通过 `--help` 或依赖包存在推断模型实际看到哪些工具。

## 三方能力差异矩阵

| 能力 | 官方 CLI 3.0.74（源码） | 当前 Runtime SDK 0.0.93（源码） | 后续处理建议与限制 |
|---|---|---|---|
| Agent Loop / 模型调用 | 完整 Core 宿主 → 官方 AgentRuntime → model.stream | `R/runner.js:1084,1087` 使用 new Agent、restore；runtime-model.js 转 Java 模型桥 | 复用 CLI 原循环；保留模型授权与预算入口，不另写循环 |
| 工具注册 / 路由 | runtime-builder 的 createBuiltinToolsList，provider/model/mode 路由 | runner 自行装配授权工具；低层 Agent 不自动执行 Core 默认路由 | 候选撤销重复装配；先验证同模型最终 Schema、回执与拒绝语义 |
| 文件读取 / 搜索 | 官方 read_files、search_codebase executors | `R/native-skills.js:135–151` 只读 Skill manifest，enableSearch=false；code_read 按 hash 读当前程序 | 复用原生读/搜；将资源边界移至隔离工作区，保留输入授权 |
| Editor / Patch | 原生 Editor/Patch；model-tool-routing.ts:60 的 GPT/Codex act 规则用 Patch、禁 Editor | sdk-adapter.js:24 已用 createEditorExecutor；没有装配 createApplyPatchExecutor；repair.edits 自定义 Schema | 复用编辑算法；模型版本绑定转 host，不能删除不可变代码证据 |
| 大补丁 / 分段 | 支持 patch parser、多文件变化；不保证模型自动成功拆分大补丁 | code-repair.js 每 old/new 段 16000 UTF-16 字符，程序 256000 bytes；超限拒绝 | 列为真实修复回归项；不能宣传“CLI 无长度上限”或必然修复成功 |
| Skills 发现 / 选择 / 按需加载 | config loader 发现 SKILL.md；service + extension 将目录/描述与 skills executor 接入；模型自主选择 | native-skills.js 已复用官方 loader/service/executor，但只提供经 Java 授权的 Skill 文件视图 | 保留授权发布；候选复用发现/按需加载。不能删除后用 Java 固定调用顺序 |
| Skill 资源 / 脚本 / 多 Skill | 正文由 skills 加载；参考/脚本通过文件和命令工具访问；多 Skill 可由模型连续选择 | 授权 manifest 支持参考读取；脚本不能直接在 Node 宿主执行 | 迁入任务工作区与 Sandbox；脚本访问需跟随任务权限。自主效果未实测 |
| MCP 注册 / 发现 / 调用 | runtime-builder.ts:210 的 loadConfiguredMcpTools → settings/clientFactory/manager → listTools/createMcpTools/callTool | 当前业务工具由 Java 授权代理和自定义业务 Schema 接入；runner 没有直接装配完整原生 MCP manager 链 | 优先原生 MCP 接受 Java 代理；保留租户数据授权、审计、空结果/来源错误语义 |
| MCP 异常 / 动态更新 | manager.ts 支持 connect/disconnect/refresh/dispose；启动 listTools 用 Promise.allSettled，失败服务器可被跳过 | Java 工具、冻结合同与回调控制可用性；不等同原生动态 MCP 生命周期 | 启动成功不代表所有 MCP 可用；manager 有 refresh 不代表活跃 Agent Schema 自动热更新。需实际变更/断线测试 |
| 上下文压缩 | Core 有 state-aware prepareTurn、持久化 compaction sidecar，Agent 调 prepareTurn | sdk-adapter.js:97 sdkPrepareTurn 已调官方压缩并附代码/技术回执/预算视图 | 复用官方；Runtime 只持久化必需治理状态。候选删重复上下文拼装前测恢复后代码与引用 |
| Session / Checkpoint | 消息、manifest、SQLite；Git workspace checkpoint + session-versioning 恢复事务 | SDK snapshot/restore + Java journal/fencing/operation 和 source hash | 两者含义不同；不能用 Git checkpoint 替代 Java 预算或远端操作回执 |
| 命令 / 编译 / 修复 | run_commands 执行构建/测试，错误写回原循环；不自动保证所有语言诊断集成 | Python Sandbox + 分类技术反馈 + 原生 Agent 下一轮修复；缺通用项目 shell | 命令在独立 Sandbox 运行；原生成功退出不能代替产物验收 |
| 工具授权 | toolPolicies、requestToolApproval、ACP permission；CLI 默认 auto-approve=true | CodingTaskContract 授权工具、数据 refs、networkAllowlist、side effects | 必须保留 Java 授权。原生审批处理工具动作，不能替代租户数据许可 |
| 任务治理 / 多租户 | session 隔离与宿主生命周期原语；不是 Runtime 企业业务合同 | DurableCodingTaskService 的租约/fencing、累计预算、operation、恢复状态机 | 必须保留 |
| Python / 结果交付 | 普通命令与文件产物，无当前 Runtime 报告/引用验收合同 | CodingPythonExecutionBridge、CodingExecutionFeedbackService、CodingCodeArtifactStore / Silo | 必须保留并用原生 MCP/扩展接入；不让 Java 决定分析顺序 |

## SDK 能力退化：源码原因与历史事实分开

| 原因 / 观察 | 源码定位与证据 | 能解释什么 | 不能声称什么 |
|---|---|---|---|
| 低层宿主装配缺失 | R/runner.js:19,705,1084；native-skills.js:151；对照 U/runtime-builder.ts | 实际文件工具仅 Skill 范围，缺搜索/Patch/通用工作区；依赖官方 Agent 不等于完整 Core 配置 | 不能说低层 Agent 不具备自主循环 |
| 修复协议额外负担 | R/code-repair.js:7–15；sdk-adapter.js:11–34；runner 的 python_execute | 模型需传精确 hash、版本和有界 edits；可追踪超长补丁拒绝 | 16000 限额不是官方 Patch 算法退化；不能据此确定性能损失大小 |
| 输入说明和传输层复杂性 | 既有 runtime-cline-phase2-root-causes-20261107.md；当前 CodingInputLayout 与 runner FILE_INPUT_GUIDANCE | 原任务把原始 schema 当 Runtime 外层映射，产生 quote_code KeyError；旧版未披露已装载辅助读取 API | 本基线已有后续修复，不能把历史缺陷当作当前仍未修复；CLI 自身也不会理解私有映射 |
| 历史 Java 强制 Skill 前置 | cline-precode-latency-root-cause.md 的 c97962110 前后链路 | 原 Node 已按需选择，Java 冻结任务仍强制读取；只改 Node 不足以解除限制 | 已删除的前置句不能再次当成本次新发现；不能把必要的新 Skill 读取统称重复规划 |
| 真实生成与修复缺陷 | 历史六任务 12 份源码 hash 重建记录：NameError、Decimal TypeError、SyntaxError、缺 rows.jsonl | 部分任务真的生成并执行了代码，部分只输出诊断；退出 0 不等于报告完成 | 不证明 SDK 工具丢失造成所有错误；不能由审计手工补报告制造通过 |
| 延迟和 provider 超时 | 历史事件包含 300 秒失败、reasoning token 高、无 provider 完成回执 | 已观察到模型耗时和较大上下文并存；部分修复时间发生在首次代码之后 | 无法拆出排队/首 token/生成时间；没有等版本 A/B 不能宣称 CLI 更快 |
| 版本混杂 | CLI Core 0.2.0；Runtime 0.0.93；官方发布说明含压缩/重试/会话修复 | 新 CLI 可能带有上游改进，值得对照 | 不能将上游版本改善全归功于 CLI 包装方式 |

这些缺口说明：**只把 SDK runner 换成 CLI 启动命令不会自动消除退化**。如果继续只暴露受限工具、灌入复杂传输协议、强制业务顺序或错误判定成功，同类问题仍会重现。

## 服务化生命周期逐项判定

“源码支持”只证明实现存在；“待验证”不能记为通过。

| 要求 | 源码确认 | 边界 / 风险 / 所需证明 |
|---|---|---|
| 多任务 Session | LocalRuntimeHost.sessions Map；startSession:502 阻止覆盖 live run；带 prompt 重入被拒绝，无 prompt 可 reattach | 防重复运行不等于跨租户认证；须测 A/B 并发，交叉 session ID、文件、MCP、数据和产物拒绝 |
| 工作 / 配置 / 状态隔离 | `--cwd`、`--config`、`--data-dir`；helpers.ts:572–604 配置 DB/session/team/provider settings 路径 | `--data-dir` 只是状态目录隔离，不是 OS Sandbox；会令 one-shot forceLocalBackend。HOME/用户级 Skills、环境变量、插件、hooks 和 MCP 都须独立审核 |
| Hub 隔离 | discovery/workspace.ts:34 使用生产 owner `hub-production` 和 data-root discovery；defaults.ts 有端口覆写 | 只改变 cwd 或 session ID 不足以建立独立 Hub。若选 Hub 路径，需独立 owner/discovery/端口/auth token 和进程归属；不能把 task-local 的 data-dir 模式当作已验证 Hub 模式 |
| Headless / JSON | run-agent.ts 输出事件及 run_result；session-events.ts 订阅 Core event；events.ts 做投影 | 不是完整双向控制协议；run_start 仅 verbose 分支触发，不能依赖一定收到；CLI 可能投影/过滤 team 事件 |
| 双向授权 | 普通 approval.ts:81 非 TTY 返回拒绝；桌面 IPC 是另一模式；ACP permissions.ts:94 调 requestPermission，失败拒绝 | 普通 JSON stdin 不是审批回复通道。默认 auto-approve=true 必须明确覆盖；建议优先审查 ACP，不能用全放权代替接入 Java 审批 |
| Session 恢复 | `--id`；Core manifest/messages/compaction；ACP loadSession；live session reattach 修复 | transcript 恢复不保证远端工具 exactly-once；重入后的 budget/operation/交付去重仍由 Java 约束 |
| Checkpoint 恢复 | session-versioning-service.ts；checkpoint-restore.ts 具备 Git 工作区 restore 与 rollback transaction | Git 恢复会改变工作目录；只能操作 task 私有目录。与 SDK messages snapshot、Sandbox 快照和业务归档不可混用 |
| 事件断线回放 | hub/client/index.ts:365 保存 sinceSequence；server/hub-event-log.ts、browser-websocket.ts 支持 durable log/replay | client 游标在内存，Java 进程重启需保存自己的 cursor；eventLog 可禁用/初始化失败退化。回放有保留范围，不是无限日志/业务提交事务 |
| 正常取消 | run-agent.ts:226 abortAll；ACP cancel:405；orchestrator 在 run-started 转发预先取消 | 需验证 Windows 进程终止与远端工具停止证明；kill CLI 不保证 Hub/子进程/Sandbox 全停 |
| **local 首轮超时风险** | run-agent.ts:285 await start，:320 后才创建 timeout；:336 明确 start 可执行首轮并直接返回 result | local 首轮运行可能不受该 CLI timeout timer 覆盖；Core 两处循环未发现等效 timeoutSeconds 计时器。**HubRuntimeHost.startSession:886 创建 session 后返回 result:undefined，随后 send/run.start；Hub run-handlers.ts 另有 timeout，不能把 local 风险推广为 Hub 首轮必然失效**。实际挂起复现待做；Java 外层 deadline 必须保留 |
| **local 启动期间 abort 风险** | activeSessionId 在 await start 返回后才赋值；abortAll 在 ID 未就绪时只设置布尔状态 | local start 若运行首轮，CLI 可能未向正确 Session 传播 abort。plannedSessionId 已存在但 abortAll 未使用；Hub 创建 session 与 run 分离，另测启动窗口。需真实阻塞/取消实验，Phase 1 不修改上游 |
| 异常退出与恢复 | persistence-service.reconcileDeadSessions；Hub 守护进程重启、事件日志及 durable run queue 原语存在 | 调用端崩溃、Hub 崩溃、工具执行节点崩溃是三种故障；不能推断在途外部副作用已回滚或自动继续 |
| 退出 / 成功状态 | run-agent.ts:379–423 区分 finishReason，但主动 abort 可保留 exitCode=0；二次 SIGINT 可退出0 | **exitCode=0 不足以标记成功**。需 completed + 无未解决 operation + 真实产物校验 + 原子交付，超时/取消分别建终态 |

## 最小候选设计与组件去留（仅建议）

```text
Java 现有任务合同 / 授权 / 累计预算 / fencing / operation journal
    → 任务私有 OS 执行环境 + 固定 CLI 二进制
    → 官方 ACP 或明确固定的 local/Hub backend（评审后择一验证）
    → 官方 Core / AgentRuntime / 原生工具 / Skills / MCP
    → 经授权的 Runtime MCP：数据、搜索、Python Sandbox、产物归档
    → Java 依据执行回执与真实产物完成交付
```

不新增 Agent Loop、Skills 调度器、编辑器或压缩算法。Adapter 只桥接 task/session/operation、事件/授权、进程监管、累计预算和交付；使用原有持久化与 Sandbox，避免再建第二套任务数据库。

| 组件 / 能力 | 建议 | 保留或移除的前提 |
|---|---|---|
| runner 的 Core 同类工具装配、模型 Schema 投影 | 迁移候选，不直接删除 runner 整体 | 原生 CLI 工具/权限/模型桥验证通过，回滚业务路径仍可用 |
| 自定义代码修复对模型 Schema / 临时 Editor 工作区 | 迁移候选 | 原生持续代码目录可读/搜/改/执行；保留代码版本、hash、不可变归档与原子失败语义 |
| sdkPrepareTurn 中重复原生的上下文拼装 | 收敛候选 | 检查点恢复后代码、技术错误、引用和预算不丢失；不删除必要业务合同 |
| native-skills 的 SDK 组装层 | 转原生配置候选 | 保留 Java 发布、授权和 manifest；真正验证自主发现/按需加载/参考及脚本访问 |
| Java DurableCodingTaskService、CodingTaskContract、CodingBudgetShare | 必须保留 | 现有租约/fencing、累计预算、撤权和 side-effect 权限不是 CLI 的替代职责 |
| ClineRuntimeModelBridge / 模型回执与认证 | 保留治理语义，协议是否改造待评审 | CLI provider 接入现有模型桥尚未证明；不能通过直接配置供应商绕过 Java 预算 |
| CodingPythonExecutionBridge / ExecutionFeedbackService | 必须保留 | Python 隔离、停止证明和真实回执；通用 Shell 同样需受控环境 |
| CodingInputSnapshotService / InputFilePublisher | 必须保留 | 数据授权、冻结版本、精度、分页和文件绑定；CLI 不替代传输合同 |
| CodingCodeArtifactStore / Silo 与交付校验 | 必须保留 | 防错误成功、重复交付、产物串用、历史归档失真 |

后续适配新增代码的必要性：原生 CLI 不理解 Runtime task fencing、用户数据 refs、累计预算、Sandbox operation 和 Silo 输出合同；仅在这些治理边界做转换。若 ACP/原生 MCP 已有能力直接满足，不在 Java 再实现一遍。

## 测试证据、缺口与下一步评审条件

本轮执行的原生二进制冒烟结果以机器证据为准。发布包下载校验、版本、help、ACP initialize 握手只覆盖发布与协议入口；**不作为自主编码或生命周期验收**。

| 组别 | 本阶段状态 | 不得误报的范围 |
|---|---|---|
| A 官方 CLI | 固定正式二进制，执行无模型冒烟；完整真实模型任务尚未执行 | Agent 工具使用、Skills/MCP、自主修复、超时/崩溃恢复、租户隔离均未实测通过 |
| B 当前 Runtime SDK | 固定基线并核对现有源码及历史审计；本次未重新调用生产模型 | 历史六任务和不同版本结果不当作同条件新 A/B 数据 |
| C Runtime + CLI | **未实施**，符合仅 Phase 1 授权 | 无 C 组性能或质量结论，不生成虚构 token/时间改善数字 |

本次未从生产配置抽取凭据，也未改变共享模型/预算/部署。当前会话没有已冻结的可用 A/B 同模型参数、任务材料与授权执行环境，因此真实质量实验继续标记未执行；不能用 mock provider 替代。完整原生任务基准仍是 Phase 1 未闭环项，报告形成不等于该门槛通过。

评审时应先决定：是否选择隔离 local + ACP，还是独立 Hub + 明确协议；如何让 CLI provider 遵守现有模型桥与预算；是否先用不改 Core 的外层进程 deadline 解决 timeout 风险。确定后在同条件真实环境验证以下项目，再决定 Phase 2 范围：

1. A/B 固定相同模型、参数、输入 hash、数据、预算与总 deadline；额外记录 Core 版本差异；必要时增加同版本 Core 低层/完整宿主对照，分离版本和装配因素。
2. 普通多文件代码任务：探索、编辑/patch、编译错误、自主修复、有效产物；Skills 多选择、按需加载和资源/脚本访问；MCP 发现、授权拒绝、断连、Schema 更新。
3. 双任务并发与负向访问：工作目录、Session、配置/MCP、数据、产物、预算，以及 symlink/绝对路径/环境继承和 Hub endpoint。
4. 首轮阻塞时 timeout/取消，任务中取消，客户端异常退出、Hub/worker 崩溃、模型超时/网络断开；恢复后检查事件去重、预算持续、远端停止确认和禁止重复交付。
5. 所有比较同时报告失败、未知、usage 缺失与环境差异。模型结束 completed、进程退出0、Python退出0都不能独立作为业务成功依据。

上游同步机制建议：保持此 tag/SHA/包 integrity 与许可证清单；候选升级先在独立审查分支核对发布说明及 Core/Agent/协议差异，重跑上述合同验证后再改 pin；不跟随 main，不原地覆盖生产二进制。旧路径在后续真实验收和回滚验证前全部保留。

**本次交付停在 Phase 1 审查结果与缺口清单；不授权删除组件、迁移默认路径或实施 Phase 2。**
