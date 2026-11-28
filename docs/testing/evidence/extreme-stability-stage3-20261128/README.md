# 第三阶段隔离部署与故障取证

业务源码基线为 `313f35664afa1983326ee7e8fec9fb8a71d96673`。本阶段只使用基线镜像、独立部署配置和取证脚本，运行数据及密钥留在服务器独立目录。

## 隔离与安全边界

- 服务 Docker 的数据根在 `/mnt/disk01/extreme-stability-313f35664/docker-qa`。新建 Worker 专用 Docker 与 containerd 采用独立网络命名空间、私有 socket 和同盘存储。Executor 实测接受 daemon 身份并报告 cgroup 资源限额已生效。
- 四个镜像修订均为同一提交。PostgreSQL 从服务器既有归档加载。OpenSearch、Silo、Temporal 直接拉取到独立 Docker 存储并固定到所核对的依赖镜像摘要。Native 镜像的二次导入仅在两个 `/mnt/disk01` 的 daemon 之间进行。
- PostgreSQL、Temporal、OpenSearch、Silo、API 和 Native Broker 使用专用端口。数据卷、日志、会话、Worker 目录和新建 Silo 桶归本轮测试所有。模型网关以专用高强度凭证监听独立的 TLS 端口。
- 从授权验证库只读导出 schema 和必要配置目录。最终原始目录快照包含 API 55 张、MCP 50 张配置表，并单独限制到一个验证登录身份。已有任务、会话、运行历史和计划任务没有复制。沿用验证环境的 `default` 租户标识，物理数据库、对象桶、队列及任务空间均独立。
- 核查时共享 `/opt` 保持 92%，共享 Docker 临时目录为 0。本轮独立磁盘由 34% 上升至约 40%，剩余约 61 GiB。新准入检查分别要求 `/opt` 可用至少 13 GiB 和独立盘可用至少 35 GiB。本轮排查不包含共享 Docker 数据根迁移或存储清理。
- 启动前采样为 7 个既有僵尸进程。随后发现 1 个额外的 `auto-setup.sh` 僵尸进程，归属于本轮独立 Temporal 的父进程，发生时尚无 Cline Worker 容器。此项不能归因为 Worker 退出或 Python 回收。

`findmnt` 核实共享 `/opt` 为 `/dev/sdb`，独立盘为 `/dev/sdc`。服务和 Worker 两个 daemon 均使用 `overlay2` 与 `json-file`，两者的 Docker 数据根、容器可写层和抽查到的日志路径均在独立盘。末次采样约为 `/opt` 92%、可用 16 GiB、inode 使用率 7%，独立盘 41%、可用 60 GiB、inode 使用率 2%。共享 `/opt/docker/tmp` 为 0，`lsof` 在抽查时未匹配到已删除仍打开的 `/opt` 文件。正式批次执行前后持续采样，每次新任务准入前重查可用空间。

四个现有本地镜像未登记注册表 `RepoDigests`，本次使用其不可变镜像配置 ID 核对实际运行版本。

| 镜像 | 本地镜像 ID |
| --- | --- |
| Java API | `sha256:33fc423364ebe0f2523c69fef92a179817db80711fd79553a3c8bb1dc41c8d09` |
| Gateway | `sha256:020a2a637e804ef81054d07044170e91af30a2228031cecd1cba7ab9c6983efd` |
| MCP | `sha256:4469ad11a2c146477668733f3fd56f6377511e5125ec964fd90fbc867d8e1fa4` |
| Cline Native | `sha256:b50c3dbbf0bc578228af7f0f6b8b280dd041ab01847fe2c0c69713769645a231` |

## 组件与启动证据

| 组件 | 观察 |
| --- | --- |
| PostgreSQL | 专用 19732 端口，独立 schema 和配置快照 |
| Temporal | 专用 19733 端口，集群报告 SERVING，登记了独立服务中的 `runtime-native` namespace 和测试队列 |
| Silo | 19790 健康检查 200，签名访问读写独立桶的创建和存在性检查通过 |
| OpenSearch | 原始镜像缺少构建文档索引所需的 pinyin 分析器，创建索引返回 400。已从同版本服务器复制经过比对的 ICU、IK、拼音插件，并只读挂载于另一测试实例 |
| Gateway | 19780 TLS 可校验，从验证配置修订 13 提供两个测试模型，使用专属客户端身份 |
| MCP | 初次实例启动未安装许可证，发现接口返回 403。新测试实例只读挂载同机有效许可证及公开验签材料，并显式指定 gRPC 端口 19764。服务报告许可证 VALID，授权快照同步及发布批次完成 |
| Java API 与 Cline | API 正式入口已实际受理场景 7。新路由修订的 API 在 19765 启动，与 MCP 19763、插件版 OpenSearch 19821、独立 Broker 19767 对接。完整业务结果按实际运行另行登记 |
| 中文字体 | 在运行的基线 API 容器内，`fc-list :lang=zh` 识别了 Noto Sans CJK SC 等字体。当前未取得可用于核对最终中文报告渲染的正式业务产物 |

