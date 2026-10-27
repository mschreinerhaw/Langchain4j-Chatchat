# 实际回归与端到端证据

日期：2026-10-27。服务器：`192.168.195.221:5221`。鉴权经正式 API；未增加测试专用工具权限、未替换业务数据、未直接放行候选。

**整体验收：未通过。** 固定发布链在最终版本真实成功；早期 R3 有全局完整成功样本，但最终版本的全局任务尚无报告完成证据，不能拼接不同任务宣称最终全链路通过。

## 自动化测试

各次运行存在测试重叠，下面不合并成一个“总通过数”。日志摘要、具体测试类与日志 hash 见 [regression-test-controls.json](regression-test-controls.json)。原日志在工作区，未将完整运行日志提交进 Git。

| 运行 | 结果 | 范围 |
|---|---|---|
| 最终空集与供数边界 | Agents 68 + API 11，共 79 项通过 | Envelope 投影、EMPTY、供数状态、禁止无数据 Coding、Parquet/CSV 独立读取、Schema 与快照 |
| 最终选择边界 | 75 项通过 | ID/参数/模板选择、发布链、Validator 与 Coverage |
| 模型派发与对账 | Agents 42 + API 62 项通过 | 本地失败的确切停止回执、provider 未调用、未知操作不重放、成本保留释放、回调与权限 |
| 初始 API 定向回归 | 95 项通过 | Coding、证据、输入发布、不可变快照及持久化 |
| OpenSearch 客户端 | 9 项通过 | HTTP 协议、安全、deadline/bulkhead 与批量；另有 25 项 MCP discovery 测试通过 |
| Node | 最终 102 项通过；生产镜像内定向 47 项通过 | SDK 迁移、代码版本/patch/rollback、授权、输出契约；新增旧补丁内引用修复及非法引用不能成为已验证回滚点 |
| 前端 | 10 项通过；Vite build 成功 | CODE_ARCHIVED 与进程/结果状态区分；监控组件 |
| 审计/部署脚本 | Python unittest 5 项通过 | 不修改无关制品、明确允许静态资源、统计未知用量、组件索引 |
| SDK → Java 工具协议 | 实际 SDK 导出 1 个 Python tool，当前打包 LangChain4j 1.20.0 解析成功 | 不是手写 schema Mock；没有调用外部 provider，也不冒称业务集成 |
| Maven package | 成功 | 正常 frontend build 后 backend-only reactor package；后续修改通过定向 reactor 测试重新编译 |

两组既有失败没有隐瞒：

- 广泛规划回归 261 项中 6 failures、3 errors，均为 AgentPlannerTest 的 9 个既有失败；在部署源码基线独立 worktree 重现相同测试方法，未引入新增失败，亦未修复这 9 项。对照见 [planner-baseline-comparison.json](planner-baseline-comparison.json)。
- 扩展 QA 回归 89 项中，`reviewerTimeoutUsesConfiguredModelTimeout` 耗时约 5 秒未满足 3 秒断言；部署源码基线同样失败。随后聚焦 QA/任务恢复测试通过，但不能称全量测试全绿。
- 最初空集回归发现 Bridge 测试仍期望“无 dataset”，与已建立的“零行 dataset”交付契约冲突；修正为验证真实零行、sourceVerifiedEmpty，而非虚构一行，最终 79 项全部通过。

## 真实业务完整链路：R3 成功样本

任务使用现有 `busi_data_query`，请求分析已有客户资产负债 API，并独立给出实际已返回 SQL 数据源的只读目录概览；不虚构客户与数据库目录之间的业务关联，不执行模型自拟任意 SQL。

