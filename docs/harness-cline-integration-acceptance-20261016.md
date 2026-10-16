# Harness / Cline 联调与部署验收（2026-10-16）

范围经用户确认：继续 Harness/Cline 联调验收；SQL Studio 保持未启动，仍需此前全部待办完成并验收。

## 构件与隔离联调

本轮使用[优化计划及实施记录](harness-cline-analysis-optimization-plan-20261016.md)对应的完整 API JAR，SHA-256 为 `4456a99534f5f5d2d6347dddb9e384b4f30db20c6957c31c94555769efdfe39e`。该构件已通过 78 项相关本地测试与完整打包。

独立发布目录：`/opt/harness-cline-integration-20261016`。隔离 Java 使用该 JAR 的原始完整 classes/lib，仅额外编译测试驱动；没有覆盖生产业务 class。复用原隔离 H2 与暂存 Cline 容器，经真实服务端模型和实际 MCP/Python 沙箱验证。

| 场景 | 运行标识 | 结果 |
| --- | --- | --- |
| 数值中位数 | `production-coding-smoke-b22e4130-791d-4798-bf34-63476efdb143` | 输入 4 条，返回 `{"statistic":"6.5"}`；主模型 2 轮、Coding 模型 2 次、Python 1 次 |
| 文本计数 | `production-coding-smoke-e0aae08b-c093-490e-871b-6fe59d444d78` | 输入 5 条，返回 `{"label":"red","count":3}`；主模型 2 轮、Coding 模型 2 次、Python 1 次 |

两例均验证：Coding COMPLETED、resultAvailable=true、reconciliationRequired=false、六项证据完整；主 Harness 消费派生数据，任务目录保留业务校验 NOT_EVALUATED；已完成子任务再次 resume 不增加 revision。

独立验收脚本直接读取 MCP 归档，核对每例 4 个文件的长度与 SHA-256，再从归档输入重新计算期望结果。两个场景通过，详见发布目录 `isolated-archive-validation.json`。夹具、业务算法与预期值只用于验收；Runtime OS 继续使用通用契约与结构校验，未加入业务答案或分支。

两例 bundle：

- `runtime-evidence:coding:1a54632b9b6798c63b0d707e862ec5c765e3e81897ea2218c2b543afc6a8f408:bundle`
- `runtime-evidence:coding:58159c11cb16a03ce5896aec428278439b58466fd96fb3e6a5d2c04c512a7300:bundle`

## 生产部署

隔离联调通过后，确认生产活动任务数为 0，备份原 API JAR，再安装同一完整构件并重启 API。生产 MCP 与 Node 沿用已验收版本。

| 构件 | SHA-256 |
| --- | --- |
| 初始 Harness API JAR | `4456a99534f5f5d2d6347dddb9e384b4f30db20c6957c31c94555769efdfe39e` |
| MCP JAR | `c49d240ffda0e726dab82f939e076338ead85f07a3127faca1339c604691b7ed` |
| Node 镜像 | `8d8534ee8a9e26f76b8648c1a20fdef7be78d681f717f90f2ad519e084f071d6` |
| 回滚 API JAR | `8b7da1738cf4ef11beb0948f8f382b7185227ec117f4c39d8a93dc899a7aaecb` |

回滚备份位于发布目录 `backup/chatchat-before-harness.jar`，需要时恢复至 `/opt/chatchat-1.0.0-SNAPSHOT/lib/app/chatchat.jar` 并使用既有 `bin/restart.sh`。

部署后 API 登录已通过，历史任务 `69c831bb-732d-3ecb-9b32-2f1b7fe88e1b` 重复提交仍返回 SUCCESS，答案和事件序号 75 未变，Python 执行次数仍为 1。

## 联调发现与修复

真实 Edge 浏览器在任务仍为 WAIT_MODEL 时收到 SSE done，游标仅为 6，说明流提前结束。源码将步骤 SUCCESS/FAILED 等状态误当任务终态，且任务已结束时可能只发送第一页历史。现已改为以任务权威状态及 lastEventSequence 决定关闭；历史分页先追平游标，结构化 SSE 明确使用 JSON。4 项新增回归通过，浏览器修复后验收另行记录。

正常入口的首次任务 `cd979da7-897e-3f62-9e60-afe211e70633`（attempt `att-1-2bd99aa7-622d-418d-9c7a-9bf84609dbae`）主任务返回 SUCCESS，但 Coding 子任务 `analysis-coding-aded64378266daa2f32cbead576980ed01134c9af6536b3c90447e3cae69f11f` 为 BUDGET_EXCEEDED、resultAvailable=false，11 次代码执行后无可消费结果。主模型明确披露失败并改用原始数据，不将该任务计入 Coding 成功验收。

