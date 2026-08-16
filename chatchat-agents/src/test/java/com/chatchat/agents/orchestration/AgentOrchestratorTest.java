package com.chatchat.agents.orchestration;

import com.chatchat.agents.assessment.EvidenceAugmentationPolicy;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.runtime.ToolRuntimeProperties;
import com.chatchat.agents.runtime.ToolRuntimeExecution;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.agents.runtime.batch.ToolCallBatchSchema;
import com.chatchat.agents.runtime.batch.ToolCallBatchResult;
import com.chatchat.agents.runtime.batch.ToolCallResult;
import com.chatchat.agents.runtime.AgentObservation;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.AgentRunStatus;
import com.chatchat.agents.runtime.InMemoryAgentRunStore;
import com.chatchat.agents.runtime.plan.DiagnosticRun;
import com.chatchat.agents.runtime.plan.DagGovernanceContractProvider;
import com.chatchat.agents.runtime.plan.InterpretationExecutionProtocol;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOrchestratorTest {

    @Test
    void suppressesScalarMandatoryFallbackAfterGovernedDiagnosticExecutorFailure() {
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("diagnosticRun", Map.of("required", 3, "completed", 0));
        metadata.put("interpretationPlanStepExecutions", List.of(
            Map.of(
                "stage", "repair-1",
                "stepId", 4,
                "toolName", "generic_batch_executor",
                "success", false,
                "errorMessage", "coverage mismatch"
            )
        ));

        assertThat(orchestrator.shouldSuppressLegacyMandatoryFallback(
            "generic_batch_executor", metadata)).isTrue();
        assertThat(orchestrator.shouldSuppressLegacyMandatoryFallback(
            "different_executor", metadata)).isFalse();
        assertThat(orchestrator.shouldSuppressLegacyMandatoryFallback(
            "generic_batch_executor", Map.of(
                "interpretationPlanStepExecutions", metadata.get("interpretationPlanStepExecutions")
            ))).isFalse();
    }

    @Test
    void pinsDatabaseDagGovernanceFingerprintIntoPlanRuntimeAttributes() {
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        DagGovernanceContractProvider.ContractSnapshot snapshot =
            new DagGovernanceContractProvider.ContractSnapshot(
                "runtime_dag_governance.v7",
                DagGovernanceContractProvider.CONTRACT_KEY,
                "runtime_dag_governance.v7",
                Map.of("immutable", true),
                "sha-v7"
            );
        orchestrator.setDagGovernanceContractProvider(() -> snapshot);

        Map<String, Object> attributes = orchestrator.interpretationPlanInitialAttributes(
            Map.of(), List.of());

        assertThat(attributes).containsKey(DagGovernanceContractProvider.CONTRACT_ATTRIBUTE);
        assertThat((Map<String, Object>) attributes.get(DagGovernanceContractProvider.CONTRACT_ATTRIBUTE))
            .containsEntry("contractId", "runtime_dag_governance.v7")
            .containsEntry("contractVersion", "runtime_dag_governance.v7")
            .containsEntry("checksumSha256", "sha-v7");
    }

    @Test
    void interpretationPlanAttemptsUseOneReviewAndAtMostThreeFullPlans() throws Exception {
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        Method attributesMethod = AgentOrchestrator.class.getDeclaredMethod(
            "workflowAttemptAttributes", Map.class, int.class);
        attributesMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) attributesMethod.invoke(
            orchestrator, Map.of("existing", true), 2);

        Method maxRewriteMethod = AgentOrchestrator.class.getDeclaredMethod(
            "maxRewriteTimes", com.chatchat.agents.runtime.plan.InterpretationPlan.class);
        maxRewriteMethod.setAccessible(true);

        assertThat(attributes)
            .containsEntry("existing", true)
            .containsEntry("workflowExecutionAttempt", 2)
            .containsEntry("toolResultReviewMaxAttempts", 1);
        assertThat(maxRewriteMethod.invoke(orchestrator, new Object[] {null})).isEqualTo(2);
    }

    @Test
    void interpretationPlanCarriesSuccessfulPrePlanToolsIntoWorkflowDependencies() {
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        InteractionToolTrace successfulAssetQuery = InteractionToolTrace.builder()
            .toolName("generated_asset_query")
            .success(true)
            .input(Map.of("filters", Map.of("assetName", "generated-worker")))
            .build();

        Map<String, Object> attributes = orchestrator.interpretationPlanInitialAttributes(
            Map.of("existing", true),
            List.of(successfulAssetQuery)
        );

        assertThat(attributes).containsEntry("existing", true);
        assertThat(attributes.get("workflowCompletedTools"))
            .isEqualTo(List.of("generated_asset_query"));
        assertThat(attributes.get("workflowContext"))
            .isEqualTo(Map.of("workflowTargetRef", "generated-worker"));
    }

    @Test
    void interpretationPlanRestoresOnlyCompletedToolsFromTheCurrentRun() {
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        AgentRunRequest firstRun = AgentRunRequest.builder()
            .runId("run-state-a")
            .requestId("request-state-a")
            .query("generated workflow")
            .build();
        AgentRunRequest secondRun = AgentRunRequest.builder()
            .runId("run-state-b")
            .requestId("request-state-b")
            .query("generated workflow")
            .build();
        runStore.start(firstRun);
        runStore.start(secondRun);
        runStore.recordObservation(firstRun.getRunId(), AgentObservation.builder()
            .type("tool")
            .source("generated_asset_discovery")
            .content("completed")
            .metadata(Map.of(
                "structuredRuntimeObservation", true,
                "toolName", "generated_asset_discovery",
                "success", true
            ))
            .build());
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            mock(ChatModel.class), mock(ToolRegistry.class), mock(ToolRuntimeService.class),
            new ObjectMapper(), new ModelsConfig(), new EvidenceTrustEvaluator(), runStore);

        assertThat(orchestrator.interpretationPlanInitialAttributes(
            Map.of("__agentRunId", firstRun.getRunId()), List.of()).get("workflowCompletedTools"))
            .isEqualTo(List.of("generated_asset_discovery"));
        assertThat(orchestrator.interpretationPlanInitialAttributes(
            Map.of("__agentRunId", secondRun.getRunId()), List.of()))
            .doesNotContainKey("workflowCompletedTools");
    }

    @Test
    void finalSynthesisPromptSummarizesAllExecutedPlanAttempts() {
        InterpretationPlanRuntime.ExecutionResult first = attemptResult(
            "result_unsatisfied", false, "first evidence", "first result was incomplete");
        InterpretationPlanRuntime.ExecutionResult second = attemptResult(
            "result_unsatisfied", false, "second evidence", "second result was incomplete");
        InterpretationPlanRuntime.ExecutionResult third = attemptResult(
            "success", true, "third evidence", null);

        String prompt = newOrchestrator(mock(ChatModel.class)).buildInterpretationPlanSummaryPrompt(
            "summarize the evidence",
            null,
            third,
            List.of(first, second, third),
            List.of(),
            List.of()
        );

        assertThat(prompt)
            .contains("Executed plan attempts (3)")
            .contains("Authoritative Summary Evidence Ledger (runtime-generated)")
            .contains("EXECUTED_RESULT")
            .contains("Attempt 1: status=result_unsatisfied, success=false")
            .contains("Attempt 2: status=result_unsatisfied, success=false")
            .contains("Attempt 3: status=success, success=true")
            .contains("first evidence", "second evidence", "third evidence")
            .contains("reconcile and summarize evidence from all attempts")
            .contains("MUST NOT produce a refusal or an empty answer")
            .contains("Do not present illustrative, manual, or 'typical' SQL/commands as executed, authorized, retrieved, or factual tool output")
            .contains("If the user explicitly asks to generate a SQL/DDL/command draft")
            .contains("clearly labeled non-executed draft for human review")
            .contains("Distinguish BLOCKED before invocation")
            .contains("Do not make the tool evidence list, document heading path, execution trace, or JSON field names the body of the answer")
            .contains("Primary answer contract (outcome-first)")
            .contains("Do not begin with asset discovery, template counts, tool calls, plan attempts, or execution chronology")
            .contains("Treat commandContext as the authoritative description and execution-lineage map")
            .contains("Follow the reference to the canonical data object and summarize that object once")
            .contains("write the user-facing answer by finding or requested dimension rather than by execution order")
            .doesNotContain("Summarize what was done step by step, then provide the final answer")
            .contains("deduplicate repeated headings")
            .contains("Do not claim that enterprise standards, terms, dictionaries, or other governed metadata do not exist")
            .contains("state its evidence-backed rejection reason")
            .contains("Do not imply that a service was unavailable")
            .contains("evidenceCoverage is descriptive only")
            .contains("never pre-decides whether a table or design conforms")
            .contains("Do not turn the final answer into a tool trace")
            .contains("clearly labeled possible business interpretation")
            .contains("Never present toolName as displayName");
    }

    @Test
    void evidenceChainCanExtendPositiveRewriteEstimateWithinRuntimeCeiling() {
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        EvidenceAugmentationPolicy.Outcome retrieveMore = new EvidenceAugmentationPolicy.Outcome(
            EvidenceAugmentationPolicy.CONTRACT_VERSION,
            EvidenceAugmentationPolicy.Decision.RETRIEVE_MORE,
            true,
            true,
            "A verified next action remains"
        );
        List<Map<String, Object>> history = List.of(Map.of(
            "missingEvidence", List.of("executable parameter contract"),
            "nextActions", List.of(Map.of(
                "tool", "api_template_query",
                "reason", "select a parameter-compatible alternative"
            ))
        ));

        assertThat(orchestrator.evidenceDrivenRewriteLimit(
            1, retrieveMore, history, List.of("mcp_service_api_template_query")))
            .isEqualTo(2);
        assertThat(orchestrator.evidenceDrivenRewriteLimit(
            1, retrieveMore, history, List.of("document_search")))
            .isEqualTo(1);
        assertThat(orchestrator.evidenceDrivenRewriteLimit(
            0, retrieveMore, history, List.of("mcp_service_api_template_query")))
            .isZero();
    }

    @Test
    void evidenceExplorationRequiresAnActionableAvailableToolAfterSuccessfulExecution() {
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        InterpretationPlanRuntime.ExecutionResult success = new InterpretationPlanRuntime.ExecutionResult(
            "completed_with_partial_evidence", true, false, null, "partial", List.of(), Map.of(), 1L);

        assertThat(orchestrator.evidenceExplorationAvailable(
            Map.of("nextActions", List.of()), success, List.of("generic_search"), true))
            .isFalse();
        assertThat(orchestrator.evidenceExplorationAvailable(
            Map.of("nextActions", List.of(Map.of("tool", "generic_search"))),
            success, List.of("generic_search"), true))
            .isTrue();

    }

    @Test
    void pendingEvidenceActionsDropDeclinedAndAlreadyExecutedRetrievals() {
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        List<Map<String, Object>> evidence = List.of(
            Map.of(
                "tool", "schema_search",
                "success", true,
                "shouldExpandQuery", true,
                "nextActions", List.of(Map.of("tool", "policy_search", "intent", "find policy"))
            ),
            Map.of(
                "tool", "policy_search",
                "success", true,
                "shouldExpandQuery", false,
                "nextActions", List.of(Map.of("tool", "policy_search", "intent", "try again"))
            )
        );

        assertThat(orchestrator.pendingEvidenceNextActions(evidence)).isEmpty();
    }

    @Test
    void pendingEvidenceActionsPreserveAvailableWorkNotExecutedInTheRound() {
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        Map<String, Object> pendingAction = Map.of(
            "tool", "verification_search",
            "intent", "verify the remaining gap"
        );

        assertThat(orchestrator.pendingEvidenceNextActions(List.of(Map.of(
            "tool", "primary_search",
            "success", true,
            "shouldExpandQuery", true,
            "nextActions", List.of(pendingAction)
        )))).containsExactly(pendingAction);
    }

    @Test
    void pendingEvidenceActionsPreserveMaterialRevisionForPreviouslyExecutedTool() {
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        Map<String, Object> revisedAction = Map.of(
            "tool", "metadata_search",
            "intent", "broaden the lookup after an exact miss",
            "input_changes", Map.of("queryTerms", List.of("broader alias"))
        );

        assertThat(orchestrator.pendingEvidenceNextActions(List.of(Map.of(
            "tool", "metadata_search",
            "success", true,
            "shouldExpandQuery", true,
            "nextActions", List.of(revisedAction)
        )))).containsExactly(revisedAction);
    }

    @Test
    void templateSelectionFeedbackPreservesCandidateReasonsForFinalAnswer() {
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        InterpretationPlanRuntime.StepExecution step = new InterpretationPlanRuntime.StepExecution(
            2,
            "mcp_tool",
            "api_template_query",
            true,
            Map.of("templates", List.of()),
            null,
            null,
            null,
            12,
            Map.of(
                "selectedTemplateIds", List.of("compatible-template"),
                "rejectedTemplateIds", List.of("precise-template"),
                "templateEvaluations", List.of(Map.of(
                    "templateId", "precise-template",
                    "decision", "reject",
                    "reasons", List.of("required customer parameter is not declared")
                )),
                "runtimeTemplateSelectionReason", "Selected from evidence-reviewed candidates"
            )
        );

        assertThat(orchestrator.templateSelectionFeedbackObservation("rewrite", step))
            .contains("template_selection_feedback.v1")
            .contains("precise-template")
            .contains("required customer parameter is not declared")
            .contains("compatible-template")
            .contains("Selected from evidence-reviewed candidates");
    }

    @Test
    void finalSynthesisKeepsLateEnterpriseMetadataObservation() {
        InterpretationPlanRuntime.ExecutionResult failedAttempt = attemptResult(
            "result_unsatisfied", false, "earlier SQL metadata was incomplete", "missing columns");
        String lateEvidence = "Mandatory workflow execution "
            + "{\"schemaVersion\":\"enterprise_metadata_model_context.v1\","
            + "\"coverage\":{\"inputFieldCount\":12,\"processedFieldCount\":12,"
            + "\"allFieldsProcessed\":true}}";

        String prompt = newOrchestrator(mock(ChatModel.class)).buildInterpretationPlanSummaryPrompt(
            "补全目标表字段注释",
            null,
            failedAttempt,
            List.of(failedAttempt),
            List.of(lateEvidence),
            List.of()
        );

        assertThat(prompt)
            .contains(lateEvidence)
            .contains("Mandatory workflow observations are executed after the listed plan attempts")
            .contains("resolves earlier missing_evidence claims");
    }

    @Test
    void dagDecisionPromptSnapshotCompactsLargeEnterpriseMetadataPayload() throws Exception {
        List<Map<String, Object>> fieldMatches = new ArrayList<>();
        for (int index = 0; index < 86; index++) {
            fieldMatches.add(Map.of(
                "input", Map.of("fieldName", "field_" + index),
                "standardFields", List.of(Map.of("raw", "raw-provider-payload-".repeat(1_000))),
                "termRoots", List.of(),
                "dictionaries", List.of()
            ));
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schemaVersion", "enterprise_metadata_field_discovery.v1");
        output.put("success", true);
        output.put("sourceSchema", Map.of("fieldCount", 86, "fields", List.of()));
        output.put("fieldMatches", fieldMatches);
        output.put("evidenceObjects", List.of("evidence"));

        Object snapshot = newOrchestrator(mock(ChatModel.class)).dagDecisionPromptOutputSnapshot(
            "mcp_chatchat_mcp_server_enterprise_metadata_search",
            output
        );
        String serialized = new ObjectMapper().writeValueAsString(snapshot);

        assertThat(serialized)
            .hasSizeLessThan(10_000)
            .contains("\"sourceFieldCount\":86", "\"matchedFieldCount\":86")
            .doesNotContain("raw-provider-payload");
    }

    @Test
    void finalSynthesisDoesNotRepeatRawStepOutputFromStoredObservationMetadata() {
        InterpretationPlanRuntime.ExecutionResult result = attemptResult(
            "success", true, "usable projected evidence", null);
        String rawMarker = "raw-step-output-must-not-be-repeated";
        AgentObservation storedObservation = AgentObservation.builder()
            .type("tool")
            .source("enterprise_metadata_search")
            .content("Enterprise metadata step completed.")
            .metadata(Map.of(
                "workflow", "interpretation_plan",
                "toolName", "enterprise_metadata_search",
                "stepOutput", Map.of("raw", rawMarker.repeat(20_000))
            ))
            .build();

        String prompt = newOrchestrator(mock(ChatModel.class)).buildInterpretationPlanSummaryPrompt(
            "设计客户信息表",
            null,
            result,
            List.of(result),
            List.of(),
            List.of(storedObservation)
        );

        assertThat(prompt)
            .contains("usable projected evidence")
            .contains("stepOutputLocation")
            .doesNotContain(rawMarker)
            .hasSizeLessThan(100_000);
    }

    @Test
    void compressedFinalSynthesisRetainsConcreteBusinessValuesFromEveryBatchChild() {
        List<ToolCallResult> childResults = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            childResults.add(new ToolCallResult(
                "query-" + index,
                "api_template_execute",
                "customer-template-" + index,
                "asset-1",
                "SUCCESS",
                20L,
                "evidence-" + index,
                Map.of(
                    "schemaVersion", "tool_execution_result.v1",
                    "target", Map.of("name", "customer-analysis-" + index),
                    "data", Map.of(
                        "statusCode", 200,
                        "body", Map.of(
                            "records", List.of(Map.of(
                                "CUSTOMER_ID", "070200046604",
                                "BUSINESS_MARKER", "returned-value-" + index,
                                "AMOUNT", index * 100
                            )),
                            "rawBody", "unbounded-raw-body-" + "x".repeat(10_000)
                        )
                    )
                ),
                Map.of()
            ));
        }
        ToolCallBatchResult batch = new ToolCallBatchResult(
            "reviewed-template-step-3",
            "SEQUENTIAL",
            "start",
            "end",
            "SUCCESS",
            new ToolCallBatchResult.Summary(5, 5, 0, 0, 0, 5),
            childResults
        );
        InterpretationPlanRuntime.StepExecution step = new InterpretationPlanRuntime.StepExecution(
            3,
            "mcp_tool",
            "mcp_chatchat_mcp_server_api_template_execute",
            true,
            batch,
            null,
            null,
            null,
            100L,
            Map.of("batchExecution", true)
        );
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "success", true, false, null, null, List.of(step), Map.of(), 100L
        );

        String prompt = newOrchestrator(mock(ChatModel.class)).buildInterpretationPlanSummaryPrompt(
            "summarize customer transactions, assets, and profit and loss",
            null,
            result,
            List.of(result),
            List.of("force-compression-" + "z".repeat(100_000)),
            List.of()
        );

        assertThat(prompt)
            .contains("enabled=true")
            .contains("batch_execution_evidence.v1")
            .contains("070200046604")
            .contains("returned-value-1", "returned-value-2", "returned-value-3",
                "returned-value-4", "returned-value-5")
            .contains("numericProfiles")
            .doesNotContain("ToolCallBatchResult[", "unbounded-raw-body");
    }

    @Test
    void deterministicSummaryKeepsSuccessfulChildrenWhenSiblingFails() {
        ToolCallResult success = new ToolCallResult(
            "asset", "api_template_execute", "asset-template", "asset-1",
            "SUCCESS", 10L, "evidence-1",
            Map.of(
                "data", Map.of("body", Map.of("records", List.of(Map.of(
                    "CUSTOMER_ID", "070200046604", "BALANCE", "912.05"
                ))))
            ),
            Map.of()
        );
        ToolCallResult failed = new ToolCallResult(
            "orders", "api_template_execute", "orders-template", "asset-1",
            "FAILED", 10L, "evidence-2", null,
            Map.of("code", "UPSTREAM_FAILED", "message", "orders unavailable")
        );
        ToolCallBatchResult batch = new ToolCallBatchResult(
            "batch-1", "SEQUENTIAL", "start", "end", "PARTIAL_SUCCESS",
            new ToolCallBatchResult.Summary(2, 1, 1, 0, 0, 2), List.of(success, failed)
        );
        InterpretationPlanRuntime.StepExecution step = new InterpretationPlanRuntime.StepExecution(
            3, "mcp_tool", "mcp_chatchat_mcp_server_api_template_execute", true,
            batch, null, null, null, 20L, Map.of("batchExecution", true)
        );
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "completed_with_partial_evidence", true, false, null, null,
            List.of(step), Map.of(), 20L
        );

        String answer = newOrchestrator(mock(ChatModel.class))
            .buildDeterministicAvailableResultAnswer(result);

        assertThat(answer)
            .contains("asset-template", "070200046604", "912.05")
            .contains("orders-template", "UPSTREAM_FAILED", "orders unavailable")
            .contains("\u6210\u529f\u5b50\u9879\uff1a1", "\u672a\u6210\u529f\u5b50\u9879\uff1a1"); /*
            .contains("成功子项：1", "未成功子项：1");
        */
    }

    @Test
    void appendsConcreteRowsWhenModelOnlyReportsTemplateExecution() {
        ToolCallResult success = new ToolCallResult(
            "asset", "api_template_execute", "asset-template", "asset-1",
            "SUCCESS", 10L, "evidence-1",
            Map.of("data", Map.of("body", Map.of("records", List.of(Map.of(
                "CUSTOMER_ID", "070200046604",
                "TOTAL_ASSET", "847174.25",
                "DAILY_PROFIT", "42263.81"
            ))))),
            Map.of()
        );
        ToolCallBatchResult batch = new ToolCallBatchResult(
            "batch-1", "SEQUENTIAL", "start", "end", "SUCCESS",
            new ToolCallBatchResult.Summary(1, 1, 0, 0, 0, 1), List.of(success)
        );
        InterpretationPlanRuntime.StepExecution step = new InterpretationPlanRuntime.StepExecution(
            3, "mcp_tool", "mcp_chatchat_mcp_server_api_template_execute", true,
            batch, null, null, null, 10L, Map.of("batchExecution", true)
        );
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "success", true, false, null, null, List.of(step), Map.of(), 10L
        );
        Map<String, Object> metadata = new LinkedHashMap<>();

        String answer = newOrchestrator(mock(ChatModel.class)).ensureConcreteBatchEvidencePresented(
            "1/1 templates succeeded; asset data can be returned.",
            "query customer 070200046604 assets",
            result,
            metadata
        );

        assertThat(answer)
            .contains("847174.25", "42263.81", "asset-template")
            .contains("\u5b9e\u9645\u8fd4\u56de\u6570\u636e");
        assertThat(metadata).containsEntry("concreteBatchEvidencePresentationFallback", true);
    }

    @Test
    void recordCoverageIncludesBatchEvidenceFromEarlierRepairAttempt() {
        ToolCallResult success = new ToolCallResult(
            "assets", "api_template_execute", "asset-template", "asset-1",
            "SUCCESS", 10L, "evidence-assets",
            Map.of("data", Map.of("body", Map.of("records", List.of(Map.of(
                "CUSTOMER_ID", "070200046604",
                "TOTAL_ASSET", "847174.25",
                "DAILY_PROFIT", "42263.81"
            ))))),
            Map.of()
        );
        ToolCallBatchResult batch = new ToolCallBatchResult(
            "batch-1", "SEQUENTIAL", "start", "end", "SUCCESS",
            new ToolCallBatchResult.Summary(1, 1, 0, 0, 0, 1), List.of(success)
        );
        InterpretationPlanRuntime.ExecutionResult firstAttempt = new InterpretationPlanRuntime.ExecutionResult(
            "rewrite_plan", false, false, null, null,
            List.of(new InterpretationPlanRuntime.StepExecution(
                3, "mcp_tool", "mcp_chatchat_mcp_server_api_template_execute", true,
                batch, null, null, null, 10L, Map.of("batchExecution", true))),
            Map.of(), 10L
        );
        InterpretationPlanRuntime.ExecutionResult repairedAttempt = new InterpretationPlanRuntime.ExecutionResult(
            "completed_with_partial_evidence", true, false, null, null,
            List.of(new InterpretationPlanRuntime.StepExecution(
                4, "final_answer", null, true, null, null, null,
                "templates succeeded", 1L, Map.of("reusedPlanStepIds", List.of(1, 2, 3)))),
            Map.of(), 1L
        );
        ChatModel model = mock(ChatModel.class);
        AgentOrchestrator orchestrator = newOrchestrator(model);
        InterpretationPlanRuntime.ExecutionResult cumulative = orchestrator.cumulativeEvidenceResult(
            repairedAttempt, List.of(firstAttempt, repairedAttempt));
        Map<String, Object> metadata = new LinkedHashMap<>();

        AgentOrchestrator.RecordCoverageBundle coverage = orchestrator.buildRecordCoverageBundle(
            model, "query customer 070200046604 assets", cumulative, Map.of(), metadata, () -> false);
        String answer = orchestrator.ensureConcreteBatchEvidencePresented(
            "8/8 templates succeeded but concrete data is unavailable",
            "query customer 070200046604 assets",
            cumulative,
            metadata
        );

        assertThat(coverage.returnedRecordCount()).isEqualTo(1);
        assertThat(coverage.processedRecordCount()).isEqualTo(1);
        assertThat(answer).contains("847174.25", "42263.81", "asset-template");
        assertThat(metadata)
            .containsEntry("recordAnalysisCoverageComplete", true)
            .containsEntry("concreteBatchEvidencePresentationFallback", true);
    }

    @Test
    void iteratesUntilEveryOversizedReturnedRecordIsCovered() {
        List<Map<String, Object>> records = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            records.add(Map.of(
                "ROW_ID", "row-" + index,
                "VALUE", "value-" + index + "-" + "x".repeat(300)
            ));
        }
        ToolCallResult success = new ToolCallResult(
            "records", "api_template_execute", "records-template", "asset-1",
            "SUCCESS", 10L, "evidence-1",
            Map.of(
                "analysisContext", Map.of(
                    "schemaVersion", "data_analysis_context.v1",
                    "source", Map.of(
                        "displayName", "Position detail API",
                        "description", "Returns account positions"),
                    "capability", Map.of("summary", "Position analysis"),
                    "business", Map.of("code", "positions"),
                    "schema", Map.of("fields", List.of(
                        Map.of("name", "VALUE", "description", "Position value")))),
                "data", Map.of("body", records)), Map.of()
        );
        ToolCallBatchResult batch = new ToolCallBatchResult(
            "batch-1", "SEQUENTIAL", "start", "end", "SUCCESS",
            new ToolCallBatchResult.Summary(1, 1, 0, 0, 0, 1), List.of(success)
        );
        InterpretationPlanRuntime.StepExecution step = new InterpretationPlanRuntime.StepExecution(
            3, "mcp_tool", "mcp_chatchat_mcp_server_api_template_execute", true,
            batch, null, null, null, 10L, Map.of("batchExecution", true)
        );
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "success", true, false, null, null, List.of(step), Map.of(), 10L
        );
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(String.class))).thenReturn("chunk evidence summary");
        AgentOrchestrator orchestrator = newOrchestrator(model);
        Map<String, Object> metadata = new LinkedHashMap<>(Map.of(
            "tenantId", "tenant-summary",
            "userId", "user-summary",
            "agentRunId", "run-summary",
            "requestId", "request-summary",
            "conversationId", "conversation-summary"
        ));

        AgentOrchestrator.RecordCoverageBundle coverage = orchestrator.buildRecordCoverageBundle(
            model, "analyze returned records", result, Map.of("__agentRunId", "run-summary"), metadata, () -> false);
        String answer = orchestrator.ensureCompleteRecordCoveragePresented(
            "all templates succeeded", coverage, metadata);

        assertThat(coverage.returnedRecordCount()).isEqualTo(60);
        assertThat(coverage.processedRecordCount()).isEqualTo(60);
        assertThat(coverage.coverageComplete()).isTrue();
        assertThat(coverage.iterative()).isTrue();
        assertThat(coverage.iterations()).isGreaterThan(1);
        assertThat(coverage.summaryResults()).hasSize(coverage.iterations())
            .allSatisfy(entry -> assertThat(entry.toMap().toString())
                .contains("schemaVersion=analysis_summary_result.v1", "scope=DATASET_CHUNK")
                .contains("datasetReference=records", "recordFrom=", "recordTo=", "totalRecords=60")
                .contains("tenantId=tenant-summary", "runId=run-summary",
                    "authority=RUNTIME_REQUEST_CONTEXT"));
        assertThat(answer)
            .contains("chunk evidence summary")
            .contains("60/60")
            .contains("受治理证据摘要")
            .doesNotContain("\u5168\u91cf\u8bb0\u5f55\u8986\u76d6\u5206\u6790", "value-59-");
        assertThat(metadata)
            .containsEntry("recordAnalysisCoverageComplete", true)
            .containsEntry("recordAnalysisReturnedRecordCount", 60)
            .containsEntry("recordAnalysisProcessedRecordCount", 60)
            .containsEntry("recordAnalysisCoverageAppendixApplied", false)
            .containsEntry("recordAnalysisNarrativeCoverageApplied", true);
        assertThat(metadata.get("analysisSummaryGovernanceBridge").toString())
            .contains("analysis_summary_bridge.v1", "summary_governance.v1")
            .contains("analysis_summary_result.v1", "scope=DATASET_CHUNK")
            .contains("datasetReference=records", "chunkIndex=1", "content=chunk evidence summary");
        verify(model, times(coverage.iterations())).chat(argThat((String prompt) ->
            prompt.contains("analysis_summary_bridge.v1")
                && prompt.contains("summary_governance.v1")
                && prompt.contains("data_analysis_context.v1")
                && prompt.contains("Position detail API")
                && prompt.contains("Returns account positions")
                && prompt.contains("Position value")
                && prompt.contains("Field comments are not display labels")
                && prompt.contains("Analysis summary bridge position")));
    }

    @Test
    void appliesCompleteRecordCoverageToNonBatchProtocolRows() {
        InterpretationPlanRuntime.StepExecution step = new InterpretationPlanRuntime.StepExecution(
            1, "mcp_tool", "sql_metadata_search", true,
            Map.of("data", Map.of("rows", List.of(
                Map.of("TABLE_NAME", "orders", "ROW_COUNT", 10),
                Map.of("TABLE_NAME", "assets", "ROW_COUNT", 20)
            ))),
            null, null, null, 10L, Map.of()
        );
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "success", true, false, null, null, List.of(step), Map.of(), 10L
        );
        ChatModel model = mock(ChatModel.class);
        AgentOrchestrator.RecordCoverageBundle coverage = newOrchestrator(model)
            .buildRecordCoverageBundle(model, "analyze metadata", result, Map.of(),
                new LinkedHashMap<>(), () -> false);

        assertThat(coverage.returnedRecordCount()).isEqualTo(2);
        assertThat(coverage.processedRecordCount()).isEqualTo(2);
        assertThat(coverage.coverageComplete()).isTrue();
        assertThat(coverage.promptEvidence()).contains("orders", "assets");
        verify(model, never()).chat(any(String.class));
    }

    @Test
    void appendsGovernedModelAnalysisWhenCandidateContainsOnlyHeadingsAndDataSource() {
        Map<String, Object> output = Map.of(
            "analysisContext", Map.of(
                "source", Map.of("displayName", "资产与盈亏概览", "description", "客户资产快照"),
                "capability", Map.of("description", "分析资产规模与当日盈亏"),
                "business", Map.of("name", "资产分析"),
                "schema", Map.of("fields", List.of(
                    Map.of("name", "ZZC", "description", "总资产"),
                    Map.of("name", "DRYK", "description", "当日盈亏")))),
            "data", Map.of("body", List.of(Map.of("ZZC", 847174.25, "DRYK", 42263.81))));
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "success", true, false, null, null,
            List.of(new InterpretationPlanRuntime.StepExecution(
                1, "mcp_tool", "livedata_asset_summary", true,
                output, null, null, null, 10L, Map.of())),
            Map.of(), 10L);
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(String.class))).thenReturn(
            "该资产快照显示总资产为 847174.25，当日盈亏为 42263.81；该结论仅基于当前返回记录。");
        AgentOrchestrator orchestrator = newOrchestrator(model);
        Map<String, Object> metadata = new LinkedHashMap<>();

        AgentOrchestrator.RecordCoverageBundle coverage = orchestrator.buildRecordCoverageBundle(
            model, "分析客户资产", result, Map.of(), metadata, () -> false);
        String answer = orchestrator.ensureCompleteRecordCoveragePresented(
            "## 资产与盈亏概览\n\n数据来源：`livedata_asset_summary`", coverage, metadata);

        assertThat(answer)
            .contains("数据分析总结", "资产与盈亏概览", "总资产为 847174.25", "当日盈亏为 42263.81");
        assertThat(metadata)
            .containsEntry("governedNarrativeAnalysisAppended", true)
            .containsEntry("governedNarrativeAnalysisSummaryCount", 1);
        verify(model).chat(argThat((String prompt) ->
            prompt.contains("客户资产快照")
                && prompt.contains("分析资产规模与当日盈亏")
                && prompt.contains("总资产")
                && prompt.contains("当日盈亏")));
    }

    @Test
    void adaptsMcpToolMetadataAndInheritsItIntoAssetCenterDatasetSummary() {
        String toolName = "mcp_asset_center_position_query";
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName)
            .title("资产中心查询")
            .description("查询客户资产快照并支持持仓分析")
            .author("MCP:asset-center")
            .categories(List.of("mcp", "asset_analysis"))
            .category("asset_analysis")
            .outputType("json")
            .metadata(Map.of(
                "serviceId", "asset-center",
                "remoteToolName", "position_query",
                "mcpToolMeta", Map.of(
                    "capabilitySpec", Map.of("supportedScenarios", List.of("position_analysis")),
                    "businessGroup", Map.of("name", "客户资产"),
                    "semantics", Map.of("currency", "CNY", "timeBasis", "business_date"),
                    "quality", Map.of("freshness", "T+1"))))
            .build());
        Map<String, Object> output = Map.of(
            "structuredData", List.of(Map.of(
                "dataset", "positions",
                "analysisContext", Map.of(
                    "source", Map.of("displayName", "持仓明细"),
                    "semantics", Map.of("granularity", "account-security-day"),
                    "schema", Map.of("fields", List.of(
                        Map.of("name", "marketValue", "description", "持仓市值")))),
                "rows", List.of(Map.of("marketValue", 100, "businessDate", "2026-07-31")))));
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "success", true, false, null, null,
            List.of(new InterpretationPlanRuntime.StepExecution(
                1, "mcp_tool", toolName, true, output, null, null, null, 10L, Map.of())),
            Map.of(), 10L);
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(String.class))).thenReturn("持仓市值为 100 元，口径为账户-证券-业务日。 ");
        AgentOrchestrator orchestrator = newOrchestrator(model, registry);

        AgentOrchestrator.RecordCoverageBundle coverage = orchestrator.buildRecordCoverageBundle(
            model, "分析客户资产", result, Map.of(), new LinkedHashMap<>(), () -> false);

        assertThat(coverage.summaryResults()).hasSize(1);
        assertThat(coverage.summaryResults().get(0).analysisContext().toString())
            .contains("资产中心查询", "持仓明细", "position_analysis", "客户资产")
            .contains("currency=CNY", "timeBasis=business_date", "account-security-day")
            .contains("freshness=T+1", "持仓市值");
        verify(model).chat(argThat((String prompt) ->
            prompt.contains("资产中心查询")
                && prompt.contains("持仓明细")
                && prompt.contains("position_analysis")
                && prompt.contains("currency\":\"CNY")
                && prompt.contains("account-security-day")
                && prompt.contains("freshness\":\"T+1")
                && prompt.contains("持仓市值")));
    }

    @Test
    void iterativelySummarizesCompleteLinuxStdoutInsteadOfDroppingTheMiddle() {
        String stdout = "LINUX_HEAD\n" + "metric=value\n".repeat(2_000) + "LINUX_TAIL";
        Map<String, Object> output = Map.of(
            "dataSchema", "ssh_steps.v1",
            "data", Map.of(
                "stdout", stdout,
                "stderr", "",
                "outputLimits", Map.of("stdoutTruncated", false, "stderrTruncated", false),
                "commandContext", Map.of(
                    "schemaVersion", "template_result_context.v1",
                    "templateId", "CHECK_METRICS",
                    "templateName", "Runtime metrics",
                    "description", "Assess current runtime metrics",
                    "executionMode", "SINGLE",
                    "commands", List.of(Map.of(
                        "commandId", "metrics",
                        "description", "Collect runtime metric values",
                        "resultReference", "$.data.stdout")),
                    "references", List.of())
            )
        );
        InterpretationPlanRuntime.StepExecution step = new InterpretationPlanRuntime.StepExecution(
            1, "mcp_tool", "linux_command_execute", true,
            output, null, null, null, 10L, Map.of()
        );
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "success", true, false, null, null, List.of(step), Map.of(), 10L
        );
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(String.class))).thenReturn("linux output chunk summary");
        Map<String, Object> metadata = new LinkedHashMap<>();

        AgentOrchestrator.RecordCoverageBundle coverage = newOrchestrator(model)
            .buildRecordCoverageBundle(model, "analyze linux output", result, Map.of(), metadata, () -> false);

        assertThat(coverage.iterative()).isTrue();
        assertThat(coverage.coverageComplete()).isTrue();
        assertThat(coverage.returnedRecordCount()).isGreaterThan(1);
        assertThat(coverage.processedRecordCount()).isEqualTo(coverage.returnedRecordCount());
        assertThat(coverage.promptEvidence()).contains("linux_command_execute#stdout", "linux output chunk summary");
        assertThat(metadata).containsEntry("recordAnalysisIterative", true);
        assertThat(metadata).containsEntry("recordAnalysisSourceContentComplete", true);
        verify(model, times(coverage.iterations())).chat(argThat((String prompt) ->
            prompt.contains("User analysis objective: analyze linux output")
                && prompt.contains("Assess current runtime metrics")
                && prompt.contains("Collect runtime metric values")
                && prompt.contains("$.data.stdout")));
    }

    @Test
    void analyzesNonEmptyExternalizedPreviewWhenFullEvidenceCannotBeResolved() {
        String preview = "USER PID CPU MEM COMMAND\n"
            + "hive 324493 2.0 8.1 /usr/java/bin/java inceptor-executor\n"
            + "root 410002 0.5 1.2 container-runtime";
        Map<String, Object> reference = Map.of(
            "outputExternal", true,
            "outputTruncated", true,
            "documentId", "tool-output:tenant:current-run:process-check:abc123",
            "evidenceId", "tool:linux_command_execute:abc123",
            "preview", preview
        );
        ToolCallResult child = new ToolCallResult(
            "process-check", "linux_command_execute", "PROCESS_CHECK", "host-1",
            "SUCCESS", 20L, "audit-evidence", reference, Map.of());
        ToolCallBatchResult batch = new ToolCallBatchResult(
            "diagnostic-step", "SEQUENTIAL", "start", "end", "SUCCESS",
            new ToolCallBatchResult.Summary(1, 1, 0, 0, 0, 1), List.of(child));
        InterpretationPlanRuntime.StepExecution step = new InterpretationPlanRuntime.StepExecution(
            1, "mcp_tool", "linux_command_execute", true,
            batch, null, null, null, 10L, Map.of());
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "success", true, false, null, null, List.of(step), Map.of(), 10L);
        ChatModel model = mock(ChatModel.class);
        Map<String, Object> metadata = new LinkedHashMap<>();

        AgentOrchestrator.RecordCoverageBundle coverage = newOrchestrator(model)
            .buildRecordCoverageBundle(model, "analyze returned process evidence", result,
                Map.of(), metadata, () -> false);
        String summaryPrompt = newOrchestrator(model).buildInterpretationPlanSummaryPrompt(
            "analyze returned process evidence", null, result, List.of(result), List.of(), List.of());
        String guardedAnswer = newOrchestrator(model).ensureCompleteRecordCoveragePresented(
            "The complete list is unavailable; read the external document.", coverage, metadata);

        assertThat(coverage.returnedRecordCount()).isEqualTo(1);
        assertThat(coverage.processedRecordCount()).isEqualTo(1);
        assertThat(coverage.coverageComplete()).isTrue();
        assertThat(coverage.sourceContentComplete()).isFalse();
        assertThat(coverage.promptEvidence())
            .contains("externalized-preview", "324493", "inceptor-executor");
        assertThat(summaryPrompt)
            .contains("PARTIAL_PREVIEW")
            .contains("absence from a preview never proves absence in the target");
        assertThat(guardedAnswer)
            .contains("全量记录覆盖分析", "324493", "inceptor-executor",
                "覆盖校验：1/1（已完整处理返回预览，源内容不完整）");
        assertThat(metadata)
            .containsEntry("recordAnalysisSourceContentComplete", false)
            .containsEntry("recordAnalysisCoverageAppendixApplied", true);
        verify(model, never()).chat(any(String.class));
    }

    @Test
    void recordCoverageUsesFullRuntimeCachedBatchChildInsteadOfExternalizedPreview() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolMetadata("linux_command_execute")).thenReturn(ToolMetadata.builder()
            .id("linux_command_execute").title("Linux command").build());
        String stdout = "CONTAINER ID IMAGE STATUS\n"
            + "abc123 transwarp/quark Up 3 days\n".repeat(12_000);
        when(registry.executeEnhancedTool(any(), any())).thenReturn(ToolOutput.success(Map.of(
            "dataSchema", "ssh_steps.v1",
            "data", Map.of(
                "stdout", stdout,
                "stderr", "",
                "outputLimits", Map.of("stdoutTruncated", false, "stderrTruncated", false)
            )
        )));
        ToolRuntimeProperties runtimeProperties = toolRuntimeProperties();
        runtimeProperties.setMaxOutputBytes(16_384);
        ToolRuntimeService runtime = new ToolRuntimeService(
            registry, new ObjectMapper(), runtimeProperties, List.of(), List.of());
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(String.class))).thenReturn("Docker containers include abc123 transwarp/quark, status Up 3 days.");
        try {
            ToolRuntimeExecution execution = runtime.execute(ToolRuntimeRequest.builder()
                .toolName("linux_command_execute").runtimeMode("agent_chat")
                .requestId("run-1:container_status").conversationId("conversation-1")
                .tenantId("tenant-1").userId("user-1")
                .allowedTools(List.of("linux_command_execute"))
                .toolInput(ToolInput.builder().userId("user-1").parameters(Map.of()).build()).build());
            ToolCallResult child = new ToolCallResult(
                "container_status", "linux_command_execute", "CHECK_DOCKER_CONTAINERS", "host-1",
                "SUCCESS", 20L, "audit-evidence", execution.output().getData(), Map.of());
            ToolCallBatchResult batch = new ToolCallBatchResult(
                "diagnostic-step", "SEQUENTIAL", "start", "end", "SUCCESS",
                new ToolCallBatchResult.Summary(1, 1, 0, 0, 0, 1), List.of(child));
            InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
                "success", true, false, null, null, List.of(new InterpretationPlanRuntime.StepExecution(
                    1, "mcp_tool", "linux_command_execute", true,
                    batch, null, null, null, 10L, Map.of())), Map.of(), 10L);
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                model, registry, runtime, new ObjectMapper(), new ModelsConfig());

            AgentOrchestrator.RecordCoverageBundle coverage = orchestrator.buildRecordCoverageBundle(
                model, "analyze docker containers", result, Map.of(), new LinkedHashMap<>(), () -> false);

            assertThat(coverage.sourceContentComplete()).isTrue();
            assertThat(coverage.promptEvidence())
                .contains("CHECK_DOCKER_CONTAINERS#stdout", "transwarp/quark")
                .doesNotContain("externalized-preview");
        } finally {
            runtime.shutdown();
        }
    }

    @Test
    void sqlScriptCoverageProcessesEveryFetchedRowAndReportsDatabaseLimitCompleteness() {
        Map<String, Object> output = Map.of(
            "dataSchema", "sql_script_result.v1",
            "analysisContext", Map.of(
                "schemaVersion", "data_analysis_context.v1",
                "source", Map.of("displayName", "数据库运行指标"),
                "capability", Map.of("description", "分析活动会话与锁等待"),
                "business", Map.of("name", "数据库运维"),
                "schema", Map.of("fields", List.of(
                    Map.of("name", "SID", "description", "会话编号"))),
                "relationships", Map.of()),
            "data", Map.of("results", List.of(
                Map.of(
                    "statementIndex", 1,
                    "stepCode", "ACTIVE_SESSIONS",
                    "possiblyTruncated", false,
                    "rows", List.of(Map.of("SID", 1), Map.of("SID", 2))
                ),
                Map.of(
                    "statementIndex", 2,
                    "stepCode", "LOCK_WAITS",
                    "possiblyTruncated", true,
                    "rows", List.of(Map.of("WAITING_SID", 9))
                )
            ))
        );
        InterpretationPlanRuntime.StepExecution step = new InterpretationPlanRuntime.StepExecution(
            1, "mcp_tool", "sql_script_execute", true,
            output, null, null, null, 10L, Map.of()
        );
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "success", true, false, null, null, List.of(step), Map.of(), 10L
        );
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(String.class))).thenReturn("SQL 分块业务分析");
        Map<String, Object> metadata = new LinkedHashMap<>();

        AgentOrchestrator.RecordCoverageBundle coverage = newOrchestrator(model)
            .buildRecordCoverageBundle(model, "analyze sql script", result, Map.of(), metadata, () -> false);

        assertThat(coverage.returnedRecordCount()).isEqualTo(3);
        assertThat(coverage.processedRecordCount()).isEqualTo(3);
        assertThat(coverage.promptEvidence())
            .contains("#statement-1", "#statement-2", "SID", "SQL 分块业务分析")
            .contains("sourceContentComplete=false");
        assertThat(metadata)
            .containsEntry("recordAnalysisCoverageComplete", true)
            .containsEntry("recordAnalysisSourceContentComplete", false);
        verify(model, times(2)).chat(argThat((String prompt) ->
            prompt.contains("数据库运行指标")
                && prompt.contains("分析活动会话与锁等待")
                && prompt.contains("会话编号")));
    }

    @Test
    void dagControllerFailureStillSelectsSoleReadyFinalAnswerStep() {
        InterpretationPlan.Step finalStep = new InterpretationPlan.Step(
            3,
            "final_answer",
            "",
            Map.of("answer", "生成客户信息表 DDL"),
            List.of(1, 2),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            null,
            null,
            new InterpretationPlan.Plan(List.of(finalStep)),
            null,
            null
        );
        InterpretationPlanRuntime.DagDecisionRequest request =
            new InterpretationPlanRuntime.DagDecisionRequest(
                plan,
                Set.of(3),
                Map.of(),
                List.of(),
                Set.of(1, 2),
                3,
                InterpretationExecutionProtocol.VERSION,
                "trace",
                ""
            );

        InterpretationPlanRuntime.DagDecision decision =
            newOrchestrator(mock(ChatModel.class)).dagDecisionFailureFallback(
                request,
                new IllegalStateException()
            );

        assertThat(decision.action()).isEqualTo("execute_step");
        assertThat(decision.stepIds()).containsExactly(3);
        assertThat(decision.reason()).contains("sole remaining final_answer step");
    }

    @Test
    void dagStatusKeepsSuccessfulTruncatedEvidenceSeparateFromExecutionFailure() {
        InterpretationPlanRuntime.StepExecution successfulPartialStep =
            new InterpretationPlanRuntime.StepExecution(
                3,
                "mcp_tool",
                "future_metadata_tool",
                true,
                Map.of("outputTruncated", true, "preview", Map.of("items", List.of("one"))),
                null,
                null,
                null,
                5L,
                Map.of(
                    "toolExecutionStatus", "SUCCEEDED",
                    "evidenceSufficiency", "INSUFFICIENT",
                    "stepFulfillmentStatus", "PARTIAL",
                    "partialEvidence", true
                )
            );
        InterpretationPlanRuntime.DagDecisionRequest request =
            new InterpretationPlanRuntime.DagDecisionRequest(
                null,
                Set.of(4),
                Map.of(3, successfulPartialStep),
                List.of(successfulPartialStep),
                Set.of(1, 2, 3),
                4,
                InterpretationExecutionProtocol.VERSION,
                "trace",
                ""
            );
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));

        assertThat(orchestrator.runtimeDagExecutionStatus(request))
            .contains("succeededStepIds=[3]")
            .contains("failedStepIds=[]")
            .contains("partialEvidenceStepIds=[3]");
        assertThat(orchestrator.buildInterpretationPlanDagDecisionPrompt("query", null, request))
            .contains("outputTruncated=true")
            .contains("none of these means the tool call failed")
            .contains("Never require an indexed child")
            .contains("Never call a successful truncated result a failed step");
    }

    @Test
    void dagDecisionUsesItsOwnBoundedEvidenceBudget() {
        String repeatedPayload = "enterprise-evidence-".repeat(8_000);
        String oversizedSystemPrompt = "DAG_SYSTEM_SENTINEL-".repeat(8_000);
        InterpretationPlanRuntime.StepExecution evidenceStep =
            new InterpretationPlanRuntime.StepExecution(
                1, "mcp_tool", "generic_metadata_search", true,
                Map.of(
                    "count", 1,
                    "results", List.of(Map.of("name", "candidate", "details", repeatedPayload))
                ),
                null, null, null, 5L,
                Map.of("partialEvidence", true, "evidenceSufficiency", "INSUFFICIENT")
            );
        InterpretationPlanRuntime.DagDecisionRequest request =
            new InterpretationPlanRuntime.DagDecisionRequest(
                null, Set.of(2), Map.of(1, evidenceStep), List.of(evidenceStep), Set.of(1),
                2, InterpretationExecutionProtocol.VERSION, "trace", ""
            );

        String prompt = newOrchestrator(mock(ChatModel.class))
            .buildInterpretationPlanDagDecisionPrompt("review evidence", oversizedSystemPrompt, request);

        assertThat(prompt)
            .contains("context_compression: {", "enabled=true")
            .contains("_BOUNDED")
            .doesNotContain(repeatedPayload)
            .doesNotContain("DAG_SYSTEM_SENTINEL")
            .hasSizeLessThan(50_000);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mandatoryWorkflowLocallyReviewsCompleteEnterpriseMetadataResult() throws Exception {
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        Method method = AgentOrchestrator.class.getDeclaredMethod(
            "mandatoryWorkflowResultReview",
            String.class,
            ToolOutput.class
        );
        method.setAccessible(true);
        ToolOutput output = ToolOutput.success(Map.of(
            "schemaVersion", "enterprise_metadata_field_discovery.v1",
            "success", true,
            "sourceFieldCount", 12,
            "matchedFieldCount", 12,
            "coverage", Map.of(
                "inputFieldCount", 12,
                "processedFieldCount", 12,
                "allFieldsProcessed", true
            )
        ));

        Map<String, Object> review = (Map<String, Object>) method.invoke(
            orchestrator,
            "mcp_chatchat_mcp_server_enterprise_metadata_search",
            output
        );

        assertThat(review)
            .containsEntry("satisfied", true)
            .containsEntry("reviewType", "LOCAL_CONTRACT_REVIEW")
            .containsEntry("sourceFieldCount", 12)
            .containsEntry("matchedFieldCount", 12)
            .containsEntry("processedFieldCount", 12)
            .containsEntry("allFieldsProcessed", true);

        Map<String, Object> partialReview = (Map<String, Object>) method.invoke(
            orchestrator,
            "mcp_chatchat_mcp_server_enterprise_metadata_search",
            ToolOutput.success(Map.of(
                "schemaVersion", "enterprise_metadata_field_discovery.v1",
                "success", true,
                "sourceFieldCount", 12,
                "matchedFieldCount", 11,
                "coverage", Map.of(
                    "inputFieldCount", 12,
                    "processedFieldCount", 11,
                    "allFieldsProcessed", false
                )
            ))
        );
        assertThat(partialReview)
            .containsEntry("satisfied", false)
            .containsEntry("sourceFieldCount", 12)
            .containsEntry("processedFieldCount", 11);

        Map<String, Object> genericSuccessReview = (Map<String, Object>) method.invoke(
            orchestrator,
            "mcp_example_read_tool",
            ToolOutput.success(Map.of("schemaVersion", "example_result.v1", "count", 1))
        );
        assertThat(genericSuccessReview)
            .containsEntry("satisfied", true)
            .containsEntry("reviewType", "LOCAL_CONTRACT_REVIEW");

        for (String discoveryTool : List.of(
            "mcp_chatchat_mcp_server_database_query_template_query",
            "mcp_chatchat_mcp_server_api_template_query"
        )) {
            Map<String, Object> emptyTemplateReview = (Map<String, Object>) method.invoke(
                orchestrator,
                discoveryTool,
                ToolOutput.success(Map.of(
                    "success", true,
                    "returnedCount", 0,
                    "templates", List.of()
                ))
            );
            assertThat(emptyTemplateReview)
                .containsEntry("satisfied", false)
                .containsEntry("resultCode", "NO_MATCHING_TEMPLATE")
                .containsEntry("returnedCount", 0);
        }

        Map<String, Object> failureReview = (Map<String, Object>) method.invoke(
            orchestrator,
            "mcp_example_read_tool",
            ToolOutput.failure("backend unavailable")
        );
        assertThat(failureReview).containsEntry("satisfied", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mandatoryWorkflowBlocksSqlAndApiExecutionAfterEmptyTemplateDiscovery() throws Exception {
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        Method method = AgentOrchestrator.class.getDeclaredMethod(
            "mandatoryWorkflowPredecessorReview",
            String.class,
            List.class
        );
        method.setAccessible(true);

        for (Map.Entry<String, String> scenario : Map.of(
            "mcp_chatchat_mcp_server_sql_query_execute",
            "mcp_chatchat_mcp_server_database_query_template_query",
            "mcp_chatchat_mcp_server_http_request_execute",
            "mcp_chatchat_mcp_server_api_template_query"
        ).entrySet()) {
            InteractionToolTrace discovery = InteractionToolTrace.builder()
                .toolName(scenario.getValue())
                .success(true)
                .output("{\"success\":true,\"returnedCount\":0,\"templates\":[]}")
                .build();
            Map<String, Object> review = (Map<String, Object>) method.invoke(
                orchestrator,
                scenario.getKey(),
                List.of(discovery)
            );
            assertThat(review)
                .containsEntry("satisfied", false)
                .containsEntry("resultCode", "NO_MATCHING_TEMPLATE")
                .containsEntry("predecessorToolName", scenario.getValue())
                .containsEntry("blockedDependentToolName", scenario.getKey());
        }
    }

    @Test
    void interpretationPlanWorkflowBlockStopsBeforeAttemptsExhaustedSynthesis() throws Exception {
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        Method method = AgentOrchestrator.class.getDeclaredMethod(
            "finishInterpretationPlanWorkflowBlockedIfPending",
            List.class,
            Map.class,
            List.class,
            String.class,
            String.class
        );
        method.setAccessible(true);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("interpretationPlanWorkflowBlocked", true);
        metadata.put("interpretationPlanWorkflowMissingTools", List.of());
        metadata.put("interpretationPlanWorkflowMissingPlanStepIds", List.of(1));
        List<String> observations = new ArrayList<>();

        AgentOrchestrator.AgentExecutionResult result = (AgentOrchestrator.AgentExecutionResult) method.invoke(
            orchestrator,
            List.of(),
            metadata,
            observations,
            "interpretation_plan_workflow_incomplete",
            "InterpretationPlan workflow guard blocked final_answer before all required DAG steps completed."
        );

        assertThat(result).isNotNull();
        assertThat(result.answer())
            .contains("InterpretationPlan workflow is incomplete")
            .doesNotContain("Synthesized answer from executed steps");
        assertThat(result.metadata())
            .containsEntry("stopReason", "interpretation_plan_workflow_incomplete")
            .containsEntry("fatalExecutionBlocked", true)
            .containsEntry("errorCode", "PLAN_INVALID_REQUIRED_STEP_NOT_EXECUTED");
        assertThat(observations)
            .anyMatch(value -> value.contains("Missing tools: []")
                && value.contains("missing plan steps: [1]"));
    }

    @Test
    void interpretationPlanWorkflowBlockIsReconciledAfterMissingToolSucceedsInFallback() throws Exception {
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        Method method = AgentOrchestrator.class.getDeclaredMethod(
            "finishInterpretationPlanWorkflowBlockedIfPending",
            List.class,
            Map.class,
            List.class,
            String.class,
            String.class
        );
        method.setAccessible(true);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("interpretationPlanWorkflowBlocked", true);
        metadata.put("interpretationPlanWorkflowMissingTools",
            List.of("mcp_chatchat_mcp_server_document_search"));
        metadata.put("interpretationPlanWorkflowMissingPlanStepIds", List.of());
        List<String> observations = new ArrayList<>();
        InteractionToolTrace successfulFallback = InteractionToolTrace.builder()
            .toolName("document_search")
            .success(true)
            .build();

        AgentOrchestrator.AgentExecutionResult result = (AgentOrchestrator.AgentExecutionResult) method.invoke(
            orchestrator,
            List.of(successfulFallback),
            metadata,
            observations,
            "interpretation_plan_workflow_incomplete",
            "InterpretationPlan workflow guard blocked final_answer before all required DAG steps completed."
        );

        assertThat(result).isNull();
        assertThat(metadata)
            .containsEntry("interpretationPlanWorkflowBlocked", false)
            .containsEntry("interpretationPlanWorkflowMissingTools", List.of())
            .containsEntry("interpretationPlanWorkflowResolvedAfterFallback", true)
            .containsEntry("interpretationPlanWorkflowReconciledFromTerminalEvents", true);
        assertThat(observations)
            .anyMatch(value -> value.contains("reconciled later successful tool observations"));
    }

    @Test
    void failedFallbackDoesNotClearInterpretationPlanWorkflowBlock() throws Exception {
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        Method method = AgentOrchestrator.class.getDeclaredMethod(
            "finishInterpretationPlanWorkflowBlockedIfPending",
            List.class,
            Map.class,
            List.class,
            String.class,
            String.class
        );
        method.setAccessible(true);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("interpretationPlanWorkflowBlocked", true);
        metadata.put("interpretationPlanWorkflowMissingTools", List.of("document_search"));
        metadata.put("interpretationPlanWorkflowMissingPlanStepIds", List.of());

        AgentOrchestrator.AgentExecutionResult result = (AgentOrchestrator.AgentExecutionResult) method.invoke(
            orchestrator,
            List.of(InteractionToolTrace.builder()
                .toolName("mcp_chatchat_mcp_server_document_search")
                .success(false)
                .errorMessage("backend unavailable")
                .build()),
            metadata,
            new ArrayList<>(),
            "interpretation_plan_workflow_incomplete",
            "InterpretationPlan workflow guard blocked final_answer before all required DAG steps completed."
        );

        assertThat(result).isNotNull();
        assertThat(result.answer()).contains("Missing tools: [document_search]");
        assertThat(metadata).containsEntry("interpretationPlanWorkflowBlocked", true);
    }

    @Test
    void finalSynthesisPromptIncludesRuntimeDiagnosticCoverageContract() {
        DiagnosticRun diagnosticRun = new DiagnosticRun(
            "environment_health_check",
            "mixed",
            List.of(
                new DiagnosticRun.CheckResult(
                    "database_availability", "instance_status", "availability", true, 1,
                    List.of(1), "completed", null, List.of("iteration:1:step:1:tool:database_status_query"),
                    100.0, 0.95
                ),
                new DiagnosticRun.CheckResult(
                    "session_pressure", "session_overview", "performance", true, 2,
                    List.of(), "missing", "execution_budget_exhausted", List.of(), null, null
                )
            ),
            new DiagnosticRun.Coverage(2, 1, 0, 1, 0.5),
            new DiagnosticRun.Assessment(
                Map.of(
                    "availability", new DiagnosticRun.DimensionAssessment(100.0, 0.95, 1, 1, "ASSESSED"),
                    "performance", new DiagnosticRun.DimensionAssessment(null, null, 1, 0, "NOT_ASSESSED")
                ),
                null,
                0.95,
                "INSUFFICIENT_EVIDENCE"
            )
        );
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "completed",
            true,
            false,
            null,
            "partial",
            List.of(),
            Map.of("diagnosticRun", diagnosticRun),
            1L
        );

        String prompt = newOrchestrator(mock(ChatModel.class)).buildInterpretationPlanSummaryPrompt(
            "analyze environment health", null, result, List.of(), List.of()
        );

        assertThat(prompt)
            .contains("diagnosticRun (runtime-calculated coverage")
            .contains("database_availability", "session_pressure")
            .contains("execution_budget_exhausted", "INSUFFICIENT_EVIDENCE")
            .contains("Never convert tool success");
    }

    @Test
    @SuppressWarnings("unchecked")
    void incompleteDiagnosticCoveragePreventsPrematureEvidenceStop() throws Exception {
        DiagnosticRun diagnosticRun = new DiagnosticRun(
            "environment_health_check",
            "mixed",
            List.of(
                new DiagnosticRun.CheckResult(
                    "database_availability", "instance_status", "availability", true, 1,
                    List.of(1), "completed", null, List.of("iteration:1:step:1:tool:database_status_query"),
                    100.0, 0.95
                ),
                new DiagnosticRun.CheckResult(
                    "lock_contention", "lock_overview", "performance", true, 2,
                    List.of(), "missing", "execution_budget_exhausted", List.of(), null, null
                )
            ),
            new DiagnosticRun.Coverage(2, 1, 0, 1, 0.5),
            new DiagnosticRun.Assessment(Map.of(), null, null, "INSUFFICIENT_EVIDENCE")
        );
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "completed", true, false, null, null, List.of(), Map.of("diagnosticRun", diagnosticRun), 1L
        );
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        Method method = AgentOrchestrator.class.getDeclaredMethod(
            "analyzeInterpretationPlanEvidence",
            ChatModel.class,
            String.class,
            String.class,
            com.chatchat.agents.runtime.plan.InterpretationPlan.class,
            InterpretationPlanRuntime.ExecutionResult.class,
            int.class,
            List.class,
            Map.class,
            Map.class,
            BooleanSupplier.class
        );
        method.setAccessible(true);

        Map<String, Object> snapshot = (Map<String, Object>) method.invoke(
            orchestrator,
            null,
            "analyze environment health",
            null,
            null,
            result,
            1,
            List.of(),
            Map.of(),
            new LinkedHashMap<>(),
            (BooleanSupplier) () -> false
        );

        assertThat(snapshot)
            .containsEntry("sufficient", false)
            .containsEntry("diagnosticCoverageComplete", false);
        assertThat((List<?>) snapshot.get("missingEvidence"))
            .singleElement()
            .satisfies(gap -> {
                Map<?, ?> values = (Map<?, ?>) gap;
                assertThat(String.valueOf(values.get("checkId"))).isEqualTo("lock_contention");
                assertThat(String.valueOf(values.get("capability"))).isEqualTo("lock_overview");
                assertThat(String.valueOf(values.get("reason"))).isEqualTo("execution_budget_exhausted");
            });
        assertThat((List<?>) snapshot.get("nextActions"))
            .singleElement()
            .satisfies(action -> assertThat(String.valueOf(((Map<?, ?>) action).get("action")))
                .isEqualTo("complete_diagnostic_check"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnosticCoverageAccumulatesAcrossRewriteAttempts() throws Exception {
        DiagnosticRun firstRun = diagnosticRunForChecks(
            "completed", "missing", "execution_budget_exhausted"
        );
        DiagnosticRun secondRun = diagnosticRunForChecks(
            "missing", "completed", "already_completed_in_prior_attempt"
        );
        InterpretationPlanRuntime.ExecutionResult secondResult = new InterpretationPlanRuntime.ExecutionResult(
            "completed", true, false, null, null, List.of(), Map.of("diagnosticRun", secondRun), 1L
        );
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        Method method = AgentOrchestrator.class.getDeclaredMethod(
            "analyzeInterpretationPlanEvidence",
            ChatModel.class,
            String.class,
            String.class,
            com.chatchat.agents.runtime.plan.InterpretationPlan.class,
            InterpretationPlanRuntime.ExecutionResult.class,
            int.class,
            List.class,
            Map.class,
            Map.class,
            BooleanSupplier.class
        );
        method.setAccessible(true);

        Map<String, Object> snapshot = (Map<String, Object>) method.invoke(
            orchestrator,
            null,
            "analyze environment health",
            null,
            null,
            secondResult,
            2,
            List.of(Map.of("diagnosticRun", firstRun)),
            Map.of(),
            new LinkedHashMap<>(),
            (BooleanSupplier) () -> false
        );

        assertThat(snapshot)
            .containsEntry("sufficient", true)
            .containsEntry("diagnosticCoverageComplete", true);
        assertThat((List<?>) snapshot.get("missingEvidence")).isEmpty();
        assertThat(((java.util.Set<?>) snapshot.get("previouslyCompletedDiagnosticChecks")).stream()
            .map(String::valueOf).toList())
            .contains("databaseavailability");
    }

    private DiagnosticRun diagnosticRunForChecks(String databaseStatus,
                                                  String lockStatus,
                                                  String missingReason) {
        int completed = ("completed".equals(databaseStatus) ? 1 : 0)
            + ("completed".equals(lockStatus) ? 1 : 0);
        return new DiagnosticRun(
            "environment_health_check",
            "mixed",
            List.of(
                new DiagnosticRun.CheckResult(
                    "database_availability", "instance_status", "availability", true, 1,
                    "completed".equals(databaseStatus) ? List.of(1) : List.of(),
                    databaseStatus,
                    "completed".equals(databaseStatus) ? null : missingReason,
                    "completed".equals(databaseStatus)
                        ? List.of("iteration:1:step:1:tool:database_status_query") : List.of(),
                    "completed".equals(databaseStatus) ? 100.0 : null,
                    "completed".equals(databaseStatus) ? 0.95 : null
                ),
                new DiagnosticRun.CheckResult(
                    "lock_contention", "lock_overview", "performance", true, 2,
                    "completed".equals(lockStatus) ? List.of(2) : List.of(),
                    lockStatus,
                    "completed".equals(lockStatus) ? null : missingReason,
                    "completed".equals(lockStatus)
                        ? List.of("iteration:2:step:2:tool:database_lock_query") : List.of(),
                    null,
                    null
                )
            ),
            new DiagnosticRun.Coverage(2, completed, 0, 2 - completed, completed / 2.0),
            new DiagnosticRun.Assessment(Map.of(), null, null, "INSUFFICIENT_EVIDENCE")
        );
    }

    @Test
    void partialToolReviewPreservesSuccessfulAttemptForLimitedAnalysis() throws Exception {
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        InterpretationPlanRuntime.StepExecution step = new InterpretationPlanRuntime.StepExecution(
            1,
            "mcp_tool",
            "generic_tool",
            true,
            Map.of("rows", List.of(Map.of("value", 1))),
            null,
            null,
            null,
            1L,
            Map.of(
                "toolResultReviewSatisfied", false,
                "toolResultReviewPartialAccepted", true,
                "toolResultReviewReason", "more evidence is required"
            )
        );
        InterpretationPlanRuntime.ExecutionResult execution = new InterpretationPlanRuntime.ExecutionResult(
            "success", true, false, null, "partial", List.of(step), Map.of(), 1L);
        Method method = AgentOrchestrator.class.getDeclaredMethod(
            "rejectUnsatisfiedInterpretationPlanResult",
            String.class,
            InterpretationPlanRuntime.ExecutionResult.class,
            List.class,
            Map.class
        );
        method.setAccessible(true);
        List<String> observations = new ArrayList<>();
        Map<String, Object> metadata = new LinkedHashMap<>();

        InterpretationPlanRuntime.ExecutionResult preserved =
            (InterpretationPlanRuntime.ExecutionResult) method.invoke(
                orchestrator, "initial", execution, observations, metadata);

        assertThat(preserved).isSameAs(execution);
        assertThat(preserved.success()).isTrue();
        assertThat(observations).noneMatch(value -> value.contains("requires a full plan rewrite"));
        assertThat(metadata).containsEntry("interpretationPlanResultSatisfied", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void continuationWorkflowDagOmitsCompletedNodesThatAreNotRepeated() throws Exception {
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        InterpretationPlan rewrittenPlan = new InterpretationPlan(
            "1.0",
            null,
            null,
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(
                    1, "mcp_tool", "mcp_chatchat_mcp_server_sql_metadata_search",
                    Map.of("query", "orders"), List.of(), null, null),
                new InterpretationPlan.Step(
                    2, "mcp_tool", "mcp_chatchat_mcp_server_enterprise_metadata_search",
                    Map.of("query", "ADS table standard"), List.of(), null, null),
                new InterpretationPlan.Step(
                    3, "final_answer", "final_answer",
                    Map.of("answer", "partial assessment"), List.of(1, 2), null, null)
            )),
            null,
            null
        );
        List<Map<String, Object>> authoritativeDag = List.of(
            Map.of(
                "tool", "mcp_chatchat_mcp_server_database_asset_search",
                "dependsOnTools", List.of()),
            Map.of(
                "tool", "mcp_chatchat_mcp_server_sql_metadata_search",
                "dependsOnTools", List.of("mcp_chatchat_mcp_server_database_asset_search")),
            Map.of(
                "tool", "mcp_chatchat_mcp_server_enterprise_metadata_search",
                "dependsOnTools", List.of())
        );
        Method method = AgentOrchestrator.class.getDeclaredMethod(
            "authoritativeWorkflowDagForContinuation",
            Object.class,
            InterpretationPlan.class,
            Set.class
        );
        method.setAccessible(true);

        Object result = method.invoke(
            orchestrator,
            authoritativeDag,
            rewrittenPlan,
            Set.of(
                "mcp_chatchat_mcp_server_database_asset_search",
                "mcp_chatchat_mcp_server_sql_metadata_search",
                "mcp_chatchat_mcp_server_enterprise_metadata_search"
            )
        );

        List<Map<String, Object>> continuationDag = (List<Map<String, Object>>) result;
        assertThat(continuationDag)
            .extracting(node -> node.get("tool"))
            .containsExactly(
                "mcp_chatchat_mcp_server_sql_metadata_search",
                "mcp_chatchat_mcp_server_enterprise_metadata_search"
            );
        assertThat((List<String>) continuationDag.get(0).get("dependsOnTools")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void continuationWorkflowDagDoesNotConstrainRepeatedEvidenceRefinementTool() throws Exception {
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        String discoveryTool = "mcp_runtime_template_query";
        String executionTool = "mcp_runtime_template_execute";
        InterpretationPlan rewrittenPlan = new InterpretationPlan(
            "1.0",
            null,
            null,
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(
                    1, "mcp_tool", discoveryTool,
                    Map.of("query", "initial evidence"), List.of(), null, null),
                new InterpretationPlan.Step(
                    2, "mcp_tool", executionTool,
                    Map.of(), List.of(1), null, null),
                new InterpretationPlan.Step(
                    3, "mcp_tool", discoveryTool,
                    Map.of("query", "refined evidence"), List.of(1, 2), null, null),
                new InterpretationPlan.Step(
                    4, "mcp_tool", executionTool,
                    Map.of(), List.of(3), null, null),
                new InterpretationPlan.Step(
                    5, "final_answer", "final_answer",
                    Map.of("answer", "synthesize all evidence"), List.of(2, 4), null, null)
            )),
            null,
            null
        );
        List<Map<String, Object>> authoritativeDag = List.of(
            Map.of("tool", discoveryTool, "dependsOnTools", List.of()),
            Map.of("tool", executionTool, "dependsOnTools", List.of(discoveryTool))
        );
        Method method = AgentOrchestrator.class.getDeclaredMethod(
            "authoritativeWorkflowDagForContinuation",
            Object.class,
            InterpretationPlan.class,
            Set.class
        );
        method.setAccessible(true);

        Object result = method.invoke(
            orchestrator,
            authoritativeDag,
            rewrittenPlan,
            Set.of(discoveryTool, executionTool)
        );

        assertThat((List<Map<String, Object>>) result).isEmpty();
    }

    private static InterpretationPlanRuntime.ExecutionResult attemptResult(String status,
                                                                            boolean success,
                                                                            String evidence,
                                                                            String error) {
        InterpretationPlanRuntime.StepExecution step = new InterpretationPlanRuntime.StepExecution(
            1,
            "mcp_tool",
            "generic_tool",
            success,
            Map.of("evidence", evidence),
            error,
            null,
            null,
            1L
        );
        return new InterpretationPlanRuntime.ExecutionResult(
            status, success, false, error, null, List.of(step), Map.of(), 1L);
    }

    @Test
    void finalSynthesisPromptIncludesCompleteDynamicSqlLongCell() {
        String status = "BACKGROUND THREAD\n"
            + "x".repeat(5_000)
            + "\nTRANSACTIONS\ntransaction details"
            + "\nFILE I/O\nio details"
            + "\nBUFFER POOL AND MEMORY\nbuffer details";
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schemaVersion", "tool_execution_result.v1");
        output.put("kind", "sql_query");
        output.put("dataSchema", "sql_result.v1");
        output.put("success", true);
        output.put("status", "success");
        output.put("operation", Map.of("type", "sql.query", "statement", "SHOW ENGINE INNODB STATUS"));
        output.put("data", Map.of(
            "rowCount", 1,
            "possiblyTruncated", false,
            "columns", List.of("Type", "Name", "Status"),
            "rows", List.of(Map.of("Type", "InnoDB", "Name", "", "Status", status))
        ));
        InterpretationPlanRuntime.StepExecution step = new InterpretationPlanRuntime.StepExecution(
            1, "mcp_tool", "db_query_mysql_248_test_db", true, output, null, null, null, 10L
        );
        InterpretationPlanRuntime.ExecutionResult result = new InterpretationPlanRuntime.ExecutionResult(
            "success", true, false, null, null, List.of(step), Map.of(), 10L
        );

        String prompt = newOrchestrator(mock(ChatModel.class)).buildInterpretationPlanSummaryPrompt(
            "分析数据库状态", null, result, List.of(), List.of()
        );

        assertThat(prompt)
            .contains("authoritativeToolResultEvidence")
            .contains("x".repeat(5_000))
            .contains("TRANSACTIONS", "FILE I/O", "BUFFER POOL AND MEMORY")
            .contains("promptPreviewTruncated=false")
            .doesNotContain("SHOW ENGINE INNODB STATUS");
    }

    @Test
    void finalSynthesisRemovesExternalDocumentReferencesAbsentFromCurrentTurnEvidence() {
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        String currentDocumentId = "tool-output:tenant:current-run:call-1:abc123";
        String historicalDocumentId = "tool-output:tenant:historical-run:call-9:def456";
        InterpretationPlanRuntime.StepExecution step = new InterpretationPlanRuntime.StepExecution(
            1,
            "mcp_tool",
            "generic_execute",
            true,
            Map.of(
                "success", true,
                "documentId", currentDocumentId,
                "preview", "visible current-turn evidence"
            ),
            null,
            null,
            null,
            1L
        );
        InterpretationPlanRuntime.ExecutionResult currentEvidence =
            new InterpretationPlanRuntime.ExecutionResult(
                "success", true, false, null, null, List.of(step), Map.of(), 1L
            );
        Map<String, Object> metadata = new LinkedHashMap<>();

        String guarded = orchestrator.removeUnsupportedCurrentTurnDocumentReferences(
            "Current evidence is available.\n"
                + "- historical reference: `" + historicalDocumentId + "`\n"
                + "- current reference: `" + currentDocumentId + "`",
            currentEvidence,
            metadata
        );

        assertThat(guarded)
            .contains("Current evidence is available", currentDocumentId)
            .doesNotContain(historicalDocumentId, "historical reference");
        assertThat(metadata)
            .containsEntry("currentTurnDocumentReferenceGuardApplied", true)
            .containsEntry("unsupportedCurrentTurnDocumentReferencesRemoved", 1);
    }

    @Test
    void finalSynthesisCompressesOversizedCumulativeEvidence() {
        String repeatedPayload = "provider-noise-".repeat(12_000);
        Map<String, Object> output = Map.of(
            "success", true,
            "status", "completed_with_partial_evidence",
            "records", List.of(Map.of("payload", repeatedPayload))
        );
        InterpretationPlanRuntime.StepExecution step = new InterpretationPlanRuntime.StepExecution(
            1, "mcp_tool", "future_dynamic_tool", true, output,
            null, null, null, 10L
        );
        InterpretationPlanRuntime.ExecutionResult result =
            new InterpretationPlanRuntime.ExecutionResult(
                "completed_with_partial_evidence", true, false, null, null,
                List.of(step), Map.of(), 10L
            );

        String prompt = newOrchestrator(mock(ChatModel.class)).buildInterpretationPlanSummaryPrompt(
            "analyze the available evidence", null, result, List.of(), List.of()
        );

        assertThat(prompt)
            .contains("context_compression:", "enabled=true")
            .contains("compressedEvidence (runtime semantic projection)")
            .contains("completed_with_partial_evidence")
            .doesNotContain(repeatedPayload)
            .hasSizeLessThan(100_000);
    }

    @Test
    void enterpriseMetadataReviewerUsesFormattedEvidenceForEveryFieldInsteadOfRawPreview() throws Exception {
        List<Map<String, Object>> sourceFields = new ArrayList<>();
        List<Map<String, Object>> fieldMatches = new ArrayList<>();
        for (int index = 1; index <= 12; index++) {
            Map<String, Object> source = Map.of(
                "fieldName", "field_" + index,
                "fieldCnName", "字段" + index,
                "description", "字段注释" + index
            );
            sourceFields.add(source);
            fieldMatches.add(Map.of(
                "fieldRef", "field-" + index,
                "input", source,
                "standardFields", List.of(Map.of(
                    "name", "标准字段" + index,
                    "technicalName", "STD_FIELD_" + index,
                    "metadata", Map.of(
                        "description", "标准注释" + index,
                        "rawInternalPayload", "x".repeat(2_000)
                    )
                )),
                "termRoots", List.of(),
                "dictionaries", List.of()
            ));
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schemaVersion", "enterprise_metadata_field_discovery.v1");
        output.put("success", true);
        output.put("sourceSchema", Map.of(
            "table", "gdp_ads.target_table",
            "fieldCount", 12,
            "fields", sourceFields
        ));
        output.put("fieldMatches", fieldMatches);
        output.put("coverage", Map.of(
            "inputFieldCount", 12,
            "processedFieldCount", 12,
            "allFieldsProcessed", true
        ));
        output.put("providerExchange", Map.of("raw", "z".repeat(30_000)));
        InterpretationPlanRuntime.StepExecution execution =
            new InterpretationPlanRuntime.StepExecution(
                1,
                "mcp_tool",
                "mcp_chatchat_mcp_server_enterprise_metadata_search",
                true,
                output,
                null,
                null,
                null,
                10L
            );
        InterpretationPlanRuntime.StepReviewRequest request =
            new InterpretationPlanRuntime.StepReviewRequest(
                null, null, execution, Map.of(), 1, 1);
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        Method method = AgentOrchestrator.class.getDeclaredMethod(
            "buildToolResultReviewPrompt",
            String.class,
            String.class,
            InterpretationPlanRuntime.StepReviewRequest.class
        );
        method.setAccessible(true);

        String prompt = (String) method.invoke(
            orchestrator, "补全字段注释", "REVIEW_SYSTEM_SENTINEL-".repeat(8_000), request);

        assertThat(prompt)
            .contains("Authoritative tool result evidence")
            .contains("evidenceCoverage as a description of returned standard-reference data")
            .contains("do not manufacture unsupported/missing claim lists")
            .contains("A revised query cannot expand a tool's declared capability")
            .contains("scope_basis")
            .contains("capability_basis")
            .contains("expected_evidence_types")
            .contains("A broad category phrase in the user query is not permission")
            .contains("Do not reinterpret a generic request for enterprise standards")
            .contains("relaxes the blocking exact filter")
            .contains("Never propose a downstream binding such as tables[0]")
            .contains("A single generic shared token is insufficient")
            .contains("full declared capability and dimension meaning")
            .contains("\"englishName\":\"field_1\"")
            .contains("\"englishName\":\"field_12\"")
            .contains("\"englishName\":\"STD_FIELD_12\"")
            .contains("\"inputFieldCount\":12")
            .contains("\"processedFieldCount\":12")
            .contains("Prompt preview truncated: false")
            .doesNotContain("REVIEW_SYSTEM_SENTINEL")
            .doesNotContain("Tool output preview, shortened only")
            .doesNotContain("rawInternalPayload")
            .doesNotContain("z".repeat(100));
    }

    @Test
    void genericToolResultTailReachesReviewerAndFinalSynthesisWithoutRuntimeTruncation() throws Exception {
        String longResult = "result-head\n" + "x".repeat(20_000) + "\ncritical-tail-evidence";
        Map<String, Object> output = Map.of(
            "success", true,
            "records", List.of(Map.of("payload", longResult))
        );
        InterpretationPlanRuntime.StepExecution execution =
            new InterpretationPlanRuntime.StepExecution(
                1, "mcp_tool", "future_dynamic_tool", true,
                output, null, null, null, 10L
            );
        InterpretationPlanRuntime.StepReviewRequest reviewRequest =
            new InterpretationPlanRuntime.StepReviewRequest(
                null, null, execution, Map.of(), 1, 1);
        AgentOrchestrator orchestrator = newOrchestrator(mock(ChatModel.class));
        Method reviewMethod = AgentOrchestrator.class.getDeclaredMethod(
            "buildToolResultReviewPrompt",
            String.class,
            String.class,
            InterpretationPlanRuntime.StepReviewRequest.class
        );
        reviewMethod.setAccessible(true);

        String reviewPrompt = (String) reviewMethod.invoke(
            orchestrator, "分析完整结果", null, reviewRequest);
        InterpretationPlanRuntime.ExecutionResult result =
            new InterpretationPlanRuntime.ExecutionResult(
                "success", true, false, null, null,
                List.of(execution), Map.of(), 10L
            );
        String summaryPrompt = orchestrator.buildInterpretationPlanSummaryPrompt(
            "分析完整结果", null, result, List.of(), List.of()
        );

        assertThat(reviewPrompt)
            .contains("result-head", "x".repeat(20_000), "critical-tail-evidence")
            .contains("Runtime truncation applied: false")
            .doesNotContain("shortened only for reviewer context");
        assertThat(summaryPrompt)
            .contains("result-head", "x".repeat(20_000), "critical-tail-evidence")
            .contains("toolResult (complete Runtime input)");
    }

    @Test
    void interpretationPlanRunsThroughDagRuntimePipeline() {
        QueueChatModel chatModel = new QueueChatModel(
            """
                {
                  "version": "1.0",
                  "intent": {"type": "document_retrieval", "goal": "Use document evidence", "risk_level": "low"},
                  "context": {"key_facts": [], "assumptions": [], "missing_info": [], "constraints": ["Use registered tools"]},
                  "plan": {
                    "steps": [
                      {"id": 1, "action_type": "mcp_tool", "tool_name": "document_search", "input": {"query": "internal definition"}, "depends_on": []},
                      {"id": 2, "action_type": "final_answer", "tool_name": "", "input": {"answer": "Use the internal definition handbook."}, "depends_on": [1]}
                    ]
                  },
                  "execution_policy": {"max_steps": 2, "allow_parallel": false, "allow_tool": ["document_search"], "deny_tool": [], "timeout_ms": 30000},
                  "review": {"self_check": {"completeness_score": 0.9, "hallucination_risk": 0.1, "tool_sufficiency": true, "missing_steps": []}, "fallback_plan": []}
                }
                """,
            "{\"accepted\":true,\"feedback\":\"The answer follows the plan.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .riskLevel("low")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("document_search"), any())).thenReturn(documentSearchOutput());
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            new ToolRuntimeService(toolRegistry, new ObjectMapper(), toolRuntimeProperties(), List.of(), List.of()),
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "What is the internal definition?",
            "tenant-1",
            List.of("document_search"),
            "Use internal evidence.",
            null,
            List.of(),
            List.of(),
            "research",
            "req-plan-pipeline",
            "conv-plan-pipeline",
            "user-1",
            10,
            List.of(),
            false
        );

        assertThat(result.answer()).contains("Use the internal definition handbook.");
        assertThat(result.toolTraces()).extracting(InteractionToolTrace::getToolName)
            .containsExactly("document_search");
        assertThat(result.metadata())
            .containsEntry("interpretationPlanPipeline", true)
            .containsEntry("interpretationPlanInitialSuccess", true)
            .containsEntry("stopReason", "evidence_sufficient");
    }

    @Test
    void fiveCallBatchUsesOneFinalSynthesisAndNoPerResultModelReview() {
        String toolName = "sql_query_execute";
        String plan = """
            {
              "version": "1.0",
              "intent": {"type": "database_health", "goal": "Execute five Oracle checks", "risk_level": "low"},
              "context": {"key_facts": [], "assumptions": [], "missing_info": [], "constraints": ["Use authorized templates"]},
              "plan": {
                "steps": [
                  {
                    "id": 1,
                    "action_type": "mcp_tool",
                    "tool_name": "sql_query_execute",
                    "input": {
                      "batchId": "oracle-health",
                      "executionMode": "SEQUENTIAL",
                      "stopOnFailure": false,
                      "calls": [
                        {"callId":"instance","toolName":"sql_query_execute","arguments":{"assetId":"asset-a","templateCode":"ORACLE_INSTANCE_STATUS"}},
                        {"callId":"sessions","toolName":"sql_query_execute","arguments":{"assetId":"asset-a","templateCode":"ORACLE_SESSION_OVERVIEW"}},
                        {"callId":"locks","toolName":"sql_query_execute","arguments":{"assetId":"asset-a","templateCode":"ORACLE_LOCKS"}},
                        {"callId":"events","toolName":"sql_query_execute","arguments":{"assetId":"asset-a","templateCode":"ORACLE_SYSTEM_EVENTS"}},
                        {"callId":"tablespace","toolName":"sql_query_execute","arguments":{"assetId":"asset-a","templateCode":"ORACLE_TABLESPACE_SIZE"}}
                      ]
                    },
                    "depends_on": []
                  },
                  {"id": 2, "action_type": "final_answer", "tool_name": "", "input": {"answer": "Summarize the batch evidence."}, "depends_on": [1]}
                ]
              },
              "execution_policy": {
                "max_steps": 2,
                "allow_parallel": false,
                "allow_tool": ["sql_query_execute"],
                "deny_tool": [],
                "max_rewrite_times": 0,
                "fallback_mode": "partial_result"
              },
              "review": {"self_check": {"completeness_score": 0.9, "hallucination_risk": 0.1, "tool_sufficiency": true, "missing_steps": []}, "fallback_plan": []}
            }
            """;
        CapturingQueueChatModel chatModel = new CapturingQueueChatModel(plan);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(toolName)).thenReturn(true);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName)
            .title("SQL template executor")
            .description("Execute authorized SQL templates")
            .riskLevel("low")
            .categories(List.of("mcp"))
            .metadata(Map.of("inputSchema", ToolCallBatchSchema.augment(
                toolName, Map.of("type", "object", "properties", Map.of("templateCode", Map.of("type", "string")))
            )))
            .build());
        when(toolRegistry.executeEnhancedTool(eq(toolName), any())).thenAnswer(invocation -> {
            com.chatchat.common.tool.ToolInput input = invocation.getArgument(1);
            return ToolOutput.success(Map.of(
                "templateCode", input.getParameters().get("templateCode"),
                "status", "ok"
            ));
        });
        ToolRuntimeProperties runtimeProperties = toolRuntimeProperties();
        runtimeProperties.setDefaultRetryAttempts(0);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            new ToolRuntimeService(toolRegistry, new ObjectMapper(), runtimeProperties, List.of(), List.of()),
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "Check Oracle health",
            "tenant-1",
            List.of(toolName),
            "Use only authorized templates.",
            null,
            List.of(),
            List.of(),
            "database",
            "req-batch-model-count",
            "conv-batch-model-count",
            "user-1",
            10,
            List.of(),
            false
        );

        assertThat(result.toolTraces()).hasSize(1);
        assertThat(result.toolTraces().get(0).getRuntimeMetadata())
            .containsEntry("batchExecution", true)
            .containsEntry("remoteToolInvocationCount", 5);
        assertThat(chatModel.messages.stream()
            .filter(message -> message.contains("final step-by-step answer synthesizer"))
            .count()).isEqualTo(1);
        assertThat(chatModel.messages.stream()
            .filter(message -> message.contains("runtime reviewer for one completed MCP tool call"))
            .count()).isZero();
        assertThat(chatModel.messages.stream()
            .filter(message -> message.contains("answer quality"))
            .count()).isZero();
        assertThat(chatModel.messages.get(0))
            .contains("Formal runtime inputSchema", "x-chatchat-batch", "\"maxItems\":32");
        assertThat(result.metadata())
            .containsEntry("batchFinalizationModelCalls", 1)
            .containsEntry("answerReviewSkipped", true);
        verify(toolRegistry, times(5)).executeEnhancedTool(eq(toolName), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void interpretationPlanPreservesSuccessfulDocumentSearchWhenModelNeedsMoreEvidence() {
        StrictDocumentReviewChatModel chatModel = new StrictDocumentReviewChatModel(
            """
                {
                  "version": "1.0",
                  "intent": {"type": "document_retrieval", "goal": "Use document chunks to synthesize a Spark SQL answer", "risk_level": "low"},
                  "context": {"key_facts": [], "assumptions": [], "missing_info": [], "constraints": ["Use document_search evidence"]},
                  "plan": {
                    "steps": [
                      {"id": 1, "action_type": "mcp_tool", "tool_name": "document_search", "input": {"query": "FileSystem JDBC Spark SQL"}, "depends_on": []},
                      {"id": 2, "action_type": "final_answer", "tool_name": "", "input": {"answer": "Use the retrieved JDBC and Filesystem chunks, and state that the combined sync example is missing."}, "depends_on": [1]}
                    ]
                  },
                  "execution_policy": {"max_steps": 2, "allow_parallel": false, "allow_tool": ["document_search"], "deny_tool": [], "timeout_ms": 30000, "max_rewrite_times": 2},
                  "review": {"self_check": {"completeness_score": 0.8, "hallucination_risk": 0.2, "tool_sufficiency": true, "missing_steps": []}, "fallback_plan": []}
                }
                """,
            "{\"accepted\":true,\"feedback\":\"The answer honestly uses partial document evidence.\",\"revisedAnswer\":\"\"}",
            "模型评估当前检索结果不足以支撑该步骤，已停止继续生成原计划结论。",
            "{\"accepted\":true,\"feedback\":\"The refusal to continue is grounded in the model review.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .riskLevel("low")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("document_search"), any())).thenReturn(documentEvidenceSearchOutput());
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            new ToolRuntimeService(toolRegistry, new ObjectMapper(), toolRuntimeProperties(), List.of(), List.of()),
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "给出 Spark SQL 中 FileSystem 和 JDBC 同步到 MySQL 的语法示例",
            "tenant-1",
            List.of("document_search"),
            "Use internal evidence and state missing evidence clearly.",
            null,
            List.of(),
            List.of(),
            "research",
            "req-document-partial-evidence",
            "conv-document-partial-evidence",
            "user-1",
            10,
            List.of(),
            false
        );

        assertThat(result.answer())
            .isNotEqualTo("Use the retrieved JDBC and Filesystem chunks, and state that the combined sync example is missing.");
        assertThat(result.toolTraces()).extracting(InteractionToolTrace::getToolName)
            .containsExactly("document_search");
        assertThat(result.metadata())
            .containsEntry("interpretationPlanConfiguredMaxRewriteTimes", 2)
            .containsEntry("interpretationPlanMaxRewriteTimes", 0)
            .containsEntry("stopReason", "evidence_partial_analysis")
            .doesNotContainKeys("evidenceAugmentationOverrideApplied", "interpretationPlanRewriteAttempted", "dagRepairEvents");
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.metadata().get("interpretationPlanStepExecutions");
        assertThat(steps)
            .anySatisfy(step -> {
                Map<String, Object> stepMetadata = (Map<String, Object>) step.get("metadata");
                assertThat(step)
                    .containsEntry("toolName", "document_search")
                    .containsEntry("success", true);
                assertThat(stepMetadata)
                    .containsEntry("toolResultReviewSatisfied", false)
                    .containsEntry("toolExecutionStatus", "SUCCEEDED")
                    .containsEntry("evidenceSufficiency", "INSUFFICIENT")
                    .containsEntry("stepFulfillmentStatus", "PARTIAL")
                    .containsEntry("modelReviewExecutionStatusOverridePrevented", true)
                    .containsEntry("partialEvidence", true);
                assertThat(stepMetadata)
                    .containsEntry("rejectedEvidenceRefs", List.of("doc://spark#chunk=159", "doc://spark#chunk=155"));
                Map<String, Object> evaluation = (Map<String, Object>) stepMetadata.get("evidenceEvaluation");
                assertThat(evaluation)
                    .containsEntry("contractVersion", "evidence_evaluation_contract_v1")
                    .containsEntry("satisfied", false)
                    .containsEntry("usefulness", "MEDIUM")
                    .containsEntry("shouldExpandQuery", true)
                    .containsEntry("rejectedRefs", List.of("doc://spark#chunk=159", "doc://spark#chunk=155"));
                assertThat((Double) evaluation.get("relevance")).isEqualTo(0.72);
                assertThat((Double) evaluation.get("answerability")).isEqualTo(0.38);
                assertThat((List<String>) evaluation.get("supportsQuestionAspect")).containsExactly("JDBC", "Filesystem");
                assertThat((List<String>) evaluation.get("missingAspects")).containsExactly("combined sync SQL example");
                assertThat(stepMetadata)
                    .doesNotContainKeys("toolResultReviewAutoAccepted", "toolResultReviewAutoAcceptReason");
            });
    }

    @Test
    void interpretationPlanCanUseRequestScopedAvailableToolWhenRegistryDoesNotAdvertiseIt() {
        String requestScopedTool = "mcp_dynamic_document_search";
        QueueChatModel chatModel = new QueueChatModel(
            """
                {
                  "version": "1.0",
                  "intent": {"type": "document_retrieval", "goal": "Use request-scoped MCP evidence", "risk_level": "low"},
                  "context": {"key_facts": [], "assumptions": [], "missing_info": [], "constraints": ["Use available request tools"]},
                  "plan": {
                    "steps": [
                      {"id": 1, "action_type": "mcp_tool", "tool_name": "mcp_dynamic_document_search", "input": {"query": "internal definition"}, "depends_on": []},
                      {"id": 2, "action_type": "final_answer", "tool_name": "", "input": {"answer": "Use request scoped evidence."}, "depends_on": [1]}
                    ]
                  },
                  "execution_policy": {"max_steps": 2, "allow_parallel": false, "allow_tool": ["mcp_dynamic_document_search"], "deny_tool": [], "timeout_ms": 30000},
                  "review": {"self_check": {"completeness_score": 0.9, "hallucination_risk": 0.1, "tool_sufficiency": true, "missing_steps": []}, "fallback_plan": []}
                }
                """,
            "{\"accepted\":true,\"feedback\":\"The answer follows the request-scoped tool evidence.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(requestScopedTool)).thenReturn(false);
        when(toolRegistry.executeEnhancedTool(eq(requestScopedTool), any())).thenReturn(documentSearchOutput());
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            new ToolRuntimeService(toolRegistry, new ObjectMapper(), toolRuntimeProperties(), List.of(), List.of()),
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "What is the internal definition?",
            "tenant-1",
            List.of(requestScopedTool),
            "Use request-scoped tools.",
            null,
            List.of(),
            List.of(),
            "research",
            "req-request-scoped-tool",
            "conv-request-scoped-tool",
            "user-1",
            10,
            List.of(),
            false
        );

        assertThat(result.toolTraces()).extracting(InteractionToolTrace::getToolName)
            .containsExactly(requestScopedTool);
        assertThat(result.metadata())
            .containsEntry("interpretationPlanPipeline", true)
            .containsEntry("interpretationPlanInitialSuccess", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void plannerAttributionSelectsBestPlanAfterThreeInvalidRepairs() {
        CapturingQueueChatModel chatModel = new CapturingQueueChatModel(
            invalidToolPlan("missing_tool_a"),
            invalidToolPlan("missing_tool_b"),
            invalidToolPlan("missing_tool_c", "Attribution selected the document-backed plan."),
            "{\"accepted\":true,\"feedback\":\"The answer follows the attributed plan.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .riskLevel("low")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("document_search"), any())).thenReturn(documentSearchOutput());
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            new ToolRuntimeService(toolRegistry, new ObjectMapper(), toolRuntimeProperties(), List.of(), List.of()),
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "What is the internal definition?",
            "tenant-1",
            List.of("document_search"),
            "Use internal evidence.",
            null,
            List.of(),
            List.of(),
            "research",
            "req-plan-attribution",
            "conv-plan-attribution",
            "user-1",
            10,
            List.of(),
            false,
            Map.of("plannerMaxRepairAttempts", 9)
        );

        assertThat(result.answer()).contains("Attribution selected the document-backed plan.");
        assertThat(result.toolTraces()).extracting(InteractionToolTrace::getToolName)
            .containsExactly("document_search");
        List<Map<String, Object>> plannerSteps = (List<Map<String, Object>>) result.metadata().get("plannerSteps");
        assertThat(plannerSteps)
            .anySatisfy(step -> assertThat((Map<String, Object>) step.get("executionPlan"))
                .containsEntry("plannerAttributionSelection", true)
                .containsEntry("plannerAttributionSource", "deterministic_java")
                .containsEntry("plannerAttributionCandidateCount", 3)
                .containsEntry("plannerAttributionSelected", "C")
                .containsEntry("plannerAttributionSelectedAttempt", 3)
                .containsEntry("plannerAttributionFailurePattern", "TOOL_MISSING")
                .containsEntry("plannerGenerationLimit", 3)
                .containsEntry("plannerGenerationCount", 3)
                .containsEntry("plannerAttributionRepairApplied", true)
                .containsKey("plannerAttributionScores")
                .containsKey("plannerAttributionCandidates")
                .containsKey("plannerAttributionCandidateFingerprints"));
        assertThat(chatModel.messages().stream()
            .filter(message -> message.contains("You are an agent planner."))
            .count()).isEqualTo(3);
        assertThat(chatModel.messages())
            .noneMatch(message -> message.contains("bounded attribution selection mode"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void plannerAttributionCoveragePrefersEvidenceBackedCandidateOverShortFinalPlan() {
        CapturingQueueChatModel chatModel = new CapturingQueueChatModel(
            finalOnlyPlan("Short unsupported answer."),
            invalidToolPlan("missing_tool_b", "Coverage selected the evidence-backed plan."),
            finalOnlyPlan("Another short unsupported answer."),
            "{\"accepted\":true,\"feedback\":\"The coverage-selected answer follows evidence.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .riskLevel("low")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("document_search"), any())).thenReturn(documentSearchOutput());
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            new ToolRuntimeService(toolRegistry, new ObjectMapper(), toolRuntimeProperties(), List.of(), List.of()),
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "Use the internal document to explain the definition.",
            "tenant-1",
            List.of("document_search"),
            "Use internal evidence.",
            null,
            List.of(),
            List.of(),
            "research",
            "req-plan-coverage",
            "conv-plan-coverage",
            "user-1",
            10,
            List.of("document_search"),
            true,
            Map.of("plannerMaxRepairAttempts", 9)
        );

        assertThat(result.answer()).contains("Coverage selected the evidence-backed plan.");
        assertThat(result.toolTraces()).extracting(InteractionToolTrace::getToolName)
            .containsExactly("document_search");
        List<Map<String, Object>> plannerSteps = (List<Map<String, Object>>) result.metadata().get("plannerSteps");
        Map<String, Object> attribution = plannerSteps.stream()
            .map(step -> (Map<String, Object>) step.get("executionPlan"))
            .filter(plan -> Boolean.TRUE.equals(plan.get("plannerAttributionSelection")))
            .findFirst()
            .orElseThrow();
        assertThat(attribution)
            .containsEntry("plannerAttributionSelected", "B")
            .containsEntry("plannerAttributionRepairApplied", true);
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) attribution.get("plannerAttributionCandidates");
        Map<String, Object> candidateA = candidates.get(0);
        Map<String, Object> candidateB = candidates.get(1);
        Map<String, Object> detailsA = (Map<String, Object>) candidateA.get("scoreDetails");
        Map<String, Object> detailsB = (Map<String, Object>) candidateB.get("scoreDetails");
        assertThat(detailsB.get("coverageScore")).isNotNull();
        assertThat((Integer) detailsB.get("coverageScore")).isGreaterThan((Integer) detailsA.get("coverageScore"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void interpretationPlanFailureTriggersRewriteAndRunsRewrittenPlan() {
        QueueChatModel chatModel = new QueueChatModel(
            """
                {
                  "version": "1.0",
                  "intent": {"type": "document_retrieval", "goal": "Use document evidence", "risk_level": "low"},
                  "context": {"key_facts": [], "assumptions": [], "missing_info": [], "constraints": ["Use registered tools"]},
                  "plan": {
                    "steps": [
                      {"id": 1, "action_type": "mcp_tool", "tool_name": "document_search", "input": {"query": "internal definition"}, "depends_on": []},
                      {"id": 2, "action_type": "final_answer", "tool_name": "", "input": {"answer": "Use document evidence."}, "depends_on": [1]}
                    ]
                  },
                  "execution_policy": {"max_steps": 2, "allow_parallel": false, "allow_tool": ["document_search"], "deny_tool": [], "timeout_ms": 30000},
                  "review": {"self_check": {"completeness_score": 0.7, "hallucination_risk": 0.2, "tool_sufficiency": false, "missing_steps": []}, "fallback_plan": []}
                }
                """,
            """
                {
                  "version": "1.0",
                  "intent": {"type": "web_search", "goal": "Recover with web evidence", "risk_level": "low"},
                  "context": {"key_facts": ["document_search failed"], "assumptions": [], "missing_info": [], "constraints": ["Use registered tools"]},
                  "plan": {
                    "steps": [
                      {"id": 1, "action_type": "mcp_tool", "tool_name": "web_search", "input": {"query": "internal definition public evidence"}, "depends_on": []},
                      {"id": 2, "action_type": "final_answer", "tool_name": "", "input": {"answer": "Use web evidence fallback."}, "depends_on": [1]}
                    ]
                  },
                  "execution_policy": {"max_steps": 2, "allow_parallel": false, "allow_tool": ["web_search"], "deny_tool": ["document_search"], "timeout_ms": 30000},
                  "review": {"self_check": {"completeness_score": 0.8, "hallucination_risk": 0.2, "tool_sufficiency": true, "missing_steps": []}, "fallback_plan": []}
                }
                """,
            "{\"accepted\":true,\"feedback\":\"The rewritten answer follows available evidence.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.hasTool("web_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .riskLevel("low")
            .build());
        when(toolRegistry.getToolMetadata("web_search")).thenReturn(ToolMetadata.builder()
            .id("web_search")
            .title("Web Search")
            .description("Search web pages")
            .riskLevel("low")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("document_search"), any())).thenReturn(ToolOutput.failure("document backend down"));
        when(toolRegistry.executeEnhancedTool(eq("web_search"), any())).thenReturn(webSearchOutput());
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            new ToolRuntimeService(toolRegistry, new ObjectMapper(), toolRuntimeProperties(), List.of(), List.of()),
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "What is the internal definition?",
            "tenant-1",
            List.of("document_search", "web_search"),
            "Recover safely if internal evidence is unavailable.",
            null,
            List.of(),
            List.of(),
            "research",
            "req-plan-rewrite",
            "conv-plan-rewrite",
            "user-1",
            10,
            List.of(),
            false
        );

        assertThat(result.answer()).contains("Use web evidence fallback.");
        assertThat(result.toolTraces()).extracting(InteractionToolTrace::getToolName)
            .containsExactly("document_search", "web_search");
        assertThat(result.metadata())
            .containsEntry("interpretationPlanRewriteAttempted", true)
            .containsEntry("interpretationPlanRewriteValid", true)
            .containsEntry("interpretationPlanRewriteSuccess", true)
            .containsEntry("stopReason", "evidence_sufficient");
        List<Map<String, Object>> repairEvents = (List<Map<String, Object>>) result.metadata().get("dagRepairEvents");
        assertThat(repairEvents)
            .extracting(event -> event.get("eventState"))
            .containsExactly("STARTED", "APPLIED");
        assertThat(repairEvents.get(1))
            .containsEntry("contractVersion", "runtime_dag_governance.v1")
            .containsEntry("repairAttempt", 1);
    }

    @Test
    void interpretationPlanHonorsZeroRewriteBudgetAndFallsBack() {
        QueueChatModel chatModel = new QueueChatModel(
            """
                {
                  "version": "1.0",
                  "intent": {"type": "document_retrieval", "goal": "Use document evidence", "risk_level": "low"},
                  "context": {"key_facts": [], "assumptions": [], "missing_info": [], "constraints": ["Use registered tools"]},
                  "plan": {
                    "steps": [
                      {"id": 1, "action_type": "mcp_tool", "tool_name": "document_search", "input": {"query": "internal definition"}, "depends_on": []},
                      {"id": 2, "action_type": "final_answer", "tool_name": "", "input": {"answer": "Use document evidence."}, "depends_on": [1]}
                    ]
                  },
                  "execution_policy": {"max_steps": 2, "allow_parallel": false, "allow_tool": ["document_search"], "deny_tool": [], "timeout_ms": 30000, "max_rewrite_times": 0, "fallback_mode": "partial_result"},
                  "review": {"self_check": {"completeness_score": 0.7, "hallucination_risk": 0.2, "tool_sufficiency": false, "missing_steps": []}, "fallback_plan": []}
                }
                """,
            "Internal evidence is unavailable, so only a partial result can be provided.",
            "{\"accepted\":true,\"feedback\":\"The fallback is honest about missing evidence.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .riskLevel("low")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("document_search"), any())).thenReturn(ToolOutput.failure("document backend down"));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            new ToolRuntimeService(toolRegistry, new ObjectMapper(), toolRuntimeProperties(), List.of(), List.of()),
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "What is the internal definition?",
            "tenant-1",
            List.of("document_search"),
            "Do not rewrite when budget is exhausted.",
            null,
            List.of(),
            List.of(),
            "research",
            "req-plan-no-rewrite",
            "conv-plan-no-rewrite",
            "user-1",
            10,
            List.of(),
            false
        );

        assertThat(result.answer()).contains("Synthesized answer from executed steps.");
        assertThat(result.metadata())
            .containsEntry("interpretationPlanRewriteBudgetExceeded", true)
            .containsEntry("interpretationPlanFallbackMode", "partial_result")
            .containsEntry("stopReason", "evidence_iteration_limit");
        assertThat(result.metadata()).doesNotContainKey("interpretationPlanRewriteAttempted");
    }

    @Test
    void executeRuntimeRequestReturnsTypedRunResult() {
        QueueChatModel chatModel = new QueueChatModel(
            "{\"action\":\"tool\",\"toolName\":\"document_search\",\"arguments\":{\"query\":\"internal definition\"},\"reason\":\"Need internal evidence\"}",
            "{\"action\":\"final\",\"answer\":\"Use the internal definition handbook.\"}",
            "{\"accepted\":true,\"feedback\":\"The answer uses document evidence.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("document_search"), any())).thenReturn(documentSearchOutput());
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentRunResult result = orchestrator.execute(AgentRunRequest.builder()
            .query("What is the internal definition?")
            .tenantId("tenant-1")
            .availableTools(List.of("document_search"))
            .systemPrompt("Use internal evidence.")
            .skillId("research")
            .requestId("req-runtime-1")
            .conversationId("conv-runtime-1")
            .userId("user-1")
            .webSearchResultLimit(10)
            .build());

        assertThat(result.answer()).contains("Use the internal definition handbook.");
        assertThat(result.stopReason()).isEqualTo("final_answer");
        assertThat(result.toolTraces())
            .extracting(InteractionToolTrace::getToolName)
            .containsExactly("document_search");
        assertThat(result.steps().stream().map(step -> step.action()).toList())
            .containsExactly("tool", "final");
        assertThat(result.observations().stream().map(observation -> observation.type()).toList())
            .contains("document_evidence");
        assertThat(result.metadata())
            .containsEntry("runtimeContractVersion", "agent_runtime_v1")
            .containsEntry("toolTraceCount", 1)
            .containsEntry("timeoutMs", AgentRunRequest.DEFAULT_TIMEOUT_MS);
    }

    @Test
    void requiredToolCannotBeSkippedByPlannerSufficientSignal() {
        QueueChatModel chatModel = new QueueChatModel(
            interpretationPlan("Use the required document evidence.", "document_search"),
            "{\"accepted\":true,\"feedback\":\"The answer uses the required tool.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("document_search"), any())).thenReturn(documentSearchOutput());
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "Use existing context only.",
            "tenant-1",
            List.of("document_search"),
            "Do not use tools unless needed.",
            null,
            List.of(),
            List.of(),
            "research",
            "req-sufficient-final",
            "conv-sufficient-final",
            "user-1",
            10,
            List.of("document_search"),
            true,
            Map.of()
        );

        assertThat(result.answer()).contains("Use the required document evidence.");
        assertThat(result.toolTraces())
            .extracting(InteractionToolTrace::getToolName)
            .containsExactly("document_search");
        assertThat(result.metadata())
            .containsEntry("interpretationPlanPipeline", true)
            .containsEntry("mandatoryWorkflowCompleted", true)
            .containsEntry("stopReason", "evidence_sufficient");
        verify(toolRegistry).executeEnhancedTool(eq("document_search"), any());
    }

    @Test
    void runtimeRunStorePersistsRunLifecycleEvents() {
        QueueChatModel chatModel = new QueueChatModel(
            "{\"action\":\"tool\",\"toolName\":\"document_search\",\"arguments\":{\"query\":\"internal definition\"},\"reason\":\"Need internal evidence\"}",
            "{\"action\":\"final\",\"answer\":\"Use the persisted run evidence.\"}",
            "{\"accepted\":true,\"feedback\":\"The answer uses document evidence.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("document_search"), any())).thenReturn(documentSearchOutput());
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            new ToolRuntimeService(
                toolRegistry,
                new ObjectMapper(),
                toolRuntimeProperties(),
                List.of(),
                List.of()
            ),
            new ObjectMapper(),
            new ModelsConfig(),
            new EvidenceTrustEvaluator(),
            runStore
        );

        AgentRunResult result = orchestrator.execute(AgentRunRequest.builder()
            .runId("run-store-1")
            .query("What is the internal definition?")
            .tenantId("tenant-1")
            .availableTools(List.of("document_search"))
            .systemPrompt("Use internal evidence.")
            .requestId("req-run-store-1")
            .conversationId("conv-run-store-1")
            .userId("user-1")
            .build());

        assertThat(result.runId()).isEqualTo("run-store-1");
        assertThat(result.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(result.events()).extracting(event -> event.type().name())
            .contains("RUN_STARTED", "STEP_RECORDED", "OBSERVATION_RECORDED", "RUN_COMPLETED");
        assertThat(runStore.find("run-store-1")).isPresent();
        assertThat(runStore.find("run-store-1").orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(runStore.find("run-store-1").orElseThrow().steps()).hasSize(2);
        assertThat(runStore.events("run-store-1")).hasSize(result.events().size());
    }

    @Test
    void executeRuntimeRequestReturnsCancelledRunWhenCancellationIsRequested() {
        QueueChatModel chatModel = new QueueChatModel();
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            mock(ToolRegistry.class),
            new ToolRuntimeService(
                mock(ToolRegistry.class),
                new ObjectMapper(),
                toolRuntimeProperties(),
                List.of(),
                List.of()
            ),
            new ObjectMapper(),
            new ModelsConfig(),
            new EvidenceTrustEvaluator(),
            runStore
        );

        AgentRunResult result = orchestrator.execute(AgentRunRequest.builder()
            .runId("run-cancel-1")
            .query("Cancel this run")
            .tenantId("tenant-1")
            .availableTools(List.of())
            .requestId("req-cancel-1")
            .attributes(Map.of("__agentCancellation", (BooleanSupplier) () -> true))
            .build());

        assertThat(result.status()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(result.stopReason()).isEqualTo("cancelled");
        assertThat(result.events()).extracting(event -> event.type().name())
            .contains("RUN_STARTED", "RUN_CANCELLED");
        assertThat(runStore.find("run-cancel-1")).isPresent();
        assertThat(runStore.find("run-cancel-1").orElseThrow().status()).isEqualTo(AgentRunStatus.CANCELLED);
    }

    @Test
    void executeRuntimeRequestHonorsMaxStepsBudget() {
        QueueChatModel chatModel = new QueueChatModel(
            "{\"action\":\"tool\",\"toolName\":\"document_search\",\"arguments\":{\"query\":\"internal definition\"},\"reason\":\"Need internal evidence\"}",
            "Fallback answer from one observed tool.",
            "{\"accepted\":true,\"feedback\":\"The answer uses the observed tool result.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("document_search"), any())).thenReturn(documentSearchOutput());
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            new ToolRuntimeService(
                toolRegistry,
                new ObjectMapper(),
                toolRuntimeProperties(),
                List.of(),
                List.of()
            ),
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentRunResult result = orchestrator.execute(AgentRunRequest.builder()
            .runId("run-max-steps-1")
            .query("What is the internal definition?")
            .tenantId("tenant-1")
            .availableTools(List.of("document_search"))
            .requestId("req-max-steps-1")
            .maxSteps(1)
            .build());

        assertThat(result.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(result.answer()).contains("Fallback answer from one observed tool.");
        assertThat(result.stopReason()).isEqualTo("max_steps_or_fallback");
        assertThat(result.metadata())
            .containsEntry("maxSteps", 1)
            .containsEntry("steps", 1);
        assertThat(result.toolTraces())
            .extracting(InteractionToolTrace::getToolName)
            .containsExactly("document_search");
    }

    @Test
    void executeRuntimeRequestHonorsMaxToolCallsBudget() {
        QueueChatModel chatModel = new QueueChatModel(
            "{\"action\":\"tool\",\"toolName\":\"document_search\",\"arguments\":{\"query\":\"internal definition\"},\"reason\":\"Need internal evidence\"}",
            "Cannot call tools under the current runtime budget.",
            "{\"accepted\":true,\"feedback\":\"The answer explains the runtime limit.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .build());
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            new ToolRuntimeService(
                toolRegistry,
                new ObjectMapper(),
                toolRuntimeProperties(),
                List.of(),
                List.of()
            ),
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentRunResult result = orchestrator.execute(AgentRunRequest.builder()
            .runId("run-max-tool-calls-1")
            .query("What is the internal definition?")
            .tenantId("tenant-1")
            .availableTools(List.of("document_search"))
            .requestId("req-max-tool-calls-1")
            .maxToolCalls(0)
            .build());

        assertThat(result.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(result.answer()).isEqualTo("Cannot call tools under the current runtime budget.");
        assertThat(result.stopReason()).isEqualTo("tool_budget_exceeded");
        assertThat(result.toolTraces()).isEmpty();
        assertThat(result.metadata())
            .containsEntry("maxToolCalls", 0)
            .containsEntry("toolBudgetExceeded", true)
            .containsEntry("requestedToolAfterBudget", "document_search");
        verify(toolRegistry, never()).executeEnhancedTool(eq("document_search"), any());
    }

    @Test
    void executeRuntimeRequestCancelsRunWhenTimeoutExpires() {
        ChatModel chatModel = new SlowChatModel(
            20L,
            "{\"action\":\"final\",\"answer\":\"This answer should arrive too late.\"}"
        );
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            mock(ToolRegistry.class),
            new ToolRuntimeService(
                mock(ToolRegistry.class),
                new ObjectMapper(),
                toolRuntimeProperties(),
                List.of(),
                List.of()
            ),
            new ObjectMapper(),
            new ModelsConfig(),
            new EvidenceTrustEvaluator(),
            runStore
        );

        AgentRunResult result = orchestrator.execute(AgentRunRequest.builder()
            .runId("run-timeout-1")
            .query("Timeout this run")
            .tenantId("tenant-1")
            .availableTools(List.of())
            .requestId("req-timeout-1")
            .timeoutMs(1L)
            .build());

        assertThat(result.status()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(result.stopReason()).isEqualTo("cancelled");
        assertThat(result.errorMessage()).isEqualTo("Agent run timed out");
        assertThat(result.events()).extracting(event -> event.type().name())
            .contains("RUN_STARTED", "RUN_CANCELLED");
        assertThat(runStore.find("run-timeout-1")).isPresent();
        assertThat(runStore.find("run-timeout-1").orElseThrow().status()).isEqualTo(AgentRunStatus.CANCELLED);
    }

    @Test
    void executeRuntimeRequestKeepsDisplayableReportProducedAtDeadline() {
        String report = """
            # 持仓分析报告

            ## 当前结论

            | 证券代码 | 数量 | 累计盈亏 |
            |---|---:|---:|
            | 600839 | 15,900 | 2,088.42 |

            ## 观察建议

            1. 核对当日盈亏。
            2. 核对成本口径。
            """;
        ChatModel chatModel = new SlowChatModel(20L, report);
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        ToolRegistry registry = mock(ToolRegistry.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            registry,
            new ToolRuntimeService(
                registry,
                new ObjectMapper(),
                toolRuntimeProperties(),
                List.of(),
                List.of()
            ),
            new ObjectMapper(),
            new ModelsConfig(),
            new EvidenceTrustEvaluator(),
            runStore
        );

        AgentRunResult result = orchestrator.execute(AgentRunRequest.builder()
            .runId("run-report-at-deadline-1")
            .query("分析持仓")
            .tenantId("tenant-1")
            .availableTools(List.of())
            .requestId("req-report-at-deadline-1")
            .timeoutMs(1L)
            .build());

        assertThat(result.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(result.answer()).contains("持仓分析报告", "600839");
        assertThat(result.stopReason()).isEqualTo("answer_completed_after_cancellation");
        assertThat(result.metadata())
            .containsEntry("answerCompletedAfterCancellation", true)
            .containsEntry("resultRecoveredAtDeadline", true);
    }

    @Test
    void executeRuntimeRequestReturnsFailedRunWhenPlannerThrows() {
        ChatModel chatModel = new FailingChatModel("planner boom");
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            mock(ToolRegistry.class),
            new ToolRuntimeService(
                mock(ToolRegistry.class),
                new ObjectMapper(),
                toolRuntimeProperties(),
                List.of(),
                List.of()
            ),
            new ObjectMapper(),
            new ModelsConfig(),
            new EvidenceTrustEvaluator(),
            runStore
        );

        AgentRunResult result = orchestrator.execute(AgentRunRequest.builder()
            .runId("run-fail-1")
            .query("Fail this run")
            .tenantId("tenant-1")
            .availableTools(List.of())
            .requestId("req-fail-1")
            .build());

        assertThat(result.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(result.stopReason()).isEqualTo("failed");
        assertThat(result.errorMessage()).isEqualTo("planner boom");
        assertThat(result.events()).extracting(event -> event.type().name())
            .contains("RUN_STARTED", "RUN_FAILED");
        assertThat(runStore.find("run-fail-1")).isPresent();
        assertThat(runStore.find("run-fail-1").orElseThrow().status()).isEqualTo(AgentRunStatus.FAILED);
    }

    @Test
    void keepsFinalAnswerWhenReviewerSuggestsRevisionByDefault() {
        QueueChatModel chatModel = new QueueChatModel(
            "{\"action\":\"final\",\"answer\":\"Please check the MySQL setup section in the deployment document.\"}",
            "{\"accepted\":false,\"feedback\":\"The answer did not directly provide the initialization steps.\",\"revisedAnswer\":\"Initialize LiveData by creating the MySQL database and user, then import schema.sql, update the datasource config, and restart the service to verify connectivity.\"}",
            "{\"selectedId\":\"candidate\",\"reason\":\"The original candidate is safer because no supporting observations were available.\",\"candidates\":[{\"id\":\"candidate\",\"score\":0.7,\"accuracy\":0.7,\"grounding\":0.7,\"completeness\":0.4,\"citation\":1.0,\"usefulness\":0.4,\"issues\":[]},{\"id\":\"reviewer_suggestion\",\"score\":0.3,\"accuracy\":0.2,\"grounding\":0.2,\"completeness\":0.9,\"citation\":1.0,\"usefulness\":0.8,\"issues\":[\"unsupported concrete steps\"]}]}"
        );
        AgentOrchestrator orchestrator = newOrchestrator(chatModel);

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "How do I initialize the LiveData database?",
            "tenant-1",
            List.of(),
            "You are the LiveData Studio operations assistant.",
            null,
            List.of(),
            List.of(),
            "livedata_ops",
            "req-1",
            "conv-1",
            "user-1",
            10,
            List.of(),
            false
        );

        assertThat(result.answer()).isEqualTo("Please check the MySQL setup section in the deployment document.");
        assertThat(result.metadata())
            .containsEntry("answerReviewStatus", "rejected")
            .containsEntry("answerReviewFeedback", "The answer did not directly provide the initialization steps.")
            .containsEntry("answerDecisionContractVersion", AnswerDecisionEngine.CONTRACT_VERSION)
            .containsEntry("answerDecision", AnswerDecisionEngine.NO_REWRITE)
            .containsEntry("answerRewriteSource", "planner_candidate")
            .containsEntry("answerReviewAuthority", "diagnostic_only")
            .containsEntry("answerReviewRewriteApplied", false)
            .containsEntry("answerReviewRewriteSkippedReason", "protected_business_result")
            .containsEntry("answerLifecycleStatus", "SELECTED")
            .containsEntry("evidenceRequirement", "OPTIONAL")
            .doesNotContainKey("answerQualityAvailable");
    }

    @Test
    void reviewerSuggestionDoesNotBecomeACompetingBusinessAnswer() {
        QueueChatModel chatModel = new QueueChatModel(
            "{\"action\":\"final\",\"answer\":\"Please check the MySQL setup section in the deployment document.\"}",
            "{\"accepted\":false,\"feedback\":\"The answer did not directly provide the initialization steps.\",\"revisedAnswer\":\"Initialize LiveData by creating the MySQL database and user, then import schema.sql, update the datasource config, and restart the service to verify connectivity.\"}",
            "{\"selectedId\":\"reviewer_suggestion\",\"reason\":\"The reviewer suggestion directly answers the requested procedure.\",\"candidates\":[{\"id\":\"candidate\",\"score\":0.35,\"accuracy\":0.7,\"grounding\":0.4,\"completeness\":0.2,\"citation\":1.0,\"usefulness\":0.2,\"issues\":[\"does not answer directly\"]},{\"id\":\"reviewer_suggestion\",\"score\":0.88,\"accuracy\":0.8,\"grounding\":0.7,\"completeness\":0.95,\"citation\":1.0,\"usefulness\":0.95,\"issues\":[]}]}"
        );
        AgentOrchestrator orchestrator = newOrchestrator(chatModel);

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "How do I initialize the LiveData database?",
            "tenant-1",
            List.of(),
            "You are the LiveData Studio operations assistant.",
            null,
            List.of(),
            List.of(),
            "livedata_ops",
            "req-quality-1",
            "conv-quality-1",
            "user-1",
            10,
            List.of(),
            false
        );

        assertThat(result.answer())
            .isEqualTo("Please check the MySQL setup section in the deployment document.")
            .doesNotContain("schema.sql");
        assertThat(result.metadata())
            .containsEntry("answerReviewStatus", "rejected")
            .containsEntry("answerDecision", AnswerDecisionEngine.NO_REWRITE)
            .containsEntry("answerRewriteSource", "planner_candidate")
            .containsEntry("answerReviewAuthority", "diagnostic_only")
            .containsEntry("answerReviewRewriteApplied", false)
            .containsEntry("answerLifecycleStatus", "SELECTED")
            .doesNotContainKey("answerQualityAvailable");
    }

    @Test
    void keepsFinalAnswerWhenReviewerAcceptsIt() {
        QueueChatModel chatModel = new QueueChatModel(
            "{\"action\":\"final\",\"answer\":\"Initialization steps: create the database, run schema.sql, configure the connection string, restart the service, and verify the health endpoint.\"}",
            "{\"accepted\":true,\"feedback\":\"The answer directly addresses the user request.\",\"revisedAnswer\":\"\"}"
        );
        AgentOrchestrator orchestrator = newOrchestrator(chatModel);

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "How do I initialize the LiveData database?",
            "tenant-1",
            List.of(),
            "You are the LiveData Studio operations assistant.",
            null,
            List.of(),
            List.of(),
            "livedata_ops",
            "req-2",
            "conv-2",
            "user-1",
            10,
            List.of(),
            false
        );

        assertThat(result.answer()).isEqualTo("Initialization steps: create the database, run schema.sql, configure the connection string, restart the service, and verify the health endpoint.");
        assertThat(result.metadata())
            .containsEntry("answerReviewStatus", "accepted")
            .containsEntry("answerReviewFeedback", "The answer directly addresses the user request.");
    }

    @Test
    void includesWebCitationMapInPromptAfterWebSearch() {
        CapturingQueueChatModel chatModel = new CapturingQueueChatModel(
            "{\"action\":\"tool\",\"toolName\":\"web_search\",\"arguments\":{\"query\":\"AI citation audit\"},\"reason\":\"Need web evidence\"}",
            "{\"action\":\"final\",\"answer\":\"客户可以通过页面引用核验来源。[网页1]\"}",
            "{\"accepted\":true,\"feedback\":\"The answer cites web evidence.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("web_search")).thenReturn(ToolMetadata.builder()
            .id("web_search")
            .title("Web Search")
            .description("Search web pages")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("web_search"), any())).thenReturn(webSearchOutput());
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "How can customers audit cited web answers?",
            "tenant-1",
            List.of("web_search"),
            "Use evidence.",
            null,
            List.of(),
            List.of(),
            "research",
            "req-web-1",
            "conv-web-1",
            "user-1",
            10,
            List.of(),
            false
        );

        assertThat(result.answer()).contains("[网页1]");
        assertThat(chatModel.messages()).hasSize(3);
        assertThat(chatModel.messages().get(1))
            .contains("Web citation map")
            .contains("[网页1] Audit trail for AI answers - https://example.com/audit")
            .contains("append the matching [网页N] label");
        assertThat(chatModel.messages().get(2))
            .contains("web citation labels such as [网页1]")
            .contains("https://example.com/audit");
    }

    @Test
    void searchAndExtractUsesUnifiedEvidenceTrustAndCitationPipeline() {
        CapturingQueueChatModel chatModel = new CapturingQueueChatModel(
            "{\"action\":\"tool\",\"toolName\":\"search_and_extract\",\"arguments\":{\"query\":\"agent evidence runtime\"},\"reason\":\"Need structured web evidence\"}",
            "{\"action\":\"final\",\"answer\":\"Structured web evidence supports the runtime claim [网页1].\"}",
            "{\"accepted\":true,\"feedback\":\"The answer cites trusted web evidence.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("search_and_extract")).thenReturn(ToolMetadata.builder()
            .id("search_and_extract")
            .title("Search And Extract")
            .description("Generate structured web evidence")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("search_and_extract"), any())).thenReturn(searchAndExtractOutput());
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "Verify the agent evidence runtime with web evidence.",
            "tenant-1",
            List.of("search_and_extract"),
            "Use evidence.",
            null,
            List.of(),
            List.of(),
            "research",
            "req-search-extract-1",
            "conv-search-extract-1",
            "user-1",
            10,
            List.of(),
            false
        );

        assertThat(result.answer()).contains("[网页1]");
        assertThat(chatModel.messages()).hasSize(3);
        assertThat(chatModel.messages().get(1))
            .contains("Evidence trust policy")
            .contains("usable=1")
            .contains("ignoredLowScore=1")
            .contains("Web citation map")
            .contains("Trusted runtime evidence - https://docs.example.com/evidence")
            .doesNotContain("https://docs.example.com/low-quality");
        assertThat(chatModel.messages().get(2))
            .contains("Evidence trust policy")
            .contains("https://docs.example.com/evidence")
            .doesNotContain("https://docs.example.com/low-quality");
    }

    @Test
    void documentWebVerificationAllowsDocumentSearchBeforeMandatoryWebTool() {
        String mcpWebSearch = "mcp_chatchat_mcp_server_web_search";
        QueueChatModel chatModel = new QueueChatModel(
            interpretationPlan(
                "Use internal documents first, then public web verification.",
                "document_search",
                mcpWebSearch
            ),
            "{\"accepted\":true,\"feedback\":\"The answer separates document and web evidence.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.hasTool(mcpWebSearch)).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .build());
        when(toolRegistry.getToolMetadata(mcpWebSearch)).thenReturn(ToolMetadata.builder()
            .id(mcpWebSearch)
            .title("MCP Web Search")
            .description("Search web pages")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("document_search"), any())).thenReturn(documentSearchOutput());
        when(toolRegistry.executeEnhancedTool(eq(mcpWebSearch), any())).thenReturn(webSearchOutput());
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "What is the internal definition? Please verify online.",
            "tenant-1",
            List.of("document_search", mcpWebSearch),
            "Use internal documents first.",
            null,
            List.of("doc-1"),
            List.of(),
            "research",
            "req-doc-web-1",
            "conv-doc-web-1",
            "user-1",
            10,
            List.of(mcpWebSearch),
            true
        );

        assertThat(result.toolTraces())
            .extracting(InteractionToolTrace::getToolName)
            .containsExactly("document_search", mcpWebSearch);
        assertThat(result.metadata())
            .containsEntry("documentWebVerificationRequired", true)
            .containsEntry("mandatoryTools", List.of("document_search", mcpWebSearch));
    }

    @Test
    void resumesPendingToolExecutionAfterConfirmation() {
        CapturingQueueChatModel chatModel = new CapturingQueueChatModel(
            "{\"action\":\"final\",\"answer\":\"Use the confirmed document evidence.\"}",
            "{\"accepted\":true,\"feedback\":\"The answer used the confirmed tool observation.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("document_search"), any())).thenReturn(documentSearchOutput());
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "Continue after confirmation.",
            "tenant-1",
            List.of("document_search"),
            "Use internal evidence.",
            null,
            List.of(),
            List.of(),
            "research",
            "req-confirm-resume",
            "conv-confirm-resume",
            "user-1",
            10,
            List.of(),
            false,
            Map.of(
                "mcpConfirmation", Map.of("approved", true),
                "mcpPendingToolExecution", Map.of(
                    "toolName", "document_search",
                    "input", Map.of("query", "Kafka Connect 安全认证与启动"),
                    "executionPlan", Map.of("reason", "Confirmed by user")
                )
            )
        );

        assertThat(result.toolTraces())
            .extracting(InteractionToolTrace::getToolName)
            .containsExactly("document_search");
        assertThat(result.metadata())
            .containsEntry("resumedPendingToolExecution", true)
            .containsEntry("resumedPendingTool", "document_search");
        assertThat(chatModel.messages().get(0))
            .contains("Confirmed pending Tool document_search succeeded")
            .contains("Document evidence snippets")
            .contains("Internal Definition Handbook");
    }

    @Test
    void pendingDocumentSearchCompletionAllowsNextWorkflowWebSearch() {
        String documentSearch = "mcp_chatchat_mcp_server_document_search";
        String webSearch = "mcp_chatchat_mcp_server_web_search";
        CapturingQueueChatModel chatModel = new CapturingQueueChatModel(
            interpretationPlan("Document evidence and web evidence are both available.", webSearch),
            "{\"accepted\":true,\"feedback\":\"The answer used both observations.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(documentSearch)).thenReturn(true);
        when(toolRegistry.hasTool(webSearch)).thenReturn(true);
        when(toolRegistry.getToolMetadata(documentSearch)).thenReturn(ToolMetadata.builder()
            .id(documentSearch)
            .title("MCP Document Search")
            .description("Search internal documents")
            .build());
        when(toolRegistry.getToolMetadata(webSearch)).thenReturn(ToolMetadata.builder()
            .id(webSearch)
            .title("MCP Web Search")
            .description("Search web pages")
            .build());
        when(toolRegistry.executeEnhancedTool(eq(documentSearch), any())).thenReturn(documentSearchOutput());
        when(toolRegistry.executeEnhancedTool(eq(webSearch), any())).thenReturn(webSearchOutput());
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );
        Map<String, Object> workflowConfig = Map.of(
            "enabled", true,
            "workflow", "document-web-verification",
            "executionStrategy", Map.of("mode", "sequential", "stopOnError", true),
            "steps", List.of(
                Map.of("step", 1, "tool", documentSearch, "required", true, "confirmation", "auto_execute"),
                Map.of("step", 2, "tool", webSearch, "required", true, "confirmation", "auto_execute")
            )
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "Kafka Connect 安全认证与启动",
            "tenant-1",
            List.of(documentSearch, webSearch),
            "Use document evidence, then web verification.",
            null,
            List.of("doc-1"),
            List.of(),
            "livedata_ops",
            "req-confirm-doc-web",
            "conv-confirm-doc-web",
            "user-1",
            10,
            List.of(webSearch),
            true,
            Map.of(
                "mcpWorkflow", workflowConfig,
                "mcpConfirmation", Map.of("approved", true),
                "mcpPendingToolExecution", Map.of(
                    "toolName", documentSearch,
                    "input", Map.of("query", "Kafka Connect 安全认证与启动"),
                    "executionPlan", Map.of("workflow", "document-web-verification", "reason", "Confirmed by user")
                )
            )
        );

        assertThat(result.toolTraces())
            .extracting(InteractionToolTrace::getToolName)
            .containsExactly(documentSearch, webSearch);
        assertThat(result.toolTraces())
            .allMatch(InteractionToolTrace::isSuccess);
        assertThat(result.metadata())
            .containsEntry("resumedPendingToolExecution", true);
        assertThat(chatModel.messages().get(0))
            .contains("Confirmed pending Tool " + documentSearch + " succeeded")
            .contains("Document evidence snippets");
    }

    @Test
    void workflowConfigRequiredStepsDriveMandatoryToolsAndUseFullMcpToolNames() {
        String mcpDocumentSearch = "mcp_chatchat_mcp_server_document_search";
        String mcpWebSearch = "mcp_chatchat_mcp_server_web_search";
        CapturingQueueChatModel chatModel = new CapturingQueueChatModel(
            interpretationPlan("Workflow summary uses internal and web observations.", mcpDocumentSearch, mcpWebSearch),
            "{\"accepted\":true,\"feedback\":\"The answer follows the workflow.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(mcpDocumentSearch)).thenReturn(true);
        when(toolRegistry.hasTool(mcpWebSearch)).thenReturn(true);
        when(toolRegistry.getToolMetadata(mcpDocumentSearch)).thenReturn(ToolMetadata.builder()
            .id(mcpDocumentSearch)
            .title("MCP Document Search")
            .description("Search internal documents")
            .build());
        when(toolRegistry.getToolMetadata(mcpWebSearch)).thenReturn(ToolMetadata.builder()
            .id(mcpWebSearch)
            .title("MCP Web Search")
            .description("Search web pages")
            .build());
        when(toolRegistry.executeEnhancedTool(eq(mcpDocumentSearch), any())).thenReturn(documentSearchOutput());
        when(toolRegistry.executeEnhancedTool(eq(mcpWebSearch), any())).thenReturn(webSearchOutput());
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );
        Map<String, Object> workflowConfig = Map.of(
            "enabled", true,
            "executionStrategy", Map.of("mode", "sequential", "stopOnError", true),
            "steps", List.of(
                Map.of("step", 1, "tool", mcpDocumentSearch, "required", true),
                Map.of("step", 2, "tool", mcpWebSearch, "required", true)
            )
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "PushGateway 与 Prometheus 指标集成?",
            "tenant-1",
            List.of(mcpWebSearch, mcpDocumentSearch),
            "Use internal documents before web verification.",
            null,
            List.of("doc-1"),
            List.of(),
            "livedata_ops",
            "req-workflow-doc-web",
            "conv-workflow-doc-web",
            "user-1",
            10,
            List.of(mcpWebSearch),
            true,
            Map.of("mcpWorkflow", workflowConfig)
        );

        assertThat(result.toolTraces())
            .extracting(InteractionToolTrace::getToolName)
            .containsExactly(mcpDocumentSearch, mcpWebSearch);
        assertThat(result.metadata())
            .containsEntry("workflowMandatoryTools", List.of(mcpDocumentSearch, mcpWebSearch))
            .containsEntry("mandatoryTools", List.of(mcpDocumentSearch, mcpWebSearch))
            .containsEntry("runtimeEnforcedMcpWorkflow", true);
        assertThat(chatModel.messages().get(0))
            .contains("The user query MUST first be converted into this executable InterpretationPlan")
            .contains(mcpDocumentSearch)
            .contains(mcpWebSearch);
        assertThat(chatModel.messages().stream()
            .anyMatch(message -> message.contains("Decision protocol:")
                && message.contains(InterpretationExecutionProtocol.VERSION)
                && message.contains("\"action\": \"execute_step | execute_parallel_steps | final_answer | rewrite_plan | abort\"")
                && message.contains("Observation contract used for replay/debug")))
            .isTrue();
    }

    @Test
    void workflowRuntimeOverridesPlannerWhenItSkipsConfiguredToolOrder() {
        String profileTool = "mcp_crm_customer_profile";
        String assetTool = "mcp_crm_customer_assets";
        CapturingQueueChatModel chatModel = new CapturingQueueChatModel(
            interpretationPlan("Profile and assets were both checked.", profileTool, assetTool),
            "{\"accepted\":true,\"feedback\":\"The answer follows the configured workflow.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(profileTool)).thenReturn(true);
        when(toolRegistry.hasTool(assetTool)).thenReturn(true);
        when(toolRegistry.getToolMetadata(profileTool)).thenReturn(ToolMetadata.builder()
            .id(profileTool)
            .title("Customer Profile")
            .description("Fetch customer profile")
            .build());
        when(toolRegistry.getToolMetadata(assetTool)).thenReturn(ToolMetadata.builder()
            .id(assetTool)
            .title("Customer Assets")
            .description("Fetch customer assets")
            .build());
        when(toolRegistry.executeEnhancedTool(eq(profileTool), any())).thenReturn(ToolOutput.success(Map.of("customer_id", "c-001")));
        when(toolRegistry.executeEnhancedTool(eq(assetTool), any())).thenReturn(ToolOutput.success(Map.of("aum", 1200000)));
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );
        Map<String, Object> workflowConfig = Map.of(
            "enabled", true,
            "workflow", "customer_review",
            "executionStrategy", Map.of("mode", "sequential", "stopOnError", true, "maxSteps", 7),
            "steps", List.of(
                Map.of("step", 1, "tool", profileTool, "required", true, "confirmation", "auto_execute"),
                Map.of("step", 2, "tool", assetTool, "required", true, "confirmation", "auto_execute", "dependsOn", List.of(profileTool))
            )
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "Review customer c-001.",
            "tenant-1",
            List.of(profileTool, assetTool),
            "Follow the selected Agent tool workflow.",
            null,
            List.of(),
            List.of(),
            "customer_review",
            "req-workflow-override",
            "conv-workflow-override",
            "user-1",
            10,
            List.of(),
            true,
            Map.of("mcpWorkflow", workflowConfig)
        );

        assertThat(result.toolTraces())
            .extracting(InteractionToolTrace::getToolName)
            .containsExactly(profileTool, assetTool);
        assertThat(result.metadata())
            .containsEntry("workflowMandatoryTools", List.of(profileTool, assetTool))
            .containsEntry("authoritativeWorkflowTaskId", "req-workflow-override")
            .containsEntry("authoritativeWorkflowSource", "user_defined_mcp_workflow")
            .containsEntry("maxSteps", 7)
            .containsEntry("runtimeEnforcedMcpWorkflow", true)
            .doesNotContainKey("workflowToolOverrides");
        assertThat((List<Map<String, Object>>) result.metadata().get("authoritativeWorkflowDag"))
            .hasSize(2)
            .anySatisfy(node -> assertThat(node)
                .containsEntry("tool", assetTool)
                .containsEntry("dependsOnTools", List.of(profileTool)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void workflowConditionCanShortCircuitMandatoryStepAndContinueDependentFlow() {
        String profileTool = "mcp_crm_customer_profile";
        String assetTool = "mcp_crm_customer_assets";
        CapturingQueueChatModel chatModel = new CapturingQueueChatModel(
            interpretationPlan("Asset check completed after skipping profile by policy condition.", assetTool),
            "{\"accepted\":true,\"feedback\":\"The conditional workflow was respected.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(profileTool)).thenReturn(true);
        when(toolRegistry.hasTool(assetTool)).thenReturn(true);
        when(toolRegistry.getToolMetadata(profileTool)).thenReturn(ToolMetadata.builder()
            .id(profileTool)
            .title("Customer Profile")
            .description("Fetch customer profile")
            .build());
        when(toolRegistry.getToolMetadata(assetTool)).thenReturn(ToolMetadata.builder()
            .id(assetTool)
            .title("Customer Assets")
            .description("Fetch customer assets")
            .build());
        when(toolRegistry.executeEnhancedTool(eq(assetTool), any())).thenReturn(ToolOutput.success(Map.of("aum", 1200000)));
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );
        Map<String, Object> workflowConfig = Map.of(
            "enabled", true,
            "workflow", "conditional_customer_review",
            "executionStrategy", Map.of("mode", "sequential", "stopOnError", true),
            "steps", List.of(
                Map.of("step", 1, "tool", profileTool, "required", true, "condition", "needsProfile == true"),
                Map.of("step", 2, "tool", assetTool, "required", true, "dependsOn", List.of(profileTool))
            )
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "Review customer assets only.",
            "tenant-1",
            List.of(profileTool, assetTool),
            "Follow the selected Agent tool workflow.",
            null,
            List.of(),
            List.of(),
            "customer_review",
            "req-workflow-condition",
            "conv-workflow-condition",
            "user-1",
            10,
            List.of(),
            true,
            Map.of(
                "mcpWorkflow", workflowConfig,
                "workflowContext", Map.of("needsProfile", false)
            )
        );

        assertThat(result.toolTraces())
            .extracting(InteractionToolTrace::getToolName)
            .containsExactly(assetTool);
        assertThat(result.metadata())
            .containsEntry("workflowSkippedTools", List.of(profileTool))
            .containsEntry("workflowMandatoryTools", List.of(assetTool))
            .containsEntry("runtimeEnforcedMcpWorkflow", true);
        List<Map<String, Object>> skipDecisions = (List<Map<String, Object>>) result.metadata().get("workflowSkipDecisions");
        assertThat(skipDecisions)
            .singleElement()
            .satisfies(decision -> assertThat(decision)
                .containsEntry("tool", profileTool)
                .containsEntry("status", "SKIPPED")
                .containsEntry("reason", "CONDITION_NOT_MET")
                .containsEntry("condition", "needsProfile == true")
                .containsEntry("evaluated", false));
        verify(toolRegistry, never()).executeEnhancedTool(eq(profileTool), any());
    }

    @Test
    void workflowParallelRequiredToolsMustAllCompleteBeforeFinalAnswer() {
        String documentSearch = "mcp_xxx_document_search";
        String knowledgeSearch = "mcp_xxx_knowledge_search";
        CapturingQueueChatModel chatModel = new CapturingQueueChatModel(
            interpretationPlan("Both internal tools have been observed.", documentSearch, knowledgeSearch),
            "{\"accepted\":true,\"feedback\":\"All required tools were observed.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(documentSearch)).thenReturn(true);
        when(toolRegistry.hasTool(knowledgeSearch)).thenReturn(true);
        when(toolRegistry.getToolMetadata(documentSearch)).thenReturn(ToolMetadata.builder()
            .id(documentSearch)
            .title("MCP Document Search")
            .description("Search internal documents")
            .build());
        when(toolRegistry.getToolMetadata(knowledgeSearch)).thenReturn(ToolMetadata.builder()
            .id(knowledgeSearch)
            .title("MCP Knowledge Search")
            .description("Search internal knowledge")
            .build());
        when(toolRegistry.executeEnhancedTool(eq(documentSearch), any())).thenReturn(documentSearchOutput());
        when(toolRegistry.executeEnhancedTool(eq(knowledgeSearch), any())).thenReturn(documentSearchOutput());
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );
        Map<String, Object> workflowConfig = Map.of(
            "enabled", true,
            "executionStrategy", Map.of("mode", "hybrid", "stopOnError", true),
            "steps", List.of(
                Map.of(
                    "step", 1,
                    "name", "internal_retrieval",
                    "parallelSteps", List.of(documentSearch, knowledgeSearch),
                    "required", true
                )
            )
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "Summarize internal evidence.",
            "tenant-1",
            List.of(documentSearch, knowledgeSearch),
            "Use all required internal tools.",
            null,
            List.of(),
            List.of(),
            "research",
            "req-parallel-required",
            "conv-parallel-required",
            "user-1",
            10,
            List.of(),
            true,
            Map.of("mcpWorkflow", workflowConfig)
        );

        assertThat(result.answer()).contains("Both internal tools have been observed.");
        assertThat(result.toolTraces())
            .extracting(InteractionToolTrace::getToolName)
            .containsExactly(documentSearch, knowledgeSearch);
        assertThat(result.metadata())
            .containsEntry("workflowMandatoryTools", List.of(documentSearch, knowledgeSearch))
            .containsEntry("missingMandatoryTools", List.of())
            .containsEntry("runtimeEnforcedMcpWorkflow", true);
        assertThat(chatModel.messages().get(0))
            .contains("The user query MUST first be converted into this executable InterpretationPlan")
            .contains(documentSearch)
            .contains(knowledgeSearch);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mandatoryWorkflowFailureBlocksFinalSummarySynthesis() {
        String templateSearch = "mandatory_fetch_alert_template";
        String sqlExecute = "mandatory_query_execute";
        QueueChatModel chatModel = new QueueChatModel(
            interpretationPlan("This answer must not be used.", templateSearch, sqlExecute),
            "已匹配到执行计划，但必需工具 mandatory_query_execute 未执行到终态，本次不能给出真实行情异常提醒分析结论。",
            "{\"accepted\":true,\"feedback\":\"failure summary is grounded\",\"revisedAnswer\":\"\"}",
            "{\"preferredId\":\"candidate\",\"reason\":\"grounded failure summary\",\"candidates\":[{\"id\":\"candidate\",\"score\":1.0,\"accuracy\":1.0,\"grounding\":1.0,\"completeness\":1.0,\"citation\":1.0,\"usefulness\":1.0,\"contradictsObservation\":false,\"usesFailedToolEvidence\":false,\"missingRequiredCitation\":false,\"schemaViolation\":false,\"unsafe\":false,\"issues\":[]}]}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(templateSearch)).thenReturn(true);
        when(toolRegistry.hasTool(sqlExecute)).thenReturn(true);
        when(toolRegistry.getToolMetadata(templateSearch)).thenReturn(ToolMetadata.builder()
            .id(templateSearch)
            .title("Template Search")
            .description("Search governed templates")
            .riskLevel("low")
            .build());
        when(toolRegistry.getToolMetadata(sqlExecute)).thenReturn(ToolMetadata.builder()
            .id(sqlExecute)
            .title("SQL Execute")
            .description("Execute governed SQL")
            .riskLevel("low")
            .build());
        when(toolRegistry.executeEnhancedTool(eq(templateSearch), any())).thenReturn(ToolOutput.failure("template search rejected invalid routing input"));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            new ToolRuntimeService(toolRegistry, new ObjectMapper(), toolRuntimeProperties(), List.of(), List.of()),
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "分析行情数据发生较大波动时异常提醒数据",
            "tenant-1",
            List.of(templateSearch, sqlExecute),
            "Required tools must complete before final answer.",
            null,
            List.of(),
            List.of(),
            "financial_data_analyst",
            "req-mandatory-block",
            "conv-mandatory-block",
            "user-1",
            10,
            List.of(templateSearch, sqlExecute),
            true
        );

        assertThat(result.answer())
            .contains("mandatory_fetch_alert_template, mandatory_query_execute")
            .contains("未执行到终态")
            .doesNotContain("This answer must not be used");
        assertThat(result.toolTraces())
            .extracting(InteractionToolTrace::getToolName)
            .containsExactly(templateSearch);
        assertThat(result.metadata())
            .containsEntry("fatalExecutionBlocked", true)
            .containsEntry("mandatoryWorkflowBlocked", true)
            .containsEntry("mandatoryWorkflowCompleted", false)
            .containsEntry("errorCode", "PLAN_INVALID_REQUIRED_TOOL_NOT_EXECUTED")
            .containsEntry("stopReason", "mandatory_workflow_incomplete");
        assertThat((List<String>) result.metadata().get("missingMandatoryTools"))
            .containsExactly(templateSearch, sqlExecute);
        verify(toolRegistry, never()).executeEnhancedTool(eq(sqlExecute), any());
    }

    private AgentOrchestrator newOrchestrator(ChatModel chatModel) {
        return newOrchestrator(chatModel, mock(ToolRegistry.class));
    }

    private AgentOrchestrator newOrchestrator(ChatModel chatModel, ToolRegistry toolRegistry) {
        return new AgentOrchestrator(
            chatModel,
            toolRegistry,
            new ToolRuntimeService(
                toolRegistry,
                new ObjectMapper(),
                toolRuntimeProperties(),
                List.of(),
                List.of()
            ),
            new ObjectMapper(),
            new ModelsConfig()
        );
    }

    private ToolOutput webSearchOutput() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reference_urls", List.of("https://example.com/audit"));
        result.put("results", List.of(Map.of(
            "rank", 1,
            "title", "Audit trail for AI answers",
            "url", "https://example.com/audit",
            "snippet", "Shows how web citations prove which URL supports an answer."
        )));
        return ToolOutput.success(result);
    }

    private ToolOutput searchAndExtractOutput() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", "agent evidence runtime");
        result.put("provider", "evidence");
        result.put("count", 2);
        result.put("evidence_chunks", List.of(
            Map.of(
                "chunk_id", "trusted-1",
                "title", "Trusted runtime evidence",
                "source_url", "https://docs.example.com/evidence",
                "domain", "docs.example.com",
                "score", 0.92,
                "content", "The agent evidence runtime is supported and available for structured web evidence."
            ),
            Map.of(
                "chunk_id", "low-1",
                "title", "Low quality runtime note",
                "source_url", "https://docs.example.com/low-quality",
                "domain", "docs.example.com",
                "score", 0.12,
                "content", "Low quality evidence that should not be cited."
            )
        ));
        return ToolOutput.success(result);
    }

    private ToolOutput documentSearchOutput() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("results", List.of(Map.of(
            "docId", "doc-1",
            "title", "Internal Definition Handbook",
            "snippet", "Internal definitions should be answered from enterprise documents first."
        )));
        return ToolOutput.success(result);
    }

    private ToolOutput documentEvidenceSearchOutput() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", "document_evidence_v1");
        result.put("total", 2);
        result.put("results", List.of(
            Map.of(
                "refId", "doc://spark#chunk=159",
                "fileId", "spark",
                "title", "Spark SQL Reference.md",
                "snippet", "JDBC connector CREATE TABLE example for MySQL."
            ),
            Map.of(
                "refId", "doc://spark#chunk=155",
                "fileId", "spark",
                "title", "Spark SQL Reference.md",
                "snippet", "Filesystem connector is listed as source and sink support."
            )
        ));
        result.put("citations", List.of(
            Map.of("refId", "doc://spark#chunk=159", "citation", "Spark SQL Reference.md / chunk: 159"),
            Map.of("refId", "doc://spark#chunk=155", "citation", "Spark SQL Reference.md / chunk: 155")
        ));
        return ToolOutput.success(result);
    }

    private ToolRuntimeProperties toolRuntimeProperties() {
        ToolRuntimeProperties properties = new ToolRuntimeProperties();
        properties.setEnforceAllowedTools(true);
        properties.setCircuitBreakerFailureThreshold(3);
        properties.setCircuitBreakerOpenSeconds(30);
        properties.setDefaultRetryAttempts(0);
        return properties;
    }

    private static String interpretationPlan(String answer, String... tools) {
        StringBuilder steps = new StringBuilder();
        StringBuilder allowTools = new StringBuilder();
        StringBuilder finalDependencies = new StringBuilder("[");
        int stepId = 1;
        Integer previousToolStepId = null;
        for (String tool : tools) {
            if (steps.length() > 0) {
                steps.append(",");
            }
            if (allowTools.length() > 0) {
                allowTools.append(",");
            }
            if (finalDependencies.length() > 1) {
                finalDependencies.append(",");
            }
            steps.append("{\"id\":").append(stepId)
                .append(",\"action_type\":\"mcp_tool\",\"tool_name\":\"").append(jsonEscape(tool))
                .append("\",\"input\":{\"query\":\"runtime workflow evidence\"},\"depends_on\":")
                .append(previousToolStepId == null ? "[]" : "[" + previousToolStepId + "]")
                .append("}");
            allowTools.append("\"").append(jsonEscape(tool)).append("\"");
            finalDependencies.append(stepId);
            previousToolStepId = stepId;
            stepId++;
        }
        finalDependencies.append("]");
        if (steps.length() > 0) {
            steps.append(",");
        }
        steps.append("{\"id\":").append(stepId)
            .append(",\"action_type\":\"final_answer\",\"tool_name\":\"\",\"input\":{\"answer\":\"")
            .append(jsonEscape(answer))
            .append("\"},\"depends_on\":").append(finalDependencies).append("}");
        return """
            {
              "version": "1.0",
              "intent": {"type": "workflow", "goal": "Execute the configured runtime workflow", "risk_level": "low"},
              "context": {"key_facts": [], "assumptions": [], "missing_info": [], "constraints": ["Use required workflow tools"]},
              "plan": {"steps": [%s]},
              "execution_policy": {"max_steps": %d, "allow_parallel": false, "allow_tool": [%s], "deny_tool": [], "max_rewrite_times": 0, "fallback_mode": "partial_result"},
              "review": {"self_check": {"completeness_score": 0.9, "hallucination_risk": 0.1, "tool_sufficiency": true, "missing_steps": []}, "fallback_plan": []}
            }
            """.formatted(steps, Math.max(1, tools.length + 1), allowTools);
    }

    private static String invalidToolPlan(String toolName) {
        return invalidToolPlan(toolName, "Invalid unavailable tool plan.");
    }

    private static String invalidToolPlan(String toolName, String answer) {
        return """
            {
              "version": "1.0",
              "intent": {"type": "document_retrieval", "goal": "Use unavailable evidence", "risk_level": "low"},
              "context": {"key_facts": [], "assumptions": [], "missing_info": [], "constraints": ["Use available tools"]},
              "plan": {
                "steps": [
                  {"id": 1, "action_type": "mcp_tool", "tool_name": "%s", "input": {"query": "internal definition"}, "depends_on": []},
                  {"id": 2, "action_type": "final_answer", "tool_name": "", "input": {"answer": "%s"}, "depends_on": [1]}
                ]
              },
              "execution_policy": {"max_steps": 2, "allow_parallel": false, "allow_tool": ["%s"], "deny_tool": [], "timeout_ms": 30000},
              "review": {"self_check": {"completeness_score": 0.4, "hallucination_risk": 0.4, "tool_sufficiency": false, "missing_steps": []}, "fallback_plan": []}
            }
            """.formatted(jsonEscape(toolName), jsonEscape(answer), jsonEscape(toolName));
    }

    private static String finalOnlyPlan(String answer) {
        return """
            {
              "version": "1.0",
              "intent": {"type": "document_retrieval", "goal": "Answer without evidence", "risk_level": "low"},
              "context": {"key_facts": [], "assumptions": [], "missing_info": [], "constraints": ["Use internal evidence"]},
              "plan": {
                "steps": [
                  {"id": 1, "action_type": "final_answer", "tool_name": "", "input": {"answer": "%s"}, "depends_on": []}
                ]
              },
              "execution_policy": {"max_steps": 1, "allow_parallel": false, "allow_tool": [], "deny_tool": [], "timeout_ms": 30000},
              "review": {"self_check": {"completeness_score": 0.4, "hallucination_risk": 0.4, "tool_sufficiency": false, "missing_steps": ["document_search"]}, "fallback_plan": []}
            }
            """.formatted(jsonEscape(answer));
    }

    private static String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class QueueChatModel implements ChatModel {
        private final Queue<String> responses = new ArrayDeque<>();

        private QueueChatModel(String... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public String chat(String message) {
            assertThat(message).isNotBlank();
            if (message.contains("Agent Runtime DAG execution controller")) {
                return dagDecision(message);
            }
            if (message.contains("runtime reviewer for one completed MCP tool call")) {
                return "{\"satisfied\":true,\"reason\":\"test reviewer accepts tool result\",\"selected_urls\":[],\"confidence\":1.0}";
            }
            if (message.contains("final step-by-step answer synthesizer")) {
                return answerHint(message);
            }
            assertThat(responses).isNotEmpty();
            return responses.remove();
        }
    }

    private static final class StrictDocumentReviewChatModel implements ChatModel {
        private final Queue<String> responses = new ArrayDeque<>();

        private StrictDocumentReviewChatModel(String... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public String chat(String message) {
            assertThat(message).isNotBlank();
            if (message.contains("Agent Runtime DAG execution controller")) {
                return dagDecision(message);
            }
            if (message.contains("runtime reviewer for one completed MCP tool call")) {
                return """
                    {
                      "satisfied": false,
                      "reason": "The chunks mention JDBC and Filesystem separately but do not contain a complete combined sync SQL example.",
                      "selected_urls": [],
                      "useful_refs": [],
                      "rejected_refs": ["doc://spark#chunk=159", "doc://spark#chunk=155"],
                      "relevance": 0.72,
                      "answerability": 0.38,
                      "supportsQuestionAspect": ["JDBC", "Filesystem"],
                      "missingAspects": ["combined sync SQL example"],
                      "usefulness": "MEDIUM",
                      "shouldExpandQuery": true,
                      "confidence": 0.8
                    }
                    """;
            }
            if (message.contains("final step-by-step answer synthesizer")) {
                return answerHint(message);
            }
            assertThat(responses).isNotEmpty();
            return responses.remove();
        }
    }

    private static final class CapturingQueueChatModel implements ChatModel {
        private final Queue<String> responses = new ArrayDeque<>();
        private final List<String> messages = new ArrayList<>();

        private CapturingQueueChatModel(String... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public String chat(String message) {
            assertThat(message).isNotBlank();
            messages.add(message);
            if (message.contains("Agent Runtime DAG execution controller")) {
                return dagDecision(message);
            }
            if (message.contains("runtime reviewer for one completed MCP tool call")) {
                return "{\"satisfied\":true,\"reason\":\"test reviewer accepts tool result\",\"selected_urls\":[],\"confidence\":1.0}";
            }
            if (message.contains("final step-by-step answer synthesizer")) {
                return answerHint(message);
            }
            assertThat(responses).isNotEmpty();
            return responses.remove();
        }

        private List<String> messages() {
            return messages;
        }
    }

    private static String answerHint(String message) {
        String marker = "Plan final answer hint, not authoritative evidence:";
        int start = message.indexOf(marker);
        if (start < 0) {
            return "Synthesized answer from executed steps.";
        }
        int valueStart = start + marker.length();
        int valueEnd = message.indexOf("\n\n", valueStart);
        String value = valueEnd < 0 ? message.substring(valueStart) : message.substring(valueStart, valueEnd);
        return value.trim().isBlank() ? "Synthesized answer from executed steps." : value.trim();
    }

    private static String dagDecision(String message) {
        List<Integer> remaining = integersFromLine(message, "remaining_step_ids:");
        if (remaining.isEmpty()) {
            return "{\"action\":\"abort\",\"reason\":\"No remaining DAG steps.\"}";
        }
        return "{\"action\":\"execute_step\",\"step_ids\":[" + remaining.get(0)
            + "],\"reason\":\"test model selects the next DAG step\",\"confidence\":1.0}";
    }

    private static List<Integer> integersFromLine(String message, String prefix) {
        int start = message.indexOf(prefix);
        if (start < 0) {
            return List.of();
        }
        int end = message.indexOf('\n', start);
        String line = end < 0 ? message.substring(start + prefix.length()) : message.substring(start + prefix.length(), end);
        List<Integer> values = new ArrayList<>();
        for (String token : line.replace("[", "").replace("]", "").split(",")) {
            if (token.isBlank()) {
                continue;
            }
            try {
                values.add(Integer.parseInt(token.trim()));
            } catch (NumberFormatException ignored) {
                // Ignore non-integer prompt text in the test helper.
            }
        }
        return values;
    }

    private static final class FailingChatModel implements ChatModel {
        private final String message;

        private FailingChatModel(String message) {
            this.message = message;
        }

        @Override
        public String chat(String message) {
            throw new IllegalStateException(this.message);
        }
    }

    private static final class SlowChatModel implements ChatModel {
        private final long sleepMs;
        private final String response;

        private SlowChatModel(long sleepMs, String response) {
            this.sleepMs = sleepMs;
            this.response = response;
        }

        @Override
        public String chat(String message) {
            assertThat(message).isNotBlank();
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return response;
        }
    }
}
