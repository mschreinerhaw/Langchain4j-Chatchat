package com.chatchat.agents.orchestration;

import com.chatchat.agents.orchestration.planning.AgentPlanBudgetPolicy;
import com.chatchat.agents.orchestration.planning.AgentPlannerPromptBuilder;
import com.chatchat.agents.orchestration.protocol.PlannerEnvelopeDto;
import com.chatchat.agents.tool.RegistryMcpCapabilityHierarchy;

import com.chatchat.agents.assessment.RuntimeAnswerCandidate;
import com.chatchat.agents.assessment.TaskContract;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.protocol.ToolProtocolContractResolver;
import com.chatchat.agents.runtime.observation.AgentRuntimeFactGroundingContract;
import com.chatchat.agents.runtime.batch.ToolCallBatchSchema;
import com.chatchat.agents.runtime.plan.DagRepairResult;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationExecutionProtocol;
import com.chatchat.agents.runtime.plan.InterpretationPlanJsonSchema;
import com.chatchat.agents.runtime.plan.InterpretationPlanOptimizer;
import com.chatchat.agents.runtime.plan.InterpretationPlanValidator;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.chatchat.common.mcp.capability.McpCapabilityHierarchy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds planner prompts and parses planner decisions.
 */
@Slf4j
public class AgentPlanner implements AgentPlanningPort {

    private static final String DOCUMENT_SEARCH_TOOL = "document_search";
    private static final String FINAL = "final";
    private static final String TOOL = "tool";
    private static final int DEFAULT_PLAN_REPAIR_ATTEMPTS = 3;
    private static final int MAX_PLAN_REPAIR_ATTEMPTS = 3;
    private static final int MAX_USER_QUERY_PROMPT_CHARS = 32_000;
    private static final Pattern FINAL_ANSWER_STEP_PATTERN = Pattern.compile(
        "(?s)\"action_type\"\\s*:\\s*\"final_answer\".*?\"answer\"\\s*:\\s*\"(.*?)\"\\s*}\\s*,\\s*\"depends_on\""
    );
    private static final Pattern CANDIDATE_ANSWER_PATTERN = Pattern.compile(
        "(?s)\"candidate_answer\"\\s*:\\s*\\{.*?\"content\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"type\""
    );

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final McpCapabilityHierarchy capabilityHierarchy;
    private final AgentPlannerPromptBuilder promptBuilder;
    private final InterpretationPlanValidator interpretationPlanValidator = new InterpretationPlanValidator();
    private final ToolProtocolContractResolver toolProtocolContracts = new ToolProtocolContractResolver();

    AgentPlanner(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this(toolRegistry, objectMapper, Clock.systemDefaultZone());
    }

