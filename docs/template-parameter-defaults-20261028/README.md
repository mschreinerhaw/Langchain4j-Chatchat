# Runtime OS 模板参数绑定与默认值修复

目标分支：`refactor/runtime-os-review-cleanup-20261026`。审查基线：`fda81112f`。本次根据实际源码修复，未改变助手、模型绑定、资产授权或模板内容。

## 已证实的问题与根因

1. `SchemaDrivenTemplateParameterBinder.selectionDecision()` 曾以任何非空 `unresolved_parameters` 返回整个选择结果 `PARAMETERS_REQUIRED`，没有依据实际 Schema 判断可选参数及默认值，且影响其他独立模板。
2. 模型返回未知参数名会终止整个选择；`parameters` 未返回时也会报错，即使模板可完全使用默认值执行。
3. 候选评估器根据模型的缺参声明过滤模板，Runtime 在正式参数桥接前还会做缺参检查，可能先于默认值编译阻断。
4. 通用工具参数编译与 MCP 验证各自处理参数；原 MCP 验证器无法统一做到无效可选值回退/省略、默认值合法性及完整 Schema 约束校验。
5. 默认值虽然可以进入请求，但有效参数、被拒绝覆盖值及来源没有完整地随数据传递给分析环节，存在将默认业务范围归给用户指定范围的风险。此项是源码中确认的审计信息缺口，不能据此声称历史上已发生错误业务报告。
6. Runtime/SQL 路径日志会输出参数值、SQL 或行预览，可能泄露客户信息；已改为字段名、数量、长度和错误代码。联机检查进一步确认知识检索日志及通用工具注册器会记录客户号，补充改为长度、字段名及结果类型。

## 最小修复与调用链

实际链路为：MCP 模板发现 → `AgentOrchestrationEngine` 调用 `SchemaDrivenTemplateParameterBinder.selectAndBind()` 完成一次模板选择和参数提取 → `selectionDecision()/protocols()` 校验证据并预检真实 Schema → `EvidenceBasedTemplateCandidateEvaluator` 保留独立调用 → `InterpretationPlanRuntime` 按调用执行 `TemplateInvocationBridge.prepare()` → `ToolArgumentCompiler.compileTemplate()` → MCP SQL/API 工具 → `TemplateParameterWorkflow` / `TemplateParameterValidator` → 实际执行服务。

`requiredWithoutDefaults()` 兼容声明中的三种默认字段拼写；`bindingParameterSchema()` 继续按发现的真实元数据生成提取契约，未新增业务规则。桥接器仍校验模板身份、资产权限、参数证据；权限检查没有降级。通用 `compile()` 保持原工具路由的严格行为，新增的 `compileTemplate()` 只负责模板参数政策，供 Java 与 MCP 共同复用。

最终参数按有效用户值、合法默认值、可选省略的顺序编译。支持 string/integer/number/boolean/enum/array/object/date，保留已有技术别名和日期格式转换；整数转换拒绝小数截断。默认值本身也必须合法；即使传入合法覆盖值，非法声明默认值仍返回 `INVALID_TEMPLATE_DEFAULT`，避免隐藏配置问题。未声明字段丢弃并保留审计，必填且没有有效值/默认值才阻断当前调用。

保留 `unresolved_parameters`，但不再直接将其作为阻断条件。编译失败记录到每个 invocation 的 `binding_error`；仅全部不可执行时返回整体缺参/配置错误。相同模板不同参数协议不会被错误合并。没有 Schema 的旧协议保留兼容行为，显式空 Schema 则执行 `{}`。

`runtimeParameterBindingAudit` 记录有效参数、来源、默认字段、拒绝覆盖值及未映射用户约束；经 `AnalysisEvidenceCoordinator` 随真实 dataset 进入现有 `CodingAnalysisService` 分析上下文。同模板多调用分别保存来源。默认/被拒绝/未映射条件标记 `REQUIRES_SCOPE_VERIFICATION`，并要求现有 Agent 对照原问题核验，不得将默认客户或时间范围的结果冒充用户要求。Java 不解释客户语义，也不将“参数验证通过”声称为“业务范围验证通过”。此设计保留模型语义判断职责，不能提供脱离模型的业务正确性保证。

