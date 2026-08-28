# Agent 运行流程整改说明

## 目标流程

统一链路为：`Task Submission -> Execution/Attempt -> Plan -> DAG Runtime -> Evidence -> Evaluation/Repair -> Delivery`。
Task、Runtime、Plan、Checkpoint、Event 不再各自生成互不关联的运行身份。

## 阶段一：身份、状态与持久化

- `executionId`：一次业务执行的稳定 ID，重试不变。
- `rootExecutionId`：跨重试链根 ID。
- `attemptId`：每次执行尝试的 ID，也是 Runtime `runId`。
- `parentAttemptId/attemptNumber`：形成可审计重试链。
- `AgentExecutionState` 是统一状态语义；旧 Task/Runtime 状态仍保留在兼容边界。
- 默认 Runtime Store 与 Task Event Store 都使用共享数据库。RocksDB 仅作为显式回退配置。
- `agent_execution_event` 同时保存 TASK 与 RUNTIME 事件，通过 `event_scope` 区分，Runtime 原始 eventId 保持不变。
- 启动迁移器为旧任务回填确定性的 attemptId、统一状态和事件 scope。

回退配置：

```yaml
chatchat:
  agent-runtime:
    store-type: rocksdb
  agent:
    task:
      event-store:
        type: rocksdb
```

## 阶段二：不可变发布

发布顺序为：质量门禁 -> License 校验 -> 生成 AgentRelease -> 市场状态切换 -> Release 发布。

Release 固化以下内容：

- 完整 Skill/Prompt/Workflow/Routing 配置；
- 模型 Profile；
- MCP 服务、工具及 ToolConfig 绑定；
- 文档与知识标签绑定；
- release version、SHA-256 checksum 和质量报告。

已发布 Agent 的运行解析优先读取最新 PUBLISHED Release。修改草稿不会改变生产运行语义，必须再次发布形成新版本。

查询接口：`GET /api/v1/agents/workshop/{agentId}/releases`。

## 阶段三、四：边界与预算治理

- DAG 的预算判定由 `PlanExecutionGovernor` 负责，执行器只消费判定结果。
- 所有模型调用通过 `MeteredChatModel`，记录估算输入/输出 Token、调用次数、模型耗时和估算成本。
- Token/成本上限在下一次调用前检查，并在调用返回后再次检查；超限立即失败。
- lifecycle 事件记录相邻阶段耗时，结果 metadata 包含 `agentPhaseDurationsMs`、`executionElapsedMs`、`modelUsage`。

成本单价默认为 0，部署方必须按模型供应商价格配置，才能得到有意义的成本与成本门禁：

```yaml
chatchat:
  agent-runtime:
    model-token-budget: 0
    model-cost-budget: 0
    model-input-cost-per-thousand-tokens: 0
    model-output-cost-per-thousand-tokens: 0
    budget-alert-ratio: 0.8
```

0 表示不设置全局硬上限。单次请求可通过内部属性 `__agentTokenBudget`、`__agentCostBudget` 设置更具体的上限。

## 阶段五：受控优化

反馈不再直接修改生产 Prompt。优化生命周期为：

`DRAFT -> VALIDATED -> APPROVED -> CANARY -> READY_FOR_ROLLOUT -> ROLLED_OUT`

- Proposal 必须引用同租户的 Experience；
- Patch 仅允许 Prompt、Workflow、Routing、ToolConfig、快捷问题字段；
- VALIDATED 必须带至少一个用例且整体通过的回归报告；
- Canary 范围限制为 1%–50%；
- Canary 至少 10 个样本且指标通过，否则自动 REJECTED；
- 每次转换校验租户、前置状态和乐观锁；Proposal 本身不会直接修改在线配置。

管理接口位于 `/api/v1/agent-optimizations`。

## 已发布 Agent 外部 API

外部调用由独立的 `PublishedAgentApiController` 维护，不直接暴露内部 Task 管理接口：

- `POST /api/v1/published-agents/{agentId}/questions`：提交问答，返回 `taskId`、状态和答案地址；
- `GET /api/v1/published-agents/{agentId}/questions/{taskId}/status`：查询执行状态；
- `GET /api/v1/published-agents/{agentId}/questions/{taskId}/answer`：获取完整最终答案和引用；
- `GET /api/v1/published-agents/{agentId}/curl-example`：生成完整 curl 示例，仅平台 admin 可访问。

请求必须携带登录接口签发的 Bearer token。服务端强制使用 token 对应的 tenantId/userId，并在每次操作中校验：

1. Agent 必须已发布（提交阶段）；
2. 当前角色必须绑定该 Agent；
3. task 必须属于当前租户、当前用户和 URL 中的 Agent；
4. 客户端传入的 `__*` 内部运行参数会被过滤；
5. curl 示例使用 `CHATCHAT_TOKEN` 占位符，接口不会返回或复制当前登录令牌。

同一个 `sessionId` 可连续提交多轮问题，从而复用用户会话历史。curl 示例按钮位于“Agent管理”的已发布 Agent 卡片中，并且只对 admin 账号渲染。

## 上线检查

1. 先执行数据库 DDL/备份，确认新表与新列存在。
2. 单实例启动，观察旧任务身份回填日志。
3. 验证 Task API 返回 execution/attempt 字段，事件中 TASK/RUNTIME scope 顺序正确。
4. 发布一个测试 Agent，确认 release checksum 和质量报告，并验证编辑草稿不会影响已发布运行。
5. 配置实际模型费率，再启用成本硬上限。
6. 扩到多实例，验证跨实例查询、取消、重试、计划版本和检查点恢复。
