package com.chatchat.api.runtime.coding.analysis;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisCoverageCoordinator;
import com.chatchat.agents.orchestration.model.AgentChatModelResolver;
import com.chatchat.agents.runtime.context.SkillAnalysisContext;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.observation.AgentObservation;
import com.chatchat.agents.runtime.store.AgentRunStore;
import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.interaction.service.*;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.runtime.capability.WorkflowOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;

/** Context-only reasoning over the existing immutable-input / Coding / Sandbox report contract.
 * The snapshot contains supplied context, never fabricated measurements or a grant to query sources.
 */
@Service
public final class ClineContextAnswerService implements CodingAnswerPort {
    private final java.util.function.Supplier<CodingAnalysisService> codingServices;
    private final AgentChatModelResolver models;
    private final ObjectMapper json;
    private final AgentRunStore runs;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.chatchat.api.assets.AssetAnalysisInputs> attachmentInputs;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.chatchat.agents.runtime.event.AgentRunEventPublisher> publishers;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<ProblemAnalysisPlanner> problemPlanners;

    @Override public List<com.chatchat.common.runtime.capability.ProblemAnalysisPlan.FileInput> describeFiles(InteractionRequest request) {
        var resolver = attachmentInputs == null ? null : attachmentInputs.getIfAvailable();
        if (resolver == null) throw new IllegalStateException("User assets must be enabled for attachments");
        Object raw = request.getToolInput() == null ? null : request.getToolInput().get(com.chatchat.api.assets.AssetAnalysisInputs.IDS);
        if (!(raw instanceof List<?> ids) || ids.stream().anyMatch(id -> !(id instanceof String)))
            throw new IllegalArgumentException("Invalid attachment IDs");
        Object run = request.getToolInput().get("__agentRunId");
        try {
            filePreparationEvent(run, "RUNNING");
            var files = resolver.describe(new com.chatchat.api.assets.UserAssetService.Owner(request.getTenantId(),request.getUserId()),
                ids.stream().map(String.class::cast).toList());
            filePreparationEvent(run, "COMPLETED");
            return files;
        } catch (RuntimeException failure) { filePreparationEvent(run, "FAILED"); throw failure; }
    }
    private void filePreparationEvent(Object run, String state) {
        if (publishers != null && run instanceof String id && !id.isBlank())
            publishers.orderedStream().forEach(publisher -> publisher.publish(com.chatchat.agents.runtime.event.AgentRunEvent.of(id,
                com.chatchat.agents.runtime.event.AgentRunEventType.OBSERVATION_RECORDED, "Preparing authorized file references",
                Map.of("stage", "FILE_REFERENCES_PREPARING", "status", state,
                    "metadata", Map.of("eventKind", "FILE_ANALYSIS", "stage", "FILE_REFERENCES_PREPARING", "eventState", state)))));
    }
    private void phase(String runId, String stage, String state) {
        runs.recordObservation(runId, new AgentObservation("file_analysis", "runtime", stage,
            Map.of("eventKind", "FILE_ANALYSIS", "stage", stage, "eventState", state)));
    }

