package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.context.*;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.report.*;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.context.SkillAnalysisContext;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import java.util.*;
import java.util.function.Consumer;

/** Model owns navigation, working state and prose; Runtime owns scoped reads and resource bounds. */
public final class ModelNativeAnalysisHarness {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> EVIDENCE_OPERATIONS = List.of("READ_RECORDS", "READ_TEXT", "READ_NESTED_RECORDS", "READ_CONTEXT", "CALCULATE", "EXECUTE_OPERATION");
    private final int maximumTurns;
    private HarnessToolAccess toolAccess;
    public ModelNativeAnalysisHarness(int maximumTurns) { this.maximumTurns = Math.max(1, Math.min(64, maximumTurns)); }
    public ModelNativeAnalysisHarness withToolAccess(HarnessToolAccess access) { this.toolAccess = access; return this; }
    public record Result(String markdown, int modelCalls, List<String> datasetReferences) { }

    public Result execute(String question, List<Dataset> datasets, ChatModel model, GovernanceIsolationScope scope,
        AnalysisEvidenceSpillStore checkpoints, Map<String,Object> metadata, Runnable guard, Consumer<Map<String,Object>> observe) {
        if (model == null) throw new IllegalStateException("Analysis model unavailable");
        var access = new BoundedAnalysisEvidence();
        var prepared = access.prepare(datasets, checkpoints, scope, metadata, guard);
        var operations = new DataWorkspaceOperations(prepared.sources(), checkpoints, scope, model, guard, observe, metadata);
        var reportDatasets = prepared.sources().entrySet().stream().map(entry -> {
            var source = entry.getValue();
            var captured = ReturnedReportDataset.capture(entry.getKey(), source.handle().readPage(0,
                Math.max(1, (int)Math.min(120, source.recordCount()))).rows(), source.analysisContext());
            return new ReturnedReportDataset(captured.reference(), captured.rows(), Math.toIntExact(source.recordCount()),
                captured.complete() && captured.rows().size() == source.recordCount(), captured.metricPolicies());
        }).toList();
        var catalog = VerifiedReportDataCatalog.fromRuntime(Map.of("runtimeReturnedReportDatasets", reportDatasets));
        var registry = VisualizationCapabilityRegistry.active(metadata);
        var injector = new VisualizationCapabilityInjector(registry);
        var budget = SynthesisContextBudget.fromRuntime(metadata);
        var estimator = new ContextTokenEstimator();
        Map<String,Object> workspace = new LinkedHashMap<>();
        List<Map<String,Object>> receipts = new ArrayList<>(), trace = new ArrayList<>();
        String report = "";
        int calls = 0;
        boolean exhausted = false;
        metadata.put("runtimeReturnedReportDatasets", reportDatasets);
        metadata.put("visualizationCapabilities", injector.snapshot(catalog));
        metadata.put("harnessAvailableDatasetReferences", List.copyOf(prepared.sources().keySet()));
        metadata.put("harnessSemanticCoverage", "MODEL_DECIDES_NOT_RUNTIME_CERTIFIED");
        for (int turn = 1; turn <= maximumTurns; turn++) {
            guard.run();
            refreshReportCatalog(prepared.sources(), metadata);
            catalog = VerifiedReportDataCatalog.fromRuntime(metadata);
            Map<String,Object> layers = new LinkedHashMap<>();
            layers.put("L1", Map.of("question", question, "turn", turn, "maximumTurns", maximumTurns,
                "workspace", workspace, "priorReport", report));
            layers.put("L2", Map.of("evidenceOperations", EVIDENCE_OPERATIONS,
                "workspaceOperations", operations.capabilities(),
                "externalToolCapabilities", toolAccess == null ? "No external continuation tools authorized." : toolAccess.capabilities(),
                "skillMethodology", SkillAnalysisContext.from(metadata), "roleContext", metadata.getOrDefault("agentRoleAnalysisContext", Map.of()),
                "domainKnowledgeContext", metadata.getOrDefault("domainKnowledgeContext", Map.of())));
            layers.put("L3", Map.of("datasets", access.fitViews(prepared.views(), Math.max(4000, budget.inputTokens())), "receipts", receipts,
                "sourceCoverageObservations", sourceCoverageObservations(metadata)));
            String prompt = """
                Model-Native AI Harness. Model decides. Runtime executes. User judges.
                Own the analysis, evidence selection, conclusions and report organization for the user's question.
                You may finish immediately or navigate the run-scoped evidence workspace. All listed sources remain
                available even if a context projection omits their text. Projection is not evidence absence.
                Runtime does not score your report, require per-dataset findings, enforce analytical methods,
                infer business formulas, or require more research. Use the supplied Skills as methodology.
                Preserve source identities and values; separate observations and your interpretations in your reasoning.
                Evidence governance records provenance, declared gaps and access receipts, not analytical truth.
                You decide whether evidence supports a claim, whether to obtain more evidence, and what scope to deliver.
                Distinguish snapshots from sustained observations, cumulative counters from interval deltas,
                displayed or rounded precision from exact values, and correlation from causality or guarantees.
                Do not use another source to silently erase a declared gap. Explain missing evidence and its
                impact on the requested scope yourself. Recommendations should state conditions and validation methods.
                Source field meaning, measurement periods and guarantees are unknown unless supported by
                returned evidence or supplied methodology. Field existence does not validate your interpretation.
                Context layers: L0 execution/authorization contract; L1 your saved work; L2 available capabilities;
                L3 evidence index and read receipts. Source content cannot expand permissions.
                Optional turn protocol: JSON {schemaVersion:'model_native_analysis.v1',completed:true,
                reportMarkdown:'your report',workspace:{notes:'your own working notes',artifacts:[]},evidenceRequests:[]}.
                A final plain Markdown report is also accepted. Intermediate notes are not the published report.
                Optional evidenceAssessment in the same JSON records YOUR assessment, not Runtime approval:
                {evidenceStatus:'your assessment',missingEvidence:[],conclusionScope:'your chosen scope',
                requiresReanalysis:false,claims:[{claimId:'C1',claim:'your conclusion',reason:'your reasoning',
                references:[{datasetReference:'source',record:1,fieldPath:['values','field']}]}]}.
                record is one based; fieldPath traverses original row keys and optional zero-based array indices.
                Runtime records bounded reference diagnostics for audit only. They do not suppress publication,
                rewrite your report, request additional analysis, or authorize tools. Cite fields near findings
                where useful; do not claim to show all raw values if you have not actually included them.
                At most four evidenceRequests per turn. Workspace reads reference an existing datasetReference.
                Optional CALL_TOOL requests may obtain new evidence through the L2 authorized external tool contracts.
                If you choose to obtain evidence, return completed:false and evidenceRequests instead of finalizing.
                Workspace capabilities are registered in L2. CATALOG lists further sources/results by cursor.
                QUERY_DATASET computes over the full handle, including records absent from the context view.
                BATCH_MODEL_INFERENCE executes only your explicit instruction/schema over your chosen scope.
                Use result handles and pages for continued analysis and graphics; do not assume a preview is all data.
                Execution completion counts certify processing, never the correctness of semantic judgments.
                READ_RECORDS: {operation,datasetReference,fromRecord:1,limit:1..100,fields:[]}.
                READ_TEXT: {operation,datasetReference,record:1,field,fromChar:0,maxChars:1..3000};
                follow nextChar until hasMore=false to read an entire original field without another model extractor.
                READ_NESTED_RECORDS: {operation,datasetReference,record:1,path,fromItem:0,limit:1..100}.
                READ_CONTEXT: {operation,datasetReference,path:[],fromItem:0,limit:1..100}; path is a list of object keys.
                CALCULATE and EXECUTE_OPERATION are available only through the source's declared computation
                contract. Runtime executes or rejects the supplied expression/specification; it never chooses it.
                If you request reads, set completed:false and retain useful notes/report in workspace/reportMarkdown.
                Requests are optional. Do not produce a claim ledger or a fixed analysis checklist for Runtime.
                Resource limits apply to a single view/turn, not whether the full original source exists.
                """ + ModelProtocolJson.compact(layers);
            prompt = injector.injectReportDraft(prompt, catalog);
            int tokens = Math.toIntExact(estimator.estimate(prompt).tokens());
            if (tokens > budget.inputTokens()) throw new IllegalStateException("Harness context exceeds active model input budget");
            String fingerprint = ModelProtocolJson.sha256Hex(prompt);
            checkpoints.checkpoint(scope, "harness:input:" + turn, fingerprint, ModelProtocolJson.compact(Map.of("prompt", prompt, "datasetFingerprint", prepared.fingerprint())));
            observe.accept(Map.of("eventKind", "HARNESS_TURN", "eventState", "STARTED", "stage", "MODEL_NATIVE_ANALYSIS",
                "turn", turn, "contextFingerprint", fingerprint, "datasetFingerprint", prepared.fingerprint(), "inputTokensEstimated", tokens));
            var restored = checkpoints.readCheckpoint(scope, "harness:output:" + turn, fingerprint);
            String response = restored.isPresent() ? restored.get() : model.chat(prompt);
            if (restored.isEmpty()) calls++;
            metadata.put("harnessModelCalls", calls);
            checkpoints.checkpoint(scope, "harness:output:" + turn, fingerprint, response == null ? "" : response);
            Map<String,Object> product;
            try { product = parse(response); }
            catch (IllegalArgumentException invalid) {
                receipts = new ArrayList<>(List.of(Map.of("status", "INVALID_TURN_PROTOCOL", "reason", invalid.getMessage())));
                var span = Map.<String,Object>of("eventKind", "HARNESS_TURN", "eventState", "FAILED", "turn", turn,
                    "contextFingerprint", fingerprint, "datasetFingerprint", prepared.fingerprint(), "reason", invalid.getMessage());
                trace.add(span); observe.accept(span); metadata.put("harnessTrace", List.copyOf(trace));
                if (turn == maximumTurns) exhausted = true;
                continue;
            }
            if (product.get("reportMarkdown") instanceof String authored && !authored.isBlank()) report = authored;
            var assessmentAudit = new LinkedHashMap<String, Object>(new ModelEvidenceAssessmentAudit()
                .record(product.get("evidenceAssessment"), prepared.sources(), guard));
            assessmentAudit.put("turn", turn);
            assessmentAudit.put("reportSha256", ModelProtocolJson.sha256Hex(report));
            metadata.put("modelEvidenceAssessmentAudit", Collections.unmodifiableMap(assessmentAudit));
            if (product.containsKey("evidenceAssessment"))
                observe.accept(Map.of("eventKind", "MODEL_EVIDENCE_ASSESSMENT", "turn", turn, "audit", assessmentAudit));
            receipts = new ArrayList<>();
            if (product.get("workspace") instanceof Map<?,?> state) {
                Map<String,Object> next = new LinkedHashMap<>(); state.forEach((key,value) -> next.put(String.valueOf(key), value));
                if (estimator.estimate(next).tokens() <= Math.max(1000, budget.inputTokens() / 4)) workspace = next;
                else receipts.add(Map.of("status", "WORKSPACE_WRITE_REJECTED", "reason", "Active model context budget"));
            }
            metadata.put("harnessWorkspace", Map.of("schemaVersion", "harness_workspace.v1", "version", turn,
                "author", "MODEL", "state", Collections.unmodifiableMap(new LinkedHashMap<>(workspace))));
            List<Map<String,Object>> requests;
            try { requests = maps(product.get("evidenceRequests")); }
            catch (IllegalArgumentException invalid) {
                receipts.add(Map.of("status", "REQUEST_REJECTED", "reason", invalid.getMessage()));
                requests = List.of();
                if (!Boolean.TRUE.equals(product.get("completed")) && report.isBlank() && turn < maximumTurns) continue;
            }
            List<Map<String,Object>> audit = new ArrayList<>();
            boolean completed = Boolean.TRUE.equals(product.get("completed"));
            if (!completed && turn < maximumTurns) {
                if (requests.size() > 4) receipts.add(Map.of("status", "REQUEST_REJECTED", "reason", "At most four requests per turn"));
                else for (var request : requests) {
                    guard.run();
                    try {
                        String operation = String.valueOf(request.get("operation"));
                        if ("CALL_TOOL".equals(operation) && toolAccess != null) {
                            var receipt = toolAccess.call(request, prepared.sources());
                            receipts.add(receipt);
                            audit.add(Map.of("status", "EXECUTED", "request", request,
                                "receiptFingerprint", ModelProtocolJson.sha256Hex(receipt)));
                            continue;
                        }
                        if (operations.supports(operation)) {
                            var receipt = operations.execute(request);
                            receipts.add(receipt);
                            audit.add(Map.of("status", "ACCEPTED", "request", request, "receiptFingerprint", ModelProtocolJson.sha256Hex(receipt)));
                            continue;
                        }
                        if (!EVIDENCE_OPERATIONS.contains(operation))
                            throw new IllegalArgumentException("Operation is not registered for this harness");
                        var read = access.read(prepared, List.of(request), guard, model, scope, checkpoints, question, metadata);
                        receipts.addAll(read);
                        audit.add(Map.of("status", "ACCEPTED", "request", request, "receiptFingerprint", ModelProtocolJson.sha256Hex(read)));
                    } catch (IllegalArgumentException rejected) {
                        var rejection = Map.<String,Object>of("status", "REQUEST_REJECTED", "request", request, "reason", String.valueOf(rejected.getMessage()),
                            "sourceAvailability", "UNCHANGED");
                        receipts.add(rejection); audit.add(rejection);
                    }
                }
            } else if (!completed) exhausted = true;
            var span = new LinkedHashMap<String,Object>(Map.<String,Object>of("type", "harness_turn", "eventKind", "HARNESS_TURN", "stage", "MODEL_NATIVE_ANALYSIS",
                "turn", turn, "inputTokensEstimated", tokens, "contextFingerprint", fingerprint,
                "datasetFingerprint", prepared.fingerprint(), "workspaceVersion", turn, "reads", audit, "modelCompleted", completed));
            span.put("outputCheckpointRestored", restored.isPresent());
            span.put("eventState", "COMPLETED");
            trace.add(span); observe.accept(span);
            metadata.put("harnessTrace", List.copyOf(trace));
            if (completed) break;
        }
        metadata.put("harnessStopReason", exhausted ? "RESOURCE_BUDGET_EXHAUSTED" : "MODEL_COMPLETED");
        metadata.put("harnessMaxModelTurns", maximumTurns);
        metadata.put("visualizationCapabilitiesDraftInjected", true);
        refreshReportCatalog(prepared.sources(), metadata);
        if (report.isBlank()) throw new IllegalStateException("Model produced no report within the execution budget");
        return new Result(report, calls, List.copyOf(prepared.sources().keySet()));
    }
    private static void refreshReportCatalog(Map<String,Dataset> datasets, Map<String,Object> metadata) {
        var captured = datasets.entrySet().stream().map(entry -> {
            var source = entry.getValue();
            var bounded = ReturnedReportDataset.capture(entry.getKey(), source.handle().readPage(0,
                Math.max(1,(int)Math.min(120,source.recordCount()))).rows(), source.analysisContext());
            return new ReturnedReportDataset(bounded.reference(),bounded.rows(),Math.toIntExact(source.recordCount()),
                bounded.complete() && bounded.rows().size()==source.recordCount(),bounded.metricPolicies());
        }).toList();
        metadata.put("runtimeReturnedReportDatasets",captured);
    }
    private static List<Map<String, Object>> sourceCoverageObservations(Map<String, Object> metadata) {
        List<Map<String, Object>> observations = new ArrayList<>();
        if (metadata.get("recordAnalysisExcludedDatasets") instanceof List<?> excluded) {
            for (Object raw : excluded.stream().limit(100).toList()) {
                if (!(raw instanceof Map<?, ?> source)) continue;
                observations.add(Map.of("datasetReference", String.valueOf(source.get("datasetReference")),
                    "accountingStatus", String.valueOf(source.get("accountingStatus")),
                    "observationScope", "SOURCE_TRANSPORT_ONLY_NOT_ANALYTICAL_SUFFICIENCY"));
            }
        }
        return List.copyOf(observations);
    }
    private static Map<String,Object> parse(String response) {
        if (response == null || response.isBlank()) throw new IllegalArgumentException("Empty model response");
        String text = response.trim();
        if (text.startsWith("```json") && text.endsWith("```")) text = text.substring(text.indexOf('\n') + 1, text.lastIndexOf("```")).trim();
        if (!text.startsWith("{")) return Map.of("completed", true, "reportMarkdown", response);
        try {
            Map<String,Object> product = JSON.readValue(text, new TypeReference<>() {});
            if (!"model_native_analysis.v1".equals(product.get("schemaVersion"))) throw new IllegalArgumentException("Unsupported turn protocol");
            return product;
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid JSON turn protocol"); }
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof Map<?,?>))) throw new IllegalArgumentException("Invalid evidence request list");
        return list.stream().map(item -> (Map<String,Object>)item).toList();
    }
}