没有新增 reviewer/planner 或模型调用。缺参、未知可选字段、默认补全不会触发额外模型提取；仍保留原有结构协议错误修正和执行边界确定性校验。MCP 重新验证真实发布 Schema 是必要边界校验，没有再次进行模型业务判断。Workflow 复用同次编译结果生成来源，不重复编译用于追溯。

## 修复前后

| 条件 | 修复前 | 修复后 |
| --- | --- | --- |
| unresolved 包含可选字段 | 整体可能 PARAMETERS_REQUIRED | 真实 Schema 决定默认/省略 |
| 模型参数为空或未返回 | 可能提前拒绝 | 可默认执行则直接编译 |
| 无效覆盖、有默认值 | 各层处理不一致 | 合法默认回退并记录拒绝来源 |
| 无效可选值、无默认 | 可能校验失败 | 省略并保留审计 |
| 必填无有效值及默认 | 缺参可能波及整组 | 当前 invocation 结构化错误，独立调用继续 |
| 非法默认值 | 缺少统一校验 | 配置错误，非法请求不进入执行器 |
| 默认业务范围与问题不同 | 缺少完整分析来源 | 审计随数据交给现有 Agent 核验、说明适用范围 |

## 自动化验收

最终合并测试：Agents 143、MCP 54、API 33，共 **230 项通过，0 失败、0 错误、0 跳过**。这不是全仓库所有测试。MCP 包括原核心 12 项和补充的 SQL/API/HTTP 执行边界 42 项。

| 编号 | 自动化覆盖 |
| --- | --- |
| T01–T08、T10–T12、T15 | 新增 `TemplateParameterCompilationTest` 对应编号方法；覆盖空 Schema、默认值、覆盖/省略、结构化缺参、日期/数字/布尔/枚举/数组/对象及非法默认 |
| T09 | `TemplateParameterWorkflowTest.T09_aRejectedInvocationDoesNotChangeAnIndependentBinding`、Binder 混合调用及 `InterpretationPlanRuntimeTest.givesEveryReviewedTemplateATerminalBatchEntryWhenOneCannotCompile` |
| T13 | Binder 默认业务范围回归、`AnalysisEvidenceCoordinatorTest` 数据来源隔离及 `CodingAnalysisServiceTest` 原分析指令携带来源回归 |
| T14 | Binder 单次模型调用计数；`AgentOrchestratorTest.codingAnalysisReusesCompletedSelectionDespiteAnUnavailableLegacyReview` |

额外通过：Runtime 真实执行路径的参数别名、日期协议、同模板多资产绑定、无模型批次指令时自动执行已选模板；原安全桥接与通用工具编译测试；嵌套默认审计、可空及空串默认值。

初轮发现旧断言与新“丢弃未知字段”行为不同，以及技术别名在新证据筛选中被误丢弃。已分别更新应变行为断言和修复别名兼容；在基线工作树对照确认别名回归后，最终包含这两项原测试的 188 项全部通过。网络 Schema 库最初版本与现有运行环境不兼容，最终统一为环境已有 `2.0.4` 并通过 MCP/API 集成测试。

额外边界测试首轮 39 项通过、3 项错误，根因为 HTTP 测试只 Mock 旧的 `validateDeclaredOnly()`，新的编译方法返回 Mockito 默认 null；替换为真实验证器后，最终合并 230 项全部通过，未在生产代码增加 null 绕过。最终 `package` 与测试在同一次 reactor 执行中通过，完成于 2026-10-28 07:33（Asia/Shanghai）。本地构建记录：`target/template-defaults-final-package-tests.log`（忽略的构建产物，不提交日志）。

构建：`npm.cmd run build` 成功；`mvn.cmd -o -pl chatchat-api -am package -Pbackend-only -Dfrontend.skip=true -Dfrontend.install.skip=true -DskipTests` 成功。首次打包因旧静态首页引用的生成资源缺失失败，重建前端后全 reactor 打包通过。生成静态资源不纳入本次源码提交。

相关完整测试命令：