    public ClineContextAnswerService(CodingAnalysisService coding, AgentChatModelResolver models, ObjectMapper json, AgentRunStore runs) {
        this.codingServices = ()->coding; this.models = models; this.json = json; this.runs = runs;
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ClineContextAnswerService(org.springframework.beans.factory.ObjectProvider<CodingAnalysisService> coding,
                                    AgentChatModelResolver models,ObjectMapper json,AgentRunStore runs){
        this.codingServices=coding::getIfAvailable;this.models=models;this.json=json;this.runs=runs;
    }

    @Override public InteractionResponse answer(InteractionRequest request, InteractionContext context,
                                                SkillDefinition agent, String suppliedContext) {
        InteractionExecution.checkCancellation(request);
        var internal = request.getToolInput() == null ? Map.<String,Object>of() : request.getToolInput();
        Object run = internal.get("__agentRunId");
        String runId = run instanceof String value && !value.isBlank() ? value : context.requestId();
        if (runId == null || runId.isBlank())
            throw new SecurityException("Authenticated Runtime request is required for Coding answers");
        for (String value : new String[]{request.getTenantId(), request.getUserId(), context.requestId()})
            if (value == null || value.isBlank() || Set.of("default", "anonymous", "unknown-request").contains(value))
                throw new SecurityException("Authenticated request identity is required for Coding answers");
        String sqlSource = ProblemAnalysisPlanner.sqlSource(request.getQuery());
        boolean withAttachments = internal.get(com.chatchat.api.assets.AssetAnalysisInputs.IDS) instanceof List<?> ids && !ids.isEmpty();
        if (sqlSource != null && context.problemAnalysisPlan() == null) {
            var planner = problemPlanners == null ? null : problemPlanners.getIfAvailable();
            var plan = planner == null ? com.chatchat.common.runtime.capability.ProblemAnalysisPlan.unavailable()
                : planner.analyzeFiles(request, context, agent, List.of(), withAttachments ? describeFiles(request) : List.of());
            if (!ProblemAnalysisPlanner.executable(plan)) return ProblemAnalysisPlanner.blockedResponse(plan);
            if (plan.sqlIntent() == null) return ProblemAnalysisPlanner.blockedResponse(
                com.chatchat.common.runtime.capability.ProblemAnalysisPlan.unavailable());
            context = context.toBuilder().problemAnalysisPlan(plan).build();
        }
        var sqlIntent = context.problemAnalysisPlan() == null ? null : context.problemAnalysisPlan().sqlIntent();
        if (sqlIntent == com.chatchat.common.runtime.capability.ProblemAnalysisPlan.SqlIntent.EXECUTE
                && (!withAttachments || context.problemAnalysisPlan().fileAnalysis() == null
                    || context.problemAnalysisPlan().fileAnalysis().toolDataDependency()
                        == com.chatchat.common.runtime.capability.ProblemAnalysisPlan.ToolDataDependency.REQUIRED))
            return ProblemAnalysisPlanner.sqlDataRequired(context.problemAnalysisPlan());
        CodingAnalysisService coding=codingServices.get();
        if(coding==null)throw new IllegalStateException("Cline analysis service must be enabled for this task");
        String alias = agent == null || agent.modelName() == null || agent.modelName().isBlank() ? request.getModelName() : agent.modelName();
        var model = models.resolveChatModel(alias);
        var attrs = new LinkedHashMap<String,Object>();
        attrs.put("checkpointModelConfig", models.checkpointModelConfiguration(alias, model));
        attrs.put(SkillAnalysisContext.ATTRIBUTE, SkillAnalysisContext.validate(context.skillAnalysisContext()));
        attrs.put("codingOwnedAnalysisActive", true);
        attrs.put("contextOnlyAnswer", true); // This route cannot delegate external business queries.
        var taskType=context.taskType()!=null?context.taskType():context.problemAnalysisPlan()==null?null:
            context.problemAnalysisPlan().tasks().stream().anyMatch(task->task.taskType()==com.chatchat.common.runtime.capability.ProblemAnalysisPlan.TaskType.DATA_ANALYSIS)
                ?com.chatchat.common.runtime.capability.ProblemAnalysisPlan.TaskType.DATA_ANALYSIS:com.chatchat.common.runtime.capability.ProblemAnalysisPlan.TaskType.QA;
        if(taskType!=null)attrs.put("taskType",taskType.name());
        if(com.chatchat.common.runtime.coding.VisualReportMode.active(internal)){
            attrs.put("reportMode","visual");
            attrs.put("reportPresentation",com.chatchat.common.runtime.coding.ReportPresentation.read(internal));
        }
        for (String key : List.of("__agentRunId", "__agentCancellation", "__agentDeadlineAt"))
            if (internal.get(key) != null) attrs.put(key, internal.get(key));
        var metadata = new LinkedHashMap<String,Object>();
        if (alias != null && !alias.isBlank()) metadata.put("modelName", alias);
        var scope = GovernanceIsolationScope.runtime(request.getTenantId(), request.getUserId(), runId,
            context.requestId(), context.conversationId());
        var invocation = new AnalysisCoverageCoordinator.Request(model, request.getQuery(), null, attrs, metadata,
            () -> { InteractionExecution.checkCancellation(request); return false; },
            () -> InteractionExecution.checkCancellation(request), scope, null);
        var input = new LinkedHashMap<String,Object>();
        input.put("question", Objects.toString(request.getQuery(), ""));
        input.put("history", context.history() == null ? List.of() : context.history().stream()
            .skip(Math.max(0, context.history().size() - 8)).filter(Objects::nonNull)
            .map(message -> Map.of("role", Objects.toString(message.role(),""), "content", Objects.toString(message.content(),""))).toList());
        input.put("conversationSummary", Objects.toString(context.conversationSummary(), ""));
        input.put("conversationEvidence", Objects.toString(context.conversationEvidence(), ""));
        input.put("historicalContextIsCurrentEvidence", false);
        input.put("maintainedInstructions", agent == null ? "" : Objects.toString(agent.systemPrompt(), ""));
        input.put("requestInstructions", Objects.toString(request.getSystemPrompt(), ""));
        input.put("suppliedContext", Objects.toString(suppliedContext, ""));
        if (sqlSource != null) input.put("sqlSource", sqlSource);
        if (sqlIntent != null) input.put("sqlIntent", sqlIntent.name());
        String reference = "runtime-context:" + runId;
        String inputKind = withAttachments ? "USER_ATTACHMENTS" : sqlSource == null ? "SUPPLIED_CONTEXT" : "SQL_SOURCE";
        String codingRequestId = "answer-" + UUID.nameUUIDFromBytes((runId + ":" + inputKind)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var sources = new LinkedHashMap<String,Dataset>();
        try {
            var existing = runs.find(runId);
            if (existing.isPresent()) {
                var original = existing.get().request();
                if (original == null || !request.getTenantId().equals(original.getTenantId())
                        || !request.getUserId().equals(original.getUserId()) || !request.getQuery().equals(original.getQuery()))
                    throw new SecurityException("Context run identity conflict");
            } else runs.start(AgentRunRequest.builder().runId(runId).tenantId(request.getTenantId())
                .userId(request.getUserId()).requestId(context.requestId()).conversationId(context.conversationId())
                .query(request.getQuery()).skillId(agent == null ? request.getSkillId() : agent.id()).modelName(alias)
                .attributes(Map.of("workflowFamily", "DIRECT_ANSWER", "codingOwnedAnalysisActive", true)).build());
        if (sqlSource != null) runs.recordObservation(runId, new AgentObservation("sql_analysis", "runtime", "SQL intent identified",
            Map.of("eventKind", "SQL_INTENT_IDENTIFIED", "sqlIntent", sqlIntent == null ? "UNCLASSIFIED" : sqlIntent.name(),
                "sourceKind", "SQL_SOURCE", "sourceCharacters", sqlSource.length(), "sqlExecuted", false)));
        sources.put(reference, new Dataset(reference, contextMetadata(inputKind), List.of(input)));
        if (withAttachments) {
            phase(runId, "FILE_REFERENCES_PREPARING", "RUNNING");
            var manifest = describeFiles(request);
            var plan = context.problemAnalysisPlan();
            if (plan != null && plan.fileAnalysis() != null && !manifest.equals(plan.fileAnalysis().inputs()))
                throw new SecurityException("File references changed since planning");
            input.put("fileAnalysis", plan == null ? Map.of("inputs", manifest, "toolDataDependency", "NONE") : plan);
            attrs.put("taskType", "DATA_ANALYSIS");
            var resolver=attachmentInputs==null?null:attachmentInputs.getIfAvailable();
            if(resolver==null)throw new IllegalStateException("User assets must be enabled for attachments");
            var raw=(List<?>)internal.get(com.chatchat.api.assets.AssetAnalysisInputs.IDS);
            if(raw.stream().anyMatch(id->!(id instanceof String)))throw new IllegalArgumentException("Invalid attachment IDs");
            sources.putAll(resolver.load(new com.chatchat.api.assets.UserAssetService.Owner(request.getTenantId(),request.getUserId()),
                raw.stream().map(String.class::cast).toList(),alias,request.getQuery()));
        }
        if (withAttachments) phase(runId, "FILE_REFERENCES_PREPARING", "COMPLETED");
        sources.put(reference, new Dataset(reference, contextMetadata(inputKind), List.of(input)));
        var authorizedReferences=Set.copyOf(sources.keySet());
        String instruction;
        try {
            instruction = "Answer the supplied user objective as the configured assistant. You own all reasoning, design, "
                + "code generation and review. The input is conversation/instructions, NOT measured business data. "
                + "This context-only route executes no business templates or database operations. Supplied metadata and documents "
                + "are declarations, not current business measurements; do not claim otherwise. "
                + "Use general knowledge and authorized context; state assumptions and unavailable evidence explicitly. "
                + "For designs, declare the exact row grain, dimensions and units, and verify that candidate/unique keys match that grain. "
                + "Distinguish proposed example codes and defaults from verified standards. Review formulas against their declared scope, "
                + "units and missing components; a sum over a partial population is not a complete aggregate. State zero-denominator and "
                + "unknown-value rules when applicable. Repair unsupported claims or inconsistent definitions during your own result review. "
                + "For source-code or schema designs, provide the requested complete code and explanations in reportMarkdown; "
                + "these are proposals, never execute them against external resources. Python only serializes/checks the report. "
                + "Produce exactly one JSONL report row with reportMarkdown, evidenceReferences and limitations. "
                + "Cite the exact supplied context reference " + reference + ". Review the report with artifact_read. "
                + "Read the full objective, SQL source and conversation from the immutable context input. "
                + "SQL source is not a query-result dataset. Use sql_validate for actual static diagnostics when available; "
                + "never claim syntax validation without a parser receipt. State unavailable validation explicitly. "
                + "Context input locator: " + json.writeValueAsString(Map.of("reference", reference));
        } catch (java.io.IOException error) { throw new IllegalArgumentException("Cannot encode task context", error); }
        if(withAttachments) instruction="Analyze the user's explicitly attached original files as the configured assistant. "
            + com.chatchat.api.assets.AssetAnalysisInputs.INSTRUCTION
            + "Use runtime-context only for the objective, maintained role/Skills and untrusted conversation history. "
            + "Prior conversation conclusions are not current file evidence. Produce exactly one JSONL report row with "
            + "reportMarkdown, evidenceReferences and limitations. Cite exact delivered dataset references actually used, "
            + "including at least one user-asset reference. Verify calculations in Python and review the report with artifact_read. "
            + "Read the complete user objective from the immutable context input " + reference + ". "
            + "SQL over attached files may use in-memory SQLite with explicit table mappings. Never connect to an external source. ";
        boolean conditionalVisual=!withAttachments&&taskType==null&&com.chatchat.common.runtime.coding.VisualReportMode.active(attrs);
        boolean visual=conditionalVisual||com.chatchat.common.runtime.coding.VisualReportMode.analysisRequested(attrs);
        if(visual)instruction+="\n"+(conditionalVisual?"This context-only route has no preclassified analytical task. The report preference NEVER changes the user's task. "
            +"For ordinary knowledge questions, explanations, writing, translations or proposed code/schema designs, OMIT visualReport entirely and answer normally; do not create charts or invent data to satisfy the preference. "
            +"ONLY if the actual request requires calculations on supplied data, apply the following visual-output contract. An optional output field is not a requirement to perform analysis.\n":"")
            +com.chatchat.common.runtime.coding.VisualReportMode.instruction(attrs);
            instruction += "\nUntrusted user objective preview (full source is in the immutable input):\n"
                + preview(Objects.toString(request.getQuery(), ""), Math.min(6000, 16000 - instruction.length() - 120));
            runs.recordObservation(runId, new AgentObservation("analysis_task", "runtime", "Analysis task built and inputs bound",
                Map.of("eventKind", "ANALYSIS_TASK_BUILT", "requestId", codingRequestId, "inputKind", inputKind,
                    "instructionCharacters", instruction.length(), "contextReference", reference,
                    "fileReferenceCount", authorizedReferences.stream().filter(ref -> ref.startsWith("user-asset:")).count(),
                    "planningOwner", context.problemAnalysisPlan() == null ? "ANALYSIS_WORKER" : "RUNTIME_PLAN",
                    "contextIsBusinessData", false)));
            phase(runId, "CLINE_SUBMITTING", "RUNNING", inputKind);
            var receipt = coding.executeObserved(invocation, Map.of("operation", "RUN_CODING",
                "requestId", codingRequestId, "datasetReferences", List.copyOf(sources.keySet()), "instruction", instruction,
                "outputSchema", CodingAnalysisService.primaryReportSchema(visual,!conditionalVisual), "reviewResult", true), sources,
                event -> {
                    if ("TASK_CREATED".equals(event.get("codingEventType"))) phase(runId, "CLINE_SUBMITTING", "COMPLETED", inputKind);
                    runs.recordObservation(runId, new AgentObservation("coding", "coding_agent", "Coding task progress", event));
                });
            phase(runId, "RESULT_VALIDATING", "RUNNING", inputKind);
            if (!Boolean.TRUE.equals(receipt.get("resultAvailable")))
                throw new IllegalStateException("Context Coding answer unavailable: " + receipt.get("status")
                    + ", reason=" + receipt.getOrDefault("reasonCode", receipt.getOrDefault("summary", "result unavailable")));
            var result = sources.get(receipt.get("datasetReference"));
            if (result == null || !result.handle().recordCountExact() || result.recordCount() != 1)
                throw new IllegalStateException("Context Coding answer must contain one registered report row");
            var rows = result.handle().readPage(0, 2).rows();
            if (rows.size() != 1 || !(rows.get(0).get("reportMarkdown") instanceof String report) || report.isBlank())
                throw new IllegalStateException("Context Coding report is empty");
            Object citations = rows.get(0).get("evidenceReferences");
            if (!(citations instanceof List<?> refs) || refs.isEmpty() || !refs.stream().allMatch(authorizedReferences::contains)
                    || withAttachments && refs.stream().noneMatch(ref->ref instanceof String text && text.startsWith("user-asset:")))
                throw new SecurityException("Context report cites unauthorized inputs");
            phase(runId, "RESULT_VALIDATING", "COMPLETED", inputKind);
            InteractionExecution.checkCancellation(request);
            var publication = Map.<String,Object>of("type", "final_summary", "workflow", "coding_owned_analysis",
                "inputKind", inputKind, "modelCalls", 0, "resultAdmissionScope", "STRUCTURE_AND_AUTHORIZED_REFERENCES");
            runs.recordObservation(runId, new AgentObservation("final_summary", "runtime", "Published Coding context report", publication));
            var responseMetadata = new LinkedHashMap<String,Object>(Map.<String,Object>of("handler", "ClineContextAnswerService", "workflow", "coding_owned_context_answer",
                    "javaAnalysisModelCalls", 0, "codingReceipt", receipt, "currentBusinessDataAcquired", false,
                    "attachmentReferences", authorizedReferences.stream().filter(ref->ref.startsWith("user-asset:")).toList(),
                    WorkflowOutcome.METADATA_KEY, new WorkflowOutcome(WorkflowOutcome.Type.READY_TO_ANSWER,
                        "CODING_CONTEXT_ANSWER_COMPLETED", List.of(), List.of(), true)));
            if (withAttachments) responseMetadata.put("fileAnalysis", input.get("fileAnalysis"));
            if (sqlIntent != null) responseMetadata.put("sqlIntent", sqlIntent.name());
            responseMetadata.put("codingRequestId", codingRequestId);
            if(visual && receipt.get("visualReport") instanceof Map<?,?> reportView)responseMetadata.put("visualReport",reportView);
            runs.complete(runId, AgentRunResult.builder().runId(runId).answer(report).metadata(responseMetadata).build());
            return InteractionResponse.builder().answer(report).toolTraces(List.of()).metadata(responseMetadata).build();
        } catch (java.util.concurrent.CancellationException cancelled) {
            runs.cancel(runId, "Context Coding cancelled"); throw cancelled;
        } catch (RuntimeException failure) {
            // Do not mutate an existing run belonging to another request.
            runs.find(runId).filter(record -> record.request() != null
                && request.getTenantId().equals(record.request().getTenantId())
                && request.getUserId().equals(record.request().getUserId())
                && request.getQuery().equals(record.request().getQuery())).ifPresent(record -> runs.fail(runId, failure));
            throw failure;
        } finally { sources.values().forEach(Dataset::close); }
    }
    private void phase(String runId, String stage, String state, String inputKind) {
        runs.recordObservation(runId, new AgentObservation(inputKind.equals("SQL_SOURCE") ? "sql_analysis" : "context_analysis",
            "runtime", stage, Map.of("eventKind", inputKind.equals("USER_ATTACHMENTS") ? "FILE_ANALYSIS" : inputKind,
                "stage", stage, "eventState", state)));
    }
    private static Map<String,Object> contextMetadata(String inputKind) {
        return Map.of("recordMeaning", "SUPPLIED_CONTEXT_NOT_OBSERVATIONS", "inputKind", inputKind,
            "isBusinessDataset", false, "currentBusinessDataAcquired", false);
    }
    private static String preview(String source, int maximum) {
        if (maximum < 128) throw new IllegalArgumentException("ANALYSIS_INSTRUCTION_BUDGET_EXCEEDED: task envelope is too large");
        if (source.length() <= maximum) return source;
        String marker = "\n[Preview truncated; read the complete immutable source. Never validate just this prefix.]";
        int end = maximum - marker.length();
        if (Character.isHighSurrogate(source.charAt(end - 1))) end--;
        return source.substring(0, end) + marker;
    }
}
