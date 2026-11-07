# P1-01 MCP 第一阶段源码审查

**状态：源码审查完成；未实施修复、未执行 P1 集成或真实模型验收。** 基准及包指纹见 [总矩阵](native-p1-source-comparison.md)。官方固定提交 `1a5c3f84e0933268a9dba14a55a6383a6578414d`，以下上游路径相对 `sdk/packages/core/src/`。

## 官方调用链及实际边界

`runtime/orchestration/runtime-builder.ts:207 loadConfiguredMcpTools` → settings/plugin registration → `extensions/mcp/manager.ts:43 InMemoryMcpManager` → `client.ts:createDefaultMcpServerClientFactory` → `tools.ts:16 createMcpTools` → Agent 的 tool call → provider.callTool → 原结果回到 Agent。

manager 的 register/connect/disconnect/disable/listServers/refreshTools/callTool 分别见 55/99/106/113/128/153/168 行；按 serverName 串行操作锁见 263 行，各服务状态隔离。listTools 默认缓存 5000ms，refreshTools 获取新 descriptors。client 支持 stdio、SSE、streamable HTTP 和相应 timeout/OAuth 处理。**直接采用默认 stdio 会在 Node 启动进程，不适合照搬到受控 Worker。**

`createMcpTools` 复制当前 descriptor 的 description/inputSchema，采用 SDK name-transform，将模型输入对象及 context 原样交 provider。此函数不读取宿主配置、不自己连接服务器，也不授予权限。高层 builder:280 使用 allSettled，单个服务加载失败有诊断，其他成功工具仍保留。`extensions/mcp/index.ts` 与 core 根入口公开导出 provider 工具、manager/client factory；`loadConfiguredMcpTools` 是内部函数，不应从 npm 根假设可导入。

`refreshTools` 刷新服务缓存，**不等于现有 Agent 的执行器注册表自动热替换**。本版高层会话装配与低层 Agent、prepareTurn 的工具披露是不同层次；仅向模型请求增加 Schema 不足以注册执行器。不能把“最新版本可能支持”的行为混入 0.0.93。

## Runtime 逐层断点

| 层次 | 实际源码与调用 | 当前事实 | 结论 |
| --- | --- | --- | --- |
| 任务授权目录 | `chatchat-api/.../nativecode/NativeCodingTaskService.java:23,97–106 submit` | 请求有 files/skills，没有 MCP server/tool 绑定；授权仅固定原生工具及可选 skills | M-01a，缺受治理的 MCP 任务目录，不是模型没选工具 |
| Host open | `NativeCodingWorkspaceService.java:55–77 openLocked` | 返回当前树、模型、策略及执行/验证，不返回 mcpServers | M-01b，Node 正常不会进入 MCP 装配分支 |
| Node 装配 | `coding-agent-node/src/native-runner.js:80–87` | 有 createMcpTools 与 provider 委托入口 | 原 SDK 已复用，不能再写另一套参数/工具定义协议 |
| Node HTTP gateway | `coding-agent-node/src/server.js:141–171 httpGateway` | 有原 native/read-skill 回调，无 listCodeMcpTools/callCodeMcpTool | M-01c，补清单后仍会报 provider unavailable |
| Java 回调 | `chatchat-api/.../cline/ClineModelCallbackController.java:71–82,133` | 有 native/read-skill，无上述 native MCP 发现/调用回调 | M-01d，不能直接把任意远程 URL 暴露给模型补洞 |
| 工具执行准入 | `NativeCodingWorkspaceService.java:123` 与 `chatchat-common/.../NativeCodeWorkspace.java:13 TOOLS` | 即使临时加 gateway，SDK 命名 MCP 工具仍不在六工具固定集合 | M-03，需受冻结授权约束的合法扩展，不能删除整个白名单 |
| 既有 MCP 能力 | `chatchat-integration/.../transport/McpGatewayClient.java:133,143,189`；`chatchat-runtime-mcp/.../registry/McpToolInvocationService.java:11` | 已有通知、发现、调用，以及 registry 活性/Kernel bridge | 可复用，但不是已连接到 generic Coding Agent 的证据；真实权限/路由连接仍待接线和测试 |
| 多服务初始化 | native-runner:80 串行 await，对比官方 builder:280 allSettled | 任一 provider 列举错误可中止整个 prepare | M-02，候选分支静态差异；当前生产链未通，不能宣称已复现 MCP 故障拖垮真实任务 |

当前不存在完整生产发现→披露→自主调用链的源码条件，因此不以 SDK helper 的存在、registry 构件测试或已有业务 MCP 调用宣布 P1-01 通过。

## 审查后最小修复建议

1. 复用当前用户/租户/Agent 的已发布服务目录及网关，形成任务 server/tool/Schema/版本授权快照。服务列表是可达资源范围，不能由 Java 替模型选“本题应该调用哪个工具”。
2. SDK createMcpTools 继续负责名字、description、Schema、参数和原结果装配；只补 provider 的治理回调与 Java 执行准入。逐次校验当前权限、原始模型参数与冻结 Schema，禁止将模型给定 URL/凭据当授权。
3. 连接生命周期只选择一个权威。本仓库已有 Java MCP 网关，应优先验证其适用性；如需 SDK manager，先证明已有传输确实无法满足，不能两边同时维护重连/会话。不要新建工具选择代理或第二个 MCP client 协议栈。
4. 对授权撤销立即拒绝新执行；对目录/Schema 更新明确冻结到当前会话还是下一会话。不同版本不能悄悄替换已开始工具的参数或回执；失败服务与其他服务隔离，不吞掉安全/完整性错误。
5. 保留无盲重试策略。当前 wrapper 的 retryable=false/maxRetries=0 不应简单当缺陷删除；外部副作用必须有独立 operation 身份和停止/结果回执才可回放，未知结果不能靠再次调用“恢复”。复用现有 task budget/fence/journal/归档，另列一般 MCP 结果无法确认的情形。

## 待执行的验证矩阵

| 场景 | 要求及独立证据 | 当前状态 |
| --- | --- | --- |
| 两个已授权服务发现 | 原服务身份、授权范围、描述/Schema Hash、实际模型披露 | 未测试 |
| 模型自主选择及参数 | 同一原 Agent 从两个服务选择；原 tool call/input Hash 与被执行参数对应，无 Java 参数代填 | 未测试 |
| 结果/错误 | 真实服务器数据及错误进入同会话；错误可促使模型改参数/选其他工具，不能使用 Mock 冒充 | 未测试 |
| 一个服务中断 | 另一服务仍可发现/调用；中断服务状态/恢复及预算开销可解释 | 未测试 |
| 断连/短暂网络中断 | 重建连接与重做副作用区分；未知结果不重发，原回执可回放 | 未测试 |
| Schema/服务动态变化 | 按评审确定的会话版本规则发现或明确推迟；不能披露新旧混合 Schema | 未测试 |
| 撤权/跨用户/跨租户/过期 | 发现及每次执行均拒绝越权；恢复后不复活旧授权 | 未测试 |
| Worker/API/MCP 恢复 | 原 task/session/预算/执行身份及产物 Hash；无重复副作用、无虚假成功 | 未测试 |

无生产修改，当前无需运行回滚。后续 MCP 专项独立提交且默认不影响 P0 无 MCP 合同；先完成审查再开始补丁及真实实验。