| 证据 | 唯一标识 / 实际结果 |
|---|---|
| 父任务 | `72317e82-9886-3884-b4e4-f6629d07d898`，SUCCEEDED |
| Run | `att-1-c592a949-99a0-4fc2-a1a1-b345b5299706` |
| 选择与执行 | OpenSearch 实际候选；模型完成资产/模板选择；API + SQL MCP 实际供数，模板执行批次 1 次 |
| Coding | `analysis-coding-72bccf9b620fa05806f73eb4c63b2ec4383649e7939cc2928b192bb0485f7eff`，COMPLETED；派发 1 次 |
| Python operation | `cline-578d5955fe016359d3c87f2a99c11726e586d14780f2de03adb72ac699587252`，SUCCEEDED；exitCode=0，executorStopped=true，容器已清理 |
| Code hash | `d9b10d0144b49165b92bfea5594ce88c72a55f671297992a2ab49c0b465e60ff` |
| Manifest hash | `a4e4cde1db922e6a21edbb28496d012d1dd5494fed6ea52acbda02c87465fd78` |
| 输入统计 | 6 个输入数据集；处理记录数 584；其中客户 API 报告依据 5 条实际业务记录，其余含目录数据。584 不能当成 584 个客户 |
| 结果引用 | `runtime-evidence:coding:03e325746a1df5b41eb8147dad4873aba3a0accb4b8a50cda86b192a368170b4:result`；resultAvailable=true |
| 报告 | 已授权读取实际归档报告，包含业务分析及独立目录概览。业务内容留在私有证据区，不入 Git |

技术执行与归档通过；`validationStatus=INCONCLUSIVE` 表示没有通过机器规则证明所有业务结论，不能写成业务正确性全部 PASSED。Coding 模型基于真实报告完成审阅；Java 既不替模型认证业务结论，也不因没有额外业务认证而抹去真实执行证据。

### 调用与耗时

[原始控制 trace](e2e-customer-catalog-trace-r3.json)：

| 阶段 | 次数 | 模型耗时合计 | 输入 / 输出 Token |
|---|---:|---:|---:|
| Java 计划 | 1 | 97,627 ms | 5,296 / 5,689 |
| Java retrieval enrichment | 4 | 232,321 ms | 14,599 / 13,480 |
| Java 资产选择入口 reviewInterpretationPlanToolResult | 2 | 107,860 ms | 4,789 / 6,258 |
| Java 模板选择与参数 nativeCall | 2 | 113,338 ms | 4,119 / 6,598 |
| Coding provider_call | 2 | 115,736 ms | 35,274 / 6,684 |

端到端 **611,432 ms**。Java 9 次模型耗时合计 551,146 ms，存在并行，不能与 Coding 耗时相加当墙钟时间。模板批次 2,288 ms；Python 往返 4,040 ms，其中进程 1,218 ms、启动 743 ms、退出至归档 224 ms。首次 Python 即产出结果，Python 执行 1 次。Java 内容分析模型调用为 0；仍存在检索增强可优化，不代表完全消除了 Java 侧模型调用。

同一 phase 多次可能对应不同资产或检索调用；静态名称或相似 Token 数不足以断定重复。应依据同一输入/配置/快照的指纹比较，再合并缓存或条件跳过，不能禁用全部语义选择。provider cache 未记录，保持 unknown。异常路径可能丢失最终模型计量，trace 中 observed=0 不代表实际未调用或 Token 为 0。当前样本不支持宣称成功率或 P95 改善。

## 真实失败样本及收敛效果

