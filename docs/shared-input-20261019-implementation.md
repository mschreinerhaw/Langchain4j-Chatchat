# Runtime OS → Cline：共享目录与类型化输入

基线：`main@1fae5a58`。本次扩展现有 Coding Analysis v3、Snapshot、MCP Python 控制面、持久化事件及 Vue 3/TypeScript 监控；不新增任务系统。默认关闭新输入格式，启用后优先 Parquet，也可明确配置 CSV。输出仍为既有 `rows.jsonl`，不改变结果契约。

## 能力与改动

| 现有能力 | 本次补齐 | 主要文件 | 验证 |
| --- | --- | --- | --- |
| 不可变 JSONL/分片快照与任务授权 | 类型化 Manifest、Schema、Parquet/CSV 分片、同目录原子发布 | `CodingInputManifest`、`CodingSharedFiles`、`CodingInputFilePublisher`、`CodingTableCodec` | JVM 往返、并发发布、真实 Linux 文件系统与 Python 读取 |
| Python 数据上传及操作级冻结 | 仅登记授权共享文件，不再跨服务上传数据字节 | `McpPythonControlPlanePort/Client`、`PythonDataFileService`、`PythonAdminController` | 开关、租户/用户、哈希、大小及操作级副本测试 |
| Coding v3 配置快照与签名回调 | 固定输入格式、能力协商、首次模型调用前受控读取 Schema 元数据 | `CodingRuntimeContext`、`ClineCodingAdapter`、`ClineModelCallbackController`、Node `runner/server` | 旧契约兼容、恢复重协商、过期租约拒绝、准备阶段取消 |
| 独立执行产物校验 | 按输入格式重建原始逻辑行，继续拦截原始记录复制 | `CodingInputRows`、`ComputedResultBoundary`、证据与财务校验器 | 两种格式的原始记录/文档复制拒绝及聚合允许测试 |
| 持久化任务/代码/事件页面 | 输入版本、格式、文件/哈希、Schema/质量及实际操作冻结状态 | `CodingTaskMonitorController`、`codingMonitor.ts`、`CodingTaskMonitor.vue` | 16 项前端测试、Vite 构建，另行记录部署页面验收 |

## 调用链与目录

1. Java 从本轮已授权的 `DatasetHandle` 捕获数据，保持原有 1–8 个数据集约束，不自行检索额外来源。
2. 在已有 MCP Python data root 下创建按 tenant/owner 隔离的暂存区，流式统计字段和质量、写入分片及 Schema。
3. 文件完整写入并刷盘后原子发布；登记 MCP 时只传 `fileId/fileName/sha256/size`。清单最后发布，持久化快照证据提交后才能授权 Coding Task。
4. 任务配置快照携带清单 ID/哈希/格式；Node 在任务提交和恢复时声明格式能力。首次模型调用前，由现有签名和 fencing 校验的 Java 回调返回有限 Schema 元数据。
5. Cline 生成读取 `/data/input/{fileId}/{fileName}` 的 Python，经现有 Java 校验及 Sandbox 控制面执行。MCP 继续生成操作级不可变只读输入副本，不绕过既有执行冻结与恢复机制。
6. Java 独立校验产物与原始来源边界，完成原有结果登记、语义审查和终态更新。代码生成不代表任务成功。

共享目录仅挂载至 Java API 与 MCP；Node 不获得数据根目录、业务凭证或 Docker Socket。Cline 获得结构化元数据、授权引用及受控执行回执，全量业务数据不进入 Prompt。

物理存储复用 `base64url(tenant)/base64url(owner)/uploads/{fileId}/{fileName}`，物理路径不进入公共契约。所有目录层级拒绝符号链接；发布后的同 ID 内容不可覆盖，重放必须匹配大小与 SHA-256。暂存数据失败不产生授权清单。未引用的已发布分片保留供运维核对，本次不新增按时间盲删的清理器。

附件中的目录是逻辑布局，本实现对应现有机制如下，避免复制第二套任务状态或代码历史：

