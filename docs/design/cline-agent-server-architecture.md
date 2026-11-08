# Cline Agent Server 最小 POC 设计

日期：2026-11-08。状态：待评审设计；未实现服务。依据：[Phase 2.1 审查](../audits/cline-v4.1.26-server-architecture-review.md)、[宿主差异矩阵](../audits/cline-server-host-diff.md)。固定上游 `v4.1.26 / a459f1d04dea030833b558be645011ab739c463b`。

## 运行拓扑

```text
Java Runtime OS（身份、任务授权、预算、fencing、Operation Journal）
  → REST / SSE → TypeScript API + durable session/event/permission registry
    → 单机 worker supervisor（分配私有 workspace/HOME/配置/凭据）
      → Worker A：Server HostBridge ↔ 官方 Standalone Cline Core
      → Worker B：Server HostBridge ↔ 官方 Standalone Cline Core
                         ↓
           官方 SdkController / VscodeSessionHost / 同 Tag ClineCore
                         ↓
                 官方 AgentRuntime / 工具 / Skills / MCP
                         ↓
       授权模型网关 / Runtime MCP → Python Sandbox、Silo、OpenSearch
```

API 常驻；worker 在一次 session 生命周期中常驻，完成单个模型回合不退出。POC 先单 worker 证明真实任务，再用两个隔离 worker 测并发。一个 worker 只有一个活跃 native session，所有消息与审批投递串行；同一租户不同 session 也不共享未经验证的 global state。服务级 Session 可以休眠，恢复须重新取得 worker 和新 epoch。

Server HostBridge 是原生 host.proto 的真实实现。先选 worker 内部 loopback ProtoBus + 受控 OS/network namespace 作为 POC 传输，不对外发布 insecure gRPC；后续可沿用已存在的 token/CoreConnection 路径，其认证/断线行为须单独验证。HostBridge 健康与完整工作区/存储/模型配置就绪共同决定 ready。禁止直接用上游 mock test-hostbridge-server 作为服务宿主。

## API 草案

下表是拟议合同，不表示端点已存在。后续实现时输出 OpenAPI 3.1；不在本轮创建假服务或空壳成功响应。

| 方法 / 路径 | 最小请求 / 响应与原生映射 |
|---|---|
| `POST /v1/sessions` | 输入 runtimeTaskId、已授权 workspaceRef、modelProfileRef、policyRef；返回 201 `{sessionId,status,epoch}`。路径/凭据由合同解析，客户端不能传宿主任意绝对路径 |
| `GET /v1/sessions/{id}` | 200 服务 session、nativeSessionId（建立后）、worker 状态、epoch；每次做租户/任务授权 |
| `POST /v1/sessions/{id}/messages` | `{messageId,text,attachmentRefs}`；Idempotency-Key；202 `{taskId,messageId,status:"accepted"}`。先持久化接收命令，再异步 newTask/继续原生会话 |
| `GET /v1/sessions/{id}/events` | SSE，支持 Last-Event-ID，读 durable journal；断线只取消订阅 |
| `POST /v1/sessions/{id}/cancel` | 幂等取消，202 `{status:"cancelling"}`；转原生 cancelTask/abort 与外部执行停止对账，停止证据完整后才 cancelled |
| `GET /v1/sessions/{id}/status` | 200 当前任务/会话状态、错误类别、待交互项、最后 eventId、停止对账状态 |
| `GET /v1/sessions/{id}/messages` | 游标分页脱敏历史，关联 native message/turn；不泄露模型凭据 |
| `GET /v1/sessions/{id}/diff` | 200 实际工作区与固定起点的变更列表/patch、基线和当前 Hash；基于文件/Git结果，不重实现 patch 应用 |
| `GET /v1/sessions/{id}/artifacts` | 200 已校验的产物引用、内容 Hash、大小；没有产物返回空列表但不能因此声称交付成功 |
| `POST /v1/permissions/{id}/respond` | `{decision:"approve"或"deny",reason?}`；验证 principal/session/task/toolCallId/epoch/expiry/输入 Hash；返回解析结果与审计引用 |
| `GET /health/live` | API 进程存活，200；不代表 Agent 就绪 |
| `GET /health/ready` | worker 容量、存储、原生链、必要宿主/模型配置可用时 200，否则 503 及不含秘密的诊断 |
| `GET /openapi.json` | 版本化请求、响应、错误 schema；健康与文档可按部署策略开放，业务接口必须认证 |

