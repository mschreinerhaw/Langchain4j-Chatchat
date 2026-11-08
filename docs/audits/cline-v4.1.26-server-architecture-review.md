# Cline v4.1.26 原生 Agent Server：Phase 2.1 架构审查

日期：2026-11-08（Asia/Shanghai）。任务：RTOS-CLINE-SERVER-P2。范围：源码审查、宿主差异矩阵、最小 POC 设计；提交推送后等待评审，不实施生产重构。

## 结论与选型

**选择方案 B：以官方 Standalone 产品入口为首个 POC 基础，增加 Server HostBridge 与 HTTP/SSE 外层。选型属于设计决定，尚未证明能够完成真实任务。** 保留 `common.initialize → SdkController → VscodeSessionHost → 同 Tag ClineCore → AgentRuntime` 执行链。先验证完整产品宿主，再考虑是否能进一步简化为直接调用同 Tag ClineCore。

`compile-standalone`、`HostProvider`、`common.ts` 和 Standalone Core 均为已核对的复用点；它们提供构建、依赖注入、公共初始化和 IDE 外进程通信，**不是已经验收的 HTTP Agent Server**。Standalone 等待外部 HostBridge，带 VS Code stub，仍有共享状态、审批相关性和事件可观测性需要适配。不得用“独立进程能启动”推导功能等价。

硬门槛：同一真实编码任务，在官方 Cline v4.1.26 能完成的核心动作，Server 必须通过 HTTP 提交、交互、审批和取消完成。失败要提交“缺失宿主能力 → 原生调用点 → 实际失败回执 → 最小适配”的证据，停止扩围；不得新增 Java Agent Loop、搜索/编辑/Patch/Shell 或修复协议绕过缺口。

## 现场与正式发布基线