| 逻辑区域 | 实际复用 |
| --- | --- |
| `input/manifest.json`、模板 Schema/数据 | 既有 owner 隔离文件 ID；操作级只读冻结副本；Parquet 默认或 CSV 单一规范格式 |
| `task-spec.json` | 持久化 Coding Analysis v3 任务契约及有效配置快照，通过任务 API 交付 |
| `coding/`、代码清单与 revisions | 已有按 task/operation 关联的不可变代码产物、哈希和版本索引 |
| `execution/`、stdout/stderr | MCP 的既有操作归档与可验证停止回执；Node 仅接收受控诊断元数据 |
| `artifacts/` | Java Evidence、输入版本关联及正式结果登记 |
| `events/` | 现有持久化事件日志与按 sequence 分页恢复接口 |

`files[].virtualPath` 由服务端从校验过的 ID 和文件名计算，是 Sandbox 固定挂载下的虚拟路径；不接受调用者提交任意绝对路径。Node 经签名回调读取有限清单与 Schema，业务数据文件仅在执行侧读取。任务同时不导出两份规范数据源；格式在任务创建时冻结。字段单位、日期或业务含义不靠名称猜测。

## 公共契约

- 新描述符版本：`runtime_os.coding.input.v1`，仅 Coding Analysis v3 可声明；旧 v1/v2 JSON Schema 不扩展。
- `runtimeContext.inputFiles`：`schemaVersion/format/delivery/manifestFileId/manifestSha256`；ID 必须属于任务授权，必须匹配持久化输入证据。
- `authorization.allowedDataRefs` 沿用公共契约的规范排序。授权检查比较完整的规范列表，缺失、多余或重复引用均不接受；数据分片读取顺序仍由 Manifest 决定。实测发现旧校验直接比较权限列表与清单顺序，修正后增加排列及拒绝测试，同时覆盖 Parquet/CSV 的真实文件授权。
- Manifest：来源引用、Schema 文件 ID、逻辑内容哈希、总行数/字节数、字段类型/精度/缺失/空值统计、按序文件 ID/名称/哈希/行数/大小。
- MCP：`GET /api/v1/python/input-capabilities`，`POST /api/v1/python/data-files/{fileId}/shared`；沿用既有控制面认证与 tenant/owner 作用域。
- Node：受认证的 `POST /v1/input-capabilities`；不支持或未知格式在模型分派前拒绝。
- Java：`POST /internal/coding/cline/input-context`，复用签名、任务契约哈希与当前租约；最多 180000 UTF-8 字节。不是新增模型工具，也不增加数据获取权限。
- 监控：`GET /api/v1/agent/runtime/runs/{runId}/coding/{taskId}/inputs?operationId={id}`；沿用所有者校验。`OPERATION_FROZEN` 仅表示已有持久化操作输入绑定，否则返回 `TASK_LATEST`。
- 任务列表增加可选 `modelReceipts[]`，公共类型为 `CodingModelCallTelemetry`。由原始模型操作、起始 fence 和持久化响应回执关联，只返回实际模型标识、协议、起止时间、耗时、可用 Token 及来源；校验回执内容哈希和任务契约哈希。原始回答、Prompt、工具参数和额外凭证字段不返回浏览器。前端按 attempt/modelCall 合并，原事件不重写；缺少可选字段时保留既有实际事件信息，不拿默认模型补空。

### MCP 尚未受理时的取消

服务器首次验收暴露了构建遗漏 Docker CLI 的问题：Java 已记录 start intent，但 MCP 尚未受理，原取消接口因查不到操作无法提供停止证明。该次失败保留为诊断证据，不计入通过。

本次在既有 MCP 执行日志中补充 `mcp_python_managed_cancellation` 取消记录；它不是新的任务系统。取消和受理通过同一 task fence 行锁排序：取消先提交时，同一作用域/operation 的所有迟到启动永久被拒绝；受理先提交则继续原有执行取消流程。只有已在 Java 任务日志中受理的 operation 才能经 Node 回调创建取消记录。

