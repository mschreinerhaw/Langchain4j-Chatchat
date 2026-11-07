# 通用 Coding Agent 与 Runtime 治理适配

基准：`fix/runtime-data-exploration-delivery-20261106` / `e9eda1b9a`。开发分支：`feature/runtime-native-coding-agent-20261107`。SDK 固定 0.0.93，上游提交及发布包校验见 [源码审查](../audits/cline-native-source-baseline-20261107.md)。金融、数据探索与 SQL 不是本阶段基础能力验收目标。

## 职责与执行流程

`native-runner.js` 只配置一个 SDK `Agent`。原生 Agent 自主决定读代码、搜索、编辑、测试和后续修复；没有第二个规划器、审核 Agent 或生成循环。`native-session.js` 包装模型传输、累计预算、工具披露与 SDK 稳定检查点，不能生成修复代码。旧 `runner.js` / `python_execute` 路径继续服务已有合同。

`native-workspace.js` 使用 `createDefaultTools`、原生读取/搜索 executors、Editor 与 Apply Patch。模型使用原生参数，不提交父版本或 Hash。代码编辑和执行分开；可以连续编辑多个文件后再执行，也可以执行后继续检查与修改。

每次原生修改先作用于任务私有 staging。原生 SDK 产出的结果、Diff 或 `computePatchChanges` 变更信息与代码一起提交给治理端。治理端独立校验租户/用户/run/task、路径、当前父版本及文件 SHA，持久提交成功后才发布当前目录视图。失败不能让部分修改成为当前版本。

`run_commands` 保留原生工具定义，Executor Adapter 把当前冻结版本和命令交给 Runtime。Worker 不启动宿主 Shell，不挂 Docker Socket，不获取数据库或模型密钥。Sandbox 中可使用原生 shell executor；其进程权限、挂载、网络、资源及停止证据由 Runtime 控制。命令失败返回真实 stdout、stderr、exitCode 和版本绑定，再由同一 SDK Agent 决定下一步。

## 存储与版本

沿用 Silo `RuntimeAssetArchive` 的 CODE / ARTIFACT、已有 scoped Evidence Store 和 `DurableCodingTaskService.privateState`。不建立 Git 仓库、新产品数据库或第二套 SDK transcript。代码树清单只是现有代码归档的私有索引，复用 `coding-canonical-json-v1`；不是模型侧文件或编辑协议。

Silo 保存不可变代码树、原生 Diff、工具结果及执行日志。Journal 只保存当前代码归档指针、版本及幂等操作引用。Worker 目录是可丢弃缓存，不能作为恢复或验收依据。空项目、目录与多个文本文件由同一工作区支持；默认保留32文件、256000代码字节的有界策略。

每次执行冻结当前树。执行源快照、命令 Hash、环境版本/镜像 digest、Runtime script allocation、MCP operation 与停止回执须相互绑定。编辑版本号与执行次数分别记录；运行测试不隐式创建新的代码版本。

## 既有 Runtime 的适配点

| 位置 | 适配 | 兼容约束 |
|---|---|---|
| Worker dispatch | 显式 native-project profile 路由至通用 Agent | 默认关闭；其他合同仍走旧 runner |
| Java Task / Callback | 原生工具准入、Silo版本CAS、审计、累计预算 | 使用现有合同、lease/fence、operation 与 checkpoint；不决定修复步骤 |
| MCP Python control plane | 冻结项目只读交付、受控命令执行、现有容器生命周期与回执 | 不给模型宿主路径、Socket或连接凭据；不改变旧Python参数语义 |
| `CodingPythonExecutionBridge` | 仅允许当前 task 已登记的代码快照作为执行输入 | 不以通配符放开业务数据授权 |
| Skills | 复用现有 SDK discovery / 按需读取 | 可冻结 builtin 选择，通用任务不自动加载金融分析指导；不建第二套 Skill 管理器 |
| MCP tools | 使用 SDK `createMcpTools` 的 provider 接口接 Runtime 授权代理 | Worker 不直连任意server；每次调用重新鉴权；schema变化须重新核验 |

