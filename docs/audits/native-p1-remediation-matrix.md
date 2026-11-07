# P1 整改范围冻结（2026-11-07）

基线：P0 `d5dd48356`，既有审查 `86102fdfb`。本次逐层复核后继续实施；历史审查文件保留原样，不把历史证据写成本轮验收。SDK 固定 0.0.93，官方源码固定 `1a5c3f84e0933268a9dba14a55a6383a6578414d`。官方路径相对 `sdk/packages/`。

| 编号/优先级 | 原生入口 | Runtime 入口与根因 | 最小整改/影响范围 | 可执行验收 | 回滚 |
|---|---|---|---|---|---|
| M-01/P1 | core/src/extensions/mcp/tools.ts:createMcpTools | NativeCodingTaskService.submit 无目录；NativeCodingWorkspaceService.open 无清单；server.httpGateway 与 ClineModelCallbackController 缺 provider 回调 | 复用 McpServiceDirectory、ResourceAuthorizationPort 和 Java 既有连接生命周期；SDK 自行生成工具和处理参数，Java 只冻结身份、Schema、授权与回执；Java/Node | 已授权多工具发现、原参数/原结果、真实服务错误、越权/撤权拒绝、恢复不重复执行 | 逐项撤回 MCP 专项提交；不修改旧合同 |
| M-02/P1 | core/src/runtime/orchestration/runtime-builder.ts:loadConfiguredMcpTools | native-runner.prepare 串行准备，单服务失败会中断全部；未真实复现 | 服务隔离，但安全和完整性失败保持拒绝；Node/Java | 一好一坏服务；恢复后同合同重新发现；安全拒绝不降级放行 | 随 MCP 提交回滚 |
| M-03/P1 | core/src/extensions/mcp/tools.ts | NativeCodingWorkspaceService.authorize 仅允许 NativeCodeWorkspace.TOOLS | 仅对合同冻结目录的工具扩展准入；逐次授权，Schema 漂移拒绝；新增授权在新合同生效，撤权立即生效；Java/Node | Schema 漂移、名字冲突、跨租户、恢复旧合同不能扩大授权 | 随 MCP 提交回滚 |
| M-04/P1 | SDK provider 不涉及 Spring 装配 | 首次真实升级 API 启动失败：NativeCodingMcpService 构造器注入 McpServiceDirectory，同时存在传输端口、本地目录、Kernel 三个实现；原手工夹具未覆盖 | 显式使用既有 `@Qualifier("mcpRuntimeTransportPort") McpRuntimeTransportPort`；不创建新连接权威；Java | 三目录并存的 Spring 装配测试；真实 API 认证读请求与 Worker 同时就绪，不能只检查 Worker | 保留首次失败容器/日志；从原 P0 Jar 构建修正覆盖包 |
| M-05/P1 | SDK 不涉及 Runtime 输入证据 Hash | 真实提交 403：`NativeCodingTaskService.register` 对包含 Binding record 的 payload 计算 Hash，存储重读成为 Map 后排序改变，`requireSnapshot` 拒绝；同样影响 SkillBinding | 仅新增原生输入在注册前转为 JSON Map/List 值；保持原 Hash 策略和历史失败分区不变；Java | 有/无 MCP 两种完整准入、真实 H2 证据重读、同请求重试、原预算不变；再跑真实提交 | 撤回该专项修复；不改写既有失败证据 |
| S-01/P1 | core/src/extensions/agent-plugin/loader.ts；config/user-instruction-service.ts | CodingSkillReader.freezeCatalog/read 与 native-skills.prepareNativeSkills 提前取包；并非全量正文注入模型 | 先记录交付字节、模型实际加载和资源读取，保留 SDK 自主选择；不以删除完整性检查换性能；Java/Node | 真实单包/多包/不匹配包；记录预取与披露，异常/撤权/大目录 | 撤回 Skills 专项提交 |
| S-02/P1 | core/src/extensions/config/user-instruction-plugin.ts 返回 root | NativeCodingCommandService.execute 只上传项目；NativeProjectSnapshot.materialize 仅展开项目 | 用既有不可变输入传输承载包、Hash 和相对目录，在原 Sandbox 提供只读路径；不增加 Skill 执行器；Java/Node/MCP | 真实包引用资源、原包脚本及参数/相对依赖、脚本失败、拒绝越权 | 旧无 Skills 合同走原路径；撤回专项提交 |
| S-03/P1 | core/src/extensions/agent-plugin/loader.ts diagnostics | native-skills.prepareNativeSkills 对完整性/可用性异常都中断；风险待复现 | 先做真实异常样本，再决定最小反馈适配；不忽略包 Hash 或授权错误 | 一个包暂不可用、脚本错误、其他包可用性与错误反馈 | 仅回滚实证缺陷对应提交 |
| R-01/P1 | agents/src/agent-runtime.ts:restore/continue | ClineCodingAdapter.resume、PythonManagedExecutionService.reconcile 已存在；缺跨服务完整交付实证 | 先故障注入；只修可复现协调缺陷，未知副作用不得自动重发或伪造停止；Java/Node/MCP | Worker/Python/MCP/Java/传输/Silo/连续故障；原 task/session/预算；源码、报告、结果、归档重读 Hash | 独立恢复修复提交；不删除原日志/资产 |
| A-01/P1 | 官方同版本原生客户端 | 现有 P0 的 SDK 参考不等同官方客户端完整对照 | T01–T08 同模型/任务/预算/输入与外部条件，记录工具可达性差异及实际 Token/耗时；无业务集成 | 两组原始日志、独立交付校验、失败逐层归因；缺证据记未通过 | 测试控制器独立；停止并核对全部操作后恢复服务 |

冻结边界：保留原 Agent Loop、原 Editor/Patch/搜索、SDK Skills discovery/loader、压缩与恢复；不升级 SDK，不改变共享模型或预算，不触及金融业务。P0 分支不动。MCP 连接权威只保留 Java 现有目录/网关；Node 不新增 MCP 客户端。协议/Schema/目录新增在新任务生效；当前任务撤权立即拒绝。

本轮补充审查发现 M-03 还包括 `ClineCodingAdapter.submit` 的合同工具集合检查、`ClineRuntimeModelBridge.allowedTool` 的模型披露/响应检查，以及 `DurableCodingTaskService` 的已停止原生子工具核对。只修改工作区准入不能打通链路。三处统一复用 common 层的冻结目录准入判断，并保持实际模型回执、合同权限和停止证明检查；这是同一适配缺口的补充位置，不扩大至新的 Agent 实现。

首轮 Java 验证还定位到 `CodingRuntimeContext.validate` 拒绝新增目录字段：需要显式校验 MCP 描述符、唯一身份及 Schema Hash，并仅允许原生任务合同携带该字段；不能删除通用上下文白名单。首轮失败日志保留，修复后重新验收。

本矩阵审查依据为既有报告及本轮实际源码阅读；确认断点与待复现实证风险分开。后续每专项独立提交/推送，测试记录与产品验收分开。最终验收当前为 **未通过**。
