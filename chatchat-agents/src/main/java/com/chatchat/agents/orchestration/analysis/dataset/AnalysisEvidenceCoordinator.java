package com.chatchat.agents.orchestration.analysis.dataset;

import com.chatchat.agents.orchestration.analysis.contract.SemanticInsightContractProvider;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.model.DatasetRelationshipPlan;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.batch.ToolCallBatchResult;
import com.chatchat.agents.runtime.batch.ToolCallResult;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.protocol.RuntimeAnalysisContextProtocol;
import com.chatchat.agents.runtime.protocol.RuntimeResultAnalysisProtocol;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.knowledge.template.TemplateMatchAnalysis;
import com.chatchat.common.knowledge.template.TemplateWorkerAnalysisContext;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisSummaryProtocol;
import com.chatchat.common.tool.McpToolNamePolicy;
import com.chatchat.common.tool.ToolMetadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.chatchat.agents.orchestration.support.AgentValueSupport.*;

/** Projects arbitrary Runtime evidence into governed, relationship-aware analysis datasets. */
public final class AnalysisEvidenceCoordinator {

    private final ToolRegistry toolRegistry;
    private final ToolRuntimeService toolRuntimeService;
    private final StructuredDataProjector structuredDataProjector;
    private final AnalysisRecordChunkPlanner recordChunkPlanner;
    private final int maximumChunkChars;
    private RuntimeAnalysisContextProtocol analysisContextProtocol;
    private RuntimeResultAnalysisProtocol resultAnalysisProtocol;
    private SemanticInsightContractProvider semanticInsightContractProvider;

    public AnalysisEvidenceCoordinator(
        ToolRegistry toolRegistry,
        ToolRuntimeService toolRuntimeService,
        StructuredDataProjector structuredDataProjector,
        AnalysisRecordChunkPlanner recordChunkPlanner,
        int maximumChunkChars,
        RuntimeAnalysisContextProtocol analysisContextProtocol,
        RuntimeResultAnalysisProtocol resultAnalysisProtocol,
        SemanticInsightContractProvider semanticInsightContractProvider
    ) {
        this.toolRegistry = toolRegistry;
        this.toolRuntimeService = toolRuntimeService;
        this.structuredDataProjector = structuredDataProjector;
        this.recordChunkPlanner = recordChunkPlanner;
        this.maximumChunkChars = maximumChunkChars;
        this.analysisContextProtocol = analysisContextProtocol;
        this.resultAnalysisProtocol = resultAnalysisProtocol;
        this.semanticInsightContractProvider = semanticInsightContractProvider;
    }

    public void setProtocols(RuntimeAnalysisContextProtocol contextProtocol,
                             RuntimeResultAnalysisProtocol resultProtocol) {
        if (contextProtocol != null) this.analysisContextProtocol = contextProtocol;
        if (resultProtocol != null) this.resultAnalysisProtocol = resultProtocol;
    }

    public void setSemanticInsightContractProvider(SemanticInsightContractProvider provider) {
        if (provider != null) this.semanticInsightContractProvider = provider;
    }