```powershell
mvn.cmd -o -pl chatchat-api -am test -Pbackend-only '-Dfrontend.skip=true' '-Dfrontend.install.skip=true' '-Dtest=TemplateParameterCompilationTest,ToolArgumentCompilerTest,SchemaDrivenTemplateParameterBinderTest,TemplateInvocationBridgeTest,EvidenceBasedTemplateCandidateEvaluatorTest,AnalysisEvidenceCoordinatorTest,PublishedDataSupplyWorkflowTest,TemplateParameterValidatorTest,TemplateParameterWorkflowTest,CodingAnalysisServiceTest,ControlledTemplateBindingTest,TemplateSelectionAndBindingTest,AgentOrchestratorTest#codingAnalysisReusesCompletedSelectionDespiteAnUnavailableLegacyReview,InterpretationPlanRuntimeTest#givesEveryReviewedTemplateATerminalBatchEntryWhenOneCannotCompile+upgradesScalarBindingAndExecutesEveryDiscoveredTemplateWithoutModelBatchInstructions+normalizesTemplateExecutionParameterAliasesFromTemplateSchema+compilesControllerParameterProtocolAgainstRuntimeDiscoveredTemplate+oneAuthorizedTemplateBindsToBothSelectedAssetsWithoutBorrowingSiblingScope' '-Dsurefire.failIfNoSpecifiedTests=false'
```

最终将上述命令的 `test` 改为 `package`，并在 `-Dtest` 中加入 `SqlQueryExecutionWorkflowTest,SqlQueryExecuteServiceTest,SqlMcpToolPublisherTest,ApiInvokeServiceTest,HttpRequestToolServiceLivedataTest`，合并执行 230 项。另运行 `git diff --check`，生成前端静态文件恢复后通过。

## 部署与真实业务验收

测试通过 `POST /api/v1/published-agents/financial_indicator/questions` 正式发布助手入口，原助手为“金融指标分析助手”，保持模型 `qwen3.7-flash-2026-07-15` 和发布 workflow 配置。原始问题和执行证据只保存在服务器私有目录，不提交客户明文或凭据。

| 任务 | 真实结果 |
| --- | --- |
| `af70422c-8440-383e-8ab9-5f57e23525cd` | 105 秒后 FAILED；MCP 模板发现成功，模板选择调用 `MODEL_UNAVAILABLE: TimeoutException` |
| `2e6f5a9f-b9e0-3c98-8c4b-c1cd246f65c7` | 97 秒后 FAILED；独立复测同一模型选择超时 |
| `74e3e34d-4057-31ed-b36b-4c7c2ac83a89` | 最终部署后 73 秒 FAILED；相同模板选择超时，日志脱敏核验通过 |

三次请求均未获得模型选择的模板 ID/参数绑定，没有进入客户数据执行，也没有生成客户分析结果。**该真实业务场景尚未通过联机验收**；不能确认本次客户号映射、交易偏好分析或勾稽关系结论。失败是实际模型调用超时，不能将其解释为参数缺失阻断，也不能以自动化 Mock 结果替代。三次请求均确认原发布 workflow 与模型未改变；没有为失败追加生产自动重试或绕过模型授权。需要先恢复该选择调用的可用性，再重复相同正式业务验收；本次未修改模型或超时配置来规避失败。

包含补充日志修复的最终版本已部署到指定环境 `192.168.195.221`，API/MCP 就绪检查通过。采用原镜像的精确生产 class 覆盖，保留无关依赖、前端、原容器环境变量、挂载、网络别名与安全配置；环境已存在同版本 Schema 库，无需替换其他依赖。旧容器、镜像及部署前 compose 均保留用于回滚。

| 最终服务 | `/srv/app/app.jar` SHA-256 |
| --- | --- |
| API | `49c410ee6873f080a060248fde2b1a2c57bc27783ccac0cf6b9f9e2aee50cef2` |
| MCP | `4465eeb44f0cfde8f4fa8852e7b6ddd21407ff0c9d58e3368240cb7ed82893eb` |

九类权限表部署前后去除创建/更新时间后的全部内容摘要与行数一致：`resource_grant` 160、`skill_resource_scope` 21、`sys_menu_permission` 28、`mcp_tool_permission` 93、`sys_permission` 82、`sys_role` 8、`sys_role_org_scope` 3、`sys_role_permission` 115、`sys_user_role` 11。原发布助手/模型配置未修改。

