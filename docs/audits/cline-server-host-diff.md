# Cline Server 宿主差异矩阵

日期：2026-11-08。上游固定 `v4.1.26 / a459f1d04dea030833b558be645011ab739c463b`。本文件是设计差异，**本轮未实施下列修改**。源码指纹及调用链见 [Phase 2.1 审查](cline-v4.1.26-server-architecture-review.md)。

## 官方复用与最小改造

| 组件 / 官方位置 | 处理方式 | Server 宿主责任与验证 |
|---|---|---|
| `apps/vscode/package.json:compile-standalone`、`esbuild.mjs`、`scripts/package-standalone.mjs` | 沿用官方构建入口；分发冻结适配 | build:sdk、protos、资源打包、native ABI、依赖解析与制品 Hash；不能以 npm registry 同版本号替代同 SHA workspace 源码 |
| `src/standalone/cline-core.ts` | 保留执行链；必要的小范围入口补丁 | 删除全量 env 日志；显式只注入许可配置；启动/关闭受 worker supervisor 管理；无无限共享进程状态 |
| `src/hosts/host-provider.ts`、`common.ts` | 复用 singleton/公共初始化 | 每 worker 私有状态；验证 StateManager、endpoint、MCP、资源可用后 ready；不把 singleton 当 Session 安全边界 |
| `ExternalHostBridgeClientManager`、bridge auth、CoreConnection / ProtoBus | 复用生成协议与连接 | 新增真实 Server HostBridge；桥接仅 worker 内部可达。选定一种传输并测认证/重连，不向外开放反射、任意 RPC 或明文 ProtoBus |
| `proto/host/workspace.proto` | 新 HostBridge 实现 | `getWorkspacePaths` 返回授权根；save dirty document 在无编辑器状态下明确 wasSaved=false；searchWorkspaceItems 可按协议 UNIMPLEMENTED 以启用原生 rg fallback；diagnostics 未实现必须可辨识 |
| `proto/host/window.proto`、`env.proto` | 新 HostBridge 实现 | 真实 host identity、受控日志/telemetry、shutdown；需要用户输入时转 HTTP 交互。编辑器 tabs 等 UI 能力按明确能力表报告；不能统一空响应假成功 |
| `ExternalEditPreview`、`proto/host/diff.proto` | headless 采用 backgroundEditEnabled；保留原生写入 | diff 预览可用文件内容/patch 表达；审批不依赖弹窗；不能用 diff handler 新写一套 Patch 算法 |
| `SdkDiffEditCoordinator`、Core Editor/Patch | 原样复用算法 | 验证真实多文件写入、拒绝不落盘、大补丁、冲突和错误回到原循环；仅移除展示依赖 |
| `vscode-terminal-execution-mode.ts`、`vscode-run-commands-tool.ts` | 沿用官方 backgroundExec/createShellExecutor | 在授权工作区与受限 worker 执行；原生输出、exit code、signal 返回 Agent。supervisor 负责进程树清理和停止证明；不由 Java 执行命令代办任务 |
| `SdkController → VscodeSessionHost → ClineCore` | 保留产品会话创建、发送、恢复 | API Session 与 native task/session、worker、epoch 映射持久化；按 worker 串行投递；空 taskId/原生异常归失败 |
| `SdkInteractionCoordinator` / `TaskService.askResponse` | 宿主边界需要审批关联出口/校验补丁 | 发布 toolCallId+输入摘要；permissionId 与 session/task/epoch/expiry 绑定，回答前核对当前 resolver。新 epoch 或取消拒绝旧审批，不能仅靠 API 内存中“当前按钮”关联 |
| `SdkSessionEventCoordinator` / CoreSessionEvent | 增加只读原生事件出口，避免只订阅 UI 状态 | 在 UI 转换前复制事件到 worker journal；不修改原生事件内容、工具时序或循环；脱敏、序列化、去重、usage unknown 与终态因果均由服务外层处理 |
| `McpHub → McpHubToolProvider/createMcpTools` | 优先产品原生链 | Runtime 授权代理作为 MCP Server；独立凭据/配置；动态变更时测试工具 Schema 重建和断线拒绝，不重复注册两套 MCP |
| Core Skills/config/prepareTurn/session persistence | 原样复用 | 私有 HOME/CLINE_DIR、受控 Skill 发布与引用、脚本执行环境；原生压缩/恢复不得由 Java 替代 |
| Runtime gateway / budget / Operation Journal / fencing | 保留企业治理 | 以不可变任务合同、授权数据、额度、远端操作幂等和停止对账控制边界；不参与工具选择顺序 |
| 新 HTTP/OpenAPI/SSE/session registry | 新增 TypeScript 服务外层 | 认证、schema、异步接收、Idempotency-Key、状态与事件持久化、限流、隔离调度与资源回收 |

## 预计源码修改边界

优先通过独立 `cline-agent-server/src/host`、`api`、`sessions`、`events`、`permissions` 实现新服务。不可避免的上游差异只落在宿主入口日志、审批相关性、原生事件观测边界，保存逐文件 patch 和必要性说明。禁止修改 `AgentRuntime` 的模型调用/工具决策、Editor/Patch 解析/应用、Skills 调度、压缩算法。若这些边界无法保留且 POC 不通过，回到架构评审。

## SDK 迁移候选与保留理由

| 当前 Runtime 组件 | 候选方向 | 何时才能迁移 / 必须保留什么 |
|---|---|---|
| `coding-agent-node/src/runner.js` | HTTP client/task adapter，取消低层工具重复装配 | Server 真实验收通过并有配置开关后；本轮保留全部路径 |
| `sdk-adapter.js` | 移除服务侧重复 Agent/编辑/压缩拼装候选 | 原生上下文、用量、代码引用和错误语义证明等价后；治理证据不删 |
| `native-skills.js` | 原生发现/加载迁入 worker | 保留授权 Skill 包发布、realpath/manifest 与撤权边界；不把租户材料放入共享 HOME |
| `code-repair.js` | 自定义 repair.edits/hash 协议撤销候选 | 原生 Editor/Patch 真实修复可用后；代码不可变版本、Hash、审计归档仍保留 |
| Java DurableCodingTask / budget / journal / fencing | 保留，转 HTTP/SSE 对接 | 任务级授权、累计预算、租约、operation、远端停止证明不能由 native session snapshot 替代 |
| PythonSandbox / Silo / delivery validation | 保留，经原生 MCP 接入 | 继续做外部执行隔离与产物验收；不得人工补代码/报告将 Server 任务记为成功 |

本轮迁移量为零。没有删除旧 SDK、切换默认 Agent、部署镜像或写入生产配置。
