# 模型请求超时审查（实施前）

基线：338f2f935。保留此前工作台页面修改；本次仅处理模型请求超时。

## 当前调用链

模型页面 `ModelManagementView` → `/api/v1/platform/models` → `PlatformModelCatalogService` → JDBC 草稿表 `platform_model_config` → 发布表 `platform_model_publication` → `ModelResourceRegistry` → `ConfigurableChatModelFactory` → LangChain4j OpenAI 同步/流式客户端或 DashScope JDK HTTP。

Runtime OS：`AgentOrchestrationEngine` → `AgentChatModelResolver` → 容量控制、任务截止时间及计量包装 → InterpretationPlan、工具结果审查、模板选择、分析图、答案总结/审查；这些功能传递同一个已选模型，不能从默认模型另取超时。

Coding：`CodingAnalysisService` → `CodingParentModels` 绑定已授权模型 → `ClineCodingAdapter` 提交耐久任务 → Node `runner` → Cline SDK 的既有 `AgentModel.stream` 扩展点 → `runtime-model.js` → `httpGateway` → Java `ClineModelCallbackController` → `ClineRuntimeModelBridge` → 绑定模型/模型工厂 → 真实供应商请求。Node 不直接保存供应商配置，也不直接创建供应商客户端。

向量模型：同一目录发布 → API 的 SearchProperties 及 MCP 模型同步 → embedding HTTP 请求。需要从统一秒数转换为既有传输层的毫秒。

## 实际使用和覆盖清单

| 配置/位置 | 现状与语义 | 处理方向 |
| --- | --- | --- |
| 页面/目录 `timeout` | 页面默认 30000；数据库整数，无单位；工厂 `<1000` 按秒、其他按毫秒 | 新增每实例 `requestTimeoutSeconds`，缺失为 300，不沿用旧值推测单位 |
| `ModelsConfig.ModelConnectionConfig.timeout` | 默认 30；YAML 常有 1800000/300000/300；工厂实际使用 | 运行入口统一使用新字段，旧字段仅兼容读取，不能覆盖新默认 |
| 模型目录草稿/发布 | 数据库直接查询，无目录缓存；发布前不生效 | 兼容迁移两张既有表，保留发布流程 |
| `AgentChatModelResolver` | 发布配置按内容缓存；文件配置长期缓存；工具调用构建有界客户端 | 超时进入缓存身份；物理调用重新解析当前实例，避免跨模型/更新后旧值 |
| `LangChain4jConfig` | 默认 Chat 客户端按连接签名缓存 | 新超时进入签名 |
| `ConfigurableChatModelFactory` | SDK `.timeout`、DashScope `HttpRequest.timeout`；可被 caller maximumTimeout 压低 | 新字段唯一来源；仅任务/工具的实际剩余生命周期允许压低 |
| `AgentOrchestrationEngine` 工具结果审查 | `ModelInvocationBudget.open` 默认 60000，可提前终止模型 | 移除独立模型阶段上限 |
| `AnswerReviewCoordinator` | 固定默认模型快照及系统属性 `chatchat.agent.answer.review.timeout.ms` | 移除独立模型等待上限，由所选客户端及任务截止时间约束 |
| `AgentAnswerFinalizer` | 从启动默认模型读取超时；网页增强另有 45000 阶段包装 | 移除默认模型超时快照；网页工具本身超时保留，模型部分遵守自身配置 |
| `ClineRuntimeModelBridge.max-model-call-millis` | 独立可选单轮上限，默认 0 | 不再参与模型请求截止时间 |
| Node `httpGateway` | 普通回调默认 90000，模型回调已使用任务剩余时间；使用原生 HTTP 避免 fetch headers 固定超时 | 保留普通回调超时；模型等待不得早于 Java 请求，仍受取消/任务截止时间约束 |
| Java→Node | 建连 3 秒；提交/控制请求 5 秒，异步模型不在该响应内执行 | 保留，不是模型请求超时 |
| Cline 租约 | 5 分钟租约、60 秒续租 | 保留，不是模型请求时限 |
| Runtime 流式客户端 | SDK timeout，但桥接 `result.get()` 无总请求上限 | 新字段传入 SDK，并对流全程限时、取消订阅 |
| 网关 | public gateway 1800 秒，node proxy 300 秒读取闲置上限 | 按路由确认模型回调是否经过代理；不将非模型路由全部删除超时 |
| query refinement | retrieval 总预算外另有 `refinementTimeoutMs=3000`，并传到模型工厂 | 移除独立的 3 秒模型上限；仅保留检索完整生命周期剩余预算 |

## 保留的独立机制

Agent 父任务 deadline、Coding 工具预算及 Python 执行截止时间、MCP 工具执行预算、容量排队、数据库、连接建立、健康检查、控制面通信、租约/心跳。最终请求等待不得超过其所属任务剩余时间。重试分别受当前模型单次请求超时约束，且不能超过任务总截止时间；不能通过放大任务预算掩盖请求配置失效。

## 实施与验证边界

复用目录及发布机制，不引入配置中心或事件总线。新增秒字段在草稿和发布中各保存其现有版本语义，不在业务模块另存配置。旧缺失字段回填 300；超时修改发布后新请求使用新值，进行中的物理请求保留已开始时的设置。验证包含 SDK/HTTP 实际接收参数、延迟/持续流服务器真实中断、模型切换、发布更新、Cline SDK 扩展点、多轮调用及任务先到期。第三方依赖不修改源码。