SDK 0.0.93 的模型路由 helper 不从 npm 根入口导出。仅该内部模块以官方原始文件和 Apache-2.0许可保留在 vendor，Typescript5.9.3去类型生成，Git blob 与对应上游完全相同。编辑与搜索算法仍直接调用发布包。`generate-tool-routing.mjs --check` 防止复制后的规则漂移。

## 权限及执行边界

只允许任务中已授权的源文件树。绝对宿主路径、`..`、软链接、硬链接、路径大小写碰撞及Windows设备名均拒绝。代码文件缓存每次读取、搜索、修改和执行前核对完整性；不能凭同名目录获得访问。原生SDK的 restrictToCwd 不能替代此检查。

新增文件受同一数量/总字节限制。对给定项目的只读测试、配置或其他受保护文件，Runtime 在提交时验证保持不变；不通过修改验收输入或测试制造通过。执行只读冻结源；工作区写入必须经过原生编辑工具和 Runtime 提交。

网络默认NONE，无宿主 Shell、Docker Socket或数据库挂载。依赖安装不是默认权限。执行结果不能凭 exitCode=0宣称任务完成；最终代码、要求的测试、归档和复读都需验证。

## 中断与恢复

SDK `restore(messages)` 会重置 SDK usage，因此累计预算及原始deadline始终留在 Java。新 Worker 从同一 scoped Silo 指针重建目录，复用 SDK sessionId、原生 Compaction 状态和完整 call/result 边界。

模型已结束但回复未交付时，只能消费原已归档模型回执，不新增推理。原生工具按 task / toolCallId 幂等；编辑提交后中断不能再次插入同一补丁。命令须先通过既有 MCP 确认停止并结算原 operation，再恢复。无法确认的状态保持 UNKNOWN 和原预算预留，禁止重放。

工具执行途中丢失 Worker 时，Runtime 核对已提交代码及工具/命令回执。缺少原工具返回值时记录真实的中断错误，让同一 Agent 检查当前代码；不制造成功结果。恢复不能重置模型次数、执行次数、Token、成本预留或任务截止时间。

## 独立验收与迁移门槛

按用户要求执行 A–G：空项目多文件开发；现有错误源码；长源码局部修改；多文件重构；SyntaxError/KeyError/TypeError；Worker中断；权限负向。使用固定初始文件、独立只读测试及 Hash；保留实际失败，禁止自动修正模型产物。

对照分别报告原生SDK、当前Runtime、通用Runtime。工具、权限、反馈或执行环境差异单独列明，不能归因于模型。记录任务/修复完成率、首次有效代码时间、修复轮次、模型次数、Token、工具失败率和恢复结果。单次成功、SDK单测或零退出不是整体通过。

此前隔离的8类机制预实验含一个金融样本，已随任务方向调整停止后续提交；保留历史证据，不作为本阶段验收。预实验B为协议回放，不等同完整Runtime。新的正式验收以通用任务及真实Runtime路径为准。

原生能力、真实测试、恢复、权限和归档全部通过后，才开放业务插件接入。回滚先关闭新profile，只影响新任务；已开始的任务按其原合同完成或停止/结算，保留所有归档和旧兼容路径。

当前实现状态：原生工作区、通用SDK Agent、Java/Silo 端口、独立项目 API 与 MCP 冻结代码挂载已接线。确定性组件回归已执行；真实模型 A–G、完整 Worker 中断及正式性能对照尚待完成，不能视为整体验收通过。

## 独立项目接口

认证沿用 `ApiAuthenticationFilter`，租户及用户只从认证属性获取。`PUT /api/v1/coding/projects/{requestId}` 接收 instruction、files、readOnlyPaths、modelName、editStrategy、acceptanceCommand 和可选的已发布 Skills 绑定。服务端按认证身份和 requestId 生成独立 taskId/runId，拒绝同标识下替换需求、初始代码、只读文件或模型。空项目合法；任务本身不依赖金融、SQL Studio 或上层 Agent Run。

按返回的 taskId 读取 `GET /api/v1/coding/projects/{taskId}`、`/files`、`/history`、`/diff/{version}`、`/events`。`POST /resume` 和 `/cancel` 复用已有 SPI、租约和停止机制。历史读取只访问所属任务的不可变归档，不重启模型或执行器。