| 样本 | Task / Run | 实际结果与含义 |
|---|---|---|
| 错误模式预探针 | `4746488e-8755-30b1-9211-91b3b56817cf` / `att-1-be166283-efcc-46fe-b102-c0c4e25416c9` | 未进入完整供数；发现本轮初版 schema oneOf 不兼容并已修正。原探针经正式 API 取消，不列为成功 |
| 资产选择缺字段 | `63e0d5da-3052-3810-9b0f-2da614ba28cf` / `att-1-fa85217b-727e-4e33-8838-468bbfcce9d5` | 真实 MCP discovery 后模型未给出合法资产 ID；DATA_SUPPLY_SELECTION_FAILED，Coding 0 次。没有假装 MCP 执行失败，也没有全选候选 |
| 运维修改前 | `d4a6b754-51fc-38d0-8b8f-90f28058e422` / `att-1-d52ccc27-7117-4619-b7f4-b89b7e3c6141` | 重复 Planner 3 次，均被旧工具名依赖规则拒绝，未执行工具 |
| 运维规则统一后 | `08c6d34f-7e43-375d-ae4e-dcb157543004` / `att-1-6a664161-7522-4a4d-82d0-518ea337b628` | 规划后选中并真实执行 5 个模板，但 SSH target 上下文均未命中；DATA_SUPPLY_EXECUTION_FAILED，Coding 0 次。DEV 登记库有主机，不能归因为缺主机，更不能算 SSH 通过 |
| API-only 空集 | `9acb9b3f-e033-3a62-ad38-245c7708b4b9` / `att-1-4072c853-65f2-4369-a955-d8e177cbe8ed` | 真实成功 API 的 records=[] 被 Adapter 丢弃；据此补充严格空集交付回归并部署 R4 |

API-only 夹具仅临时缩小既有 Agent 的必需工具与指定模型，没有新增任何工具授权。任务配置冻结后立即恢复原 Agent；恢复前后 fingerprint 一致，见 [fixture-control.json](fixture-control.json)。不能把临时缩小的测试工作流当成线上默认流程。

## 最终候选 R4 验证

R4 首次重复原请求：`a44428ac-8117-3a20-bb23-53e249ac281e` / `att-1-afa82914-f473-4ea1-8f16-95cd2cfe70be`，468.844 秒后 FAILED。模型选中了真实模板，但明确返回 NEEDS_INPUT，要求补充 START_DATE/END_DATE；系统返回 DATA_SUPPLY_SELECTION_FAILED，Coding/Python 均为 0。它是合法的缺参数负例，不是 MCP 故障，也不能自动代填日期。控制证据见 [e2e-missing-dates-control-r4.json](e2e-missing-dates-control-r4.json)，原错误中含参数值，仅导出错误 hash 与状态。

补齐日期 2026-08-03 至 2026-08-07 后，新任务 `eaf0c820-dc8d-3e5d-b98d-1ab61444e771` / `att-1-fdfd5d7d-da42-43d0-bec5-c9f8652a9832` 取得 5 条实际客户记录，进入 Coding。数据库分支本次为 NO_MATCH，未强制执行。

该任务最终 FAILED，端到端 806,408 ms；实际 Python 4 次均 exitCode=0、执行器停止，前两次没有合同要求的结果文件；后续归档结果引用不合法。Coding 用满 12 次模型调用，事件明确包含 PATCH_REQUIRED 和 REGRESSION_ASSERTION_FAILED。不是“生成代码后未执行”，也不是 OpenSearch/MCP 故障。错误传播后 Java 模型调用计量未完整保留，trace 的 observed=0 **不是**实际零调用。

根因：CodeHistory 把一次补丁的任意 newText 当作不可修改的“回归断言”；且输出引用未通过时已把代码记为 VALIDATED。后续合法引用修正被旧文本规则挡住。证据：[e2e-code-freeze-control-r4-node-r2.json](e2e-code-freeze-control-r4-node-r2.json)、[失败 trace](e2e-dated-failure-trace-r4-node-r2.json)。保留失败历史，不以候选全选或略过引用校验制造成功。

据此实施 Node R3：取消自动代码文本冻结；保留父版本/hash/精确 patch/回滚新执行以及 Java 全部授权与文件/引用校验；引用失败记为结果不可用，不能成为已验证回滚点。系统提示词同时明确模型拥有业务理解/选择/分析权。最终 102 项 Node 测试通过，真实生产镜像与 SDK 内定向 47 项通过；部署证据见 [deployment-node-r3.json](deployment-node-r3.json)、[源码及模块 hash](node-r3-source-manifest.json)。

