# 统一分析上下文预算

2026-09-06 的 ETF 分析中，SQL 与检索均成功。第一次工具结果审查发送约 19,967 token，耗时 272 秒；随后超时堆栈落在 `UnifiedQuestionAnalysisGraph.generate_findings`。旧实现以字符数控制上下文：初始证据允许 64,000 字符，补证每项允许约 10,000 字符、每轮最多四项，最终只在 160,000 字符时拒绝。该限制不能反映中英文和 JSON 的 token 成本，也会让中等规模的结构化结果整批进入模型。

修复后的规则：

- 直接原始记录窗口为 12,000 字符。超过后执行全量结构扫描，但只向模型发送扫描摘要、已验证计算和有原始引用的少量记录。
- 投影证据总预算为 30,000 字符；统一分析实际首轮窗口为 24,000 字符。
- 每项按需读取结果限制为 4,000 字符，累计补证窗口限制为 10,000 字符。
- 模型调用前使用中英混合 token 估算器验收，单次统一分析输入最多 12,000 token，并记录 `unifiedAnalysisMaxPromptTokens`。
- 调用前记录 `promptChars`、`estimatedTokens`、证据模式和轮次，使卡住的模型请求可以直接定位。
- 工具结果审查保留候选模板/资产选择所需的完整上下文；对非候选选择步骤，将重复的累计上下文限制为 8,000 字符。完整工具结果仍在审计轨迹中。

以上数字是 Runtime 的传输预算，不是数据读取上限。Runtime 仍扫描全部返回记录、执行已授权计算，并允许模型通过带数据集及记录引用的请求读取更多证据。


## Pre-analysis model input budgets

- With an authoritative workflow DAG, Planner receives a compact plan shape, the exact workflow, bounded tool descriptions, and the user question. Full JSON Schema validation remains in Runtime.
- Template and asset review preserves every candidate identity and semantic selection fields. Executor configuration, transport bindings, invocation examples, and repeated raw schemas remain in Runtime and are applied after selection.
- Other tool-result reviews receive bounded current evidence and cumulative context. Complete results remain available in the Runtime evidence store for the analysis graph.

Planner decides the path, candidate review decides semantic admission, and the unified analysis graph requests bounded evidence for calculations and Findings. No stage needs the complete transport object in one model request.

## Findings and final composition budgets

- Unified analysis permits one initial reasoning call and one supplementary evidence call. A round can batch several bounded evidence operations, so a third unconditional model pass is prohibited.
- Claim-bound final composition receives the admitted Claim ledger plus bounded objective, methodology, conflict, gap, and role context. It never receives the legacy raw execution prompt, tool replay, or record payload.
- Runtime records the final prompt size and input mode as `analysisDriverModelPromptChars` and `analysisFinalSynthesisInputMode`.
- Missing or limited evidence remains publishable as a qualified finding or human-review note. Context budgeting does not turn evidence gaps into a report-wide veto.
