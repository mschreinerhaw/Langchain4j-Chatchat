# Runtime OS 发现过滤契约修复与 Docker 联机验收

## 原始问题和执行事实

2026-10-12 20:36:25，任务 `a8e232d5-2dc3-42b3-bff1-d2a670181183` 的首次资产发现失败。
运行标识：`att-1-b6b07f94-b64e-4320-afa7-6cba3c711649`。
工具：`mcp_chatchat_mcp_server_server_capability_query`。
实际参数为 `stage=asset`，过滤条件包含 `queryTerms`、`env=DEV` 和模型生成的
`description=logical target docker database simulation server`。
MCP 返回 `INVALID_REQUEST`，错误为 `Filter field is not allowed for targetKind=host: description`。

原任务后来删除该字段，并成功执行了六个 Docker 检查，20:47:06 最终记录 `SUCCESS`。
另外三个针对具体容器的检查因缺少必需的 `container` 等参数被阻断；这不能解释为传输失败。
因此，首次失败是真实的调用契约错误，不能将它解释为“服务器不存在”或“Docker 没有数据”，
也不能把原任务最终成功当作首次参数已经正确的证明。

## 架构根因

| 边界 | 原有行为 | 影响和修复 |
| --- | --- | --- |
| MCP 发布 → 模型规划 | `OpsCapabilityBridgePublisher` 声明 `filters.additionalProperties=true`，仅显式列出 `assetIds` | 模型被允许生成内部不接受的字段。改为由 `TargetKindRegistry` 生成封闭的域过滤 schema，四类桥共用内部准入字段定义 |
| Runtime 参数编译 → 工具调用 | 嵌套封闭对象中的未知字段会被静默删除；可选父对象也可能被删除 | 静默删除条件可能扩大查询范围。编译前递归检查封闭对象和数组项，返回 `UNKNOWN_NESTED_PARAMETER`、完整字段路径及允许字段；错误由现有 Runtime 调用边界阻断 |
| DAG guard / contract audit → 执行事实 | guard 接受调度；contract audit 检查声明、元数据及结果契约 | 二者不验证所有实际输入值，也不证明工具已成功。最终参数编译、provider 准入、执行结果及证据提交分别负责各自事实 |
| 失败事件 → 证据分析 | 状态更新、证据捕获等操作可以成功，但其中的工具调用仍然失败 | UI 中其他“已完成”行不能覆盖失败事实；保留原错误，不能把缺少证据说成权威空结果 |
| 批执行账本 → 模型分析上下文 | `AnalysisEvidenceCoordinator` 把所有非成功子调用归入 FAILED；`sourceCoverageObservations` 又仅保留记账状态，并称其为 transport observation | 受阻事实、是否调用和缺参原因被丢失。修复为区分受阻/跳过/取消与执行失败，完整传递 `executionStatus`、`invoked`、`reason`、`error`；失败父批次仍逐项保留子结果 |

没有增加 Docker 关键词分支、业务专用字段容错或新的 Agent。
未知 `description` 没有被加入白名单，也没有被自动转换为搜索词。
有效 `env`、`assetName`、`queryTerms` 等条件及既有别名编译继续保留。
`assetIds` 仍由现有 provider 在模板阶段验证集合绑定，资产阶段不会因此获得该能力。
开放对象仍支持其发布契约允许的扩展字段。

现有 `CapabilityManifestInjector` 已把权威 `inputSchema` 注入规划上下文；刷新目录即可传播新契约。
管理端直接 MCP 调用仍使用 provider 准入；Agent DAG 路径额外经过 Runtime 参数编译。

## 源码及部署验证

Maven 定向测试全部通过，共 209 项：Runtime/参数编译/上下文恢复 175 项，MCP 发布和字段契约 34 项。
新增测试验证四类公开过滤字段与内部注册表一致、未知嵌套字段和数组项被拒绝、有效别名保留、
开放对象扩展不受影响，以及 Runtime 在 provider 调用前拒绝非法发现条件。
旧测试中“未知嵌套过滤条件可被静默删除”的期望已移除，正常类型转换测试继续通过。

服务器实际依赖隔离验证通过：API 编译器拒绝未知字段并保留合法别名；MCP 四类发布 schema 均为封闭契约。
增量包以服务器运行包为基线，仅替换编译器、域桥和字段注册表及其内部类。
对每个 ZIP 条目核对未替换内容不变，保留原依赖和配置，并在部署前保存两端回滚包。

部署指纹：

