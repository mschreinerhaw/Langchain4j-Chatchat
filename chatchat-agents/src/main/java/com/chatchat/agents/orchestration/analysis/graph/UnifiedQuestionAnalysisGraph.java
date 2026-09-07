package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.dispatch.AnalysisDispatchCoordinator.Outcome;
import com.chatchat.agents.orchestration.analysis.model.AnalysisDatasetSummary;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.context.ContextTokenEstimator;
import com.chatchat.agents.orchestration.analysis.prompt.AdaptiveBusinessAnalysisPromptSynthesizer;
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
    private final com.chatchat.agents.orchestration.analysis.prompt.DomainAnalysisProfileProvider profiles;
    public UnifiedQuestionAnalysisGraph() { this(com.chatchat.agents.orchestration.analysis.prompt.DomainAnalysisProfileProvider.empty()); }
    public UnifiedQuestionAnalysisGraph(com.chatchat.agents.orchestration.analysis.prompt.DomainAnalysisProfileProvider profiles) {
        this.profiles = profiles;
    }
    private static final String VERSION = "unified_question_analysis.v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_INPUT_TOKENS = 12_000;
    private static final int MAX_EVIDENCE_ROUNDS = 2;
    private static final int INITIAL_EVIDENCE_CHARS = 24_000;
    private static final int REQUESTED_EVIDENCE_CHARS = 10_000;
    private static final ContextTokenEstimator TOKENS = new ContextTokenEstimator();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(UnifiedQuestionAnalysisGraph.class);

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
        var adaptivePrompt = new AdaptiveBusinessAnalysisPromptSynthesizer.Result[1];
        metadata.put("textExtractionModelCalls", 0);
        metadata.put("supplementaryFormulaCount", 0);
        metadata.put("unifiedEvidenceRejectedRequestCount", 0);
        metadata.remove("unifiedAnalysisFindingCount");
        var readAudit = new ArrayList<Map<String, Object>>();
        metadata.put("unifiedEvidenceReadAudit", readAudit);
        var execution = new AnalysisExecutionGraph().execute(List.of(
            new AnalysisExecutionGraph.Step("analysis_planning", () -> {
                plan.put("objective", question);
                plan.put("scope", "ONE_QUESTION_ALL_BOUND_DATASETS");
                plan.put("datasets", sources.stream().map(dataset -> Map.of(
                    "datasetReference", dataset.reference(), "recordCount", dataset.recordCount(),
                    "dataHandle", dataset.handle().descriptor())).toList());
                plan.put("calculationPolicy", "MODEL_SELECTS_ANALYSIS_RUNTIME_EXECUTES_ONLY_DECLARED_OR_RESOLVED_SEMANTICS");
                plan.put("formulaInferencePolicy", "RUNTIME_NEVER_INFERS_AGGREGATION_DENOMINATOR_WEIGHTING_OR_TIME_COMPARISON");
                plan.put("claimBoundaryPolicy", com.chatchat.common.runtime.summary.analysis.contract.AnalysisMethodologyContract
                    .enterpriseDefault().toMap().get("claimBoundaryPolicy"));
                plan.put("partialEvidencePolicy", com.chatchat.common.runtime.summary.analysis.contract.AnalysisMethodologyContract
                    .enterpriseDefault().toMap().get("partialEvidencePolicy"));
                plan.put("analysisAuthorityPolicy", com.chatchat.common.runtime.summary.analysis.contract.AnalysisMethodologyContract
                    .enterpriseDefault().toMap().get("analysisAuthorityPolicy"));
                plan.put("modelReportQualityPolicy", com.chatchat.common.runtime.summary.analysis.contract.AnalysisMethodologyContract
                    .enterpriseDefault().toMap().get("modelReportQualityPolicy"));
                metadata.put("unifiedAnalysisPlan", plan);
                return AnalysisExecutionGraph.Status.READY;
            }),
            new AnalysisExecutionGraph.Step("prompt_synthesis", () -> {
                adaptivePrompt[0] = new AdaptiveBusinessAnalysisPromptSynthesizer(profiles).synthesize(
                    question, sources, model, scope, checkpoints, metadata, guard);
                plan.put("adaptivePromptContractSha256", metadata.get("adaptiveAnalysisPromptSha256"));
                plan.put("adaptivePromptMode", metadata.get("adaptiveAnalysisPromptMode"));
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
                for (int round = 1; round <= MAX_EVIDENCE_ROUNDS; round++) {
                    Object boundedEvidence = evidenceAccess.fitViews(evidence, INITIAL_EVIDENCE_CHARS);
                    Object boundedRequests = evidenceAccess.fitRequestedEvidence(requestedEvidence, REQUESTED_EVIDENCE_CHARS);
                    String prompt = adaptivePrompt[0].compiledPrompt() + "\n"
                        + "Execute unified question analysis (" + VERSION + "). All datasets below belong to one question. "
                        + "Generate findings around the question, not separate dataset reports. Preserve dataset boundaries; never implicitly join tables. "
                        + "You own the analytical choice: decide what the question requires and which supported analysis is meaningful. Runtime does not infer SUM, AVG, ratios, denominators, weights or time comparisons from numeric columns. "
                        + "Select a derived measure only when the supplied semantic contract declares its aggregation, grain, denominator, unit and scope, or request it explicitly as an unverified formula proposal. Runtime executes and audits the declaration; it does not choose the business formula. "
                        + "Interpret Runtime verifiedCalculations; do not invent computed values or units. Refer to other supplied datasets as available, not missing. "
                        + "Return JSON {schemaVersion:'" + VERSION + "',findings:[{datasetReference,claimClass,claim,significance,operation,recordRefs,supportingValues,confidence,caveats,method,inputFields,outputUnit,grain,timeScope,populationScope,semanticBasis,alternativeExplanations}],limitations:[],evidenceRequests:[]}. "
                        + "claimClass is OBSERVED_RETURNED_FACT, AUTHORIZED_DERIVED_MEASURE or CALIBRATED_INFERENCE; confidence is HIGH, MEDIUM or LOW. "
                        + "operation must be one of OBSERVE, AGGREGATE, DERIVE, COMPARE, RANK, TREND, INFER, PROXY; do not invent operation names. "
                        + "recordRefs, caveats, inputFields, semanticBasis and alternativeExplanations are JSON arrays of strings. supportingValues is an array of evidence-bound objects, e.g. [{recordRef:'dataset.records[1]',VALUE:17,previous:null}]. "
                        + "For a model-calculated claim, supportingValues must cite every raw input value from its source records; never cite only the calculated output unless Runtime supplied it in verifiedCalculations. String supportingValues are a compatibility fallback and must be exact JSON field fragments such as '\"VALUE\":17', never field=value prose. "
                        + "Each finding must cite original dataset.records[n] and exact supporting values. A finding belongs to its evidence dataset. "
                        + "Cross-dataset implications must stay qualified unless an authorized relationship and computation supports them. "
                        + "Do not emit SQL or executable instructions. Cover material returned facts relevant to the question; explain unsupported questions in limitations.\n"
                        + "For projected data, selectedRecords carry original recordRef values. Never treat scan coverage as semantic review of every row. "
                        + "You may request more original evidence with evidenceRequests:[{operation:'READ_RECORDS',datasetReference,fromRecord:1,limit:20,fields:['field']}]. "
                        + "Ranges are one-based, maximum 100 rows and four requests per round. No SQL, implicit joins or arbitrary execution. "
                        + "recordCount counts top-level records, not business rows embedded in search results. Never use a nested sample count as a top-level offset. "
                        + "For nestedCollections use {operation:'READ_NESTED_RECORDS',datasetReference,record:1,path:['data','rows'],fromItem:0,limit:20}. Copy the catalog locator exactly; nested indexes are zero-based. The catalog is bounded and not an exhaustive inventory. Cite the parent original recordRef. "
                        + "For omitted semantic contracts or existing calculation results request {operation:'READ_CONTEXT',datasetReference,path:['runtimeAnalysisInputs','verifiedCalculations'],fromItem:0,limit:5}. Context list pages are zero-based, at most 20 items. "
                        + "Use full-scan structural profiles to navigate; only authorized verifiedCalculations support business aggregates. "
                        + "Request new arithmetic with {operation:'CALCULATE',datasetReference,expression:'(a-b)/b',inputs:{a:'runtimeFindingId1',b:'runtimeFindingId2'}}. Inputs must reference existing verified calculations. New formulas require semantic review and cannot be promoted to authorized metrics. "
                        + "When a dataset descriptor declares supportsPushdown=true, you may request {operation:'EXECUTE_OPERATION',datasetReference,analysisOperation:'AGGREGATE',specification:{...}}. You must supply the complete metric, aggregation, grouping, filter, grain, unit, time and population semantics required by the provider; Runtime will execute or reject it and will never complete missing business semantics. "
                        + "For long string fields request {operation:'EXTRACT_TEXT',datasetReference,record:1,field:'text',fromChar:0}. Runtime extracts source-quoted candidates in bounded partitions; nextChar indicates continuation. This is not exhaustive event counting. "
                        + "Analyze all available question-relevant evidence even when coverage is partial. Missing history or fields block only dependent claims, never the entire analysis. "
                        + "Lead with supported findings and their business implications; propose evidence-bound actions where supported. Describe the actual sample and period. Missing values are not zero. "
                        + "Without history, explain current state and supported composition instead of asserting trends. Do not replace available analysis with an indicator framework or only a request for more data. "
                        + "Final findings must address the supported parts of the question across sources. Emit material evidence-bound findings for every non-empty question-relevant dataset; this is a coverage floor, not a one-finding-per-dataset limit. Preserve distinct question-relevant measures, comparisons and exceptions as separate findings where their definitions or evidence differ. A limitation may replace a finding only when those returned fields truly cannot answer any part of the question. Build a question-level conclusion from complementary source findings instead of producing one description per dataset. Use returned observations to characterize the observed-period state and behavior; reserve long-term persistence claims for historical-data limitations. Never describe a returned question-relevant dataset as missing. State residual limitations after supported findings; do not claim complete coverage when evidence is partial. "
                        + "Make the finding set read as one report: each material finding states the answer, observation, interpretation, implication and boundary; keep one value/unit/period/population definition for each metric. "
                        + "Do not make the executive conclusion stronger than the detailed evidence, do not contradict a finding later in limitations, and do not issue an action without the finding that motivates it. Use meaningful prose, remove duplicate findings and expose no runtime IDs. "
                        + "Do not infer intent, motive, strategy, causality or remediation behavior merely because two observations coexist. Describe the observed association and list plausible alternatives when causal evidence is absent. "
                        + "Do not label a value extreme, healthy, excessive, normal, high or low without an explicit comparison baseline in the evidence. Do not translate an observed event into a named domain pattern unless its required evidence and temporal sequence are present. "
                        + "Preserve the producer-declared meaning, measurement basis, period and inclusion/exclusion rules of every field. A request is not a completed outcome, and similarly named measures are not interchangeable. Undeclared definitions or adjustments remain unknown. Use qualified observed-period language for samples and single dates. "
                        + "Before returning JSON, silently verify QUESTION_ANSWERED, METRIC_DEFINITION_STABLE, TIME_SCOPE_STABLE, POPULATION_SCOPE_STABLE, NO_INTERNAL_CONTRADICTION, NO_UNSUPPORTED_CAUSE, NO_SAMPLE_TO_LONG_TERM_EXPANSION, ACTION_TRACES_TO_FINDING and READABLE_WITHOUT_RUNTIME_CONTEXT. "
                        + "Evidence round " + round + "/" + MAX_EVIDENCE_ROUNDS + ". "
                        + (round == MAX_EVIDENCE_ROUNDS
                            ? "No more requests are available; return bounded conclusions and limitations. " : "")
                        + "Requested evidence: " + ModelProtocolJson.compact(boundedRequests) + "\n"
                        + "Question plan: " + ModelProtocolJson.compact(evidenceAccess.fitControlContext(plan, 4_000))
                        + "\nBound evidence: " + ModelProtocolJson.compact(boundedEvidence);
                    var promptSize = TOKENS.estimate(prompt);
                    if (promptSize.tokens() > MAX_INPUT_TOKENS) throw new IllegalStateException(
                        "Unified analysis control context exceeds token budget after bounded projection: " + promptSize.tokens());
                    metadata.put("unifiedAnalysisMaxPromptTokens", Math.max(promptSize.tokens(),
                        ((Number) metadata.getOrDefault("unifiedAnalysisMaxPromptTokens", 0L)).longValue()));
                    String hash = ModelProtocolJson.sha256Hex(Map.of("prompt", prompt,
                        "sourceFingerprint", prepared.fingerprint(), "model", String.valueOf(metadata.getOrDefault("modelName", ""))));
                    String key = VERSION + ":findings:round-" + round;
                    Optional<String> restored = checkpoints.readCheckpoint(scope, key, hash);
                    Map<String, Object> product = restored.map(this::parse).orElse(Map.of());
                    Set<String> known = new LinkedHashSet<>(prepared.sources().keySet());
                    boolean cached = valid(product) && boundFindings(product, known);
                    if (!cached) {
                        if (model == null) throw new IllegalStateException("Unified analysis model is unavailable");
                        guard.run();
                        modelCalls++;
                        allRestored = false;
                        LOG.info("Unified analysis model request partition={} round={} promptChars={} estimatedTokens={} evidenceMode={}",
                            scope.partitionKey(), round, promptSize.chars(), promptSize.tokens(),
                            metadata.getOrDefault("unifiedEvidenceMode", "UNKNOWN"));
                        try {
                            String rawProduct = model.chat(prompt);
                            product = parse(rawProduct);
                            if (!valid(product)) {
                                guard.run();
                                modelCalls++;
                                metadata.put("unifiedAnalysisContractRepairAttempted", true);
                                String repairPrompt = "Repair the previous response into the required JSON protocol. "
                                    + "Preserve its supported analytical meaning; do not add facts or calculations. "
                                    + "Return exactly one JSON object with schemaVersion='" + VERSION + "', "
                                    + "findings as an array, limitations as an array, and evidenceRequests as an array. "
                                    + "Each finding must retain datasetReference, claimClass, claim, operation, recordRefs, "
                                    + "supportingValues, confidence and caveats. JSON only.\nPrevious response:\n"
                                    + boundedRawResponse(rawProduct);
                                product = parse(model.chat(repairPrompt));
                                metadata.put("unifiedAnalysisContractRepairSucceeded", valid(product));
                            }
                        } catch (RuntimeException failure) {
                            guard.run();
                            if (Thread.currentThread().isInterrupted()
                                || failure instanceof java.util.concurrent.CancellationException
                                || failure instanceof com.chatchat.agents.orchestration.model.AgentDeadlineExceededException
                                || maps(generated.get("findings")).isEmpty()) throw failure;
                            // Optional enrichment must not discard existing candidates. They still
                            // pass through the same evidence validation as a successful final round.
                            List<Object> limitations = new ArrayList<>();
                            if (generated.get("limitations") instanceof List<?> list) limitations.addAll(list);
                            limitations.add("Supplementary model analysis failed; retained earlier findings require normal evidence validation. Requested enrichment remains unresolved.");
                            generated.put("limitations", limitations);
                            metadata.put("unifiedAnalysisSupplementFailure", failure.getClass().getSimpleName());
                            metadata.put("unifiedAnalysisModelCalls", modelCalls);
                            metadata.put("unifiedAnalysisFindingCount", maps(generated.get("findings")).size());
                            LOG.warn("Supplementary analysis failed; retaining candidates partition={} round={} failure={}",
                                scope.partitionKey(), round, failure.getClass().getSimpleName());
                            break;
                        }
                        if (!valid(product)) throw new IllegalStateException("Unified analysis returned an invalid finding contract");
                        if (!boundFindings(product, known)) throw new IllegalStateException("Finding cites an unbound dataset");
                    }
                    metadata.put("unifiedAnalysisModelCalls", modelCalls);
                    metadata.put("unifiedAnalysisRestored", allRestored);
                    metadata.put("unifiedAnalysisEvidenceRounds", round);
                    metadata.put("unifiedAnalysisMaxPromptChars", Math.max(prompt.length(),
                        ((Number) metadata.getOrDefault("unifiedAnalysisMaxPromptChars", 0)).intValue()));
                    var requests = maps(product.get("evidenceRequests"));
                    if (!requests.isEmpty() && round < MAX_EVIDENCE_ROUNDS) {
                        int accepted = 0;
                        for (var evidenceRequest : requests) {
                            guard.run();
                            Map<String, Object> audit = new LinkedHashMap<>();
                            audit.put("round", round);
                            for (String auditKey : List.of("operation", "datasetReference", "fromRecord", "limit", "record", "fromChar", "fromItem", "path")) {
                                if (evidenceRequest.containsKey(auditKey)) audit.put(auditKey, boundedAuditValue(evidenceRequest.get(auditKey)));
                            }
                            try {
                                requestedEvidence.addAll(evidenceAccess.read(prepared, List.of(evidenceRequest), guard, model, scope, checkpoints, question, metadata));
                                accepted++;
                                audit.put("status", "ACCEPTED");
                            } catch (IllegalArgumentException rejected) {
                                String reference = String.valueOf(evidenceRequest.get("datasetReference"));
                                var source = prepared.sources().get(reference);
                                requestedEvidence.add(Map.of("status", "REQUEST_REJECTED", "datasetReference", reference,
                                    "reason", String.valueOf(rejected.getMessage()), "availableRecordCount", source == null ? -1 : source.recordCount(),
                                    "instruction", "Original evidence remains available. Correct the request: record indices start at 1 and limit must be 1..100. A rejected read is not an empty dataset."));
                                metadata.put("unifiedEvidenceRejectedRequestCount", ((Number) metadata.getOrDefault("unifiedEvidenceRejectedRequestCount", 0)).intValue() + 1);
                                audit.put("status", "REQUEST_REJECTED");
                                audit.put("datasetBound", source != null);
                                audit.put("availableRecordCount", source == null ? -1 : source.recordCount());
                                audit.put("reason", boundedAuditValue(rejected.getMessage()));
                                LOG.warn("Analysis evidence read rejected partition={} audit={}", scope.partitionKey(), ModelProtocolJson.compact(audit));
                            } finally {
                                audit.putIfAbsent("status", "INTERRUPTED_OR_FAILED");
                                readAudit.add(Map.copyOf(audit));
                            }
                        }
                        if (!cached) checkpoints.checkpoint(scope, key, hash, ModelProtocolJson.compact(product));
                        if (accepted > 0 || maps(product.get("findings")).isEmpty()) {
                            generated.clear();
                            generated.putAll(product);
                            continue;
                        }
                        // Retain usable candidates when every optional read was rejected; validate them normally.
                    }
                    if (!cached) checkpoints.checkpoint(scope, key, hash, ModelProtocolJson.compact(product));
                    generated.putAll(product);
                    metadata.put("unifiedAnalysisFindingCount", maps(product.get("findings")).size());
                    if (prepared.projected() || !requests.isEmpty()) {
                        List<Object> limitations = new ArrayList<>();
                        if (product.get("limitations") instanceof List<?> list) limitations.addAll(list);
                        if (prepared.projected()) limitations.add("Runtime scanned every returned row; model interpretation used bounded evidence views. This does not establish semantic review of every record.");
                        if (!requests.isEmpty()) limitations.add("Supplementary evidence requests remain unresolved; original returned records are still available and existing findings require normal evidence validation.");
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
                        dataset.handle().contentSha256(), false, 0);
                    var result = new AnalysisDatasetSummary(AnalysisDatasetSummary.SCHEMA_VERSION,
                        scope.partitionKey() + ":" + reference + "#validated", summary.content(), "SUCCESS", scope,
                        reference, Math.toIntExact(dataset.recordCount()), false, -1,
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
    private String boundedAuditValue(Object value) {
        String text = String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
        return text.length() <= 256 ? text : text.substring(0, 256) + "…";
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
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) text = text.substring(start, end + 1);
            Map<String, Object> parsed = JSON.readValue(text, new TypeReference<Map<String, Object>>() {});
            if (!parsed.containsKey("schemaVersion") && parsed.get("findings") instanceof List<?>) {
                parsed.put("schemaVersion", VERSION);
            }
            parsed.putIfAbsent("limitations", List.of());
            parsed.putIfAbsent("evidenceRequests", List.of());
            return parsed;
        } catch (Exception invalid) { return Map.of(); }
    }
    private String boundedRawResponse(String raw) {
        String text = raw == null ? "" : raw.trim();
        int limit = 20_000;
        return text.length() <= limit ? text : text.substring(text.length() - limit);
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? list.stream().filter(Map.class::isInstance)
            .map(item -> (Map<String, Object>) item).toList() : List.of();
    }
}
