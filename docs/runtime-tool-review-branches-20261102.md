# tool_result_review 分支梳理与局部优化

基线：`851c7b084`（包含此前审查报告）；日期：2026-11-02。此次调整 `AgentOrchestrationEngine.reviewInterpretationPlanToolResult` 的准入识别和监控，并根据联机失败补充 Coding 输入契约说明；不迁移 Agent / Runtime 职责，不删除选模或恢复协议。

## 1. 分支清单

以下按方法实际判断顺序排列。E 指 `chatchat-agents/src/main/java/com/chatchat/agents/orchestration/AgentOrchestrationEngine.java`；R 指 `chatchat-agents/src/main/java/com/chatchat/agents/runtime/plan/InterpretationPlanRuntime.java`。E 行号为本次修改后的工作树，R 未修改。

| 顺序 | 触发条件 | 是否进入通用 String chat | 判断与本次处置 | 位置 |
| --- | --- | --- | --- | --- |
| 1 | 请求被取消 | 否，抛取消异常 | 保留取消优先级 | E:3474 |
| 2 | 成功资产发现，queryIr 已声明合法选择 | 否 | 复用规范身份，已有优化 | E:3479 |
| 3 | 成功资产发现，完整页上用户精确指名唯一资产 | 否 | CanonicalAssetReferenceReviewer 判断否定、比较与歧义，已有优化 | E:3490 |
| 4 | 失败的资产 / 模板发现 | 否 | 保留 MCP 原始错误，后续 Runtime 决定步骤状态，已有优化 | E:3494 |
| 5 | 成功模板发现 | 否；另走选择 Function Calling | selectPublishedTemplates 复用选择或调用合并的选择/绑定函数。不能把此处分支计为通用 reviewPrompt，也不能去掉必要选择 | E:3499、3864 |
| 6 | Coding 所有的非候选结果 | 否 | 成功接受、失败拒绝；候选发现明确不在此捷径内，已有优化 | E:3505 |
| 7 | 模型 / 请求 / execution 不可用，且未提前返回 | 否 | 保留原兼容返回 | E:3518 |
| 8 | 成功 TEMPLATE_EXECUTION | 否 | 供应端执行结果确定性准入，已有优化 | E:3527 |
| 9 | 成功统一发布模板能力，角色为 DIRECT | **修改前是，修改后否** | 注册元数据同时声明 templateCapability=true、schemaVersion=template-capability.v1；复用第 8 项准入 | E:3525、4355 |
| 10 | 成功 Web 发现，无直接依赖当前步骤的爬虫 | 否 | hasDownstreamCrawler 只检查现有直接依赖；本次不改路由算法，已有优化 | E:3544、hasDownstreamCrawler |
| 11 | 成功 sql_schema_context_query / enterprise_metadata_search | 否 | 已有按工具语义名判断的元数据捷径；没有扩展到任意 metadata 名称 | E:3560、isMetadataDiscoveryTool |
| 12 | 其他满足模型与 execution 上下文的结果 | **是** | 见下一表，新增明确用途和实际调用计数 | E:3575—3619 |

方法外还有准入边界：R.reviewToolResult:2901 对有序 batch 子结果跳过再审；R.shouldSkipModelReviewAfterLocalFactCheck:3502 对已本地验收的非候选结构化结果跳过模型。故“调用本方法的测试”不等同于“每个线上工具都会走到该方法”。没有用工具名数量推测线上调用频率。

## 2. 仍进入通用 chat 的调用及必要性

本次为每次真实通用调用增加 `toolResultReviewPurpose`。用途只解释调用消费者，不授予权限，也不改变原审查输出。