| 服务 | 修复前 SHA256 | 修复后 SHA256 |
| --- | --- | --- |
| API | `b21df8dc6a5773ba8dd94cb9a0dfaccd4074e463f7d24053030cb4008ce589c4` | `756c4e36a0aa6642fda108d92183cd220e7ec2ff141bbcf5b2f46d7db1b49d52` |
| MCP | `1d65a76a5e6ed6f451fdfcff1d24e7878562c4baf18c516dc906bffeaf17812c` | `00a3e6d4beb969b0be3a54f895b44a547dff7f980a5cc5ce11f3121c31dcab4d` |

API 的实际认证健康接口 `/api/v1/health` 返回 `UP`，MCP 目录刷新后注册 2 个授权工具。
线上四类发现桥均发布封闭过滤契约；直接调用非法 `filters.description` 返回失败，
正确资产查询成功并返回授权的 `Docker 数据库模拟服务器`，ID 为 `cdd1fdb8-88b6-48e3-875c-ad9ee888e009`。

辅助部署检查最初使用了此部署未启用的 actuator 路由；停止该辅助检查后，改用真实认证健康接口、
工具发现和实际 MCP 调用核验服务。Windows PowerShell 编码及交互模式探针失败不计入业务验收。

验证材料：`target/codex-live/filter-contract-20261012`。
服务器暂存及回滚材料：`/opt/chatchat-deploy-staging/filter-contract-20261012`。

## 原问题端到端验收

原文请求：`分析 Docker 数据库模拟服务器 管理的镜像和容器运行情况`。
使用原 Agent `server_ops_assistant`、`agent_chat` 模式及其两个授权工具；没有更换模型或通过手工 SSH 检查替代 Agent。
正式验收任务：`23a4e0cf-c198-3719-9083-762e7eb12a32`。
运行标识：`att-1-98f7246b-78fb-4126-8b4e-8c1ba789c7ff`。

上述任务于 21:25:52 左右结束，公共任务状态 `SUCCESS`，模型声明 `PARTIAL_COMPLETE`。
资产/模板发现均成功，六项只读检查成功，无过滤字段失败。
但原始模型报告仍把三项受阻检查写成“传输失败（FAILED）”，因此不作为完整的报告语义验收。
该报告按原样保留在第一批材料中，未人工重写模型结论。

已核对本次取数：正式资产绑定到 `192.168.195.226`，不是部署主机 `192.168.195.221`。
六个成功检查分别为 `CHECK_DOCKER_CONTAINERS`、`CHECK_DOCKER_IMAGES`、`CHECK_DOCKER_PS_ALL`、
`CHECK_DOCKER_OVERVIEW`、`CHECK_CONTAINER_RUNTIME_OVERVIEW` 和 `CHECK_DOCKER_SYSTEM_DF`，退出码均为 0。
批次为 `PARTIAL_SUCCESS`，计数为 total=9、success=6、failed=0、blocked=3。
TOP/PORTS 缺 `container`，LOGS 缺 `container` 和 `lines`；对应记录均为 `BLOCKED` 且 `invoked=false`。

由原始 stdout 独立核对：

| 项目 | 本次执行返回值 |
| --- | --- |
| 镜像 | 19 个仓库/标签条目、18 个独立 image ID；`docker system df` 显示 18 个镜像 |
| 容器 | 共 11 个，4 个运行、7 个退出 |
| 运行容器 | `kingbasev8r6`、`sql2025`、`kingbasev8r6-ai`、`dm8_test` |
| 镜像空间 | 15.16 GB，可回收 9.376 GB（61%） |
| 容器空间 | 4.365 GB，可回收 2.342 GB（53%） |
| 本地卷 | 74 个、2 个活跃，25.47 GB；Docker 标记可回收 25.41 GB（99%） |

“可回收”是 Docker 的空间统计，不代表已证明业务数据可删除。本次仅执行授权的只读检查。
证据保存在 `docker-execution.json` 和六个 `CHECK_*.stdout.txt` 文件中。

## 执行事实交接的后续修复

`AnalysisEvidenceCoordinator` 现在对 `BLOCKED` 使用 `SOURCE_EXECUTION_BLOCKED`，
对 `SKIPPED`/`NOT_EXECUTED` 使用 `SOURCE_NOT_EXECUTED`，对 `CANCELLED` 使用 `SOURCE_EXECUTION_CANCELLED`。
这些无可分析数据的子项使用既有 `EXCLUDED` 记账类别，真实失败仍使用 `FAILED`。
实际 `executionStatus`、原始 `invoked` 和错误对象独立保留，记账类别不代替执行事实。
不改变公共任务状态机或持久化状态枚举。

