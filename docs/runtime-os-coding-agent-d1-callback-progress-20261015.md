# Runtime OS Coding Agent D1 回调与 Sandbox 联调进展（2026-10-15）

本记录承接 `runtime-os-coding-agent-d1-container-debug-20261015.md`。生产 Coding Agent 仍未启用；生产 Java API 尚未部署本地回调代码，线上 `/internal/coding/cline/reserve-model` 的 404 仍可预期。线上 API/MCP 继续运行，本记录不构成 D1、D2、D3 或 G3 验收。

## 本地已实现

- `CodingInputSnapshotService` 仅从当前 Analysis Loop 已交付的 `DatasetHandle` 选取一份授权数据，按行数/字节上限导出不可变 JSONL，确认 MCP 文件哈希与大小后，在既有 `JdbcRuntimeEvidenceStore` 登记输入证据。提交合同必须精确绑定任务、决策、工作区及该文件 ID；既有 MCP 资产发现、授权模板、参数绑定、执行模板路径未修改。
- `CodingValidationPlanService` 在 Runtime 侧登记独立 Decimal 合计与源记录唯一键校验规则。计划绑定输入证据与任务，标准答案不发送 Node；验算时重新读取并校验输入哈希。该规则仅是 D1 金融固定场景，不能替代通用业务验算器。
- `/execute-python`、`/get-python`、`/cancel-python` 通过 A2 lease/fencing、预算预留及既有 MCP Python 异步控制面运行。工具仅能使用当前合同的单个输入快照、固定 `main.py` 与一个 JSONL 输出文件；失败诊断读取已归档且限长的 stderr。Node 必须轮询到 Docker 确认停止后才接收结果。
- 成功的 Python 操作由 `CodingEvidenceCollector` 验证 manifest、执行回执及独立验算，并提交六项引用。`/finish` 仅以已验证操作将 A2 状态提交为 `COMPLETED`，业务验证失败仍单独记录；SDK 失败且没有未知执行时可提交 `FAILED`。旧 C 阶段归集的输入证据支持幂等重入。
- `ClineCodingAdapter.resume` 对失效租约可接管，核对已知 Python 操作和已有持久回执的模型调用；没有回执的模型调用保持预留且不重放。当前 Java 入口拒绝尚无受控回调的 `metadata_read`、`artifact_read`，防止将未实现的工具假装成可用能力。

## 本地验证

- Node `npm.cmd test`：10/10 通过，覆盖 Cline SDK 技术错误修复循环、异步 Python 停止确认、工具拒绝、签名回调和服务鉴权；模型为确定性替身。
- Java 定向 H2 测试：输入快照、独立 Decimal 验算、模型预算/回执、证据重入通过。`CodingValidationPlanServiceTest` 验证精度错误、空结果与输入篡改。
- Java 真实 Docker + H2 集成测试：`CodingEvidenceDockerIntegrationTest` 三项通过，新增 Java 回调→MCP Python 容器→停止核对→六项证据→A2 `COMPLETED` 链路；原有固定脚本正常、零行、业务失败、Python 失败、取消及归档故障场景保持通过。Docker 运行使用本机镜像，MCP/证据持久化为测试 H2；这不等于线上部署验证。

## D1 仍未关闭的门槛

1. Node 的 Metadata MCP 与证据产物读取回调尚未实现；Java 已拒绝相应工具合同。DataWorkspace 到 Analysis Loop 的可信提交入口尚未接线，因此没有真实用户任务。
2. 未使用项目现有服务端模型配置执行一次真实 Cline 模型调用；未验证真实模型的代码生成、报错修复、计费和取消。当前 Java 对未知模型费用保留预留，不声称已按实际费用结算。
3. Node 会话仍只驻留内存。Java 恢复会核对已知 Python 操作且不重放未知模型调用，但容器重启后的会话恢复、无启动意图任务的收尾、超时重复派发场景未完成端到端验收。
4. 容器出站仅靠配置和工具白名单，尚未证明网络策略强制只允许模型网关及内部受控接口；累计 CPU/输出预算也不应被描述为全部由 Docker 硬限制。
5. 生产 Java JAR、Node 镜像及配置仍为旧版本。本地修复未部署；生产 API/MCP 不变，生产 Coding/Cline 开关继续关闭。

本轮只读网络与进程检查：生产 API `/` 返回 HTTP 200，MCP `/` 返回 HTTP 302；两者仍由原有 Java 进程监听 8080/8090，独立 `coding-agent-stage` 容器仍在 loopback 端口运行。先前容器的 `POST /reserve-model` 合成请求返回 404。本轮没有提交真实生产 Coding 任务，也没有替换线上 JAR 或镜像。

因此 D1 尚未通过，D2/D3 不启动，G3 未通过，不能宣称生产可用。