| 用途 | 条件（按判断顺序） | 实际消费者 / 为什么保留 |
| --- | --- | --- |
| ASSET_SELECTION | 资产角色，既无可复用选择，也未命中精确引用 | E.review 解析 selectedAssetIds；R.applyRuntimeAssetSelection:8880 调 evaluator，再由 semanticCandidateSelectionError:3383 决定是否可以依赖该选择。不能凭 singleton 或排名代替业务选择 |
| EXECUTION_RECOVERY | 非资产的执行失败，且未命中前面失败发现 / Coding 捷径 | E 解析 missingParameters、retryInputChanges、templateReselectionRequired、nextActions；R.appendTemplateExecutionReviewContract:3197 接续模板修复，普通工具还可能产生恢复行动。取消会改变有界修复能力 |
| DOWNSTREAM_URL_SELECTION | 成功 Web 发现且 hasDownstreamCrawler 为 true | R.selectedUrlsFromCompletedWebSearch:8569 优先收集模型选择 URL，再补充原输出 URL；PlanStepInputCompiler.compile:51 在爬虫未给 URL 时使用优先列表首项。模型选择确实能改变后续获取目标，不能直接认定多余 |
| DOCUMENT_EVIDENCE_SELECTION | 成功文档搜索，未命中此前捷径 | `chatchat-agents/src/main/java/com/chatchat/agents/orchestration/tool/ToolObservationBuilder.java` 的 applyEvidenceEvaluationSelection:1515 根据 usefulEvidenceRefs/rejectedEvidenceRefs 筛选 chunks；引用与最终证据消费仍使用这些字段 |
| EVIDENCE_ASSESSMENT | 其余成功普通工具、成功爬虫/内容工具等 | evidenceEvaluation、evidenceIterationSufficient、missingEvidence、nextActions 被 `orchestration/evidence/InterpretationPlanEvidenceAnalyzer.java`:480—503 及 R.executionLock:4127 消费。不能只因为最终还有 synthesis 就断定没有作用；本次保留，并通过日志定位后续候选 |

其中 ASSET_SELECTION、URL 与文档选择有明确选择消费者；EXECUTION_RECOVERY 有恢复消费者；EVIDENCE_ASSESSMENT 范围最宽，适合下一轮按真实工具、执行事实及消费者使用率继续收缩。这个分类是本次审查边界，不代表所有 EVIDENCE_ASSESSMENT 调用都已被证明不可取消。

## 3. 发现并修复的遗漏

`chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/templatepublication/publisher/TemplateCapabilityPublisher.java` 的 contribute:23 在 52 行发布 templateCapability、schemaVersion、operationType 等元数据，但没有 workflowContract。`chatchat-common/src/main/java/com/chatchat/common/tool/ToolWorkflowContract.java` 的 resolveRole:49 在未声明角色时返回 DIRECT。此前成功发布模板因此不满足 TEMPLATE_EXECUTION 快捷条件，在普通 QA 中落到通用审查。

该发布者的 handler 在执行时调用 `TemplateCapabilityService.invoke`（同模块 templatepublication/admin，方法:133）：实时验证可用性与调用人、验证参数 Schema、注入固定资源 binding、调用 executor、记录 publication revision 和审计。执行成功之后，无需再为“是否接收执行事实”支付一次普通业务匹配审查；这与已有 TEMPLATE_EXECUTION 处理一致。

本次新增 `isPublishedTemplateCapability`：

- 只从 ToolRegistry 返回的 ToolMetadata 读取声明，兼容直接 metadata 和远程集成的 mcpToolMeta 包装。
- 同时要求严格 Boolean 标记和已知契约版本；未知版本保留原通用路径。
- 仅用于 DIRECT 角色且 execution.success 为 true；发现角色优先执行选择，失败执行保留恢复审查。
- 工具名叫 tpl_*、输出中伪造发布标记都不能触发捷径。空行或截断事实不被重写为“业务需求已满足”。

此修改影响普通 QA / 兼容执行路径中成功的已发布模板。DATA_ANALYSIS 非候选结果此前已有 Coding 准入，所以不能把本次修复计成每次数据分析都额外减少一次模型调用。

## 4. 监控改进

