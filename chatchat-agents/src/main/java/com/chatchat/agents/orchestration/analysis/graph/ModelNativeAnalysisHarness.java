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
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.*;
import com.chatchat.common.runtime.analysis.execution.ModelAnalysisIntent;
import java.util.function.Consumer;

/** Model owns navigation, working state and prose; Runtime owns scoped reads and resource bounds. */
public final class ModelNativeAnalysisHarness {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final int maximumTurns;
    private HarnessToolAccess toolAccess;
    private AnalysisCodingPort coding;
    private com.chatchat.agents.orchestration.analysis.governance.AnalysisCoverageCoordinator.Request codingContext;
    public ModelNativeAnalysisHarness withCoding(AnalysisCodingPort port,
            com.chatchat.agents.orchestration.analysis.governance.AnalysisCoverageCoordinator.Request context) {
        this.coding = port; this.codingContext = context; return this;
    }
    public ModelNativeAnalysisHarness(int maximumTurns) { this.maximumTurns = Math.max(1, Math.min(64, maximumTurns)); }
    public ModelNativeAnalysisHarness withToolAccess(HarnessToolAccess access) { this.toolAccess = access; return this; }
    public record Result(String markdown, int modelCalls, List<String> datasetReferences) { }

    public Result execute(String question, List<Dataset> datasets, ChatModel model, GovernanceIsolationScope scope,
        AnalysisEvidenceSpillStore checkpoints, Map<String,Object> metadata, Runnable guard, Consumer<Map<String,Object>> observe) {
        try {
            return executeInternal(question, datasets, model, scope, checkpoints, metadata, guard, observe);
        } catch (java.util.concurrent.CancellationException stopped) {
            if (ModelAnalysisIntent.active(metadata)) {
                metadata.put("executionState", "STOPPED");
                metadata.put("executionStopReason", stopped instanceof com.chatchat.agents.orchestration.model.AgentDeadlineExceededException
                    ? "TIMEOUT" : "CANCELLED");
                metadata.put("publicationState", "NOT_REQUESTED");
            }
            throw stopped;
        }
    }
    private Result executeInternal(String question, List<Dataset> datasets, ChatModel model, GovernanceIsolationScope scope,
        AnalysisEvidenceSpillStore checkpoints, Map<String,Object> metadata, Runnable guard, Consumer<Map<String,Object>> observe) {
        if (model == null) throw new IllegalStateException("Analysis model unavailable");
        boolean pythonRequired = "PYTHON_REQUIRED".equals(metadata.get("analysisExecutionPolicy"));
        if (pythonRequired && coding == null)
            throw new IllegalStateException("PYTHON_ANALYSIS_UNAVAILABLE: data analysis requires an enabled Coding Agent and Python Sandbox");
        Set<String> rawReferences = new HashSet<>();
        datasets.forEach(dataset -> rawReferences.add(dataset.reference()));
        Set<String> admittedResults = new HashSet<>();
        if (coding != null && (codingContext == null || !Objects.equals(scope, codingContext.isolationScope())
                || model != codingContext.model() || metadata != codingContext.metadata()))
            throw new SecurityException("Coding context must belong to the executing analysis scope, model and state");
        var access = new BoundedAnalysisEvidence();
        var prepared = access.prepare(datasets, checkpoints, scope, metadata, guard, coding != null);
        rawReferences.addAll(prepared.sources().keySet());
        var operations = new DataWorkspaceOperations(prepared.sources(), checkpoints, scope, model, guard, observe, metadata);
        var reportDatasets = prepared.sources().entrySet().stream()
            .filter(entry -> !pythonRequired || !rawReferences.contains(entry.getKey())).map(entry -> {
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
        var codingTasks = new LinkedHashMap<String, Map<String,Object>>(16, 0.75f, true);
        String report = "";
        int calls = 0;
        boolean v2 = !"model_native_analysis.v1".equals(metadata.get("modelAnalysisProtocol"));
        if (v2) metadata.put("modelAnalysisProtocol", ModelAnalysisIntent.VERSION);
        var plans = new HarnessPlanState(metadata, checkpoints, scope, observe, question);
        boolean exhausted = false;
        List<Map<String, Object>> assessmentHistory = new ArrayList<>();
        var sourceViews = new LinkedHashMap<String, Map<String, Object>>();
        for (var view : prepared.views()) sourceViews.put(String.valueOf(view.get("datasetReference")), view);
        metadata.put("runtimeReturnedReportDatasets", reportDatasets);
        metadata.put("visualizationCapabilities", injector.snapshot(catalog));
        metadata.put("harnessAvailableDatasetReferences", List.copyOf(prepared.sources().keySet()));
        metadata.put("harnessSemanticCoverage", "MODEL_DECIDES_NOT_RUNTIME_CERTIFIED");
        for (int turn = 1; turn <= maximumTurns; turn++) {
            guard.run();
            refreshReportCatalog(reportSources(prepared.sources(), pythonRequired, admittedResults), metadata);
            // New tool results remain indexed after receipts are replaced by protocol
            // feedback. A model should never have to rediscover an acquired source.
            var newlyAcquired = prepared.sources().entrySet().stream()
                .filter(entry -> !sourceViews.containsKey(entry.getKey())).map(Map.Entry::getValue).toList();
            if (!newlyAcquired.isEmpty()) {
                var indexed = access.prepare(newlyAcquired, checkpoints, scope, metadata, guard, !pythonRequired && coding != null);
                for (var view : indexed.views()) sourceViews.put(String.valueOf(view.get("datasetReference")), view);
            }
            catalog = VerifiedReportDataCatalog.fromRuntime(metadata);
            Map<String,Object> layers = new LinkedHashMap<>();
            layers.put("L1", Map.of("question", question, "turn", turn, "maximumTurns", maximumTurns,
                "workspace", workspace, "priorReport", report,
                "submittedPlan", plans.view(),
                "codingTasks", codingTasks.values().stream().skip(Math.max(0, codingTasks.size() - 8)).toList(),
                "reportSha256", ModelProtocolJson.sha256Hex(report), "evidenceSnapshotRef", snapshot(prepared.sources())));
            layers.put("L2", Map.of("evidenceOperations", BoundedAnalysisEvidence.capabilities(), "planOperations", plans.capabilities(),
                "workspaceOperations", pythonRequired ? Map.of("CATALOG", operations.capabilities().get("CATALOG")) : operations.capabilities(),
                "codingOperations", coding == null ? Map.of() : coding.capabilities(),
                "externalToolCapabilities", "No external tool dispatch: only delivered template data and its declared data operations are available.",
                "roleContext", metadata.getOrDefault("agentRoleAnalysisContext", Map.of())));
            layers.put("methods", Map.of("skillMethodology", analysisMethods(metadata),
                "domainKnowledgeContext", metadata.getOrDefault("domainKnowledgeContext", Map.of())));
            layers.put("L3", Map.of("datasets", access.fitViews(new ArrayList<>(sourceViews.values()), Math.max(4000, budget.inputTokens())), "receipts", receipts,
                "sourceCoverageObservations", sourceCoverageObservations(metadata)));
            String instructions = ModelAnalysisPrompt.CORE
                + (pythonRequired ? "\nEXECUTION POLICY: PYTHON_REQUIRED overrides optional Coding instructions. Raw rows are execution-only. "
                    + "Use RUN_CODING for all data calculations, diagnostics and transformations. Do not read raw rows or use batch model inference. "
                    + "Source profiles, including numeric summaries and record counts, are navigation/quality metadata, not report-ready analytical results. "
                    + "Obtain reported quantities from admitted Coding outputs; record or text-fragment counts are not domain entity counts. "
                    + "Related calculations may share one Coding task over authorized inputs; each distinct request has an independent tool budget. "
                    + "Never replace an unresolved execution with a new request to bypass its stop or reconciliation state. "
                    + "READ_* operations may inspect only admitted Coding result datasets. COMPLETE requires an admitted result. "
                    + "If execution fails or is pending, report limitations with PARTIAL_COMPLETE; never invent computed values.\n" : "")
                + (coding == null ? "" : "For multi-record calculations, prefer RUN_CODING over reading all source rows into the model. "
                    + "Use the dataset catalog, structural profile and at most bounded samples to plan; Python processes the complete authorized snapshot. "
                    + "Synthesize from admitted result references and validation receipts, not from an unverified model calculation.\n")
                + ModelAnalysisPrompt.protocol(v2);
            String contracts = ModelProtocolJson.compact(Map.of("L2", layers.remove("L2")));
            String context = ModelProtocolJson.compact(layers);
            String system = injector.injectReportDraft(instructions + contracts, catalog,
                v2 ? "content" : "reportMarkdown");
            String prompt = system + "\n\n" + context;
            metadata.put("harnessPromptSize", Map.of("instructionsChars", instructions.length(),
                "layersChars", context.length() + contracts.length(), "fullChars", prompt.length(),
                "systemChars", system.length(), "userChars", context.length(),
                "utf8Bytes", prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
            int tokens = Math.toIntExact(estimator.estimate(prompt).tokens());
            if (tokens > budget.inputTokens()) {
                exhausted = true;
                break;
            }
            String fingerprint = ModelProtocolJson.sha256Hex(prompt);
            checkpoints.checkpoint(scope, "harness:input:" + turn, fingerprint, ModelProtocolJson.compact(Map.of("prompt", prompt, "datasetFingerprint", prepared.fingerprint())));
            observe.accept(Map.of("eventKind", "HARNESS_TURN", "eventState", "STARTED", "stage", "MODEL_NATIVE_ANALYSIS",
                "turn", turn, "contextFingerprint", fingerprint, "datasetFingerprint", prepared.fingerprint(), "inputTokensEstimated", tokens,
                "promptSize", metadata.get("harnessPromptSize")));
            var restored = checkpoints.readCheckpoint(scope, "harness:output:" + turn, fingerprint);
            String response;
            try {
                if (restored.isPresent()) response = restored.get();
                else if (Boolean.TRUE.equals(metadata.get("harnessLegacyTextTransport"))) response = model.chat(prompt);
                else {
                    var returned = model.chat(ChatRequest.builder().messages(SystemMessage.from(system), UserMessage.from(context)).build());
                    response = returned == null || returned.aiMessage() == null ? null : returned.aiMessage().text();
                }
            } catch (com.chatchat.agents.orchestration.model.AgentBudgetExceededException limited) {
                exhausted = true;
                metadata.put("harnessBudgetFeedback", limited.getClass().getSimpleName());
                break;
            }
            if (restored.isEmpty()) calls++;
            metadata.put("harnessModelCalls", calls);
            checkpoints.checkpoint(scope, "harness:output:" + turn, fingerprint, response == null ? "" : response);
            Map<String,Object> product;
            String candidateReport;
            Map<String,Object> acceptedIntent = new LinkedHashMap<>(metadata);
            try {
                product = parse(response, v2);
                boolean nextV2 = ModelAnalysisIntent.VERSION.equals(product.get("schemaVersion"));
                if (v2 && !nextV2) throw new IllegalArgumentException("v2 cannot silently downgrade to legacy publication");
                if (!v2 && nextV2) throw new IllegalArgumentException("PROTOCOL_MISMATCH: this request uses model_native_analysis.v1");
                if (v2) normalizeLegacyIntent(product, metadata);
                validateIntent(product, v2);
                if (pythonRequired && admittedResults.isEmpty()
                        && (v2 ? product.get("decision") instanceof Map<?,?> decision && "COMPLETE".equals(decision.get("action"))
                               : Boolean.TRUE.equals(product.get("completed"))))
                    throw new IllegalArgumentException("PYTHON_RESULT_REQUIRED: use RUN_CODING and an admitted result before COMPLETE");
                Object authored = product.get(v2 ? "content" : "reportMarkdown");
                if (authored != null && !(authored instanceof String)) throw new IllegalArgumentException("INVALID_CONTENT: content must be a string");
                candidateReport = authored instanceof String value && !value.isBlank() ? value : report;
                if (pythonRequired && !admittedResults.isEmpty() && product.get("decision") instanceof Map<?,?> decision
                        && Set.of("COMPLETE","PARTIAL_COMPLETE").contains(String.valueOf(decision.get("action")))) {
                    var review=new ComputedReportAdmission().review(question,candidateReport,prepared.sources(),admittedResults,
                        model,scope,checkpoints,guard,trace,prepared.views());
                    calls+=review.modelCalls();metadata.put("harnessModelCalls",calls);
                    metadata.put("finalReportSemanticAdmission",review.value());
                    observe.accept(Map.of("eventKind","FINAL_REPORT_SEMANTIC_REVIEW","stage","FINAL_REPORT_SEMANTIC_REVIEW",
                        "eventState",review.value().get("status"),"turn",turn,"review",review.value()));
                    if(!"SUPPORTED".equals(review.value().get("status")))
                        throw new IllegalArgumentException("FINAL_REPORT_EVIDENCE_UNSUPPORTED: "+ModelProtocolJson.compact(review.value())
                            +". Remove unsupported claims or use RUN_CODING with an output schema exposing the missing computations. "
                            +"Do not repeatedly resubmit rejected numerical claims from source profiles or worker prose.");
                }
                if (product.get("workspace") != null && !(product.get("workspace") instanceof Map<?,?>))
                    throw new IllegalArgumentException("INVALID_WORKSPACE: public state must be an object");
                if (v2) recordIntent(product, candidateReport, snapshot(prepared.sources()), acceptedIntent);
                else if (Boolean.TRUE.equals(product.get("completed")) && candidateReport.isBlank())
                    throw new IllegalArgumentException("EMPTY_FINAL_CONTENT: provide your answer");
                var proposedPlan = plans.prepare(product);
                plans.commit(proposedPlan);

            }
            catch (com.chatchat.agents.orchestration.model.AgentBudgetExceededException limited) {
                exhausted=true;metadata.put("harnessBudgetFeedback",limited.getClass().getSimpleName());break;
            }
            catch (IllegalArgumentException invalid) {
                // Protocol correction must not erase the execution facts that prompted it.
                receipts = new ArrayList<>(receipts.stream()
                    .filter(receipt -> !"INVALID_TURN_PROTOCOL".equals(receipt.get("status"))).toList());
                receipts.add(Map.of("status", "INVALID_TURN_PROTOCOL", "reason", invalid.getMessage()));
                var span = Map.<String,Object>of("eventKind", "HARNESS_TURN", "eventState", "FAILED", "turn", turn,
                    "contextFingerprint", fingerprint, "datasetFingerprint", prepared.fingerprint(), "reason", invalid.getMessage());
                trace.add(span); observe.accept(span); metadata.put("harnessTrace", List.copyOf(trace));
                if (turn == maximumTurns) exhausted = true;
                continue;
            }
            report = candidateReport;
            if (v2) for (String key : List.of("modelDecision", "modelEvidenceSnapshotRef", "analysisEvidenceSnapshotFingerprint",
                "publicationState", "modelOutput", "modelPublicationRequest", "modelPublicationReferenceAudit")) {
                if (acceptedIntent.containsKey(key)) metadata.put(key, acceptedIntent.get(key)); else metadata.remove(key);
            }
            var assessmentAudit = new LinkedHashMap<String, Object>(new ModelEvidenceAssessmentAudit()
                .record(product.get("evidenceAssessment"), prepared.sources(), guard));
            assessmentAudit.put("turn", turn);
            assessmentAudit.put("reportSha256", ModelProtocolJson.sha256Hex(report));
            metadata.put("modelEvidenceAssessmentAudit", Collections.unmodifiableMap(assessmentAudit));
            assessmentHistory.add(Collections.unmodifiableMap(assessmentAudit));
            metadata.put("modelEvidenceAssessmentHistory", List.copyOf(assessmentHistory));
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
            requests = maps(product.get("evidenceRequests"));
            List<Map<String,Object>> audit = new ArrayList<>();
            boolean completed;
            if (v2) metadata.put("modelAnalysisOutput", report);
            completed = v2 ? ModelAnalysisIntent.finalDeclared(metadata) : Boolean.TRUE.equals(product.get("completed"));
            metadata.put("modelEvidenceRequests", v2 ? List.copyOf(requests) : List.of());
            var explorationState = new LinkedHashMap<String,Object>();
            for (String key : List.of("hypotheses", "findings")) {
                if (workspace.containsKey(key)) explorationState.put(key, workspace.get(key));
                else if (workspace.get("state") instanceof Map<?,?> declared && declared.containsKey(key))
                    explorationState.put(key, declared.get(key));
            }
            var progress = new LinkedHashMap<String,Object>();
            progress.put("eventKind", "HARNESS_TURN");
            progress.put("eventState", "RUNNING");
            progress.put("stage", "MODEL_RETURNED");
            progress.put("turn", turn);
            progress.put("contextFingerprint", fingerprint);
            progress.put("datasetFingerprint", prepared.fingerprint());
            progress.put("modelDecision", metadata.getOrDefault("modelDecision", Map.of()));
            progress.put("workspace", Map.of("state", explorationState));
            progress.put("requestCount", requests.size());
            progress.put("completedRequestCount", 0);
            if (product.get("progress") instanceof Map<?,?> declared && declared.get("summary") instanceof String summary)
                progress.put("summary", summary.substring(0, Math.min(500, summary.length())));
            observe.accept(Collections.unmodifiableMap(new LinkedHashMap<>(progress)));
            if (!completed && turn < maximumTurns) {
                if (requests.size() > 4) receipts.add(Map.of("status", "REQUEST_REJECTED", "reason", "At most four requests per turn"));
                else for (int requestIndex = 0; requestIndex < requests.size(); requestIndex++) {
                    var request = requests.get(requestIndex);
                    guard.run();
                    progress.put("stage", "EVIDENCE_REQUEST_STARTED");
                    var pending = new ArrayList<Map<String,Object>>(audit);
                    pending.add(Map.of("status", "STARTED", "request", request));
                    progress.put("reads", List.copyOf(pending));
                    observe.accept(Collections.unmodifiableMap(new LinkedHashMap<>(progress)));
                    try {
                        String operation = String.valueOf(request.get("operation"));
                        if ("CALL_TOOL".equals(operation)) {
                            throw new IllegalArgumentException("TEMPLATE_SCOPE_LIMIT: analysis cannot rediscover assets, rebind templates or dispatch external tools; use delivered data operations only");
                        } else if (coding != null && coding.capabilities().containsKey(operation)) {
                            if (!deliveredCodingInputs(request, prepared.sources()))
                                throw new IllegalArgumentException("CODING_INPUT_NOT_DELIVERED: choose an exact datasetReference from the current data catalog");
                            var receipt = pythonRequired
                                ? coding.executeObserved(codingContext, request, prepared.sources(), observe)
                                : coding.execute(codingContext, request, prepared.sources());
                            if (Boolean.TRUE.equals(receipt.get("resultAvailable"))
                                    && receipt.get("datasetReference") instanceof String resultRef
                                    && prepared.sources().containsKey(resultRef) && !rawReferences.contains(resultRef))
                                admittedResults.add(resultRef);
                            receipts.add(receipt);
                            var summary = codingTaskSummary(request, receipt);
                            codingTasks.put(String.valueOf(receipt.getOrDefault("taskId", request.get("requestId"))), summary);
                            metadata.put("harnessCodingTasks", List.copyOf(codingTasks.values()));
                            if (Boolean.TRUE.equals(receipt.get("analysisBlocked"))) {
                                metadata.put("codingLastReceipt", receipt);
                                throw new IllegalStateException("CODING_FAILURE_BUDGET_EXHAUSTED: repeated non-retryable output contract failures; stop analysis before creating more work");
                            }
                            if (Boolean.TRUE.equals(receipt.get("reconciliationRequired"))
                                    && Boolean.TRUE.equals(receipt.get("budgetExhausted"))) {
                                metadata.put("codingLastReceipt", receipt);
                                throw new IllegalStateException("CODING_RECONCILIATION_REQUIRED: Coding wait budget ended with an unresolved execution; stop parent analysis before creating more work");
                            }
                            audit.add(Map.of("status", "ACCEPTED", "request", request,
                                "receiptFingerprint", ModelProtocolJson.sha256Hex(receipt), "receipt", receipt));
                            // Tool-local exhaustion is a receipt for the model, not exhaustion of the caller.
                        } else if (operations.supports(operation)) {
                            if (pythonRequired && !"CATALOG".equals(operation))
                                throw new IllegalArgumentException("PYTHON_EXECUTION_REQUIRED: use RUN_CODING for data processing");
                            var receipt = operations.execute(request);
                            receipts.add(receipt);
                            audit.add(Map.of("status", "ACCEPTED", "request", request, "receiptFingerprint", ModelProtocolJson.sha256Hex(receipt), "receipt", explorationReceipt(receipt)));
                        } else if ("READ_PLAN".equals(operation)) {
                            var receipt = plans.read(request); receipts.add(receipt);
                            audit.add(Map.of("status", "ACCEPTED", "request", request, "receiptFingerprint", ModelProtocolJson.sha256Hex(receipt)));
                        } else {
                            if (pythonRequired && !admittedResults.contains(String.valueOf(request.get("datasetReference"))))
                                throw new IllegalArgumentException("RAW_DATA_EXECUTION_ONLY: use RUN_CODING; only admitted result datasets are readable");
                            if (!BoundedAnalysisEvidence.capabilities().containsKey(operation))
                                throw new IllegalArgumentException("Operation is not registered for this harness");
                            var read = access.read(prepared, List.of(request), guard, model, scope, checkpoints, question, metadata);
                            receipts.addAll(read);
                            audit.add(Map.of("status", "ACCEPTED", "request", request, "receiptFingerprint", ModelProtocolJson.sha256Hex(read),
                                "receipts", read.stream().map(ModelNativeAnalysisHarness::explorationReceipt).toList()));
                        }
                    } catch (IllegalArgumentException rejected) {
                        var rejection = Map.<String,Object>of("status", "REQUEST_REJECTED", "request", request, "reason", String.valueOf(rejected.getMessage()),
                            "sourceAvailability", "UNCHANGED");
                        receipts.add(rejection); audit.add(rejection);
                    } catch (RuntimeException failed) {
                        pending.set(pending.size() - 1, Map.of("status", "FAILED", "request", request,
                            "reason", failed.getClass().getSimpleName()));
                        progress.put("stage", "EVIDENCE_REQUEST_FAILED");
                        progress.put("eventState", "FAILED");
                        progress.put("reads", List.copyOf(pending));
                        observe.accept(Collections.unmodifiableMap(new LinkedHashMap<>(progress)));
                        throw failed;
                    }
                    progress.put("stage", "EVIDENCE_REQUEST_FINISHED");
                    progress.put("completedRequestCount", audit.size());
                    progress.put("reads", List.copyOf(audit));
                    observe.accept(Collections.unmodifiableMap(new LinkedHashMap<>(progress)));
                    if (exhausted) break;
                }
            } else if (!completed) exhausted = true;
            var span = new LinkedHashMap<String,Object>(Map.<String,Object>of("type", "harness_turn", "eventKind", "HARNESS_TURN", "stage", "MODEL_NATIVE_ANALYSIS",
                "turn", turn, "inputTokensEstimated", tokens, "contextFingerprint", fingerprint,
                "datasetFingerprint", prepared.fingerprint(), "workspaceVersion", turn, "reads", audit, "modelCompleted", v2
                    ? ModelAnalysisIntent.action(metadata) == ModelAnalysisIntent.Action.COMPLETE
                        || ModelAnalysisIntent.action(metadata) == ModelAnalysisIntent.Action.PARTIAL_COMPLETE : completed));
            span.put("outputCheckpointRestored", restored.isPresent());
            span.put("modelDecision", metadata.getOrDefault("modelDecision", Map.of()));
            // Persist model-declared exploration summaries, not a Runtime assessment or prescribed graph.
            span.put("workspace", Map.of("state", explorationState));
            span.put("requestCount", requests.size());
            span.put("completedRequestCount", audit.size());
            if (progress.containsKey("summary")) span.put("summary", progress.get("summary"));
            span.put("publicationState", metadata.getOrDefault("publicationState", "NOT_REQUESTED"));
            span.put("modelOutput", metadata.getOrDefault("modelOutput", Map.of()));
            if (metadata.get("modelPublicationRequest") instanceof Map<?,?> binding) {
                span.put("reportSha256", binding.get("reportSha256"));
                span.put("evidenceSnapshotRef", binding.get("evidenceSnapshotRef"));
                span.put("target", "CURRENT_SESSION");
                if (metadata.containsKey("modelPublicationReferenceAudit"))
                    span.put("reason", "Runtime bound accepted final content; legacy model publication references have no delivery authority");
            }
            span.put("eventState", "COMPLETED");
            trace.add(span); observe.accept(span);
            metadata.put("harnessTrace", List.copyOf(trace));
            if (completed || exhausted) break;
        }
        metadata.put("harnessStopReason", exhausted ? "RESOURCE_BUDGET_EXHAUSTED"
            : v2 ? "MODEL_DECISION" : "MODEL_COMPLETED");
        metadata.put("harnessMaxModelTurns", maximumTurns);
        metadata.put("visualizationCapabilitiesDraftInjected", true);
        refreshReportCatalog(reportSources(prepared.sources(), pythonRequired, admittedResults), metadata);
        if (v2) {
            metadata.put("modelAnalysisOutput", report);
            if (!metadata.containsKey("modelDecision")) {
                metadata.put("executionState", "STOPPED");
                metadata.put("executionStopReason", exhausted ? "RESOURCE_BUDGET_EXHAUSTED" : "INVALID_TURN_PROTOCOL");
                metadata.put("publicationState", "NOT_REQUESTED");
                return new Result(report, calls, List.copyOf(prepared.sources().keySet()));
            }
            metadata.put("executionState", exhausted ? "STOPPED" : "COMPLETED");
            metadata.put("executionStopReason", exhausted ? "RESOURCE_BUDGET_EXHAUSTED" : "MODEL_DECISION");
            if (exhausted) metadata.put("publicationState", "NOT_REQUESTED");
        } else if (exhausted) {
            metadata.put("executionState", "STOPPED"); metadata.put("executionStopReason", "RESOURCE_BUDGET_EXHAUSTED");
        }
        return new Result(report, calls, List.copyOf(prepared.sources().keySet()));
    }
    private static String snapshot(Map<String,Dataset> sources) {
        return ModelProtocolJson.sha256Hex(sources.entrySet().stream().map(entry -> Map.of(
            "reference", entry.getKey(), "contentSha256", entry.getValue().handle().contentSha256())).toList());
    }
    private static Map<String,Dataset> reportSources(Map<String,Dataset> sources, boolean required, Set<String> results) {
        if (!required) return sources;
        Map<String,Dataset> admitted = new LinkedHashMap<>();
        sources.forEach((ref, source) -> { if (results.contains(ref)) admitted.put(ref, source); });
        return admitted;
    }
    private static boolean deliveredCodingInputs(Map<String,Object> request, Map<String,Dataset> sources) {
        Object single = request.get("datasetReference"), multiple = request.get("datasetReferences");
        if (single instanceof String reference && multiple == null) return sources.containsKey(reference);
        if (single != null || !(multiple instanceof List<?> refs) || refs.isEmpty() || refs.size() > 8)
            return false;
        Set<String> unique = new HashSet<>();
        for (Object ref : refs) if (!(ref instanceof String name) || !sources.containsKey(name) || !unique.add(name))
            return false;
        return true;
    }
    private static Map<String,Object> codingTaskSummary(Map<String,Object> request, Map<String,Object> receipt) {
        var summary = new LinkedHashMap<String,Object>();
        summary.put("requestId", request.getOrDefault("requestId", ""));
        for (String key : List.of("taskId", "status", "resultAvailable", "reconciliationRequired", "reasonCode", "stopReason",
                "validationStatus", "businessValidation", "datasetReference")) {
            Object value = receipt.get(key);
            if (value instanceof String text) summary.put(key, text.substring(0, Math.min(512, text.length())));
            else if (value instanceof Boolean) summary.put(key, value);
        }
        return Collections.unmodifiableMap(summary);
    }
    private static void validateIntent(Map<String,Object> product, boolean v2) {
        var requests = maps(product.get("evidenceRequests"));
        if (requests.size() > 4) throw new IllegalArgumentException("REQUEST_LIMIT: at most four requests per turn");
        if (!v2) {
            if (!(product.get("completed") instanceof Boolean)) throw new IllegalArgumentException("v1 completed must be boolean");
            if (Boolean.TRUE.equals(product.get("completed")) && !requests.isEmpty()) throw new IllegalArgumentException("FINAL_REQUESTS_FORBIDDEN");
            return;
        }
        if (!(product.get("decision") instanceof Map<?,?> decision)) throw new IllegalArgumentException("v2 decision is required");
        ModelAnalysisIntent.Action action;
        try { action = ModelAnalysisIntent.Action.valueOf(String.valueOf(decision.get("action"))); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid v2 action"); }
        if (!Set.of(ModelAnalysisIntent.Action.CONTINUE, ModelAnalysisIntent.Action.COMPLETE, ModelAnalysisIntent.Action.PARTIAL_COMPLETE).contains(action))
            throw new IllegalArgumentException("UNSUPPORTED_ACTION: " + action + "; use CONTINUE, COMPLETE or PARTIAL_COMPLETE");
        String type = String.valueOf(product.get("output_type"));
        if (!Set.of("intermediate", "draft", "final").contains(type)) throw new IllegalArgumentException("INVALID_OUTPUT_TYPE: declare intermediate, draft or final");
        if ((action == ModelAnalysisIntent.Action.CONTINUE) == "final".equals(type))
            throw new IllegalArgumentException("INVALID_ACTION_OUTPUT: CONTINUE requires intermediate/draft; COMPLETE/PARTIAL_COMPLETE require final");
        if (product.containsKey("reportMarkdown") || product.containsKey("completed"))
            throw new IllegalArgumentException("V2_FIELD_MISMATCH: use content and decision, not legacy fields");
        if (action != ModelAnalysisIntent.Action.CONTINUE && !maps(product.get("evidenceRequests")).isEmpty())
            throw new IllegalArgumentException("Only CONTINUE may execute evidence requests");
    }
    private static void normalizeLegacyIntent(Map<String,Object> product, Map<String,Object> metadata) {
        if (!Boolean.TRUE.equals(metadata.get("harnessLegacyV2Compatibility"))) return;
        if (product.containsKey("reportMarkdown")) {
            if (product.containsKey("content") && !Objects.equals(product.get("content"), product.get("reportMarkdown")))
                throw new IllegalArgumentException("CONTENT_CONFLICT: content and legacy reportMarkdown differ");
            product.put("content", product.remove("reportMarkdown"));
        }
        if (product.get("decision") instanceof Map<?,?> decision && "PUBLISH".equals(decision.get("action"))) {
            product.put("decision", Map.of("action", "COMPLETE")); product.putIfAbsent("output_type", "final");
        }
    }

    private static Map<String,Object> analysisMethods(Map<String,Object> metadata) {
        var validated = SkillAnalysisContext.from(metadata);
        if (!"APPLIED".equals(validated.get("status"))) return Map.of();
        var result = new LinkedHashMap<>(validated);
        if (validated.get("stages") instanceof Map<?,?> stages) {
            var relevant = new LinkedHashMap<String,Object>();
            for (String stage : List.of("ANALYSIS", "VALIDATION", "REPORT")) if (stages.containsKey(stage)) relevant.put(stage, stages.get(stage));
            if (!Boolean.TRUE.equals(metadata.get("analysisInterpretationPlanValidated")) && stages.containsKey("PLAN")) relevant.put("PLAN", stages.get("PLAN"));
            result.put("stages", relevant);
            result.put("projectionOf", validated.get("fingerprint")); result.remove("fingerprint");
        }
        return result;
    }
    private static boolean recordIntent(Map<String,Object> product, String report, String snapshot, Map<String,Object> metadata) {
        metadata.put("modelDecision", product.get("decision"));
        metadata.put("modelEvidenceSnapshotRef", snapshot);
        metadata.put("analysisEvidenceSnapshotFingerprint", snapshot);
        metadata.put("publicationState", "NOT_REQUESTED");
        var action = ModelAnalysisIntent.action(metadata);
        // A model declaration is the only source of content semantics. Execution completion is separate.
        String type = product.containsKey("output_type")
            ? String.valueOf(product.get("output_type")).toUpperCase(java.util.Locale.ROOT)
            : action == ModelAnalysisIntent.Action.PUBLISH ? "FINAL" : "UNDECLARED";
        if (!List.of("INTERMEDIATE", "DRAFT", "FINAL", "UNDECLARED").contains(type))
            throw new IllegalArgumentException("Invalid model output_type");
        metadata.put("modelOutput", Map.of("author", "MODEL", "type", type,
            "declaration", product.containsKey("output_type") ? "output_type" : action == ModelAnalysisIntent.Action.PUBLISH ? "legacy_PUBLISH" : "NONE",
            "reportSha256", ModelProtocolJson.sha256Hex(report), "evidenceSnapshotRef", snapshot, "target", "CURRENT_SESSION"));
        metadata.remove("modelPublicationRequest");
        metadata.remove("modelPublicationReferenceAudit");
        if (ModelAnalysisIntent.finalDeclared(metadata)) {
            String hash = ModelProtocolJson.sha256Hex(report);
            if (report.isBlank()) throw new IllegalArgumentException("A model-declared final requires non-empty content");
            // The model declares final content, not the Runtime's version identifiers.
            // Bind exactly the accepted content and run-scoped evidence here. Consumers
            // still verify this Runtime-owned binding after persistence/transport.
            if (product.containsKey("publication")) {
                var legacy = product.get("publication");
                boolean matches = legacy instanceof Map<?,?> reference
                    && hash.equals(reference.get("reportSha256"))
                    && snapshot.equals(reference.get("evidenceSnapshotRef"));
                metadata.put("modelPublicationReferenceAudit", Map.of("authority", "RUNTIME",
                    "status", "LEGACY_REFERENCE_IGNORED", "matchedActualOutput", matches,
                    "deliveryEffect", "NONE"));
            }
            metadata.put("modelPublicationRequest", Map.of("authority", "RUNTIME", "target", "CURRENT_SESSION",
                "reportSha256", hash, "evidenceSnapshotRef", snapshot));
            metadata.put("publicationState", "REQUESTED");
        }
        return ModelAnalysisIntent.finalDeclared(metadata) || action != ModelAnalysisIntent.Action.CONTINUE;
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
                Map<String, Object> observation = new LinkedHashMap<>();
                for (String key : List.of("datasetReference", "toolName", "accountingStatus", "executionStatus",
                    "invoked", "reason", "error")) {
                    if (source.get(key) != null) observation.put(key, source.get(key));
                }
                observation.put("observationScope", "SOURCE_EXECUTION_ONLY_NOT_ANALYTICAL_SUFFICIENCY");
                observations.add(Map.copyOf(observation));
            }
        }
        return List.copyOf(observations);
    }
    private static Map<String,Object> explorationReceipt(Map<String,Object> receipt) {
        var facts = new LinkedHashMap<String,Object>();
        for (String key : List.of("status", "outcome", "operation", "requestId", "toolName", "datasetReference", "rawResultReference",
            "reason", "errorMessage", "exceptionType", "errorCode", "retryable", "complete", "truncated", "children"))
            if (receipt.containsKey(key)) facts.put(key, receipt.get(key));
        if (receipt.get("datasets") instanceof List<?> datasets) {
            var sources = new ArrayList<Map<String,Object>>();
            for (Object value : datasets) if (value instanceof Map<?,?> dataset) {
                var source = new LinkedHashMap<String,Object>();
                for (String key : List.of("datasetReference", "sourceReference", "contentSha256", "recordCount"))
                    if (dataset.containsKey(key)) source.put(key, dataset.get(key));
                sources.add(source);
            }
            facts.put("datasets", sources);
        }
        if (receipt.get("nestedPage") instanceof Map<?, ?> page) {
            var navigation = new LinkedHashMap<String, Object>();
            for (String key : List.of("record", "path", "availableItemCount", "fromItem", "toItemExclusive",
                "nextItem", "hasMore", "truncated", "complete", "valueOmitted", "totalChars", "nextChar", "fieldAvailability"))
                if (page.containsKey(key)) navigation.put(key, page.get(key));
            facts.put("navigation", navigation);
        }
        return facts;
    }
    private static Map<String,Object> parse(String response, boolean v2) {
        if (response == null || response.isBlank()) throw new IllegalArgumentException("Empty model response");
        String text = response.trim();
        if (text.startsWith("```json") && text.endsWith("```")) text = text.substring(text.indexOf('\n') + 1, text.lastIndexOf("```")).trim();
        if (!text.startsWith("{")) {
            if (v2) throw new IllegalArgumentException("INVALID_TURN_PROTOCOL: return the requested v2 JSON envelope");
            return Map.of("completed", true, "reportMarkdown", response);
        }
        try {
            Map<String,Object> product = JSON.readValue(text, new TypeReference<>() {});
            if (!(product.get("schemaVersion") instanceof String version)
                || !Set.of("model_native_analysis.v1", ModelAnalysisIntent.VERSION).contains(version))
                throw new IllegalArgumentException("Missing or unsupported schemaVersion; use " + (v2 ? ModelAnalysisIntent.VERSION : "model_native_analysis.v1"));
            return product;
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid JSON turn protocol"); }
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof Map<?,?>))) throw new IllegalArgumentException("Invalid evidence request list");
        return list.stream().map(item -> (Map<String,Object>)item).toList();
    }
}
