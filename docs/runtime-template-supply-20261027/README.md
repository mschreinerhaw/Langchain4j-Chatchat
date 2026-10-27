# Runtime OS 资产选择与模板供数修复（2026-10-27）

已同步 `refactor/runtime-os-review-cleanup-20261026`：fetch、切换分支、pull --ff-only。基线 `bb5bf19774e124989dc5f54c95fa97006f3cd407`。本报告随修复代码一同提交。

## 实际根因

原始任务 `5397ba44-9025-4bac-a014-623df47bb391` 的日志证明候选选择/审查没有完成，随后任务被拒绝；它不能证明 MCP 实际取数失败，也不能证明候选一定选对。

1. 工作流为 `api_service_query → customer_service_template_query → api_template_execute`。中间节点已发布 `TEMPLATE_DISCOVERY / FIXED_BINDING / api_service`；资产由发布绑定关系确定，不应再次按业务需求选择基础设施资产。
2. `PublishedDataAcquisitionPlanner.compile()` 没有消除被固定发布覆盖的独立 ASSET_DISCOVERY，保留三个操作，无法满足唯一发现器/执行器的编译条件而返回 null。后续通用规划把客户号放进 filters.assetName，业务参数误入资产检索。
3. Coding 工具准入曾在候选选择之前返回 skipped/rejected，模型没有机会完成必要选择；Runtime 却报告 `the model did not select an asset id from the returned candidate set`。
4. 旧模板流程在首次模型选择后，仍调用独立 Coverage Audit，再通过 Java 分数/角色裁剪、跨资产业务关系判断改写选择，最后另调模型绑定参数。二次审查拥有否决权。
5. 优化器、DAG 校验、工作流完成检查和 ToolRuntimeService 还分别要求旧资产发现依赖。只改编译器不能修复整条链路。
6. 历史模型字段存在 snake_case/camelCase 差异；缺字段曾由 singleton/survivor 规则补齐。现在缺字段作为协议错误，不猜 ID，不执行全部候选。
7. 失败传播经过 Runtime → AgentOrchestrationEngine → AnalysisEvidenceCoordinator/AnalysisCoverageCoordinator → InterpretationAnalysisSession。空业务证据被统一升级为 REQUIRED_MCP_DATA_UNAVAILABLE，混淆了选择失败与 MCP 执行失败。
8. 现场还存在独立的模型超时：旧流程先遇到约 60 秒可选审核预算，调整后金融 Skill 绑定的 qwen3.8-flash 仍曾在配置的 180 秒 providerTimeout。请求 modelName 不能覆盖 Skill 已绑定模型。本次没有改模型、Skill 或权限配置。

## 决策边界和修改点

模型理解需求、选择业务资产/模板、判断覆盖并提取参数。Java 提供真实候选，执行 ID、权限、发布绑定、Schema 和数据证据校验。MCP 发布元数据并执行取数。Coding Agent 负责取数后的探索、计算和报告。

| 位置 | 修改 |
| --- | --- |
| TemplateWorkflowMetadata | 仅按 authoritative FIXED_BINDING、相同 assetType 和声明角色识别重复通用发现；另一固定发布不能替代。没有业务关键词或猜测 ID。 |
| PublishedDataAcquisitionPlanner | 固定发布编译为模板发现→执行→回答；compile 的 null 原因分类记录；其他复杂发布仍进入原有规划。 |
| InterpretationPlanOptimizer / Validator | 同步改写重复节点的依赖、边契约和绑定，防止补回通用资产发现。 |
| AgentToolNameResolver / AgentWorkflowToolResolver | 完成检查识别发布绑定已覆盖的通用发现能力。 |
| ToolRuntimeService | 仅在 authoritative interpretation_plan DAG 下满足被固定发布覆盖的通用依赖；其他依赖和权限继续检查。 |
| AgentOrchestrationEngine.selectPublishedTemplates | 成功模板发现直接进入一次选择与参数提取，绕开旧模板 tool_result_review、Coverage Audit 和 Java 业务关系否决。已完成选择可复用。Coding 取数后不进入旧语义审核；QA 原有有效审核保留。 |
| SchemaDrivenTemplateParameterBinder.selectAndBind | 一次原生 Function Calling：select_templates_and_bind_parameters。统一 status、selections[].templateId、parameters；仅发布真实绑定 ID 时允许 assetId。保留当前/历史资产身份和角色上下文供模型理解。 |
| CandidateEvaluator / AdmissionPolicy | 只将模型选择映射到实际候选 ID，不按分数、TARGET/CONTEXT、singleton 或剩余数量决定业务选择。合法选择不因旧审查 unavailable 而失效。 |
| InterpretationPlanRuntime | 独立返回模型不可用、响应缺字段、非法 ID、缺参数、无匹配等结果，不伪装成 MCP 执行失败。 |
| AnalysisCoverageCoordinator | 没有业务数据时交付明确的 DATA_SUPPLY_STATUS 技术回执，businessDataAvailable=false，业务行计数为 0；由模型解释输入不足或提出后续授权请求，不由 Java 生成业务结论。 |
| API CodingAnalysisService | 明确技术回执不含业务测量数据，禁止据此推导业务数字；保留授权、快照、Schema、结果证据引用校验。 |
| PlanCheckpointCoordinator | 恢复检查点允许合法 JSON null，避免 Map.copyOf 空指针；身份、提交边界和权限校验保留。 |

