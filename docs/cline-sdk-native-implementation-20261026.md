# Cline SDK 原生能力整改实施记录

本记录对应分支 `feature/mcp-template-centric-permissions` 上基于 `e0271b164` 开发的 Runtime/Cline 整改；管理员安全修复已单独提交为 `82616b543`。SDK 保持 `0.0.93`，未修改 SDK 内核。Java 继续持有任务、权限、数据、预算和最终验收；Node 复用原生 Agent；Python 继续在已有隔离 Sandbox 内执行。

## 文件级修改与已落地行为

| 文件 | 实际修改 |
| --- | --- |
| `coding-agent-node/src/sdk-adapter.js` | 新增薄适配：调用公开 `createEditorExecutor` 编辑私有临时 `program.py`；调用公开 `createContextCompactionPrepareTurn` 的 basic 策略，并转换 SDK 两层消息格式。模型不能传入宿主路径，临时目录逐次隔离、finally 清理。 |
| `coding-agent-node/src/runner.js` | 绑定原生 sessionId/conversationId，使用 prepareTurn；在每次实际 stream attempt 检查 Deadline/调用数；移出原来依赖 beforeModel 的准入假设；使用 SDK afterTool.durationMs；保存 SDK 消息检查点并 restore/continue；提供最近验证代码 Hash；兼容读取旧占位检查点。 |
| `coding-agent-node/src/runtime-model.js` | 无损映射 STOP/TOOL_EXECUTION/LENGTH/CONTENT_FILTER/OTHER；将经过 Java 授权的原始无效工具 JSON 交给 SDK 自身产生工具反馈；原生模式不使用私有响应修复调度。 |
| `coding-agent-node/src/server.js` | 增加外部功能开关 `CLINE_NATIVE_SDK_ENABLED`；恢复时遵守检查点原有模式；签名模型回调支持 Java 管理整个 attempt。 |
| `coding-agent-node/src/code-repair.js` | 删除自研会话压缩和观察上下文存储；原生模式采用 SDK 编辑结果。保留任务绑定、父版本/hash、最近有效代码及已有回退保护，兼容模式才使用原精确编辑算法。 |
| `chatchat-api/.../cline/ClineRuntimeModelBridge.java` | 保留模型连接治理、工具授权、大小和 JSON 校验；将获授权的原始工具参数附在私有错误回执中，供 SDK 原生诊断；不将未授权工具交给 SDK 执行。 |
| `chatchat-api/.../cline/ClineModelCallbackController.java` | `managedAttempt` 在一次签名请求内复用原 reserve/start/receipt/settle 事务；未知执行仍不重放；模型调用前的 Node 观察事件标记为 admissionPending，不冒充已登记执行。 |
| `chatchat-common/.../coding/ExecutionFeedback.java` | 补回旧 v1 构造函数，缺失 CPU/RSS 时保留 null，兼容独立部署的旧调用方。 |
| `coding-agent-node/package.json`、`package-lock.json` | 删除 `@cline/llms`、`@cline/shared` 的冗余直接依赖声明。它们仍是 SDK 必需的传递依赖，未声称从发行镜像移除了这些模块。 |
| `deploy/docker/application/compose.yaml` | 外置 `CODING_NATIVE_SDK_ENABLED`，默认 false，验证后启用；沿用外置回调 URL、主机白名单、认证文件。 |
| `scripts/prepare-coding-sdk-release.py` | 从冻结的原运行包组装候选，覆盖明确的类族与反馈 Schema；保留部署中未在本分支出现的类、资源、配置和外层版本；检查源文件校验和、未修改条目以及可选 javap 二进制兼容性。只准备镜像，不切换流量。 |

测试同步更新：`sdk-migration.test.js`、`code-lifecycle.test.js`、`runner.test.js`、`CodingNodeHttpFixture.java`、`CodingAnalysisDockerGateTest.java`、`ClineRuntimeModelBridgeTest.java`、`ExecutionFeedbackContractTest.java`、`test_coding_sdk_release.py`。保留了旧错误回退拦截、原始账本拷贝拒绝、独立计算结果、输出文件验收、权限和取消断言。

