# 官方 Cline / HTTP Server 同任务验收计划

日期：2026-11-08。以下保留原验收设计。**后续已执行官方未修改 Standalone 与 HTTP Server 同任务 POC，实际结果及限制见 [真实验收报告](cline-server-runtime-tests.md)**；原计划中的完整 VS Code Desktop A/B 尚未完成，不能将二者混同。上游固定 `v4.1.26 / a459f1d04dea030833b558be645011ab739c463b`。关联：[源码审查](../audits/cline-v4.1.26-server-architecture-review.md)、[POC 架构](../design/cline-agent-server-architecture.md)。

## 核心门槛

同一个真实编码任务，官方 Cline v4.1.26 能完成的核心动作，Server 必须仅通过 HTTP 客户端完成；不能人工修改生成代码、从本地控制台帮 Server 执行命令、调用 Java 自定义执行器或伪造成功报告。原生工具可以自主选择和排序，不要求工具序列或耗时机械相同。

比较组 A 是正式 VSIX v4.1.26 在真实 VS Code 宿主运行，组 B 是从固定 Tag 构建的 Standalone + Server HostBridge 经 HTTP 运行；现有 SDK 0.0.93 是独立参考组 C，不能代替 A。记录每组 native/adapter/model/host 版本，不能把不同版本的效果差异全归因于宿主。

每组从独立、相同的真实仓库快照开始，固定起始 commit、依赖 lock、问题描述、验收测试、输入/Skills/MCP 内容 Hash、模型/provider/参数/预算/截止、权限、网络、语言工具链和 OS。宿主不可等同之处单列，不隐藏模型参数或环境差异。先确认 A 的基线确实可完成；A 失败的用例标 inconclusive，不能据此免除已观察到的 B 缺口。

## 真实任务准备与执行

最小 POC 选择一个真实项目内的多文件 bug/功能变更，至少跨实现、调用方与测试文件；冻结任务材料后再开始任何一组。优先使用本仓库独立 scratch checkout 中的非生产前端/工具模块，任务不得要求改造 Agent 内核或新增 Java 执行器。任务 ID、目标模块、基线 commit、完整 prompt 和独立验收命令必须填入 run manifest；尚未选择或没有真实模型日志时维持 NOT_RUN，不能用临时 toy server/demo 充当对等验证。

任务描述只指定目标行为和验收标准，不规定读哪些文件、先调什么 Skill 或如何拆补丁。应包含必须搜索定位的调用关系、至少两个实现文件变更与测试、可观测的失败测试和修复后构建验证。原生 patch 路由受模型影响：先观测 A 实际使用的工具；若共同任务没有触发某关键工具，增加双方相同的多文件/大补丁专项，不能把未触发记为 PASS。

HTTP 组的验收驱动顺序如下（不干预 Agent 内部工具顺序）：

1. `POST /v1/sessions`，绑定固定 workspaceRef/modelProfile/policy，保存请求与 201 回执。
2. 连接 `/events`；`POST /messages` 提交冻结 prompt，保存 Idempotency-Key、202、taskId。重试同 key 验证只创建一次命令；异体同 key 验证 409。
3. 等待真实原生模型及工具事件。出现 permission.requested 时仅经 `/permissions/{id}/respond` 按固定策略批准/拒绝；追问通过携带 interactionId 的 messages 回答。
4. SSE 断开后重连 Last-Event-ID，验证 Agent 继续、事件顺序/去重/回放、任务状态与 journal 一致。
5. 查询 status/messages/diff/artifacts；在独立验证进程检验实际变更和测试，不帮 Agent 修文件或补跑其漏做的核心动作。独立验收可以检查结果，但不能冒充 Agent 自主构建测试。
6. 复制同材料运行取消、拒绝/恢复与隔离变体。所有请求、事件、工具输出、退出码、变更与产物 Hash 留档；不保存密钥或私有模型推理内容。

## 动作级验收矩阵

以下各项初始均为 NOT_RUN。每项记录 A/B 状态（PASS/FAIL/NOT_EXERCISED/INCONCLUSIVE）、native call/event、输入摘要 Hash、可见副作用、独立验证结果、日志路径与耗时。