    AgentPlanner(ToolRegistry toolRegistry, ObjectMapper objectMapper, Clock clock) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
        this.capabilityHierarchy = toolRegistry == null
            ? McpCapabilityHierarchy.empty()
            : new RegistryMcpCapabilityHierarchy(toolRegistry);
        this.promptBuilder = new AgentPlannerPromptBuilder(toolRegistry, objectMapper, this.clock);
    }

    @Override
    public PlannerExecutionResult plan(AgentPlanningRequest request) {
        return decideNextAction(
            request.chatModel(), request.query(), request.systemPrompt(), request.availableTools(),
            request.observations(), request.boundDocumentIds(), request.boundDocumentTags(),
            request.mandatoryTools(), request.requireToolBeforeFinal(),
            request.requireDocumentWebVerification(), request.documentSearchTool(),
            request.verificationWebSearchTool(), request.runtimeAttributes());
    }

    PlannerExecutionResult decideNextAction(ChatModel activeChatModel,
                                            String query,
                                            String systemPrompt,
                                            List<String> availableTools,
                                            List<String> observations,
                                            List<String> boundDocumentIds,
                                            List<String> boundDocumentTags,
                                            List<String> mandatoryTools,
                                            boolean requireToolBeforeFinal,
                                            boolean requireDocumentWebVerification,
                                            String documentSearchTool,
                                            String verificationWebSearchTool,
                                            Map<String, Object> runtimeAttributes) {
        AgentDecision decision = decideNextDecision(
            activeChatModel, query, systemPrompt, availableTools, observations,
            boundDocumentIds, boundDocumentTags, mandatoryTools, requireToolBeforeFinal,
            requireDocumentWebVerification, documentSearchTool, verificationWebSearchTool,
            runtimeAttributes
        );
        return plannerExecutionResult(decision, query, mandatoryTools, requireToolBeforeFinal);
    }

    private AgentDecision decideNextDecision(ChatModel activeChatModel,
                                             String query,
                                             String systemPrompt,
                                             List<String> availableTools,
                                             List<String> observations,
                                             List<String> boundDocumentIds,
                                             List<String> boundDocumentTags,
                                             List<String> mandatoryTools,
                                             boolean requireToolBeforeFinal,
                                             boolean requireDocumentWebVerification,
                                             String documentSearchTool,
                                             String verificationWebSearchTool,
                                             Map<String, Object> runtimeAttributes) {
        String prompt = buildPlannerPrompt(
            query,
            systemPrompt,
            availableTools,
            observations,
            boundDocumentIds,
            boundDocumentTags,
            mandatoryTools,
            requireToolBeforeFinal,
            requireDocumentWebVerification,
            documentSearchTool,
            verificationWebSearchTool,
            runtimeAttributes
        );
        PlannerValidationContext validationContext = new PlannerValidationContext(
            normalizeList(mandatoryTools),
            requireToolBeforeFinal,
            requireDocumentWebVerification,
            documentSearchTool,
            verificationWebSearchTool,
            normalizeList(availableTools),
            query,
            experiencePrior(runtimeAttributes),
            AgentPlanBudgetPolicy.fromRuntimeAttributes(runtimeAttributes),
            authoritativeWorkflowDagForPlanning(runtimeAttributes)
        );
        String runId = stringValue(runtimeAttributes == null ? null : runtimeAttributes.get("__agentRunId"));
        int maxAttempts = plannerRepairAttempts(runtimeAttributes);
        String currentPrompt = prompt;
        AgentDecision lastDecision = null;
        String lastRaw = null;
        String logRunId = runId == null ? "" : runId;
        List<PlanCandidate> candidates = new ArrayList<>();
        boolean experienceOptimizationRequested = false;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long startedAt = System.currentTimeMillis();
            log.info("agentModelRequest phase=planner runId={} attempt={}/{} modelClass={} promptChars={} toolCount={} observationCount={}",
                logRunId,
                attempt,
                maxAttempts,
                activeChatModel == null ? null : activeChatModel.getClass().getName(),
                currentPrompt.length(),
                availableTools == null ? 0 : availableTools.size(),
                observations == null ? 0 : observations.size());
            String raw = activeChatModel.chat(currentPrompt);
            lastRaw = raw;
            log.info("agentModelResponse phase=planner runId={} attempt={}/{} durationMs={} responseChars={}",
                logRunId,
                attempt,
                maxAttempts,
                System.currentTimeMillis() - startedAt,
                raw == null ? 0 : raw.length());
            logPlannerRawOutput(logRunId, attempt, maxAttempts, raw);
            AgentDecision decision = parseDecision(raw, validationContext);
            if (decision == null) {
                lastDecision = invalidPlannerDecision(
                    raw, "non_json_response", "Planner did not return valid JSON.", validationContext);
                logPlannerDecision(logRunId, attempt, maxAttempts, lastDecision);
            } else {
                logPlannerDecision(logRunId, attempt, maxAttempts, decision);
            }
            if (decision != null) {
                lastDecision = decision;
            }
            candidates.add(planCandidate(attempt, raw, lastDecision, validationContext));
            if (shouldDeferInvalidPlanToAuthoritativeRuntime(lastDecision, validationContext)) {
                log.info("agentPlannerAuthoritativeFallback phase=planner_parse runId={} attempt={}/{} "
                        + "reason=invalid_plan_with_authoritative_workflow mandatoryToolCount={}",
                    logRunId, attempt, maxAttempts,
                    normalizeList(validationContext.mandatoryTools()).size());
                break;
            }
            if (hasPresentableRecoveredAnswer(lastDecision)
                && normalizeList(validationContext.mandatoryTools()).isEmpty()) {
                log.info("agentPlannerAcceptedPresentableAnswer phase=planner_parse runId={} attempt={}/{} "
                        + "reason=non_json_report mandatoryToolsPending=0",
                    logRunId, attempt, maxAttempts);
                break;
            }
            if (decision != null && !plannerPlanInvalid(decision)) {
                PlanCandidate currentCandidate = candidates.get(candidates.size() - 1);
                if (!experienceOptimizationRequested
                    && attempt < maxAttempts
                    && shouldOptimizeFromExperience(currentCandidate, validationContext)) {
                    experienceOptimizationRequested = true;
                    currentPrompt = buildExperienceOptimizationPrompt(
                        prompt, raw, currentCandidate, attempt + 1, maxAttempts);
                    continue;
                }
                PlanRewriteContext rewriteContext = planRewriteContext(candidates);
                if (experienceOptimizationRequested) {
                    PlanCandidate baseline = candidates.stream()
                        .filter(candidate -> candidate != null
                            && candidate.decision() != null
                            && !plannerPlanInvalid(candidate.decision()))
                        .findFirst()
                        .orElse(currentCandidate);
                    if (!sameExperienceWorkflowContract(baseline.decision(), currentCandidate.decision())) {
                        return withAttributionMetadata(
                            baseline.decision(),
                            baseline,
                            rewriteContext,
                            "Rejected experience optimization candidate because it changed the user-bound workflow contract.",
                            false,
                            List.of("experience_workflow_mutation_rejected")
                        );
                    }
                    AgentDecision optimized = attributeAndSelectBestPlan(rewriteContext, validationContext, logRunId);
                    if (optimized != null) {
                        return optimized;
                    }
                }
                return withAttributionMetadata(
                    decision,
                    currentCandidate,
                    rewriteContext,
                    "Selected the first runtime-valid plan candidate.",
                    false,
                    List.of()
                );
            }
            if (attempt < maxAttempts && shouldRepairPlan(lastDecision, validationContext)) {
                currentPrompt = buildPlannerRepairPrompt(prompt, raw, lastDecision, attempt + 1, maxAttempts);
                continue;
            }
            break;
        }
        PlanRewriteContext rewriteContext = planRewriteContext(candidates);
        AgentDecision attributionDecision = attributeAndSelectBestPlan(rewriteContext, validationContext, logRunId);
        if (attributionDecision != null) {
            return attributionDecision;
        }
        return lastDecision == null
            ? invalidPlannerDecision(
                lastRaw, "non_json_response", "Planner did not return valid JSON.", validationContext)
            : lastDecision;
    }

    private PlannerExecutionResult plannerExecutionResult(AgentDecision decision,
                                                          String query,
                                                          List<String> mandatoryTools,
                                                          boolean requireToolBeforeFinal) {
        Map<String, Object> executionPlan = decision == null || decision.executionPlan() == null
            ? Map.of() : decision.executionPlan();
        boolean planValid = Boolean.TRUE.equals(executionPlan.get("interpretationPlanValid"));
        boolean planExecutable = Boolean.TRUE.equals(executionPlan.get("interpretationPlanExecutable"));
        List<String> issues = stringList(executionPlan.get("interpretationPlanRuntimeIssues"));
        PlannerPlanProduct planProduct = new PlannerPlanProduct(
            decision == null ? null : decision.interpretationPlan(),
            planValid,
            planExecutable,
            issues
        );

        String answer = candidateAnswer(decision);
        RuntimeAnswerCandidate candidate = null;
        boolean explicitlyPreserved = Boolean.TRUE.equals(
            executionPlan.get("plannerCandidateAnswerPreserved"));
        boolean legacyBusinessAnswer = decision != null
            && FINAL.equals(decision.action())
            && !"non_json_response".equals(decision.reason())
            && !"invalid_interpretation_plan".equals(decision.reason());
        if (answer != null && !answer.isBlank()
            && (planValid || explicitlyPreserved || legacyBusinessAnswer)) {
            candidate = new RuntimeAnswerCandidate(
                RuntimeAnswerCandidate.CONTRACT_VERSION,
                answer,
                firstNonBlank(stringValue(executionPlan.get("generationType")), "generated_artifact"),
                firstNonBlank(stringValue(executionPlan.get("answerOrigin")), "planner_generated"),
                RuntimeAnswerCandidate.Status.GENERATED,
                Map.of(
                    "planSyntaxValid", planValid,
                    "planExecutable", planExecutable,
                    "contentPreservedIndependently", true
                )
            );
        }

        boolean evidenceRequired = requireToolBeforeFinal
            || (mandatoryTools != null && !mandatoryTools.isEmpty());
        String taskType = decision != null
            && decision.interpretationPlan() != null
            && decision.interpretationPlan().intent() != null
            ? decision.interpretationPlan().intent().type()
            : "generation";
        String userGoal = decision != null
            && decision.interpretationPlan() != null
            && decision.interpretationPlan().intent() != null
            ? firstNonBlank(decision.interpretationPlan().intent().goal(), query)
            : query;
        TaskContract taskContract = new TaskContract(
            TaskContract.CONTRACT_VERSION,
            taskType,
            userGoal,
            evidenceRequired
                ? TaskContract.EvidenceRequirement.REQUIRED
                : TaskContract.EvidenceRequirement.OPTIONAL,
            !evidenceRequired,
            candidate == null ? "answer" : candidate.type(),
            normalizeList(mandatoryTools)
        );
        return new PlannerExecutionResult(planProduct, candidate, taskContract, decision);
    }

    private String candidateAnswer(AgentDecision decision) {
        if (decision == null) {
            return null;
        }
        String independent = stringValue(
            decision.executionPlan() == null
                ? null : decision.executionPlan().get("candidateAnswerContent"));
        if (independent != null && !independent.isBlank()) {
            return independent;
        }
        if (decision.answer() != null && !decision.answer().isBlank()) {
            return decision.answer();
        }
        InterpretationPlan plan = decision.interpretationPlan();
        if (plan == null || plan.steps() == null) {
            return null;
        }
        return plan.steps().stream()
            .filter(step -> step != null && step.finalAnswerAction())
            .map(step -> answerFromFinalStep(plan, step))
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse(null);
    }

    private String buildPlannerPrompt(String query,
                                      String systemPrompt,
                                      List<String> availableTools,
                                      List<String> observations,
                                      List<String> boundDocumentIds,
                                      List<String> boundDocumentTags,
                                      List<String> mandatoryTools,
                                      boolean requireToolBeforeFinal,
                                      boolean requireDocumentWebVerification,
                                      String documentSearchTool,
                                      String verificationWebSearchTool,
                                      Map<String, Object> runtimeAttributes) {
        return promptBuilder.build(
            query, systemPrompt, availableTools, observations, boundDocumentIds, boundDocumentTags,
            mandatoryTools, requireToolBeforeFinal, requireDocumentWebVerification,
            documentSearchTool, verificationWebSearchTool, runtimeAttributes
        );
    }

    private void appendMcpControlPlaneToolContracts(StringBuilder prompt, List<String> availableTools) {
        promptBuilder.appendMcpControlPlaneToolContracts(prompt, availableTools);
    }

    private String describeTools(List<String> availableTools, Map<String, Object> runtimeAttributes) {
        return promptBuilder.describeTools(availableTools, runtimeAttributes);
    }
    private AgentDecision parseDecision(String raw, PlannerValidationContext validationContext) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = extractJson(raw);
        try {
            PlannerEnvelopeDto envelope = parsePlannerEnvelope(json);
            Map<String, Object> payload = objectMapper.convertValue(envelope.planningPayload(), Map.class);
            AgentDecision interpretationPlanDecision = parseInterpretationPlanDecision(payload, validationContext);
            if (interpretationPlanDecision != null) {
                return attachCandidateAnswer(interpretationPlanDecision, envelope.candidateAnswer());
            }
            if (requiresStrictInterpretationPlan(validationContext)) {
                return invalidPlannerDecision(
                    raw,
                    "legacy_action_not_allowed",
                    "MCP workflow requires an InterpretationPlan; legacy action JSON is not allowed.",
                    validationContext
                );
            }
            String action = stringValue(payload.get("action"));
            if (action == null) {
                return null;
            }
            action = action.toLowerCase(Locale.ROOT);
            Map<String, Object> executionPlan = asMap(payload.get("executionPlan"));
            if (FINAL.equals(action)) {
                Boolean sufficient = booleanObject(firstObject(payload, "sufficient", "isSufficient"));
                if (sufficient == null) {
                    sufficient = booleanObject(firstObject(executionPlan, "sufficient", "isSufficient"));
                }
                return new AgentDecision(
                    FINAL,
                    null,
                    Map.of(),
                    stringValue(payload.get("answer")),
                    stringValue(payload.get("reason")),
                    executionPlan,
                    sufficient
                );
            }
            if (!TOOL.equals(action)) {
                return null;
            }
            Object argsObj = payload.get("arguments");
            Map<String, Object> arguments = argsObj instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
            return new AgentDecision(
                TOOL,
                stringValue(payload.get("toolName")),
                arguments,
                null,
                stringValue(payload.get("reason")),
                executionPlan,
                null
            );
        } catch (Exception ex) {
            log.debug("Failed to parse planner decision: {}", raw, ex);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private PlannerEnvelopeDto parsePlannerEnvelope(String json) throws IOException {
        try {
            return PlannerEnvelopeDto.from(objectMapper.readTree(json), objectMapper);
        } catch (JsonProcessingException initialFailure) {
            String repaired = repairInvalidJsonStringCharacters(json);
            if (repaired.equals(json)) {
                throw initialFailure;
            }
            PlannerEnvelopeDto parsed = PlannerEnvelopeDto.from(objectMapper.readTree(repaired), objectMapper);
            log.warn("Planner JSON contained unescaped quote or control characters inside string values; "
                + "Runtime repaired the JSON syntax before InterpretationPlan validation.");
            return parsed;
        }
    }

    /**
     * Repairs invalid characters only while inside JSON string values. A terminating quote must be
     * followed (ignoring whitespace) by a JSON structural delimiter, while raw control characters
     * are converted to their JSON escape sequences. The repaired document is still parsed and fully
     * validated; missing commas, braces and other malformed structures remain rejected.
     */
    private String repairInvalidJsonStringCharacters(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        StringBuilder repaired = new StringBuilder(json.length() + 16);
        boolean inString = false;
        boolean escaped = false;
        boolean changed = false;
        for (int index = 0; index < json.length(); index++) {
            char current = json.charAt(index);
            if (!inString) {
                repaired.append(current);
                if (current == '"') {
                    inString = true;
                }
                continue;
            }
            if (escaped) {
                repaired.append(current);
                escaped = false;
                continue;
            }
            if (current == '\\') {
                repaired.append(current);
                escaped = true;
                continue;
            }
            if (current < 0x20) {
                appendJsonControlCharacter(repaired, current);
                changed = true;
                continue;
            }
            if (current != '"') {
                repaired.append(current);
                continue;
            }
            int next = index + 1;
            while (next < json.length() && Character.isWhitespace(json.charAt(next))) {
                next++;
            }
            boolean legalTerminator = next >= json.length()
                || json.charAt(next) == ':'
                || json.charAt(next) == ','
                || json.charAt(next) == '}'
                || json.charAt(next) == ']';
            if (legalTerminator) {
                repaired.append(current);
                inString = false;
            } else {
                repaired.append('\\').append(current);
                changed = true;
            }
        }
        return changed ? repaired.toString() : json;
    }

    private void appendJsonControlCharacter(StringBuilder target, char value) {
        switch (value) {
            case '\b' -> target.append("\\b");
            case '\f' -> target.append("\\f");
            case '\n' -> target.append("\\n");
            case '\r' -> target.append("\\r");
            case '\t' -> target.append("\\t");
            default -> {
                String hex = Integer.toHexString(value);
                target.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
            }
        }
    }

    private AgentDecision attachCandidateAnswer(AgentDecision decision,
                                                PlannerEnvelopeDto.CandidateAnswerDto candidatePayload) {
        if (decision == null || candidatePayload == null) {
            return decision;
        }
        String content = candidatePayload.content();
        if (content == null || content.isBlank()) {
            return decision;
        }
        Map<String, Object> executionPlan = new LinkedHashMap<>(
            decision.executionPlan() == null ? Map.of() : decision.executionPlan());
        executionPlan.put("plannerCandidateAnswerPreserved", true);
        executionPlan.put("protectedCandidateAnswer", true);
        executionPlan.put("candidateAnswerContent", content);
        executionPlan.put("answerOrigin", "planner_generated");
        executionPlan.put("generationType", firstNonBlank(
            candidatePayload.type(),
            "generated_artifact"
        ));
        return new AgentDecision(
            decision.action(),
            decision.toolName(),
            decision.arguments(),
            FINAL.equals(decision.action()) ? content : decision.answer(),
            decision.reason(),
            executionPlan,
            decision.sufficient(),
            decision.interpretationPlan()
        );
    }

    private AgentDecision parseInterpretationPlanDecision(Map<String, Object> payload,
                                                         PlannerValidationContext validationContext) {
        if (payload == null || !payload.containsKey("plan") || !payload.containsKey("intent")) {
            return null;
        }
        payload = normalizeInterpretationPlanPayload(payload);
        InterpretationPlan interpretationPlan = objectMapper.convertValue(payload, InterpretationPlan.class);
        AgentPlanBudgetPolicy.ApplyResult budgetResult = AgentPlanBudgetPolicy.apply(
            interpretationPlan,
            validationContext == null ? null : validationContext.budgetCaps()
        );
        interpretationPlan = budgetResult.plan();
        InterpretationPlan sourcePlan = interpretationPlan;
        InterpretationPlanOptimizer.OptimizationResult optimization =
            new InterpretationPlanOptimizer(toolRegistry).optimize(
                interpretationPlan,
                validationContext == null ? null : validationContext.authoritativeWorkflowDag()
            );
        if (optimization.plan() != null) {
            InterpretationPlan optimized = optimization.plan();
            // Planning-time optimization repairs the graph before validation. The model's
            // already budget-capped execution policy remains authoritative here; runtime
            // policy tuning must not silently expand an explicit zero-rewrite budget.
            interpretationPlan = new InterpretationPlan(
                optimized.version(),
                optimized.intent(),
                optimized.context(),
                optimized.plan(),
                interpretationPlan.executionPolicy(),
                optimized.review()
            );
        }
        DagRepairResult dagRepair = DagRepairResult.derive(
            sourcePlan,
            interpretationPlan,
            optimization.appliedPasses(),
            optimization.repairResult().stepIdMappings(),
            optimization.repairResult().passFailures());
        InterpretationPlanValidator.ValidationResult validation =
            interpretationPlanValidator.validate(
                interpretationPlan,
                toolRegistry,
                new LinkedHashSet<>(validationContext == null ? List.of() : normalizeList(validationContext.availableTools()))
            );
        List<String> runtimeIssues = validateRuntimePlanRules(interpretationPlan, validationContext);
        Map<String, Object> validationMetadata = new LinkedHashMap<>(validationMetadata(validation, runtimeIssues));
        validationMetadata.put("dagRepair", dagRepair.auditMetadata());
        validationMetadata.put("dagRepairValidationState",
            validation.valid() && runtimeIssues.isEmpty() ? "ACCEPTED" : "REJECTED");
        if (!optimization.appliedPasses().isEmpty()) {
            validationMetadata.put("interpretationPlanOptimizationPasses", optimization.appliedPasses());
        }
        if (optimization.appliedPasses().contains("AuthoritativeWorkflowDagPass")) {
            boolean repairedCandidateValid = validation.valid() && runtimeIssues.isEmpty();
            String repairState = repairedCandidateValid ? "APPLIED" : "REJECTED";
            String repairCode = repairedCandidateValid
                ? "AUTHORITATIVE_WORKFLOW_DAG_RESTORED"
                : "AUTHORITATIVE_WORKFLOW_DAG_RESTORED_PLAN_INVALID";
            Map<String, Object> repairEvent = Map.of(
                "contractVersion", "runtime_dag_governance.v1",
                "eventKind", "DAG_REPAIR",
                "eventState", repairState,
                "repairCode", repairCode,
                "topologyRestored", true,
                "candidateValid", repairedCandidateValid,
                "source", "user_defined_mcp_workflow"
            );
            validationMetadata.put("eventKind", "DAG_REPAIR");
            validationMetadata.put("eventState", repairState);
            validationMetadata.put("repairEvent", repairEvent);
        }
        if (validationContext != null && validationContext.budgetCaps() != null
            && validationContext.budgetCaps().configured()) {
            validationMetadata.put("agentBudgetCaps", validationContext.budgetCaps().metadata());
            validationMetadata.put("agentBudgetAdjusted", budgetResult.adjusted());
        }
        if (!validation.valid() || !runtimeIssues.isEmpty()) {
            return new AgentDecision(
                FINAL,
                null,
                Map.of(),
                "Planner produced an invalid InterpretationPlan.",
                "invalid_interpretation_plan",
                validationMetadata,
                false,
                interpretationPlan
            );
        }
        InterpretationPlan.Step nextStep = nextExecutableStep(interpretationPlan);
        if (nextStep == null) {
            return new AgentDecision(
                FINAL,
                null,
                Map.of(),
                answerFromFinalStep(interpretationPlan, null),
                "interpretation_plan_without_actionable_step",
                validationMetadata,
                null,
                interpretationPlan
            );
        }
        Map<String, Object> executionPlan = new LinkedHashMap<>(validationMetadata);
        executionPlan.put("plan_step_id", nextStep.id());
        executionPlan.put("action_type", nextStep.actionType());
        executionPlan.put("intent", interpretationPlan.intent() == null ? null : interpretationPlan.intent().goal());
        executionPlan.put("risk_level", interpretationPlan.intent() == null ? null : interpretationPlan.intent().riskLevel());
        executionPlan.put("tool", nextStep.toolName());
        if (nextStep.finalAnswerAction() && nextStep.input() != null) {
            Object artifactContract = firstObject(
                nextStep.input(), "artifact_contract", "artifactContract");
            if (artifactContract instanceof Map<?, ?>) {
                executionPlan.put("artifactContract", artifactContract);
            }
        }

        if (nextStep.mcpToolAction()) {
            return new AgentDecision(
                TOOL,
                nextStep.toolName(),
                nextStep.input() == null ? Map.of() : nextStep.input(),
                null,
                interpretationPlan.intent() == null ? null : interpretationPlan.intent().goal(),
                executionPlan,
                false,
                interpretationPlan
            );
        }
        if (nextStep.finalAnswerAction()) {
            return new AgentDecision(
                FINAL,
                null,
                Map.of(),
                answerFromFinalStep(interpretationPlan, nextStep),
                "interpretation_plan_final_answer",
                executionPlan,
                interpretationPlan.review() != null
                    && interpretationPlan.review().selfCheck() != null
                    && Boolean.TRUE.equals(interpretationPlan.review().selfCheck().toolSufficiency()),
                interpretationPlan
            );
        }
        return new AgentDecision(
            FINAL,
            null,
            Map.of(),
            answerFromFinalStep(interpretationPlan, nextStep),
            "interpretation_plan_reasoning_step",
            executionPlan,
            null,
            interpretationPlan
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeInterpretationPlanPayload(Map<String, Object> payload) {
        Map<String, Object> normalized = new LinkedHashMap<>(payload);
        alias(normalized, "executionPolicy", "execution_policy");

        Map<String, Object> plan = mutableMap(normalized.get("plan"));
        if (!plan.isEmpty()) {
            alias(plan, "edgeContracts", "edge_contracts");
            alias(plan, "dependencyContracts", "dependency_contracts");
            alias(plan, "conditionalEdges", "conditional_edges");
            alias(plan, "branchGroups", "branch_groups");
            Object rawConditionalEdges = plan.get("conditional_edges");
            if (rawConditionalEdges instanceof List<?> edges) {
                List<Object> normalizedEdges = new ArrayList<>();
                for (Object rawEdge : edges) {
                    Map<String, Object> edge = mutableMap(rawEdge);
                    alias(edge, "branchGroupId", "branch_group_id");
                    alias(edge, "defaultEdge", "default_edge");
                    normalizedEdges.add(edge.isEmpty() ? rawEdge : edge);
                }
                plan.put("conditional_edges", normalizedEdges);
            }
            Object rawBranchGroups = plan.get("branch_groups");
            if (rawBranchGroups instanceof List<?> groups) {
                List<Object> normalizedGroups = new ArrayList<>();
                for (Object rawGroup : groups) {
                    Map<String, Object> group = mutableMap(rawGroup);
                    alias(group, "candidateStepIds", "candidate_step_ids");
                    alias(group, "targetStepId", "target_step_id");
                    alias(group, "selectionStrategy", "selection_strategy");
                    normalizedGroups.add(group.isEmpty() ? rawGroup : group);
                }
                plan.put("branch_groups", normalizedGroups);
            }
            Object rawDependencyContracts = plan.get("dependency_contracts");
            if (rawDependencyContracts instanceof List<?> contracts) {
                List<Object> normalizedContracts = new ArrayList<>();
                for (Object rawContract : contracts) {
                    Map<String, Object> contract = mutableMap(rawContract);
                    if (contract.isEmpty()) {
                        normalizedContracts.add(rawContract);
                        continue;
                    }
                    alias(contract, "onFailure", "on_failure");
                    normalizedContracts.add(contract);
                }
                plan.put("dependency_contracts", normalizedContracts);
            }
            Object rawSteps = plan.get("steps");
            if (rawSteps instanceof List<?> steps) {
                List<Object> normalizedSteps = new ArrayList<>();
                for (Object rawStep : steps) {
                    Map<String, Object> step = mutableMap(rawStep);
                    if (step.isEmpty()) {
                        normalizedSteps.add(rawStep);
                        continue;
                    }
                    alias(step, "actionType", "action_type");
                    alias(step, "toolName", "tool_name");
                    alias(step, "dependsOn", "depends_on");
                    alias(step, "outputContract", "output_contract");
                    Map<String, Object> outputContract = mutableMap(step.get("output_contract"));
                    if (!outputContract.isEmpty()) {
                        alias(outputContract, "schemaHint", "schema_hint");
                        step.put("output_contract", outputContract);
                    }
                    step.put("input", normalizeStepInput(stringValue(step.get("tool_name")), step.get("input")));
                    normalizedSteps.add(step);
                }
                plan.put("steps", normalizedSteps);
            }
            Map<String, Object> stability = mutableMap(plan.get("stability"));
            if (!stability.isEmpty()) {
                alias(stability, "stableNodes", "stable_nodes");
                alias(stability, "criticalTools", "critical_tools");
                alias(stability, "lockedEdges", "locked_edges");
                alias(stability, "mutableActionTypes", "mutable_action_types");
                plan.put("stability", stability);
            }
            normalized.put("plan", plan);
        }

        Map<String, Object> policy = mutableMap(normalized.get("execution_policy"));
        if (!policy.isEmpty()) {
            alias(policy, "maxSteps", "max_steps");
            alias(policy, "allowParallel", "allow_parallel");
            alias(policy, "allowTool", "allow_tool");
            alias(policy, "denyTool", "deny_tool");
            alias(policy, "timeoutMs", "timeout_ms");
            alias(policy, "maxRewriteTimes", "max_rewrite_times");
            alias(policy, "fallbackMode", "fallback_mode");
            alias(policy, "toolPriority", "tool_priority");
            alias(policy, "costBudget", "cost_budget");
            alias(policy, "latencyBudgetMs", "latency_budget_ms");
            alias(policy, "accuracyVsSpeed", "accuracy_vs_speed");
            policy.put("tool_priority", clampPriorityMap(policy.get("tool_priority")));
            policy.put("accuracy_vs_speed", clampNullableDouble(policy.get("accuracy_vs_speed"), 0.0, 1.0));
            normalized.put("execution_policy", policy);
        }

        Map<String, Object> intent = mutableMap(normalized.get("intent"));
        if (!intent.isEmpty()) {
            alias(intent, "riskLevel", "risk_level");
            normalized.put("intent", intent);
        }

        Map<String, Object> context = mutableMap(normalized.get("context"));
        if (!context.isEmpty()) {
            alias(context, "keyFacts", "key_facts");
            alias(context, "missingInfo", "missing_info");
            normalized.put("context", context);
        }

        Map<String, Object> review = mutableMap(normalized.get("review"));
        if (!review.isEmpty()) {
            alias(review, "selfCheck", "self_check");
            Map<String, Object> selfCheck = mutableMap(review.get("self_check"));
            if (!selfCheck.isEmpty()) {
                alias(selfCheck, "completenessScore", "completeness_score");
                alias(selfCheck, "hallucinationRisk", "hallucination_risk");
                alias(selfCheck, "toolSufficiency", "tool_sufficiency");
                alias(selfCheck, "missingSteps", "missing_steps");
                review.put("self_check", selfCheck);
            }
            alias(review, "fallbackPlan", "fallback_plan");
            normalized.put("review", review);
        }
        return normalized;
    }

    private Map<String, Object> normalizeStepInput(String toolName, Object rawInput) {
        Map<String, Object> input = mutableMap(rawInput);
        if (input.isEmpty()) {
            return input;
        }
        String semanticTool = toolSemanticKey(toolName);
        if (workflowRole(toolName) == ToolWorkflowRole.ASSET_DISCOVERY) {
            normalizeDiscoveryQueryInput(input);
        }
        if (workflowRole(toolName) == ToolWorkflowRole.TEMPLATE_DISCOVERY) {
            normalizeDiscoveryQueryInput(input);
        }
        if ("linux_command_execute".equals(semanticTool)) {
            alias(input, "command_template", "template");
            alias(input, "commandTemplate", "template");
            alias(input, "templateCode", "template");
            alias(input, "context", "executionContext");
        }
        return input;
    }

    private void normalizeDiscoveryQueryInput(Map<String, Object> input) {
        Object context = input.remove("context");
        if (context instanceof Map<?, ?> map) {
            input.putIfAbsent("filters", map);
            return;
        }
        if (context != null && !String.valueOf(context).isBlank()) {
            String text = String.valueOf(context).trim();
            Map<String, Object> filters = mutableMap(input.get("filters"));
            if (looksLikeAssetName(text)) {
                filters.putIfAbsent("assetName", text);
            } else {
                filters.putIfAbsent("service", text);
            }
            input.put("filters", filters);
        }
    }

    private boolean looksLikeAssetName(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String text = value.trim();
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("_")
            || normalized.contains(":")
            || normalized.startsWith("ssh_")
            || normalized.startsWith("sql_")
            || normalized.startsWith("http_");
    }

    private Map<String, Double> clampPriorityMap(Object value) {
        Map<String, Object> raw = mutableMap(value);
        if (raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> clamped = new LinkedHashMap<>();
        raw.forEach((tool, priority) -> {
            Double number = doubleValue(priority);
            if (tool != null && !tool.isBlank() && number != null) {
                clamped.put(tool, clamp(number, 0.0, 1.0));
            }
        });
        return clamped;
    }

    private Double clampNullableDouble(Object value, double min, double max) {
        Double number = doubleValue(value);
        return number == null ? null : clamp(number, min, max);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void alias(Map<String, Object> values, String alias, String canonical) {
        if (values == null || !values.containsKey(alias) || values.containsKey(canonical)) {
            return;
        }
        values.put(canonical, values.remove(alias));
    }

    private Map<String, Object> mutableMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null) {
                values.put(String.valueOf(key), item);
            }
        });
        return values;
    }

    private InterpretationPlan.Step nextExecutableStep(InterpretationPlan interpretationPlan) {
        if (interpretationPlan == null || interpretationPlan.steps().isEmpty()) {
            return null;
        }
        return interpretationPlan.steps().stream()
            .filter(step -> step != null && (step.mcpToolAction() || step.finalAnswerAction()))
            .findFirst()
            .orElse(interpretationPlan.steps().get(0));
    }

    private String answerFromFinalStep(InterpretationPlan interpretationPlan, InterpretationPlan.Step finalStep) {
        Map<String, Object> input = finalStep == null ? Map.of() : finalStep.input();
        String answer = firstNonBlank(
            stringValue(firstObject(input, "answer", "response", "text", "result")),
            null
        );
        if (answer != null) {
            return answer;
        }
        return interpretationPlan == null || interpretationPlan.intent() == null
            ? ""
            : firstNonBlank(interpretationPlan.intent().goal(), "");
    }

    private Map<String, Object> validationMetadata(InterpretationPlanValidator.ValidationResult validation,
                                                   List<String> runtimeIssues) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("plannerProtocol", "interpretation_plan");
        boolean runtimeValid = runtimeIssues == null || runtimeIssues.isEmpty();
        metadata.put("interpretationPlanValid", validation.valid() && runtimeValid);
        metadata.put("interpretationPlanSchemaValid", validation.valid());
        metadata.put("interpretationPlanRuntimeRulesValid", runtimeValid);
        metadata.put("interpretationPlanExecutable", validation.executable() && runtimeValid);
        metadata.put("interpretationPlanApprovalRequired", validation.approvalRequired());
        metadata.put("interpretationPlanIssues", validation.issues().stream()
            .map(issue -> Map.of(
                "severity", issue.severity(),
                "path", issue.path(),
                "message", issue.message()
            ))
            .toList());
        metadata.put("interpretationPlanRuntimeIssues", runtimeIssues == null ? List.of() : runtimeIssues);
        metadata.put("orderedStepIds", validation.orderedSteps().stream()
            .map(InterpretationPlan.Step::id)
            .toList());
        return metadata;
    }

    private List<String> validateRuntimePlanRules(InterpretationPlan plan, PlannerValidationContext context) {
        if (context == null || plan == null) {
            return List.of();
        }
        List<String> issues = new ArrayList<>();
        List<String> mandatoryTools = normalizeList(context.mandatoryTools());
        if (context.requireToolBeforeFinal() && mandatoryTools.isEmpty()) {
            issues.add("Runtime policy requires a tool before final answer, but no mandatory tool was provided.");
        }
        Map<Integer, InterpretationPlan.Step> stepsById = new LinkedHashMap<>();
        Map<String, List<Integer>> toolStepIds = new LinkedHashMap<>();
        InterpretationPlan.Step finalStep = null;
        for (InterpretationPlan.Step step : plan.steps()) {
            if (step == null || step.id() == null) {
                continue;
            }
            stepsById.put(step.id(), step);
            if (step.finalAnswerAction()) {
                finalStep = step;
            }
            if (step.mcpToolAction() && step.toolName() != null && !step.toolName().isBlank()) {
                toolStepIds.computeIfAbsent(step.toolName(), ignored -> new ArrayList<>()).add(step.id());
            }
        }
        Integer previousMandatoryStepId = null;
        boolean authoritativeDagConfigured = !authoritativeWorkflowNodes(context.authoritativeWorkflowDag()).isEmpty();
        for (String mandatoryTool : mandatoryTools) {
            Integer mandatoryStepId = firstToolStepId(toolStepIds, mandatoryTool);
            if (mandatoryStepId == null) {
                issues.add("Mandatory tool is missing from InterpretationPlan: " + mandatoryTool);
                continue;
            }
            if (!authoritativeDagConfigured
                && previousMandatoryStepId != null && mandatoryStepId <= previousMandatoryStepId) {
                issues.add("Mandatory tools must appear in configured order: " + mandatoryTool);
            }
            if (!authoritativeDagConfigured && previousMandatoryStepId != null
                && !dependsOnStep(mandatoryStepId, previousMandatoryStepId, stepsById, new LinkedHashSet<>())) {
                issues.add("Mandatory tool must depend on previous configured workflow step: " + mandatoryTool);
            }
            if (finalStep == null || !dependsOnStep(finalStep.id(), mandatoryStepId, stepsById, new LinkedHashSet<>())) {
                issues.add("final_answer must depend on mandatory tool before answering: " + mandatoryTool);
            }
            previousMandatoryStepId = mandatoryStepId;
        }
        if (authoritativeDagConfigured) {
            validateAuthoritativeWorkflowDependencies(
                context.authoritativeWorkflowDag(), stepsById, toolStepIds, issues);
        }
        if (context.requireDocumentWebVerification()) {
            Integer documentStepId = firstToolStepId(toolStepIds, context.documentSearchTool());
            Integer webStepId = firstToolStepId(toolStepIds, context.verificationWebSearchTool());
            boolean documentRequiredInPlan = containsTool(mandatoryTools, context.documentSearchTool());
            boolean webRequiredInPlan = containsTool(mandatoryTools, context.verificationWebSearchTool());
            if (documentRequiredInPlan && documentStepId == null) {
                issues.add("Document-web verification requires document tool in plan: " + context.documentSearchTool());
            }
            if (webRequiredInPlan && webStepId == null) {
                issues.add("Document-web verification requires web verification tool in plan: " + context.verificationWebSearchTool());
            }
            if (documentStepId != null && webStepId != null && webStepId <= documentStepId) {
                issues.add("Document-web verification must plan document search before web verification.");
            }
            if (documentRequiredInPlan && finalStep != null && documentStepId != null
                && !dependsOnStep(finalStep.id(), documentStepId, stepsById, new LinkedHashSet<>())) {
                issues.add("final_answer must depend on document verification evidence.");
            }
            if (webRequiredInPlan && finalStep != null && webStepId != null
                && !dependsOnStep(finalStep.id(), webStepId, stepsById, new LinkedHashSet<>())) {
                issues.add("final_answer must depend on web verification evidence.");
            }
        }
        validateAssetDiscoveryIsNotGuessed(plan, context, toolStepIds, issues);
        validateWebSearchCrawlerSplit(plan, context, stepsById, toolStepIds, finalStep, issues);
        return issues;
    }

    private void validateAuthoritativeWorkflowDependencies(Object rawDag,
                                                            Map<Integer, InterpretationPlan.Step> stepsById,
                                                            Map<String, List<Integer>> toolStepIds,
                                                            List<String> issues) {
        for (Map<String, Object> node : authoritativeWorkflowNodes(rawDag)) {
            String tool = stringValue(firstObject(node, "tool", "toolName"));
            Integer targetStepId = firstToolStepId(toolStepIds, tool);
            if (targetStepId == null) {
                continue;
            }
            for (String dependencyTool : stringList(firstObject(
                node, "dependsOnTools", "depends_on_tools", "dependsOn", "depends_on"))) {
                Integer dependencyStepId = firstToolStepId(toolStepIds, dependencyTool);
                if (dependencyStepId == null) {
                    issues.add("Authoritative workflow dependency tool is missing from InterpretationPlan: "
                        + dependencyTool + " -> " + tool);
                    continue;
                }
                if (!dependsOnStep(targetStepId, dependencyStepId, stepsById, new LinkedHashSet<>())) {
                    issues.add("Mandatory tool must preserve authoritative workflow dependency: "
                        + dependencyTool + " -> " + tool);
                }
            }
        }
    }

    private List<Map<String, Object>> authoritativeWorkflowNodes(Object rawDag) {
        if (!(rawDag instanceof Collection<?> nodes)) {
            return List.of();
        }
        return nodes.stream()
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .map(this::asStringObjectMap)
            .filter(node -> firstObject(node, "tool", "toolName") != null)
            .toList();
    }

    private Map<String, Object> asStringObjectMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), value);
            }
        });
        return result;
    }

    private void validateAssetDiscoveryIsNotGuessed(InterpretationPlan plan,
                                                    PlannerValidationContext context,
                                                    Map<String, List<Integer>> toolStepIds,
                                                    List<String> issues) {
        if (plan == null || context == null || issues == null
            || matchingAvailableTool(context.availableTools(), "asset_discovery") == null) {
            return;
        }
        boolean hasAssetQueryStep = toolStepIds.keySet().stream()
            .anyMatch(tool -> workflowRole(tool) == ToolWorkflowRole.ASSET_DISCOVERY);
        if (!hasAssetQueryStep && contextClaimsGuessedAssetRouting(plan.context())) {
            issues.add("Asset routing context must come from typed asset discovery, user-provided executionContext, or observations; plan context must not assume assetName/env/datasource registration.");
            return;
        }
        for (InterpretationPlan.Step step : plan.steps()) {
            if (step == null || !"reasoning".equals(step.actionType())) {
                continue;
            }
            String text = normalize(step.input() == null ? "" : step.input().toString());
            boolean mentionsAssetQueryFailure = (text.contains("asset_query") || text.contains("asset_discovery"))
                && (text.contains("reject") || text.contains("rejected") || text.contains("refuse")
                    || text.contains("denied") || text.contains("confirmation") || text.contains("failed")
                    || text.contains("失败") || text.contains("拒绝") || text.contains("确认"));
            boolean guessesTargetContext = text.contains("assume") || text.contains("default")
                || text.contains("env") || text.contains("service") || text.contains("cluster") || text.contains("target")
                || text.contains("假设") || text.contains("默认");
            if (mentionsAssetQueryFailure && guessesTargetContext) {
                issues.add("Do not use a reasoning step to replace typed asset discovery or guess env/service after discovery failure; call the typed asset discovery tool or ask the user for logical executionContext.");
                return;
            }
            if (!hasAssetQueryStep && guessesTargetContext && text.contains("service") && text.contains("env")) {
                issues.add("Asset routing context must come from typed asset discovery, user-provided executionContext, or observations; reasoning steps must not invent env/service defaults.");
                return;
            }
        }
    }

    private boolean contextClaimsGuessedAssetRouting(InterpretationPlan.Context context) {
        if (context == null) {
            return false;
        }
        String text = normalize(String.join(" ",
            safeTextList(context.keyFacts()),
            safeTextList(context.assumptions()),
            safeTextList(context.constraints())
        ));
        if (text.isBlank()) {
            return false;
        }
        boolean assetRouting = containsAny(text,
            "asset", "assetname", "datasource", "data source", "env", "environment", "service", "cluster",
            "资产", "数据源", "环境", "服务", "集群"
        );
        boolean guessed = containsAny(text,
            "assume", "assumption", "default", "registered", "known", "already known",
            "假设", "默认", "已注册", "已知", "当前工具链不包含", "工具缺失", "不可用", "直接通过"
        );
        return assetRouting && guessed;
    }

    private String safeTextList(List<String> values) {
        return values == null ? "" : String.join(" ", values);
    }

    private void validateWebSearchCrawlerSplit(InterpretationPlan plan,
                                               PlannerValidationContext context,
                                               Map<Integer, InterpretationPlan.Step> stepsById,
                                               Map<String, List<Integer>> toolStepIds,
                                               InterpretationPlan.Step finalStep,
                                               List<String> issues) {
        if (plan == null || context == null || issues == null) {
            return;
        }
        String crawlerTool = preferredCrawlerTool(context.availableTools());
        if (crawlerTool == null) {
            return;
        }
        List<Integer> webDiscoverySteps = new ArrayList<>();
        List<Integer> crawlerSteps = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> entry : toolStepIds.entrySet()) {
            if (isWebDiscoveryTool(entry.getKey())) {
                webDiscoverySteps.addAll(entry.getValue());
            } else if (sameToolName(entry.getKey(), crawlerTool) || isCrawlerTool(entry.getKey())) {
                crawlerSteps.addAll(entry.getValue());
            }
        }
        if (webDiscoverySteps.isEmpty()) {
            return;
        }
        if (crawlerSteps.isEmpty()) {
            issues.add("web discovery must be followed by a crawler/content tool before final_answer: " + crawlerTool);
            return;
        }
        for (Integer webStepId : webDiscoverySteps) {
            boolean hasCrawlerAfterDiscovery = crawlerSteps.stream()
                .anyMatch(crawlerStepId -> crawlerStepId > webStepId
                    && dependsOnStep(crawlerStepId, webStepId, stepsById, new LinkedHashSet<>()));
            if (!hasCrawlerAfterDiscovery) {
                issues.add("crawler/content step must depend on each web discovery step before analysis.");
            }
        }
        if (finalStep != null) {
            boolean finalDependsOnCrawler = crawlerSteps.stream()
                .anyMatch(crawlerStepId -> dependsOnStep(finalStep.id(), crawlerStepId, stepsById, new LinkedHashSet<>()));
            if (!finalDependsOnCrawler) {
                issues.add("final_answer must depend on crawler/content evidence, not only on web discovery snippets.");
            }
        }
    }

    private String preferredWebSearchTool(List<String> availableTools) {
        List<String> tools = normalizeList(availableTools);
        return tools.stream()
            .filter(this::isWebSearchTool)
            .findFirst()
            .orElse(null);
    }

    private String preferredCrawlerTool(List<String> availableTools) {
        List<String> tools = normalizeList(availableTools);
        String crawlUrl = tools.stream()
            .filter(tool -> "crawl_url".equals(toolSemanticKey(tool)) || toolSemanticKey(tool).endsWith("_crawl_url"))
            .findFirst()
            .orElse(null);
        if (crawlUrl != null) {
            return crawlUrl;
        }
        return tools.stream()
            .filter(this::isCrawlerTool)
            .findFirst()
            .orElse(null);
    }

    private boolean isWebSearchTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return semantic.equals("web_search") || semantic.endsWith("_web_search") || semantic.contains("web_search");
    }

    private boolean isWebDiscoveryTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return isWebSearchTool(toolName)
            || semantic.equals("web_page_analyze")
            || semantic.contains("web_page_analyze")
            || semantic.equals("site_intelligence_resolver")
            || semantic.contains("site_intelligence")
            || semantic.equals("finance_site_search")
            || semantic.contains("finance_site_search")
            || semantic.equals("generic_web_site_search")
            || semantic.contains("generic_web_site_search")
            || semantic.equals("web_site_search")
            || (semantic.contains("site_search") && !semantic.contains("search_and_extract"));
    }

    private boolean isCrawlerTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return !isWebDiscoveryTool(toolName)
            && (semantic.equals("crawl_url")
            || semantic.contains("crawl")
            || semantic.contains("crawler")
            || semantic.contains("fetch_page")
            || semantic.contains("page_content")
            || semantic.contains("download")
            || semantic.contains("extract"));
    }

    private boolean containsTool(List<String> tools, String toolName) {
        if (toolName == null || toolName.isBlank() || tools == null || tools.isEmpty()) {
            return false;
        }
        return tools.stream().anyMatch(tool -> sameToolName(tool, toolName));
    }

    private Integer firstToolStepId(Map<String, List<Integer>> toolStepIds, String toolName) {
        if (toolName == null || toolName.isBlank() || toolStepIds == null || toolStepIds.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, List<Integer>> entry : toolStepIds.entrySet()) {
            if (sameToolName(entry.getKey(), toolName) && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private boolean dependsOnStep(Integer fromStepId,
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
            if (dependsOnStep(dependency, requiredDependencyId, stepsById, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean requiresStrictInterpretationPlan(PlannerValidationContext context) {
        return context != null
            && (context.requireToolBeforeFinal()
            || context.requireDocumentWebVerification()
            || !normalizeList(context.mandatoryTools()).isEmpty());
    }

    private boolean plannerPlanInvalid(AgentDecision decision) {
        return decision != null
            && "invalid_interpretation_plan".equals(decision.reason())
            && decision.executionPlan() != null
            && "interpretation_plan".equals(decision.executionPlan().get("plannerProtocol"));
    }

    private boolean shouldRepairPlan(AgentDecision decision, PlannerValidationContext context) {
        return decision != null
            && ("non_json_response".equals(decision.reason())
            || plannerPlanInvalid(decision) || (requiresStrictInterpretationPlan(context)
            && "legacy_action_not_allowed".equals(decision.reason())));
    }

    private boolean shouldDeferInvalidPlanToAuthoritativeRuntime(AgentDecision decision,
                                                                  PlannerValidationContext context) {
        return plannerPlanInvalid(decision)
            && context != null
            && !normalizeList(context.mandatoryTools()).isEmpty()
            && !authoritativeWorkflowNodes(context.authoritativeWorkflowDag()).isEmpty();
    }

    private void logPlannerRawOutput(String runId, int attempt, int maxAttempts, String raw) {
        log.info("agentPlannerRawOutput phase=planner runId={} attempt={}/{} chars={} content=\n{}",
            runId,
            attempt,
            maxAttempts,
            raw == null ? 0 : raw.length(),
            ModelProtocolJson.prettyJsonForLog(raw));
    }

    private void logPlannerDecision(String runId, int attempt, int maxAttempts, AgentDecision decision) {
        if (decision == null) {
            log.warn("agentPlannerDecision phase=planner_parse runId={} attempt={}/{} status=null_decision",
                runId, attempt, maxAttempts);
            return;
        }
        Map<String, Object> executionPlan = decision.executionPlan() == null ? Map.of() : decision.executionPlan();
        Object protocol = executionPlan.get("plannerProtocol");
        Object valid = executionPlan.get("interpretationPlanValid");
        Object executable = executionPlan.get("interpretationPlanExecutable");
        Object runtimeIssues = executionPlan.get("interpretationPlanRuntimeIssues");
        log.info(
            "agentPlannerDecision phase=planner_parse runId={} attempt={}/{} action={} reason={} protocol={} planPresent={} valid={} executable={} sufficient={} toolName={} runtimeIssues={} answerPreview={}",
            runId,
            attempt,
            maxAttempts,
            decision.action(),
            decision.reason(),
            protocol,
            decision.interpretationPlan() != null,
            valid,
            executable,
            decision.sufficient(),
            decision.toolName(),
            runtimeIssues,
            abbreviate(decision.answer(), 500)
        );
        if (decision.interpretationPlan() != null) {
            log.info("agentPlannerInterpretationPlan phase=planner_parse runId={} attempt={}/{} planJson=\n{}",
                runId,
                attempt,
                maxAttempts,
                prettyJson(decision.interpretationPlan()));
            log.info("agentPlannerInterpretationPlanValidation phase=planner_parse runId={} attempt={}/{} validationMetadata=\n{}",
                runId,
                attempt,
                maxAttempts,
                prettyJson(executionPlan));
        } else {
            log.warn("agentPlannerNoInterpretationPlan phase=planner_parse runId={} attempt={}/{} action={} reason={} rawAnswerPreview={}",
                runId,
                attempt,
                maxAttempts,
                decision.action(),
                decision.reason(),
                abbreviate(decision.answer(), 1000));
        }
    }

    private String prettyJson(Object value) {
        return ModelProtocolJson.pretty(value);
    }

    private String abbreviate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (maxChars <= 0 || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "...";
    }

    private AgentDecision invalidPlannerDecision(String raw,
                                                 String reason,
                                                 String issue,
                                                 PlannerValidationContext validationContext) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("plannerProtocol", "interpretation_plan");
        metadata.put("interpretationPlanValid", false);
        metadata.put("interpretationPlanSchemaValid", false);
        metadata.put("interpretationPlanRuntimeRulesValid", false);
        metadata.put("interpretationPlanExecutable", false);
        metadata.put("interpretationPlanRuntimeIssues", issue == null || issue.isBlank() ? List.of() : List.of(issue));
        String recoveredAnswer = recoverFinalAnswerCandidate(raw);
        if ((recoveredAnswer == null || recoveredAnswer.isBlank())
            && "non_json_response".equals(reason)) {
            recoveredAnswer = recoverPresentableNonJsonAnswer(raw);
        }
        if (recoveredAnswer != null && !recoveredAnswer.isBlank()) {
            boolean requiredEvidence = validationContext != null
                && (validationContext.requireToolBeforeFinal()
                || !normalizeList(validationContext.mandatoryTools()).isEmpty());
            metadata.put("plannerCandidateAnswerPreserved", true);
            metadata.put("protectedCandidateAnswer", true);
            metadata.put("answerOrigin", "planner_generated_recovered");
            metadata.put("generationType", "generated_artifact");
            metadata.put("planningStatus", "PROTOCOL_INVALID");
            metadata.put("requiredEvidence", requiredEvidence);
            metadata.put("taskBoundary", Map.of(
                "userIntent", validationContext == null
                    ? "" : firstNonBlank(validationContext.query(), ""),
                "requiredEvidence", requiredEvidence,
                "mandatoryTools", validationContext == null
                    ? List.of() : normalizeList(validationContext.mandatoryTools()),
                "reviewerMayChangeTaskType", false
            ));
        }
        return new AgentDecision(
            FINAL,
            null,
            Map.of(),
            recoveredAnswer == null || recoveredAnswer.isBlank() ? raw : recoveredAnswer,
            reason,
            metadata,
            false
        );
    }

    /**
     * Keeps a generated user artifact independent from the executable planning channel.
     * No recovered step is ever executed; only final_answer.input.answer is retained.
     */
    private String recoverFinalAnswerCandidate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = CANDIDATE_ANSWER_PATTERN.matcher(raw);
        boolean matched = matcher.find();
        if (!matched || matcher.group(1) == null || matcher.group(1).isBlank()) {
            matcher = FINAL_ANSWER_STEP_PATTERN.matcher(raw);
            matched = matcher.find();
        }
        if (!matched) {
            return null;
        }
        String encoded = matcher.group(1);
        try {
            return objectMapper.readValue("\"" + encoded + "\"", String.class).trim();
        } catch (Exception ignored) {
            return encoded
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\r", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .trim();
        }
    }

    private String buildPlannerRepairPrompt(String originalPrompt,
                                            String previousOutput,
                                            AgentDecision invalidDecision,
                                            int nextAttempt,
                                            int maxAttempts) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(originalPrompt).append("\n\n");
        prompt.append("Previous planner output was rejected by runtime validation.\n");
        prompt.append("Repair attempt: ").append(nextAttempt).append('/').append(maxAttempts).append("\n");
        prompt.append("Validation issues:\n");
        Object runtimeIssues = invalidDecision == null || invalidDecision.executionPlan() == null
            ? null
            : invalidDecision.executionPlan().get("interpretationPlanRuntimeIssues");
        Object schemaIssues = invalidDecision == null || invalidDecision.executionPlan() == null
            ? null
            : invalidDecision.executionPlan().get("interpretationPlanIssues");
        boolean issueWritten = false;
        if (schemaIssues instanceof List<?> issues && !issues.isEmpty()) {
            for (Object issue : issues) {
                prompt.append("- ").append(issue).append("\n");
                issueWritten = true;
            }
        }
        if (runtimeIssues instanceof List<?> issues && !issues.isEmpty()) {
            for (Object issue : issues) {
                prompt.append("- ").append(issue).append("\n");
                issueWritten = true;
            }
        }
        if (!issueWritten) {
            prompt.append("- ").append(invalidDecision == null ? "Invalid planner output" : invalidDecision.reason()).append("\n");
        }
        prompt.append("Rejected output:\n").append(previousOutput == null ? "" : previousOutput).append("\n\n");
        prompt.append("Regenerate the entire response as strict InterpretationPlan JSON only. ");
        prompt.append("Do not omit any mandatory MCP tool and do not return legacy action JSON. ");
        prompt.append("Keep tool_priority and accuracy_vs_speed values within 0.0 to 1.0.");
        return prompt.toString();
    }

    private ZoneId runtimeZoneId(Map<String, Object> runtimeAttributes) {
        Object configured = null;
        if (runtimeAttributes != null) {
            for (String key : List.of("timezone", "timeZone", "zoneId")) {
                Object candidate = runtimeAttributes.get(key);
                if (candidate != null && !String.valueOf(candidate).isBlank()) {
                    configured = candidate;
                    break;
                }
            }
        }
        if (configured != null) {
            try {
                return ZoneId.of(String.valueOf(configured).trim());
            } catch (Exception ignored) {
                // Invalid model/request values cannot replace the server's Runtime timezone.
            }
        }
        return ZoneId.systemDefault();
    }

    private boolean shouldOptimizeFromExperience(PlanCandidate candidate,
                                                 PlannerValidationContext validationContext) {
        if (candidate == null || validationContext == null || validationContext.experiencePrior() == null) {
            return false;
        }
        Map<String, Object> prior = validationContext.experiencePrior();
        if (prior.isEmpty() || longValue(prior.get("failedCount")) <= 0) {
            return false;
        }
        Object scoreValue = candidate.deterministicScoreDetails().get("experienceFit");
        int score = scoreValue instanceof Number number ? number.intValue() : 0;
        return score < 6;
    }

    private String buildExperienceOptimizationPrompt(String originalPrompt,
                                                     String previousOutput,
                                                     PlanCandidate candidate,
                                                     int nextAttempt,
                                                     int maxAttempts) {
        StringBuilder prompt = new StringBuilder(originalPrompt).append("\n\n");
        prompt.append("The previous plan is runtime-valid, but historical feedback shows a repeated execution weakness.\n");
        prompt.append("Experience optimization attempt: ").append(nextAttempt).append('/').append(maxAttempts).append("\n");
        prompt.append("Experience score details: ").append(candidate.deterministicScoreDetails().get("experience")).append("\n");
        prompt.append("Previous valid plan:\n").append(previousOutput == null ? "" : previousOutput).append("\n\n");
        prompt.append("Return a complete strict InterpretationPlan JSON that improves only explicit bindings, edge contracts, validation, bounded rewrite, timeout or fallback policy. ");
        prompt.append("The MCP tool names, required flags, dependency order and user-bound workflow must remain exactly unchanged. ");
        prompt.append("Do not add, replace, remove, skip or reorder any MCP tool. ");
        prompt.append("If the previous plan already contains the safest applicable policy, return it unchanged.");
        return prompt.toString();
    }

    private boolean sameExperienceWorkflowContract(AgentDecision baseline, AgentDecision candidate) {
        InterpretationPlan baselinePlan = baseline == null ? null : baseline.interpretationPlan();
        InterpretationPlan candidatePlan = candidate == null ? null : candidate.interpretationPlan();
        if (baselinePlan == null || candidatePlan == null) {
            return baselinePlan == candidatePlan;
        }
        List<String> baselineSteps = baselinePlan.steps().stream()
            .filter(InterpretationPlan.Step::mcpToolAction)
            .map(step -> canonicalToolName(step.toolName()) + "|" + normalizeIntegerList(step.dependsOn()))
            .toList();
        List<String> candidateSteps = candidatePlan.steps().stream()
            .filter(InterpretationPlan.Step::mcpToolAction)
            .map(step -> canonicalToolName(step.toolName()) + "|" + normalizeIntegerList(step.dependsOn()))
            .toList();
        return baselineSteps.equals(candidateSteps);
    }

    private List<Integer> normalizeIntegerList(List<Integer> values) {
        return values == null ? List.of() : values.stream().filter(java.util.Objects::nonNull).toList();
    }

    private PlanRewriteContext planRewriteContext(List<PlanCandidate> candidates) {
        List<PlanCandidate> values = candidates == null ? List.of() : List.copyOf(candidates);
        PlanCandidate last = values.isEmpty() ? null : values.get(values.size() - 1);
        String lastFailureReason = last == null || last.decision() == null ? "unknown" : last.decision().reason();
        String failurePattern = dominantFailurePattern(values);
        return new PlanRewriteContext(values.size(), values, lastFailureReason, failurePattern);
    }

    private PlanCandidate planCandidate(int attempt,
                                        String raw,
                                        AgentDecision decision,
                                        PlannerValidationContext validationContext) {
        String label = String.valueOf((char) ('A' + Math.max(0, attempt - 1)));
        String failurePattern = failurePattern(decision);
        String fingerprint = planFingerprint(decision);
        Map<String, Object> scoreDetails = deterministicPlanScoreDetails(decision, validationContext);
        int score = ((Number) scoreDetails.getOrDefault("total", 0)).intValue();
        return new PlanCandidate(attempt, label, raw, decision, failurePattern, fingerprint, score, scoreDetails);
    }

    private String dominantFailurePattern(List<PlanCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "UNKNOWN";
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PlanCandidate candidate : candidates) {
            counts.merge(candidate.failurePattern(), 1, Integer::sum);
        }
        String bestPattern = "UNKNOWN";
        int bestCount = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestPattern = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return bestPattern;
    }

    private String failurePattern(AgentDecision decision) {
        if (decision == null) {
            return "NON_JSON";
        }
        if ("legacy_action_not_allowed".equals(decision.reason())) {
            return "LEGACY_ACTION";
        }
        List<String> issues = plannerIssues(decision).stream()
            .map(issue -> issue.toLowerCase(Locale.ROOT))
            .toList();
        if (issues.stream().anyMatch(issue -> issue.contains("missing") || issue.contains("not found")
            || issue.contains("available tool") || issue.contains("unavailable") || issue.contains("unknown tool"))) {
            return "TOOL_MISSING";
        }
        if (issues.stream().anyMatch(issue -> issue.contains("depend") || issue.contains("step")
            || issue.contains("cycle") || issue.contains("final_answer"))) {
            return "DAG_INVALID";
        }
        Object schemaValid = decision.executionPlan() == null ? null : decision.executionPlan().get("interpretationPlanSchemaValid");
        if (Boolean.FALSE.equals(schemaValid)) {
            return "SCHEMA_INVALID";
        }
        if (!issues.isEmpty()) {
            return "RUNTIME_POLICY";
        }
        return "UNKNOWN";
    }

    private String planFingerprint(AgentDecision decision) {
        InterpretationPlan plan = decision == null ? null : decision.interpretationPlan();
        if (plan == null || plan.steps().isEmpty()) {
            return "no-plan";
        }
        StringBuilder canonical = new StringBuilder();
        for (InterpretationPlan.Step step : plan.steps()) {
            canonical.append(step.id()).append('|')
                .append(step.actionType()).append('|')
                .append(step.toolName()).append('|')
                .append(step.dependsOn()).append(';');
        }
        return Integer.toHexString(canonical.toString().hashCode());
    }

    private int deterministicPlanScore(AgentDecision decision, PlannerValidationContext validationContext) {
        Map<String, Object> details = deterministicPlanScoreDetails(decision, validationContext);
        return ((Number) details.getOrDefault("total", 0)).intValue();
    }

    private Map<String, Object> deterministicPlanScoreDetails(AgentDecision decision,
                                                              PlannerValidationContext validationContext) {
        InterpretationPlan plan = decision == null ? null : decision.interpretationPlan();
        if (plan == null) {
            return Map.of(
                "toolAvailability", 0,
                "dagValidity", 0,
                "executionCost", 0,
                "runtimePolicyFit", 0,
                "experienceFit", 0,
                "total", 0
            );
        }
        int toolAvailability = toolAvailabilityScore(plan, validationContext);
        int dagValidity = dagValidityScore(decision);
        int executionCost = executionCostScore(plan);
        int runtimePolicyFit = runtimePolicyFitScore(plan, decision, validationContext);
        Map<String, Object> coverage = coverageScoreDetails(plan, validationContext);
        int coverageScore = ((Number) coverage.getOrDefault("coverageScore", 0)).intValue();
        Map<String, Object> experience = experienceFitScoreDetails(plan, validationContext);
        int experienceFit = ((Number) experience.getOrDefault("experienceFit", 0)).intValue();
        int baseTotal = toolAvailability + dagValidity + executionCost + runtimePolicyFit + coverageScore;
        boolean experienceApplied = Boolean.TRUE.equals(experience.get("applied"));
        int total = experienceApplied
            ? Math.max(0, Math.min(100, (int) Math.round(baseTotal * 0.9D) + experienceFit))
            : Math.max(0, Math.min(100, baseTotal));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("toolAvailability", toolAvailability);
        details.put("dagValidity", dagValidity);
        details.put("executionCost", executionCost);
        details.put("runtimePolicyFit", runtimePolicyFit);
        details.put("coverageScore", coverageScore);
        details.put("coverage", coverage);
        details.put("experienceFit", experienceFit);
        details.put("experience", experience);
        details.put("baseTotal", Math.max(0, Math.min(100, baseTotal)));
        details.put("total", total);
        return details;
    }

    private Map<String, Object> experienceFitScoreDetails(InterpretationPlan plan,
                                                          PlannerValidationContext validationContext) {
        Map<String, Object> prior = validationContext == null || validationContext.experiencePrior() == null
            ? Map.of()
            : validationContext.experiencePrior();
        if (prior.isEmpty() || plan == null) {
            return Map.of("experienceFit", 0, "applied", false, "reasons", List.of());
        }
        List<String> reasons = new ArrayList<>();
        int score = 0;
        Double confidenceValue = doubleValue(prior.get("confidence"));
        double confidence = confidenceValue == null ? 0D : confidenceValue;
        if (confidence > 0D) {
            int confidenceScore = Math.max(1, (int) Math.round(Math.min(1D, confidence) * 2D));
            score += confidenceScore;
            reasons.add("confidence_supported:" + confidenceScore);
        }
        String candidateToolChain = plan.steps().stream()
            .filter(InterpretationPlan.Step::mcpToolAction)
            .map(InterpretationPlan.Step::toolName)
            .filter(tool -> tool != null && !tool.isBlank())
            .map(this::canonicalToolName)
            .collect(java.util.stream.Collectors.joining(">"));
        boolean preferredChainMatched = stringList(prior.get("preferredToolChains")).stream()
            .map(this::canonicalToolChain)
            .anyMatch(chain -> !chain.isBlank() && chain.equals(candidateToolChain));
        if (preferredChainMatched) {
            score += 2;
            reasons.add("successful_bound_workflow_matched");
        }
        long failedCount = longValue(prior.get("failedCount"));
        if (failedCount > 0) {
            InterpretationPlan.ExecutionPolicy policy = plan.executionPolicy();
            if (policy != null && policy.maxRewriteTimes() != null && policy.maxRewriteTimes() > 0) {
                score += 2;
                reasons.add("failure_history_has_bounded_rewrite");
            }
            if (policy != null && policy.fallbackMode() != null && !policy.fallbackMode().isBlank()) {
                score += 2;
                reasons.add("failure_history_has_fallback");
            }
        }
        if (Boolean.TRUE.equals(prior.get("bindingFailureObserved"))
            && plan.plan() != null
            && ((plan.plan().bindings() != null && !plan.plan().bindings().isEmpty())
                || (plan.plan().edgeContracts() != null && !plan.plan().edgeContracts().isEmpty()))) {
            score += 2;
            reasons.add("binding_failure_history_has_explicit_contract");
        }
        score = Math.min(10, score);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("experienceFit", score);
        details.put("applied", true);
        details.put("matchedExperienceIds", stringList(prior.get("matchedExperienceIds")));
        details.put("workflowMutationAllowed", false);
        details.put("candidateToolChain", candidateToolChain);
        details.put("reasons", reasons);
        return details;
    }

    private Map<String, Object> experiencePrior(Map<String, Object> runtimeAttributes) {
        if (runtimeAttributes == null || !(runtimeAttributes.get("experiencePrior") instanceof Map<?, ?> values)) {
            return Map.of();
        }
        Map<String, Object> prior = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && value != null) {
                prior.put(String.valueOf(key), value);
            }
        });
        return Map.copyOf(prior);
    }

    private String canonicalToolChain(String toolChain) {
        if (toolChain == null || toolChain.isBlank()) {
            return "";
        }
        return List.of(toolChain.split(">"))
            .stream()
            .map(this::canonicalToolName)
            .filter(value -> !value.isBlank())
            .collect(java.util.stream.Collectors.joining(">"));
    }

    private String canonicalToolName(String toolName) {
        if (toolName == null) {
            return "";
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT);
        int marker = normalized.lastIndexOf("_mcp_server_");
        return marker >= 0 ? normalized.substring(marker + "_mcp_server_".length()) : normalized;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private int toolAvailabilityScore(InterpretationPlan plan, PlannerValidationContext validationContext) {
        List<String> tools = plan.steps().stream()
            .filter(InterpretationPlan.Step::mcpToolAction)
            .map(InterpretationPlan.Step::toolName)
            .filter(tool -> tool != null && !tool.isBlank())
            .toList();
        if (tools.isEmpty()) {
            return 0;
        }
        long available = tools.stream()
            .filter(tool -> toolAvailable(tool, validationContext))
            .count();
        return (int) Math.round(30.0 * available / tools.size());
    }

    private boolean toolAvailable(String toolName, PlannerValidationContext validationContext) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        List<String> availableTools = validationContext == null ? List.of() : normalizeList(validationContext.availableTools());
        if (!availableTools.isEmpty()) {
            return availableTools.stream().anyMatch(tool -> sameToolName(tool, toolName));
        }
        return toolRegistry != null && toolRegistry.hasTool(toolName);
    }

    private int dagValidityScore(AgentDecision decision) {
        Map<String, Object> metadata = decision == null || decision.executionPlan() == null ? Map.of() : decision.executionPlan();
        int score = 0;
        if (Boolean.TRUE.equals(metadata.get("interpretationPlanSchemaValid"))) {
            score += 10;
        }
        if (Boolean.TRUE.equals(metadata.get("interpretationPlanRuntimeRulesValid"))) {
            score += 10;
        }
        if (Boolean.TRUE.equals(metadata.get("interpretationPlanExecutable"))) {
            score += 5;
        }
        return score;
    }

    private int executionCostScore(InterpretationPlan plan) {
        long toolSteps = plan.steps().stream().filter(InterpretationPlan.Step::mcpToolAction).count();
        if (toolSteps <= 0) {
            return 0;
        }
        return (int) Math.max(3, 15 - Math.max(0, toolSteps - 1) * 3);
    }

    private int runtimePolicyFitScore(InterpretationPlan plan,
                                      AgentDecision decision,
                                      PlannerValidationContext validationContext) {
        int score = 20;
        List<String> issues = plannerIssues(decision);
        score -= Math.min(12, issues.size() * 4);
        List<String> mandatoryTools = validationContext == null ? List.of() : normalizeList(validationContext.mandatoryTools());
        for (String mandatoryTool : mandatoryTools) {
            boolean present = plan.steps().stream()
                .anyMatch(step -> step.mcpToolAction() && sameToolName(step.toolName(), mandatoryTool));
            if (!present) {
                score -= 8;
            }
        }
        return Math.max(0, score);
    }

    private Map<String, Object> coverageScoreDetails(InterpretationPlan plan,
                                                     PlannerValidationContext validationContext) {
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        if (plan == null) {
            return Map.of(
                "coverageScore", 0,
                "matchedCapabilities", matched,
                "missingCapabilities", missing
            );
        }
        List<InterpretationPlan.Step> steps = plan.steps();
        Map<Integer, InterpretationPlan.Step> stepsById = stepsById(steps);
        InterpretationPlan.Step finalStep = finalStep(steps);
        List<InterpretationPlan.Step> toolSteps = steps.stream()
            .filter(step -> step != null && step.mcpToolAction())
            .toList();

        int mandatoryCoverage = mandatoryCoverageScore(toolSteps, validationContext, matched, missing);
        int evidenceDependency = evidenceDependencyScore(plan, finalStep, toolSteps, stepsById, validationContext, matched, missing);
        int workflowCoverage = workflowCoverageScore(finalStep, stepsById, toolSteps, validationContext, matched, missing);
        int stageCoverage = stageCoverageScore(steps, toolSteps, finalStep, matched, missing);
        int goalCoverage = goalCoverageScore(plan, validationContext, matched, missing);
        int total = Math.max(0, Math.min(25, mandatoryCoverage + evidenceDependency + workflowCoverage + stageCoverage + goalCoverage));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("coverageScore", total);
        details.put("mandatoryCoverage", mandatoryCoverage);
        details.put("evidenceDependency", evidenceDependency);
        details.put("workflowCoverage", workflowCoverage);
        details.put("stageCoverage", stageCoverage);
        details.put("goalCoverage", goalCoverage);
        details.put("matchedCapabilities", matched.stream().distinct().toList());
        details.put("missingCapabilities", missing.stream().distinct().toList());
        return details;
    }

    private int mandatoryCoverageScore(List<InterpretationPlan.Step> toolSteps,
                                       PlannerValidationContext validationContext,
                                       List<String> matched,
                                       List<String> missing) {
        List<String> mandatoryTools = validationContext == null ? List.of() : normalizeList(validationContext.mandatoryTools());
        if (mandatoryTools.isEmpty()) {
            matched.add("no_mandatory_tool_gap");
            return 5;
        }
        long present = mandatoryTools.stream()
            .filter(tool -> toolSteps.stream().anyMatch(step -> sameToolName(step.toolName(), tool)))
            .peek(tool -> matched.add("mandatory_tool:" + tool))
            .count();
        mandatoryTools.stream()
            .filter(tool -> toolSteps.stream().noneMatch(step -> sameToolName(step.toolName(), tool)))
            .forEach(tool -> missing.add("mandatory_tool:" + tool));
        return (int) Math.round(7.0 * present / mandatoryTools.size());
    }

    private int evidenceDependencyScore(InterpretationPlan plan,
                                        InterpretationPlan.Step finalStep,
                                        List<InterpretationPlan.Step> toolSteps,
                                        Map<Integer, InterpretationPlan.Step> stepsById,
                                        PlannerValidationContext validationContext,
                                        List<String> matched,
                                        List<String> missing) {
        boolean evidenceExpected = expectsToolEvidence(plan, validationContext);
        if (!evidenceExpected) {
            matched.add("direct_answer_allowed");
            return 5;
        }
        if (!toolSteps.isEmpty() && finalDependsOnAnyTool(finalStep, toolSteps, stepsById)) {
            matched.add("final_answer_depends_on_tool_evidence");
            return 5;
        }
        if (toolSteps.isEmpty()) {
            missing.add("tool_evidence_step");
        } else {
            missing.add("final_answer_tool_dependency");
        }
        return 0;
    }

    private int workflowCoverageScore(InterpretationPlan.Step finalStep,
                                      Map<Integer, InterpretationPlan.Step> stepsById,
                                      List<InterpretationPlan.Step> toolSteps,
                                      PlannerValidationContext validationContext,
                                      List<String> matched,
                                      List<String> missing) {
        if (validationContext != null && validationContext.requireDocumentWebVerification()) {
            boolean documentCovered = finalDependsOnTool(finalStep, validationContext.documentSearchTool(), stepsById, toolSteps);
            boolean webCovered = finalDependsOnTool(finalStep, validationContext.verificationWebSearchTool(), stepsById, toolSteps);
            if (documentCovered && webCovered) {
                matched.add("document_web_verification_chain");
                return 5;
            }
            if (!documentCovered) {
                missing.add("document_verification_chain");
            }
            if (!webCovered) {
                missing.add("web_verification_chain");
            }
            return documentCovered || webCovered ? 2 : 0;
        }

        int score = 0;
        if (expectsDocumentEvidence(validationContext)
            && finalDependsOnTool(finalStep, validationContext.documentSearchTool(), stepsById, toolSteps)) {
            matched.add("document_evidence_chain");
            score += 3;
        } else if (expectsDocumentEvidence(validationContext)) {
            missing.add("document_evidence_chain");
        }

        if (expectsWebEvidence(validationContext)) {
            boolean webCovered = toolSteps.stream().anyMatch(step -> isWebDiscoveryTool(step.toolName()))
                && finalDependsOnAnyTool(finalStep, toolSteps.stream().filter(step -> isWebDiscoveryTool(step.toolName())).toList(), stepsById);
            if (webCovered) {
                matched.add("web_evidence_chain");
                score += 2;
            } else {
                missing.add("web_evidence_chain");
            }
        }
        return Math.min(5, score);
    }

    private int stageCoverageScore(List<InterpretationPlan.Step> steps,
                                   List<InterpretationPlan.Step> toolSteps,
                                   InterpretationPlan.Step finalStep,
                                   List<String> matched,
                                   List<String> missing) {
        int score = 0;
        if (finalStep != null) {
            matched.add("final_answer_stage");
            score += 2;
        } else {
            missing.add("final_answer_stage");
        }
        if (!toolSteps.isEmpty()) {
            matched.add("tool_execution_stage");
            score += 2;
        }
        boolean hasIntermediateStage = steps.stream()
            .filter(step -> step != null && !step.mcpToolAction() && !step.finalAnswerAction())
            .anyMatch(step -> !normalize(step.actionType()).isBlank());
        if (hasIntermediateStage || toolSteps.size() > 1) {
            matched.add("multi_stage_plan");
            score += 1;
        }
        return Math.min(5, score);
    }

    private int goalCoverageScore(InterpretationPlan plan,
                                  PlannerValidationContext validationContext,
                                  List<String> matched,
                                  List<String> missing) {
        List<String> goalTerms = significantGoalTerms(validationContext == null ? null : validationContext.query());
        if (goalTerms.isEmpty()) {
            matched.add("no_goal_keyword_gap");
            return 3;
        }
        String planText = normalize(planText(plan));
        long covered = goalTerms.stream()
            .filter(term -> planText.contains(normalize(term)))
            .peek(term -> matched.add("goal_term:" + term))
            .count();
        goalTerms.stream()
            .filter(term -> !planText.contains(normalize(term)))
            .forEach(term -> missing.add("goal_term:" + term));
        return (int) Math.round(3.0 * covered / goalTerms.size());
    }

    private Map<Integer, InterpretationPlan.Step> stepsById(List<InterpretationPlan.Step> steps) {
        Map<Integer, InterpretationPlan.Step> values = new LinkedHashMap<>();
        for (InterpretationPlan.Step step : steps == null ? List.<InterpretationPlan.Step>of() : steps) {
            if (step != null && step.id() != null) {
                values.put(step.id(), step);
            }
        }
        return values;
    }

    private InterpretationPlan.Step finalStep(List<InterpretationPlan.Step> steps) {
        if (steps == null) {
            return null;
        }
        return steps.stream()
            .filter(step -> step != null && step.finalAnswerAction())
            .findFirst()
            .orElse(null);
    }

    private boolean finalDependsOnAnyTool(InterpretationPlan.Step finalStep,
                                          List<InterpretationPlan.Step> toolSteps,
                                          Map<Integer, InterpretationPlan.Step> stepsById) {
        if (finalStep == null || toolSteps == null || toolSteps.isEmpty()) {
            return false;
        }
        return toolSteps.stream()
            .anyMatch(step -> step != null && dependsOnStep(finalStep.id(), step.id(), stepsById, new LinkedHashSet<>()));
    }

    private boolean finalDependsOnTool(InterpretationPlan.Step finalStep,
                                       String toolName,
                                       Map<Integer, InterpretationPlan.Step> stepsById,
                                       List<InterpretationPlan.Step> toolSteps) {
        if (finalStep == null || toolName == null || toolName.isBlank()) {
            return false;
        }
        return toolSteps.stream()
            .filter(step -> step != null && sameToolName(step.toolName(), toolName))
            .anyMatch(step -> dependsOnStep(finalStep.id(), step.id(), stepsById, new LinkedHashSet<>()));
    }

    private boolean expectsToolEvidence(InterpretationPlan plan, PlannerValidationContext validationContext) {
        return validationContext != null
            && (validationContext.requireToolBeforeFinal()
            || validationContext.requireDocumentWebVerification()
            || !normalizeList(validationContext.mandatoryTools()).isEmpty()
            || expectsDocumentEvidence(validationContext)
            || expectsWebEvidence(validationContext))
            || plan != null && plan.steps().stream().anyMatch(InterpretationPlan.Step::mcpToolAction);
    }

    private boolean expectsDocumentEvidence(PlannerValidationContext validationContext) {
        String text = requestText(validationContext);
        return containsAny(text, "document", "doc", "file", "report", "paper", "knowledge", "internal",
            "文档", "文件", "报告", "论文", "知识库", "内部", "资料");
    }

    private boolean expectsWebEvidence(PlannerValidationContext validationContext) {
        String text = requestText(validationContext);
        return containsAny(text, "web", "website", "site", "online", "internet", "current", "latest", "today", "recent",
            "网页", "网站", "联网", "互联网", "当前", "最新", "今天", "近期");
    }

    private String requestText(PlannerValidationContext validationContext) {
        return validationContext == null || validationContext.query() == null
            ? ""
            : validationContext.query().toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... tokens) {
        if (text == null || text.isBlank() || tokens == null) {
            return false;
        }
        for (String token : tokens) {
            if (token != null && !token.isBlank() && text.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String boundedUserQuery(String query) {
        if (query == null || query.length() <= MAX_USER_QUERY_PROMPT_CHARS) {
            return query == null ? "" : query;
        }
        int tailLength = MAX_USER_QUERY_PROMPT_CHARS / 4;
        int headLength = MAX_USER_QUERY_PROMPT_CHARS - tailLength;
        int omitted = query.length() - headLength - tailLength;
        return query.substring(0, headLength)
            + "\n...[user query truncated " + omitted + " chars; preserving tail]...\n"
            + query.substring(query.length() - tailLength);
    }

    private List<String> significantGoalTerms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        String normalized = query.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{IsHan}a-z0-9_]+", " ")
            .trim();
        for (String term : normalized.split("\\s+")) {
            if (term.length() >= 4 && !isStopword(term)) {
                terms.add(term);
            }
        }
        for (String keyword : List.of("文档", "文件", "报告", "论文", "知识库", "内部", "搜索", "检索", "联网", "最新", "今天", "分析", "汇总", "验证")) {
            if (query.contains(keyword)) {
                terms.add(keyword);
            }
        }
        return terms.stream().limit(6).toList();
    }

    private boolean isStopword(String term) {
        return Set.of(
            "what", "with", "from", "that", "this", "into", "about", "please", "using",
            "the", "and", "for", "are", "how"
        ).contains(term);
    }

    private String planText(InterpretationPlan plan) {
        if (plan == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        if (plan.intent() != null) {
            builder.append(plan.intent().type()).append(' ')
                .append(plan.intent().goal()).append(' ')
                .append(plan.intent().riskLevel()).append(' ');
        }
        if (plan.context() != null) {
            builder.append(plan.context().keyFacts()).append(' ')
                .append(plan.context().assumptions()).append(' ')
                .append(plan.context().missingInfo()).append(' ')
                .append(plan.context().constraints()).append(' ');
        }
        for (InterpretationPlan.Step step : plan.steps()) {
            if (step == null) {
                continue;
            }
            builder.append(step.actionType()).append(' ')
                .append(step.toolName()).append(' ')
                .append(step.input()).append(' ');
        }
        return builder.toString();
    }

    private AgentDecision attributeAndSelectBestPlan(PlanRewriteContext rewriteContext,
                                                     PlannerValidationContext validationContext,
                                                     String runId) {
        if (rewriteContext == null || rewriteContext.candidates().isEmpty()) {
            return null;
        }
        PlanCandidate selected = selectBestCandidate(rewriteContext.candidates());
        if (selected == null || selected.decision() == null) {
            return null;
        }
        GuardRepairResult repair = deterministicGuardRepair(selected.decision(), validationContext);
        AgentDecision selectedDecision = repair.decision();
        String reason = attributionReason(selected, repair);
        AgentDecision attributed = withAttributionMetadata(
            selectedDecision,
            selected,
            rewriteContext,
            reason,
            repair.applied(),
            repair.notes()
        );
        log.info("agentPlannerAttribution phase=planner_attribution runId={} source=deterministic selected={} score={} repairApplied={} candidateCount={}",
            runId == null ? "" : runId,
            selected.label(),
            selected.deterministicScore(),
            repair.applied(),
            rewriteContext.candidates().size());
        logPlannerDecision(runId == null ? "" : runId, rewriteContext.rewriteCount(), rewriteContext.rewriteCount(), attributed);
        return attributed;
    }

    private PlanCandidate selectBestCandidate(List<PlanCandidate> candidates) {
        PlanCandidate best = null;
        for (PlanCandidate candidate : candidates == null ? List.<PlanCandidate>of() : candidates) {
            if (candidate == null || candidate.decision() == null) {
                continue;
            }
            if (best == null || betterCandidate(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private boolean betterCandidate(PlanCandidate candidate, PlanCandidate currentBest) {
        if (candidate.deterministicScore() != currentBest.deterministicScore()) {
            return candidate.deterministicScore() > currentBest.deterministicScore();
        }
        boolean candidateValid = !plannerPlanInvalid(candidate.decision());
        boolean bestValid = !plannerPlanInvalid(currentBest.decision());
        if (candidateValid != bestValid) {
            return candidateValid;
        }
        int candidateIssueCount = plannerIssues(candidate.decision()).size();
        int bestIssueCount = plannerIssues(currentBest.decision()).size();
        if (candidateIssueCount != bestIssueCount) {
            return candidateIssueCount < bestIssueCount;
        }
        return candidate.attempt() > currentBest.attempt();
    }

    private List<String> plannerIssues(AgentDecision decision) {
        if (decision == null || decision.executionPlan() == null || decision.executionPlan().isEmpty()) {
            return List.of();
        }
        List<String> issues = new ArrayList<>();
        Object runtimeIssues = decision.executionPlan().get("interpretationPlanRuntimeIssues");
        if (runtimeIssues instanceof List<?> list) {
            list.stream().map(String::valueOf).forEach(issues::add);
        }
        Object validationIssues = decision.executionPlan().get("interpretationPlanIssues");
        if (validationIssues instanceof List<?> list) {
            list.stream().map(String::valueOf).forEach(issues::add);
        }
        return issues.stream()
            .filter(issue -> issue != null && !issue.isBlank())
            .distinct()
            .toList();
    }

    private GuardRepairResult deterministicGuardRepair(AgentDecision decision,
                                                       PlannerValidationContext validationContext) {
        InterpretationPlan plan = decision == null ? null : decision.interpretationPlan();
        if (plan == null) {
            return new GuardRepairResult(decision, false, List.of());
        }
        List<String> unavailableTools = plan.steps().stream()
            .filter(InterpretationPlan.Step::mcpToolAction)
            .map(InterpretationPlan.Step::toolName)
            .filter(tool -> tool != null && !tool.isBlank())
            .filter(tool -> !toolAvailable(tool, validationContext))
            .distinct()
            .toList();
        if (unavailableTools.isEmpty()) {
            return new GuardRepairResult(decision, false, List.of());
        }
        List<String> replacements = candidateReplacementTools(validationContext);
        if (replacements.size() != 1) {
            return new GuardRepairResult(decision, false, List.of("Skipped repair because replacement tool was ambiguous."));
        }
        String replacement = replacements.get(0);
        if (!toolAvailable(replacement, validationContext)) {
            return new GuardRepairResult(decision, false, List.of("Skipped repair because replacement tool is unavailable."));
        }
        Map<String, String> toolReplacements = new LinkedHashMap<>();
        unavailableTools.forEach(tool -> toolReplacements.put(tool, replacement));
        InterpretationPlan repairedPlan = replaceUnavailableTools(plan, toolReplacements);
        AgentDecision repairedDecision = parseInterpretationPlanDecision(objectMapper.convertValue(repairedPlan, Map.class), validationContext);
        if (repairedDecision == null || plannerPlanInvalid(repairedDecision)) {
            return new GuardRepairResult(decision, false, List.of("Skipped repair because repaired plan did not pass validation."));
        }
        List<String> notes = unavailableTools.stream()
            .map(tool -> "Replaced unavailable tool " + tool + " with " + replacement + ".")
            .toList();
        return new GuardRepairResult(repairedDecision, true, notes);
    }

    private List<String> candidateReplacementTools(PlannerValidationContext validationContext) {
        List<String> mandatoryTools = validationContext == null ? List.of() : normalizeList(validationContext.mandatoryTools());
        List<String> availableTools = validationContext == null ? List.of() : normalizeList(validationContext.availableTools());
        List<String> mandatoryAvailable = mandatoryTools.stream()
            .filter(tool -> toolAvailable(tool, validationContext))
            .distinct()
            .toList();
        if (mandatoryAvailable.size() == 1) {
            return mandatoryAvailable;
        }
        List<String> registeredAvailable = availableTools.stream()
            .filter(tool -> toolAvailable(tool, validationContext))
            .distinct()
            .toList();
        if (registeredAvailable.size() == 1) {
            return registeredAvailable;
        }
        String documentTool = validationContext == null ? null : validationContext.documentSearchTool();
        if (documentTool != null && !documentTool.isBlank() && toolAvailable(documentTool, validationContext)) {
            return List.of(documentTool);
        }
        return registeredAvailable;
    }

    private InterpretationPlan replaceUnavailableTools(InterpretationPlan plan, Map<String, String> toolReplacements) {
        List<InterpretationPlan.Step> steps = plan.steps().stream()
            .map(step -> {
                if (step == null || !step.mcpToolAction()) {
                    return step;
                }
                String replacement = toolReplacements.get(step.toolName());
                if (replacement == null) {
                    return step;
                }
                return new InterpretationPlan.Step(
                    step.id(),
                    step.actionType(),
                    replacement,
                    step.input(),
                    step.dependsOn(),
                    step.outputContract(),
                    step.validation()
                );
            })
            .toList();
        InterpretationPlan.Plan originalPlan = plan.plan();
        InterpretationPlan.Plan repairedInnerPlan = new InterpretationPlan.Plan(
            steps,
            originalPlan == null ? List.of() : originalPlan.edgeContracts(),
            originalPlan == null ? List.of() : originalPlan.dependencyContracts(),
            originalPlan == null ? List.of() : originalPlan.bindings(),
            originalPlan == null ? null : originalPlan.stability(),
            originalPlan == null ? null : originalPlan.diagnosticProfile(),
            originalPlan == null ? List.of() : originalPlan.conditionalEdges(),
            originalPlan == null ? List.of() : originalPlan.branchGroups()
        );
        return new InterpretationPlan(
            plan.version(),
            plan.intent(),
            plan.context(),
            repairedInnerPlan,
            replacePolicyTools(plan.executionPolicy(), toolReplacements),
            plan.review()
        );
    }

    private InterpretationPlan.ExecutionPolicy replacePolicyTools(InterpretationPlan.ExecutionPolicy policy,
                                                                  Map<String, String> toolReplacements) {
        if (policy == null) {
            return null;
        }
        return new InterpretationPlan.ExecutionPolicy(
            policy.maxSteps(),
            policy.allowParallel(),
            replaceToolList(policy.allowTool(), toolReplacements),
            replaceToolList(policy.denyTool(), toolReplacements),
            policy.timeoutMs(),
            policy.maxRewriteTimes(),
            policy.fallbackMode(),
            replaceToolPriority(policy.toolPriority(), toolReplacements),
            policy.costBudget(),
            policy.latencyBudgetMs(),
            policy.accuracyVsSpeed()
        );
    }

    private List<String> replaceToolList(List<String> tools, Map<String, String> toolReplacements) {
        if (tools == null) {
            return List.of();
        }
        return tools.stream()
            .map(tool -> toolReplacements.getOrDefault(tool, tool))
            .filter(tool -> tool != null && !tool.isBlank())
            .distinct()
            .toList();
    }

    private Map<String, Double> replaceToolPriority(Map<String, Double> priorities,
                                                    Map<String, String> toolReplacements) {
        if (priorities == null || priorities.isEmpty()) {
            return priorities;
        }
        Map<String, Double> values = new LinkedHashMap<>();
        priorities.forEach((tool, priority) -> values.put(toolReplacements.getOrDefault(tool, tool), priority));
        return values;
    }

    private String attributionReason(PlanCandidate selected, GuardRepairResult repair) {
        StringBuilder reason = new StringBuilder();
        reason.append("Selected candidate ")
            .append(selected.label())
            .append(" by deterministic attribution score ")
            .append(selected.deterministicScore())
            .append("/100.");
        if (repair != null && repair.applied()) {
            reason.append(" Applied guard repair for verifiable unavailable-tool mapping.");
        }
        return reason.toString();
    }

    private AgentDecision withAttributionMetadata(AgentDecision decision,
                                                  PlanCandidate selected,
                                                  PlanRewriteContext rewriteContext,
                                                  String reason,
                                                  boolean repairApplied,
                                                  List<String> repairNotes) {
        if (decision == null) {
            return null;
        }
        Map<String, Object> executionPlan = new LinkedHashMap<>(decision.executionPlan() == null ? Map.of() : decision.executionPlan());
        executionPlan.put("plannerAttributionSelection", true);
        executionPlan.put("plannerAttributionSource", "deterministic_java");
        executionPlan.put("plannerAttributionContractVersion", "plan_attribution_v1");
        executionPlan.put("plannerGenerationLimit", MAX_PLAN_REPAIR_ATTEMPTS);
        executionPlan.put("plannerGenerationCount", rewriteContext == null ? 0 : rewriteContext.candidates().size());
        executionPlan.put("plannerAttributionCandidateCount", rewriteContext == null ? 0 : rewriteContext.candidates().size());
        executionPlan.put("plannerAttributionSelected", selected == null ? null : selected.label());
        executionPlan.put("plannerAttributionSelectedAttempt", selected == null ? null : selected.attempt());
        executionPlan.put("plannerAttributionReason", reason);
        executionPlan.put("plannerAttributionAnalysis", reason);
        executionPlan.put("plannerAttributionScores", plannerAttributionScores(rewriteContext));
        executionPlan.put("plannerAttributionCandidates", plannerAttributionCandidates(rewriteContext));
        executionPlan.put("plannerAttributionFailurePattern", rewriteContext == null ? "UNKNOWN" : rewriteContext.failurePattern());
        executionPlan.put("plannerAttributionCandidateFingerprints", rewriteContext == null ? List.of() : rewriteContext.candidates().stream()
            .map(candidate -> Map.of(
                "label", candidate.label(),
                "fingerprint", candidate.fingerprint(),
                "failurePattern", candidate.failurePattern(),
                "deterministicScore", candidate.deterministicScore()
            ))
            .toList());
        executionPlan.put("plannerAttributionRepairApplied", repairApplied);
        executionPlan.put("plannerAttributionRepairNotes", repairNotes == null ? List.of() : repairNotes);
        return new AgentDecision(
            decision.action(),
            decision.toolName(),
            decision.arguments(),
            decision.answer(),
            decision.reason(),
            executionPlan,
            decision.sufficient(),
            decision.interpretationPlan()
        );
    }

    private Map<String, Object> plannerAttributionScores(PlanRewriteContext rewriteContext) {
        Map<String, Object> scores = new LinkedHashMap<>();
        if (rewriteContext == null || rewriteContext.candidates() == null) {
            return scores;
        }
        for (PlanCandidate candidate : rewriteContext.candidates()) {
            scores.put(candidate.label(), candidate.deterministicScore());
        }
        return scores;
    }

    private List<Map<String, Object>> plannerAttributionCandidates(PlanRewriteContext rewriteContext) {
        if (rewriteContext == null || rewriteContext.candidates() == null) {
            return List.of();
        }
        return rewriteContext.candidates().stream()
            .map(candidate -> {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("label", candidate.label());
                record.put("attempt", candidate.attempt());
                record.put("failurePattern", candidate.failurePattern());
                record.put("fingerprint", candidate.fingerprint());
                record.put("deterministicScore", candidate.deterministicScore());
                record.put("scoreDetails", candidate.deterministicScoreDetails());
                record.put("issues", plannerIssues(candidate.decision()));
                record.put("valid", candidate.decision() != null && !plannerPlanInvalid(candidate.decision()));
                return record;
            })
            .toList();
    }

    private int plannerRepairAttempts(Map<String, Object> runtimeAttributes) {
        Object configured = runtimeAttributes == null ? null : runtimeAttributes.get("plannerMaxRepairAttempts");
        if (configured instanceof Number number) {
            return Math.max(1, Math.min(MAX_PLAN_REPAIR_ATTEMPTS, number.intValue()));
        }
        if (configured != null) {
            try {
                return Math.max(1, Math.min(MAX_PLAN_REPAIR_ATTEMPTS, Integer.parseInt(String.valueOf(configured))));
            } catch (NumberFormatException ignored) {
                return DEFAULT_PLAN_REPAIR_ATTEMPTS;
            }
        }
        return DEFAULT_PLAN_REPAIR_ATTEMPTS;
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }

    private String extractJson(String raw) {
        String text = raw.trim();
        if (text.startsWith("{")) {
            int lastBrace = text.lastIndexOf('}');
            return lastBrace > 0 ? text.substring(0, lastBrace + 1) : text;
        }
        int blockStart = text.indexOf("```");
        if (blockStart >= 0) {
            int firstBrace = text.indexOf('{', blockStart);
            int lastBrace = text.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                return text.substring(firstBrace, lastBrace + 1);
            }
        }
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1);
        }
        return text;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private boolean sameToolName(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        String left = first.trim();
        String right = second.trim();
        String leftAlias = normalizeKnownToolAlias(left);
        String rightAlias = normalizeKnownToolAlias(right);
        return left.equals(right)
            || left.equals(rightAlias)
            || leftAlias.equals(right)
            || leftAlias.equals(rightAlias)
            || toolSemanticKey(left).equals(toolSemanticKey(right));
    }

    private String normalizeKnownToolAlias(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return toolName;
        }
        String semantic = toolSemanticKey(toolName);
        if (semantic.contains("document") && semantic.contains("search")) {
            return DOCUMENT_SEARCH_TOOL;
        }
        if (semantic.equals("web_search") || semantic.endsWith("_web_search") || semantic.contains("web_search")) {
            return "web_search";
        }
        if (semantic.contains("search_and_extract")) {
            return "search_and_extract";
        }
        if (workflowRole(toolName) == ToolWorkflowRole.ASSET_DISCOVERY) {
            return "asset_discovery";
        }
        if ("asset_query".equals(semantic)
            || "asset_search".equals(semantic)
            || "asset_discovery".equals(semantic)) {
            return "asset_discovery";
        }
        if (workflowRole(toolName) == ToolWorkflowRole.TEMPLATE_DISCOVERY) {
            return "template_discovery";
        }
        if (semantic.endsWith("_asset_query") || semantic.endsWith("_asset_search")) {
            return "asset_discovery";
        }
        return toolName.trim();
    }

    private String matchingAvailableTool(List<String> availableTools, String semanticToolName) {
        if (availableTools == null || semanticToolName == null) {
            return null;
        }
        List<String> matches = new ArrayList<>();
        for (String availableTool : availableTools) {
            String semantic = toolSemanticKey(availableTool);
            ToolWorkflowRole role = workflowRole(availableTool);
            if (("asset_discovery".equals(semanticToolName) && role == ToolWorkflowRole.ASSET_DISCOVERY)
                || (("template_discovery".equals(semanticToolName) || "template_query".equals(semanticToolName))
                    && role == ToolWorkflowRole.TEMPLATE_DISCOVERY)
                || semanticToolName.equals(semantic)
                || ("asset_discovery".equals(semanticToolName) && "asset_query".equals(semantic))
                || ("asset_discovery".equals(semanticToolName) && "asset_search".equals(semantic))
                || ("template_discovery".equals(semanticToolName) && "template_query".equals(semantic))
                || ("asset_discovery".equals(semanticToolName) && semantic.endsWith("_asset_query"))
                || ("asset_discovery".equals(semanticToolName) && semantic.endsWith("_asset_search"))
                || ("template_discovery".equals(semanticToolName) && semantic.endsWith("_template_query"))
                || ("template_discovery".equals(semanticToolName) && semantic.endsWith("_template_search"))
                || ("asset_query".equals(semanticToolName) && semantic.endsWith("_asset_query"))
                || ("asset_query".equals(semanticToolName) && semantic.endsWith("_asset_search"))
                || ("template_query".equals(semanticToolName) && semantic.endsWith("_template_query"))
                || ("template_query".equals(semanticToolName) && semantic.endsWith("_template_search"))
                || (("template_discovery".equals(semanticToolName) || "template_query".equals(semanticToolName))
                    && workflowRole(availableTool) == ToolWorkflowRole.TEMPLATE_DISCOVERY)) {
                matches.add(availableTool);
            }
        }
        if (matches.isEmpty()) return null;
        List<String> mostSpecific = capabilityHierarchy.mostSpecific(matches);
        if (mostSpecific.size() == 1) {
            String selected = mostSpecific.get(0);
            return capabilityHierarchy.directlyInvocable(selected) ? selected : null;
        }
        // Multiple business implementations require intent/governance selection;
        // never pick an arbitrary leaf merely because of registry order.
        return matches.stream()
            .filter(tool -> capabilityHierarchy.node(tool)
                .map(node -> node.abstractCapability() || !node.businessImplementation())
                .orElse(true))
            .filter(capabilityHierarchy::directlyInvocable)
            .findFirst()
            .orElse(null);
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

    private ToolWorkflowRole workflowRole(String toolName) {
        if (toolRegistry != null) {
            ToolWorkflowRole role = toolRegistry.getWorkflowRole(toolName);
            if (role != null) return role;
            return ToolWorkflowContract.resolveRole(toolName, toolRegistry.getToolMetadata(toolName));
        }
        return ToolWorkflowContract.resolveRole(toolName, null);
    }

    private Map<String, Object> asMap(Object data) {
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> values = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) {
                    values.put(String.valueOf(key), value);
                }
            });
            return values;
        }
        return Map.of();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Boolean booleanObject(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return null;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Map<String, Object> workflowConfigMap(Object rawWorkflow) {
        if (rawWorkflow instanceof List<?> list) {
            Map<String, Object> workflow = new LinkedHashMap<>();
            workflow.put("enabled", true);
            workflow.put("steps", list);
            return workflow;
        }
        return asMap(rawWorkflow);
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                .map(this::stringValue)
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        }
        if (value == null) {
            return List.of();
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? List.of() : List.of(text);
    }

    private Object firstObject(Map<String, Object> values, String... keys) {
        if (values == null || values.isEmpty() || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    private Object authoritativeWorkflowDagForPlanning(Map<String, Object> runtimeAttributes) {
        Object rawDag = runtimeAttributes == null ? null : runtimeAttributes.get("authoritativeWorkflowDag");
        if (!(rawDag instanceof Collection<?> nodes) || nodes.isEmpty()) {
            return null;
        }
        return rawDag;
    }

    private String recoverPresentableNonJsonAnswer(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String candidate = raw.trim();
        if (candidate.length() < 80 || candidate.startsWith("{") || candidate.startsWith("[")) {
            return null;
        }
        boolean hasMarkdownHeading = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s+\\S+")
            .matcher(candidate).find();
        boolean hasMarkdownTable = Pattern.compile("(?m)^\\s*\\|.+\\|\\s*$")
            .matcher(candidate).find();
        boolean hasStructuredList = Pattern.compile("(?m)^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+)\\S+")
            .matcher(candidate).find();
        return hasMarkdownHeading && (hasMarkdownTable || hasStructuredList) ? candidate : null;
    }

    private boolean hasPresentableRecoveredAnswer(AgentDecision decision) {
        return decision != null
            && FINAL.equals(decision.action())
            && decision.answer() != null
            && !decision.answer().isBlank()
            && decision.executionPlan() != null
            && Boolean.TRUE.equals(decision.executionPlan().get("plannerCandidateAnswerPreserved"));
    }
}

record PlannerValidationContext(
    List<String> mandatoryTools,
    boolean requireToolBeforeFinal,
    boolean requireDocumentWebVerification,
    String documentSearchTool,
    String verificationWebSearchTool,
    List<String> availableTools,
    String query,
    Map<String, Object> experiencePrior,
    AgentPlanBudgetPolicy.BudgetCaps budgetCaps,
    Object authoritativeWorkflowDag
) {
    PlannerValidationContext(List<String> mandatoryTools,
                             boolean requireToolBeforeFinal,
                             boolean requireDocumentWebVerification,
                             String documentSearchTool,
                             String verificationWebSearchTool,
                             List<String> availableTools,
                             String query,
                             Map<String, Object> experiencePrior,
                             AgentPlanBudgetPolicy.BudgetCaps budgetCaps) {
        this(mandatoryTools, requireToolBeforeFinal, requireDocumentWebVerification,
            documentSearchTool, verificationWebSearchTool, availableTools, query, experiencePrior,
            budgetCaps, null);
    }

    PlannerValidationContext(List<String> mandatoryTools,
                             boolean requireToolBeforeFinal,
                             boolean requireDocumentWebVerification,
                             String documentSearchTool,
                             String verificationWebSearchTool,
                             List<String> availableTools,
                             String query,
                             Map<String, Object> experiencePrior) {
        this(mandatoryTools, requireToolBeforeFinal, requireDocumentWebVerification,
            documentSearchTool, verificationWebSearchTool, availableTools, query, experiencePrior,
            new AgentPlanBudgetPolicy.BudgetCaps(null, null, null), null);
    }

    PlannerValidationContext(List<String> mandatoryTools,
                             boolean requireToolBeforeFinal,
                             boolean requireDocumentWebVerification,
                             String documentSearchTool,
                             String verificationWebSearchTool,
                             List<String> availableTools,
                             String query) {
        this(mandatoryTools, requireToolBeforeFinal, requireDocumentWebVerification,
            documentSearchTool, verificationWebSearchTool, availableTools, query, Map.of(),
            new AgentPlanBudgetPolicy.BudgetCaps(null, null, null), null);
    }
}

record AgentDecision(
    String action,
    String toolName,
    Map<String, Object> arguments,
    String answer,
    String reason,
    Map<String, Object> executionPlan,
    Boolean sufficient,
    InterpretationPlan interpretationPlan
) {
    AgentDecision(String action,
                  String toolName,
                  Map<String, Object> arguments,
                  String answer,
                  String reason,
                  Map<String, Object> executionPlan,
                  Boolean sufficient) {
        this(action, toolName, arguments, answer, reason, executionPlan, sufficient, null);
    }
}

record PlannerExecutionResult(
    PlannerPlanProduct plan,
    RuntimeAnswerCandidate candidateAnswer,
    TaskContract taskContract,
    AgentDecision decision
) {
}

record PlannerPlanProduct(
    InterpretationPlan plan,
    boolean valid,
    boolean executable,
    List<String> issues
) {
    PlannerPlanProduct {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}

record PlanRewriteContext(
    int rewriteCount,
    List<PlanCandidate> candidates,
    String lastFailureReason,
    String failurePattern
) {
}

record PlanCandidate(
    int attempt,
    String label,
    String raw,
    AgentDecision decision,
    String failurePattern,
    String fingerprint,
    int deterministicScore,
    Map<String, Object> deterministicScoreDetails
) {
}

record GuardRepairResult(
    AgentDecision decision,
    boolean applied,
    List<String> notes
) {
}
