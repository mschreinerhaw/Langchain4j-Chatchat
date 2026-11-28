# 极限稳定性专项测试阶段记录

基线为 `fix/runtime-gateway-lifecycle-control` 的 `313f35664afa1983326ee7e8fec9fb8a71d96673`。应用源码、网关配置和测试基线 Dockerfile 均按此提交验证。此次工作只增加独立测试和证据，不包含实施变更或发布评审。

本地使用 Node 25.0.0、JDK 17.0.19 和 Maven 3.8.9。网关锁文件中的 Undici 为 7.30.2，Vitest 为 4.1.12，Vite 为 7.3.8。

## 本地证据

| 范围 | 执行结果 | 证据边界 |
| --- | --- | --- |
| 模型网关 | 构建成功。既有 Vitest 30 项、HTTP 路由 1 项通过 | HTTP 用例验证配置版本、Runtime 请求关联和取消 |
| 网关极端输入 | 44 个独立模拟样本 | 对接本地 Mock，直接运行生产 `proxy`。六个上下文档位以字符数模拟上游拒绝，实际 Token 边界待测 |
| 网关延迟和并发 | 5 至 180 秒五档普通模式请求完成。1、2、4、8 四档并发中，每组额外两次请求均返回 429 | 1、3、5、10、30 次每秒为短 SSE 分片采样，实际模型生成速率、排队及长流待测 |
| Java 模板与数据供应 | 208 项通过 | 授权、候选模板、参数及工具结果审阅定向测试 |
| Java Harness 与恢复 | 58 项中 57 项通过，1 项失败 | 失败项独立复跑仍失败。定位记录见下文 |
| Java API | 首轮 39 项中 36 项通过。余下 3 项在提供本地一次性引导配置后独立通过 | 授权隔离测试使用本地 fixture。真实多租户并发和 MCP 仍待验证 |
| Cline Native Server | 146 项通过，13 项跳过 | 跳过项仍需在对应的 SDK、容器和权限环境执行 |

复现命令参见 [独立网关 Mock 与测试入口](../../../../llm-compatibility-gateway/tests/extreme-baseline.mjs)。逐项结果见 [44 个网关模拟样本](gateway-extreme.json)、[实际等待时长](gateway-wall-clock.json)、[并发与分片节奏](gateway-load.json) 和 [Cline Native 本地测试日志](cline-native-test.txt)。

## 初步发现与证据