服务器证据目录 `/opt/chatchat-releases/template-defaults-20261028/` 及最终 `/opt/chatchat-releases/template-defaults-20261028-r2/` 均限制为 root 访问；包含候选清单、源差异摘要、部署前镜像/配置、部署与权限核验结果。`live-private`、`live-retry-private` 保存完整真实事件，不提交原始客户数据。最终部署后的原问题请求中，API/MCP 日志扫描均未发现该测试客户号明文；该检查只覆盖本次请求，不能声称完成全系统所有日志路径审计。

## 修改文件与关键位置

以下路径均相对仓库根目录；具体位置以本次提交源码为准。

关键入口行号：Binder `selectionDecision:75`、`requiredWithoutDefaults:219`、`protocols:242`、`bindingParameterSchema:348`；Compiler `compileTemplate:32`；Bridge `prepare:44`、审计组装 `:207`；数据来源挂载 `AnalysisEvidenceCoordinator:204`；Coding 来源指令 `CodingAnalysisService:259`、聚合 `:557`；MCP `TemplateParameterValidator.compileDeclared:39`、`TemplateParameterWorkflow.executePlan:63`。

- `chatchat-agents/pom.xml`：显式依赖与现有环境一致的 Schema 编译库。
- `chatchat-agents/src/main/java/com/chatchat/agents/orchestration/SchemaDrivenTemplateParameterBinder.java`：`selectionDecision()`、`protocols()`、默认值与未映射约束协议。
- `chatchat-agents/src/main/java/com/chatchat/agents/runtime/toolcall/ToolArgumentCompiler.java`：`compileTemplate()`、`compileTemplateObject()`、`templateValue()`、`declaredTemplateParameter()`。
- `chatchat-agents/src/main/java/com/chatchat/agents/runtime/toolcall/TemplateInvocationBridge.java`：`prepare()`、证据过滤、最终参数及 `BINDING_AUDIT`。
- `chatchat-agents/src/main/java/com/chatchat/agents/runtime/plan/selection/EvidenceBasedTemplateCandidateEvaluator.java`：按调用判断阻断，保留模型选择。
- `chatchat-agents/src/main/java/com/chatchat/agents/runtime/plan/InterpretationPlanRuntime.java`：模板预检、调用去重及日志脱敏。
- `chatchat-agents/src/main/java/com/chatchat/agents/orchestration/analysis/dataset/AnalysisEvidenceCoordinator.java`：`templateParameterBinding()`，标量/批次数据来源。
- `chatchat-agents/src/main/java/com/chatchat/agents/orchestration/tool/AgentToolArgumentResolver.java`、`chatchat-agents/src/main/java/com/chatchat/agents/runtime/tool/ToolRuntimeService.java`：修复日志只输出字段/代码。
- `chatchat-agents/src/main/java/com/chatchat/agents/tool/DefaultToolRegistry.java`、`chatchat-knowledge-base/src/main/java/com/chatchat/knowledgebase/search/document/application/DocumentSearchEvidenceService.java`：联机确认后的客户信息日志脱敏。
- `chatchat-api/src/main/java/com/chatchat/api/runtime/coding/analysis/CodingAnalysisService.java`：`parameterBindingContext()`、原有分析目标与数据来源传递。
- `chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/template/TemplateParameterValidator.java`：统一编译、配置与参数错误。
- `chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/template/workflow/TemplateParameterWorkflow.java`：复用编译结果、真实默认来源及 trace。
- `chatchat-mcp-server/src/main/java/com/chatchat/mcpserver/sql/template/SqlTemplateService.java`、`sql/execution/SqlQueryExecuteService.java`、`sql/execution/SqlScriptExecuteService.java`：执行路径日志脱敏。
- 测试：Agents 的 `TemplateParameterCompilationTest`、`SchemaDrivenTemplateParameterBinderTest`、`TemplateSelectionAndBindingTest`、`AnalysisEvidenceCoordinatorTest`、`EvidenceBasedTemplateCandidateEvaluatorTest`；API 的 `CodingAnalysisServiceTest`；MCP 的 `TemplateParameterValidatorTest`、`TemplateParameterWorkflowTest`、`HttpRequestToolServiceLivedataTest`（替换旧验证方法的 Mock，使用真实共享验证器验证 HTTP 重试行为）。