此前工作区中的 Deadline、模型单次调用、Python 归档锁、资源诊断和回调部署整改一并进入相关验证；其文件清单与设计见 `runtime-coding-stability-20261026.md`。本轮没有把既有未提交修改重置或覆盖为仓库旧版本。

## 原生复用与删除范围

- 主循环、工具调用、失败反馈后再次推理、完成工具生命周期：直接使用 `Agent.run/continue`。未发现并虚构删除一个“自研主 Agent Loop”。
- 上下文：使用公开 `prepareTurn` 和 SDK basic compaction；不调用摘要模型，不另建会话压缩器。
- 编辑：使用公开 `createEditorExecutor`，由平台校验父 hash/版本并绑定新脚本执行证据。通用匹配与编辑由 SDK 完成。
- 检查点：使用原生 `snapshot/restore/continue` 的消息状态，Java 已有 Journal 负责持久化、CAS、租约和预算。未假称启用了完整 `ClineCore` 的 SessionHost/SQLite 或 Git 工作区检查点；后者不是逐 Python 工具调用的已验证版本记录。
- 监控：工具耗时取 SDK afterTool.durationMs；模型 usage 来自模型回执和 SDK usage 事件；Java 账本保留最终计费与未知用量语义。
- 原生截断/无效工具参数恢复服从实际 attempt 准入。未知传输结果仍禁用自动重发，保留原 reservation 等待 Java 对账。

实际删除：`CodeHistory.messages/observe`、initialPrompt/observations/lastReceipt/rejections 的重复写入、占位检查点新写入逻辑、工具自研计时包装、裸 AgentTool 不生效的 timeout/retry 元数据，以及两个冗余直接依赖声明。

仍保留的代码有实际引用：兼容开关后的原模型响应协议、精确替换算法；读取旧检查点的迁移分支；代码父版本和有效 hash；Java 权限/数据/预算/结果校验。它们不是无引用遗留代码，本轮未为减少行数删除。

## 验证范围

本地构建及测试日志在 `.codex-tmp/sdk-native-*.log`、`.codex-tmp/sdk-migration-tests-final.log`。服务器证据根目录为 `/opt/chatchat-releases/cline-sdk-native-20261026/`，原始业务数据、认证、源码、容器完整环境均保留在私有目录，不复制到公共交付文档。

| 检查 | 范围与状态 |
| --- | --- |
| Node 编译与回归 | 提交前重新运行 TypeScript 构建和 78 项测试，全部通过，包含原生错误反馈、真实 SDK 压缩、代码回退保护、预算、取消、监控、工作区编辑、认证与隔离。 |
| Java 相关回归 | 提交前按 Coding/Cline、模型适配、执行反馈和事件日志等测试模式重新编译并运行：448 项发现、428 通过、20 条件跳过、0 失败。早期更宽范围回归和服务器测试是独立历史运行，不与本次数量累加，也不是全仓库所有测试。 |
| Python/部署辅助 | 13 项 coding 辅助测试及 5 项 Linux 诊断测试通过，覆盖回调失败阻止切换、防火墙更新及保留部署扩展类。 |
| 服务器 Java/HTTP/SDK/Docker | 37 项发现，36 通过，1 项 SQLGlot 条件跳过。使用真实 Java 持久化、签名 HTTP、Node SDK、Docker Python 与结果校验；模型与外部模板数据源是固定测试响应。 |
| 兼容开关对照 | 相同账本计算和技术故障，兼容模式、原生模式均通过独立结果 `assetsTotal=364.10` 与版本链断言。4 次模型、2 次 Python、160 Token 保持一致，模型相关 HTTP 从 12 次降至 4 次。不能当成外部模型端到端提速。 |
| 联机业务 | 使用历史失败任务 `78a20370-abea-4db1-becd-d837f8afe438` 的原问题与原 Agent，经正式认证 API 重新提交；真实注册模型、授权模板和数据供给、真实 Sandbox。基线成功；后续兼容部署样本因预算耗尽失败，详见下文，尚未通过性能门禁。 |

故障注入覆盖：Python 技术错误后同会话修复、多轮修复及回退拦截、exit 0 无必需产物、模型截断与预算耗尽、取消、并发隔离、Worker/Java 重启和未决操作不重放、旧 Java 构造函数及部署中扩展类兼容。受控故障注入的模型为固定响应；没有将其描述成真实供应商故障注入。