旧模板审查辅助方法为兼容历史代码暂留，正常模板发现入口已经提前进入新选择路径，不再调用这些独立审查。没有全局取消 QA 或固定工作流的确定性校验。

## 完整运行调用链

```text
InterpretationAnalysisSession
  → AgentOrchestrationEngine（完整已授权工具集）
  → PublishedDataAcquisitionPlanner.compile
  → Optimizer / Validator（消除被发布绑定覆盖的重复发现）
  → InterpretationPlanRuntime
  → ToolRuntimeService（权限、DAG、发布契约）
  → MCP customer_service_template_query（真实发布候选）
  → AgentOrchestrationEngine.selectPublishedTemplates
  → LLM select_templates_and_bind_parameters（一次业务选择、覆盖理解、参数提取）
  → CandidateEvaluator（真实 ID 投影）
  → Runtime 参数证据校验与模板执行协议编译
  → ToolRuntimeService（执行授权、输入 Schema、绑定检查）
  → MCP api_template_execute（真实绑定解析、执行、数据返回）
  → AnalysisEvidenceCoordinator（真实数据证据投影）
  → AnalysisCoverageCoordinator（授权快照或明确的技术回执）
  → CodingAnalysisService → Cline/Python（探索、计算、报告）
  → 基于真实输出发布报告
```

未固定绑定的普通请求仍为 Java OpenSearch 检索→模型选择真实资产 ID→模板发现→一次选择与参数绑定。不会将通用资产发现强加给已经固定绑定的供数模板。

OpenSearch ID 路径：`OpenSearchMcpSearchService.parseHits → SearchHit.id → ApiAssetDiscoveryMcpToolPublisher.byId → asset.id → 候选投影 → selectedAssetIds`。模型输入保留 id、databaseType、businessGroup、description、category 和 analysisIdentity，不以显示名称重建身份。

选择失败分支：MCP 候选检索成功 + 模型超时/缺字段/无匹配 → 保留 mcpDiscoverySucceeded=true 和独立 selectionStatus → 不执行未选择或非法模板 → DATA_SUPPLY_STATUS（非业务数据，业务行数 0）→ 模型说明缺失输入或提出授权请求。技术回执不是成功取数替代品，不能把该分支称为完成客户业务分析；它也不会赋予 Coding 任意调用 MCP 的权限。

compile 的 null 分类：MISSING_COMPILATION_CONTEXT、REQUIRED_TOOL_NOT_AUTHORIZED、AMBIGUOUS_OR_ADDITIONAL_REQUIRED_CAPABILITIES、NO_FIXED_PUBLICATION_AUTHORITY、UNBOUND_STAGED_DISCOVERY、INCOMPATIBLE_EXECUTION_PROTOCOL。

## 回归测试

最新相关回归 **385 项通过，0 失败**；API CodingAnalysisService **28 项通过，0 失败**。见 targeted-tests.json。

| 验收 | 覆盖 |
| --- | --- |
| 单模板、多模板、8 选 3 | TemplateSelectionAndBindingTest、PublishedTemplateSupplyTest、Runtime 标量与批量执行；没有数量兜底选择。 |
| 参数化模板 | 同一次模型调用提取；缺必填项、未知参数、无证据的伪造值不执行。 |
| 多资产、资产歧义 | 真实当前/历史身份供模型理解；显式多模板选择；绑定冲突与未授权工具拒绝。 |
| 无匹配、非法 ID、缺字段 | NO_MATCH 不自动选 singleton；混入未知 ID 整体响应失败；缺字段为 INVALID_MODEL_RESPONSE。 |
| 候选超时 | MODEL_UNAVAILABLE 保留模型错误分类，不写成 MCP 故障。 |
| 权限、绑定和依赖 | PublishedDataSupplyWorkflowTest 验证禁止未授权工具、另一固定发布不能替代、其他必需依赖不跳过。 |
| Coding Analysis | 一次选择/绑定，无独立审查；状态回执不作为业务行；真实文件输入仍需授权。 |
| 恢复 | 检查点身份、增量恢复、已完成选择复用；旧审查 unavailable 不触发重复选择。 |
| QA / 固定工作流 | 保留 QA 审核预算和有效工作流 guard、调度、计划变换校验。 |