| 项目 | 本轮确认 |
|---|---|
| 工作目录 | `D:/IdeaProjects/Langchain4j-AI/cline-cli-native`，既有独立 worktree |
| 分支 / 初始 HEAD | `feature/runtime-cline-cli-native-20261107` / `c2e70c72c`；开始时工作区干净 |
| Phase 1 | 已读 `cline-cli-native-architecture-review.md`、`runtime-cline-phase1-audit-20261107.md`；CLI 3.0.74 结论只作历史对照，不替代本次版本 |
| 官方 Tag | [`v4.1.26`](https://github.com/cline/cline/releases/tag/v4.1.26)，正式 Release，draft=false、prerelease=false |
| Tag 类型 / 源码提交 | lightweight commit ref，`a459f1d04dea030833b558be645011ab739c463b`；本次所有 U 路径固定此 SHA |
| Release / Asset ID | `424033561` / `677606386`；发布于 2026-10-26 09:06:50 UTC |
| 正式 VSIX | `cline-4.1.26.vsix`，13,908,315 bytes |
| 实测 SHA256 | `d8ea4c3505052c48e7c7a5558e7213a53827b6ba1da7e56513c87d896df51c4d`，与官方 asset digest 相等 |
| 包内 manifest | `claude-dev` / publisher `saoudrizwan` / `4.1.26` / `main=./dist/extension.js` / VS Code `^1.101.0` |
| 同 Tag 原生组件 | `@cline/core`、`agents`、`shared`、`llms`、`sdk` 均为 `0.2.0`；Standalone runtime manifest 的 `cline-core@0.0.1` 是包装包名，不能用它表示产品版本 |
| 构建依赖 | 根 `bun@1.4.2`、Node `>=22`；esbuild target `node22.15`；Standalone 打包原生模块目标 Node `22.15.0`，含 `better-sqlite3`；完整选定组件 manifest、根 lock 指纹已保存 |
| 当前 Runtime | `coding-agent-node/package.json` 固定 SDK 系列 `0.0.93`；runner、Skills、adapter、repair 文件指纹已保存，本轮没有修改 |

证据：[官方发布与 Tag 元数据](../testing/evidence/cline-server-phase21-20261108/release-pin.json)、[71 个上游文件指纹、依赖 manifest 与 VSIX 内关键文件 Hash](../testing/evidence/cline-server-phase21-20261108/source-and-release.json)、[五个优先入口与官方固定 SHA 文件逐字节交叉核对](../testing/evidence/cline-server-phase21-20261108/remote-source-verification.json)、[可重跑采集器](../testing/cline-server-phase21.mjs)。采集器只读文件，不执行 VSIX、不调用模型。

源码来自固定 SHA 的官方 codeload tarball；Windows tar 未能创建上游 instruction/skill 的若干符号链接，已如实记录，所审 regular source 文件均存在并计算 Hash。这是审查副本，**不是可执行完整 checkout**；后续 Linux 构建须用完整固定提交 checkout 并保留链接、LFS 资产和 patches。未混入 main 最新源码。

来源绑定证据是官方 Release/Tag 关联、asset digest、源码与 VSIX manifest 一致；VSIX 内含 `extension/dist/extension.js` 和 plugin runtime，没有选定路径 `extension/dist-standalone/cline-core.js`。**本次没有签名构建证明，也未重建验证二进制逐字节相同**。Standalone 是从同 Tag 另建的服务产物，不能冒称官方 VSIX 二进制原样运行。

## 官方产品执行链

`U/` = [固定上游源码根目录](https://github.com/cline/cline/tree/a459f1d04dea030833b558be645011ab739c463b)。以下路径/行号用于定位，文件 Hash 用于复核。

```text
VS Code: U/apps/vscode/src/extension.ts:activate
  → setupHostProvider → 共享文件存储迁移 → common.initialize
Standalone: U/apps/vscode/src/standalone/cline-core.ts:main
  → capture bridge token → initializeContext → waitForHostBridgeReady
  → setupHostProvider(External*) → common.initialize
共同链:
  → WebviewProvider.controller
  → core/controller/index.ts 重导出 sdk/SdkController（不是旧 Task 内核）
  → TaskService.newTask → controller.initTask
  → SdkTaskStartCoordinator.initTask
  → sessionConfigBuilder / buildStartSessionInput
  → SdkSessionLifecycle.startNewSession
  → VscodeSessionHost.create/start → ClineCore.create(backendMode="local")/start
  → createRuntimeHost → LocalRuntimeHost.startSession/startResolvedSession
  → runtime-builder 装配原生工具、Skills、MCP 与模型
  → SessionRuntimeOrchestrator → createAgentRuntimeConfig/createAgentRuntime
  → AgentRuntime.run/continue
  → prepareTurnForModelRequest → config.model.stream
  → executeToolCalls → 原生执行器 → 工具结果写回 → 下一轮模型
  → 原生事件 / 消息 / 会话持久化 → 结束当前回合或等待交互
```

关键定位：`U/apps/vscode/src/sdk/SdkController.ts:427–446` 注入审批、ask_question、原生编辑/patch/read 包装、事件回调、终端工厂；`sdk-task-start-coordinator.ts:64–175` 建会话并 `fireAndForgetSend`；`sdk-session-lifecycle.ts:255` 调 host.start。初始化异常会被捕获并返回 undefined，`newTask.ts` 可能返回空字符串，外层必须识别失败，不能记为成功提交。

`U/apps/vscode/src/sdk/vscode-session-host.ts:113–192` 设置 host capabilities、extraTools 和本地 backend；`U/sdk/packages/core/src/ClineCore.ts:212–216` 创建宿主；`runtime/host/local-runtime-host.ts:502,568` 建会话；`runtime/orchestration/runtime-builder.ts:144,508,601,621` 装配工具、Skills、MCP；`session-runtime-orchestrator.ts:1019–1080` 创建并运行官方 AgentRuntime；`U/sdk/packages/agents/src/agent-runtime.ts:977,2116,2244,2360` 为工具循环、模型流和 prepareTurn。

工具由模型、plan/act、策略和宿主覆盖决定。桌面 `getTerminalManager` 存在时会压制 Core 默认 bash 工具，以产品自己的 `run_commands` 替换；MCP 由 `McpHub → createMcpTools` 接入。Server 必须保存模型实际收到的工具 Schema（脱敏）与执行轨迹，不能只看依赖包名。

原任务书的路径线索已校正：`apps/vscode/src/exports.ts` 不存在，实际是 **`src/exports/index.ts`**；旧 `src/core/task/index.ts` 不存在，Controller 已转 SDK adapter。`createClineAPI` 仅提供开始、发消息、主/次按钮，依赖活跃 controller 和 UI 语义；它没有完整 Session、安全、SSE 和恢复合同，不选作公共 HTTP 接口。

## 四个优先复用点的边界

| 复用点 | 实际内容 | 服务化仍缺什么 |
|---|---|---|
| `apps/vscode/package.json:346` 的 `compile-standalone` | check-types、lint、`bun esbuild.mjs --standalone`；另有 `postcompile-standalone` 脚本 | 不等于产物安装/资源齐备；需显式验证打包脚本运行、native ABI、proto/rg/plugin-runtime 及冻结依赖安装 |
| `esbuild.mjs` + `scripts/package-standalone.mjs` | `src/standalone/cline-core.ts → dist-standalone/cline-core.js`；`vscode`/grpc reflection/health/sqlite 为 external；打包复制兼容 shim 和资源 | `bun run build:sdk` 是前置；runtime 打包脚本使用独立 `bun install` 和原生 prebuild 下载，不能仅凭根 bun.lock 声称所有分发依赖已冻结 |
| `src/hosts/host-provider.ts` | singleton，注入 Webview/EditPreview/CommentReview、日志、callback、binary、路径；桥接 workspace/env/window/diff | 不提供 HTTP、租户隔离或工作区沙箱；同进程不同租户不能靠切换 singleton 实现隔离 |
| `src/common.ts:initialize/tearDown` | StateManager、provider handler、Webview/controller、endpoint、遥测/同步/清理服务 | 仍有宿主调用及后台服务；存储初始化错误被捕获，ready 不能只依据 initialize 返回；服务器须明确日志/遥测/endpoint 与副作用配置 |
| `src/standalone/cline-core.ts` | 先等 HostBridge health；无 token 时起 ProtoBus gRPC，有 token 时建立 CoreConnection；SQLite instance/folder locks；退出清理 | 没有公网 REST/SSE/租户合同；无 token ProtoBus 使用 insecure listener，不能直接暴露；现有 test HostBridge 是 mock，不能交付使用 |

Standalone 内的 `standalone/runtime-files/vscode/*` 是兼容层：部分 UI API 返回空值或 stub。不能把“没有 import 错误”当作原生能力通过。另一方面，不能据此声称 Shell/编辑必然缺失：本 Tag 已有专门的 headless 执行路径。

## 能力、依赖和退化风险

| 能力 | 已追踪的官方实现 | 判定与 POC 检查 |
|---|---|---|
| 搜索/读文件 | Core 默认工具及 workspace read executor，模型工具路由 | 可复用算法；验证相对路径、ignore、rg 二进制、真实工作区、越界拒绝 |
| 多文件编辑/Patch | `SdkDiffEditCoordinator → createEditorExecutor/createApplyPatchExecutor`，diff 仅预览 | 真实落盘属于官方执行器；headless 可设 backgroundEditEnabled 跳过预览，审批必须继续保留；大 patch/冲突/错误反馈须实测 |
| Shell/构建 | `vscode-terminal-execution-mode.ts` 在 Standalone 将 vscodeTerminal 限为 backgroundExec；`vscode-run-commands-tool.ts:562` 调 `createShellExecutor` | 已有 Node child_process 路径，不能再做 Java Shell 工具；前台 IDE 终端分离行为未等价，构建进程树、输出、exit code 和取消仍需证明 |
| 审批/追问 | `SdkInteractionCoordinator` 挂起 resolver；`TaskService.askResponse` 驱动活跃任务 | 复用等待/拒绝/反馈；外层须增加持久 permissionId、toolCallId、epoch、到期和原子关联；原 RPC 没有请求级 permission ID，不能盲转按钮 |
| Skills | 同 Tag Core user-instruction config service、loader、skills executor | 验证目录发现、按需选择、引用与脚本；隔离 HOME/CLINE_DIR 和已授权资源；不由 Java 指定工具顺序 |
| MCP | 产品 `McpHub` 与 `McpHubToolProvider/createMcpTools`；Core 也有默认 manager | POC 优先保留产品配置链，不同时重复装配；动态配置后活跃工具 Schema 更新仍需实际任务验证 |
| 压缩/恢复 | Core 原生 prepareTurn、会话/快照/checkpoint；产品 session lifecycle | 不另建压缩模型；验证重启后历史、代码、引用、用量；原生恢复不等于远端副作用安全重放 |
| 事件 | `CoreSessionEvent` 有 chunk/agent_event/hook/status/ended/snapshot 等；产品 coordinator 再转换 UI 消息 | `StateService.subscribeToState` 是状态投影，不能证明每个工具/模型事件无损；需要宿主观测出口及 durable SSE journal |
| 诊断/编辑器 | HostBridge 的 workspace diagnostics、tabs、diff 等 | 构建错误可由原生命令反馈；IDE 语言服务诊断不可伪造为空诊断成功；若真实任务依赖它则报告宿主缺口并补适配或回到 A |
| 模型/认证 | 同版本 provider config 与 handler；VS Code LM 是 host-only | Server 采用批准的模型网关，测 provider/baseURL/参数/取消/预算；Standalone 的 VS Code LM access=false，不能宣称全部模型宿主功能等价 |

两项需明确的源代码风险：`standalone/cline-core.ts` 启动时记录 `JSON.stringify(process.env)`；即使 bridge token 已 scrub，其他凭据仍可能泄露，必须在宿主入口删除全量环境日志。`vscode-context.ts` 使用文件型 SecretStore，不能共享用户数据目录；worker 必须独立 HOME、CLINE_DIR、workspace storage、凭据和持久卷访问权限。

## A / B 路线对照

| 维度 | A：Headless VS Code Extension Host | B：官方 Standalone + Server HostBridge（选择） |
|---|---|---|
| 原生能力保留 | 原始 VSIX 和真实终端/编辑器/诊断最接近产品；仍须 headless 启动和 API 注入验证 | 保留公共产品初始化、会话、MCP/Skills/原生执行器；IDE 专属能力需显式说明 |
| VS Code 依赖 | 完整 VS Code runtime，部分环境仍需虚拟显示/workbench，不能假定 `--headless` 单参数足够 | Node + 官方兼容层 + 外部 HostBridge，仍非纯无宿主 Core |
| 资源 / Docker | Electron/系统库/每租户扩展宿主，预期更重，未测量 | 无 Electron；预计镜像更小，但 native sqlite、rg、git、shell/语言环境仍必需，未测量 |
| 多 Session / 隔离 | 按独立宿主/工作区/用户数据隔离 | 首版一个执行 worker 仅一个活跃 session，多个 worker；不共享 singleton 或用户级配置 |
| 恢复 | 产品历史 + 外层治理 journal；仍需远端幂等 | 同左；不以 locks.db 或会话快照替代 Runtime journal |
| 修改量 | 外层自动化/通信 + 插件桥，尽量不改 VSIX，具体工作量待验证 | 新增 HostBridge/HTTP/持久 journal；少量宿主观测、审批关联和日志修补；不改 Core 算法 |
| 升级 / 维护 | VS Code 与扩展双版本验证 | 固定上游 SHA，维护小型宿主差异；每次升级重跑对等任务 |
| 当前可行性证据 | 只有源码/分发基线，未做真实 headless 任务 | 有官方 Standalone 构建和运行代码，未做本项目 HTTP 真实任务 |

选择 B 的理由是官方已有 IDE 外入口与 background 执行路径，具备避免维护完整 VS Code 环境的复用基础。**未声称 B 已被证明修改最少或能力完全保留**。若 POC 出现需要大改 Agent Core 才能弥补的宿主缺口，停止 B 并提交 A 的验证设计，不继续用 Java 补执行器。

直接使用同 Tag `ClineCore.create` 是 B 的后续简化候选；它与旧 SDK 0.0.93 不是同一产品基线。但绕过 `SdkController/VscodeSessionHost` 会丢失产品工具覆盖、McpHub、设置/权限和事件适配，不能因为公共 API 存在就宣称与官方产品等价。首个对等 POC 暂不走此捷径。

## OpenCode 只参考服务合同

已核对 [OpenCode 官方 Server 文档](https://docs.opencode.ai/docs/server/)（读取于 2026-11-08）。借鉴其 Session、异步 Prompt、Permission、Abort、Diff、SSE 和 OpenAPI 的客户端/服务端分离模式；本项目采用任务书 `/v1` 路由，并自行定义租户认证、幂等、审批关联及回放边界。这里没有审查或复制 OpenCode Agent Loop，不主张两者内部事件或恢复语义相同。

## 许可证与构建限制

Cline 源码根与扩展 LICENSE 为 Apache-2.0，VSIX 内 LICENSE 已指纹记录；后续分发保留许可证、适用 NOTICE、改动说明和第三方声明。OpenCode 仓库元数据标明 MIT，本轮只参考接口设计，不引入其实现或依赖；未来复制任何代码须单独审查。

已保存根 bun.lock、Standalone package-lock 及组件完整 manifest（含所有声明依赖和 overrides）Hash。**声明依赖范围不等于镜像内实际解析版本或完整第三方许可结论**。当前无 Bun，未安装上游依赖、未编译、未生成完整 transitive SBOM；原生 prebuild/rg/语言工具链及其分发许可仍是下一阶段构建门槛。不能将这些待办标为许可审查通过。

## 下一阶段条件与停点

1. 评审本报告、[宿主差异矩阵](cline-server-host-diff.md)与[最小 POC 架构](../design/cline-agent-server-architecture.md)。
2. 按固定提交在独立 Linux 构建区恢复完整源码，准备 Bun/Node、冻结解析清单、资源 Hash 和 SBOM；不改变生产默认 Coding Agent。
3. 按[同任务 HTTP 验收计划](../testing/cline-server-native-comparison.md)执行官方产品 / Server 对照，SDK 0.0.93 另列历史参考组。
4. 任何关键能力未通过，输出具体缺失宿主能力；POC 未通过禁止扩大 Docker/Runtime 集成、删除 SDK 或迁移默认路径。

本轮结果：发布包校验及静态执行链审查完成；技术路线与 POC 设计可供评审。Standalone 构建、真实模型任务、HTTP 能力对等、动态 MCP、故障恢复、资源性能与完整许可清单均未验证。此报告不是服务化验收通过报告。