    public Projection project(InterpretationPlanRuntime.ExecutionResult result) {
        if (result == null || result.steps() == null) return new Projection(List.of(), List.of());
        Map<String, Map<String, Object>> templateMatches = templateRequirementMatches(result.steps());
        List<Dataset> datasets = new ArrayList<>();
        List<Map<String, Object>> excluded = new ArrayList<>();
        for (InterpretationPlanRuntime.StepExecution step : result.steps()) {
            if (step == null || !step.success()) continue;
            Object resolved = resolveEvidenceData(step);
            if (resolved instanceof ToolCallBatchResult batch) {
                for (ToolCallResult child : batch.results()) {
                    if (!"SUCCESS".equalsIgnoreCase(child.status()) || !child.evidenceUsable()) continue;
                    String reference = firstNonBlank(child.templateId(),
                        firstNonBlank(child.templateCode(), firstNonBlank(child.callId(), "result")));
                    List<Dataset> childDatasets = outputDatasets(
                        child.output(), reference, toolMetadata(child.toolName()));
                    if (childDatasets.isEmpty()) {
                        excluded.add(metadataOf("datasetReference", reference,
                            "toolName", child.toolName(), "reason", "NO_NON_EMPTY_STRUCTURED_RECORDS",
                            "executionStatus", child.status()));
                    } else {
                        datasets.addAll(withTemplateRequirementMatch(childDatasets,
                            firstNonBlank(child.templateId(), child.templateCode()), templateMatches));
                    }
                }
                continue;
            }
            if (step.toolName() == null || step.toolName().isBlank()
                || McpToolNamePolicy.isRoutingDiscovery(step.toolName())) continue;
            datasets.addAll(withTemplateRequirementMatch(
                outputDatasets(resolved, step.toolName(), toolMetadata(step.toolName())),
                null, templateMatches));
        }
        return new Projection(List.copyOf(datasets), List.copyOf(excluded));
    }

    public DatasetRelationshipPlan relationshipPlan(
        List<Dataset> datasets,
        DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> protocol
    ) {
        Map<String, Long> counts = datasets.stream().collect(java.util.stream.Collectors.groupingBy(
            Dataset::reference, LinkedHashMap::new, java.util.stream.Collectors.counting()));
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        List<DatasetRelationshipPlan.Dataset> governed = new ArrayList<>();
        for (Dataset dataset : datasets) {
            int occurrence = occurrences.merge(dataset.reference(), 1, Integer::sum);
            String reference = occurrence == 1
                ? dataset.reference() : dataset.reference() + "#occurrence-" + occurrence;
            Map<String, Object> context = protocol.govern(
                reference, dataset.analysisContext(), dataset.records());
            if (counts.getOrDefault(dataset.reference(), 0L) > 1L) {
                context = withOccurrenceRelationship(context, dataset.reference());
            }
            governed.add(new DatasetRelationshipPlan.Dataset(reference, context));
        }
        return DatasetRelationshipPlan.create(governed);
    }

    public SemanticInsightContractProvider.Resolution resolveSemanticInsights(
        GovernanceIsolationScope scope, String datasetReference, Map<String, Object> governedContext,
        Map<String, Object> runtimeAttributes, Map<String, Object> metadata
    ) {
        Map<String, Object> attributes = runtimeAttributes == null ? Map.of() : runtimeAttributes;
        List<String> requestedIds = stringList(firstObject(attributes,
            "semanticInsightContractIds", "semantic_insight_contract_ids"));
        boolean requested = !requestedIds.isEmpty() || booleanValue(firstObject(attributes,
            "semanticInsightRequested", "semantic_insight_requested"));
        Map<String, Object> source = map(governedContext == null ? null : governedContext.get("source"));
        String toolName = firstNonBlank(stringValue(source.get("remoteToolName")),
            firstNonBlank(stringValue(source.get("toolName")), stringValue(source.get("id"))));
        String agentId = firstNonBlank(stringValue(attributes.get("agentId")),
            stringValue(attributes.get("agent_id")));
        String taskType = firstNonBlank(metadata == null ? null : stringValue(metadata.get("taskType")),
            stringValue(attributes.get("taskType")));
        return semanticInsightContractProvider.resolve(new SemanticInsightContractProvider.Request(
            scope == null ? null : scope.tenantId(), agentId, taskType, toolName,
            datasetReference, requested, requestedIds));
    }

    public boolean hasTraceableEvidence(AnalysisSummaryResult summary) {
        if (summary == null || summary.evidence() == null) return false;
        Map<String, Object> evidence = summary.evidence();
        return DataAnalysisSummaryProtocol.EVIDENCE_SCHEMA_VERSION.equals(evidence.get("schemaVersion"))
            && !firstNonBlank(stringValue(evidence.get("evidenceId")), "").isBlank()
            && !firstNonBlank(stringValue(evidence.get("contentSha256")), "").isBlank()
            && booleanValue(evidence.get("rawReplayAvailable"));
    }