新通用合同冻结 8 次模型调用、262144 Token、16 次工具调用、4 次执行、300 秒任务期限、300000 毫秒累计 CPU 上限、512 MiB 内存和 1 MiB 输出上限。每轮输出上限冻结为8192 Token。它们是通用任务的固定验收条件，不能与原先金融任务的不同合同直接比较。恢复不重置这些数值；外部验收执行需单独计量，不回馈模型或补写产物。

模型公开参数以不包含凭据的指纹绑定；新推理前检查 provider model、协议、重试配置、上下文配置及端点身份是否变化。API Key 不读取、不保存或 Hash；端点指纹排除 userinfo 和 query。传输超时继续由原有请求策略和剩余任务期限约束，独立于上层 Agent 超时。

## 执行和中断的治理边界

Java 核对模型回执中实际 toolCallId、工具名和参数，工具必须先取得预算和 durable start intent。Editor/Patch 的提交由父版本及源码 Hash 校验；全量代码、SDK Diff 和工具回执进入现有 Silo。Host 不重放编辑算法。

`run_commands` 将原始模型输入 Hash、正规化后命令 Hash、命令序号及当前代码树绑定。Sandbox 中的同版本 SDK 独立执行无副作用的正规化，防止 Worker 通过替换命令绕过准入；实际命令由 SDK shell executor 执行。Python 启动器的 Hash 同时绑定 MCP helper 的 SHA，helper 再核验五个 SDK 包为0.0.93。返回实际 stdout/stderr/exitCode、独立 stop proof、镜像和代码版本；长输出保留完整归档并标记模型可见尾部截断。缺少命令结果即使退出0也不能通过。

任务级锁将命令 start intent 与工具停止回执排序，拒绝已关闭工具在超时后继续派发排队命令。恢复先变更 fence，再核对停止证据。恢复桥只允许一个精确绑定的原模型回执及其已经停止、关联正确的原生工具/命令；未确认的操作保留 UNKNOWN 和预算预留。编辑回执丢失时重读 Silo 当前版本；丢失的工具结果记录中断错误，不伪造成功。最后一次模型调用的缓存回执可在调用上限处消费，但不能获得额外推理次数。

最终提交意图在既有 journal 中固定，防止归档完成但结果提交回执丢失后改写验收结论。只有当前代码和任务声明的测试命令具备匹配的停止回执，才可交付该合同；`validationStatus` 仍为 INCONCLUSIVE，明确独立项目验收尚未评估。真实任务完成率由独立 A–G 验收确定。

## 开关及回滚

`chatchat.coding.native.enabled`、Worker 的 `CLINE_NATIVE_CODING_ENABLED` 和 MCP 的 `chatchat.mcp.python.native-coding-enabled` 默认关闭。独立原生环境由管理员固定 `chatchat.coding.native.environment-id`，请求方不能指定宿主路径、镜像、网络或 Docker 参数。

新任务还需显式开启 `chatchat.coding.native.admission-enabled`，默认关闭。回滚先关闭该准入开关，保持其余组件可用，让已有任务继续恢复或停止、结算并读取归档；确认没有活跃原生任务后，才关闭组件或回滚镜像。原 `python_execute` 和旧业务 profile 保持原路径。不得在原生任务未停止时直接换回不认识该合同的 Worker。

## 后续插件接口和版本限制

已发布 Skills 通过既有 SkillResolver、SkillExecutionScopePort、CodingSkillReader 和 SDK discovery / loader 接入。通用任务显式冻结所选包，按需披露，逐次复核授权；不自动加载金融方法。Skill 脚本暂不自动挂载到执行器，读取资源不等同获得宿主执行权限。

Node 保留 SDK `createMcpTools` 的受控 provider 端口。独立项目 API 当前不接受任意 MCP 地址或授予 MCP 工具，Java MCP 授权代理及动态 Schema 的生产整链仍待完成。不得将这一预留接口计为完整 MCP 验收。业务数据、报告和数据库工具须在通用能力达标后，以明确的资源及工具合同接入；不在基础 Agent 中新增业务修复决策。
