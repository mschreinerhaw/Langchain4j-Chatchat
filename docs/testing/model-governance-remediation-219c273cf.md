# 模型调用治理候选版验证记录

基线为 `313f35664afa1983326ee7e8fec9fb8a71d96673`。候选源码为 `219c273cf267fff0a8d1c0a072b2a41b551baf5b`，分支为 `fix/model-governance-stability-313f35664`。验证日期为 2026-11-28，时间采用 Asia/Shanghai。

## Java 调用路径与处理依据

| 入口 | 触发与用途 | 候选处理及验证方式 |
| --- | --- | --- |
| `InteractionWorkflowCoordinator` → `ProblemAnalysisPlanner.classify` | 找出需要读业务数据的请求；一次分类失败可阻断全部取数 | 显式选定工具、授权无缺口且能够编译固定流程时直接进入 Runtime。普通自然语言仍保留分类，以保留助手回答说明类问题的能力 |
| `ProblemAnalysisPlanner.analyze`、`analyzeFiles`、`analyzeSqlInput` | 依据实际输入为资料、SQL 和工作流路由；调用失败可阻断该路径 | 保留现有路由和澄清逻辑。审查需识别可由结构化输入直接路由的路径，按真实事件测量频率和影响 |
| `AgentOrchestrationEngine` 的资产结果确认 | 资产目录返回后决定目标。基线 InnoDB 场景曾进入 Cline 资产确认 | 优先复用已由目录确认的授权身份，或通过已存在的规范名称匹配器唯一确认目标。其他情况下继续由 Cline 处理，保持歧义与权限检查 |
| `PublishedDataAcquisitionPlanner`、`InterpretationPlanRuntime` | 编译和执行已发布的授权模板；固定工作流由程序确定 | 将过滤线索放进 `filters`，按发布范围确定资产类型。MCP 仍执行实际 schema、模板及授权校验 |
| `AgentOrchestrationEngine.admitTemplateDiscovery` → `ClineTemplateParameterPort` | 有参数或需语义选择时，将受控候选交给 Cline，返回结构化协议 | 复用现有批量参数接口和 Java 校验。固定发布中无参数模板继续走程序准入，正式业务验证需核对运行中是否有重复参数阶段 |
| `QuotaGovernedDirectModelInvocation` → `ConfigurableChatModelFactory` | Java 普通及 Runtime 辅助调用的授权、额度和请求运输 | 去掉辅助文本路径本地固定 30 秒上限，沿用模型配置和已有 Runtime 时间预算。记录脱敏开始和终态事件 |
| 会话摘要、图像理解、通知策略和格式化、经验学习、知识及技能生成 | 跨会话压缩、视觉输入、策略或语义内容 | 本候选仅应用共用调用治理，保留各场景语义。仍需逐项量化实际调用、重试、Token 和失败影响 |

上述为本候选涉及的路径和审查范围。Java、Cline 与 Gateway 的请求或回执只有分层关联后才能统计实际调用次数，单元测试不能证明联机 Token 或耗时改善。

[Java 静态调用位置索引](evidence/model-governance-219c273cf/java-model-call-sites.txt) 包含 76 个候选位置，覆盖直接调用、包装器及常用客户端入口。由配置和动态分派形成的运行路径仍需用任务事件逐个对照。

## 配置、兼容与回滚

- Gateway 新建映射默认 5 分钟。旧修订缺省字段保留原普通请求无 Gateway 主动总时限的行为。显式写入正整数或 `-1` 后，从后续请求开始生效。修订回滚仍可恢复原配置。
- Java 保留既有数据库秒字段及 300 秒默认值，配置页按分钟显示。聊天模型支持 `-1`，向量模型保持正数校验。Java 正数上限沿用原有 1200 秒限制。OpenAI 客户端在无限档显式抑制 SDK 的隐式读超时，连接保护和任务预算仍独立。历史秒级值按原值保留。
- Gateway 已有请求事件增加模型超时快照、请求尺寸、消息和工具数量、上游响应头与首个 SSE 事件耗时。Java 共用调用入口追加轻量事件，复用现有任务事件存储。常规记录不持久化提示词或响应文本。
- 候选环境使用独立 PostgreSQL 数据库、对象桶、索引名、任务队列和容器。基线 Python 与 Cline 镜像版本保持可回滚。候选 API 的运行层继承已验基线字体、操作系统以及 Python 环境，替换其中由服务器源码重新打包的应用。