    public boolean requiresRawReplay(AnalysisSummaryResult summary) {
        if (!hasTraceableEvidence(summary)) return true;
        Map<String, Object> evidence = summary.evidence();
        return !booleanValue(evidence.get("structured"))
            || booleanValue(evidence.get("rawReplayRecommended"))
            || !booleanValue(evidence.get("factRecordCoverageComplete"))
            || !booleanValue(evidence.get("sourceComplete"))
            || collectionSize(evidence.get("conflicts")) > 0
            || intValue(evidence.get("rejectedFactCount"), 0) > 0;
    }

    public Object resolveEvidenceData(InterpretationPlanRuntime.StepExecution step) {
        if (step == null) return null;
        Object data = step.output();
        if (data instanceof ToolCallBatchResult batch) {
            return toolRuntimeService.resolveBatchOutputForEvidenceReview(batch);
        }
        return step.toolExecution() == null
            ? data : toolRuntimeService.resolveOutputForEvidenceReview(step.toolExecution().output());
    }

    public List<String> valueGroup(Map<String, Object> record, String question) {
        return recordChunkPlanner.valueGroup(record, question);
    }

    private List<Dataset> outputDatasets(Object output, String reference, ToolMetadata metadata) {
        Map<String, Object> rootContext = analysisContextProtocol.adapt(reference, metadata, output);
        List<Dataset> sets = governedProjection(output, reference, rootContext, false);
        if (!sets.isEmpty()) return sets;
        sets = sqlDatasets(output, reference, rootContext);
        if (!sets.isEmpty()) return sets;
        sets = structuredDatasets(output, reference, rootContext);
        if (!sets.isEmpty()) return sets;
        List<Map<String, Object>> records = protocolRecords(output);
        if (!records.isEmpty()) return List.of(new Dataset(reference, rootContext, records));
        sets = externalizedPreview(output, reference, metadata, rootContext);
        if (!sets.isEmpty()) return sets;
        sets = structuredDataProjector.project(output).stream()
            .map(dataset -> new Dataset(reference + dataset.path(), rootContext, dataset.rows())).toList();
        if (!sets.isEmpty()) return deduplicate(sets);
        return governedProjection(output, reference, rootContext, true);
    }

    private List<Dataset> governedProjection(Object output, String reference,
                                             Map<String, Object> rootContext, boolean fallback) {
        int maximumRecordChars = Math.max(1_000, maximumChunkChars - 2_000);
        Map<String, Object> projection = fallback
            ? resultAnalysisProtocol.analysisProjection(reference, output, maximumRecordChars)
            : resultAnalysisProtocol.protocolAnalysisProjection(reference, output, maximumRecordChars);
        Map<String, Object> preservation = metadataOf(
            "sourceSchemaVersion", projection.get("sourceSchemaVersion"),
            "sourcePayloadPreserved", projection.get("sourcePayloadPreserved"),
            "sourcePayloadSha256", projection.get("sourcePayloadSha256"),
            "sourcePayloadChars", projection.get("sourcePayloadChars"),
            "authoritativePayloadMutated", projection.get("authoritativePayloadMutated"),
            "projectionContainsBusinessDataOnly", projection.get("projectionContainsBusinessDataOnly"));
        List<Dataset> sets = new ArrayList<>();
        for (Map<String, Object> dataset : maps(projection.get("datasets"))) {
            List<Map<String, Object>> records = maps(dataset.get("records"));
            if (records.isEmpty()) continue;
            Map<String, Object> context = new LinkedHashMap<>(map(dataset.get("analysisContext")));
            context.put("sourcePayloadPreservation", preservation);
            sets.add(new Dataset(firstNonBlank(stringValue(dataset.get("datasetReference")), reference),
                merge(rootContext, context), records));
        }
        return List.copyOf(sets);
    }