自动审批拦截了包含停止旧隔离 OpenSearch 的命令，返回 `blocked by policy`。保留原隔离实例并以独立端口和存储启动带插件的实例。共享 OpenSearch 未变更。

## 首条正式任务

场景 7 首次带可选报告模式的提交收到 HTTP 400，数据库中未新增任务。取消该可选参数后经正式已发布助手入口获得任务 `5e1b9f5f-987f-3d95-8250-3ef1ca23a0b3`，尝试 `att-1-b47f16b9-17c6-48ae-86eb-f2062132c5f2`。

按服务器本地时间 08:04:55.283 创建，08:05:06.790 到达终态，共约 11.51 秒。任务状态为 `NO_PRESENTABLE_RESULT`，归一状态为 `PARTIAL`。事件链止于必需工具准入，没有工具执行、模板数据交付或 Python 分析回执。对应实例的 MCP 发现返回 `MCP_LICENSE_ACCESS_DENIED` / `NOT_INSTALLED`。这是本轮隔离环境的实际阻断证据，无法据此将现场截图归因为模板参数绑定或模型提前结束。

许可证生效后，MCP 已记录 `initialize` 返回 200、`notifications/initialized` 返回 202。第一次动态路由调整仍发现 0 项工具，旧 API 的周期性恢复路径仍使用先前的 MCP 地址。因此改用新配置启动隔离 API 修订，同时保留原证据。


## 真实链路探测

复核服务器端的请求文件发现，上传旧探测脚本时中文被替换为问号。这些任务保留为组件握手、工具分发和运行特征的证据。其业务输入无效，报告内容、业务时间和业务通过率不能纳入验收。已停止旧批次继续提交。新批次逐条校验九个场景的中文字符与 UTF-8 字节长度，并继续使用正式 API 和真实模型。证券提示和客户数据留在服务器的私有目录。

| 探测 | 可确认的运行结果 |
| --- | --- |
| 数据库能力发现 | 授权 MCP 已发现 39 项工具。运行 `8072c49c` 收到 `database_capability_query` 回执，随后资产发布失败。Silo 测试桶缺少版本化，隔离桶启用版本化后重新核查成功 |
| Native 模型绑定 | 运行 `cac8680e` 失败。Broker 中的继承配置仍指向旧验证模型地址，得到 401，Executor 记录未分配 Worker。修订后 Broker 使用新隔离 API |
| Native 完整探测 `118f8c17-549e-3b3f-a945-bfd998234044` | Cline 创建 Worker、校验输入、经 Gateway 实际调用模型、封存并上传产物。08:20:19.089 至 08:30:04.216，约 9 分 45 秒，终态 `PARTIAL`，Worker 退出码 0 |
| 产物与下载 | 探测产物自报只有一条分析指令，无数据库观测值。正式已发布助手下载入口返回 403。保留下载状态和 355 条脱敏事件。下载授权原因及正确中文请求下的数据库模板链待验证 |
| 其余入口探测 | 原发布入口对 5.1–5.3、9、10 返回 404。使用正常前端任务接口提交后，5.1–5.3 到达 `FAILED`，输出原因为未生成分析计划。上述任务的中文输入已损坏，不能据此判断业务或授权缺陷 |

探测 `118f8c17` 的 Worker 有 34 条 HTTP 200 模型回执和 34 个不同请求 SHA，提供方统计共 995,184 Token，最大请求体 161,298 字节。最长完整响应约 78.6 秒，最长首个有效输出约 3.23 秒。这个样本只能证明一条真实 Cline 链路能传递总耗时超过 60 秒的响应。尚未施加有效首输出延迟和只有心跳的故障，也未复现现场的 `first_packet_timeout`。

同一 Worker 的 `events.jsonl` 为 2,839,592 字节、1,160 条，`worker-log.jsonl` 为 510,366 字节、2,305 条。前者未发现重复事件 ID。单次运行不足以确认语义重复、重放成本、重启后的增长情况和内存泄漏。采样时该 Worker 两个 Node 进程 RSS 分别约 133 MiB 与 531 MiB，终态记录表明容器退出。既有僵尸进程和独立 Temporal 新增的一个僵尸进程仍单独统计。