归档检查发现，多个程序只是打印诊断，进程成功但没有写出合同要求的 rows.jsonl；原回调把缺失产物统一包装成绑定错误，模型反复诊断。修复后区分经过 manifest 核验的“缺少必需输出”，返回 REQUIRED_OUTPUT_MISSING、resultAvailable=false 与文件契约提示，不放宽预算、不生成结果、不回传原始 stdout。归档损坏和错误身份绑定仍拒绝。输入描述明确 format=JSON_LINES、encoding=UTF-8。

新增回调测试 4 项、归集测试 1 项；结合既有未变动代码回归及最终复跑，相关 Java 测试去重 87 项通过，Node 15 项通过。最终构建日志 `target/harness-cline-integration-final-build.log` 为 BUILD SUCCESS；分次回归见 `target/harness-cline-analysis-final.log`、`target/harness-cline-integration-callback-final.log`。中间测试曾出现嵌套 Mockito stubbing、测试误用未改动 body，以及 SSE 序列化媒体类型未指定的问题，均已定位修正；不将中间失败日志冒充通过。

早上修正版完整 API JAR SHA-256 为 `db030019c43e2cece83d15941d1ff96147b66a73e220ffd5324f1ab8d459bea0`。当时额外的真实探索/产物生成联调尚未签收；后续核验和重新构建部署见下文。

## 正常入口验收进度

早上留下的待办为新的普通任务入口验收、独立归档核验及重启后重提验证。首次失败任务和其预算预留均保留，不修改为成功。

## 续办部署与实际验收

按用户提供的 SSH、API 和 MCP 连接信息继续工作。先核对 `4b641ca6`、`7403c1c1` 和服务器实际构件，确认生产仍使用初始 Harness API，生产 Node 尚未包含缺少产物的工具说明修复。

本次从当前源码重新执行离线 Maven reactor API 打包，17 个模块 BUILD SUCCESS，回调 4 项及证据归集 5 项测试通过；另行运行 SSE 回归 4 项通过。服务器实际 Node 镜像在无网络、只读根目录的独立测试容器中重新验证，15 项通过。构建复用既有前端 dist，本轮没有前端源码修改。日志位于工作区 `deployment-artifacts/harness-followup-build-20261016.log` 和 `harness-followup-sse-tests-20261016.log`。

服务器发布目录为 `/opt/harness-cline-followup-20261016`。完整 Maven 构件传输后与本地 SHA-256 核对一致；先在隔离 Java 18080、暂存 Node 和实际 MCP/Python 上复验，再于生产活动主任务为 0 时备份并部署 API 和 Node。MCP 继续使用原已验收构件。

| 当前生产构件 | SHA-256 / image ID |
| --- | --- |
| API JAR | `ed92b91e64170827ea0da83718bf0abbca6342db9d365aa371ffeb4fd4e8c63a` |
| MCP JAR | `c49d240ffda0e726dab82f939e076338ead85f07a3127faca1339c604691b7ed` |
| Node `runtime-coding-agent:harness-20261016` | `sha256:91d5145c9fb8a347796e13faa1623ccf97bcb6ed28647edae500e3e034494cfc` |

备份 API 为发布目录 `backup/chatchat.jar`，SHA-256 为 `4456a99534f5f5d2d6347dddb9e384b4f30db20c6957c31c94555769efdfe39e`；旧 Node 容器以 `coding-agent-production-before-20261016` 停止保留。部署脚本保存旧容器配置，沿用原网络、密钥挂载、资源限制、只读文件系统和健康检查。API 管理员与 MCP 管理员登录通过，生产 Node 健康；网络实测继续拒绝 Node 直连 API/MCP、数据库、OpenSearch 和公网，未签名回调返回 401，普通管理路径返回 404。

### 缺少产物后继续执行

重新构建的同一 API JAR 驱动 `ClineExplorationSmokeDriver`，真实运行 `production-coding-smoke-f85c2681-af90-49da-b6ad-7833227709b0`，子任务 `analysis-coding-0e8db73a6282414c5661832806b16dc699c1f5cfaecab2732aaac0d7128bab47`。3 次模型调用、2 次 Python 执行后 COMPLETED，六项证据齐全，终态 resume 不增加 revision。

独立读取实际归档确认：第一次操作只有诊断输出，manifest 为 MISSING，未产生结果文件；第二次为 PRESENT，4 条不可变输入经独立 Decimal 复算得到中位数 6.5，与归档结果一致。逐项核对归档文件长度和 SHA-256、代码快照及输入快照哈希，记录为发布目录 `isolated-archive-validation.json`。计算预期仅存在于测试驱动及部署验收脚本，未进入生产 Runtime。