    private List<Dataset> structuredDatasets(Object output, String reference,
                                             Map<String, Object> rootContext) {
        Map<String, Object> root = map(output);
        List<Map<String, Object>> containers = new ArrayList<>();
        if (!root.isEmpty()) containers.add(root);
        for (String key : List.of("data", "result", "payload", "structuredContent", "structured_content")) {
            Map<String, Object> nested = map(root.get(key));
            if (!nested.isEmpty()) containers.add(nested);
        }
        for (Map<String, Object> container : containers) {
            List<Map<String, Object>> declared = maps(container.get("structuredData"));
            if (declared.isEmpty()) continue;
            List<Dataset> sets = new ArrayList<>();
            for (int index = 0; index < declared.size(); index++) {
                Map<String, Object> dataset = declared.get(index);
                List<Map<String, Object>> rows = firstRecords(dataset, "records", "rows", "results");
                if (rows.isEmpty()) continue;
                String datasetReference = firstNonBlank(stringValue(firstNonNull(
                    dataset.get("dataset"), dataset.get("id"))), reference + "#dataset-" + (index + 1));
                sets.add(new Dataset(datasetReference,
                    analysisContextProtocol.adaptDataset(rootContext, dataset), rows));
            }
            if (!sets.isEmpty()) return List.copyOf(sets);
        }
        return List.of();
    }

    private List<Dataset> externalizedPreview(Object output, String reference, ToolMetadata metadata,
                                              Map<String, Object> rootContext) {
        Map<String, Object> root = map(output);
        if (!booleanValue(root.get("outputTruncated"))) return List.of();
        Object preview = root.get("preview");
        if (preview == null || String.valueOf(preview).isBlank()) return List.of();
        Map<String, Object> structured = map(preview);
        if (!structured.isEmpty() && structured != root) {
            List<Dataset> nested = outputDatasets(structured, reference + "#externalized-preview", metadata);
            if (!nested.isEmpty()) return nested;
        }
        return List.of(new Dataset(reference + "#externalized-preview", rootContext,
            List.of(Map.of("stream", "externalized-preview", "sourceComplete", false,
                "content", String.valueOf(preview)))));
    }

    private List<Dataset> sqlDatasets(Object output, String reference, Map<String, Object> rootContext) {
        Map<String, Object> root = map(output);
        String schema = stringValue(root.get("dataSchema"));
        Map<String, Object> data = map(root.get("data"));
        if ("sql_result.v1".equals(schema)) {
            List<Map<String, Object>> rows = annotateRows(maps(data.get("rows")),
                !booleanValue(data.get("possiblyTruncated")), null, null);
            return rows.isEmpty() ? List.of() : List.of(new Dataset(reference, rootContext, rows));
        }
        if (!"sql_script_result.v1".equals(schema)
            && !"database_query_multi_sql_result.v1".equals(schema)
            && !"database_query_workflow_result.v1".equals(schema)) return List.of();
        List<Dataset> sets = new ArrayList<>();
        for (Map<String, Object> result : maps(firstNonNull(data.get("results"), data.get("resultSets")))) {
            Object statement = firstNonNull(result.get("statementIndex"), result.get("executionOrder"));
            Object step = firstNonNull(result.get("stepCode"), result.get("sqlCode"));
            List<Map<String, Object>> rows = annotateRows(maps(result.get("rows")),
                !booleanValue(result.get("possiblyTruncated")), statement, step);
            if (rows.isEmpty()) continue;
            sets.add(new Dataset(reference + "#statement-"
                + firstNonBlank(stringValue(statement), "?"),
                analysisContextProtocol.adaptDataset(rootContext, result), rows));
        }
        return List.copyOf(sets);
    }