`ModelNativeAnalysisHarness` 把完整的上述事实传给模型，替换误导性的 transport-only 标签。
核心分析协议说明：执行状态和是否调用是 Runtime 的事实，未调用检查不得称为传输失败。
模型仍决定业务解释与证据充分性，Runtime 没有代写报告或猜测缺失的容器参数。

追加定向测试 197 项通过，与第一轮去重后共 234 项不同测试通过。
新增覆盖失败父批次不抹掉 BLOCKED 子项、成功/失败/受阻/跳过隔离，以及真实 ChatRequest 中完整的缺参事实。
服务器实际依赖的隔离重放同样通过，证明没有错误地把受阻事实改成失败。

扩展运行旧 Harness 测试时存在失败及旧恢复测试 fork 异常；其中
`ModelNativeAnalysisHarnessTest.absentProjectionFieldIsNotReportedAsMissingSourceData`
已用提交基线中的 `ModelNativeAnalysisHarness` 和 `ModelAnalysisPrompt` 重新编译、独立复现相同失败。
旧测试使用此前的模型调用/规划协议，不能据本次定向结果宣称整个历史测试集通过。
基线材料为第二批目录内 `baseline-fixture-check.log`。

第二批材料：`target/codex-live/filter-evidence-truth-20261012`；服务器同名暂存目录保存回滚包。
最终 API 包 SHA256：`8857e0cd0629a70dea41c78941d1545b2dd9899bab96c80d32656ec3ea042fce`。
MCP 保持第一批修复后的指纹。最终组合版本的真实任务验收已通过，详见下文。

## 部署兼容性修正

第二批试运行任务 `f14f9696-7720-3c0a-ae7e-29f87785623d` 的获取阶段成功，并验证三个缺参子项均为 `BLOCKED / invoked=false / SOURCE_EXECUTION_BLOCKED`；报告阶段出现 `NoClassDefFoundError: HarnessPlanState`。这是本次补丁打包遗漏：本地已提交的分析器源码包含比线上更多的计划管理协议，单独替换整个类引入了未部署的依赖。没有将这次试运行记为成功。

最终兼容补丁以线上原始分析器字节码为基础，只替换 `sourceCoverageObservations` 方法，向原有核心协议补充执行状态事实约束，保留线上其余方法和既有计划语义。未升级整套计划管理模块。使用服务器 Spring ASM 处理常量池和方法复制；ZIP 校验确认其他条目保持原内容，`-Xverify:all` 和真实依赖重放通过。生成脚本、基线类包、校验和、旧任务事件保存在第二批材料目录。

最终重复原始问题的任务：`ad78c68d-34f3-32aa-a21c-69969c14385c`；材料位于第二批目录的 `final-run`。认证健康检查通过，资产发现正反例通过，真实任务已成功交付报告。

## 最终验收结果

任务 `ad78c68d-34f3-32aa-a21c-69969c14385c` / run `att-1-a7eff363-1402-465b-add3-35ac4f7c64ac` 最终为 `SUCCESS`。原始问题未修改；线上资产和模板发现均成功，报告阶段没有缺类异常。最终报告原文保存为 `target/codex-live/filter-evidence-truth-20261012/final-run/report.txt`，事件序列完整保存至 147。

原始执行账本：9 项计划检查，6 项实际远程调用全部 `SUCCESS / exitCode=0`，0 项执行失败，3 项 `BLOCKED / invoked=false`。TOP、PORTS 缺少 `container`；LOGS 缺少 `container` 和 `lines`。三项均保留 `TEMPLATE_REQUIRED_PARAMETERS_MISSING`，证据记账为 `EXCLUDED / SOURCE_EXECUTION_BLOCKED`。最终报告明确记录未调用的缺参检查，已消除先前传输失败的错误分类。

目标资产为 DEV `192.168.195.226`，与应用部署服务器 `192.168.195.221` 不同。最终快照为 18 个唯一镜像、11 个容器，其中 4 个运行、7 个退出；运行容器为 kingbasev8r6、sql2025、kingbasev8r6-ai、dm8_test。镜像 15.16GB，容器 4.366GB，卷 25.47GB；可回收提示不能直接作为删除授权，本次未执行清理。镜像标签与镜像 ID 计数不同，不把多标签重复计为多个唯一镜像。

报告属于模型生成的快照分析，未取得单容器日志，不能确认退出原因或持续健康。正文某处把缺参表述为“未获授权获取日志”，该措辞不精确；实际账本是缺少参数阻断，并非权限拒绝。应以保留的运行时状态、调用次数和错误对象为准。完整原文未人工改写。