## 修正编码后的 InnoDB 复现

2026-11-28 08:36:01，经正式已发布助手接口受理任务 `a1029e29-765f-3d51-b7c6-cf3ee641b828`，尝试 `att-1-3330b8d3-f3df-4a71-912f-18a045eb67ea`。服务器实际请求的 UTF-8 字段为 328 字节，含 87 个中文字符，摘要随事件证据保存。

数据库资产能力查询成功。08:37:23，步骤 2 请求 `mcp_chatchat_mcp_server_mysql_db_status_template_query`。MCP 记录 HTTP 200，但 Runtime 第 24 条事件的内部观察类型为 `tool_failure`。实际工具 `database_ops_template_search` 的 schema 拒绝额外顶层字段 `query`。紧随其后记录了 `state_update_failed`，随后进入 Cline。这个实例在模板搜索的入参校验就已失败，当前证据尚未证明 InnoDB 查询执行或获得指标。外层事件标成 `SUCCESS`，`errorCode` 为空，容易使事件查看者误以为工具成功。

取证确认 Runtime 使用固定模板工作流。基线的 `PublishedDataAcquisitionPlanner` 在固定发布域模板发现步骤中写入顶层 `query`。Java 日志记录运行时已将授权资产绑定为 `LiveData测试库_223`，但解析后的入参键仍为 `[query, filters, filtersSchemaVersion, trace]`，MCP Bridge 随后向 `database_ops_template_search` 转交了该字段。目标的域模板 schema 使用 `filters` 等字段，并关闭额外属性。这是本次固定流程的输入契约缺陷，尚需现场原请求的同类日志确认是否同因。

任务事件发布器对缺少 `eventState` 的 observation 按 `COMPLETED` 映射，而计划运行器在这条错误路径只传出 `errorMessage`、`toolName` 和 `type`。这解释了此次外层事件为何显示 `SUCCESS`。影响范围暂以本次模板工具调用和事件呈现为准。

该任务 08:45:09 到达 `FAILED`，约 9 分 8 秒。Cline Worker 完成 29 次 HTTP 200 模型调用，提供方统计共 778,183 Token，最大请求体 164,479 字节，最长完整响应约 72.8 秒，最长首个有效输出 1.72 秒。该 Worker 日志分别为 `events.jsonl` 2,202,892 字节／969 条、`worker-log.jsonl` 439,842 字节／2,006 条，Worker 退出码 0。最终答复明确写明数据采集失败，无 InnoDB 指标、无已发布报告。全量事件 347 条。Docker 任务同样在模板失败后执行了 11 次 HTTP 200 模型调用，消耗 215,237 Token，最大请求 117,391 字节，最长完整响应约 69.7 秒。两项记录说明模板失败后仍可能消耗大量模型和运行资源。