交互补充：原生 ask_question 通过 SSE 发布独立 `interactionId`，包含问题/选项并绑定 epoch；`messages` 在 `awaiting_input` 状态提交答案时须携带该 ID。它与工具 permission 不共用 ID，避免把追问答案当成审批。busy 状态的普通 message 只按显式队列合同入队，不得打断或替代待批准操作。

身份来自 Runtime 服务凭证/委托令牌，并与服务持久化租户归属交叉核对。未知或越权 ID 使用不泄露对象存在性的错误。所有写接口支持幂等，key 按 principal+session+operation 作用域绑定 canonical request Hash；同 key 同体返回同命令，异体 409。错误统一 `{code,message,requestId,retryable,details}`，区分 400/401/403（任务权限）、404（对象）、409（状态/幂等冲突）、410（过期）、429、503 和原生执行失败；异步错误必须有 task.failed 及状态回执。

native newTask 返回空 taskId、存储初始化异常、不可用模型或 RPC INTERNAL 都不是 task.completed。202 仅代表持久化接收，不等待 LLM 完成。原生启动失败由相同 taskId 的失败事件报告，避免失败任务悬空。

## 任务、审批与恢复

服务分别保存 session、task/turn、command、native session、worker、epoch、operation 的映射。状态包含 accepted、starting、running、awaiting_permission、awaiting_input、cancelling、completed、failed、cancelled、recoverable；每次变更写 journal 并带递增 revision。原生回合结束先记录 turn.completed；只有目标任务交付检查通过才 task.completed，模型说“完成”或 native ended 不能替代验收。

审批流程：在官方 `requestToolApproval` 宿主边界拿到完整 toolCallId/input，生成服务 permissionId，先持久化 pending，再发布 permission.requested，原 resolver 保持等待。HTTP approve/deny 通过 CAS 原子消费与串行 worker 通道投递，原生侧再次核对 toolCallId+epoch+输入 Hash，匹配才 resolve。响应丢失可重试查询既有决议；已决议同响应返回原结果，冲突响应 409。过期/取消/旧 epoch 自动拒绝，不能在恢复后将旧批准重放给新工具。纯粹调用无 ID 的 `TaskService.askResponse` 不满足该合同，需宿主关联补丁。

禁止将客户端提供的 autoApprovalSettings 覆盖 Runtime policy；生产不采用全工具 auto-approve。权限拒绝回到官方工具反馈，是否重新规划由官方循环决定。

取消先写取消意图并 fencing，再转原生取消，拒绝 pending approvals/输入，阻止后续工具启动。supervisor 管理本地 shell/MCP 进程树，Runtime 对账远端 Sandbox 等 operation；未确认停止时保持 cancelling/stop-unconfirmed，不发 task.cancelled。API 或 SSE 客户端消失不等于用户取消。

重启时读 durable command 和事件日志，对账 native history/checkpoint、工作区 Hash、Runtime 租约和远端 operation。新 worker 使用新 epoch；UNKNOWN 外部副作用不能盲目重试。会话恢复调用官方 persistence/restore；幂等与预算权威仍在 Runtime，不重新实现 Agent Loop。

## 原生事件到 SSE

事件 envelope：`{eventId,sessionId,taskId,epoch,timestamp,eventType,payload,usage?,nativeRef?}`。每个 session 持久化严格递增序号；SSE id 即 eventId。nativeRef 保存可用的原生 event/toolCall/turn 标识，源无 ID 时在单 worker 采集边界赋序号。usage 缺失标为 unknown，不写虚假零。