公共回执版本 `runtime_os.mcp.python_no_start.v1` 绑定 tenant/owner/run/task/operation/fencing token，并同时要求 `stopConfirmed` 和 `dispatchPrevented`。该取消视图的 container/code/image 字段为 null，只提供 receipt/manifest 审计文件，不产生代码、stdout 或分析结果产物。Java 独立验证作用域、哈希及当前持久化取消视图后才接受停止证明；单纯 404 仍然不能证明已停止。

当 stop intent 已持久化、所有模型与执行操作均已核对停止后，Node 会话因重启丢失不再阻塞取消终态；有未知模型或执行的任务继续等待核对。对应并发、重启、跨租户、伪造回执拒绝和恢复门禁测试分别记录。

升级脚本见 `database/migration/{h2,mysql,postgresql}/V20261019_03__mcp_python_unadmitted_cancellation.sql`。测试环境的实际配置为 Hibernate validate，必须先执行增量 DDL。`migrate-shared-input-stage.py` 校验源 SHA-256、严格限定隔离 MCP 数据库、保留原表所有者，并在事务中执行增量迁移；部署脚本随后检查 MCP Docker、HTTP/gRPC 就绪。其他环境也应先执行对应数据库的增量 DDL，并先部署兼容回执读取器，再启用新写入能力。

## 类型与资源边界

- 类型：BOOLEAN、INT64、FLOAT64、DECIMAL、STRING、JSON、NULL。Decimal 最大精度 38，保持精度/标度；不猜测时间、单位或业务含义。冲突类型明确失败。
- Parquet 使用安全存储列名 `cN`，Schema 映射回原始名称；`pN` presence 标记区分缺失与显式 null。Python 使用 PyArrow 或 `pandas.read_parquet(dtype_backend='pyarrow')`。
- CSV 使用 UTF-8、标准引号转义；`\M` 为缺失、`\N` 为 null，字面反斜杠额外转义。读取时 `dtype=str, keep_default_na=False`，再按 Schema 转型。JSON 单元使用 `json.loads(parse_float=Decimal)`。
- 沿用最大 100 万行/256 MiB 的配置上界，256 字段，最多 63 个数据分片加一个 Schema 辅助文件；分片最多 10000 行或约 4 MiB 逻辑数据。MCP 单文件上限继续生效。
- 新格式关闭时保留原 JSONL 字节与哈希语义；既有任务补充数据不会因全局开关变化自动切换格式。新格式任务的后续输入必须匹配其冻结格式。
- 模型、Skills、工具权限仍来自现有任务配置快照；本次没有 Node 独立模型配置或业务规则分支。

## 构建与部署

Node 固定 `24.21.0-bookworm-slim`，Cline SDK 固定 `0.0.93`，使用现有锁文件。Java Parquet 使用 `parquet-hadoop 1.16.0` 与本地文件接口；不启用 Hadoop 网络文件系统。验证容器固定 Python 3.11.16、pandas 3.0.6、PyArrow 26.0.0，完整依赖见测试锁文件。

本地打包使用 `-Dcontainer.image=true` 和独立前端输出目录；API/MCP 包均扫描部署密钥文件。API 使用既有通用 Dockerfile；MCP 使用既有 Dockerfile.mcp 并校验固定 Docker CLI 字节，Node 使用其既有非 root、健康检查和优雅退出镜像。

通用启用配置见 `deploy/docker/application/shared-inputs.compose.yaml`。`CODING_SHARED_INPUT_DIR` 必须指向既有 MCP Python data root，禁止自动创建任意宿主机目录。

隔离测试使用 `scripts/deploy-shared-input-stage.py`：校验归档哈希、核对 Compose 项目、拒绝活动任务/执行容器、保存 root-only 配置备份、只更新 API/MCP/Node 及其代理，并核对其他容器未改变。真实测试结果单独保存，部署脚本返回 `DEPLOYED_PENDING_ACCEPTANCE` 不表示端到端通过。

测试机上的复现命令（归档及脚本先由本地构建后上传，迁移文件必须与 SHA 一致）：

