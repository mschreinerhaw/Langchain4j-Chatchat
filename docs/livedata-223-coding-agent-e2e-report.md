# LiveData测试库_223 Runtime OS + Cline 技术验收报告

验收时间：2026-10-16；分支 `main`，源码提交 `bdc7f898`。整体端到端 PoC：**未通过**。真实数据库工作流未启动，因此本文不把本地模型替身门禁或模型连通性当作业务验收。

## 构建、隔离部署和回滚

API/MCP 已从当前源码重新执行离线 Maven 打包并传输到主机 `/opt/coding-deploy-stage-20261016-phase2/artifacts/`。本地与远端三个产物的 SHA-256 一致，详见 [部署清单](livedata-223-evidence/deployment-manifest.json)。本次未替换现网 8080/8090 的 Java 进程。Node Worker 复用目标主机已有的 `@cline/agents 0.0.93` 基础镜像，仅覆盖当前 `src`，构建 `runtime-coding-agent:phase2-staged-20261016`；容器 `coding-agent-phase2-stage` 仅绑定 `127.0.0.1:8793`，鉴权 `/healthz` 返回 HTTP 200，未携带鉴权时返回 401；空闲内存占用约 94 MiB。该容器的回调指向未启动的隔离 Java 测试端口 18080，所以**健康检查不代表可运行 Coding 任务**。现网 Node 8791 与旧测试 Node 8792 均未替换。首次启动因只读挂载的密钥文件属主不符而报 `EACCES`；修正为容器运行用户可读后健康检查通过，所有凭证值未写入报告。

回滚：`docker stop coding-agent-phase2-stage && docker rm coding-agent-phase2-stage`；Java 产物仅为隔离目录内文件，无服务切换需要回滚。不要将这些产物覆盖现网路径，直至阻断项复核通过。

## 模型和执行链

`dev` 配置含 `hy3` OpenAI Compatible 模型，凭证从目标环境已有安全配置解析，未向 Node Worker 传递密钥。一次最小聊天补全连通性探测返回 HTTP 200、非空 `choices` 和 `usage`，耗时约 1.42 秒。这证明模型网关可用，**尚未通过 Agent API 调用该模型**，也未验证 Cline SDK 的真实业务任务。Node 当前代码经 Java `ClineRuntimeModelBridge` 回调既有模型服务；本次隔离 Node 容器尚无可用 Java 回调。

| 验收项 | 结论 | 本轮证据或阻断 |
|---|---|---|
| MCP 资产发现、模板选择/绑定、授权取数 | 受阻 | 目标资产和授权模板未发现；相关全量回归未通过 |
| DatasetHandle/manifest、分片/hash/归档 | 受阻 | 无真实输入数据；仅第一、二阶段本地门禁通过 |
| Java CodingTaskContract 提交与 Analysis Loop 消费 | 受阻 | 隔离 Java 未启动，未提交真实业务任务 |
| Node Cline SDK 真实调用模型及自主编程 | 受阻 | Worker 健康，模型独立探测通过，未发生 SDK 业务调用 |
| Python Sandbox 执行、修复重跑 | 受阻 | 目标主机的 `chatchat-python-runtime:3.11-v1.1` 在无网络、只读根文件系统、256 MiB/1 CPU 限制下执行临时 Python fixture 并生成带 SHA-256 的文件；代码和验证记录见 [环境证据目录](livedata-223-evidence/)。本场景未走 Sandbox API、未生成或修复业务代码。既有本地 G1/G3 使用真实 Docker/Python 及模型替身 |
| Evidence Governance 全尝试链、数值独立验算 | 受阻 | 没有实际数据、代码、运行和结果证据 |
| 事件、预算、取消/恢复 | 受阻 | 本场景没有任务运行 |

门禁来源：`target/coding-gates/20261016-145630/gates.json`。G1/G2/G3、Node、前端及选定 Java 39 套件/366 项通过；G3 模型和模板源为确定性替身。完整后端 610 套件/3451 项中 **142 失败、64 错误、0 跳过**，完整门禁失败。失败清单见 `docs/runtime-os-ai-coding-phase2-failures-20261016.csv`。2026-10-16 16:03 至 16:05 又单独复跑相关用例：Tool Access 7 项中 3 失败，MCP 数据库模板发现 8 项中 3 失败，授权隔离 3 项全部因测试上下文缺少已发布 Runtime semantic policy 而错误；机器可读结果见 [focused-gate-rerun.json](livedata-223-evidence/focused-gate-rerun.json)。这些阻断当前可重现，不能用定向 G1/G2/G3 通过覆盖。

本轮未产生实际 SQL 引用、数据集 manifest、Python 代码、计算结果或验算文件；没有可据以计算任务完成率、数值正确率、检出率、误报率、真实 SDK 耗时及 Token 消耗的样本。模型独立探测的 `usage` 不计作 Coding Agent Token 用量。

## 明确结论

数据库运行情况：**证据不足**。MCP 数据获取：**受阻**；Cline 自主编程：**受阻**；Python Sandbox：**受阻**；Evidence 回流：**受阻**；Runtime Analysis Loop：**受阻**；整体端到端 PoC：**未通过**。
