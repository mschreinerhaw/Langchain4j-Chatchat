package com.chatchat.agents.orchestration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.runtime.tool.ToolRuntimeProperties;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.chatchat.agents.orchestration.analysis.context.ContextTokenEstimator;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class ToolReviewerOptimizationTest {
    @Test void stagedBridgeResolvesAnExplicitCanonicalUserReferenceWithoutModelReview() {
        ChatModel model=mock(ChatModel.class);ToolRegistry registry=mock(ToolRegistry.class);
        when(registry.getToolMetadata(TOOL)).thenReturn(ToolMetadata.builder().metadata(Map.of(ToolWorkflowContract.METADATA_KEY,
            ToolWorkflowContract.declaration(ToolWorkflowRole.TEMPLATE_DISCOVERY,"fixture","asset_query_result.v1"))).build());
        var engine=new AgentOrchestrationEngine(model,registry,new ToolRuntimeService(registry,mapper,new ToolRuntimeProperties(),List.of(),List.of()),mapper,new ModelsConfig());
        var output=Map.<String,Object>of("schemaVersion","asset_query_result.v1","stage","asset","success",true,"possiblyTruncated",false,"returnedCount",1,
            "assets",List.of(Map.of("asset",Map.of("id","canonical-target","name","node-alpha 集群","enabled",true))));
        var execution=new InterpretationPlanRuntime.StepExecution(1,"mcp_tool",TOOL,true,output,null,null,null,1L);
        var review=engine.reviewInterpretationPlanToolResult(model,"分析 node-alpha 集群 的状态",null,()->false,
            new InterpretationPlanRuntime.StepReviewRequest(null,null,execution,Map.of(),1,1));
        assertThat(review.metadata()).containsEntry("toolResultReviewMode",com.chatchat.agents.runtime.plan.review.CanonicalAssetReferenceReviewer.MODE);
        assertThat(new com.chatchat.agents.runtime.plan.selection.EvidenceBasedAssetCandidateEvaluator().evaluate(output,review.metadata()).selectedIds())
            .containsExactly("canonical-target");verifyNoInteractions(model);
    }
    @Test void explicitCanonicalAssetSelectionAvoidsModelReviewButSoleUnselectedAssetDoesNot() {
        ChatModel model=mock(ChatModel.class);ToolRegistry registry=mock(ToolRegistry.class);
        when(registry.getToolMetadata(TOOL)).thenReturn(ToolMetadata.builder().metadata(Map.of(ToolWorkflowContract.METADATA_KEY,
            ToolWorkflowContract.declaration(ToolWorkflowRole.ASSET_DISCOVERY,"fixture","asset_query_result.v1"))).build());
        var engine=new AgentOrchestrationEngine(model,registry,new ToolRuntimeService(registry,mapper,new ToolRuntimeProperties(),List.of(),List.of()),mapper,new ModelsConfig());
        var output=Map.<String,Object>of("assets",List.of(Map.of("assetId","canonical-target")),
            "queryIr",Map.of("asset",Map.of("selected",Map.of("id","canonical-target"))));
        var execution=new InterpretationPlanRuntime.StepExecution(1,"mcp_tool",TOOL,true,output,null,null,null,1L);
        var review=engine.reviewInterpretationPlanToolResult(model,"inspect the configured target",null,()->false,
            new InterpretationPlanRuntime.StepReviewRequest(null,null,execution,Map.of(),1,1));
        assertThat(review.metadata()).containsEntry("toolResultReviewMode","RUNTIME_DECLARED_ASSET_SELECTION");
        var projected=new com.chatchat.agents.runtime.plan.selection.EvidenceBasedAssetCandidateEvaluator().evaluate(output,review.metadata());
        assertThat(projected.selectedIds()).containsExactly("canonical-target");verifyNoInteractions(model);
        var ambiguous=new InterpretationPlanRuntime.StepExecution(1,"mcp_tool",TOOL,true,
            Map.of("assets",List.of(Map.of("assetId","canonical-target"))),null,null,null,1L);
        var needsReview=engine.reviewInterpretationPlanToolResult(model,"inspect another target",null,()->false,
            new InterpretationPlanRuntime.StepReviewRequest(null,null,ambiguous,Map.of(),1,1));
        assertThat(needsReview.metadata()).containsEntry("toolResultReviewUnavailable",true);
        verify(model,times(1)).chat(anyString());
    }
    @Test void publisherBaselineUsesNoModelEvenWhenModelWouldRejectEveryCandidate() {
        ChatModel model=mock(ChatModel.class);ToolRegistry registry=mock(ToolRegistry.class);
        when(registry.getToolMetadata(TOOL)).thenReturn(ToolMetadata.builder().metadata(Map.of(
            ToolWorkflowContract.METADATA_KEY,ToolWorkflowContract.declaration(ToolWorkflowRole.TEMPLATE_DISCOVERY,"fixture","template_query.v1"),
            com.chatchat.common.mcp.capability.McpTemplateSelectionScope.METADATA_KEY,
            com.chatchat.common.mcp.capability.McpTemplateSelectionScope.fixedBinding("fixture").toMetadata(),
            com.chatchat.common.mcp.capability.McpDataSupplyPolicy.METADATA_KEY,
            com.chatchat.common.mcp.capability.McpDataSupplyPolicy.fixedBaseline())).build());
        var engine=new AgentOrchestrationEngine(model,registry,new ToolRuntimeService(registry,mapper,new ToolRuntimeProperties(),List.of(),List.of()),mapper,new ModelsConfig());
        var output=Map.<String,Object>of("success",true,"scopeMode","FIXED_BINDING","bindingComplete",true,"hasMore",false,
            com.chatchat.common.mcp.capability.McpDataSupplyPolicy.METADATA_KEY,
            com.chatchat.common.mcp.capability.McpDataSupplyPolicy.fixedBaseline(),
            "templates",List.of(Map.of("templateId","source-a","parameterSchema",Map.of("type","object"))));
        var execution=new InterpretationPlanRuntime.StepExecution(1,"mcp_tool",TOOL,true,output,null,null,null,1L);
        var review=engine.reviewInterpretationPlanToolResult(model,"collect the published baseline",null,()->false,
            new InterpretationPlanRuntime.StepReviewRequest(null,null,execution,Map.of(),1,1));
        assertThat(review.satisfied()).isTrue();assertThat(review.metadata()).containsEntry("selectedTemplateIds",List.of("source-a"));
        verifyNoInteractions(model);
    }
    static final String TOOL="publisher_declared_discovery";
    final ObjectMapper mapper=new ObjectMapper();
    AgentOrchestrationEngine engine(ChatModel model) {
        ToolRegistry registry=mock(ToolRegistry.class);
        when(registry.getToolMetadata(TOOL)).thenReturn(ToolMetadata.builder().metadata(Map.of(ToolWorkflowContract.METADATA_KEY,
            ToolWorkflowContract.declaration(ToolWorkflowRole.TEMPLATE_DISCOVERY,"fixture","template_query.v1"))).build());
        return new AgentOrchestrationEngine(model,registry,new ToolRuntimeService(registry,mapper,new ToolRuntimeProperties(),List.of(),List.of()),mapper,new ModelsConfig());
    }
    InterpretationPlanRuntime.StepReviewRequest fixture() {
        List<Map<String,Object>> candidates=new ArrayList<>();
        for(int i=1;i<=30;i++)candidates.add(Map.of("templateId","candidate-"+i,"name","Candidate "+i,
            "description","declared-shared-capability ".repeat(35),"capabilitySpec",Map.of("measure","distinct-measure-"+i),
            "parameterSchema",Map.of("description","shared-parameter-schema ".repeat(45)),
            "templateConfig",Map.of("privateExecutor","DO_NOT_SEND_EXECUTOR".repeat(500))));
        var execution=new InterpretationPlanRuntime.StepExecution(20,"mcp_tool",TOOL,true,Map.of("templates",candidates,"hasMore",false),null,null,null,1L);
        return new InterpretationPlanRuntime.StepReviewRequest(null,null,execution,Map.of(),1,1,"fixture-run");
    }
    @Test void repeatedCandidateBenchmark()throws Exception {
        String prompt=engine(mock(ChatModel.class)).buildToolResultReviewPrompt("Compare the declared measures for every applicable subject.",null,fixture());
        var size=new ContextTokenEstimator().estimate(prompt);
        String path=System.getProperty("toolReview.benchmarkFile");
        if(path!=null)Files.writeString(Path.of(path),mapper.writeValueAsString(Map.of("promptChars",size.chars(),"estimatedTokens",size.tokens())));
        assertThat(prompt).contains("candidate-1","candidate-30").doesNotContain("DO_NOT_SEND_EXECUTOR");
        System.out.println("TOOL_REVIEW_BENCHMARK chars="+size.chars()+" estimatedTokens="+size.tokens());
    }
    @Test void preservesEveryCurrentCandidateCapabilityAndDoesNotRepeatCatalog() {
        String prompt=engine(mock(ChatModel.class)).buildToolResultReviewPrompt("compare all applicable measures",null,fixture());
        for(int i=1;i<=30;i++)assertThat(prompt).contains("candidate-"+i,"distinct-measure-"+i);
        assertThat(prompt).contains("tool_review_shared_context.v1").doesNotContain("Complete returned candidate identity catalog");
    }
    @Test void currentInputIsSentOnceAndRecentHistoryDoesNotDisplaceDeclaredDependency() {
        Map<Integer,InterpretationPlanRuntime.StepExecution> prior=new LinkedHashMap<>();
        for(int i=1;i<=40;i++)prior.put(i,new InterpretationPlanRuntime.StepExecution(i,"mcp_tool",TOOL,true,Map.of("templates",List.of(Map.of("templateId","prior-"+i,"name","previous "+i))),null,null,null,1L));
        String input="unique_resolved_input_".repeat(180);
        var step=new InterpretationPlan.Step(50,"mcp_tool",TOOL,Map.of("argument",input),List.of(1),null,null);
        var request=new InterpretationPlanRuntime.StepReviewRequest(null,step,fixture().execution(),prior,1,1,"fixture");
        String prompt=engine(mock(ChatModel.class)).buildToolResultReviewPrompt("compare",null,request);
        assertThat(prompt).contains("prior-1\"","prior-40\"").doesNotContain("prior-2\"","currentStepInput");
        assertThat(prompt.indexOf(input)).isGreaterThan(0).isEqualTo(prompt.lastIndexOf(input));
    }
    @Test void independentCoverageAuditStillRunsAndCannotAdmitAnEmptySelection() {
        ChatModel model=mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("{\"satisfied\":false,\"selected_template_ids\":[],\"reason\":\"no declared match\"}",
            "{\"coverage_decision\":\"SCOPE_INSUFFICIENT\",\"corrected_selected_template_ids\":[],\"reason\":\"no declared source\"}");
        var review=engine(model).reviewInterpretationPlanToolResult(model,"compare",null,()->false,fixture());
        assertThat(review.satisfied()).isFalse();verify(model,times(2)).chat(anyString());
        assertThat(review.metadata()).containsKeys("toolResultReviewPromptChars","toolResultReviewEstimatedTokens","toolResultReviewModelDurationMs","toolResultReviewCoverageDurationMs","toolResultReviewBindingDurationMs");
    }
    @Test void cancellationAfterSelectionPreventsAdditionalModelRequests() {
        ChatModel model=mock(ChatModel.class);var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        when(model.chat(anyString())).thenAnswer(call->{cancelled.set(true);return "{\"satisfied\":false}";});
        assertThatThrownBy(()->engine(model).reviewInterpretationPlanToolResult(model,"compare",null,cancelled::get,fixture()))
            .isInstanceOf(java.util.concurrent.CancellationException.class);
        verify(model,times(1)).chat(anyString());
    }
    @Test void missingModelResponseRetainsUnavailableMarkerWithoutNullMetadataFailure() {
        ChatModel model=mock(ChatModel.class);
        var review=engine(model).reviewInterpretationPlanToolResult(model,"compare",null,()->false,fixture());
        assertThat(review.metadata()).containsEntry("toolResultReviewUnavailable",true);
        verify(model,times(1)).chat(anyString());
    }
    @Test void allSelectedEmptySchemasRemainAOneModelCallPath() {
        ChatModel model=mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("{\"satisfied\":true,\"selected_template_ids\":[\"approved-check\"],\"coverage_decision\":\"SUFFICIENT\"}");
        var execution=new InterpretationPlanRuntime.StepExecution(1,"mcp_tool",TOOL,true,
            Map.of("templates",List.of(Map.of("templateId","approved-check","parameterSchema",Map.of("type","object","properties",Map.of())))),null,null,null,1L);
        var request=new InterpretationPlanRuntime.StepReviewRequest(null,null,execution,Map.of(),1,1);
        var review=engine(model).reviewInterpretationPlanToolResult(model,"inspect declared service",null,()->false,request);
        assertThat(review.satisfied()).isTrue();assertThat(review.metadata()).containsEntry("parameterBindingMode","runtime_empty_schema");
        verify(model,times(1)).chat(anyString());verify(model,never()).chat(org.mockito.ArgumentMatchers.any(dev.langchain4j.model.chat.request.ChatRequest.class));
    }
    @Test void largeResolvedArgumentsUseTraceReferencesWithoutChangingExecutionInput() {
        String resolved="large-resolved-record-".repeat(10000);
        var step=new InterpretationPlan.Step(50,"mcp_tool",TOOL,Map.of("assetId","exact-target","records",resolved),List.of(),null,null);
        var request=new InterpretationPlanRuntime.StepReviewRequest(null,step,fixture().execution(),Map.of(),1,1);
        String prompt=engine(mock(ChatModel.class)).buildToolResultReviewPrompt("inspect the declared target",null,request);
        assertThat(prompt).contains("exact-target","runtimeStepInputRef","valueSha256","inputProjectionLimited").doesNotContain("large-resolved-record-");
        assertThat(step.input().get("records")).isEqualTo(resolved);
        assertThat(prompt).hasSizeLessThan(20000);
    }
}
