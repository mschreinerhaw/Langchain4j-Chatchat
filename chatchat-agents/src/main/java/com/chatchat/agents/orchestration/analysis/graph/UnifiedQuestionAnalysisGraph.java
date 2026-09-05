package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.dispatch.AnalysisDispatchCoordinator.Outcome;
import com.chatchat.agents.orchestration.analysis.model.AnalysisDatasetSummary;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.analysis.spi.DataAnalysisSummaryProtocol;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import java.util.*;
import java.util.function.Supplier;

/** Question-scoped interpretation with bounded evidence reads; no per-dataset model reports. */
public final class UnifiedQuestionAnalysisGraph {
    private static final String VERSION = "unified_question_analysis.v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_INPUT_CHARS = 160_000;

    public Map<String, Outcome> execute(String question, List<Dataset> sources,
        Supplier<List<Dataset>> computation, ChatModel model, GovernanceIsolationScope scope,
        DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> protocol,
        AnalysisEvidenceSpillStore checkpoints, Map<String, Object> metadata, Runnable guard) {
        var bound = new ArrayList<Dataset>();
        var plan = new LinkedHashMap<String, Object>();
        var generated = new LinkedHashMap<String, Object>();
        var outcomes = new LinkedHashMap<String, Outcome>();
        var evidenceAccess = new BoundedAnalysisEvidence();
        var evidenceView = new BoundedAnalysisEvidence.Prepared[1];
        metadata.put("textExtractionModelCalls", 0);
        metadata.put("supplementaryFormulaCount", 0);
        var execution = new AnalysisExecutionGraph().execute(List.of(
            new AnalysisExecutionGraph.Step("analysis_planning", () -> {
                plan.put("objective", question);
                plan.put("scope", "ONE_QUESTION_ALL_BOUND_DATASETS");
                plan.put("datasets", sources.stream().map(dataset -> Map.of(
                    "datasetReference", dataset.reference(), "recordCount", dataset.records().size())).toList());
                plan.put("calculationPolicy", "EXECUTE_ONLY_RESOLVED_SEMANTIC_CONTRACTS");
                metadata.put("unifiedAnalysisPlan", plan);
                return AnalysisExecutionGraph.Status.READY;
            }),
            new AnalysisExecutionGraph.Step("data_computation", () -> {
                bound.addAll(computation.get());
                return AnalysisExecutionGraph.Status.READY;
            }),
            new AnalysisExecutionGraph.Step("evidence_projection", () -> {
                evidenceView[0] = evidenceAccess.prepare(bound, checkpoints, scope, metadata, guard);
                return AnalysisExecutionGraph.Status.READY;
            }),
            new AnalysisExecutionGraph.Step("generate_findings", () -> {
                var prepared = evidenceView[0];
                List<Map<String, Object>> evidence = prepared.views();
                List<Map<String, Object>> requestedEvidence = new ArrayList<>();
                int modelCalls = 0;
                boolean allRestored = true;
                for (int round = 1; round <= 3; round++) {
                    String prompt = "Execute unified question analysis (" + VERSION + "). All datasets below belong to one question. "
                        + "Generate findings around the question, not separate dataset reports. Preserve dataset boundaries; never implicitly join tables. "
                        + "Interpret Runtime verifiedCalculations; do not invent computed values or units. Refer to other supplied datasets as available, not missing. "
                        + "Return JSON {schemaVersion:'" + VERSION + "',findings:[{datasetReference,claimClass,claim,significance,operation,recordRefs,supportingValues,confidence,caveats,method,inputFields,outputUnit,grain,timeScope,populationScope,semanticBasis,alternativeExplanations}],limitations:[],evidenceRequests:[]}. "
                        + "claimClass is OBSERVED_RETURNED_FACT, AUTHORIZED_DERIVED_MEASURE or CALIBRATED_INFERENCE; confidence is HIGH, MEDIUM or LOW. "
                        + "Each finding must cite original dataset.records[n] and exact supporting values. A finding belongs to its evidence dataset. "
                        + "Cross-dataset implications must stay qualified unless an authorized relationship and computation supports them. "
                        + "Do not emit SQL or executable instructions. Cover material returned facts relevant to the question; explain unsupported questions in limitations.\n"
                        + "For projected data, selectedRecords carry original recordRef values. Never treat scan coverage as semantic review of every row. "
                        + "You may request more original evidence with evidenceRequests:[{operation:'READ_RECORDS',datasetReference,fromRecord:1,limit:20,fields:['field']}]. "
                        + "Ranges are one-based, maximum 100 rows and four requests per round. No SQL, implicit joins or arbitrary execution. "
                        + "For omitted semantic contracts or existing calculation results request {operation:'READ_CONTEXT',datasetReference,path:['runtimeAnalysisInputs','verifiedCalculations'],fromItem:0,limit:5}. Context list pages are zero-based, at most 20 items. "
                        + "Use full-scan structural profiles to navigate; only authorized verifiedCalculations support business aggregates. "
                        + "Request new arithmetic with {operation:'CALCULATE',datasetReference,expression:'(a-b)/b',inputs:{a:'runtimeFindingId1',b:'runtimeFindingId2'}}. Inputs must reference existing verified calculations. New formulas require semantic review and cannot be promoted to authorized metrics. "
                        + "For long string fields request {operation:'EXTRACT_TEXT',datasetReference,record:1,field:'text',fromChar:0}. Runtime extracts source-quoted candidates in bounded partitions; nextChar indicates continuation. This is not exhaustive event counting. "
                        + "Final findings must cover the question across sources. If evidence remains insufficient state explicit limitations. "
                        + "Evidence round " + round + "/3. " + (round == 3 ? "No more requests are available; return bounded conclusions and limitations. " : "")
                        + "Requested evidence: " + ModelProtocolJson.compact(requestedEvidence) + "\n"
                        + "Question plan: " + ModelProtocolJson.compact(plan) + "\nBound evidence: " + ModelProtocolJson.compact(evidence);
                    if (prompt.length() > MAX_INPUT_CHARS) throw new IllegalStateException(
                        "Unified analysis control context exceeds budget after bounded projection");
                    String hash = ModelProtocolJson.sha256Hex(Map.of("prompt", prompt,
                        "sourceFingerprint", prepared.fingerprint(), "model", String.valueOf(metadata.getOrDefault("modelName", ""))));
                    String key = VERSION + ":findings:round-" + round;
                    Optional<String> restored = checkpoints.readCheckpoint(scope, key, hash);
                    Map<String, Object> product = restored.map(this::parse).orElse(Map.of());
                    Set<String> known = new LinkedHashSet<>();
                    evidence.forEach(item -> known.add((String) item.get("datasetReference")));
                    boolean cached = valid(product) && boundFindings(product, known);
                    if (!cached) {
                        if (model == null) throw new IllegalStateException("Unified analysis model is unavailable");
                        guard.run();
                        product = parse(model.chat(prompt));
                        modelCalls++;
                        allRestored = false;
                        if (!valid(product)) throw new IllegalStateException("Unified analysis returned an invalid finding contract");
                        if (!boundFindings(product, known)) throw new IllegalStateException("Finding cites an unbound dataset");
                    }
                    metadata.put("unifiedAnalysisModelCalls", modelCalls);
                    metadata.put("unifiedAnalysisRestored", allRestored);
                    metadata.put("unifiedAnalysisEvidenceRounds", round);
                    metadata.put("unifiedAnalysisMaxPromptChars", Math.max(prompt.length(),
                        ((Number) metadata.getOrDefault("unifiedAnalysisMaxPromptChars", 0)).intValue()));
                    var requests = maps(product.get("evidenceRequests"));
                    if (!requests.isEmpty() && round < 3) {
                        requestedEvidence.addAll(evidenceAccess.read(prepared, requests, guard, model, scope, checkpoints, question, metadata));
                        if (!cached) checkpoints.checkpoint(scope, key, hash, ModelProtocolJson.compact(product));
                        continue;
                    }
                    if (!cached) checkpoints.checkpoint(scope, key, hash, ModelProtocolJson.compact(product));
                    generated.putAll(product);
                    if (prepared.projected() || !requests.isEmpty()) {
                        List<Object> limitations = new ArrayList<>();
                        if (product.get("limitations") instanceof List<?> list) limitations.addAll(list);
                        if (prepared.projected()) limitations.add("Runtime scanned every returned row; model interpretation used bounded evidence views. This does not establish semantic review of every record.");
                        if (!requests.isEmpty()) limitations.add("Evidence request budget exhausted; unresolved requests remain unsupported.");
                        generated.put("limitations", limitations);
                    }
                    break;
                }
                return AnalysisExecutionGraph.Status.READY;
            }),
            new AnalysisExecutionGraph.Step("validate_findings", () -> {
                Set<String> known = new LinkedHashSet<>();
                Map<String, Integer> occurrences = new LinkedHashMap<>();
                for (Dataset dataset : bound) known.add(unique(dataset.reference(), occurrences));
                for (Map<String, Object> finding : maps(generated.get("findings"))) {
                    if (!known.contains(finding.get("datasetReference")))
                        throw new IllegalStateException("Finding cites an unbound dataset");
                }
                occurrences.clear();
                List<String> datasetsWithoutFindings = new ArrayList<>();
                for (Dataset dataset : bound) {
                    guard.run();
                    String reference = unique(dataset.reference(), occurrences);
                    var findings = maps(generated.get("findings")).stream()
                        .filter(finding -> reference.equals(finding.get("datasetReference"))).toList();
                    if (findings.isEmpty()) datasetsWithoutFindings.add(reference);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("summary", findings.isEmpty() ? "当前问题下，该数据集尚无通过分析产物支持的结论。"
                        : String.join("\n", findings.stream().map(f -> String.valueOf(f.get("claim"))).toList()));
                    payload.put("insights", findings);
                    payload.put("facts", findings.stream()
                        .filter(finding -> "OBSERVED_RETURNED_FACT".equals(finding.get("claimClass")))
                        .map(finding -> Map.of("claim", finding.getOrDefault("claim", ""),
                            "recordRefs", finding.getOrDefault("recordRefs", List.of()),
                            "exactValues", finding.getOrDefault("supportingValues", List.of()))).toList());
                    payload.put("limitations", generated.getOrDefault("limitations", List.of()));
                    payload.put("demandAnalysis", Map.of("decisionGoal", question,
                        "answeredQuestions", findings.isEmpty() ? List.of() : List.of(question),
                        "openQuestions", findings.isEmpty() ? List.of("No validated finding produced for this dataset") : List.of()));
                    payload.put("metricAssociations", List.of());
                    var context = protocol.govern(reference, dataset.analysisContext(), dataset.records());
                    var position = protocol.position(reference, 1, 1, 1, dataset.records().size(), dataset.records().size());
                    var summary = protocol.validateProduct(scope, position, context, dataset.records(), question,
                        ModelProtocolJson.compact(payload));
                    var chunk = new AnalysisDatasetSummary.ChunkResult(summary, null,
                        ModelProtocolJson.sha256Hex(dataset.records()), false, 0);
                    var result = new AnalysisDatasetSummary(AnalysisDatasetSummary.SCHEMA_VERSION,
                        scope.partitionKey() + ":" + reference + "#validated", summary.content(), "SUCCESS", scope,
                        reference, dataset.records().size(), false, ModelProtocolJson.compact(dataset.records()).length(),
                        List.of(chunk), summary, 0, 0, 0, 0, 0, 0, false, List.of(summary.resultId()),
                        Map.of("analysisMode", VERSION, "modelTaskCount", 0));
                    outcomes.put(reference, new Outcome(result, "SUCCESS", "unified-validation", 0, ""));
                }
                metadata.put("unifiedAnalysisDatasetsWithoutFindings", List.copyOf(datasetsWithoutFindings));
                return datasetsWithoutFindings.isEmpty() && maps(generated.get("findings")).size() > 0
                    && generated.getOrDefault("limitations", List.of()).equals(List.of())
                    ? AnalysisExecutionGraph.Status.COMPLETED : AnalysisExecutionGraph.Status.COMPLETED_WITH_LIMITATIONS;
            })), guard);
        metadata.put("unifiedAnalysisGraphNodes", execution.nodes());
        metadata.put("unifiedAnalysisStatus", execution.status().name());
        return Map.copyOf(outcomes);
    }

    private String unique(String reference, Map<String, Integer> occurrences) {
        int count = occurrences.merge(reference, 1, Integer::sum);
        return count == 1 ? reference : reference + "#occurrence-" + count;
    }
    private boolean valid(Map<String, Object> value) {
        return value != null && VERSION.equals(value.get("schemaVersion")) && value.get("findings") instanceof List<?> findings
            && findings.stream().allMatch(Map.class::isInstance)
            && (!value.containsKey("evidenceRequests") || value.get("evidenceRequests") instanceof List<?> requests
                && requests.size() <= 4 && requests.stream().allMatch(Map.class::isInstance));
    }
    private boolean boundFindings(Map<String, Object> product, Set<String> known) {
        return maps(product.get("findings")).stream().allMatch(f -> known.contains(f.get("datasetReference")));
    }
    private Map<String, Object> parse(String raw) {
        try {
            String text = raw == null ? "" : raw.trim();
            if (text.startsWith("```")) text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            return JSON.readValue(text, new TypeReference<Map<String, Object>>() {});
        } catch (Exception invalid) { return Map.of(); }
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? list.stream().filter(Map.class::isInstance)
            .map(item -> (Map<String, Object>) item).toList() : List.of();
    }
}