- [固定模板发现入参](../../../../chatchat-agents/src/main/java/com/chatchat/agents/runtime/plan/template/PublishedDataAcquisitionPlanner.java#L72)
- [模板工具 schema](../../../../chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/ops/discovery/TemplateDiscoveryMcpToolPublisher.java#L294)
- [任务状态映射](../../../../chatchat-chat/src/main/java/com/chatchat/chat/task/event/AgentRuntimeTaskEventPublisher.java#L266)
- [工具观察生成](../../../../chatchat-agents/src/main/java/com/chatchat/agents/runtime/plan/InterpretationPlanRuntime.java#L2545)
- [修正编码后的脱敏事件](utf8-business-events.json)
- [InnoDB 正式运行终态事件](case7-utf8-events.json)

## 第三阶段验收进度

| 场景 | 正式输入与验收状态 |
| --- | --- |
| 5.1–5.3 | 有效中文请求按同一会话提交，三项均在 `TASK_CLASSIFICATION` 以 `IllegalArgumentException` 终止，未进入数据供应。请求标注 `qwen3.7-plus`，分类日志实际选用助手配置的 `qwen-3.6-open-plus`，该名称不在隔离模型目录，也不在本轮所用授权 Gateway 源修订的路由中 |
| 6 | `FAILED`，约 4 分 53 秒。任务 `b9ce5824-5a0e-3256-9e2b-3592f154bab9` 在容器资源模板调用被拒。MCP 目标为 `ssh_template_query`，完整 Java 错误指明额外字段 `assetType`、`query`，外层事件同样显示 `SUCCESS`，无可验证容器指标报告 |
| 7 | `FAILED`。确认模板搜索入参契约缺陷及事件状态误标，Cline 未取得可验证的实例指标，未发布报告 |
| 8 | `FAILED`，分类选用未发布的 `qwen3.6-plus`，未进入知识库样例查询 |
| 9–10 | `FAILED`，分类选用未发布的 `qwen3.6-plus`，未进入联网检索或生成研究报告 |
| 11 | `FAILED`，分类选用未发布的 `hy3`，未进入 YARN 指标查询 |

有效中文的九项正式任务均已受理并结束，九项均未通过。七项分类均在 1–2 毫秒内报 `IllegalArgumentException`，应用没有记录相应的模型响应。只读核对确认三个缺失模型名都不在本轮隔离模型目录或所复用的授权 Gateway 路由修订 13。业务验证受助手模型绑定与可用目录不一致影响，现有证据不足以判定这些助手的取数和报告逻辑。

正常批次原始记录位于服务器独立目录 `stage3/logs/business-wave-utf8.log`。逐项任务 ID、失败阶段和脱敏摘要见 [正式业务批次终态](business-wave-results.json)。证券内容及授权目录原始结果留在隔离服务器。正常批次结束后，以相同文字经正式任务入口请求 `Qwen3.8-27B`。首个任务在 Java 分类和 Cline Broker 都仍选用助手固定的 `qwen3.7-plus`。停止了这个批次的后续准入，保留首个任务用于真实取消测试。此项证明了本次入口的实际模型选择，不能作为模型能力对照。

## 真实 Worker 取消与异常退出

- 模型在途取消。任务 `e733d66d-de10-3d40-814f-1d168ed240ca` 已进入 `WAIT_MODEL`，Broker 记录了未完成的原生模型请求。08:55:47 经正式取消 API 返回 HTTP 200 和 `CANCELLED`，约 0.55 秒后收到该请求的迟到 HTTP 200 回执。任务未被迟到回执重新激活。约 1.86 秒后记录 `worker.stopped`，专用 Docker 确认该容器退出码 0。持续复查超过两分钟均为 `CANCELLED`。上游何时停止生成及计费仍需提供方回执确认。 [脱敏取消事件](model-cancel-events.json)
- 模型在途 Worker 退出。任务 `5b5a7bba-7b73-3594-ac9b-8d5b79ae5a8d` 仅在专用 Docker 中注入一次 Worker 强制退出。注入前校验 Worker、会话、租户、基线镜像和容器唯一性。目标退出码 137，PID 回到 0，`OOMKilled=false`。Temporal 记录 `AnalysisTaskWorkflow.v1` 执行失败，约 10 秒后同一个上层任务中出现另一个 Broker 会话及新 Worker。后者开始请求模型。两个 Broker 会话 ID 不同，因此只确认后续执行已启动，尚未证明原会话从检查点成功恢复。隔离测试期间主机仍为 8 个僵尸进程，并未观测到这两次动作新增僵尸。 [脱敏 Worker 退出事件](worker-exit-events.json)

资源采样中另有单个短暂 `sshd` 僵尸样本，PPID 为 1，仅在 08:44:08 记录一次，时间早于两项故障动作。常驻僵尸仍为 8 个，已知的新增常驻者归属于本轮隔离 Temporal。

后续 Worker 的真实请求中，首个有效模型输出分别在 62.201、98.604 和 119.962 秒到达，完整响应分别为 64.028、107.629 和 121.149 秒，均有 HTTP 200 回执。这把本轮已观察到的真实 Native 首输出边界扩展至约 120 秒。该结果只适用于当前隔离路由和配置。客户现场的 `first_packet_timeout` 仍需要受控延迟及逐层时序证据来复现。

故障任务 09:11:37 进入 `FAILED`。新会话收到了 25 条 HTTP 200 模型回执，提供方统计合计 648,098 Token，并记录了交付封存、Silo 上传和 Runtime 校验。任务失败终态与新的交付回执均已持久记录。收尾时专用 Docker 没有运行中的 Worker。原会话重放、检查点一致性和跨会话的业务幂等仍未验证。

独立 Mock 服务脚本 [model-fault-fixture.py](model-fault-fixture.py) 的本地测试已通过。六档的实际首次输出为约 59.01、60.01、61.01、90.01、120.51、180.01 秒。60 秒对照的延迟前分别收到 HTTP 响应头或 SSE 心跳，纯心跳持续 210 秒无模型输出。HTTP 413、429 和半截工具参数后断流也已校验。这些是测试工具自身的可控性结果。完整 Gateway、Cline 和 Java Runtime 路径的注入、真实 413 后重试、各工具与持久化阶段的异常退出、跨组件故障、48 小时连续稳定性以及系统性源码审查仍待执行。

脱敏记录见 [运行摘要](runtime-summary.json) 和 [场景 7 链路探测事件](case7-probe-events.json)。