通用调用现在携带：

- `toolResultReviewMode=MODEL_TOOL_RESULT_REVIEW`。
- `toolResultReviewPurpose`：以上五类用途。
- `toolResultReviewModelCalls`：本次 invocation 的实际尝试数，准备阶段为 0，调用模型前变为 1；不是跨重试累计计数。
- 原有 promptChars、estimatedTokens、preparationDuration、modelDuration、totalDuration。无法解析 / 返回 error 的降级路径现在也保留这些指标，避免调用过模型却在结果上漏记。

MODEL_INFERENCE 开始/终止事件携带用途，终止事件携带本次调用数；请求日志也包含 reviewPurpose。模型调用抛异常时沿用原异常传播，终止事件仍记录是否已尝试调用。没有为确定性准入伪造模型推理事件。成功模板执行准入明确记录 modelCalls=0。

指标用途是定位真实等待，不把 estimatedTokens 当真实计费 Token；分支单测使用模拟模型；下述联机验收调用了实际模型，但不是受控性能对比，不承诺生产耗时或成本减少比例。

## 5. 验证

定向 Maven 测试 **87 项通过，0 failure / error / skipped**：

| 测试类 | 项数 |
| --- | --- |
| ToolResultReviewBranchTest（新增） | 13 |
| ToolReviewerOptimizationTest | 14 |
| CodingToolReviewBenchmarkTest | 1 |
| SchemaDrivenTemplateParameterBinderTest | 17 |
| CanonicalAssetReferenceReviewerTest | 9 |
| EvidenceBasedAssetCandidateEvaluatorTest | 7 |
| EvidenceBasedTemplateCandidateEvaluatorTest | 26 |

新测试同时覆盖发布声明两种包装、截断事实保留、失败恢复建议、工具名/输出不能冒充声明、未知版本、发现角色优先、爬虫 URL 选择、文档引用筛选、异常格式降级统计和取消优先级。调用次数由 Mockito 验证：本次优化目标为 0，保留的通用分支为 1，模板选择仍使用 ChatRequest。

测试命令：

```powershell
mvn -pl chatchat-agents -am test `
  '-Dtest=ToolResultReviewBranchTest,ToolReviewerOptimizationTest,CodingToolReviewBenchmarkTest,CanonicalAssetReferenceReviewerTest,EvidenceBasedAssetCandidateEvaluatorTest,EvidenceBasedTemplateCandidateEvaluatorTest,SchemaDrivenTemplateParameterBinderTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-DfailIfNoTests=false' '-Dstyle.color=never' -B
