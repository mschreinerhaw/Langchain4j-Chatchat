# Cline 数据分析第三阶段验收

**结论：FAIL。当前证据不足以提交生产发布申请。** 原生整包及隔离容器的模型请求边界已验证。原始 LiveData 快照已核对，真实供应商复测和自主分析交付尚未完成，文件用途识别还有可复现的缺口。

## 版本和构建

- 验收分支 `fix/cline-analysis-tool-results`，任务书基线 `ba8e89e47`，开工版本 `ed9f48f8c3fc2f928889533c6375ae235225f092`，构建修复提交 `8dca34a818bc2425b1493a32c679daf77a556d08`。后续验收记录和探针无运行时改动。
- 本地 Docker Desktop Linux `29.6.2` 使用独立镜像标签和临时容器。原生模型请求探针使用 `--network none`，容器内的 HTTP 端点返回脚本化的模型响应。历史服务器隔离项目只有 API/MCP Compose 定义，检查时没有运行中的隔离容器。原始快照只在现有服务器上做了只读核对。
- Cline `4.1.27` 固定上游 `8075d2e35cf5cad67c24f180c80a05d311b74a3e`。原生构建通过上游类型检查、lint、standalone 打包和宿主模块验证。运行时校验清单有 1,849 个文件，ProtoBus 描述符有 22 个服务。

| 产物 | SHA-256 或本地镜像 ID |
| --- | --- |
| Java `chatchat-api-1.0.0-SNAPSHOT.jar` | `e3d27fac59104c6a791f3354eae75df6022420533751134ffd39f8862f4d6d24` |
| Java `chatchat-api-1.0.0-SNAPSHOT-release.zip` | `af1067a29314ea56f55c0122dcd8289000878228daf629cf81cf9d62b6931674` |
| `cline-agent-server:analysis-acceptance-8dca34a8` | `sha256:b39b795fc14af472a69ac1d482cdeee05c42c7609fc593b1bfef3f0b73df42d0` |
| `chatchat-python-runtime:analysis-acceptance-ed9f48f8` | `sha256:04943f15896169881ae85855ae35b7ee9a73e2ebab038ea3ea334302ee9a4009` |
| `runtime-os-coding-node:analysis-acceptance-8dca34a8` | `sha256:e035abcd0567b7133f37f0c4167cb5164c66480d0d7c965178d63f0c13371326` |
| `runtime-os-native-worker:analysis-acceptance-8dca34a8` | `sha256:34ba9ed7df71e534427438534ef6ac7a6b629bfe9fdbb581d79caf93c5f1ed47` |

Coding Agent 镜像校验了 SDK `0.0.93`、镜像内 Skill ID 和两个原生 Skill 工具，隔离容器的健康探针返回成功。Java 完成 Maven reactor 打包，Maven 使用现有本地工作区。现有隔离环境缺少完整 Broker、Executor、Worker、Java 和真实供应商路由的部署验收证据。

## 本阶段修复

| 现场问题 | 修复与证据 |
| --- | --- |
| 原生构建失败 | `cline-agent-server/scripts/analysis-tool-results.mjs` 中未使用的 `dirname` 触发上游 lint。删除后整包构建通过。 |
| Coding Agent 构建失败 | `coding-agent-node/Dockerfile` 的数量判定仍要求 17 个 Skill。改为核对镜像内声明的完整 Skill ID 集合，同时要求分析 Skill 及两个原生工具。镜像重建和健康探针通过。 |
| 首个归档可以超出 32 MiB | 原有总量检查只在遍历已有文件时运行，空目录跳过了判断。归档前校验单次输入字节数。测试覆盖空目录首条超限、512 文件上限、校验不匹配及回执不含原始标记。 |

## 实际模型 HTTP 请求与平台测试

| 检查 | 结果 |
| --- | --- |
| Native Standalone HTTP，分析任务 | 容器启动、Session 创建、读取受保护数据、模型请求和取消均已观察到。工具回执 662 字符，未出现尾部数据标记。 |
| Native Standalone HTTP，普通编码任务 | 同一镜像运行，模型收到 16,562 字符和尾部标记，取消成功。范围为读取场景，仍需验证编辑、补丁、搜索、Shell 和自主修复的完整业务链。 |
| 上游 SDK 回环请求 | 分析请求 5,166 字节，编码请求 384,209 字节。已检查实际 HTTP 请求中的工具关联、隔离、取消和内存内恢复。两种数字都是合成请求体字节。 |
| Linux Cline Agent 测试 | 151 项，150 通过，1 跳过。跳过项为需要固定上游源码路径的 SDK 回环请求测试，该项已在本机另行执行并通过。 |
| Linux 平台专项 | 14 通过、零跳过，覆盖 Windows 跳过的 Skill 目录、S3 Unix socket、绑定目录访问和初始化。 |
| Java 定向测试 | `NativeAnalysisBridgeTest`、`CodingNativeAnalysisObjectiveTest`、`NativeS3CodingAdapterTest` 合计 22 项，21 通过，1 项因未配置 `coding.sqlglotPython` 跳过。 |
| Python 确定性 fixture | 4 项通过。CSV、Parquet、Excel、JSONL 核对完整计算，JSONL 最大 1,000 万行。长文本首、中、尾证据均可定位。另覆盖空数据、坏行和快照不匹配。 |
| 模型流和恢复专项 | Linux 14 项通过，包含 HTTP 200 后断流归类、分片 SSE、缺失用量、恢复后对账、取消回执及命令证据保留。测试以事件/本地服务为主，尚未完成供应商连接、响应头超时、200 无首个增量、空闲超时和下游断开场景的全链注入。 |

