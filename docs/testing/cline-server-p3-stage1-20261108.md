# P3 第一阶段基础实现与真实测试报告

日期：2026-11-08，基线 `62ce5d9e5`。结论：**双租户隔离基础测试通过；P3 整体及三个硬门槛尚未全部通过**。本轮停在第一阶段证据评审点，没有执行第二阶段操作恢复/远端取消集成，没有进入模型预算、Silo 或 Java 集成。

交付：[根因矩阵](../audits/cline-server-p3-root-causes-20261108.md)、[隔离设计](../design/cline-server-p3-stage1-isolation.md)、[Docker 运维](../../cline-agent-server/docker/README.governance.md)、[66个关键证据文件](evidence/cline-server-p3-stage1-20261108/README.md)。

## 已完成及范围

任务合同真实签名校验，绑定 tenant/user/task/session/ref/profile/权限/期限；按合同路由 Session、审批和 SSE，拒绝任意宿主路径。基础 Scheduler 原子预留全局/租户 slot，容量满429；UNKNOWN/启动未确认停止不释放容量。每任务独立 Docker、工作区、HOME、Cline/MCP 配置、state、bridge、资源限额，network=none、UID1000、只读根、无 Docker socket、无 host port。

只有控制面可访问 Docker 和真实 provider/MCP 凭据。任务内模型/MCP 通过自己专属 Unix Socket 代理，经合同校验后转固定上游；这只是网络隔离所需转发，未实现 Runtime 模型预算网关。Worker 的 Agent/Editor/Patch/Shell/MCP 决策和执行仍为同 Tag 官方产品。新阶段没有新增上游补丁；Core/Agents608个文件再次逐字节比较零修改，1850运行资产及 SQLite ABI 验证通过。前轮103/93回归证据保留，本轮不冒充重跑该全集。

本轮 MCP 是独立的官方 SDK、真实租户私有文件安全 fixture。它证明协议/授权隔离，不代表生产 Runtime MCP、动态授权及 Sandbox 已集成。测试身份由可信私有 HMAC 发行者生成，尚未接 Runtime 实际身份发行/JWKS。生产服务、共享模型/MCP/Sandbox 配置、默认 Coding Agent 及旧 SDK 均未修改。

## 最终镜像真实双租户结果

最终镜像 `sha256:bfea82551ff365794670f7a4160de239cd3ff985b07b89c811c3ed2b3adb2b31`，父镜像仍为已验证 POC `sha256:f23feae55353f57f2e7fb68455a41b2f26cd383b0ca04f928e0072ad7b869ace`。模型 `qwen3.7-plus`；两个隔离工作副本来自固定 Runtime `7d076d0dc` 的完整真实 coding-agent-node 源码，再加入各自私有文件 canary。

两用户同时通过 HTTP 提交任务，各自自主读取原生文件、用原生 `tenant_data__read_dataset` 获取本租户真实 MCP 记录、原生 editor 新建 JSON 和测试、原生 Shell 运行测试。`simultaneous-status.json` 保存同时运行/等待审批的实际状态。验收器不替模型写代码，只对生成结果独立运行 Node 测试及核对 MCP 数据。

| 真实租户 | Session / native session | 结果 |
|---|---|---|
| tenant-a / user-a | `session-4029ac059c8b43348f7d304866e1a9d5` / `1794110882378_fnmkl` | 41.18秒，原生完成；独立4/4，真实 MCP marker 完全匹配 |
| tenant-b / user-b | `session-b1a3d2550e004964bf8b3d45c94924bd` / `1794110882375_qoz4h` | 45.31秒，原生完成；独立2/2，真实 MCP marker 完全匹配 |

两个 Worker 的 cpuset 分别8,9和10,11，各3GiB/2CPU/PID256。新文件 Hash/Diff、原生 toolCallId/输入/结构化输出、模型回合、独立日志分别保存；原有 tracked 业务文件均未改变。控制审计、实际 MCP 文件读取日志和容器 inspect 来自 Worker 外部。

## 恶意访问与撤权结果