在 API R4 + Node R3 上用**同一份补齐日期的请求**重新执行，新任务 `09deeb65-b2f0-3836-935f-2f2ad42fa0a6` / `att-1-68ee37fc-e1c0-4fd0-b94d-4db4e943f608`，长时间停在首次规划模型请求，尚无 MCP/Coding/Python。通过正式取消接口结束这条自建探针，保留 CANCELLED 及调用已发出的事实，不记为执行成功，也不记为 MCP 失败或零用量。

### 最终发布供数链：成功，含真实空集

复用现有 `financial_indicator` 角色和原授权；单一发布供数链不等于只选择一个模板。模型从 **10 个真实候选中选择 2 个模板**，抽取客户与日期参数；一个模板返回真实零行，另一个交付 5 条实际记录，最终报告明确区分空来源与有效来源。

| 阶段 | 最终候选实际证据 |
|---|---|
| Task / Run | `bd79caa2-e8e7-3de2-810a-044acf64a8be` / `att-1-07529897-46fe-4027-b0fe-f50de466a6e8`，SUCCEEDED |
| 模型决策 | Java 1 次 selectAndBind；实际模型 `qwen3.7-flash-2026-07-15`；没有追加业务审核 |
| 供数 | MCP 查询和 API 模板实际执行成功，2 个输入数据集，处理 5 条记录；真实空集没有伪造记录 |
| Coding | `analysis-coding-4afeaf215b302defdf7316eedf4690d07cfb08ff8c646f6f7a345958807dfa0c`，COMPLETED；2 次模型调用，派发 1 次 |
| Python | `cline-acd3f7b91add73aad1f5710d0c0cdf7da90b25ce8b19ad2e7956fbb082f7a74b`，SUCCEEDED，exitCode=0，执行器停止、容器清理 |
| Code hash / Manifest hash | `d9abca7fc2dc27437471802ffc7b53075d827cd7683f8732aaabc0ad0ce487fb` / `921b386a7c985535f1be6099fabfa903ebf2d6204cbe3ec5fa70e6fd3e4bf60d` |
| 结果 | resultAvailable=true；validationStatus=INCONCLUSIVE、businessValidation=NOT_EVALUATED；报告 2,274 字符，正文留私有证据区 |
| 耗时 | 107,095 ms；模板批次 1,123 ms；Python 往返 4,323 ms，进程 1,530 ms；首次执行交付结果 |

证据：[最终 trace](e2e-final-single-published-trace.json)、[候选/参数/工具控制记录](e2e-final-single-published-control.json)、[实际执行 hash 与状态](e2e-final-execution-control.json)、[真实空响应控制字段](e2e-final-empty-source-control.json)。正式网关 HTTP 200 返回的前端 index hash 与 R4 发布资源一致，见 [部署核验](deployment-verification-r4.json)。

该用例走固定发布候选，**本次没有 OpenSearch 查询回执**，不能冒充全局资产检索验收。107 秒与早期 611 秒来自不同角色、模型及数据量，不能当作同样本性能提升。空来源的实际 response 控制字段为 retu_code=0、memo 为空；未将错误响应包装成空数据行。

### 最终全局多工具：批次预算拒绝，未通过

`0c47da68-7c18-3eca-8964-14cb9783eb8e` / `att-1-bb173590-a3a5-4280-aeed-aa5902cb76d7`：保留既有 `busi_data_query` 全部 5 个原工具，任务冻结 DeepSeek 后立即恢复原 Agent。指纹证明权限、工具授权不变，原步骤未删，见 [fixture-control-final-global.json](fixture-control-final-global.json)。

真实全局调用最终 FAILED：API 模板查询的输入展开为 20 个独立批次，超过 16，MCP 返回 QUERY_BATCH_BUDGET_EXCEEDED；最终归类 DATA_SUPPLY_EXECUTION_FAILED，Coding/Python 0 次。资源上限正确生效，但 API 查询预算说明、扩词生成及错误参数阶段归类尚未统一，不能称架构整改全部完成。见 [e2e-final-global-budget-failure-control.json](e2e-final-global-budget-failure-control.json)。没有提高批次上限、截断关键词或改选资产强行通过。

### 最终聚焦全局检索