### 普通任务入口与浏览器 SSE

通过普通 `POST /api/v1/agent/tasks`，选择既有 `db_ops_assistant`、`qwen3.5-plus` 和 `model_native_analysis.v2` 协议。请求仅使用正常 API 参数，没有注入内部工作流定义。主模型自主获取只读 SQL 数据并调用 RUN_CODING。

| 项目 | 实际验收值 |
| --- | --- |
| 主任务 | `c9e48b7d-3d20-390b-98f7-8c7152a9735f` |
| attempt | `att-1-e384a92f-c672-4a3c-a801-f863349b978a` |
| Coding 子任务 | `analysis-coding-e2bf878800c56c0c1247c5327ae8ab060fa513996755205382663b7867535b86` |
| 主任务状态 / 持久事件序号 | SUCCESS / 75 |
| Coding 状态 | COMPLETED，resultAvailable=true，reconciliationRequired=false |
| 执行量 | 2 次 Coding 模型调用，1 次 Python 执行 |
| 结果 | MySQL 8.4.7；10 条输入，`{"record_count":10}` |
| Runtime 校验边界 | INCONCLUSIVE / businessValidation=NOT_EVALUATED / meteringComplete=false |
| 六项证据前缀 | `runtime-evidence:coding:c709411e3c4d99a39171c8df922ffdc4cd84f6f8cb088f57b4694d358edfc0b9:` |

独立从 MCP 归档重新计数，逐项核对 4 个归档文件长度与 SHA-256，并验证输入、执行代码快照。确认只有一次 Python operation，结果为 10。结果文件 SHA-256 为 `a10d87fea365a83b8193a9080254af5cf87fa7d0a40fdf05cc6791e7fb9a320f`；manifest SHA-256 为 `ed670919a49c26779e832975e7f04d00b4f20de7dead87caae5ed27e69e1ad91`。验收记录为发布目录 `production-archive-validation.json`。

真实 Edge headless 浏览器登录并打开任务事件页面；浏览器 Fetch 流与独立 HTTP 客户端均使用每页 3 条事件，在游标 3 主动断线后重连，准确接收 1–75 全部事件，无重复、乱序，done.cursor=75，与权威任务终态一致。HTTP 续传使用 Last-Event-ID；浏览器续传使用 afterSequence。过程截图及 `browser-verification.json`、`sse-verification.json` 位于工作区 `deployment-artifacts/harness-followup-20261016`。

一次先行请求未选择数据库运维 Agent，使用了默认文档检索工作流，最终明确报告未获得目标数据；未运行 Coding，不计作 Coding 验收通过。该任务 `2e859eba-c16c-373b-b1e6-32445d3c2782` 及其 34 条 SSE 事件保留于 `default-agent-trial`，未伪造或覆盖结果。

### 重启与重复提交

确认活动主任务为 0 后重启生产 API 和 Node，再重新认证，使用相同请求和幂等键提交。返回相同主任务 SUCCESS，attempt、答案及事件序号 75 均未改变；重新分页读取持久 observations，Coding 状态、可用结果、六项证据和全部 usage 与重启前一致，Python 执行次数仍为 1。原历史任务 `69c831bb-732d-3ecb-9b32-2f1b7fe88e1b` 的答案和事件序号也未改变。重启后的 SSE 历史分页再次完整追至游标 75。

此次既有 `bin/stop.sh` 在等待期限内未能让 API 自行退出，按其现有逻辑强制结束进程后启动；未将这一过程记为正常优雅退出。随后 API、MCP 管理员登录与 Node 健康检查通过，持久任务及证据核验通过。详细过程记录为发布目录 `restart.log`。

工作区证据为 `restart-idempotency.json`、`post-restart-checks.json`、`verified-coding-receipt.json`；相同验收摘要另存服务器发布目录。早上遗留的修复版部署、探索后生成结果、普通任务入口、浏览器 SSE、独立归档与重启后幂等验证已完成。此结论仅覆盖上述固定输入 v1 联调项目，仍受下节门槛说明约束。

## 门槛说明

本轮验证的是固定输入快照的 Harness/Cline v1。增量补数 v2、活跃 SDK 会话跨进程恢复、历史 provider 超时事实核对、完整 D3/G3 等待办仍分别验收。CPU/费用等计量不完整时继续保留预算预留，不以零费用或本地超时伪造完成。SQL Studio 不因本轮局部验收自动启动。