    private List<Map<String, Object>> annotateRows(List<Map<String, Object>> rows,
                                                    boolean complete, Object statement, Object step) {
        List<Map<String, Object>> annotated = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> row = new LinkedHashMap<>(rows.get(index));
            if (statement != null) row.put("_statementIndex", statement);
            if (step != null) row.put("_stepCode", step);
            row.put("_resultRowIndex", index + 1);
            row.put("sourceComplete", complete);
            annotated.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(annotated);
    }

    private List<Dataset> deduplicate(List<Dataset> datasets) {
        Map<String, Dataset> unique = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> aliases = new LinkedHashMap<>();
        for (Dataset dataset : datasets) {
            if (dataset == null || dataset.records().isEmpty()) continue;
            String fingerprint = ModelProtocolJson.sha256Hex(ModelProtocolJson.compact(dataset.records()));
            unique.putIfAbsent(fingerprint, dataset);
            aliases.computeIfAbsent(fingerprint, ignored -> new LinkedHashSet<>()).add(dataset.reference());
        }
        List<Dataset> result = new ArrayList<>();
        unique.forEach((fingerprint, dataset) -> {
            List<String> sourceAliases = List.copyOf(aliases.get(fingerprint));
            if (sourceAliases.size() == 1) result.add(dataset);
            else {
                Map<String, Object> context = new LinkedHashMap<>(dataset.analysisContext());
                context.put("sourceAliases", sourceAliases);
                context.put("projectionDeduplicated", true);
                result.add(new Dataset(dataset.reference(), context, dataset.records()));
            }
        });
        return List.copyOf(result);
    }

    private List<Dataset> withTemplateRequirementMatch(List<Dataset> datasets, String templateId,
        Map<String, Map<String, Object>> matches) {
        if (datasets == null || datasets.isEmpty() || matches.isEmpty()) return datasets == null ? List.of() : datasets;
        Map<String, Object> match = templateId == null
            ? (matches.size() == 1 ? matches.values().iterator().next() : Map.of())
            : matches.getOrDefault(templateId.toLowerCase(Locale.ROOT), Map.of());
        if (match.isEmpty()) return datasets;
        String effectiveId = templateId == null
            ? stringList(match.get("selectedTemplateIds")).stream().findFirst().orElse(null) : templateId;
        return datasets.stream().map(dataset -> {
            Map<String, Object> context = new LinkedHashMap<>(dataset.analysisContext());
            context.put(TemplateMatchAnalysis.ANALYSIS_CONTEXT_KEY, match);
            Map<String, Object> current = maps(match.get("templateMatches")).stream()
                .filter(item -> effectiveId != null
                    && effectiveId.equalsIgnoreCase(stringValue(item.get("templateId"))))
                .findFirst().orElse(Map.of());
            if (!current.isEmpty()) {
                List<Map<String, Object>> related = maps(match.get("templateRelationships")).stream()
                    .filter(item -> effectiveId.equalsIgnoreCase(stringValue(item.get("fromTemplateId")))
                        || effectiveId.equalsIgnoreCase(stringValue(item.get("toTemplateId")))).toList();
                context.put(TemplateWorkerAnalysisContext.ANALYSIS_CONTEXT_KEY,
                    new TemplateWorkerAnalysisContext(
                        firstNonBlank(stringValue(match.get("userQuestion")), "unknown user question"),
                        map(match.get("globalAnalysisContext")), map(match.get("analysisIntent")),
                        match, current, related).toMap());
                if (!related.isEmpty()) {
                    List<Object> relationships = new ArrayList<>();
                    Object declared = context.get("relationships");
                    if (declared instanceof Iterable<?> values) values.forEach(relationships::add);
                    else if (declared != null) relationships.add(declared);
                    relationships.addAll(related);
                    context.put("relationships", List.copyOf(relationships));
                }
            }
            return new Dataset(dataset.reference(), context, dataset.records());
        }).toList();
    }

