package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.tool.ToolRuntimeProperties;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ToolResultReviewBranchTest {
    private final ChatModel model = mock(ChatModel.class);
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulPublishedCapabilityNeedsNoSecondModelForEitherMetadataEnvelope(boolean nested) {
        Map<String, Object> publication = Map.of("templateCapability", true,
            "schemaVersion", "template-capability.v1");
        metadata("opaque_registered_tool", nested ? Map.of("mcpToolMeta", publication) : publication);
        var output = Map.of("rows", List.of(Map.of("amount", "153.17")), "possiblyTruncated", true);
        var review = review("opaque_registered_tool", true, output);

        assertThat(review.satisfied()).isTrue();
        assertThat(review.metadata()).containsEntry("toolResultReviewModelCalls", 0)
            .containsEntry("toolResultReviewMode", "RUNTIME_DETERMINISTIC_EXECUTION_ADMISSION")
            .containsEntry("templateExecutionSatisfied", true)
            .doesNotContainKeys("evidenceEvaluation", "evidenceIterationSufficient");
        assertThat(output).containsEntry("possiblyTruncated", true);
        verifyNoInteractions(model);
    }

    @Test
    void publishedCapabilityFailureStillGetsRecoveryAdviceRatherThanSuccessAdmission() {
        metadata("published_tool", Map.of("mcpToolMeta", Map.of("templateCapability", true,
            "schemaVersion", "template-capability.v1")));
        when(model.chat(anyString())).thenReturn("""
            {"satisfied":false,"reason":"required parameter missing",
             "missing_parameters":["account"],"retry_input_changes":{"account":"A42"}}
            """);

        var review = review("published_tool", false, Map.of("error", "required parameter missing"));

        assertThat(review.satisfied()).isFalse();
        assertThat(review.metadata()).containsEntry("toolResultReviewPurpose", "EXECUTION_RECOVERY")
            .containsEntry("toolResultReviewModelCalls", 1)
            .containsEntry("missingParameters", List.of("account"))
            .containsEntry("retryInputChanges", Map.of("account", "A42"));
        verify(model, times(1)).chat(anyString());
        verify(model, never()).chat(any(ChatRequest.class));
    }

    @Test
    void toolNameAndResultCannotImpersonatePublishedMetadata() {
        when(model.chat(anyString())).thenReturn("{\"satisfied\":true}");
        var review = review("tpl_name_alone", true, Map.of("templateCapability", true,
            "schemaVersion", "template-capability.v1", "rows", List.of()));

        assertModelReview(review, "EVIDENCE_ASSESSMENT");
        verify(model, times(1)).chat(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "template-capability.v2"})
    void unrecognizedPublicationContractRetainsModelReview(String schemaVersion) {
        metadata("published_tool", Map.of("mcpToolMeta", Map.of("templateCapability", true,
            "schemaVersion", schemaVersion)));
        when(model.chat(anyString())).thenReturn("{\"satisfied\":true}");

        assertModelReview(review("published_tool", true, Map.of("rows", List.of())), "EVIDENCE_ASSESSMENT");
        verify(model, times(1)).chat(anyString());
    }

    @Test
    void publicationFlagNeverBypassesAssetSelection() {
        metadata("asset_query", Map.of("templateCapability", true, "schemaVersion", "template-capability.v1",
            ToolWorkflowContract.METADATA_KEY,
            ToolWorkflowContract.declaration(ToolWorkflowRole.ASSET_DISCOVERY, "fixture", "asset_query.v1")));
        var review = review("asset_query", true, Map.of("assets",
            List.of(Map.of("assetId", "asset-a"), Map.of("assetId", "asset-b"))));

        assertThat(review.metadata()).containsEntry("candidateSelectionErrorCode", "CLINE_ASSET_CONFIRMATION_UNAVAILABLE");
        verifyNoInteractions(model);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void clineSearchTermsAndConfirmedAssetsStayInsideRuntimeBindings(boolean authorized) {
        var declaration = ToolWorkflowContract.declaration(ToolWorkflowRole.ASSET_DISCOVERY, "fixture", "asset_query.v1");
        metadata("asset_query", Map.of(ToolWorkflowContract.METADATA_KEY, declaration));
        var port = mock(com.chatchat.agents.orchestration.retrieval.ClineDiscoveryPort.class);
        var scope = new com.chatchat.common.kernel.KernelDataScope("tenant", "user", "request", "conversation", "run", null, Map.of());
        when(port.propose(eq(scope), eq("model"), eq(1), eq(com.chatchat.agents.orchestration.retrieval.ClineDiscoveryPort.Stage.SEARCH_TERMS), any(), any()))
            .thenReturn(Map.of("queryTerms", List.of("customer warehouse")));
        when(port.propose(eq(scope), eq("model"), eq(1), eq(com.chatchat.agents.orchestration.retrieval.ClineDiscoveryPort.Stage.CONFIRM_ASSETS), any(), any()))
            .thenReturn(Map.of("selectedAssetIds", List.of(authorized ? "warehouse" : "outside-scope")));
        var engine = engine();
        engine.setClineDiscoveryPort(port);
        var input = Map.<String,Object>of("filters", Map.of("assetType", "database", "keywords", List.of("old")));
        var step = new InterpretationPlan.Step(1, "mcp_tool", "asset_query", input, List.of(), null, null);
        var enriched = engine.enrichPlanSearchInput(model, "Find the customer warehouse",
            new InterpretationPlanRuntime.StepInputEnrichmentRequest(step, input, Map.of(), null), scope, "model", () -> false);
        assertThat(enriched).containsEntry("filters", Map.of("assetType", "database", "queryTerms", List.of("customer warehouse")));
        var output = Map.of("assets", List.of(Map.of("assetId", "warehouse", "name", "warehouse",
            "displayName", "Warehouse display", "type", "database", "environment", "TEST",
            "endpoint", "internal endpoint"), Map.of("assetId", "archive")));
        var review = engine.reviewInterpretationPlanToolResult(model, "Find the customer warehouse", null, () -> false,
            new InterpretationPlanRuntime.StepReviewRequest(null, step, execution("asset_query", true, output), Map.of(), 1, 1),
            Map.of(), scope, "model");
        verify(port).propose(eq(scope), eq("model"), eq(1),
            eq(com.chatchat.agents.orchestration.retrieval.ClineDiscoveryPort.Stage.CONFIRM_ASSETS),
            argThat(payload -> ((List<?>) payload.get("candidates")).get(0).equals(Map.of(
                "assetId", "warehouse", "name", "warehouse", "displayName", "Warehouse display",
                "type", "database", "environment", "TEST", "description", ""))), any());
        if (authorized) assertThat(review.metadata()).containsEntry("selectedAssetIds", List.of("warehouse"));
        else assertThat(review.metadata()).containsEntry("candidateSelectionErrorCode", "CLINE_ASSET_CONFIRMATION_REQUIRED");
        verifyNoInteractions(model);
    }

    @Test
    void templatePublicationFlagAloneDoesNotAuthorizeSelection() {
        metadata("template_query", Map.of("templateCapability", true, "schemaVersion", "template-capability.v1",
            ToolWorkflowContract.METADATA_KEY,
            ToolWorkflowContract.declaration(ToolWorkflowRole.TEMPLATE_DISCOVERY, "fixture", "template_query.v1")));

        var review = review("template_query", true, Map.of("templates", List.of(Map.of("templateId", "candidate"))));

        assertThat(review.metadata()).containsEntry("candidateSelectionErrorCode", "TEMPLATE_SELECTION_REQUIRED")
            .containsEntry("mcpDiscoverySucceeded", true)
            .containsEntry("selectedTemplateIds", List.of())
            .containsEntry("toolResultReviewModelCalls", 0);
        verifyNoInteractions(model);
    }

    @Test
    void fixedPublicationAdmitsOnlyGovernedTemplatesWithoutBindingParameters() {
        var scope = com.chatchat.common.mcp.capability.McpTemplateSelectionScope.fixedBinding("api");
        metadata("template_query", Map.of(
            ToolWorkflowContract.METADATA_KEY,
            ToolWorkflowContract.declaration(ToolWorkflowRole.TEMPLATE_DISCOVERY, "fixture", "template_query.v1"),
            com.chatchat.common.mcp.capability.McpTemplateSelectionScope.METADATA_KEY, scope.toMetadata()));
        var templates = List.of(
            Map.of("templateId", "assets", "parameterSchema", Map.of("type", "object")),
            Map.of("templateId", "trades", "parameterSchema", Map.of("type", "object")));
        var governed = Map.of("templates", templates);
        var review = review("template_query", true, Map.of(
            "schemaVersion", com.chatchat.common.mcp.runtime.McpAnalysisPayload.SCHEMA_VERSION,
            "data", governed, "rawData", Map.of("templates", List.of(Map.of("templateId", "transport-only")))));

        assertThat(review.metadata()).containsEntry("toolResultReviewMode", "RUNTIME_PUBLISHED_TEMPLATE_ADMISSION")
            .containsEntry("candidateDecisionOwner", "PUBLISHER")
            .containsEntry("selectedTemplateIds", List.of("assets", "trades"))
            .doesNotContainKeys("modelSelectedTemplateIds", "parameterProtocols");
        var projected = new com.chatchat.agents.runtime.plan.selection.EvidenceBasedTemplateCandidateEvaluator()
            .evaluate(governed, review.metadata());
        assertThat(projected.selectedIds()).containsExactly("assets", "trades");
        var projection = (Map<?, ?>) projected.output();
        assertThat(projection.get("templates")).isEqualTo(templates);
        var selection = (Map<?, ?>) projection.get("runtimeTemplateSelection");
        assertThat(selection.get("selectionAuthority")).isEqualTo("runtime_published_data_supply");
        verifyNoInteractions(model);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void optedInAgentUsesClineProposalsBeforeAuthorizingTemplateInvocations(boolean complete) {
        metadata("template_query", Map.of(
            ToolWorkflowContract.METADATA_KEY,
            ToolWorkflowContract.declaration(ToolWorkflowRole.TEMPLATE_DISCOVERY, "fixture", "template_query.v1"),
            com.chatchat.common.mcp.capability.McpTemplateSelectionScope.METADATA_KEY,
            com.chatchat.common.mcp.capability.McpTemplateSelectionScope.fixedBinding("api").toMetadata()));
        var parameters = mock(com.chatchat.agents.orchestration.tool.ClineTemplateParameterPort.class);
        when(parameters.enabledFor("agent")).thenReturn(true);
        var scope = new com.chatchat.common.kernel.KernelDataScope("tenant", "user", "request", "conversation", "run", null, Map.of());
        var schema = Map.of("type", "object", "properties", Map.of("limit", Map.of("type", "integer", "default", 10)));
        var protocol = Map.<String,Object>of("protocol_version", "template_parameter_protocol_v2",
            "template_id", "orders", "arguments", Map.of("limit", Map.of("value", 3, "source", "user_query",
                "evidence", Map.of("quote", "three", "interpretation", "model"))), "unresolved_parameters", List.of());
        when(parameters.propose(eq(scope), eq("model"), eq(1), eq("Show three orders"),
            eq(List.of(Map.of("templateId", "orders", "title", "", "description", "", "parameterSchema", schema))),
            eq(com.chatchat.agents.orchestration.tool.ClineTemplateParameterPort.Selection.ALL_PUBLISHED), any()))
            .thenReturn(complete ? List.of(protocol) : List.of());
        var engine = engine();
        engine.setClineTemplateParameterPort(parameters);
        var output = Map.of("templates", List.of(Map.of("templateId", "orders", "parameterSchema", schema,
            "executionContext", Map.of("assetId", "runtime-only"))));
        var review = engine.reviewInterpretationPlanToolResult(model, "Show three orders", null, () -> false,
            new InterpretationPlanRuntime.StepReviewRequest(null, null, execution("template_query", true, output),
                Map.of(), 1, 1), Map.of("authorizationAgentId", "agent"), scope, "model");
        if (complete) {
            var reviewed = new LinkedHashMap<>(protocol);
            reviewed.put("binding_status", "READY");
            assertThat(review.metadata()).containsEntry("parameterProtocols", List.of(reviewed));
            var projected = new com.chatchat.agents.runtime.plan.selection.EvidenceBasedTemplateCandidateEvaluator()
                .evaluate(output, review.metadata());
            assertThat(projected.selectedIds()).containsExactly("orders");
            assertThat(projected.output().toString()).contains("reviewedInvocations", "value=3");
        } else {
            assertThat(review.metadata()).containsEntry("candidateSelectionErrorCode", "CLINE_TEMPLATE_PARAMETERS_UNAVAILABLE")
                .containsEntry("selectedTemplateIds", List.of());
        }
        verifyNoInteractions(model);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void clineSelectsOnlyReturnedTemplatesAfterDiscovery(boolean authorized) {
        metadata("template_query", Map.of(ToolWorkflowContract.METADATA_KEY,
            ToolWorkflowContract.declaration(ToolWorkflowRole.TEMPLATE_DISCOVERY, "fixture", "template_query.v1")));
        var parameters = mock(com.chatchat.agents.orchestration.tool.ClineTemplateParameterPort.class);
        var scope = new com.chatchat.common.kernel.KernelDataScope("tenant", "user", "request", "conversation", "run", null, Map.of());
        var protocol = Map.<String, Object>of("protocol_version", "template_parameter_protocol_v2",
            "template_id", authorized ? "orders" : "outside-scope", "arguments", Map.of(), "unresolved_parameters", List.of());
        when(parameters.propose(eq(scope), eq("model"), eq(1), eq("Show orders"), any(),
            eq(com.chatchat.agents.orchestration.tool.ClineTemplateParameterPort.Selection.MATCHING_CANDIDATES),
            any())).thenReturn(List.of(protocol));
        var engine = engine();
        engine.setClineTemplateParameterPort(parameters);
        var output = Map.of("templates", List.of(Map.of("templateId", "orders"), Map.of("templateId", "shipment")));
        var review = engine.reviewInterpretationPlanToolResult(model, "Show orders", null, () -> false,
            new InterpretationPlanRuntime.StepReviewRequest(null, null, execution("template_query", true, output),
                Map.of(), 1, 1), Map.of(), scope, "model");
        if (authorized) {
            var reviewed = new LinkedHashMap<>(protocol);
            reviewed.put("binding_status", "READY");
            assertThat(review.metadata()).containsEntry("selectedTemplateIds", List.of("orders"))
                .containsEntry("parameterProtocols", List.of(reviewed));
        } else {
            assertThat(review.metadata()).containsEntry("candidateSelectionErrorCode", "CLINE_TEMPLATE_PARAMETERS_UNAVAILABLE");
        }
        verifyNoInteractions(model);
    }

    @Test
    void downstreamCrawlerStillReceivesModelSelectedUrls() {
        when(model.chat(anyString())).thenReturn("""
            {"satisfied":false,"reason":"crawl selected page", "selected_urls":["https://example.test/b"]}
            """);
        var discovery = new InterpretationPlan.Step(1, "mcp_tool", "web_search", Map.of(), List.of(), null, null);
        var crawler = new InterpretationPlan.Step(2, "mcp_tool", "crawl_url", Map.of(), List.of(1), null, null);
        var plan = new InterpretationPlan("1.0", null, null,
            new InterpretationPlan.Plan(List.of(discovery, crawler)), null, null);
        var execution = execution("web_search", true, Map.of("results",
            List.of(Map.of("url", "https://example.test/a"), Map.of("url", "https://example.test/b"))));

        var review = engine().reviewInterpretationPlanToolResult(model, "find the requested source", null, () -> false,
            new InterpretationPlanRuntime.StepReviewRequest(plan, discovery, execution, Map.of(), 1, 1));

        assertModelReview(review, "DOWNSTREAM_URL_SELECTION");
        assertThat(review.satisfied()).isTrue();
        assertThat(review.metadata()).containsEntry("selectedUrls", List.of("https://example.test/b"));
        verify(model, times(1)).chat(anyString());
    }

    @Test
    void documentEvidenceReviewRetainsReferenceSelection() {
        when(model.chat(anyString())).thenReturn("""
            {"satisfied":true,"useful_refs":["doc://useful"],"rejected_refs":["doc://irrelevant"]}
            """);

        var review = review("document_search", true, Map.of("chunks",
            List.of(Map.of("reference", "doc://useful"), Map.of("reference", "doc://irrelevant"))));

        assertModelReview(review, "DOCUMENT_EVIDENCE_SELECTION");
        assertThat(review.metadata()).containsEntry("usefulEvidenceRefs", List.of("doc://useful"))
            .containsEntry("rejectedEvidenceRefs", List.of("doc://irrelevant"));
        verify(model, times(1)).chat(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a JSON response", "{\"error\":\"provider unavailable\"}"})
    void unavailableReviewStillRecordsTheActualModelCall(String response) {
        when(model.chat(anyString())).thenReturn(response);

        var review = review("ordinary_tool", true, Map.of("value", 7));

        assertModelReview(review, "EVIDENCE_ASSESSMENT");
        assertThat(review.satisfied()).isTrue();
        assertThat(review.metadata()).containsEntry("toolResultReviewUnavailable", true);
        verify(model, times(1)).chat(anyString());
    }

    @Test
    void cancellationStillPrecedesPublishedExecutionAdmission() {
        metadata("published_tool", Map.of("templateCapability", true, "schemaVersion", "template-capability.v1"));
        var execution = execution("published_tool", true, Map.of("value", 7));

        assertThatThrownBy(() -> engine().reviewInterpretationPlanToolResult(model, "question", null, () -> true,
            new InterpretationPlanRuntime.StepReviewRequest(null, null, execution, Map.of(), 1, 1)))
            .isInstanceOf(java.util.concurrent.CancellationException.class);
        verifyNoInteractions(model);
    }

    private void assertModelReview(InterpretationPlanRuntime.StepReview review, String purpose) {
        assertThat(review.metadata()).containsEntry("toolResultReviewMode", "MODEL_TOOL_RESULT_REVIEW")
            .containsEntry("toolResultReviewPurpose", purpose).containsEntry("toolResultReviewModelCalls", 1)
            .containsKeys("toolResultReviewModelDurationMs", "toolResultReviewTotalDurationMs");
    }

    private void metadata(String name, Map<String, Object> values) {
        when(registry.getToolMetadata(name)).thenReturn(ToolMetadata.builder().metadata(values).build());
    }

    private InterpretationPlanRuntime.StepExecution execution(String name, boolean success, Object output) {
        return new InterpretationPlanRuntime.StepExecution(1, "mcp_tool", name, success,
            output, null, null, null, 1L);
    }

    private InterpretationPlanRuntime.StepReview review(String name, boolean success, Object output) {
        return engine().reviewInterpretationPlanToolResult(model, "question", null, () -> false,
            new InterpretationPlanRuntime.StepReviewRequest(null, null, execution(name, success, output), Map.of(), 1, 1));
    }

    private AgentOrchestrationEngine engine() {
        return new AgentOrchestrationEngine(model, registry,
            new ToolRuntimeService(registry, mapper, new ToolRuntimeProperties(), List.of(), List.of()),
            mapper, new ModelsConfig());
    }
}