## 自动化与隔离部署

- 本地 Java 五个针对性测试类 41 项通过。Gateway 协议回归 30 项、Gateway HTTP 集成 1 项及模型管理页面 5 项通过。另一次早期运行验证了 Runtime 计划测试和现有事件映射测试。
- 部署采用指定代理 `http://192.168.195.225:18118`。服务器从提交导出的构建树编译 Java、构建 Vue 并构建 Gateway、API 和 MCP 镜像。构建树 SHA-256 为 `49aba59584f7219546005d3ac81f5be4ce54636f9fcbdf643a76faea19743282`。
- 构建前共享 `/opt` 使用率 92%、可用约 16 GiB，独立盘使用率 41%、可用约 60 GiB。构建后共享 `/opt` 仍为 92%，独立盘约 44%、可用约 57 GiB。容器存储、源码、缓存和日志使用 `/mnt/disk01`。
- Gateway 初次启动缺少 TLS 引导配置，保留日志。改在候选实例显式启用自签名引导，并将候选证书加入独立信任库。候选 API、MCP、Gateway、Broker 和恢复服务均已启动。API 启动后通过登录及正式任务受理验证。
- 首轮候选数据库来自原始授权目录快照，缺少后来加入的两项真实 Gateway 模型映射。九项任务均在分类阶段终止。已从原隔离验证数据库只读复制这两项已授权映射，变更仅落在候选数据库。保留首轮记录，重新验证 Docker 和 InnoDB 两条实际模型链路。

| 候选镜像 | 验证服务器的不可变本地镜像 ID |
| --- | --- |
| Gateway | `sha256:fc8ac6562b9cf1081445bd44fad9e46037aac53cc0e6ef97fa82fd3cd67b418b` |
| API | `sha256:b413ea75367cb6dd50cc344c2060a16e482514b4d971796098d8026be6166884` |
| MCP | `sha256:a24683addb98970d7ebc11030ebbe8bdae7c6ed92e9fd3642d9e50f7e3cab74b` |

镜像均标记候选提交，未发布到镜像仓库。运行中候选 API 的 `fc-list :lang=zh` 已识别 Noto Sans CJK SC。Gateway 网络回归在上述候选镜像中执行，真实业务任务使用单独启动的候选部署。

| Gateway 镜像实测 | 观察 |
| --- | --- |
| 自定义 1 分钟 | 首输出配置 59 秒的请求在 59.313 秒成功。60 和 61 秒两项均在约 60.117 秒返回 504 |
| 持续纯心跳 | HTTP 已开始，随后在约 60.118 秒收到 SSE 超时错误，无正常结束标记。监控记录首个 SSE 事件，首个有效输出字段为空，终止组件为 Gateway |
| `-1` | 90、120、180 秒档均成功。取消用例确认调用连接关闭传递到上游 |
| 默认 5 分钟 | 298 秒档于 298.299 秒完成。302 秒档在 300.211 秒返回 504 |

原有 44 个网关异常样本已在服务器的候选镜像复跑，样本结束后 `inFlightAfter` 都回到 0。单独的字节边界测试按顺序返回 200、200、413、413、413、200，三条超限请求均未转发。该结果仅验证 Gateway 入口保护和后续新请求可用。

- [实际超时与取消测试日志](evidence/model-governance-219c273cf/gateway-deadlines.tap)
- [44 个异常样本](evidence/model-governance-219c273cf/gateway-44-samples.json)
- [请求体限制回归](evidence/model-governance-219c273cf/gateway-request-limit.json)

## InnoDB 正式链路复跑

任务 `9bcf0105-7a6b-3b89-a549-caffc79f6490` 于 11:24:41.545 受理。11:24:58.966，`mysql_db_status_template_query` 返回真实工具结果。调用入参键只有 `filters`、`filtersSchemaVersion` 与 `trace`。后续执行 `sql_query_execute` 批次，运行事件明确记录其成功。基线同一业务请求从受理到模板失败约 82.3 秒，候选从受理到模板成功约 17.4 秒。两者均使用真实 `qwen3.7-plus`，此比较只覆盖截至模板结果的阶段。