Standalone 探针直接使用隔离进程的受控配置打开策略。Java 任务签发、授权与 Worker 上送策略由定向测试覆盖，仍需在同一个真实任务中串联验收。Native 返回的结果归档与 Silo 原始快照是两套证据，归档回执中的 `originalSourceCompleteness` 保留 `NOT_ASSERTED`。已验证归档重复内容复用引用和异常时的拒绝回退。尚未验收生产等价服务重启后可下载的持久化证据。

执行探针可使用仓库内的 `cline-agent-server/test/analysis-standalone-smoke.mjs`。将它只读挂载到原生候选镜像的 `/tmp/worker-smoke.mjs`，以 `--network none --entrypoint node` 分别运行 `analysis` 和 `coding` 参数。报表中只记录脱敏的计数、标记布尔值和哈希。

## 原始 LiveData 与业务交付

只读核对 2026-11-23 的 InnoDB 分析父任务 `31497979-75d3-338e-bb90-8c1462a2e296` 和分析 Worker `92d206c7-5c08-489c-9083-c1ada12009d3`。冻结的 9 份 JSONL 全部可逐行解析，行数按源顺序为 `[6, 19, 1, 0, 10, 88, 359, 20, 50]`，共 553 行、67,163 字节。按源顺序对 `相对路径 + 空格 + 文件SHA-256 + LF` 计算清单摘要，得到 `1a14963f48ee1f576da1da9bbfafe76002350fd378d94c2bea232a799752be889`。`data-supply-manifest.json` SHA-256 为 `8d226ccb1f7044edb434623bb01676742f95ab8c316c262cab3c8832ce5b9cc6`。仓库没有写入业务原文或连接凭据。

| 供应商指标 | 历史 LiveData | 相同快照和模型的整改版 |
| --- | ---: | --- |
| 模型 | qwen3.7-plus | 待执行 |
| 请求 / 有用量回执 | 22 / 21 | 待执行 |
| 供应商累计输入 Token | 851,021 | 待执行 |
| 缓存读取 Token | 723,072 | 待执行 |
| 已确认输入与缓存读取的差额 | 127,949 | 待执行 |
| 累计输出 Token | 20,702 | 待执行 |
| 工具结果 / JSONL 回执 Token 估算 | 约 530,000 / 400,000 | 待执行 |
| 累计模型请求耗时 / 异常请求 | 762.234 秒 / 331.135 秒 | 待执行 |
| 端到端 | 分析 Worker 24 分 40 秒后取消，Java 验收 `NOT_RUN` | 待执行 |
| 冷热缓存控制、供应商实际费用、Python / Hook 耗时、成功率 | 缺少可比较的实测记录 | 待执行 |

一笔历史失败请求未提供 Token 用量，已确认的累计值未包含该请求。缓存读取是输入 Token 的子集。70% 降幅对应累计输入不超过约 255,306 Token，仍需基于可复现的真实供应商回执判定。历史任务没有成功报告供对照，复测需独立核对 InnoDB 结论及证据。

隔离环境尚未取得可用的 `qwen3.7-plus` 验收路由。LiveData、证券账户、跨数据集关联、图文报告、多格式业务交付均未完成 Cline 端到端验证。字段缺失、日期格式、类型冲突、关联键不一致、缺依赖、JSONL 解析、路径错误和空输入也尚无模型自主修复证据。监控中的报告下载、图表展示、证据跨任务隔离和失败状态仍需用实际登录身份复测。

## 已复现的剩余边界与发布条件

- 用合成文件直接调用 Hook，原始 `input` 目录中的数据被拒绝，复制到非受保护目录后的相同数据能进入 `read_files` 原生回执。该场景涉及来源与用途识别，仍需针对分析任务修复并复验正常代码读取。
- 大型已授权数据清单通过 `beforeTool` 时未检查字节预算。小型 MCP 结果重复调用 8 次时每次保留原生回执。实际长任务中二者的累积 Token 影响待量化。现有 4,096 字节摘要限制、超限数据转产物交付及多章节报告是否匹配，仍需用真实报告验证。
- 全链验收使用同一冻结快照、相同模型及参数，分别对原版本与候选版做多次对照。若供应商无法强制冷缓存，记录每次缓存读量和测试顺序，并保留供应商原始回执的计数及完整性标签。以确定性参考计算核对核心指标、关联键基数和证据位置，再决定是否提交发布申请。
- 生产配置、服务和发布指针保留在原有版本。本候选通过真实验收及审批后才进入发布，由同一批经 SHA-256 核验的构建产物进行部署。保留现网的 `33d00126230e274be14b8a0990b92ea2543efe2f` 发布目录、配套镜像与原发布配置。若健康、权限、交付或模型请求边界校验失败，按原部署回滚流程恢复整套旧版本，复查入口健康、任务接受、产物读取及运行状态。该回滚方案目前未执行生产演练。
