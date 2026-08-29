package com.chatchat.agents.orchestration.analysis;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.ModelSummaryReducer;
import com.chatchat.common.runtime.summary.ModelSummaryModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reduces chunk evidence into dataset summaries and explicitly-related dataset groups. */
public final class HierarchicalAnalysisReducer implements ModelSummaryReducer<
    AnalysisSummaryResult, HierarchicalAnalysisReducer.Context, HierarchicalAnalysisReducer.Result> {

    public static final String SCHEMA_VERSION = "hierarchical_analysis_reduce.v1";
    private static final int MAX_SUMMARY_INPUT_CHARS = 24_000;

    public Result reduce(ModelSummaryModel model,
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
        return reduce(context.model(), context.isolationScope(), context.relationshipPlan(),
            summaries, context.userObjective());
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
            hierarchyEvidence(chunks, Map.of("reduceSchemaVersion", SCHEMA_VERSION))
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
        String content = synthesize(model, "RELATIONSHIP_GROUP_SYNTHESIS", objective, inputs, Map.of(
            "groupId", group.groupId(),
            "datasetReferences", group.datasetReferences(),
            "authorizedRelationships", groupEdges,
            "rule", "Correlate only through the authorized relationships; do not invent joins or recalculate values."));
        boolean fallback = content == null || content.isBlank();
        if (fallback) content = deterministicMerge(inputs);
        return AnalysisSummaryResult.intermediateSummary(
            scope,
            "RELATIONSHIP_GROUP_SYNTHESIS",
            "relationship-summary#" + group.groupId(),
            content,
            fallback ? "DETERMINISTIC_RELATIONSHIP_REDUCE_FALLBACK" : "MODEL_RELATIONSHIP_REDUCE",
            Map.of("groupId", group.groupId(), "datasetReferences", group.datasetReferences()),
            Map.of("relationships", groupEdges),
            Map.of("inputDatasetCount", inputs.size(), "complete", true),
            inputs,
            hierarchyEvidence(inputs, Map.of(
                "reduceSchemaVersion", SCHEMA_VERSION, "authorizedRelationships", groupEdges))
        );
    }

    private Map<String, Object> hierarchyEvidence(List<AnalysisSummaryResult> inputs,
                                                  Map<String, Object> additions) {
        Map<String, Object> evidence = new LinkedHashMap<>(additions);
        for (String key : List.of("facts", "entities", "crossChunkKeys", "conflicts", "limitations")) {
            List<Object> values = new ArrayList<>();
            for (AnalysisSummaryResult input : inputs) {
                Object value = input.evidence().get(key);
                if (value instanceof Iterable<?> items) items.forEach(values::add);
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
            + "objectiveAlignment as a coverage contract: merge addressed aspects, retain unsupported aspects, and "
            + "do not replace an unsupported requested metric. Field meaning, aggregation, additivity, proxy "
            + "relationships, population scope, and completeness are valid only when explicitly producer-declared "
            + "in upstream analysisContext. Never infer them from field names, repeated values, chunk shape, or "
            + "correlation. Without a declaration, preserve the observation, mark the semantic operation unknown, "
            + "and do not aggregate, deduplicate, substitute, or generalize it. Distinguish returned-detail-row "
            + "coverage from explicitly declared comparison/population coverage and source completeness. "
            + "Do not promote chunk-local extrema, rankings, or trends to dataset/global conclusions unless completeness "
            + "is explicitly evidenced. Preserve material exact values, conflicts, limitations, dataset identity, and "
            + "cite upstream resultId values inline. Do not infer relationships beyond the reduction context. "
            + "Do not concatenate chunk summaries, output raw rows, or discuss execution metadata.";
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
        result.put("evidenceId", summary.evidence().get("evidenceId"));
        result.put("conflicts", summary.evidence().getOrDefault("conflicts", List.of()));
        result.put("limitations", summary.evidence().getOrDefault("limitations", List.of()));
        result.put("objectiveAlignment",
            summary.evidence().getOrDefault("objectiveAlignment", Map.of()));
        result.put("facts", summary.evidence().getOrDefault("facts", List.of()));
        result.put("recordCount", summary.evidence().getOrDefault("recordCount", 0));
        result.put("sourceComplete", summary.evidence().getOrDefault("sourceComplete", true));
        return Collections.unmodifiableMap(result);
    }

    private String deterministicMerge(List<AnalysisSummaryResult> inputs) {
        StringBuilder merged = new StringBuilder();
        for (AnalysisSummaryResult input : inputs) {
            if (!merged.isEmpty()) merged.append("\n\n");
            merged.append("[").append(input.resultId()).append("] ")
                .append(abbreviate(input.content()));
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
                "semantics", "quality", "analysisPolicy", "extensions", "contextCompleteness")) {
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
}