## 部署中的实际问题与修正

部署前发现现有 HostConfig 使用 Mounts、Binds 可能为 null；自动分配子网不允许指定静态地址；共享网络命名空间代理不能带 hostname/exposed ports。适配后重走门禁，失败尝试保留回滚记录。

首个完整模块替换候选通过签名回调，但真实业务在数据供给前因部署中旧组件引用 `McpDataSupplyPolicy` 失败。已回滚原服务。修正为类族覆盖，并补回 ExecutionFeedback 旧构造函数；实际检查保留该部署扩展类的字节，javap 检查公开方法/构造函数兼容，再次部署和业务复测。

验证服务器依次部署兼容模式和原生模式，两次验证签名回调成功、无认证回调 401；使用 Docker DNS 的回调授权/代理、当前容器身份生成防火墙规则；同时检查 MCP gRPC 就绪。旧容器与冻结运行包保留用于回滚。正式生产流量未被本次验证宣称已切换。

## 性能基线与验收边界

历史约 30 分钟的问题包含未返回模型请求、输出缺失和 Deadline 终止，不能当作成功耗时样本。本轮同问题、同 Agent 的新基线任务 `181032b4-011d-33af-85ad-4e519edef56a` 成功：558,923 ms；Java 模型 4 次/205,472 ms，Coding 模型 3 次/296,605 ms，模板批次 17,157 ms，Python 1 次/进程 1,469 ms。

兼容部署后的真实任务 `934b91f1-057f-341e-aada-7217cdfe99b0` 最终失败，Coding 状态 `BUDGET_EXCEEDED`，采集器耗时约 1,394.259 秒。三次 Python 已产生可用结果且 Artifact 读取成功，但模型继续改写，后续还触发了回退保护。不能将该失败样本描述为成功或性能提升。

针对该样本，当前源码改为复用 SDK 的完成工具生命周期：reviewed 原生模式下，只有最后一次已验证执行对应的完整 Artifact 才能结束循环；陈旧结果或未验证 Python 回执不得结束。Java 提供的业务计划不再要求 Cline 重做业务规划，减少完成产物后的自行重写。对应新增回归已纳入本地 78 项 Node 测试和上述 Java 回归；此最终调整尚未部署并重新完成真实业务验收。

失败、取消和未完成不计为成功提速。两组是同一业务请求重新获取授权实时快照，不是同一不可变数据快照；目前每组一个样本，不提供稳定成功率或统计性 P95 保证。Token 是已观察用量，未采集的供应商费用/历史 CPU 与内存保持未知。

## 本次提交前核验

日志：`.codex-tmp/remaining-node-tests.log`、`remaining-java-tests.log`、`remaining-python-coding-tests.log`、`remaining-python-diagnostic-tests.log`。13 项 Coding 辅助和 5 项 Python 诊断测试全部通过。Windows 本机 Docker 守护进程未启动，4 项 Compose 配置检查通过后，3 项容器检查因环境不可用而报错；未删除或跳过这些断言，而将同一套配置及测试放入服务器私有隔离目录重新运行，7 项全部通过。该运行未更新线上服务。服务器日志：`/opt/chatchat-releases/enterprise-security-82616b543/remaining-validation/validation.log`。

核验中修正了 `CLINE_NATIVE_SDK_ENABLED` 环境项的 YAML 缩进；排除了 Maven 重建产生的 MCP 静态页面改动。旧 `ToolExecutionTimeScope` 及其对应测试已无源码引用，随实际适配替换删除。前面两个历史阶段的文档和证据仍保留原采集范围，不能将其旧样本计数解释为当前完整联机验收。

剩余边界：SQLGlot 可选测试环境尚未配置；受控故障模型不等于真实供应商故障；字面回退保护不证明任意 Python 的语义正确；跨进程恢复只重用完全结算的检查点，未决模型请求不能被当成已停止或安全重放；`ClineCore` 持久 SessionHost 未整体迁移。最终真实任务和性能门禁未通过前，不能宣称全部联机验收完成。