```sh
python3 /opt/runtime-os-docker-stage/evidence/shared-input-20261019/deploy-shared-input-stage.py \
  /opt/runtime-os-docker-stage/images/runtime-os-shared-input-20261019-r9.tar \
  2bf03bc6cd8b6de17b93a1f21e7730f094d395b6e0040c7cd36a870681ad29d7 \
  shared-input-20261019-r9 \
  --migration-sha256 8bc8bb17553d0a4596fcfeb6b4f1b7a4d01b27f0926d5ec0b1ec0b7606170013
docker logs --tail 100 runtime-os-app-stage-api-1
docker logs --tail 100 runtime-os-app-stage-mcp-1
docker logs --tail 100 runtime-os-app-stage-coding-agent-1
```

本地构建沿用 `deploy/docker/application/build-images.ps1 -DockerCliPath <Linux-amd64-Docker-CLI> -Tag <release>`；它校验前端与密钥文件，并为镜像记录 Git 提交/发布标签，Node 的运行时构建版本也使用同一值。该通用流程还构建 News 镜像，但本次部署脚本不替换 News 或基础设施。

本轮实际归档仅含 API/MCP/Node 三个镜像；各镜像配置摘要、JAR、Docker CLI、源文件哈希见 `shared-input-20261019-build.json`。通用构建脚本输出 `runtime-os-app-<release>.tar`，部署时传其实际路径和新计算的 SHA-256；上面的固定哈希只对应本轮已构建归档。首次构建先联网解析 POM 和锁文件依赖，之后可使用脚本的 Maven 离线流程。镜像构建及服务就绪不等于模型与执行验收通过。

回滚使用 `--rollback /opt/runtime-os-docker-stage/evidence/shared-input-20261019/backup-<timestamp>.json`，关闭新格式创建但保留兼容读取器。已有新格式任务持久化后不能直接退回拒绝 `inputFiles` 的旧二进制。备份包含服务端私密配置，不能下载至公开证据目录。

## 验证证据与限制

本轮定向 Java 测试 251 项，0 失败/0 错误，14 项条件测试跳过；新增恢复门禁另运行 22 项，0 失败/0 错误，2 项条件测试跳过（与前述范围有重叠，不累加）；Node 29 项通过；前端 16 项及 Vite 构建通过。真实 Python 容器已读取两种格式的 Java 导出文件，验证大整数、精确 Decimal、嵌套 JSON、缺失与空值；这些属于真实文件/容器验证，不是模型验收。

全量后端基线与最终代码比较见 `shared-input-20261019-backend-comparison.json`：基线 4458 项与最终 4486 项均有 130 个失败、64 个错误、21 个跳过；按失败用例及其重复次数比较，新增失败集合为空。全量运行显式使用测试失败保留选项收集全部报告，Maven BUILD SUCCESS 不代表全量测试通过。历史全量失败及既有 Docker 门禁失败没有被报告为通过，也没有为此放宽安全边界。

中途新增测试的时间戳夹具违反毫秒精度，导致两项测试错误；修复夹具后重新执行定向测试及完整 reactor，最终新增错误为零。服务器早期镜像缺 Docker CLI、以及遗漏 validate 模式前置 DDL，均保留失败记录并修复；服务未就绪时提交的测试不计入通过。

真实模型、取消、重启恢复、部署页面及最终部署状态以同前缀独立证据为准。生产切换仍需处理历史门禁、评审配置及资源容量，并显式授权；本次仅部署隔离测试环境。

### 服务器验收发现的修正

`failed-attempts.json` 保留未通过的实际服务器用例及修正说明，包含验收观察器未触发重启的情况。公开任务契约会排序 `allowedDataRefs`，快照授权校验现按相同顺序比较，测试覆盖所有排列及缺失、额外引用。元数据回调新增每个授权文件的准确 `files[].virtualPath`；所有 file ID 目录在 `/data/input` 下并列，不能嵌套到 manifest 目录。

Node 会话丢失后，只有操作全部对账且最新 Python 失败具备可验证的停止回执，才允许任务进入失败终态；状态未知的模型请求继续保留待对账。定向回归 40 项、0 失败/错误、2 项跳过。上一轮真实失败任务通过正式恢复接口确认预算截止和执行器停止，证据见 `verified-failure-recovery.json`，并未转成成功。

