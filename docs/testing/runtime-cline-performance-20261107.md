# Flash / Plus 模型性能对照报告

状态：FAIL（稳定交付和性能优化验收）。三轮真实对照已全部结束；两模型均为0/3完整报告，不能宣称性能优化完成。

旧R5各两轮结果及失败保留于[旧验收](runtime-data-exploration-delivery-20261106.md)，不能补一个不同版本任务后混成新三轮。R5仅Flash首轮完成任务和四图归档，复核其业务报告为PARTIAL；Plus首轮错误判断零记录，其余两轮未交付。Flash首轮有一次Token使用量UNKNOWN，已知值只是下界。失败更快不算优化。

新对照要求：沿用B2实际2943行行情的四个冻结材料，同实际字节Hash、权限、工具、预算；Flash/Plus各三次，独立taskId/nodeId，按三轮成对执行。`verify-runtime-data-scenarios.py`增加测试用环境变量 `RUNTIME_ACCEPTANCE_REPETITIONS=3`，只改变测试次数，不改变任何任务预算。可比性验证拒绝复制同一任务冒充重复；HTTP超时后只轮询终态，上一对仍运行时不启动下一对。

上限保持12模型调用、786432Tokens、24工具、12Python、CPU300000ms、512MiB内存、4MiB输出、USD10。使用最新Worker，其他业务测试结束后再执行，避免混入负载干扰。共享模型配置不修改。

记录规划/发现/文件读取/代码生成/Python/报告/总时间、首次有效代码、模型次数、重复读取/无进展次数、已知Token与UNKNOWN次数、完整报告成功率、业务质量。已有非流式API无法拆开的纯规划/生成阶段保留null/UNKNOWN；不得用代码提议回调（包含思考与IO）冒充纯代码生成时间。

请求级参数先审查能力：当前callback中的maxTokens用于Runtime预算预留，不等于供应商输出上限；不能仅改该字段声称限制了模型思考或Token。只有供应商/现有SDK实际支持并可由回执核对时才对照参数，不创建新的模型实现。截止时间和剩余预算通过既有budgetGuidance与Runtime准入执行。

## 计量口径与版本

本轮统一运行在API `c63e705321a922f4c1ddfd51e0681dedb219bb19`（JAR SHA `618689c828ea04e492f458336632e5ef2a1c217ff0fe20aaa1869fd86a3be288`）和原Worker/SDK0.0.93。只读进度和有界停止对账修复均在全部六任务终止和证据收集后才发布，不混入计时队列；[独立发布顺序证明](evidence/runtime-cline-acceptance-20261107/final-release-order-proof.json)核对六个终态时间、准备时间及当前JAR。未做最终停止修复版本的六轮性能重测，不据此推断提速。新真实文件包与旧R5的26行材料不同，因此不将两批耗时混为同输入前后优化对比。

已归档请求回执中两模型输出上限均为32768；缺失usage的操作不因此补齐用量。冻结executionProfile只有schemaVersion、complexity、inputRows、sourceCount，未设置reasoningEffort；现有桥接的可选请求参数有26项离线测试，不能据此声称供应商实际接受了本轮未发送的思考控制。未修改共享模型设置来制造对照差异。

| 字段 | 含义与限制 |
|---|---|
| durationMs | Node接收任务到终态的服务端耗时，不是完整报告成功耗时 |
| httpDurationMs | 现有execute请求实际往返；超时或提前返回不冒充最终交付 |
| firstCodeLatencyMs | 首次CODE_READY，仅说明源码就绪，不保证能成功运行 |
| firstZeroExitLatencyMs | 首次Python工具报告成功退出；缺少rows.jsonl仍不是报告 |
| firstReportLatencyMs | 首次Python返回resultAvailable=true的归档报告；仍需终态和业务质量检查 |
| submissionToDeliveredReportMs | 只有HTTP确实返回完成报告、终态COMPLETED、结构和图表完整时才填；PARTIAL/失败/未知保持null |
| codeProposalModelMs | 发出Python提议的整个模型回调，包含思考、生成和供应商IO，不是纯代码生成时间 |
| unknownUsageCalls | 缺少完整输入/输出Token回执的实际模型操作数；已知Token只为下界 |
| repeatedReadRequests | 相同工具和参数的再次读取次数；读取本身不必然无进展 |
| wholeProgramProposalCount | 完整源码提议数，含被拒绝的重复提议，不冒充全部提交成功 |
| rejectedCodeProposals | 实际CODE_PROPOSAL_REJECTED观察数；不能直接等同无进展总数 |
| noProgressToolCalls | UNKNOWN，当前没有可靠的全量业务进展判定，不能填0 |

阶段工具耗时与Python耗时可能重叠，不能相加作总时间。纯规划、纯代码生成、纯报告生成未单独观测时为UNKNOWN；本文件队列没有执行行情发现工具时，也不拿其他场景的发现耗时拼接。结果中的strictCompleteDelivery是严格结构交付标记，业务正确性仍须独立复核原始报告、数值和图表。

首轮实际结果（不是最终三轮结论）：Flash预算耗尽，服务端1200192ms、11次模型操作、已知输入286903/输出58617 Token、2次usage未知，Python累计3475ms；Plus失败，479795ms、12次模型操作、已知输入362267/输出25231 Token、usage未知0次，Python累计6735ms。两者均无可交付报告，Plus失败更快不构成质量相同条件下的性能优势。

## 最终六任务结果

