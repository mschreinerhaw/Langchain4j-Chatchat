# Cline Server POC 下一阶段整改建议

日期：2026-11-08。依据 [真实验收报告](../testing/cline-server-runtime-tests.md)。本次已证明固定官方 Standalone 的核心自主编码能力可经 HTTP 使用；继续保留官方 Core/Agents、Editor/Patch、Shell、压缩与工具决策链，不增加 Java 自定义执行逻辑。

## 上生产前必须完成

| 优先级 | 整改 | 下一轮必须提交的验收证据 |
|---|---|---|
| P0 | 每 worker 独立 OS/容器工作区、凭据和网络边界；单操作者 token 改为 Runtime 租户/任务委托合同 | 跨租户路径、软链、Shell、环境、网络与凭据攻击用例均被隔离；workspaceRef 校验不能代替沙箱 |
| P0 | 模型调用走受约束网关，单次/累计预算、租约 fencing 与可信 usage 对账 | 原生模型请求仍由 Cline 决策；超预算/撤权后无新调用，未知价格不计零费用 |
| P0 | 原生审批 resolver 与 durable outbox/Operation Journal 关联；崩溃后拒绝重放旧审批 | kill API/worker、重启、过期决策、重复回调及并发取消故障注入；不重复副作用 |
| P0 | 本地进程所有权延伸到 worker 崩溃；原生 MCP/Sandbox 操作取消、远端停止证明与对账 | 长运行外部操作中取消、断网、迟到回执；确认远端停止后才 cancelled；无法确认时显式 unresolved |
| P0 | 独立交付验证与任务终态分离 | 真实业务项目、财务/Parquet 等成果验收；文件 Hash、不可变版本和归档授权，不以模型自述作为完成证据 |

## 后续能力与工程化

| 优先级 | 具体调用边界 | 整改及门槛 |
|---|---|---|
| P1 | `definitions.ts` 的 bashTimeoutMs 默认 30 秒、vscode-run-commands-tool background 包装 | 宿主 profile 明确工作负载预算和超时；原生 Shell 输出/直接退出码保留；禁止用 Java 补跑失败命令 |
| P1 | `HostProvider/common.ts` 的全局状态、后台服务与初始化 | 每 worker 隔离配置、存储和生命周期；ready 通过真实 native 任务探测；测试重启/异常回收 |
| P1 | `ExternalEditPreview`、host diff/window/workspace diagnostics | 列明支持能力和 UNIMPLEMENTED，按业务需求实现；补 VS Code Desktop 同模型/同材料/同审批的完整 A/B |
| P1 | `McpHubToolProvider/createMcpTools`、Core Skills/prepareTurn | 真实授权 MCP 与 Skills 发布、撤权、工具 Schema 动态变更；管理后台登录不算原生工具集成 |
| P1 | HTTP/SSE/session registry | 完整 OpenAPI、输入限制、速率/背压、日志保留、journal 截断/损坏处理、并发/重连/恢复测试 |
| P1 | Standalone packaging、native sqlite ABI、proto descriptor | 固定基础镜像 digest、Node/Bun/双 lock、资产 manifest、SBOM/许可证、离线与升级回滚；不能只按版本号选 registry 包 |
| P1 | 项目 fixture、CPU/cgroup、代理与证书 | 使用完整 tracked 文件清单/Hash；资源并发取 cpuset 实际核心数；完整回归与所需 proxy/CA 白名单实测 |

业务 Runtime 接入应先通过配置开关使用 HTTP/SSE，保留现有授权、预算、归档与 operation 治理。只有完整场景、异常恢复和外部停止证明通过后，才评估默认 Agent 切换及旧 SDK 适配层退出。本次不宣布这些门槛已通过。
