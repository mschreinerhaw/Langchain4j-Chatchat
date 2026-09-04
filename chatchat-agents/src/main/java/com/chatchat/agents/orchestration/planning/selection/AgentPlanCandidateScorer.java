package com.chatchat.agents.orchestration.planning.selection;

import com.chatchat.agents.orchestration.planning.model.AgentDecision;
import com.chatchat.agents.orchestration.planning.model.PlanCandidate;
import com.chatchat.agents.orchestration.planning.model.PlanRewriteContext;
import com.chatchat.agents.orchestration.planning.model.PlannerValidationContext;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministically scores model-produced plan candidates against available
 * tools, DAG validity, evidence coverage, runtime policy and learned experience.
 * It performs no model calls and does not mutate runtime execution state.
 */
public final class AgentPlanCandidateScorer {

    private static final int MAX_USER_QUERY_PROMPT_CHARS = 32_000;
    private final ToolRegistry toolRegistry;

    public AgentPlanCandidateScorer(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public PlanRewriteContext rewriteContext(List<PlanCandidate> candidates) {
        List<PlanCandidate> values = candidates == null ? List.of() : List.copyOf(candidates);
        PlanCandidate last = values.isEmpty() ? null : values.get(values.size() - 1);
        String lastFailureReason = last == null || last.decision() == null ? "unknown" : last.decision().reason();
        String failurePattern = dominantFailurePattern(values);
        return new PlanRewriteContext(values.size(), values, lastFailureReason, failurePattern);
    }

    public PlanCandidate score(int attempt,
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

    public Map<String, Object> deterministicPlanScoreDetails(AgentDecision decision,
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

    public Map<String, Object> experiencePrior(Map<String, Object> runtimeAttributes) {
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

    private boolean plannerPlanInvalid(AgentDecision decision) {
        if (decision == null) {
            return true;
        }
        if ("invalid_interpretation_plan".equals(decision.reason())) {
            return true;
        }
        Object schemaValid = decision.executionPlan() == null ? null
            : decision.executionPlan().get("interpretationPlanSchemaValid");
        Object runtimeValid = decision.executionPlan() == null ? null
            : decision.executionPlan().get("interpretationPlanRuntimeRulesValid");
        return Boolean.FALSE.equals(schemaValid) || Boolean.FALSE.equals(runtimeValid);
    }

    private List<String> plannerIssues(AgentDecision decision) {
        if (decision == null || decision.executionPlan() == null) {
            return List.of();
        }
        List<String> issues = new ArrayList<>();
        for (String key : List.of("interpretationPlanRuntimeIssues", "interpretationPlanIssues")) {
            Object value = decision.executionPlan().get(key);
            if (value instanceof List<?> list) {
                list.stream().map(String::valueOf).filter(item -> !item.isBlank()).forEach(issues::add);
            }
        }
        return issues.stream().distinct().toList();
    }

    private List<String> normalizeList(List<String> values) {
        return values == null ? List.of() : values.stream()
            .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> values)) {
            return List.of();
        }
        return values.stream().filter(java.util.Objects::nonNull).map(String::valueOf)
            .filter(item -> !item.isBlank()).toList();
    }

    private boolean sameToolName(String first, String second) {
        return toolSemanticKey(first).equals(toolSemanticKey(second));
    }

    private boolean isWebDiscoveryTool(String toolName) {
        String semantic = toolSemanticKey(toolName);
        return semantic.contains("web_search")
            || semantic.contains("web_page_analyze")
            || semantic.contains("site_intelligence")
            || semantic.contains("finance_site_search")
            || semantic.contains("generic_web_site_search")
            || semantic.equals("web_site_search")
            || (semantic.contains("site_search") && !semantic.contains("search_and_extract"));
    }

    private String toolSemanticKey(String toolName) {
        if (toolName == null) {
            return "";
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        while (normalized.startsWith("mcp_")) {
            normalized = normalized.substring(4);
        }
        for (String prefix : List.of("chatchat_mcp_server_", "chatchat_", "xxx_")) {
            if (normalized.startsWith(prefix)) {
                normalized = normalized.substring(prefix.length());
            }
        }
        return normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean dependsOnStep(Integer fromStepId,
                                  Integer requiredDependencyId,
                                  Map<Integer, InterpretationPlan.Step> stepsById,
                                  Set<Integer> visited) {
        if (fromStepId == null || requiredDependencyId == null) return false;
        if (fromStepId.equals(requiredDependencyId)) return true;
        Set<Integer> seen = visited == null ? new LinkedHashSet<>() : visited;
        if (!seen.add(fromStepId)) return false;
        InterpretationPlan.Step from = stepsById.get(fromStepId);
        if (from == null || from.dependsOn() == null) return false;
        if (from.dependsOn().contains(requiredDependencyId)) return true;
        return from.dependsOn().stream()
            .anyMatch(dependency -> dependsOnStep(dependency, requiredDependencyId, stepsById, seen));
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