[原始计量](evidence/runtime-cline-acceptance-20261107/real-scenario-results.json)、[输入/权限/预算可比性](evidence/runtime-cline-acceptance-20261107/benchmark-comparability-proof.json)、[实际挂载schema绑定](evidence/runtime-cline-acceptance-20261107/native-schema-delivery-proof.json)、[业务失败与输出文件证明](evidence/runtime-cline-acceptance-20261107/business-quality-proof.json)。六个不同taskId/nodeId、四个相同原始文件、实际挂载SHA、逻辑内容、权限种类和预算上限全部核对通过；不是复制任务冒充重复。每轮两任务全终止后才进入下一轮。

时间单位为秒；模型数为已登记的不同模型操作数，不把它直接当作精确供应商计费次数。已知Token列为输入/输出，缺失回执不记0。

| 轮次 | 模型 | 终态 | 模型操作 | 已知输入/输出Token | UNKNOWN次数 | Node总时长 | 控制面总时长 | Python累计 | 可交付报告/图表 |
|---|---|---|---:|---:|---:|---:|---:|---:|---|
| 1 | Flash | BUDGET_EXCEEDED | 11 | 286903 / 58617 | 2 | 1200.192 | 1201.131 | 3.475 | 无 / 0 |
| 1 | Plus | FAILED | 12 | 362267 / 25231 | 0 | 479.795 | 479.772 | 6.735 | 无 / 0 |
| 2 | Flash † | BUDGET_EXCEEDED | 10 | 202765 / 50966 | 2 | 1200.128 | 1918.055 | 2.540 | 无 / 0 |
| 2 | Plus | FAILED | 12 | 418605 / 61340 | 0 | 1057.215 | 1057.175 | 2.782 | 无 / 0 |
| 3 | Flash | BUDGET_EXCEEDED | 8 | 170096 / 42319 | 2 | 1200.375 | 1200.743 | 2.711 | 无 / 0 |
| 3 | Plus | FAILED | 12 | 371510 / 32193 | 1 | 903.054 | 903.009 | 7.073 | 无 / 0 |

† 第二轮Flash在截止时已停止Worker，但停止回执晚于最后一次恢复查询，控制面未自动终结。通过原节点、原requestId执行了一次明确的原生恢复；[对账证明](evidence/runtime-cline-acceptance-20261107/flash-2-native-reconciliation-proof.json)确认契约Hash、快照、预算和全部操作ID不变，新增模型/Python操作为0。控制面时长包含等待和介入，不能隐藏该异常后声称自动流程或时延可比性完整通过。其余五个任务无此介入。

| 分阶段指标 | Flash（三轮） | Plus（三轮） |
|---|---|---|
| 纯规划耗时 | UNKNOWN | UNKNOWN |
| 数据发现耗时 | 本文件队列没有调用对应发现工具；不填0 | 同左 |
| 文件读取中位数 | 2.100秒 | 0.317秒 |
| 模型操作数合计 | 29 | 36 |
| 纯代码生成耗时 | UNKNOWN | UNKNOWN |
| 代码提议模型回调中位数（含思考/IO） | 571.306秒 | 501.796秒 |
| 工具累计耗时中位数 | 11.661秒 | 18.301秒 |
| Python累计耗时中位数 | 2.711秒 | 6.735秒 |
| 纯报告生成/交付校验耗时 | UNKNOWN；未交付 | UNKNOWN；未交付 |
| 首次源码就绪延迟 | 230.960 / 196.710 / 311.611秒 | 51.536 / 84.258 / 50.688秒 |
| 首次Python零退出延迟 | 未出现 / 201.878秒 / 未出现 | 173.607 / 88.995 / 55.576秒 |
| 提交到完整报告 | 三轮均未发生 | 三轮均未发生 |
| 已知Token合计 | 811666（下界） | 1271146（下界） |
| UNKNOWN用量次数 | 6 | 1 |
| 完整报告成功率 | 0/3 | 0/3 |
| 数据分析正确性 | 无完整报告，无法验收 | 无完整报告，无法验收 |
| 相同参数重复code_read | 0 / 1 / 0次 | 1 / 1 / 1次 |
| 完整源码提议数 | 1 / 2 / 1次 | 4 / 4 / 2次 |
| 实际代码提议拒绝 | 2 / 1 / 1次 | 3 / 6 / 3次 |
| 全量无进展调用 | UNKNOWN | UNKNOWN |

读取/提议计数描述保留的SDK checkpoint；历史压缩后可能只是下界，空映射不证明整个任务没有重复工作。没有PLAN_READY事件也不能解释为零规划耗时。供应商负载和未回报的内部思考未被控制或分离；不能从以上下界推断Flash更省Token，也不能从Plus更快失败推断其性能更优。

本轮已经排除“文件没有交付”作为这六次失败的统一解释：六个任务的真实Parquet、当前fileId、原始manifest和39列映射均验证通过。日志显示生成代码错误、诊断程序零退出但缺rows.jsonl，以及无效/超长修复提议。Plus第3轮生成了两个可下载的Plotly草稿文件，SHA已核对，但未写出必需报告和关联引用，不计交付图表。实际长补丁反馈已经包含分段位置/长度、唯一锚点、当前Hash和版本，不能将未正确分段归因于模型根本没有得到提示。

对账竞态随后以 `e2a97cfac` 最小修复：仅对已有停止原因的原任务短时恢复对账，保持原预算，真实主动停止验证另列于交付报告。它解决状态收尾，不构成模型生成性能改善；此六任务仍属于C63版本，未冒充修复后六轮。正式性能验收仍不通过。
