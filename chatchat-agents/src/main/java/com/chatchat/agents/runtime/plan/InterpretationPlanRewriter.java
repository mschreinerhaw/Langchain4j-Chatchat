package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.protocol.ToolProtocolContractResolver;
import com.chatchat.agents.runtime.batch.ToolCallBatchSchema;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Rewrites failed InterpretationPlans into a new validated plan.
 */
@Slf4j
public class InterpretationPlanRewriter {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final InterpretationPlanValidator validator;
    private final EvidenceCompressionGate evidenceCompressionGate;
    private final ToolProtocolContractResolver toolProtocolContracts = new ToolProtocolContractResolver();
    private final InterpretationPlanIncrementalRepair incrementalRepair =
        new InterpretationPlanIncrementalRepair();

    public InterpretationPlanRewriter(ChatModel chatModel,
                                      ObjectMapper objectMapper,
                                      InterpretationPlanValidator validator) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.validator = validator == null ? new InterpretationPlanValidator() : validator;
        this.evidenceCompressionGate = new EvidenceCompressionGate(this.objectMapper);
    }

    /**
     * Rewrites a failed plan. The returned plan is not executed by this class.
     *
     * @param request the rewrite request
     * @return the rewrite result
     */
    public RewriteResult rewrite(RewriteRequest request) {
        if (request == null || request.originalPlan() == null) {
            return RewriteResult.failed("Rewrite request and original plan are required", null, null);
        }
        if (chatModel == null) {
            return RewriteResult.failed("ChatModel is required for plan rewriting", null, null);
        }
        EvidenceCompressionGate.CompressionResult compressedEvidence = evidenceCompressionGate.compress(
            request.observations(), request.evidenceHistory());
        String prompt = buildRewritePrompt(request, compressedEvidence);
        long startedAt = System.currentTimeMillis();
        log.info("agentModelRequest phase=interpretation_plan_rewrite promptChars={} observationCount={} availableToolCount={} "
                + "evidenceCompressionContract={} evidenceCharsBefore={} evidenceCharsAfter={} compressionRatio={}",
            prompt.length(),
            request.observations() == null ? 0 : request.observations().size(),
            request.availableTools() == null ? 0 : request.availableTools().size(),
            EvidenceCompressionGate.CONTRACT_VERSION,
            compressedEvidence.metadata().get("originalChars"),
            compressedEvidence.metadata().get("compressedChars"),
            compressedEvidence.metadata().get("compressionRatio"));
        String raw;
        try {
            raw = chatModel.chat(prompt);
        } catch (RuntimeException ex) {
            log.warn("agentModelFailed phase=interpretation_plan_rewrite promptChars={} errorType={} error={}",
                prompt.length(),
                ex.getClass().getSimpleName(),
                ex.getMessage() == null || ex.getMessage().isBlank() ? "(no message)" : ex.getMessage());
            return RewriteResult.failed(
                "Plan rewrite model failed: "
                    + (ex.getMessage() == null || ex.getMessage().isBlank()
                        ? ex.getClass().getSimpleName()
                        : ex.getMessage()),
                null,
                null
            );
        }
        log.info("agentModelResponse phase=interpretation_plan_rewrite durationMs={} responseChars={}",
            System.currentTimeMillis() - startedAt,
            raw == null ? 0 : raw.length());
        log.info("agentModelRawOutput phase=interpretation_plan_rewrite raw=\n{}",
            ModelProtocolJson.prettyJsonForLog(raw));
        try {
            InterpretationPlan rewrittenPlan = objectMapper.readValue(extractJson(raw), InterpretationPlan.class);
            rewrittenPlan = normalizeRewritePlan(
                request.originalPlan(), rewrittenPlan, request.availableTools(), request.toolRegistry());
            InterpretationPlanIncrementalRepair.RepairRegion repairRegion =
                incrementalRepair.region(request.originalPlan(), request.failedStep());
            rewrittenPlan = incrementalRepair.apply(
                request.originalPlan(), rewrittenPlan, request.failedStep());
            if (repairRegion.bounded()) {
                log.info("InterpretationPlan incremental repair applied affectedStepIds={} frozenStepIds={} mergedStepCount={}",
                    repairRegion.affectedStepIds(), repairRegion.frozenStepIds(), rewrittenPlan.steps().size());
            }
            rewrittenPlan = preserveBudgetCeilings(request.budgetCeilings(), rewrittenPlan);
            InterpretationPlanValidator.ValidationResult validation = validator.validate(
                rewrittenPlan,
                request.toolRegistry(),
                new java.util.LinkedHashSet<>(request.availableTools() == null ? List.of() : request.availableTools())
            );
            validation = validateRequiredToolExecutions(rewrittenPlan, request.requiredToolExecutions(), validation);
            InterpretationPlan continuationPlan = repairContinuationPlan(request.originalPlan(), rewrittenPlan);
            if (!validation.valid() || continuationPlan != rewrittenPlan) {
                InterpretationPlan repairedPlan = continuationPlan;
                repairedPlan = repairExecutionPolicyStepLimit(
                    repairedPlan,
                    request.budgetCeilings() == null
                        ? null
                        : request.budgetCeilings().maxSteps()
                );
                InterpretationPlanOptimizer.OptimizationResult optimizedRepair =
                    new InterpretationPlanOptimizer().optimize(repairedPlan);
                repairedPlan = optimizedRepair.plan() == null ? repairedPlan : optimizedRepair.plan();
                repairedPlan = repairExecutionPolicyStepLimit(
                    repairedPlan,
                    request.budgetCeilings() == null ? null : request.budgetCeilings().maxSteps()
                );
                if (repairedPlan != rewrittenPlan) {
                    InterpretationPlanValidator.ValidationResult repairedValidation = validator.validate(
                        repairedPlan,
                        request.toolRegistry(),
                        new java.util.LinkedHashSet<>(request.availableTools() == null ? List.of() : request.availableTools())
                    );
                    repairedValidation = validateRequiredToolExecutions(repairedPlan, request.requiredToolExecutions(), repairedValidation);
                    if (repairedValidation.valid()) {
                        log.info("InterpretationPlan rewrite repaired as continuation DAG. originalErrors={}, repairedStepCount={}",
                            validationSummary(validation),
                            repairedPlan.steps().size());
                        return new RewriteResult(true, repairedValidation.executable(), repairedPlan, repairedValidation, raw, null);
                    }
                    InterpretationPlan evidenceOnlyPlan = repairAsEvidenceOnlyFinalPlan(request, rewrittenPlan);
                    if (evidenceOnlyPlan != null) {
                        InterpretationPlanValidator.ValidationResult evidenceOnlyValidation = validator.validate(
                            evidenceOnlyPlan,
                            request.toolRegistry(),
                            new java.util.LinkedHashSet<>(request.availableTools() == null ? List.of() : request.availableTools())
                        );
                        if (evidenceOnlyValidation.valid()) {
                            log.info("InterpretationPlan rewrite repaired as evidence-only final plan after successful prior tool evidence. originalErrors={}",
                                validationSummary(repairedValidation));
                            return new RewriteResult(true, evidenceOnlyValidation.executable(), evidenceOnlyPlan,
                                evidenceOnlyValidation, raw, null);
                        }
                    }
                    return new RewriteResult(false, false, repairedPlan, repairedValidation, raw,
                        "Rewritten plan failed validation after continuation repair: " + validationSummary(repairedValidation));
                }
                return new RewriteResult(false, false, rewrittenPlan, validation, raw,
                    "Rewritten plan failed validation: " + validationSummary(validation));
            }
            return new RewriteResult(true, validation.executable(), rewrittenPlan, validation, raw, null);
        } catch (Exception ex) {
            log.debug("Failed to parse rewritten InterpretationPlan: {}", raw, ex);
            return RewriteResult.failed("Failed to parse rewritten InterpretationPlan: " + ex.getMessage(), raw, null);
        }
    }

    private String buildRewritePrompt(RewriteRequest request,
                                      EvidenceCompressionGate.CompressionResult compressedEvidence) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an MCP plan rewriter.\n");
        prompt.append("Your job is to repair a failed InterpretationPlan without executing tools.\n");
        prompt.append("Output exactly one valid InterpretationPlan JSON object. No markdown, comments, code fences, or natural language.\n");
        prompt.append("For incremental repair, include only mutable-region and new steps; Runtime restores frozen nodes before validation.\n");
        prompt.append("Rewrite rules:\n");
        prompt.append("- Preserve the user's original goal and already successful evidence.\n");
        InterpretationPlanIncrementalRepair.RepairRegion repairRegion =
            incrementalRepair.region(request.originalPlan(), request.failedStep());
        if (repairRegion.bounded()) {
            prompt.append("- This is an incremental region repair. Only affected_step_ids may change; frozen_step_ids are immutable and Runtime restores them verbatim.\n");
            prompt.append("repairRegion: ").append(toJson(Map.of(
                "affected_step_ids", repairRegion.affectedStepIds(),
                "frozen_step_ids", repairRegion.frozenStepIds()
            ))).append("\n");
        }
        prompt.append("- Evidence gaps are rewrite targets only when they block the current user request. Tool capability exclusions, unsupportedClaims, and notAssessedClaims are guardrails, not additional requirements; never add steps solely to investigate an out-of-scope excluded claim.\n");
        prompt.append("- When an exact metadata lookup succeeds with an empty collection, do not preserve an indexed output binding such as tables[0] or results[0]. Remove that binding and either add one bounded semantic/variant lookup with materially revised inputs or proceed to a partial final answer when the retrieval budget is exhausted.\n");
        prompt.append("- A recovery lookup may derive separator/case variants and semantic tokens from the current user query or observed identifiers, but those variants are search hypotheses only and must never be reported as existing objects.\n");
        prompt.append("- Treat completed steps and evidence_execution_lock_v1 observations as immutable execution state; do not re-add them as runnable tool steps.\n");
        prompt.append("- Do not repeat a failed MCP tool step unless the failure reason is transient and no safer alternative exists.\n");
        prompt.append("- Evidence refinement is tool-agnostic: when evidenceHistory marks a round insufficient, use nextActions to revise inputs, repeat a suitable tool, or select another available tool that can close the recorded evidence gap.\n");
        prompt.append("- Treat analysisCoverage and gapRequests as the business-question coverage contract. A CORE question marked PARTIAL or UNSUPPORTED remains a retrieval target while a safe path and bounded budget remain. Do not stop merely because another sub-question already has usable evidence.\n");
        prompt.append("- Translate declarative gap requests through normal capability discovery and semantic admission. Never copy a suggested query as executable SQL, never bypass template review, parameter binding, evidence review, or authorization.\n");
        prompt.append("- When a requested change/trend/flow lacks comparison periods, seek producer-declared historical or flow capabilities and request the missing time horizon and grain. Preserve the distinction between stock/level observations and change/flow evidence.\n");
        prompt.append("- Never repeat an identical tool call unless the recorded failure is transient and a retry is explicitly justified. A repeated call must materially change its inputs or execution conditions.\n");
        prompt.append("- Every revised action must be traceable to evidence IDs, missingEvidence, or conflicts from evidenceHistory. Do not discard useful evidence from earlier rounds.\n");
        prompt.append("- Preserve a decision ledger for changed retrieval or template choices: carry the prior candidate id, its evidence-backed rejection/abandonment reason, the replacement candidate or refined query, and the evidence gap that replacement is intended to close. Put these facts in dependency reasons, next-step inputs such as excludeTemplateIds/refined intent, and the final_answer dependency chain so Runtime can audit and summarize them.\n");
        prompt.append("- Distinguish availability from usability. A tool is unavailable only when it is absent from Available tools or an executed availability/transport result says so. Binding failures, missing parameter evidence, contract denial, empty results, and semantic rejection mean the tool or candidate was not usable for that attempt; never rewrite those states as service unavailable.\n");
        prompt.append("- When hypotheses are present, revise the plan to test unresolved or conflicting hypotheses. Preserve hypothesis IDs through the evidence chain; never treat the hypothesis statement itself as proof.\n");
        prompt.append("- Use Evidence Object quality dimensions when choosing among competing evidence paths. Read each dimension's value/status/type/reason; UNKNOWN is not a neutral score and must not be treated as 0.5. Never mix MODEL_ESTIMATED modelConfidence with computed evidence quality.\n");
        prompt.append("- Use evidence_graph_v1 ACTIVE SUPPORTS/CONTRADICTS relations to identify which hypothesis needs validation. Ignore rejectedRelations as factual support and never create a plan from a nonexistent evidence reference.\n");
        prompt.append("- Remove impossible dependencies and keep the result as a DAG.\n");
        prompt.append("- Use only available tools. If no safe tool remains, produce a final_answer step explaining what is missing.\n");
        prompt.append("- Include exactly one final_answer step and keep every depends_on id valid.\n");
        prompt.append("- Preserve or tighten execution_policy.max_rewrite_times and fallback_mode.\n");
        prompt.append("- Preserve execution_policy tool priority/cost/latency/accuracy constraints unless the failure proves they are impossible.\n");
        prompt.append("- Preserve plan.stability stable_nodes, critical_tools, and locked_edges; do not alter locked edges.\n");
        prompt.append("- Preserve plan.dependency_contracts, plan.branch_groups, and plan.conditional_edges. Mutually exclusive paths must use branch groups, not optional dependencies.\n");
        prompt.append("- Preserve plan.diagnostic_profile check IDs, capabilities, dimensions, and required flags. Update step_ids when steps change; keep an uncovered required check with step_ids=[] instead of deleting it.\n");
        prompt.append("- Use diagnosticRun missing/failed checks as evidence refinement targets. Do not invent a score for an uncovered check, and do not replace a missing check with a different capability merely to increase coverage.\n");
        prompt.append("- Add or update plan.edge_contracts when the failure was caused by missing or mistyped tool output fields.\n");
        prompt.append("- STEP_OUTPUT_CONTRACT_FAILED is a runtime gate, not usable completion evidence. Repair the producing step, its binding, or its declared contract so the required type and fields are actually present; never bypass the gate or execute its downstream step with missing output.\n");
        prompt.append("- Preserve the exact output-contract violations from the previous execution error while repairing. Do not replace a missing structured field with explanatory prose.\n");
        prompt.append("- Keep execution_policy.deny_tool for tools that failed due to policy, permission, or safety.\n\n");
        prompt.append("Sequential MCP batch repair contract:\n");
        prompt.append("- When multiple remaining authorized template executions use an executor that publishes template_execution and batch_execution capabilities, combine them into one mcp_tool input {batchId,executionMode:\"SEQUENTIAL\",stopOnFailure:false,calls:[{callId,toolName,arguments}]} instead of creating one model round per call.\n");
        prompt.append("- Template execution is failure-isolated by Runtime. Preserve every remaining child in the batch and never stop or omit later templates because an earlier template failed or returned no rows.\n");
        prompt.append("- Preserve original diagnostic order and exact discovered template identifiers/arguments. Runtime validates, executes, audits, and persists each child independently; do not inline raw transport payloads, credentials, or fields forbidden by the tool-published protocol.\n\n");
        prompt.append("- Never repair a diagnostic batch by adding a reasoning/aggregation step that copies discovered template ids into invented output fields. Map diagnostic checks to the executor step and let Runtime deterministically resolve only asset-scoped authorized template metadata.\n\n");
        if (request.budgetCeilings() != null) {
            prompt.append("Agent-configured budget ceilings (authoritative): ")
                .append(toJson(request.budgetCeilings()))
                .append("\n- The rewritten execution policy may use smaller values but MUST NOT exceed these ceilings.\n\n");
        }
        List<RequiredToolExecution> requiredExecutions = request.requiredToolExecutions() == null
            ? List.of()
            : request.requiredToolExecutions().stream()
                .filter(execution -> execution != null
                    && execution.required()
                    && execution.toolName() != null
                    && !execution.toolName().isBlank())
                .toList();
        if (!requiredExecutions.isEmpty()) {
            prompt.append("Required tool execution contract:\n");
            prompt.append("- Runtime marked these pending tool executions as required. The model may order and parameterize them, but must not skip or replace them with reasoning/final_answer.\n");
            prompt.append("- final_answer is allowed only after each required tool appears as an MCP tool step and final_answer depends on those step results.\n");
            prompt.append("- If a required tool cannot run, keep the tool step so runtime can produce the success, error, or permission observation.\n");
            prompt.append("- execution_policy.allow_tool must include every required tool unless allow_tool is intentionally omitted by schema.\n");
            prompt.append("requiredToolExecutions: ").append(toJson(requiredExecutions)).append("\n\n");
        }
        prompt.append("Asset discovery repair rules:\n");
        prompt.append("- Model intent recognition belongs in the plan. When repairing an asset discovery step for a high-level user request, preserve or add scored intentCandidates sorted by score/confidence. Include every candidate with score >= 0.75 in queryTerms/retrievalSignals; if none reaches 0.75, use the top two candidates. Add the original user question and useful multi-query expansions under queries/queryTerms/expandedQueries/keywords, alongside semantic filters published by that tool.\n");
        prompt.append("- Preserve any user-explicit value for a tool-published logical filter dimension in its canonical filter field as well as in retrieval terms. Do not turn descriptive intent into an invented exact routing identity or physical target.\n");
        prompt.append("- Repair asset and template retrieval with bounded abbreviation aliases when useful. Add at most 4 lowercase aliases to queryTerms/keywords while retaining their original phrases: use Chinese pinyin initials (\u6570\u636e\u670d\u52a1\u4e2d\u5fc3 -> sjfwzx) and English word initials across multi-word/camelCase/snake_case/kebab-case names (Example Metric Service -> ems). Aliases must be 2-16 characters and come only from short candidate names or capability phrases, never a full sentence, description, or executable payload. Treat them as weak retrieval signals and never write them into exact routing or template identity fields. Preserve a user-supplied abbreviation and expand it only when the full phrase is supported by request or observation evidence.\n");
        prompt.append("- enterprise_metadata_search uses canonical top-level queryTerms (or query). Do not emit keywords alone for this tool; keywords is accepted only as a legacy alias and Runtime normalizes it to queryTerms.\n");
        prompt.append("- Preserve runtime-required tool parameters declared in the execution context. Do not invent hidden source identifiers or dataset codes.\n");
        prompt.append("- Do not convert a natural-language target phrase into an exact routing filter unless that registered value appears in the current-turn user request or a prior observation. Ignore historical conversation targets and model-generated plan text as identity evidence.\n");
        prompt.append("- Treat DEV/TEST/UAT/PROD as hard environment filters only when the user explicitly states an environment constraint (for example, TEST environment or \u6d4b\u8bd5\u73af\u5883), or when a prior asset observation returns that environment. Do not infer env from a word inside an asset proper name such as 248\u6d4b\u8bd5\u6570\u636e\u5e93; preserve the complete proper name as a retrieval signal.\n");
        prompt.append("- If exact assetName/env/service labels are uncertain but the request contains target clues, omit filters.assetName and use semantic retrieval filters; use filters={} only when no target clue or task clue exists.\n\n");
        prompt.append("- The canonical asset result view is assets[].asset: use assets[0].asset.name for executionContext.assetName and assets[0].asset.environment for executionContext.env. Do not guess abbreviated paths such as assets[0].assetName or assets[0].name.\n\n");
        prompt.append("Strict template argument contract:\n");
        prompt.append("- Model output is untrusted. template/templateId must be one scalar string from templates[i].templateId; parameters must be an object of execution values; executionContext must be an object.\n");
        prompt.append("- Use input.toolCall={toolName,action,parameters,context}. Runtime, not the model, compiles this semantic DSL into concrete MCP executor parameters and may query allowed MCP metadata/resolver tools within its bounded repair policy.\n");
        prompt.append("- For a parameterized template discovered by a prior step, preserve that discovery dependency. The DAG controller will emit ")
            .append(InterpretationExecutionProtocol.TEMPLATE_PARAMETER_PROTOCOL_VERSION)
            .append(" as an evidence-based parameter profile after seeing the returned parameterSchema and completed evidence; do not guess undeclared parameter names in the rewritten executor input.\n");
        prompt.append("- Parameter evidence must cite an exact user-query quote or a successful completed step_id/output_path. Runtime re-reads the cited value, audits it, applies defaults/type conversion, and alone invokes MCP.\n");
        prompt.append("- For missing direct non-template tool arguments, use contextParameterEvidence with parameter/source and either stepId+outputPath or quote+value; preserve or add the dependency on the cited completed step. Runtime verifies every proposal and never trusts a model-supplied value by itself.\n");
        prompt.append("- parameterSchema, requiredParameters, parameterContract, invocationExample, selectedTemplate, and an entire templates[i] object are read-only discovery metadata. Never pass any of them as templateId or parameters.\n");
        prompt.append("- A binding targeting template/templateId must use an output_path ending in the scalar identifier field templateId (or the discovery contract's explicit scalar id field).\n\n");
        prompt.append("- When template discovery candidates are rejected, preserve the evidence-backed rejection reason, exclude their ids from bounded retries, and materially refine the request. Never repeat an identical discovery query.\n");
        prompt.append("- When changing from a precise-looking candidate to a broader or different query, explicitly justify the change from observed parameter compatibility, execution failure, missing evidence, or semantic mismatch. Retrieval rank or a template name alone is insufficient.\n");
        prompt.append("- Treat template_execution_satisfaction.v1 as an authoritative retry contract. Apply only evidence-proven retryInputChanges and bind missing required parameters; when reselection is required, never retry the same template id unchanged.\n");
        prompt.append("- Omitted parameters with declared defaults need no model evidence. Add or bind only required parameters that have no usable default; never invent an override.\n\n");
        prompt.append(toolProtocolContracts.rewriterSection(request.availableTools(), request.toolRegistry()));
        prompt.append("InterpretationPlan JSON Schema:\n").append(InterpretationPlanJsonSchema.SCHEMA).append("\n\n");
        prompt.append("Available tools:\n").append(request.availableTools() == null ? List.of() : request.availableTools()).append("\n");
        prompt.append("Failed step:\n").append(toJson(failedStep(request))).append("\n");
        prompt.append("Failure reason:\n").append(request.failureReason()).append("\n");
        prompt.append("Evidence Compression Gate metadata:\n")
            .append(toJson(compressedEvidence.metadata()))
            .append("\n");
        prompt.append("Observations so far (compressed scheduling evidence; full evidence remains Runtime-owned):\n")
            .append(compressedEvidence.observations())
            .append("\n");
        prompt.append("Evidence iteration history (authoritative basis for plan revision):\n")
            .append(compressedEvidence.evidenceHistory())
            .append("\n");
        prompt.append(repairRegion.bounded() ? "Original plan incremental repair projection:\n" : "Original plan:\n")
            .append(toJson(rewritePlanProjection(request.originalPlan(), repairRegion)));
        return prompt.toString();
    }

    private Object rewritePlanProjection(InterpretationPlan original,
                                         InterpretationPlanIncrementalRepair.RepairRegion region) {
        if (original == null || region == null || !region.bounded() || original.plan() == null) {
            return original;
        }
        List<InterpretationPlan.Step> mutableSteps = original.steps().stream()
            .filter(Objects::nonNull)
            .filter(step -> region.affectedStepIds().contains(step.id()))
            .toList();
        List<Map<String, Object>> frozenBoundary = original.steps().stream()
            .filter(Objects::nonNull)
            .filter(step -> region.frozenStepIds().contains(step.id()))
            .map(step -> Map.<String, Object>of(
                "id", step.id(),
                "action_type", step.actionType() == null ? "" : step.actionType(),
                "tool_name", step.toolName() == null ? "" : step.toolName(),
                "depends_on", step.dependsOn() == null ? List.of() : step.dependsOn()
            ))
            .toList();
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("version", original.version());
        projection.put("intent", original.intent());
        projection.put("context", original.context());
        projection.put("mutable_steps", mutableSteps);
        projection.put("frozen_boundary", frozenBoundary);
        projection.put("edge_contracts", original.plan().edgeContracts() == null ? List.of()
            : original.plan().edgeContracts().stream()
                .filter(Objects::nonNull)
                .filter(edge -> region.affectedStepIds().contains(edge.from())
                    || region.affectedStepIds().contains(edge.to()))
                .toList());
        projection.put("dependency_contracts", original.plan().dependencyContracts() == null ? List.of()
            : original.plan().dependencyContracts().stream()
                .filter(Objects::nonNull)
                .filter(contract -> region.affectedStepIds().contains(contract.from())
                    || region.affectedStepIds().contains(contract.to()))
                .toList());
        projection.put("branch_groups", original.plan().branchGroups() == null ? List.of()
            : original.plan().branchGroups());
        projection.put("conditional_edges", original.plan().conditionalEdges() == null ? List.of()
            : original.plan().conditionalEdges());
        projection.put("bindings", original.plan().bindings() == null ? List.of()
            : original.plan().bindings().stream()
                .filter(Objects::nonNull)
                .filter(binding -> region.affectedStepIds().contains(binding.from())
                    || region.affectedStepIds().contains(binding.to()))
                .toList());
        projection.put("affected_step_ids", region.affectedStepIds());
        projection.put("frozen_step_ids", region.frozenStepIds());
        projection.put("execution_policy", original.executionPolicy());
        projection.put("review", original.review());
        return projection;
    }

    private boolean hasAvailableSemanticTool(List<String> availableTools, String semanticToolName) {
        if (availableTools == null || availableTools.isEmpty() || semanticToolName == null || semanticToolName.isBlank()) {
            return false;
        }
        for (String toolName : availableTools) {
            String semantic = toolSemanticKey(toolName);
            if (semanticToolName.equals(semantic)
                || ("template_discovery".equals(semanticToolName)
                    && ("template_query".equals(semantic)
                        || semantic.endsWith("_template_query")
                        || semantic.endsWith("_template_search")))) {
                return true;
            }
        }
        return false;
    }

    private InterpretationPlanValidator.ValidationResult validateRequiredToolExecutions(
        InterpretationPlan plan,
        List<RequiredToolExecution> requiredExecutions,
        InterpretationPlanValidator.ValidationResult validation
    ) {
        if (validation == null) {
            return validation;
        }
        List<RequiredToolExecution> pendingRequiredExecutions = requiredExecutions == null
            ? List.of()
            : requiredExecutions.stream()
                .filter(execution -> execution != null
                    && execution.required()
                    && execution.toolName() != null
                    && !execution.toolName().isBlank())
                .toList();
        if (plan == null || plan.steps() == null || plan.steps().isEmpty() || pendingRequiredExecutions.isEmpty()) {
            return validation;
        }
        Map<Integer, InterpretationPlan.Step> stepsById = new LinkedHashMap<>();
        InterpretationPlan.Step finalStep = null;
        for (InterpretationPlan.Step step : plan.steps()) {
            if (step != null && step.id() != null) {
                stepsById.put(step.id(), step);
                if (step.finalAnswerAction()) {
                    finalStep = step;
                }
            }
        }
        List<InterpretationPlanValidator.ValidationIssue> extraErrors = new ArrayList<>();
        for (RequiredToolExecution execution : pendingRequiredExecutions) {
            InterpretationPlan.Step requiredStep = firstStep(plan.steps(), execution.toolName());
            if (requiredStep == null) {
                extraErrors.add(new InterpretationPlanValidator.ValidationIssue(
                    "error",
                    "plan.steps",
                    "Required tool must be present before final_answer: " + execution.toolName()
                ));
                continue;
            }
            if (finalStep == null || !dependsOn(finalStep.id(), requiredStep.id(), stepsById, new LinkedHashSet<>())) {
                extraErrors.add(new InterpretationPlanValidator.ValidationIssue(
                    "error",
                    "plan.steps[" + (finalStep == null ? "final_answer" : finalStep.id()) + "].depends_on",
                    "final_answer must depend on required tool result: " + execution.toolName()
                ));
            }
            if (plan.executionPolicy() != null
                && plan.executionPolicy().allowTool() != null
                && !plan.executionPolicy().allowTool().isEmpty()
                && plan.executionPolicy().allowTool().stream().noneMatch(tool -> sameTool(tool, execution.toolName()))) {
                extraErrors.add(new InterpretationPlanValidator.ValidationIssue(
                    "error",
                    "execution_policy.allow_tool",
                    "execution_policy.allow_tool must include required tool: " + execution.toolName()
                ));
            }
        }
        if (extraErrors.isEmpty()) {
            return validation;
        }
        List<InterpretationPlanValidator.ValidationIssue> errors = new ArrayList<>();
        errors.addAll(validation.errors() == null ? List.of() : validation.errors());
        errors.addAll(extraErrors);
        return new InterpretationPlanValidator.ValidationResult(
            false,
            false,
            validation.approvalRequired(),
            List.copyOf(errors),
            validation.warnings() == null ? List.of() : validation.warnings(),
            validation.approvalRequests() == null ? List.of() : validation.approvalRequests(),
            validation.orderedSteps() == null ? List.of() : validation.orderedSteps()
        );
    }

    private InterpretationPlan.Step firstStep(List<InterpretationPlan.Step> steps, String toolName) {
        if (steps == null || toolName == null || toolName.isBlank()) {
            return null;
        }
        return steps.stream()
            .filter(step -> step != null && sameTool(step.toolName(), toolName))
            .findFirst()
            .orElse(null);
    }

    private boolean dependsOn(Integer fromStepId,
                              Integer requiredDependencyId,
                              Map<Integer, InterpretationPlan.Step> stepsById,
                              Set<Integer> visited) {
        if (fromStepId == null || requiredDependencyId == null) {
            return false;
        }
        if (fromStepId.equals(requiredDependencyId)) {
            return true;
        }
        if (visited == null) {
            visited = new LinkedHashSet<>();
        }
        if (!visited.add(fromStepId)) {
            return false;
        }
        InterpretationPlan.Step from = stepsById.get(fromStepId);
        if (from == null || from.dependsOn() == null || from.dependsOn().isEmpty()) {
            return false;
        }
        if (from.dependsOn().contains(requiredDependencyId)) {
            return true;
        }
        for (Integer dependency : from.dependsOn()) {
            if (dependsOn(dependency, requiredDependencyId, stepsById, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean sameTool(String left, String right) {
        String leftKey = toolSemanticKey(left);
        String rightKey = toolSemanticKey(right);
        return !leftKey.isBlank() && leftKey.equals(rightKey);
    }

    private String toolSemanticKey(String toolName) {
        if (toolName == null) {
            return "";
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        while (normalized.startsWith("mcp_")) {
            normalized = normalized.substring(4);
        }
        String[] prefixes = {
            "chatchat_mcp_server_",
            "chatchat_",
            "xxx_"
        };
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : prefixes) {
                if (normalized.startsWith(prefix)) {
                    normalized = normalized.substring(prefix.length());
                    changed = true;
                }
            }
        }
        return normalized;
    }

    private Map<String, Object> failedStep(RewriteRequest request) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (request.failedStep() == null) {
            return values;
        }
        values.put("id", request.failedStep().id());
        values.put("action_type", request.failedStep().actionType());
        values.put("tool_name", request.failedStep().toolName());
        values.put("input", request.failedStep().input());
        values.put("depends_on", request.failedStep().dependsOn());
        return values;
    }

    private String toJson(Object value) {
        return ModelProtocolJson.compact(value);
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.trim();
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1);
        }
        return text;
    }

    /**
     * Model rewrites occasionally preserve the plan's meaning while omitting
     * protocol-required structural fields. Normalize those omissions before
     * semantic validation so a recoverable rewrite is not discarded.
     */
    private InterpretationPlan normalizeRewritePlan(InterpretationPlan originalPlan,
                                                    InterpretationPlan rewrittenPlan,
                                                    List<String> availableTools,
                                                    ToolRegistry toolRegistry) {
        if (rewrittenPlan == null) {
            return null;
        }
        InterpretationPlan.Plan body = rewrittenPlan.plan();
        List<InterpretationPlan.Step> sourceSteps = body == null || body.steps() == null
            ? List.of()
            : body.steps();
        List<InterpretationPlan.Step> normalizedSteps = new ArrayList<>();
        List<Integer> precedingStepIds = new ArrayList<>();
        for (InterpretationPlan.Step step : sourceSteps) {
            if (step == null) {
                continue;
            }
            String actionType = step.actionType();
            if (actionType == null || actionType.isBlank()) {
                actionType = step.toolName() != null && !step.toolName().isBlank()
                    ? "mcp_tool"
                    : "final_answer";
            }
            List<Integer> dependsOn = step.dependsOn();
            if (dependsOn == null) {
                dependsOn = "final_answer".equals(actionType)
                    ? List.copyOf(precedingStepIds)
                    : List.of();
            }
            Map<String, Object> normalizedInput = normalizeBatchChildToolNames(
                step,
                step.input() == null ? Map.of() : step.input(),
                availableTools,
                toolRegistry
            );
            normalizedSteps.add(new InterpretationPlan.Step(
                step.id(),
                actionType,
                step.toolName() == null ? "" : step.toolName(),
                normalizedInput,
                dependsOn,
                step.outputContract(),
                step.validation()
            ));
            if (step.id() != null) {
                precedingStepIds.add(step.id());
            }
        }

        InterpretationPlan.Review review = rewrittenPlan.review();
        InterpretationPlan.SelfCheck selfCheck = review == null ? null : review.selfCheck();
        if (selfCheck == null && originalPlan != null && originalPlan.review() != null) {
            selfCheck = originalPlan.review().selfCheck();
        }
        if (selfCheck == null) {
            selfCheck = new InterpretationPlan.SelfCheck(
                0.5,
                0.5,
                false,
                List.of("Runtime restored the required rewrite self-check structure.")
            );
        }
        InterpretationPlan.Review normalizedReview = new InterpretationPlan.Review(
            selfCheck,
            review == null || review.fallbackPlan() == null ? List.of() : review.fallbackPlan()
        );
        InterpretationPlan.Plan normalizedBody = new InterpretationPlan.Plan(
            normalizedSteps,
            repairedLockedBindingEdges(body),
            body == null || body.dependencyContracts() == null ? List.of() : body.dependencyContracts(),
            body == null || body.bindings() == null ? List.of() : body.bindings(),
            body == null ? null : body.stability(),
            normalizeDiagnosticProfile(
                body == null
                    ? originalPlan == null || originalPlan.plan() == null ? null : originalPlan.plan().diagnosticProfile()
                    : body.diagnosticProfile(),
                normalizedSteps
            ),
            body == null || body.conditionalEdges() == null ? List.of() : body.conditionalEdges(),
            body == null || body.branchGroups() == null ? List.of() : body.branchGroups()
        );
        InterpretationPlan.ExecutionPolicy executionPolicy = rewrittenPlan.executionPolicy() == null
            && originalPlan != null
            ? originalPlan.executionPolicy()
            : rewrittenPlan.executionPolicy();
        return new InterpretationPlan(
            rewrittenPlan.version(),
            rewrittenPlan.intent(),
            rewrittenPlan.context(),
            normalizedBody,
            executionPolicy,
            normalizedReview
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeBatchChildToolNames(InterpretationPlan.Step step,
                                                             Map<String, Object> input,
                                                             List<String> availableTools,
                                                             ToolRegistry toolRegistry) {
        ToolMetadata metadata = step == null || toolRegistry == null
            ? null : toolRegistry.getToolMetadata(step.toolName());
        if (step == null || input == null || !ToolCallBatchSchema.supports(step.toolName(), metadata)) {
            return input == null ? Map.of() : input;
        }
        Object rawCalls = input.get("calls");
        if (!(rawCalls instanceof List<?> calls) || calls.isEmpty()) {
            return input;
        }
        Map<String, Object> normalizedInput = new LinkedHashMap<>(input);
        List<Object> normalizedCalls = new ArrayList<>(calls.size());
        for (Object rawCall : calls) {
            if (!(rawCall instanceof Map<?, ?> map)) {
                normalizedCalls.add(rawCall);
                continue;
            }
            Map<String, Object> call = new LinkedHashMap<>((Map<String, Object>) map);
            Object declared = call.containsKey("toolName")
                ? call.get("toolName") : call.get("tool_name");
            String declaredTool = declared == null ? step.toolName() : String.valueOf(declared);
            String resolved = resolveAvailableToolName(declaredTool, availableTools);
            if (resolved == null && sameTool(declaredTool, step.toolName())) {
                resolved = step.toolName();
            }
            if (resolved != null) {
                call.put("toolName", resolved);
                call.remove("tool_name");
            }
            normalizedCalls.add(call);
        }
        normalizedInput.put("calls", List.copyOf(normalizedCalls));
        return normalizedInput;
    }

    private String resolveAvailableToolName(String declaredTool, List<String> availableTools) {
        if (declaredTool == null || declaredTool.isBlank()
            || availableTools == null || availableTools.isEmpty()) {
            return null;
        }
        return availableTools.stream()
            .filter(tool -> sameTool(tool, declaredTool))
            .findFirst()
            .orElse(null);
    }

    /**
     * A model may correctly bind separate discovered templates into batch children
     * while declaring only one coarse edge such as {@code templates}. Locked-edge
     * validation requires each required binding to have its exact source path. The
     * binding is already subject to normal tool/path validation, so materializing
     * its matching edge contract restores protocol structure without inventing a
     * template id or an execution value.
     */
    private List<InterpretationPlan.EdgeContract> repairedLockedBindingEdges(
        InterpretationPlan.Plan body
    ) {
        List<InterpretationPlan.EdgeContract> existing = body == null || body.edgeContracts() == null
            ? List.of()
            : body.edgeContracts();
        if (body == null || body.stability() == null
            || !Boolean.TRUE.equals(body.stability().lockedEdges())
            || body.bindings() == null || body.bindings().isEmpty()) {
            return existing;
        }
        List<InterpretationPlan.EdgeContract> repaired = new ArrayList<>(existing);
        for (InterpretationPlan.Binding binding : body.bindings()) {
            if (binding == null || Boolean.FALSE.equals(binding.required())
                || binding.from() == null || binding.to() == null
                || binding.outputPath() == null || binding.outputPath().isBlank()) {
                continue;
            }
            String canonicalPath = canonicalPath(binding.outputPath());
            boolean present = repaired.stream()
                .filter(Objects::nonNull)
                .anyMatch(edge -> Objects.equals(edge.from(), binding.from())
                    && Objects.equals(edge.to(), binding.to())
                    && canonicalPath(edge.field()).equals(canonicalPath));
            if (!present) {
                repaired.add(new InterpretationPlan.EdgeContract(
                    binding.from(), binding.to(), binding.outputPath(),
                    templateIdentifierPath(binding.outputPath()) ? "string" : "any", true
                ));
            }
        }
        return List.copyOf(repaired);
    }

    private String canonicalPath(String value) {
        return value == null ? "" : value.replace("_", "").replace("-", "")
            .replace("$", "").replace("[", "").replace("]", "")
            .replace(".", "").trim().toLowerCase(Locale.ROOT);
    }

    private boolean templateIdentifierPath(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith(".templateid") || normalized.endsWith(".template_id")
            || normalized.endsWith(".id") || normalized.endsWith(".code");
    }

    private InterpretationPlan repairContinuationPlan(InterpretationPlan originalPlan, InterpretationPlan rewrittenPlan) {
        if (originalPlan == null || rewrittenPlan == null
            || originalPlan.steps().isEmpty() || rewrittenPlan.steps().isEmpty()) {
            return rewrittenPlan;
        }
        Map<Integer, InterpretationPlan.Step> originalStepsById = new LinkedHashMap<>();
        for (InterpretationPlan.Step step : originalPlan.steps()) {
            if (step != null && step.id() != null) {
                originalStepsById.put(step.id(), step);
            }
        }
        if (originalStepsById.isEmpty()) {
            return rewrittenPlan;
        }

        Set<Integer> rewrittenStepIds = stepIds(rewrittenPlan.steps());
        Set<Integer> missingStepIds = referencedStepIds(rewrittenPlan);
        missingStepIds.removeAll(rewrittenStepIds);
        missingStepIds.retainAll(originalStepsById.keySet());
        if (missingStepIds.isEmpty()) {
            return rewrittenPlan;
        }

        collectTransitiveOriginalDependencies(missingStepIds, rewrittenStepIds, originalStepsById);
        List<InterpretationPlan.Step> mergedSteps = new ArrayList<>();
        for (InterpretationPlan.Step step : originalPlan.steps()) {
            if (step != null && step.id() != null && missingStepIds.contains(step.id())) {
                mergedSteps.add(step);
            }
        }
        mergedSteps.addAll(rewrittenPlan.steps());
        if (mergedSteps.size() == rewrittenPlan.steps().size()) {
            return rewrittenPlan;
        }

        InterpretationPlan.Plan rewrittenBody = rewrittenPlan.plan();
        InterpretationPlan.Plan repairedBody = new InterpretationPlan.Plan(
            mergedSteps,
            rewrittenBody == null || rewrittenBody.edgeContracts() == null ? List.of() : rewrittenBody.edgeContracts(),
            rewrittenBody == null || rewrittenBody.dependencyContracts() == null ? List.of() : rewrittenBody.dependencyContracts(),
            rewrittenBody == null || rewrittenBody.bindings() == null ? List.of() : rewrittenBody.bindings(),
            rewrittenBody == null ? null : rewrittenBody.stability(),
            normalizeDiagnosticProfile(
                rewrittenBody == null
                    ? originalPlan.plan() == null ? null : originalPlan.plan().diagnosticProfile()
                    : rewrittenBody.diagnosticProfile(),
                mergedSteps
            ),
            rewrittenBody == null || rewrittenBody.conditionalEdges() == null ? List.of() : rewrittenBody.conditionalEdges(),
            rewrittenBody == null || rewrittenBody.branchGroups() == null ? List.of() : rewrittenBody.branchGroups()
        );
        return new InterpretationPlan(
            rewrittenPlan.version(),
            rewrittenPlan.intent(),
            rewrittenPlan.context(),
            repairedBody,
            repairExecutionPolicy(
                originalPlan.executionPolicy(),
                rewrittenPlan.executionPolicy(),
                mergedSteps,
                null
            ),
            rewrittenPlan.review()
        );
    }

    private InterpretationPlan preserveBudgetCeilings(InterpretationPlan.ExecutionPolicy budgetCeilings,
                                                      InterpretationPlan rewrittenPlan) {
        if (rewrittenPlan == null || budgetCeilings == null) {
            return rewrittenPlan;
        }
        InterpretationPlan.ExecutionPolicy guardedPolicy = repairExecutionPolicy(
            null,
            rewrittenPlan.executionPolicy(),
            rewrittenPlan.steps(),
            budgetCeilings
        );
        if (Objects.equals(guardedPolicy, rewrittenPlan.executionPolicy())) {
            return rewrittenPlan;
        }
        return new InterpretationPlan(
            rewrittenPlan.version(),
            rewrittenPlan.intent(),
            rewrittenPlan.context(),
            rewrittenPlan.plan(),
            guardedPolicy,
            rewrittenPlan.review()
        );
    }

    private InterpretationPlan repairExecutionPolicyStepLimit(InterpretationPlan plan, Integer maxStepsCeiling) {
        if (plan == null || plan.executionPolicy() == null) {
            return plan;
        }
        int stepCount = plan.steps() == null ? 0 : plan.steps().size();
        Integer maxSteps = plan.executionPolicy().maxSteps();
        int repairedMaxSteps = maxStepsCeiling == null
            ? stepCount
            : Math.min(stepCount, maxStepsCeiling);
        if (Objects.equals(maxSteps, repairedMaxSteps)) {
            return plan;
        }
        InterpretationPlan.ExecutionPolicy policy = plan.executionPolicy();
        InterpretationPlan.ExecutionPolicy repairedPolicy = new InterpretationPlan.ExecutionPolicy(
            repairedMaxSteps,
            policy.allowParallel(),
            policy.allowTool(),
            policy.denyTool(),
            policy.timeoutMs(),
            policy.maxRewriteTimes(),
            policy.fallbackMode(),
            policy.toolPriority(),
            policy.costBudget(),
            policy.latencyBudgetMs(),
            policy.accuracyVsSpeed()
        );
        return new InterpretationPlan(
            plan.version(),
            plan.intent(),
            plan.context(),
            plan.plan(),
            repairedPolicy,
            plan.review()
        );
    }

    private InterpretationPlan repairAsEvidenceOnlyFinalPlan(RewriteRequest request,
                                                              InterpretationPlan rewrittenPlan) {
        if (request == null || rewrittenPlan == null || rewrittenPlan.steps() == null
            || hasPendingRequiredExecution(request.requiredToolExecutions())
            || !hasSuccessfulToolEvidence(request.observations())) {
            return null;
        }
        InterpretationPlan.Step finalStep = rewrittenPlan.steps().stream()
            .filter(step -> step != null && step.finalAnswerAction())
            .findFirst()
            .orElse(null);
        if (finalStep == null) {
            return null;
        }
        InterpretationPlan.Step detachedFinal = new InterpretationPlan.Step(
            finalStep.id(), finalStep.actionType(), finalStep.toolName(), finalStep.input(),
            List.of(), finalStep.outputContract(), finalStep.validation());
        InterpretationPlan.ExecutionPolicy policy = rewrittenPlan.executionPolicy();
        InterpretationPlan.ExecutionPolicy evidenceOnlyPolicy = policy == null ? null
            : new InterpretationPlan.ExecutionPolicy(
                1, false, List.of(), policy.denyTool(), policy.timeoutMs(), policy.maxRewriteTimes(),
                policy.fallbackMode(), Map.of(), policy.costBudget(), policy.latencyBudgetMs(), policy.accuracyVsSpeed());
        return new InterpretationPlan(
            rewrittenPlan.version(), rewrittenPlan.intent(), rewrittenPlan.context(),
            new InterpretationPlan.Plan(
                List.of(detachedFinal),
                List.of(),
                List.of(),
                List.of(),
                null,
                normalizeDiagnosticProfile(
                    rewrittenPlan.plan() == null ? null : rewrittenPlan.plan().diagnosticProfile(),
                    List.of(detachedFinal)
                ),
                List.of(),
                List.of()
            ),
            evidenceOnlyPolicy, rewrittenPlan.review());
    }

    private InterpretationPlan.DiagnosticProfile normalizeDiagnosticProfile(
        InterpretationPlan.DiagnosticProfile profile,
        List<InterpretationPlan.Step> steps
    ) {
        if (profile == null || profile.checks() == null) {
            return profile;
        }
        Set<Integer> availableStepIds = stepIds(steps);
        List<InterpretationPlan.DiagnosticCheck> checks = profile.checks().stream()
            .map(check -> check == null ? null : new InterpretationPlan.DiagnosticCheck(
                check.checkId(),
                check.capability(),
                check.dimension(),
                check.required(),
                check.priority(),
                (check.stepIds() == null ? List.<Integer>of() : check.stepIds()).stream()
                    .filter(availableStepIds::contains)
                    .distinct()
                    .toList()
            ))
            .toList();
        return new InterpretationPlan.DiagnosticProfile(profile.profileId(), profile.targetKind(), checks);
    }

    private boolean hasPendingRequiredExecution(List<RequiredToolExecution> executions) {
        return executions != null && executions.stream().anyMatch(execution -> execution != null
            && execution.required() && execution.toolName() != null && !execution.toolName().isBlank());
    }

    private boolean hasSuccessfulToolEvidence(List<String> observations) {
        if (observations == null) {
            return false;
        }
        return observations.stream()
            .filter(java.util.Objects::nonNull)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(value -> (value.contains(" tool ")
                || (value.contains("interpretationplan") && value.contains(" step ")))
                && (value.contains(" succeeded") || value.contains("success=true")
                    || value.contains("completed successfully")));
    }

    private void collectTransitiveOriginalDependencies(Set<Integer> missingStepIds,
                                                       Set<Integer> rewrittenStepIds,
                                                       Map<Integer, InterpretationPlan.Step> originalStepsById) {
        ArrayDeque<Integer> pending = new ArrayDeque<>(missingStepIds);
        while (!pending.isEmpty()) {
            Integer stepId = pending.removeFirst();
            InterpretationPlan.Step step = originalStepsById.get(stepId);
            if (step == null || step.dependsOn() == null) {
                continue;
            }
            for (Integer dependency : step.dependsOn()) {
                if (dependency == null || rewrittenStepIds.contains(dependency) || !originalStepsById.containsKey(dependency)) {
                    continue;
                }
                if (missingStepIds.add(dependency)) {
                    pending.addLast(dependency);
                }
            }
        }
    }

    private Set<Integer> stepIds(List<InterpretationPlan.Step> steps) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (steps == null) {
            return ids;
        }
        for (InterpretationPlan.Step step : steps) {
            if (step != null && step.id() != null) {
                ids.add(step.id());
            }
        }
        return ids;
    }

    private Set<Integer> referencedStepIds(InterpretationPlan plan) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (plan == null || plan.plan() == null) {
            return ids;
        }
        for (InterpretationPlan.Step step : plan.steps()) {
            if (step != null && step.dependsOn() != null) {
                ids.addAll(step.dependsOn());
            }
        }
        if (plan.plan().edgeContracts() != null) {
            for (InterpretationPlan.EdgeContract contract : plan.plan().edgeContracts()) {
                if (contract == null) {
                    continue;
                }
                addIfPresent(ids, contract.from());
                addIfPresent(ids, contract.to());
            }
        }
        if (plan.plan().bindings() != null) {
            for (InterpretationPlan.Binding binding : plan.plan().bindings()) {
                if (binding == null) {
                    continue;
                }
                addIfPresent(ids, binding.from());
                addIfPresent(ids, binding.to());
            }
        }
        InterpretationPlan.Stability stability = plan.plan().stability();
        if (stability != null && stability.stableNodes() != null) {
            ids.addAll(stability.stableNodes());
        }
        ids.remove(null);
        return ids;
    }

    private void addIfPresent(Set<Integer> ids, Integer value) {
        if (value != null) {
            ids.add(value);
        }
    }

    private InterpretationPlan.ExecutionPolicy repairExecutionPolicy(InterpretationPlan.ExecutionPolicy originalPolicy,
                                                                     InterpretationPlan.ExecutionPolicy rewrittenPolicy,
                                                                     List<InterpretationPlan.Step> mergedSteps,
                                                                     InterpretationPlan.ExecutionPolicy budgetCeilings) {
        if (rewrittenPolicy == null) {
            return originalPolicy;
        }
        Integer maxSteps = rewrittenPolicy.maxSteps();
        int stepCount = mergedSteps == null ? 0 : mergedSteps.size();
        if (maxSteps != null && maxSteps < stepCount) {
            maxSteps = stepCount;
        }
        if (budgetCeilings != null && budgetCeilings.maxSteps() != null) {
            maxSteps = maxSteps == null
                ? budgetCeilings.maxSteps()
                : Math.min(maxSteps, budgetCeilings.maxSteps());
        }
        Double costBudget = rewrittenPolicy.costBudget();
        if (budgetCeilings != null && budgetCeilings.costBudget() != null) {
            costBudget = costBudget == null
                ? budgetCeilings.costBudget()
                : Math.min(costBudget, budgetCeilings.costBudget());
        }
        Integer latencyBudgetMs = rewrittenPolicy.latencyBudgetMs();
        if (budgetCeilings != null && budgetCeilings.latencyBudgetMs() != null) {
            latencyBudgetMs = latencyBudgetMs == null
                ? budgetCeilings.latencyBudgetMs()
                : Math.min(latencyBudgetMs, budgetCeilings.latencyBudgetMs());
        }
        return new InterpretationPlan.ExecutionPolicy(
            maxSteps,
            rewrittenPolicy.allowParallel(),
            mergeTools(originalPolicy == null ? null : originalPolicy.allowTool(), rewrittenPolicy.allowTool()),
            rewrittenPolicy.denyTool(),
            rewrittenPolicy.timeoutMs(),
            rewrittenPolicy.maxRewriteTimes(),
            rewrittenPolicy.fallbackMode(),
            rewrittenPolicy.toolPriority(),
            costBudget,
            latencyBudgetMs,
            rewrittenPolicy.accuracyVsSpeed()
        );
    }

    private List<String> mergeTools(List<String> left, List<String> right) {
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        if (left != null) {
            tools.addAll(left);
        }
        if (right != null) {
            tools.addAll(right);
        }
        return new ArrayList<>(tools);
    }

    private String validationSummary(InterpretationPlanValidator.ValidationResult validation) {
        if (validation == null || validation.errors() == null || validation.errors().isEmpty()) {
            return "unknown validation error";
        }
        return validation.errors().stream()
            .map(InterpretationPlanValidator.ValidationIssue::message)
            .filter(message -> message != null && !message.isBlank())
            .limit(5)
            .toList()
            .toString();
    }

    public record RewriteRequest(
        InterpretationPlan originalPlan,
        InterpretationPlan.Step failedStep,
        String failureReason,
        List<String> observations,
        List<String> availableTools,
        ToolRegistry toolRegistry,
        List<RequiredToolExecution> requiredToolExecutions,
        List<Map<String, Object>> evidenceHistory,
        InterpretationPlan.ExecutionPolicy budgetCeilings
    ) {
        public RewriteRequest(InterpretationPlan originalPlan,
                              InterpretationPlan.Step failedStep,
                              String failureReason,
                              List<String> observations,
                              List<String> availableTools,
                              ToolRegistry toolRegistry,
                              List<RequiredToolExecution> requiredToolExecutions,
                              List<Map<String, Object>> evidenceHistory) {
            this(originalPlan, failedStep, failureReason, observations, availableTools, toolRegistry,
                requiredToolExecutions, evidenceHistory, null);
        }

        public RewriteRequest(InterpretationPlan originalPlan,
                              InterpretationPlan.Step failedStep,
                              String failureReason,
                              List<String> observations,
                              List<String> availableTools,
                              ToolRegistry toolRegistry,
                              List<RequiredToolExecution> requiredToolExecutions) {
            this(originalPlan, failedStep, failureReason, observations, availableTools, toolRegistry,
                requiredToolExecutions, List.of(), null);
        }

        public RewriteRequest(InterpretationPlan originalPlan,
                              InterpretationPlan.Step failedStep,
                              String failureReason,
                              List<String> observations,
                              List<String> availableTools,
                              ToolRegistry toolRegistry) {
            this(originalPlan, failedStep, failureReason, observations, availableTools, toolRegistry,
                List.of(), List.of(), null);
        }
    }

    public record RequiredToolExecution(
        String toolName,
        String source,
        boolean required
    ) {
    }

    public record RewriteResult(
        boolean valid,
        boolean executable,
        InterpretationPlan rewrittenPlan,
        InterpretationPlanValidator.ValidationResult validation,
        String rawResponse,
        String errorMessage
    ) {
        private static RewriteResult failed(String errorMessage, String rawResponse, InterpretationPlan rewrittenPlan) {
            return new RewriteResult(false, false, rewrittenPlan, null, rawResponse, errorMessage);
        }
    }
}