| 核心动作 | 验收证据 | 失败时优先定位的宿主能力 |
|---|---|---|
| 自主读文件/搜索 | 原生工具读取真实实现/调用方；搜索命中与文件位置正确 | workspace root、路径解析、ignore、rg 分发/权限、文件可见范围 |
| 多文件编辑 | 至少两个真实实现文件及测试变更，实际落盘 Hash，拒绝的变更不发生 | 原生 executor capabilities、编辑预览展示依赖、文件系统权限 |
| 原生 Patch/大型补丁 | 对双方相同专项产生可应用 patch；真实变更、错误与冲突语义 | 模型路由、patch executor 装配、资源/工作目录；不得用 Java 自己解析 Patch |
| Shell/构建/测试 | Agent 自主执行真实项目命令，stdout/stderr/exit code 与实际进程一致 | backgroundExec、shell/工具链、cwd/env、输出与 signal；不得以 stub sendText 通过 |
| 错误诊断与二次修复 | 冻结失败测试/编译错误，经原生反馈再改代码并重测成功 | 结果回传、上下文/诊断、模型工具可见性；不注入固定 repair.edits 流程 |
| 审批与追问 | HTTP 批准后才执行；拒绝无副作用；正确 toolCallId/epoch；追问答案进入正确原生等待点 | 宿主 callback、ID 关联、resolver 生命周期、到期与串行投递 |
| 继续任务 | HTTP 第二轮在同 session 读取已完成变更，历史正确 | Session/native ID 映射、产品 init/clear/send 语义 |
| 用户取消 | 在模型、审批、长命令阶段分别取消；无后续新工具，进程/远端操作停止证据 | native abort、worker 进程树、Sandbox stop/fencing；容器退出不能单独证明停止 |
| Skills/MCP | 在需此能力的真实任务中模型自主发现/选择/加载与调用 | 私有 HOME/目录、MCP 配置/授权/连接、活跃 Schema 更新；不可人工固定工具调用顺序 |
| 压缩/恢复 | 跨原生压缩与重启后保持历史、文件引用、累计预算和任务归属 | prepareTurn/state dir、checkpoint、事件与 Operation Journal 对账 |
| Diff/产物 | HTTP 返回与实际文件一致；实际产物可读取且 Hash/引用授权正确 | 工作区基线、归档引用、租户数据许可、交付校验 |

## 必做故障与隔离变体

最小 POC 必测：审批 approve/deny/过期/重复/旧 epoch、取消期间迟到批准、SSE 中断与 API 订阅重连、两个 worker 并发的工作目录/历史/模型凭据/MCP/产物交叉访问拒绝。严禁用共享 session map 或 locks.db 当作租户隔离证明。

后续完整阶段追加：模型超时、API 崩溃、worker 崩溃、服务重启、MCP 不可用与动态配置、Python 失败、恢复后的重复远端 operation、预算耗尽/租约过期。每个故障记录触发点、预期状态、观测状态、停止/恢复时间和副作用对账；恢复后不能假设工具未执行，UNKNOWN 不能盲重放。

业务任务按任务书保留“分析华是科技最新的股票价格行情走势”和真实 Parquet 多数据集用例到后续业务集成阶段。测试日记录查询时点和数据快照；无数据、源故障、无权限、执行/模型失败、缺少报告产物分别判定，空数据不得伪装业务报告成功。本轮没有查询行情、提交业务任务或生成报告。

## 失败证据模板与扩围条件

```json
{
  "runId": "pending",
  "taskManifestHash": "pending",
  "action": "run_commands",
  "official": {"status": "NOT_RUN", "nativeRef": null},
  "server": {"status": "NOT_RUN", "httpRequestId": null, "nativeRef": null},
  "missingHostCapability": null,
  "upstreamCallsite": null,
  "observedErrorAndEvidence": [],
  "requiredMinimalHostAdaptation": null,
  "coreAlgorithmChangeRequired": null,
  "retestResult": "NOT_RUN"
}
```

模板占位符不是测试数据。失败必须定位具体原生调用点、HostBridge 方法/配置/资源及日志；无法确定根因时写 unknown，补采证据，不将所有差异归为 SDK。核心修改才能解决时回到 A/B 架构评审，禁止继续扩充 Java 自定义执行逻辑。

POC 最低通过条件：官方可完成的目标任务，B 经 HTTP 完成同等核心动作及独立验收；读/搜/改/Patch/Shell 有实际轨迹；审批、取消、SSE 与隔离变体通过。未触发的动作追加专项验证，任一必测项 FAIL/NOT_RUN/NOT_EXERCISED 都不能宣称整体 PASS。记录成功率、首个有效动作/代码耗时、总耗时、token/调用次数、自动修复与交付完整率，不从单任务声称长期性能优势。

本轮仅完成固定基线、静态调用链和上述验收设计。真实 POC、性能、恢复及能力等价结论均待下一阶段证据。