| 观察 | 触发方式与运行证据 | 代码位置及影响 | 当前判断 |
| --- | --- | --- | --- |
| 空 SSE 流产生正常结束 | 上游只发送 `data: [DONE]`。网关返回 200 并转发结束标记 | [proxy.ts](../../../../llm-compatibility-gateway/server/proxy.ts#L210) 对标准结束标记允许没有任何 choice 完成。影响依赖该标记判断模型响应是否完结的消费者 | 网关行为已确认。系统任务能否误判为成功须走 Cline 和报告准入验证。初步高风险 |
| 心跳使诊断首包计时提前停止 | 同一 300ms 预算下，600ms 首内容无心跳时返回 504，有心跳的场景约 659ms 后成功 | [proxy.ts](../../../../llm-compatibility-gateway/server/proxy.ts#L210) 在识别事件内容之前清除计时。诊断首包等待与首有效输出存在语义差异 | 已复现。需在服务器区分首字节、首 SSE 帧和首有效输出。初步中风险 |
| 普通和 Runtime 管理转发对静默上游依赖调用方取消 | 两种模式的静默请求都在独立测试调用方超时后退出，随后转发计数归零 | [proxy.ts](../../../../llm-compatibility-gateway/server/proxy.ts#L161) 仅诊断显式传入超时，[upstream.ts](../../../../llm-compatibility-gateway/adapters/upstream.ts#L19) 默认首包及空闲为零 | 已确认网关层边界。真实任务端到端期限与普通接入方行为待测。初步高风险 |
| 工具响应质量由下游继续判定 | 缺失 ID、参数 JSON 截断、工具名未知三种流均由网关转发。声明不支持工具时请求在网关返回 400，未触达 Mock | [proxy.ts](../../../../llm-compatibility-gateway/server/proxy.ts#L21) 的能力门禁和转发行为。需要查证 Runtime 执行层能否拒绝错误工具参数、未知工具及重复调用 | 转发行为已确认，实际工具是否执行待测 |
| Harness 恢复回归无法验证预期分支 | `fullHarnessReplaysSavedModelTurnAndRestoresToolEvidenceAfterCancellation` 首轮及独立复跑均在第 207 行报告 `Expecting code to raise a throwable` | [HarnessToolRecoveryTest.java](../../../../chatchat-agents/src/test/java/com/chatchat/agents/orchestration/analysis/graph/HarnessToolRecoveryTest.java#L198) 模拟 `chat(String)`，而 [ModelNativeAnalysisHarness.java](../../../../chatchat-agents/src/main/java/com/chatchat/agents/orchestration/analysis/graph/ModelNativeAnalysisHarness.java#L155) 默认调用 `chat(ChatRequest)` | 已确认测试替身未覆盖默认调用。真实取消与恢复行为仍待端到端验证 |
| 现场的 60 秒断点和模板查询后结束 | 现场截图显示 `first_packet_timeout` 和约 60,000ms，以及独立的 404、502。InnoDB 截图中只看到模板查询 | 验证服务器在线网关修订为 `f2f9b6beb`，健康状态 healthy。当前基线为 `313f35664` | 现场截图与源码基线属于不同版本。原请求的模型、网关、副作用工具回执和版本映射尚未齐备，根因未归档 |

模拟中，10 个上游 HTTP 状态均原样归类，JSON 缺失与 SSE 中断样本返回错误，UTF-8 跨分片样本完成，负数 usage 保持 UNKNOWN。测试不执行模型生成的命令。

## 服务器与磁盘

| 挂载 | 初始使用率 | 可用 | inode 使用率 |
| --- | ---: | ---: | ---: |
| `/` | 85% | 约 7.1 GiB | 5% |
| `/opt`，共享 Docker 数据目录所在挂载 | 92% | 约 16 GiB | 7% |
| `/mnt/disk01` | 26% | 约 75 GiB | 1% |

验证机 48 个 CPU，约 62 GiB 内存，首次详细采样可用内存约 32 GiB，Swap 已用约 2.4 GiB。指定代理访问 npm registry 返回 200，访问 Docker registry V2 返回预期认证挑战 401。独立构建目录为 `/mnt/disk01/extreme-stability-313f35664`。源码从 Git 提交归档并校验 SHA-256。共享 Docker 的镜像、卷、日志与 BuildKit 缓存保留原样。共享 Docker 的可回收估算不代表可以删除，已有停止容器含回滚与故障证据。

服务器以 JDK 17.0.5 和 Maven 3.8.9 源码编译 API、MCP 及其依赖共 17 个模块成功。编译运行 2 分 54 秒。随后分别构建两套前端、执行资源校验并打包两个 JAR。网关宿主机源码构建也通过，运行 Node 24.22.3 和 npm 11.19.1。 [Java 编译记录](server-java-compile.txt) · [Java 打包及校验和](server-java-package.txt) · [网关宿主机构建记录](server-gateway-host-build.txt)

网关首次完整拉取基础层停滞。随后复用与 Docker Hub 清单和每层 DiffID 完全一致的 Node 基础层，用指定代理完成依赖安装，按当前提交的 Dockerfile 生成 `chatchat-gateway-baseline:313f35664`。镜像 ID 为 `sha256:020a2a637e804ef81054d07044170e91af30a2228031cecd1cba7ab9c6983efd`，本地镜像没有 registry RepoDigest。构建实际运行了全部 30 项 Vitest，含兼容性测试。隔离容器里的 HTTP 测试复跑通过。 [镜像构建日志](server-gateway-image-build.txt) · [镜像 HTTP 验证](server-gateway-image-smoke.txt)

新镜像使用验证环境已有配置修订 13，对 `Qwen3.8-27B` 和 `Qwen3.8-27B-Framework` 各发送一条普通 SSE 探针。两个路径均返回 HTTP 200、`finish_reason=stop`、`[DONE]` 与预期正文，分别耗时 895 和 893 毫秒。仅验证短提示词链路，复杂任务、低算力下的模型能力与重复成功率待测。 [真实模型探针记录](server-gateway-real-model.txt)

两次清理均只移除本轮用于基础层校验的临时归档，共释放约 1.03 GiB。校验后的基础层、源码快照和构建证据仍保留。每次清理后原 API 和网关均为 healthy。 [Node 临时归档清理](server-scoped-cleanup.txt) · [Java 临时归档清理](server-scoped-java-cleanup.txt)

现运行的业务验收数据库中，API 库约 4.1 GB，前三大的表均存放历史证据或事件。完整业务联机前须使用隔离状态和许可的业务资源，避免把该库的历史任务和日志写入共享挂载，或把上一版的执行结果纳入当前基线。

MCP 已构建为 `chatchat-mcp-baseline:313f35664`，镜像 ID 为 `sha256:4469ad11a2c146477668733f3fd56f6377511e5125ec964fd90fbc867d8e1fa4`。API 首次镜像构建在经代理下载 61.2 MB 的中文字体包时达到本轮 600 秒构建时限。服务器已启动一次有界重试，保留既有 Dockerfile 和所有依赖，生成镜像后仅执行断网 H2 启动检查。重试日志为 `java-images-retry.log`，启动检查日志为 `java-image-smoke-final.log`。此时共享 `/opt` 为 92%，独立构建盘为 32%。

## 真实业务与持续运行覆盖

| 场景 | 必备验收证据 | 当前状态 |
| --- | --- | --- |
| 5.1 账户投资行为 | 五类授权数据、账务口径、已实现和未实现收益、完整报告 | 待新基线 |
| 5.2 选股能力 | 同一持有区间的复权行情、市场及行业基准，外部查询仅用证券标识与日期 | 待新基线 |
| 5.3 择时和持仓 | 与前两轮同会话，买卖及持有期拆解、Python 计算、图表与证据关联 | 待新基线 |
| 6 Docker | 镜像与容器状态、资源指标、采样时间、缺失指标 | 待新基线 |
| 7 InnoDB | 授权目标、模板参数与实际查询回执、锁和 I/O 数据 | 待新基线。历史证据仅供比对 |
| 8 LiveBOS | 可核验的步骤、报表类型、样例入口与引用 | 待新基线 |
| 9 A 股十标的 | 筛选规则、行情与财报基准、风险调整收益及背离证据 | 待新基线 |
| 10 白酒五公司 | 三年可比报表、竞争格局变化、估值依据与引用 | 待新基线 |
| 11 YARN | JMX/API 采样、节点和队列分解、应用状态与瓶颈 | 待新基线 |
| 持续与故障注入 | 48 小时采样、租户与任务边界、容器异常与恢复、持久化及资源曲线 | 待新基线 |

完整代码审查和统一整改方案在极端模拟与真实业务证据齐备后进行。本记录仅收集当前观察和未测项，不作为稳定性验收或整改实施依据。