| 实测 | 结果和证据 |
|---|---|
| 双向 Session/status/messages/events/diff/artifacts 读取及消息/cancel/revoke 越权 | 404；失败调用没有路由到另一 Worker；http.json.gz |
| 其他租户 resolver 的 approve | 404；adversarial-summary.json |
| 身份篡改、未知/原型租户、客户任意路径 | 篡改401；签名但未授权 prototype tenant403；任意路径400 |
| 创建重试与资源限制 | 同合同/key返回原记录；异体409；第三任务429，不重复分配 |
| 治理入口 SSE 与独立租户配额 | 真实 SSE断线不取消 native worker；Last-Event-ID 1后回放2；外国SSE404；全局仍空一slot时同租户第二任务429；过期身份401、外国数据grant403 |
| 正文延迟与撤权竞态 | 请求先鉴权、正文留最后一字节，撤权后再发送该字节，实际403；没有原生模型派发；晚到状态不覆盖撤权结果 |
| 真实文件/Socket攻击 | UID1000 尝试另一租户宿主绝对路径、state、worker.sock、symlink，均不可读；Docker socket、控制面密钥未挂载 |
| 网络越权 | Worker 仅lo；直连实际 MCP 容器、宿主18100/18101、外网均失败，不靠 Prompt 禁止 |
| MCP/模型 broker | 自己的数据200且值匹配；对方dataset403、外国broker route404、未授权model403；MCP服务未收到越权数据读取 |
| 原生运行中撤权 | 真实 Shell 已批准并启动后 revoke，native cancel及Docker实际停止；后续消息/事件/审批403，另一租户仍正常 |
| 停止后观察 | 原生 call `call_983fadb1b176430fa693cc2f` 被停止，Docker running=false；额外25秒后原20秒延迟文件未出现 |
| 基线/新增回归 | Windows及最终 Linux镜像8/8；导出器实际文件/symlink/FIFO隔离回归2/2；runtime1850文件、descriptor22服务、Node24.21 ABI137/SQLite3.50.4通过 |

撤权回执为 **CANCELLED_LOCAL_ONLY、remoteStopConfirmed=false**；本地容器停止不能用作 MCP/Python 远端长作业停止证明。最终无运行中的 stage1 Worker，治理入口保留在宿主 `127.0.0.1:18328`，实例目录 `/opt/cline-server-p3-stage1-20261108`。

## 失败历史与具体位置

| 实际失败/问题 | 调用点及修正 | 证据 |
|---|---|---|
| MCP fixture 启动 MODULE_NOT_FOUND | test/tenant-data-mcp.mjs 的 server SDK require；Standalone 分发不公开 server SDK，fixture 改用固定官方 workspace SDK依赖，仅测试容器只读挂载 | failures/mcp-fixture-initial-failure.log |
| 启动中 status409 | governance-server.mjs 的 nativeSessionId 可用性检查先于状态观察；现启动阶段返回公共状态及 native=null | failures/startup-r1-failure/ |
| MCP权限拒绝 | server.mjs native.permission 合同上限真实拒绝 `tenant_data__read_dataset`，原因是授权按猜测 mcp 前缀；现按实际原生目录名字授权 | failures/policy-r2-failure/、failures/native-*.json；没有改写失败为成功 |
| 未确认资源被当可释放、并发slot计算不稳 | task-contract.mjs:holdsCapacity 与 governance-server.mjs 创建/allocate；保留未停止容量，await前预留slot，最终镜像重跑全部双租户/恶意/撤权测试 | linux-unit-tests.log、最终 worker-container.json |
| 复核发现正文及晚到状态竞态 | governance-server.mjs 在读取正文后、转发前复核 live lease，过期create拒绝；撤权后不写回晚到native status | scoped-sse-and-quota.json 的真实分段HTTP403 |
| 证据处理的潜在宿主读取风险 | 原导出方式对不可信workspace运行host Git或跟随symlink；现直接比较只读可信模板，以 O_NOFOLLOW/dir_fd 读文件，拒绝目录链接、FIFO及越界路径 | evidence-export-tests.log；不执行不可信Git配置，不读取链接目标 |

## 未完成项、安全风险与下一步

| 用户硬门槛 | 本轮状态 | 下一阶段必要证据 |
|---|---|---|
| 两用户 Session/文件/MCP数据隔离 | 基础测试 PASS_WITH_DEDICATED_REAL_MCP_FIXTURE；生产 Runtime 身份与实际 MCP 数据源未验收 | 评审当前身份/威胁模型，再在获准环境验证真实 Runtime 权限及动态撤权 |
| Worker被杀后判定各操作完成情况 | NOT_RUN；只有 UNKNOWN/fail-closed，不是操作恢复 | trusted Operation Journal/outbox、稳定 operationId、epoch/fencing；kill 在提交前/执行中/回执前，不重放已完成副作用，不把未知判成功 |
| MCP与Python远端实际停止 | NOT_RUN；当前只有本地停止 | 原生 MCP 长任务与现有 managed Python Sandbox 的真实取消、进程树/容器 inspect、超过延迟写入期限的无输出证明、迟到回执和副作用对账 |

控制面 Docker socket 等同宿主高权限，尚无生产 TLS/发行与运维授权、磁盘配额、完整资源压力/内核攻击测试、PostgreSQL 事务与审计防篡改。Worker 自身可写的 native state 不能作为可信远端业务操作账本。详细源码定位与复用点见根因矩阵，包括现有 PythonManagedExecutionService.cancel、PythonDockerRuntime.stopManaged 和 Runtime durable journal；本轮不修改这些 Java 实现。

任务书第十四节要求“每一阶段必须先提交测试证据，评审通过后再进入下一阶段”，第十五节只授权本轮架构审查和第一阶段基础实现。因此提交证据后停在评审点；三个硬门槛整体未通过前，预算、Silo 和 Java 集成保持未开始。