目标日志：工作区 runtime-decision-boundary-final-tests.log。suite 清单与计数见 targeted-tests.json。

扩大检查：完整 AgentOrchestratorTest 145 项、72 个失败，原始基线该类 75 个失败，新增失败 0；ToolRuntimeServiceTest 仍为基线相同的 16 个失败，新增 0。见 engine-final-baseline-comparison.json 与 tool-runtime-baseline-comparison.json。历史测试桩/配置问题未作为无关修复处理，未宣称完整模块全部通过。regression-baseline-comparison.json 是前一阶段对照，不能替代最新结果。

API 完整 Maven reactor 的离线缓存缺少 langchain4j-skills:1.20.0-beta30，因此使用 Java 17、服务器实际部署包依赖及当前 agents 产物独立 javac 编译，并通过 JUnit Launcher 执行 28 项 CodingAnalysisServiceTest。未宣称完整 API reactor 已通过。

## 部署验证

服务器 192.168.195.221:5221，使用用户提供的信息登录。凭据、令牌和业务行不写入本目录。

最终部署以 deployment.json / build-manifest.json 为准。仅覆盖受影响 agents 生产类与 API CodingAnalysisService；保留模型绑定、权限、MCP 发布关系、配置和其他 ZIP entry。部署前检查无活动任务，保留旧镜像/容器；API 就绪后重新绑定共享网络命名空间的 node-proxy，并验证 nginx 与服务就绪。

中间 R4 打包复查发现附带两个 API 测试类，R5 已精确移除；最终包只有生产类，历史 manifest 保留追溯。没有通过修改模型或放宽权限完成验收。

最终包 SHA-256：`af96fe102dfaa8ac5b56e7ea5020fac415c45993c4c976b4e03687809418cef5`。
最终发布目录：`/opt/chatchat-releases/runtime-template-supply-20261027-r6`。已核验运行包摘要与 manifest 一致，未混入 API 测试类；API 正常运行，验证结束时活动父任务为 0、Python 沙箱容器为 0。

最终版本正式接口复测：

| 请求 | 真实结果 |
| --- | --- |
| 普通 QA：1b5c5e85-c5de-3138-b88f-ececf03f65ec | SUCCEEDED，约 192 秒，报告 2683 字符；模型 deepseek-v4-pro，见 selection-qa-evidence.json。 |
| 原业务请求：3fcb79d9-6d88-3a8a-8d97-bc31209fb75b | MCP 模板检索约 245 ms 成功，返回 10 个真实模板；一次选择/参数提取在 Qwen 的 180 秒超时，记录 MODEL_UNAVAILABLE、mcpDiscoverySucceeded=true、toolResultReviewModelCalls=1。未执行未选模板，未获得业务数据，见 selection-analysis-evidence.json。 |
| 原业务后续 Coding：analysis-coding-ee5d35050bd5d702c411e59a55173d9a88b8f560a5f409be2e9f9e9148edda1b | 已进入 PRIMARY_CODING_ANALYSIS（交付 1 个技术回执数据集）；同一 Qwen 模型调用约 180262 ms 后失败。父任务继续等待结果核对，保留失败证据后，通过正式取消接口结束本次验证任务，最终 CANCELLED。见 selection-live-validation.json / verification-cleanup.json。 |

因此：职责边界修复、相关回归、部署与普通 QA 验证已完成；**原业务真实取数与分析尚未验收通过，阻塞于实际模型调用超时**。不能将回执、取消或启动成功记为业务分析成功。此次没有通过放行全部候选、修改模型绑定或绕过结果核对制造成功。

剩余限制：模型失败后父任务的结果核对仍会等待，当前通过正式取消结束验证；本次未更改执行结果核对的安全边界。更早的失败与 QA 记录在 original-failure-evidence.json、qwen-timeout-evidence.json、qa-evidence.json，仅供历史对照。