```

日志：`target/runtime-review-branches-20261102.log`。没有运行全仓回归或真实 LLM 性能基准；指定问题的联机验收见下节。此前审查中已记录的旧 SQL 范围断言失败不属于本次所选测试，不能据本次 87 项绿灯宣称全仓测试通过。

## 6. 联机发现与最小修复

指定问题为“分析 Docker 数据库模拟服务器 管理的镜像和容器运行情况”，使用“服务器主机运维助手”（server_ops_assistant）。首次任务 `51fdccc4-58b7-341b-9ae3-16cb7025a93a` 失败：四项模板均成功供数，Coding 却把 Parquet 物理列按位置解读，混入 pN 存在标记，四次执行分别发生列数、JSON 解析及类型错误。此失败属于生成代码读取契约错误，不是模板关系或 selected asset ID 拒绝；保留[首次记录](runtime-tool-review-branches-20261102-evidence/first-attempt.json)，不记为通过。

`chatchat-api/src/main/java/com/chatchat/api/runtime/coding/analysis/CodingAnalysisService.java` 的 tableInput 指令（351 行起）补充通用读取规则：以 manifest 的 storageName/name/type 为准；区分 cN 数据列与 pN 布尔存在列；按类型解码 JSON；先恢复逻辑字段再读取 record。没有加入 Docker 专用分支，也没有改变供数、权限或验收规则。该说明可减少同类误读，但不是对 Agent 生成代码的确定性保证。

补充验证为 CodingAnalysisServiceTest 39 项、CodingSourceRepresentationTest 5 项通过，并重复运行 Agent 分支相关 27 项通过。合并前述 87 项，去重后共 **131 项 Java 测试**通过。既有 Python 输入协议测试在无网络隔离容器中 **3 项通过**。测试数量与日志哈希见[测试记录](runtime-tool-review-branches-20261102-evidence/tests.json)。未运行全仓回归。

## 7. 最终部署与业务验收

已部署至 `192.168.195.221` 的现有 Docker stage，入口 [应用环境](http://192.168.195.221:18100)，最终镜像 `runtime-os-api:tool-review-branches-20261102-r2`，发布目录 `/opt/chatchat-releases/tool-review-branches-20261102-r2`。

部署前分别编译 HEAD 的原始 Engine 与 CodingAnalysisService，以 javap 比对运行 class，确认反汇编一致。两轮部署仅替换对应 class，保留 JAR 其他条目；新镜像从不含业务 JAR 的既有运行时基础镜像构建。原容器、JAR 和配置保留用于回滚。node-proxy 原来绑定过期 API 网络空间，已重绑当前 API；node-proxy、gateway、Coding Agent 健康，首页 HTTP 200、匿名鉴权 HTTP 401、登录后接口 HTTP 200。没有清理业务镜像、容器或卷。

最终产物 SHA-256：

- 应用 JAR：`aa5fe8505bf106319eefaadbefa05a6548c5e647f7e4010c63470937b252d12c`。
- Engine class：`86945086cc9ae7dc2a3b57e398bdacfd7c4188872b080e90f26cadf11ff2adc2`。
- CodingAnalysisService class：`664bb40494d7b89c1172b2aee37514eed08b20ebd4b3e562049c636e1ac06ddf`。
- 两个 Java 源文件相对基线的 diff：`650506fab01ea88c23b41fbb88f60ba048d08ada7c3b3b5298c7899e4fdd4c55`。

第二次按原问题复测，任务 `7318dbbd-59ff-38b7-9f0b-d0fbb84fff59` 为 **SUCCEEDED**，Coding 为 **COMPLETED**，执行器已停止，无待协调或停止原因。生成 6193 字报告及 2 项引用；四个供数模板均 SUCCESS、exitCode=0、complete=true。逐项核对采集回执：**19 条镜像记录、18 个唯一镜像 ID、11 个容器，其中 4 个运行、7 个退出**，运行统计为 4 条。

复测不是一次执行即成功：Coding v1 发生 KeyError，修复后 v2 至 v5 均执行成功；保留完整状态摘要与使用量。生成代码读取了实际 manifest、part 并校验哈希，使用与该输入一致的 c0/c1 映射；不能据此声称代码已通用遍历任意 schema。结构验收状态 INCONCLUSIVE 不代表业务建议已独立认证，本次人工核对上述核心数量；报告中的清理建议未执行，OOM 等诊断推测未独立证实。

**联机覆盖边界：**该 DATA_ANALYSIS 请求走合并选模与有序批量准入，选模调用 1 次，批量结果跳过模型复审；没有触发本次新增的 DIRECT 发布模板分支。新增分支的 0 次模型调用由定向单测验证，不将这次联机结果当作生产模型等待减少的测量证据。

脱敏证据：[最终验收](runtime-tool-review-branches-20261102-evidence/acceptance.json)、[供数回执](runtime-tool-review-branches-20261102-evidence/source-receipts.json)、[最终部署](runtime-tool-review-branches-20261102-evidence/deployment.json)、[健康检查](runtime-tool-review-branches-20261102-evidence/health.json)、[首轮部署](runtime-tool-review-branches-20261102-evidence/deployment-r1.json)。交付目录不含登录凭据、会话令牌及原始业务数据。
