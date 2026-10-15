# Runtime OS Coding Agent G2 验收报告

日期：2026-10-15。先核对 G1：`docs/runtime-os-coding-agent-g1-acceptance.md` 与 `target/coding-b-final-validation.log` 记录真实 Docker/H2 定向测试 15 项，0 失败、0 错误、0 跳过；本次 Docker Engine 29.6.2 可用，因此进入 C1–C3。

## 实测范围

命令：

```powershell
mvn -pl chatchat-api -am -Pbackend-only '-Dfrontend.skip=true' '-Dcoding.realDocker=true' '-Dtest=CodingEvidenceDockerIntegrationTest,CodingEvidenceCollectorTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

日志：`target/coding-c-final-validation.log`；17 个 reactor 模块构建成功。API 定向测试 **6 项，失败 0、错误 0、跳过 0**：H2-backed Collector 4 项，真实 Docker 端到端 2 项。Docker 测试实际使用 `python:3.11-alpine`，真实 B 执行服务及工作区归档、A2 bridge/预算/停止证明、现有 JDBC Evidence Store。MCP 端口在测试进程中委托给实际 B 服务，网络 HTTP 传输不在此次 C 测试范围内；G1 已单独验过相关 B 传输行为。

| C3 情形 | 实际验证 |
| --- | --- |
| 正常计算 | 授权 CSV、固定 Python、独立 Java 求和；六项引用均可解析，结果可完整分页读取；A2 预算和 operationId 保留；Runtime 提交 `COMPLETED` + `PASSED`。 |
| 合法零行 | 空 JSONL 的 manifest 为 `EMPTY`、行数 0；六项引用完整，数据集可读。 |
| 业务校验不通过 | Python 正常完成，独立验算记录 `FAILED`；任务仍按编程合同完成，Runtime outcome 的 `validationStatus=FAILED`。 |
| Python 执行失败 | B 状态 `FAILED` 且执行停止；归集器拒绝发布完整结构化结果。 |
| 执行中取消 | 真 Docker 运行中的任务被取消，实际容器停止；归集器拒绝发布结构化结果。 |
| 归档过程中故障 | 脚本故意创建非法结果目录；B 保留 `UNKNOWN` 且无 manifest；C 不发布悬空引用。测试仅在断言后清理自身容器。 |
| 服务重启后恢复 | 重建 B 服务与 C 归集器实例，读取已归档结果并幂等重试已提交 batch；H2 测试还在 VERIFIED 后注入提交故障，重建 JDBC adapter 后完成提交且不再次执行。 |
| 跨租户及未授权读取 | 别的 tenant 不能解析相同 ID；归集器读取先检查 A2 任务及 producingTaskId。 |

## G2 判定和限制

**G2 在本地真实 Docker + H2 的固定脚本范围内通过。** 六项引用、结果完整读取、预算和执行身份、事务发布与恢复均由上述真实测试支撑；没有调用大模型或 Cline SDK，未修改 Analysis Loop。`docs/runtime-os-coding-agent-c-implementation.md` 给出协议与六项引用实例。

尚未验证生产 MySQL/PostgreSQL、多实例共享工作区和 C 端真实 HTTP/网络故障；不宣称这些环境已验收。当前数据集读取会完整装载在进程内，受合同 maxResultBytes 限制；大结果需要分页/流式读取适配。归档文件真实损坏或操作为 `UNKNOWN` 时保持阻塞，不能擅自补造成功证据。CPU/费用等无法计量值沿 A2/B 规则保留预算预留，FULL 可复现等级不自动声明。C 阶段验证规则由受信任 Runtime 调用方提供，D1 前应固定规则版本、权限和可观察性，避免将模型自述当作独立验算。

D1 前置条件：锁定本报告的六引用、回执/manifest 与 A2 绑定校验；明确规则注册和大结果策略；在目标生产数据库及部署拓扑重跑持久化、授权和故障恢复测试。D1 尚未启动。
