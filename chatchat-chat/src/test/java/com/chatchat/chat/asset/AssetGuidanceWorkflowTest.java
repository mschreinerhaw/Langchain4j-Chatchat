package com.chatchat.chat.asset;

import com.chatchat.agents.runtime.event.AgentRunEventPublisher;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.common.runtime.analysis.asset.*;
import com.chatchat.common.runtime.analysis.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetGuidanceWorkflowTest {
    final AssetGuidanceSource source = mock(AssetGuidanceSource.class);
    final AssetGuidanceEnhancer enhancer = mock(AssetGuidanceEnhancer.class);
    final SkillExecutionScopePort scopes = mock(SkillExecutionScopePort.class);
    final ObjectProvider<AgentRunEventPublisher> events = mock(ObjectProvider.class);
    AssetGuidanceWorkflow workflow() {
        when(events.orderedStream()).thenAnswer(invocation -> Stream.empty());
        when(scopes.resolve("t", "u", "agent", List.of(), List.of())).thenReturn(
            new SkillExecutionScopePort.EffectiveScope(List.of(), List.of(), List.of("trusted-role"), false, true));
        return new AssetGuidanceWorkflow(source, enhancer, scopes, events, new ObjectMapper());
    }
    AnalysisContext context(String query) {
        return new AnalysisContext(query, new KernelDataScope("t", "u", "r", null, "run", null, Map.of()),
            "agent", List.of(), List.of(), List.of("untrusted-role"), null, Map.of());
    }
    AssetContext asset(String id) {
        return new AssetContext(id, "api_service", id, "客户持仓模板", Map.of("parameterSchema", Map.of()), Map.of(), Map.of(), "discovery", Map.of());
    }
    com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome run(AssetGuidanceWorkflow workflow, AnalysisContext context) {
        var plan = workflow.execute(context);
        var templates = workflow.resolveTemplate(context, plan);
        var request = workflow.requestData(context, plan, templates);
        return workflow.synthesize(context, request, workflow.acquireData(context, request, templates));
    }
    @Test void executionOnlyBuildsUnderstandingPlanWithoutToolsOrFinalAnswer() {
        var result = workflow().execute(context("这个 API 怎么用"));
        assertThat(result.synthesis()).isEmpty();
        assertThat(result.metadata()).containsEntry("guidanceState", "ASSET_CONTEXT_REQUIRED");
        verifyNoInteractions(source, enhancer);
    }
    @Test void runtimeUsesDefaultRequirementsAndAcquiresAssetMetadataOnceWhenTemplateIsMissing() {
        var workflow = workflow();
        when(source.retrieve(any())).thenReturn(new AssetGuidanceSource.Result(List.of(), List.of("模板未命中"), false));
        when(source.acquireMetadata(any(), any())).thenReturn(new AssetGuidanceSource.Result(List.of(asset("position")), List.of(), false));
        when(enhancer.recommend(any(), any())).thenReturn(new AssetGuidanceEnhancer.Recommendation("建议用途，未验证", List.of("skill"), "APPLIED"));
        var original = context("position API 怎么用");
        var context = new AnalysisContext(original.query(), original.kernelScope(), original.skillId(), List.of(), List.of(), List.of(),
            new AnalysisIntent("ASSET_GUIDANCE", List.of(), Set.of(AnalysisCapability.ASSET_GUIDANCE), "UNSPECIFIED", true), Map.of());
        var result = new com.chatchat.agents.runtime.analysis.workflow.DefaultAnalysisWorkflowRuntime(List.of(workflow)).analyze(context);
        assertThat(result.metadata()).containsEntry("runtimeGuidanceDecision", "GUIDANCE_READY")
            .containsEntry("runtimePublicStatus", "PARTIAL_SUCCESS");
        assertThat(((GuidanceDataRequestPlan) result.metadata().get("dataRequestPlan")).basis()).isEqualTo("DEFAULT");
        var order = inOrder(source, enhancer);
        order.verify(source).retrieve(any());
        order.verify(source).acquireMetadata(any(), argThat(plan -> plan.assetType().equals("api_service") && plan.basis().equals("DEFAULT")));
        order.verify(enhancer).recommend(any(), any());
        verifyNoMoreInteractions(source, enhancer);
    }
    @Test void fixedTemplateIncludesFactsMissingEvidenceAndSkillSuggestions() {
        var workflow = workflow();
        when(source.retrieve(any())).thenReturn(new AssetGuidanceSource.Result(List.of(asset("position")), List.of(), false));
        when(enhancer.recommend(any(), any())).thenReturn(new AssetGuidanceEnhancer.Recommendation("建议用于持仓分析，需确认输出契约。", List.of("skill"), "APPLIED"));
        var result = run(workflow, context("position API 怎么用"));
        assertThat(result.synthesis()).contains("## 1.", "## 2.", "## 3.", "## 4.", "## 5.", "暂无可验证", "建议用于持仓分析");
        assertThat(result.metadata()).containsEntry("templateExecutionExecuted", false).containsEntry("status", "PARTIAL");
        assertThat(((GuidanceDataRequestPlan) result.metadata().get("dataRequestPlan")).basis()).isEqualTo("TEMPLATE");
        verify(source).retrieve(argThat(ctx -> ctx.roles().equals(List.of("trusted-role"))));
        verify(source, never()).acquireMetadata(any(), any());
    }
    @Test void ambiguousCandidatesAskForSelectionWithoutInvokingSkills() {
        var workflow = workflow();
        when(source.retrieve(any())).thenReturn(new AssetGuidanceSource.Result(List.of(asset("a"), asset("b")), List.of(), false));
        assertThat(run(workflow, context("这个接口怎么用")).metadata()).containsEntry("status", "NEEDS_SELECTION");
        verifyNoInteractions(enhancer);
    }
    @Test void unknownExplicitTemplateMustNotFallBackToDifferentAsset() {
        var workflow = workflow();
        when(source.retrieve(any())).thenReturn(new AssetGuidanceSource.Result(List.of(asset("a")), List.of(), false));
        assertThat(run(workflow, context("接口怎么用").withAttribute("assetTemplateId", "missing")).metadata())
            .containsEntry("status", "NEEDS_SELECTION");
        verifyNoInteractions(enhancer);
    }
    @Test void noMetadataReturnsLimitationNotFabricatedFacts() {
        var workflow = workflow();
        when(source.retrieve(any())).thenReturn(new AssetGuidanceSource.Result(List.of(), List.of("未授权模板"), false));
        when(source.acquireMetadata(any(), any())).thenReturn(new AssetGuidanceSource.Result(List.of(), List.of(), false));
        var result = run(workflow, context("这个 API 怎么用"));
        assertThat(result.verification().accepted()).isFalse();
        assertThat(result.synthesis()).contains("无法确认", "未授权模板");
        verifyNoInteractions(enhancer);
        verify(source, times(1)).acquireMetadata(any(), argThat(plan -> plan.basis().equals("DEFAULT")));
    }
    @Test void unauthorizedAgentNeverQueriesMetadata() {
        var workflow = workflow();
        when(scopes.resolve(any(), any(), any(), any(), any())).thenReturn(
            new SkillExecutionScopePort.EffectiveScope(List.of(), List.of(), List.of(), false, false));
        assertThatThrownBy(() -> workflow.execute(context("API 怎么用"))).isInstanceOf(SecurityException.class);
        verifyNoInteractions(source, enhancer);
    }
}