重启验收脚本支持 `--restart-phase executing|validated`：前者必须观察到该任务实际 Sandbox 容器运行，后者必须观察到 Java 返回执行成功和产物校验状态，且 Coding Task 尚未结束；最终还需检查持久化结果引用及结果登记。公开事件会移除产物引用，不能用该字段触发重启。完成后的重启不计入恢复通过。两种时点分别记录，不能相互冒充。

实际强制重启进一步发现：监控 API 的恢复请求 scope 不含原调用 trace，旧证据批次哈希却包含调用时 scope，导致同一份已归档证据重读冲突。H2 回归在修正前以不同 HTTP trace 稳定复现相同错误；修正后在校验 tenant/user/run 归属后使用不可变 `contract.scope()` 创建证据批次。产物、输入、回执哈希和内容变更门禁均保留；跨租户仍拒绝。包含该修正的第二轮定向测试 35 项全部通过，完整后端重新执行并单独比较。失败任务通过正式取消接口确认停止，未编辑数据库状态。

r7 真实重启已恢复 Coding `COMPLETED` 且没有重复执行，但暴露了另一个父子任务衔接问题：主调用遇到待对账状态立即返回，父任务结束后没有再次进入结果语义审查。这不是业务语义拒绝，早期诊断已在失败记录中更正。r8 在原工具预算内定期对账并保留原模型绑定，恢复完成后继续既有语义审查和结果登记；只有预算耗尽且操作仍未知时才返回不可用结果，不制造停止证明。新增“先未知、后恢复完成并登记”回归，并把一直未知的测试明确限制在原预算内；该轮 50 项定向测试全部通过。

验收脚本 `--recovery-driver automatic` 仅观察应用对账；`manual` 才调用既有恢复控制。两种模式明确记录，强制重启的执行次数在重启前后比较，不将代码修正重试计为重启重放。

r8 的真实自动恢复用例已通过：`a38c0f98-e2f3-34b9-b8ca-5c74639ebe68`，Sandbox 执行 1 次，Node 强制重启后应用自行完成对账、语义审查和结果登记，父任务 `SUCCESS/SUCCEEDED`；执行次数未增加。最终页面复核发现 Node 未发出的第二次调用展示事件不能靠事件补全，因此 r9 增加上述只读回执投影，直接恢复既有成功任务的真实模型信息，不重放模型或执行任务。

r9 定向 Java 54 项全部通过；监控相关前端 18 项及构建通过。额外默认前端套件曾在与全量 Maven 并行时发生一次未修改的 SQL 语法加载 5000ms 超时；保留该失败日志，未修改 SQL 代码或超时门限。后端结束后复跑同一默认命令，290 项 UI、61 项 SQL Studio、10 项代码智能测试全部通过；不与定向测试重复累加。

最终 r9 已读取同一成功任务的两份 Java 模型回执并通过真实浏览器复查，第二次调用的身份、耗时和 Token 完整恢复；四个代码版本的历史切换、差异、五次实际模型调用及断线恢复也通过。启动期间 MCP 曾因 RocksDB LOCK 竞争自动重试两次，随后稳定就绪，未删除缓存或锁文件；旧锁持有者未确认，生产切换前应复核关闭顺序。就绪后的稳定性记录见 `shared-input-20261019-startup-observation.json`。

交付索引：[验收总览](shared-input-20261019-report.json)、[修改文件与构建哈希](shared-input-20261019-build.json)、[最终部署](shared-input-20261019-deployment.json)、[实际模型回执](shared-input-20261019-model-receipts.json)、[完整恢复用例](shared-input-20261019-real-restart.json)、[最终页面](shared-input-20261019-browser-final.json)、[失败用例与修正](shared-input-20261019-failed-attempts.json)。CSV 已通过 JVM 往返和真实受限 Python 容器读取，未将其报告为真实模型服务器 CSV 验收。各用例的实际测试版本分别记录；未切换生产，仓库改动尚未提交。
