# Runtime OS Coding Agent D 阶段实施记录（未验收）

日期：2026-10-15。G2 核验依据为 `target/coding-c-final-validation.log`：H2 持久化与真实 Docker 的 C 阶段定向测试共 6 项，失败、错误、跳过均为 0。该记录的范围是本地固定脚本；没有覆盖真实模型、Cline、跨进程 HTTP 回调或生产数据库。因此允许开始 D1，但不能把 G2 的测试结果当作 D1/G3 证明。

## 当前 D1 代码与接口

- `coding-agent-node` 锁定 `@cline/agents@0.0.93`。使用无默认宿主工具的 `Agent`，只按合同暴露 `python_execute`、`metadata_read`、`artifact_read`；SDK 工具重试关闭。Node HTTP 服务仅监听 loopback，并用共享密钥、合同哈希、任务身份和 fencing token 绑定一次会话。
- `ClineCodingAdapter` 复用 A2 的 `DurableCodingTaskService`；提交先持久化，再领取租约并尝试分发。取消先持久化 Runtime 停止请求，再向 Node 和已知 Python operation 传递取消。恢复只查询未知身份，不重放代码执行。
- `ClineRuntimeModelBridge` 通过项目的 `ModelResourceRegistry` 和 `ConfigurableChatModelFactory` 使用服务端默认模型，凭据留在 Java。模型工具调用经合同白名单校验；为 Coding 调用设置输出上限、90 秒超时，并关闭连接层额外重试。Node `AgentModel` 只转发文本、工具调用与用量事件。
- Node 拟调用 Java 的 `/internal/coding/cline/{action}`：`reserve-model`、`model`、`settle-model`、`execute-python`、`cancel-python`、`read-metadata`、`read-artifact`、`event`、`finish`。**这些受保护的 Java 回调路由尚未实现，接口目前不是可运行的端到端链路。**

## 已运行验证

| 验证 | 结果 | 实际覆盖 |
| --- | --- | --- |
| `npm.cmd test`（`coding-agent-node`） | 5 项通过 | 真正的 Cline `Agent` 使用确定性假模型进行生成、Sandbox 工具报错、修复；Node HTTP 幂等与认证；Runtime 模型事件转换与不支持内容拒绝。未使用真实模型。 |
| `mvn -pl chatchat-api -am -Pbackend-only '-Dfrontend.skip=true' '-Dtest=ClineRuntimeModelBridgeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | 2 项通过，Reactor 成功 | Java 服务端模型映射和未授权工具拒绝；模型为单元测试替身。 |
| G2 既有记录 | 6 项通过 | 固定脚本 H2/真实 Docker 证据闭环；不包含 D1。 |

## D1 未关闭项及验收判定

1. Java 回调必须先以可信身份绑定任务，再校验已持久化的合同 hash、当前 lease/fencing 和停止状态；A2 的 `CodingTaskAccess` 在生产组合中仍要求显式可信实现，不能用请求载荷代替身份。
2. 模型调用、工具调用须先做同一预算身份的持久化预留，执行后按可证实用量结算；未知费用保留预留。当前 Node hook 有调用点，但 Java 回调未落地，不能声称预算闭环。
3. Python 工具调用须解析 Runtime 授权的数据快照和 `environmentRef`，经 `CodingPythonExecutionBridge` 启动、查询与取消；不能从模型指定任意 `assetId`、输入路径或数据库。证据归集须使用受信的独立验算规则注册，不得把模型自评当成业务验证。
4. Node SDK 状态目前仅在进程内。Node 重启后 Java 不重放未知任务，这是安全的保守行为，但恢复核对、会话快照持久化与终态回流尚未完成。
5. 尚无服务端默认模型 + Node + Java + MCP Python + Evidence Store 的真实集成测试，也未验证真实模型生成、报错修复、取消、预算和六项引用。

**D1 未通过。按阶段门槛，D2、D3 尚未开始，G3 未通过，不宣称生产可用。** 最短的后续验收路径是：完成受保护回调及可信身份、预算和工具接线；先用确定性模型跑跨进程真实 Docker/证据测试，再用项目现有模型配置执行一次受控真实模型任务。确认 D1 后才接入 Analysis Loop；其后才能运行金融数据故障注入对照。

当前风险：`@cline/agents@0.0.93` 属实验性 SDK；依赖审计仍有漏洞告警，且本机 Node 25 与部分依赖声明的偶数主版本要求不一致。生产部署前需要固定受支持的 Node LTS、审查依赖并验证 SDK API 兼容性。模型费用数据若缺失，A2 应保留预留，不能按零费用结算。