从授权任务事件接口已读取到 Java 分类调用的开始和完成事件，关联同一请求 ID，耗时 8.605 秒，上游回执合计 1,000 Token。候选的业务链路仍保留一次自然语言分类。

Cline 接收的输入清单含 19 个文件，总计 79,107 字节。逐个核对物化文件大小与 SHA-256，19 项匹配。9 个 JSONL 文件中有 8 个非空，合计 554 个非空记录，含任务输入和数据记录。单次检查确认实质数据交付与文件完整性。

基线的两个 Cline Worker 通过输入清单的同一 Runtime 尝试 ID 对齐。首次资产确认 Worker 有 10 个模型回执、102,594 Token，主分析 Worker 有 29 个回执、778,183 Token，合计 39 次、880,777 Token。候选没有资产确认专用 Worker。主分析一个 Worker 共 83 个模型回执、2,346,241 Token。基线缺业务指标并失败，候选取得数据但未完成正式交付，两者产出不等价。**首次供数路径的改进已取证，全任务成本与交付验收未通过。**

候选 Worker 工作区出现了 HTML，未观察到正式封存、对象上传或 Runtime 校验回执。达到本次测试预设的 30 分钟观察上限时，使用正式取消入口终止该隔离任务。11:54:47 任务为 `CANCELLED`，11:54:50 记录 `worker.stopped`。该 Worker 的 `events.jsonl` 最终采样为 5,621,691 字节、2,190 条。取消是测试操作，不能视为系统自身在 30 分钟终止。其后正式受理 Docker 复跑。

对照源码发现，`NativeSiloTaskService.capture` 要求普通 Native 分析任务的模型、工具和代码执行次数配置为 `-1`。`NativeS3CodingAdapter.binding` 的签名授权中，有限次数与 Token 的 `studioBudget` 仅下发给 Studio 路径，普通路径标记预算治理待处理。实际长任务与这一配置一致。后续需验证普通分析的预算入账、终止和恢复语义，并使用真实产物质量确定限额，避免把 Studio 默认值直接套给分析任务。

Docker 复跑任务 `e9df1a2c-f159-3437-a955-ff230ab6d6e5` 经正式入口受理。模板搜索调用入参为 `trace` 与 `filters`，MCP 工具在 518 毫秒内成功返回，继续进入 `linux_command_execute` 授权批次。Broker 已记录输入校验和 Worker 就绪。授权任务事件已确认 Java 分类及 `agent-model` 各一次，合计 7,200 Token。该场景仍需量化语义规划开销。

核对 Docker 复跑的 17 个输入文件，文件大小及 SHA-256 均匹配。报告没有正式交付。鉴于 InnoDB 已观察到长任务放大，本轮在 Docker 获得取数证据后增加测试用量保护。累计回执超过 150 万 Token 时，经正式接口执行取消。取消前观察 48 次、1,535,172 Token，收尾记录为 49 次、1,575,832 Token。12:08:49 任务进入 `CANCELLED`，12:08:53 Worker 退出。基线同场景的主分析 Worker 为 11 次、215,237 Token，基线模板调用失败，整任务结果与候选不可等价比较。

- [候选及基线运行证据](evidence/model-governance-219c273cf/candidate-runtime.json)
- [授权任务接口的 Java 模型事件](evidence/model-governance-219c273cf/java-model-events.json)
- [定向空间清理记录](evidence/model-governance-219c273cf/disk-cleanup.log)

仅清理已被构建树替代、无引用和无打开句柄的候选全量源码包，核验绝对路径、文件大小和 SHA-256 后释放约 202 MiB。保留基线和候选镜像、实际构建树及测试证据。共享 `/opt` 保持 92%。

## 当前验收范围

实际测试验证了模板取数、输入完整性、Gateway 边界和取消操作。原先绑定的三类模型 `qwen-3.6-open-plus`、`qwen3.6-plus` 与 `hy3` 缺少已授权路由，证券、LiveBOS、市场研究和 YARN 共七项停在分类阶段。

完整 Java、Cline 和 Gateway 的统一诊断、真实业务报告质量、全链路模型减量、业务链路 413 重试、Worker 检查点恢复及跨组件故障矩阵尚未验收。两项实际报告路径均触发了测试人员取消，48 小时稳定性验收没有启动。候选版保留在隔离环境。