再构造范围明确的客户资产负债任务，保留全局 API 资产检索、模板检索和模板执行三步骤，原有权限及 5 个工具授权不变。任务 `26fe57f0-1316-359e-ba1b-ba07089ba772` / `att-1-5aa608f9-26be-4a29-97ff-aa11f68b6e42`。临时夹具在冻结任务后恢复，见 [fixture-control-final-focused.json](fixture-control-final-focused.json)。

本次有 **8 条实际 OpenSearch 查询回执**，资产检索、模型资产选择、模板选择与参数、MCP 执行均成功；已启动 Coding `analysis-coding-890e2372279a526d830f34ab0a188c124b0455bec32b68fbcf1bb60bf7fcc06c`。其首个模型调用长时间未返回，Python 尚未启动。随后记录为 CANCELLED；本轮检查时已经存在 tenant cancellation，本轮取消脚本没有重复提交取消或重试。证据：[全局阶段控制记录](e2e-final-focused-global-control.json)、[OpenSearch 回执](e2e-final-focused-search-receipts.json)。

取消后 Node 已为 CANCELLED；Durable Coding outcome 仍为 RUNNING/CANCEL_REQUESTED，executionControl.executorStopped=false、reconciliationRequired=true，模型回执等待对账。不能用 Node 已取消冒充 provider 已停止或用量已结算，也不能重放同一未知操作。见 [取消及待对账记录](e2e-focused-model-cancellation-control.json)。观察器发现父任务终态但 Coding 尚未结算后停止，没有生成一个虚假的完整成功 trace。检查时父任务活动数为 0，无活动 Python Sandbox；这不等于所有未知模型操作已清零。

另以已验证可用的 Flash 配置运行相同聚焦问题，`dffa0d5e-3314-3d91-b65a-e3d47d7a3a94` / `att-1-01b53a09-6f99-4b4c-b0ee-495d8d113a93`，规划阶段 request timed out，尚无 MCP/Coding；trace 端到端 39,310 ms，初始模型阶段约 30 秒。丢失的异常模型用量保持未知，不能将 trace observed=0 当实际零调用。见 [配置恢复指纹](fixture-control-final-flash.json)、[超时 trace](e2e-focused-flash-timeout-trace.json)。这表明固定发布链通过不能替代通用规划链验收。

完整成功还需要关闭本轮明确发现的预算协议、可编译流程回退、长模型调用/取消对账及配置恢复问题。当前不按全部通过上线验收，不增加重试或放宽权限/结果检查掩盖失败。

## 可查询证据及复跑方式

- PostgreSQL `runtime_os_acceptance_api.agent_task_latest` 按 task_id，`agent_runtime_run` 按 run_id；读取正式任务 API、Runtime run 与 `/agent/runtime/runs/{runId}/coding` 查询相同标识。
- MCP `runtime_os_acceptance_mcp.mcp_python_managed_operation` 按 run_id/operation_id，核对 status、exit_code、executor_stopped、code_hash、manifest_hash。
- OpenSearch 控制日志 `MCP_SEARCH_RECEIPT` 以 requestId 关联 Runtime 请求，以 searchRequestId+index+phase 定位查询。并非依赖业务行或未经执行的计划证明检索。
- 服务器 `/opt/chatchat-releases/runtime-governance-20261027/` 保存各场景的正式 API、Node、Python 原始证据；R4 证据在 `/opt/chatchat-releases/runtime-governance-20261027-r4/customer-catalog-e2e/`。权限受限，Git 不复制明文业务结果。
- `scripts/benchmark-runtime-analysis.py` 从调用方提供的正式 auth/request 文件运行，不内置业务模板、资产或公式；`scripts/collect-runtime-template-supply-evidence.py` 只导出控制信息。新一轮使用新 output-dir，已有任务用 first-task-id 观察，不能自动重复提交未知执行。
- 本轮未完成所有协议、所有租户和所有重启组合的真实验收，后续条件与风险控制见 [分阶段任务](README.md#6-最小执行方案与后续分阶段任务)。