| 服务事件 | 证据来源 / 映射条件 |
|---|---|
| session.created / task.started | session registry 提交 / 原生成功建立并开始发送的回执，分别记录 |
| model.started / model.completed | Core `agent_event` 中模型回合相关元数据及原生 hook；必须核对是否确为一次 provider 请求，不能将一次工具迭代或 UI thinking 当作准确模型调用 |
| tool.started / tool.completed / tool.failed | 原生工具执行事件/hook，带 toolCallId，区分成功、拒绝、抛错；只看到 UI 工具说明不足以推导执行成功 |
| permission.requested / resolved | 原生审批宿主 callback 与持久决议 |
| context.compacted | 原生 compaction notice/状态的实际完成证据；不能仅依据压缩开始消息 |
| task.completed / failed / cancelled | 原生结果 + 服务任务状态 + 交付或停止证据，遵循上述门槛 |
| artifact.created | 实际文件验收/归档成功及 Hash，不由模型文本触发 |

本 Tag 的 `CoreSessionEvent` 类型见 `sdk/packages/core/src/types/events.ts`；`StateService.subscribeToState` 可以支撑 UI 状态，但并非完整事件总线。POC 要在 `SdkSessionEventCoordinator` 转换前增加只读观测边界并保存原始 envelope；具体 AgentEvent 子类型到 model/tool 时间的逐项绑定留到实现时以真实轨迹验证。无法定位某事件要报告可观测性缺口，不从日志文本猜出成功事件。

worker 先写可恢复 journal 再异步发 API；API journal outbox 发布 SSE，按 nativeRef 去重并保留因果顺序。断线重连读取 Last-Event-ID 后记录，超保留窗口返回明确 410 与最早可回放 ID/状态快照；慢消费者有上限和断开重连策略，不阻塞 Agent。API 重启不丢 pending 审批或工具结果，故障注入验证后才能宣称可靠回放。

## Docker 与 Runtime 集成方向

本轮只设计，POC 前不建设生产 Docker/集成。目标独立服务镜像 `runtime-cline-agent-server:4.1.26`，同时标识 upstream SHA 与 adapter revision，禁止只靠产品版本标识自建差异。

Linux 多阶段构建固定源码和基础镜像 digest，安装 Bun 1.4.2/匹配 Node ABI，冻结 root lock、patches、Standalone 独立运行依赖及 prebuild/rg 资产 Hash；依次构建 SDK、生成 proto、compile-standalone、显式打包并验证内容。runtime 只留必须的 Node/原生模块、git、rg、shell、授权语言工具与只读服务代码，非 root 运行。后续验证官方打包命令不能冻结的下载项并形成补丁，不能宣称现有脚本天然可复现。

API 与执行 worker 分离进程/隔离边界。单机多 worker 可由受控 supervisor 预置资源，容器内不默认挂 Docker socket。每 worker 私有工作目录、HOME、CLINE_DIR、workspace storage、MCP/Skills/模型凭据；最小网络许可、资源上限、无跨租户卷访问，worker 内 HostBridge 不发布端口。持久化 session/event/operation 元数据和受控产物卷，凭据由任务授权注入，日志禁止全量环境及 secret 文件内容。

Java 后续通过 `ClineAgentServiceClient/ClineTaskAdapter/EventSubscriber/PermissionBridge/RecoveryService`（名称待实现时按仓库规范）接 HTTP/SSE。保留现有 SDK 配置开关和回滚路径；原生 MCP 访问 Runtime 已授权代理，模型经统一网关预算。Python Sandbox、Silo、OpenSearch 和业务授权不迁入 Agent 内核。

## POC 退出标准

先完成 [真实任务对照计划](../testing/cline-server-native-comparison.md)。核心动作对等、审批拒绝、取消停止、SSE 重连以及双 worker 隔离都须有真实回执；任何核心动作缺口均按宿主矩阵反馈。评审通过仅授权下一阶段的候选路线，不能代替 POC 验收。POC 通过后才扩展部署、业务数据、恢复和迁移；生产默认切换需另行评估。