    private Map<String, Map<String, Object>> templateRequirementMatches(
        List<InterpretationPlanRuntime.StepExecution> steps) {
        Map<String, Map<String, Object>> matches = new LinkedHashMap<>();
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (InterpretationPlanRuntime.StepExecution step : steps) {
            if (step == null || step.metadata() == null) continue;
            Map<String, Object> analysis = map(step.metadata().get(TemplateMatchAnalysis.ANALYSIS_CONTEXT_KEY));
            if (!analysis.isEmpty()) unique.putIfAbsent(ModelProtocolJson.sha256Hex(analysis), analysis);
        }
        for (Map<String, Object> match : unique.values()) {
            for (String id : stringList(match.get("selectedTemplateIds"))) {
                matches.put(id.toLowerCase(Locale.ROOT), match);
            }
        }
        return Map.copyOf(matches);
    }

    private Map<String, Object> withOccurrenceRelationship(Map<String, Object> context, String reference) {
        Map<String, Object> result = new LinkedHashMap<>(context == null ? Map.of() : context);
        List<Object> relationships = new ArrayList<>();
        Object declared = result.get("relationships");
        if (declared instanceof Iterable<?> values) values.forEach(relationships::add);
        else if (declared != null) relationships.add(declared);
        relationships.add(Map.of("groupId", "runtime-occurrence:" + reference,
            "relationType", "SAME_LOGICAL_DATASET_OCCURRENCE",
            "authority", "RUNTIME_DATASET_REFERENCE"));
        result.put("relationships", List.copyOf(relationships));
        return Map.copyOf(result);
    }

    private List<Map<String, Object>> protocolRecords(Object output) {
        Map<String, Object> root = map(output);
        if (root.isEmpty()) return List.of();
        List<Map<String, Object>> records = firstRecords(root, "records", "rows", "results");
        if (!records.isEmpty()) return records;
        Map<String, Object> data = map(root.get("data"));
        records = firstRecords(data, "records", "rows", "results");
        if (!records.isEmpty()) return records;
        Map<String, Object> body = map(data.get("body"));
        records = firstRecords(body, "records", "rows", "results");
        if (!records.isEmpty()) return records;
        records = maps(data.get("body"));
        return records.isEmpty() ? firstRecords(map(root.get("result")), "records", "rows", "results") : records;
    }

    private List<Map<String, Object>> firstRecords(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            List<Map<String, Object>> records = maps(source.get(key));
            if (!records.isEmpty()) return records;
        }
        return List.of();
    }

    private ToolMetadata toolMetadata(String name) {
        return name == null || name.isBlank() ? null : toolRegistry.getToolMetadata(name);
    }

    private Map<String, Object> merge(Map<String, Object> root, Map<String, Object> addition) {
        if (addition == null || addition.isEmpty()) return root;
        Map<String, Object> merged = new LinkedHashMap<>(root == null ? Map.of() : root);
        merged.putAll(addition);
        return Map.copyOf(merged);
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Iterable<?> values)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) {
            Map<String, Object> mapped = map(item);
            if (!mapped.isEmpty()) result.add(mapped);
        }
        return List.copyOf(result);
    }

    private int collectionSize(Object value) {
        if (value instanceof Collection<?> values) return values.size();
        if (value instanceof Map<?, ?> values) return values.size();
        return value == null ? 0 : 1;
    }

    public record Dataset(String reference, Map<String, Object> analysisContext,
                          List<Map<String, Object>> records) {
        public Dataset {
            analysisContext = analysisContext == null ? Map.of() : Map.copyOf(analysisContext);
            records = records == null ? List.of() : List.copyOf(records);
        }
    }

    public record Projection(List<Dataset> datasets, List<Map<String, Object>> excludedDatasets) {
        public Projection {
            datasets = datasets == null ? List.of() : List.copyOf(datasets);
            excludedDatasets = excludedDatasets == null ? List.of() : List.copyOf(excludedDatasets);
        }
    }
}
