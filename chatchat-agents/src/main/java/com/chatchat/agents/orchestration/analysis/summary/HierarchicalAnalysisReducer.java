package com.chatchat.agents.orchestration.analysis.summary;

import com.chatchat.agents.runtime.context.AgentRoleAnalysisContext;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.model.DatasetRelationshipPlan;


import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisAssignment;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisDecisionOperatingModel;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisParticipant;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisScope;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisWork;
import com.chatchat.common.runtime.summary.spi.ModelSummaryReducer;
import com.chatchat.common.runtime.summary.spi.ModelSummaryModel;
import com.chatchat.common.runtime.summary.spi.ModelSummaryProgressReporter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Reduces chunk evidence into dataset summaries and explicitly-related dataset groups. */
public final class HierarchicalAnalysisReducer implements ModelSummaryReducer<
    AnalysisSummaryResult, HierarchicalAnalysisReducer.Context, HierarchicalAnalysisReducer.Result>,
    DataAnalysisParticipant<ModelSummaryModel, HierarchicalAnalysisReducer.Request,
        HierarchicalAnalysisReducer.Result> {

    public static final String SCHEMA_VERSION = "hierarchical_analysis_reduce.v1";
    private static final int MAX_SUMMARY_INPUT_CHARS = 24_000;

    public Result reduce(ModelSummaryModel model,
                         GovernanceIsolationScope isolationScope,
                         DatasetRelationshipPlan relationshipPlan,
                         List<AnalysisSummaryResult> chunkSummaries,
                         String userObjective) {
        Context context = new Context(model, isolationScope, relationshipPlan, userObjective);
        return analyze(model, Request.create(context, chunkSummaries),
            ModelSummaryProgressReporter.NOOP, () -> false);
    }

    @Override
    public Set<DataAnalysisScope> supportedScopes() {
        return Set.of(DataAnalysisScope.ASSIGNED_DATASET_COLLECTION);
    }

    @Override
    public Result analyzeAssigned(ModelSummaryModel model,
                                  Request work,
                                  ModelSummaryProgressReporter progressReporter,
                                  BooleanSupplier cancellationCheck) {
        if (cancellationCheck.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException(
                "Coordinating analysis was cancelled");
        }
        Context context = work.context();
        return reduceAssigned(model, context.isolationScope(), context.relationshipPlan(),
            work.summaries(), context.userObjective());
    }

    @Override
    public void reconcile(Request work, Result result) {
        Map<String, AnalysisSummaryResult> lineage = new LinkedHashMap<>();
        work.summaries().forEach(summary -> lineage.put(summary.resultId(), summary));
        result.datasetSummaries().forEach(summary -> lineage.put(summary.resultId(), summary));
        result.relationshipGroupSummaries().forEach(summary -> lineage.put(summary.resultId(), summary));
        Set<String> reachable = new java.util.LinkedHashSet<>();
        result.finalInputs().forEach(summary -> collectLineage(summary, lineage, reachable));
        List<String> missing = work.assignment().inputReferences().stream()
            .filter(reference -> !reachable.contains(reference)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                "Coordinating analysis lost assigned input lineage: " + missing);
        }
    }

    private void collectLineage(AnalysisSummaryResult summary,
                                Map<String, AnalysisSummaryResult> lineage,
                                Set<String> reachable) {
        if (summary == null || !reachable.add(summary.resultId())) return;
        for (String inputId : summary.inputSummaryResultIds()) {
            AnalysisSummaryResult input = lineage.get(inputId);
            if (input != null) collectLineage(input, lineage, reachable);
            else reachable.add(inputId);
        }
    }

    private Result reduceAssigned(ModelSummaryModel model,
                         GovernanceIsolationScope isolationScope,
                         DatasetRelationshipPlan relationshipPlan,
                         List<AnalysisSummaryResult> chunkSummaries,
                         String userObjective) {
        List<AnalysisSummaryResult> chunks = chunkSummaries == null
            ? List.of() : List.copyOf(chunkSummaries);
        Map<String, AnalysisSummaryResult> datasetSummaries = new LinkedHashMap<>();
        boolean workerReduced = !chunks.isEmpty() && chunks.stream()
            .allMatch(summary -> "DATASET_SYNTHESIS".equals(summary.scope()));
        if (workerReduced) {
            for (AnalysisSummaryResult summary : chunks) {
                String dataset = String.valueOf(
                    summary.position().getOrDefault("datasetReference", "result"));
                datasetSummaries.put(dataset, summary);
            }
        } else {
            Map<String, List<AnalysisSummaryResult>> chunksByDataset = new LinkedHashMap<>();
            for (AnalysisSummaryResult chunk : chunks) {
                String dataset = String.valueOf(
                    chunk.position().getOrDefault("datasetReference", "result"));
                chunksByDataset.computeIfAbsent(dataset, ignored -> new ArrayList<>()).add(chunk);
            }
            for (Map.Entry<String, List<AnalysisSummaryResult>> entry : chunksByDataset.entrySet()) {
                datasetSummaries.put(entry.getKey(), reduceDataset(
                    model, isolationScope, entry.getKey(), entry.getValue(), userObjective));
            }
        }

        List<AnalysisSummaryResult> finalInputs = new ArrayList<>();
        List<AnalysisSummaryResult> groupSummaries = new ArrayList<>();
        for (DatasetRelationshipPlan.Group group : relationshipPlan.groups()) {
            List<AnalysisSummaryResult> inputs = group.datasetReferences().stream()
                .map(datasetSummaries::get)
                .filter(java.util.Objects::nonNull)
                .toList();
            if (inputs.isEmpty()) continue;
            if (!group.explicitRelationship() || inputs.size() == 1) {
                finalInputs.addAll(inputs);
                continue;
            }
            AnalysisSummaryResult groupSummary = reduceGroup(
                model, isolationScope, group, relationshipPlan, inputs, userObjective);
            groupSummaries.add(groupSummary);
            finalInputs.add(groupSummary);
        }
        // A runtime guard: every analyzed dataset must survive into exactly one final input lineage.
        List<String> uncovered = datasetSummaries.keySet().stream()
            .filter(dataset -> relationshipPlan.groups().stream()
                .noneMatch(group -> group.datasetReferences().contains(dataset)))
            .toList();
        uncovered.forEach(dataset -> finalInputs.add(datasetSummaries.get(dataset)));

        return new Result(relationshipPlan, List.copyOf(datasetSummaries.values()),
            List.copyOf(groupSummaries), List.copyOf(finalInputs), uncovered);
    }

    @Override
    public Result reduce(Context context, List<AnalysisSummaryResult> summaries) {
        if (context == null) {
            throw new IllegalArgumentException("summary reduction context is required");
        }
        return analyze(context.model(), Request.create(context, summaries),
            ModelSummaryProgressReporter.NOOP, () -> false);
    }

    public AnalysisSummaryResult reduceDataset(ModelSummaryModel model,
                                               GovernanceIsolationScope scope,
                                               String dataset,
                                               List<AnalysisSummaryResult> chunks,
                                               String objective) {
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("dataset chunks are required");
        }
        String originalUserQuestion = requiredQuestion(objective);
        AnalysisSummaryResult first = chunks.get(0);
        boolean requiresModelReduce = chunks.size() > 1;
        String content = requiresModelReduce
            ? synthesize(model, "DATASET_SYNTHESIS", originalUserQuestion, chunks, Map.of(
                "datasetReference", dataset,
                "analysisContext", first.analysisContext(),
                "rule", "Merge only chunks of this dataset; preserve conflicts and limitations."))
            : chunks.size() == 1 ? first.content() : deterministicMerge(chunks);
        boolean fallback = content == null || content.isBlank();
        if (fallback) content = deterministicMerge(chunks);
        return AnalysisSummaryResult.intermediateSummary(
            scope,
            "DATASET_SYNTHESIS",
            "dataset-summary#" + dataset,
            content,
            requiresModelReduce
                ? fallback ? "DETERMINISTIC_DATASET_REDUCE_FALLBACK" : "MODEL_DATASET_REDUCE"
                : chunks.size() == 1 ? "SINGLE_CHUNK_DATASET_REDUCE" : "DETERMINISTIC_DATASET_REDUCE",
            Map.of("datasetReference", dataset, "chunkCount", chunks.size()),
            first.analysisContext(),
            Map.of("inputChunkCount", chunks.size(), "complete", true),
            chunks,
            hierarchyEvidence(chunks, Map.of(
                "reduceSchemaVersion", SCHEMA_VERSION,
                "analysisDecisionOperatingModelVersion",
                    DataAnalysisDecisionOperatingModel.SCHEMA_VERSION,
                "analysisParticipantRole",
                    DataAnalysisDecisionOperatingModel.ParticipantRole.REDUCER.name(),
                "managementReviewInput", true))
        );
    }

    private AnalysisSummaryResult reduceGroup(ModelSummaryModel model,
                                              GovernanceIsolationScope scope,
                                              DatasetRelationshipPlan.Group group,
                                              DatasetRelationshipPlan plan,
                                              List<AnalysisSummaryResult> inputs,
                                              String objective) {
        List<Map<String, Object>> groupEdges = plan.edges().stream()
            .filter(edge -> group.datasetReferences().contains(edge.fromDataset())
                && group.datasetReferences().contains(edge.toDataset()))
            .map(DatasetRelationshipPlan.Edge::toMap)
            .toList();
        Map<String, Object> commonRoleContext = commonAgentRoleContext(inputs);
        Map<String, Object> reduceContext = new LinkedHashMap<>();
        reduceContext.put("groupId", group.groupId());
        reduceContext.put("datasetReferences", group.datasetReferences());
        reduceContext.put("authorizedRelationships", groupEdges);
        reduceContext.put("rule", "Correlate only through the authorized relationships; do not invent joins or recalculate values.");
        if (!commonRoleContext.isEmpty()) {
            reduceContext.put(AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY, commonRoleContext);
        }
        String content = synthesize(model, "RELATIONSHIP_GROUP_SYNTHESIS", objective, inputs,
            Collections.unmodifiableMap(reduceContext));
        boolean fallback = content == null || content.isBlank();
        if (fallback) content = deterministicMerge(inputs);
        return AnalysisSummaryResult.intermediateSummary(
            scope,
            "RELATIONSHIP_GROUP_SYNTHESIS",
            "relationship-summary#" + group.groupId(),
            content,
            fallback ? "DETERMINISTIC_RELATIONSHIP_REDUCE_FALLBACK" : "MODEL_RELATIONSHIP_REDUCE",
            Map.of("groupId", group.groupId(), "datasetReferences", group.datasetReferences()),
            groupAnalysisContext(groupEdges, commonRoleContext),
            Map.of("inputDatasetCount", inputs.size(), "complete", true),
            inputs,
            hierarchyEvidence(inputs, Map.of(
                "reduceSchemaVersion", SCHEMA_VERSION,
                "analysisDecisionOperatingModelVersion",
                    DataAnalysisDecisionOperatingModel.SCHEMA_VERSION,
                "analysisParticipantRole",
                    DataAnalysisDecisionOperatingModel.ParticipantRole.REDUCER.name(),
                "managementReviewInput", true,
                "authorizedRelationships", groupEdges))
        );
    }

    private Map<String, Object> hierarchyEvidence(List<AnalysisSummaryResult> inputs,
                                                  Map<String, Object> additions) {
        Map<String, Object> evidence = new LinkedHashMap<>(additions);
        for (String key : List.of("facts", "insights", "claimLifecycle", "claimAdmissionDecisions", "semanticGaps",
            "semanticGapRequests",
            "entities", "crossChunkKeys",
            "conflicts", "limitations", "analysisQuality", "analysisObjectiveContract",
            "analysisDepth", "analysisDepthContractVersion",
            "demandAnalysis", "metricAssociations",
            "datasetFindings", "metrics", "rankings", "analyzedRelationships", "businessConclusions",
            "unsupportedQuestions", "missingEvidence", "recommendedFollowupRequests")) {
            List<Object> values = new ArrayList<>();
            for (AnalysisSummaryResult input : inputs) {
                Object value = input.evidence().get(key);
                if (value instanceof Iterable<?> items) items.forEach(values::add);
                else if (value instanceof Map<?, ?> map && !map.isEmpty()) values.add(value);
            }
            if (!values.isEmpty()) evidence.put(key, List.copyOf(values));
        }
        List<Object> objectiveAlignments = inputs.stream()
            .map(input -> input.evidence().get("objectiveAlignment"))
            .filter(value -> value instanceof Map<?, ?> map && !map.isEmpty())
            .toList();
        if (!objectiveAlignments.isEmpty()) {
            evidence.put("objectiveAlignments", objectiveAlignments);
        }
        evidence.put("rawReplayRecommended", inputs.stream().anyMatch(input ->
            Boolean.TRUE.equals(input.evidence().get("rawReplayRecommended"))));
        return Collections.unmodifiableMap(evidence);
    }

    private String synthesize(ModelSummaryModel model,
                              String scope,
                              String objective,
                              List<AnalysisSummaryResult> inputs,
                              Map<String, Object> reduceContext) {
        if (model == null) return null;
        List<Map<String, Object>> projections = inputs.stream().map(this::projection).toList();
        String prompt = "You are performing a governed hierarchical analysis reduction ("
            + SCHEMA_VERSION + ").\n"
            + "Reduction scope: " + scope + "\n"
            + "Original user question (authoritative analysis intent): "
            + requiredQuestion(objective) + "\n"
            + "Reduction context: " + ModelProtocolJson.compact(reduceContext) + "\n"
            + "Upstream summaries: " + ModelProtocolJson.compact(projections) + "\n"
            + "Produce a concise business analysis that directly answers the original question. Treat upstream "
            + "summaries as completed Worker analysis reports, not as raw data descriptions. Consolidate their work, "
            + "preserve each report's analytical contribution and detect disagreements, evidence gaps and weakly "
            + "supported areas for the final management-level Driver. Do not simply concatenate Worker narratives. "
            + "agent_role_analysis_context as maintained orientation for relevance, analytical emphasis and vocabulary, never "
            + "as returned evidence, semantic authorization or a replacement for the original question. Treat upstream "
            + "objectiveAlignment as a coverage contract: merge addressed aspects, retain unsupported aspects, and "
            + "do not replace an unsupported requested metric. Field meaning, aggregation, additivity, proxy "
            + "relationships, population scope, and completeness are valid only when explicitly producer-declared "
            + "in upstream analysisContext. Never infer them from field names, repeated values, chunk shape, or "
            + "correlation. Without a declaration, preserve the observation, mark the semantic operation unknown, "
            + "and do not aggregate, deduplicate, substitute, or generalize it. Distinguish returned-detail-row "
            + "coverage from explicitly declared comparison/population coverage and source completeness. "
            + "Do not promote chunk-local extrema, rankings, trends, one-period behavior or a small sample to "
            + "dataset/global, stable, causal or longitudinal conclusions unless scope and completeness support it. "
            + "Apply the professional analysis stages carried in analysisObjectiveContract: reconcile scope and grain, "
            + "quality signals and conflicts; combine the highest-materiality upstream insights; preserve the distinction "
            + "between observed facts, authorized derived measures and calibrated inferences; retain formulas, supporting "
            + "values, validated semanticBasis, confidence and alternative explanations. Treat evidence.insights as "
            + "the only admitted analytical claims: never recreate a calculation or inference omitted by validation, "
            + "and never recover rejected claims from free-text content. Resolve contradictory claims when evidence permits, "
            + "otherwise surface the conflict once. Reconcile upstream analysisDepth coverage against the objective mode. "
            + "Do not accept a data inventory, generic risk list or generic recommendation as a diagnosis. A diagnostic "
            + "or decision conclusion must connect an admitted observation to an authorized baseline or comparison, a "
            + "material deviation, its supported impact, competing explanations, discriminating verification and a "
            + "prioritized action. Preserve unsupported depth dimensions and their follow-up requests instead of filling "
            + "them with common knowledge. "
            + "Merge upstream demandAnalysis into a question-directed decision goal, distinguishing answered from "
            + "open questions. Preserve upstream metricAssociations as analysis candidates. Only an association "
            + "already admitted as an insight may be stated as a finding; otherwise label it pending validation and "
            + "retain the proposed metrics, method and missing evidence without inventing correlation or causality. "
            + "Preserve material exact values and limitations. Keep dataset and "
            + "result identity in the structured lineage only: never print resultId values, runtime ids, template ids, "
            + "tool names, schema keys, or technical evidence references in the business narrative. Do not infer "
            + "relationships beyond the reduction context. Rank by relevance and materiality. Do not concatenate chunk "
            + "summaries, output raw rows or complete record inventories, or discuss execution metadata.";
        try {
            String result = model.generate(prompt);
            return result == null || result.isBlank() ? null : result.trim();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Map<String, Object> projection(AnalysisSummaryResult summary) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resultId", summary.resultId());
        result.put("scope", summary.scope());
        result.put("position", summary.position());
        result.put("content", abbreviate(summary.content()));
        result.put("outcome", summary.outcome());
        Object roleContext = summary.analysisContext().get(
            AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY);
        if (roleContext instanceof Map<?, ?> role && !role.isEmpty()) {
            result.put(AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY, roleContext);
        }
        result.put("evidenceId", summary.evidence().get("evidenceId"));
        result.put("conflicts", summary.evidence().getOrDefault("conflicts", List.of()));
        result.put("limitations", summary.evidence().getOrDefault("limitations", List.of()));
        result.put("objectiveAlignment",
            summary.evidence().getOrDefault("objectiveAlignment", Map.of()));
        result.put("analysisObjectiveContract",
            summary.evidence().getOrDefault("analysisObjectiveContract", Map.of()));
        result.put("facts", summary.evidence().getOrDefault("facts", List.of()));
        result.put("insights", summary.evidence().getOrDefault("insights", List.of()));
        result.put("claimAdmissionDecisions",
            summary.evidence().getOrDefault("claimAdmissionDecisions", List.of()));
        result.put("semanticGaps", summary.evidence().getOrDefault("semanticGaps", List.of()));
        result.put("claimLifecycle", summary.evidence().getOrDefault("claimLifecycle", List.of()));
        result.put("semanticGapRequests",
            summary.evidence().getOrDefault("semanticGapRequests", List.of()));
        result.put("analysisQuality", summary.evidence().getOrDefault("analysisQuality", Map.of()));
        result.put("analysisDepth", summary.evidence().getOrDefault("analysisDepth", Map.of()));
        result.put("demandAnalysis", summary.evidence().getOrDefault("demandAnalysis", Map.of()));
        result.put("metricAssociations",
            summary.evidence().getOrDefault("metricAssociations", List.of()));
        result.put("datasetFindings", summary.evidence().getOrDefault("datasetFindings", List.of()));
        result.put("metrics", summary.evidence().getOrDefault("metrics", Map.of()));
        result.put("rankings", summary.evidence().getOrDefault("rankings", Map.of()));
        result.put("analyzedRelationships",
            summary.evidence().getOrDefault("analyzedRelationships", List.of()));
        result.put("businessConclusions", summary.evidence().getOrDefault("businessConclusions", List.of()));
        result.put("unsupportedQuestions", summary.evidence().getOrDefault("unsupportedQuestions", List.of()));
        result.put("missingEvidence", summary.evidence().getOrDefault("missingEvidence", List.of()));
        result.put("recommendedFollowupRequests",
            summary.evidence().getOrDefault("recommendedFollowupRequests", List.of()));
        result.put("rejectedInsightCount", summary.evidence().getOrDefault("rejectedInsightCount", 0));
        result.put("recordCount", summary.evidence().getOrDefault("recordCount", 0));
        result.put("sourceComplete", summary.evidence().getOrDefault("sourceComplete", true));
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Object> groupAnalysisContext(List<Map<String, Object>> relationships,
                                                      Map<String, Object> roleContext) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("relationships", relationships);
        if (roleContext != null && !roleContext.isEmpty()) {
            context.put(AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY, roleContext);
        }
        return Collections.unmodifiableMap(context);
    }

    private Map<String, Object> commonAgentRoleContext(List<AnalysisSummaryResult> inputs) {
        List<Map<String, Object>> contexts = new ArrayList<>();
        for (AnalysisSummaryResult input : inputs) {
            Object value = input.analysisContext().get(
                AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY);
            if (!(value instanceof Map<?, ?> raw)) continue;
            Map<String, Object> copy = new LinkedHashMap<>();
            raw.forEach((key, item) -> {
                if (key != null) copy.put(String.valueOf(key), item);
            });
            Map<String, Object> context = Collections.unmodifiableMap(copy);
            if (!context.isEmpty() && !contexts.contains(context)) contexts.add(context);
        }
        return contexts.size() == 1 ? contexts.get(0) : Map.of();
    }

    private String deterministicMerge(List<AnalysisSummaryResult> inputs) {
        StringBuilder merged = new StringBuilder();
        for (AnalysisSummaryResult input : inputs) {
            if (!merged.isEmpty()) merged.append("\n\n");
            merged.append(abbreviate(input.content()));
        }
        return merged.toString();
    }

    private String abbreviate(String content) {
        if (content == null) return "";
        return content.length() <= MAX_SUMMARY_INPUT_CHARS
            ? content : content.substring(0, MAX_SUMMARY_INPUT_CHARS) + "…";
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) return "Summarize the material returned evidence.";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 2_000 ? normalized : normalized.substring(0, 2_000);
    }

    private String requiredQuestion(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("original user question is required for analysis reduction");
        }
        return value;
    }

    public record Result(
        DatasetRelationshipPlan relationshipPlan,
        List<AnalysisSummaryResult> datasetSummaries,
        List<AnalysisSummaryResult> relationshipGroupSummaries,
        List<AnalysisSummaryResult> finalInputs,
        List<String> uncoveredDatasets
    ) {
        public Result {
            datasetSummaries = datasetSummaries == null ? List.of() : List.copyOf(datasetSummaries);
            relationshipGroupSummaries = relationshipGroupSummaries == null
                ? List.of() : List.copyOf(relationshipGroupSummaries);
            finalInputs = finalInputs == null ? List.of() : List.copyOf(finalInputs);
            uncoveredDatasets = uncoveredDatasets == null ? List.of() : List.copyOf(uncoveredDatasets);
        }

        public String promptEvidence() {
            StringBuilder prompt = new StringBuilder(
                "Hierarchical governed summaries (hierarchical_analysis_reduce.v1). "
                    + "Use these as the primary final-synthesis input.\n");
            prompt.append("Dataset relationship plan: ")
                .append(ModelProtocolJson.compact(relationshipPlan.toMap())).append("\n");
            for (AnalysisSummaryResult input : finalInputs) {
                prompt.append("- ").append(input.resultId())
                    .append(" scope=").append(input.scope())
                    .append(" position=").append(ModelProtocolJson.compact(input.position()))
                    .append(" analysisContext=")
                    .append(ModelProtocolJson.compact(contextProjection(input.analysisContext())))
                    .append(" lineage=").append(ModelProtocolJson.compact(input.evidence()))
                    .append(": ").append(input.content()).append("\n");
            }
            return prompt.toString();
        }

        private Map<String, Object> contextProjection(Map<String, Object> context) {
            Map<String, Object> projection = new LinkedHashMap<>();
            if (context == null) return projection;
            for (String key : List.of("source", "capability", "business", "relationships",
                "semantics", "quality", "analysisPolicy", "extensions", "contextCompleteness",
                "templateMatchAnalysis", "workerAnalysisContext",
                AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY)) {
                Object value = context.get(key);
                if (value != null && (!(value instanceof Map<?, ?> map) || !map.isEmpty())) {
                    projection.put(key, value);
                }
            }
            return projection;
        }
    }

    /** Agent-owned reduction context; the common reducer port remains model-SDK neutral. */
    public record Context(
        ModelSummaryModel model,
        GovernanceIsolationScope isolationScope,
        DatasetRelationshipPlan relationshipPlan,
        String userObjective
    ) {
        public Context {
            relationshipPlan = relationshipPlan == null
                ? DatasetRelationshipPlan.create(List.of()) : relationshipPlan;
            userObjective = userObjective == null ? "" : userObjective;
        }
    }

    /** Immutable Driver-side payload; it follows the same assignment contract as Worker work. */
    public record Request(
        DataAnalysisAssignment assignment,
        Context context,
        List<AnalysisSummaryResult> summaries
    ) implements DataAnalysisWork {
        public Request {
            if (assignment == null) throw new IllegalArgumentException("assignment is required");
            if (context == null) throw new IllegalArgumentException("reduction context is required");
            summaries = summaries == null ? List.of() : List.copyOf(summaries);
            if (summaries.isEmpty()) {
                throw new IllegalArgumentException("assigned dataset summaries are required");
            }
            List<String> actualInputs = summaries.stream()
                .map(AnalysisSummaryResult::resultId).distinct().toList();
            if (!assignment.inputReferences().equals(actualInputs)) {
                throw new IllegalArgumentException(
                    "assignment input lineage does not match reduction summaries");
            }
            if (!context.isolationScope().samePartition(assignment.isolationScope())) {
                throw new IllegalArgumentException("assignment isolation partition mismatch");
            }
            if (summaries.stream().anyMatch(summary ->
                !context.isolationScope().samePartition(summary.isolationScope()))) {
                throw new IllegalArgumentException("summary isolation partition mismatch");
            }
        }

        public static Request create(Context context, List<AnalysisSummaryResult> summaries) {
            if (context == null) throw new IllegalArgumentException("reduction context is required");
            List<AnalysisSummaryResult> inputs = summaries == null ? List.of() : List.copyOf(summaries);
            List<String> references = inputs.stream()
                .map(AnalysisSummaryResult::resultId).distinct().toList();
            String fingerprint = ModelProtocolJson.sha256Hex(Map.of(
                "relationshipPlan", context.relationshipPlan().toMap(),
                "originalUserQuestion", context.userObjective(),
                "inputReferences", references));
            DataAnalysisAssignment assignment = new DataAnalysisAssignment(
                DataAnalysisAssignment.SCHEMA_VERSION,
                context.isolationScope().partitionKey() + ":driver-reduce:" + fingerprint,
                fingerprint, DataAnalysisScope.ASSIGNED_DATASET_COLLECTION,
                context.isolationScope(), context.userObjective(), references,
                Map.of("relationshipPlan", context.relationshipPlan().toMap()),
                300_000L, 1);
            return new Request(assignment, context, inputs);
        }
    }
}
